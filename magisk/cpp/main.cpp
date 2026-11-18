#include <jni.h>
#include <cstring>
#include <string>
#include <thread>
#include <cerrno>
#include <cstdio>
#include <chrono>
#include <sys/stat.h>
#include <unistd.h>
#include "zygisk.hpp"
#include "duplicate_guard.h"
#include "companion_probe.h"
#include "hc_compat.h"

// Both original implementations retain their signatures and control files.
void hack_prepare(const char *, void *, size_t);
void hack_prepare(const char *, JavaVM *);
bool pcc_gps_ready();
// Read-only capability diagnostics (core_capabilities.tsv), single writer = this heartbeat.
bool pikmin_capabilities_publish(const char *game_data_dir, bool gps_ready, const char *version, int code);
static pcc::PathState path_state(const char *path, bool directory) {
    struct stat st{};
    if (lstat(path,&st)==0) return (directory?S_ISDIR(st.st_mode):S_ISREG(st.st_mode))?pcc::PathState::Present:pcc::PathState::Unknown;
    return errno==ENOENT?pcc::PathState::Missing:pcc::PathState::Unknown;
}
static void companion_guard(int fd) {
    using pcc::PathState;
    // preAppSpecialize has zygote privileges, not the companion's superuser namespace.
    const auto updates=path_state("/data/adb/modules_update",true);
    const bool readable=getuid()==0 && path_state("/data/adb",true)==PathState::Present &&
        path_state("/data/adb/modules",true)==PathState::Present && updates!=PathState::Unknown;
    const bool allowed=readable && pcc::allow_unified(
        path_state("/data/adb/modules/zygisk_pikmin_gps_copy",true),
        path_state("/data/adb/modules/zygisk_pikmin_gps_copy/disable",false),
        path_state("/data/adb/modules_update/zygisk_pikmin_gps_copy",true));
    pcc::send_guard_result(fd,allowed);
    const bool hc_clear=readable && path_state("/data/user/0/dev.ody.pikmincontrol.hccompat",true)==PathState::Missing;
    pcc::send_hc_guard_result(fd,hc_clear);
}

class ControlCenterModule final : public zygisk::ModuleBase {
    zygisk::Api *api{};
    JNIEnv *env{};
    JavaVM *vm{};
    std::string directory;
    bool safe{};
    bool hc_legacy_blocked{true};
public:
    void onLoad(zygisk::Api *a, JNIEnv *e) override {
        api=a; env=e; env->GetJavaVM(&vm);
    }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if (!args || !args->nice_name || !args->app_data_dir) return;
        const char *name=env->GetStringUTFChars(args->nice_name,nullptr);
        const char *data=env->GetStringUTFChars(args->app_data_dir,nullptr);
        if (name && data && !std::strcmp(name,"com.nianticlabs.pikmin")) directory=data;
        else api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
        if (name) env->ReleaseStringUTFChars(args->nice_name,name);
        if (data) env->ReleaseStringUTFChars(args->app_data_dir,data);
        if (!directory.empty()) {
            const int fd=api->connectCompanion();
            safe=pcc::read_guard_result(fd);
            hc_legacy_blocked=!pcc::read_hc_guard_result(fd);
            if(fd>=0)close(fd);
        }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (directory.empty() || !vm || !safe) return;
        pcc_hc_start(vm,directory.c_str(),hc_legacy_blocked);
        // Unified GPS only records its context here. Metadata resolution must
        // wait for Nectar's post-login game callback, not the library mapping.
        hack_prepare(directory.c_str(),vm);
        // automation creates its own detached worker, so provide process-lifetime storage.
        char *path=strdup(directory.c_str());
        if (path) hack_prepare(path,nullptr,0);
        std::thread([path=directory] {
            const std::string file=path+"/files/unified_runtime.tsv", tmp=file+".tmp";
            for (;;) {
                const auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::system_clock::now().time_since_epoch()).count();
                FILE *f=std::fopen(tmp.c_str(),"w");
                if (f) {
                    const bool ok=std::fprintf(f,"v1\t%lld\t%d\t1.21.1\t147\t%d\n",
                        static_cast<long long>(ms),getpid(),pcc_gps_ready()?1:0)>0;
                    const bool closed=std::fclose(f)==0;
                    if (ok&&closed) {chmod(tmp.c_str(),0644);rename(tmp.c_str(),file.c_str());}
                }
                // Same cadence, same thread; failures keep the previous file and are not logged per tick.
                pikmin_capabilities_publish(path.c_str(),pcc_gps_ready(),"1.21.1",147);
                pcc_hc_heartbeat(path.c_str());
                sleep(1);
            }
        }).detach();
    }
};
REGISTER_ZYGISK_MODULE(ControlCenterModule)
REGISTER_ZYGISK_COMPANION(companion_guard)

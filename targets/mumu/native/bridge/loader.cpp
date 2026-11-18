// NativeBridge callback layout/loading approach adapted from Perfare's
// Zygisk-Il2CppDumper (MIT, Copyright (c) 2020 Rikka). See LICENSE.reference.
// MuMu-only loader; the integrated ARM payload enables gameplay, never on phones.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <elf.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <sys/system_properties.h>
#include <cstdio>
#include <cerrno>
#include <chrono>
#include <cstring>
#include <string>
#include <thread>
#include <vector>
#include "zygisk.hpp"
#include "companion_probe.h"
#include "xdl.h"

struct BridgeCallbacks {
    uint32_t version;
    void *initialize;
    void *(*loadLibrary)(const char *, int);
    void *(*getTrampoline)(void *, const char *, const char *, uint32_t);
    void *isSupported, *getAppEnv, *isCompatibleWith, *getSignalHandler;
    void *unloadLibrary, *getError, *isPathSupported, *initAnonymousNamespace;
    void *createNamespace, *linkNamespaces;
    void *(*loadLibraryExt)(const char *, int, void *);
    void *getVendorNamespace;
    void *(*getExportedNamespace)(const char *);
};
static bool emulator_only() {
    char abi[PROP_VALUE_MAX]{},sdk[PROP_VALUE_MAX]{},bridge[PROP_VALUE_MAX]{};
    __system_property_get("ro.product.cpu.abi",abi);
    __system_property_get("ro.build.version.sdk",sdk);
    __system_property_get("ro.dalvik.vm.native.bridge",bridge);
    return !strcmp(abi,"x86_64") && !strcmp(sdk,"35") && !strcmp(bridge,"libnb.so");
}
static void log_stage(const char *stage) {
    __android_log_print(ANDROID_LOG_INFO, "PCCBridgeCanary", "pid=%d %s", getpid(), stage);
}
// Zygisk may place this module in a different linker namespace from ART.
// Resolve only these already-mapped x86_64 system images; XDL_DEFAULT never
// dlopens another copy or initializes a second NativeBridge state.
static void *mapped_symbol(const char *path,const char *symbol) {
    void *image=xdl_open(path,XDL_DEFAULT);
    if(!image)return nullptr;
    xdl_info_t info{};void *address=nullptr;
    if(xdl_info(image,XDL_DI_DLINFO,&info)==0&&info.dli_fname&&info.dli_fbase&&
            strcmp(info.dli_fname,path)==0){
        const auto *elf=static_cast<const Elf64_Ehdr *>(info.dli_fbase);
        if(memcmp(elf->e_ident,ELFMAG,SELFMAG)==0&&elf->e_ident[EI_CLASS]==ELFCLASS64&&elf->e_machine==EM_X86_64)
            address=xdl_sym(image,symbol,nullptr);
    }
    xdl_close(image);return address;
}
static bool missing(const char *path) {
    struct stat st{};
    return lstat(path, &st) != 0 && errno == ENOENT;
}
static void companion(int fd) {
    struct stat st{};
    // Isolation is stricter than production: no unified or legacy module, even disabled.
    const bool safe=getuid()==0 && emulator_only() && lstat("/data/adb/modules", &st)==0 && S_ISDIR(st.st_mode) &&
        missing("/data/adb/modules/pikmin_nectar_rpc") &&
        missing("/data/adb/modules/zygisk_pikmin_gps_copy") &&
        missing("/data/adb/modules_update/pikmin_nectar_rpc") &&
        missing("/data/adb/modules_update/zygisk_pikmin_gps_copy");
    pcc::send_guard_result(fd, safe);
}
static bool mapped_game() {
    FILE *f=fopen("/proc/self/maps", "r");
    if (!f) return false;
    char line[2048]; bool found=false;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, "libil2cpp.so")) { found=true; break; }
    }
    fclose(f); return found;
}
static long long epoch_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();
}
static void publish_loader(const std::string &directory,const char *phase,int result,long long token) {
    const std::string file=directory+"/files/emulator_bridge_loader.tsv",tmp=file+".tmp";
    FILE *f=fopen(tmp.c_str(),"w");
    if(!f)return;
    const bool ok=fprintf(f,"v2\t%lld\t%d\t%s\t%s\t%s\t%s\t%s\t%x\t%lld\n",
        epoch_ms(),getpid(),PCC_CANARY_STAGE,PCC_CANARY_GATE,PCC_CANARY_NAMESPACE,
        PCC_CANARY_API,phase,result,token)>0;
    const bool closed=fclose(f)==0;
    if(ok&&closed){chmod(tmp.c_str(),0600);rename(tmp.c_str(),file.c_str());}
}
static void terminal(const std::string &directory,const char *phase,int result,long long token) {
    // Bounded x64 diagnostic heartbeat, not an ARM or production-core proof.
    for(int i=0;i<90;++i){publish_loader(directory,phase,result,token);sleep(1);}
}
static bool triggered(const std::string &directory,long long token) {
    const std::string file=directory+"/files/emulator_bridge_trigger.tsv";
    const int fd=open(file.c_str(),O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0)return false;
    struct stat st{}; char data[128]{};
    const bool safe=fstat(fd,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==getuid()&&
        (st.st_mode&0022)==0&&st.st_size>0&&st.st_size<static_cast<off_t>(sizeof(data));
    const ssize_t n=safe?read(fd,data,sizeof(data)-1):-1; close(fd);
    int process=0; long long expected=0; char extra=0;
    return n>0&&sscanf(data,"v1 %d %lld %c",&process,&expected,&extra)==2&&
        process==getpid()&&expected==token;
}
#ifdef PCC_CANARY_DAILY_PAIR
#include "sealed_payload.h"
#endif
static void load_payload(JavaVM *vm, std::string directory, std::vector<unsigned char> bytes) {
    const long long token=epoch_ms();
#ifdef PCC_CANARY_NO_BRIDGE
    // Negative control: same x64 Zygisk lifecycle, no ARM payload load/calls.
    log_stage("loader-only-no-NativeBridge");
    terminal(directory,"loader-only",0,token);
    return;
#endif
    if(!strcmp(PCC_CANARY_GATE,"manual")){
        bool ready=false;
        for(int i=0;i<180;++i){
            publish_loader(directory,"waiting",0,token);
            if(triggered(directory,token)){ready=true;break;}
            sleep(1);
        }
        if(!ready){log_stage("manual-trigger-timeout");terminal(directory,"timeout",0,token);return;}
    }
    // Wait for the game's translated library, not an arbitrary fixed boot delay.
    int attempt=0;
    while (!mapped_game() && attempt++<60) sleep(1);
    if (!mapped_game()) { log_stage("game-map-timeout"); return; }
    auto cb=static_cast<BridgeCallbacks *>(mapped_symbol("/system/lib64/libnb.so","NativeBridgeItf"));
    if (!cb) { log_stage("libnb-not-loaded");terminal(directory,"libnb-not-loaded",0,token);return; }
    if (!cb || cb->version<3 || !cb->loadLibraryExt || !cb->getTrampoline) {
        log_stage("unsupported-callbacks");terminal(directory,"unsupported-callbacks",0,token);return;
    }
    __android_log_print(ANDROID_LOG_INFO, "PCCBridgeCanary", "pid=%d callbacks-v%u", getpid(), cb->version);
    constexpr const char *framework="/apex/com.android.art/lib64/libnativebridge.so";
    auto initialized=reinterpret_cast<bool (*)()>(mapped_symbol(framework,"NativeBridgeInitialized"));
    __android_log_print(ANDROID_LOG_INFO,"PCCBridgeCanary","pid=%d bridge-initialized=%d",getpid(),initialized?initialized():-1);
    void *ns=nullptr;
    if(!strcmp(PCC_CANARY_NAMESPACE,"legacy"))ns=reinterpret_cast<void *>(3);
    else {
        if(cb->version<5||!cb->getExportedNamespace){terminal(directory,"namespace-unavailable",0,token);return;}
        ns=cb->getExportedNamespace("default");
        if(!strcmp(PCC_CANARY_NAMESPACE,"created")){
            using Create=void *(*)(const char*,const char*,const char*,uint64_t,const char*,void*);
            auto create=reinterpret_cast<Create>(cb->createNamespace);
            // A dedicated namespace returned by the bridge, never an invented numeric handle.
            if(!create){terminal(directory,"namespace-unavailable",0,token);return;}
            ns=create("pcc-canary","/system/lib64/arm64","/system/lib64/arm64",0,nullptr,ns);
        }
        if(!ns){log_stage("namespace-unavailable");terminal(directory,"namespace-unavailable",0,token);return;}
    }
    __android_log_print(ANDROID_LOG_INFO,"PCCBridgeCanary","pid=%d namespace=%s handle=%p api=%s",getpid(),PCC_CANARY_NAMESPACE,ns,PCC_CANARY_API);
    auto load=cb->loadLibraryExt;
    auto trampoline=cb->getTrampoline;
    if(!strcmp(PCC_CANARY_API,"framework")){
        if(!initialized||!initialized()){terminal(directory,"bridge-not-ready",0,token);return;}
        load=reinterpret_cast<decltype(load)>(mapped_symbol(framework,"NativeBridgeLoadLibraryExt"));
        trampoline=reinterpret_cast<decltype(trampoline)>(mapped_symbol(framework,"NativeBridgeGetTrampoline"));
        if(!load||!trampoline){terminal(directory,"api-unavailable",0,token);return;}
    }
#ifdef PCC_CANARY_DAILY_PAIR
    const int fd=emu_payload::create_image(bytes.data(),bytes.size());
    if(fd<0){log_stage("sealed-image-failed");return;}
#else
    const int fd=memfd_create("pcc-arm-canary", MFD_CLOEXEC);
    if (fd<0) { log_stage("memfd-failed"); return; }
    if (ftruncate(fd, static_cast<off_t>(bytes.size()))!=0) { close(fd); return; }
    void *mem=mmap(nullptr, bytes.size(), PROT_WRITE, MAP_SHARED, fd, 0);
    if (mem==MAP_FAILED) { close(fd); return; }
    memcpy(mem, bytes.data(), bytes.size()); munmap(mem, bytes.size());
#endif
    char path[64]; snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    // The upstream anonymous-namespace token is emulator-specific and is being
    // tested here only. It is NOT a production compatibility guarantee.
    log_stage("calling-loadLibraryExt");
    void *handle=load(path, RTLD_NOW, ns);
    if (!handle) { log_stage("loadLibraryExt-failed"); close(fd); return; }
    if(!strcmp(PCC_CANARY_STAGE,"load")){terminal(directory,"loaded",0,token);return;}
    auto init=reinterpret_cast<jint (*)(JavaVM *, void *)>(trampoline(handle, "JNI_OnLoad", nullptr, 0));
    if (!init) { log_stage("trampoline-missing"); return; }
    if(!strcmp(PCC_CANARY_STAGE,"trampoline")){terminal(directory,"trampoline",0,token);return;}
    log_stage("calling-arm-JNI_OnLoad");
    JNIEnv *callEnv=nullptr;
    const bool wasAttached=vm->GetEnv(reinterpret_cast<void **>(&callEnv),JNI_VERSION_1_6)==JNI_OK;
    if(!wasAttached&&vm->AttachCurrentThread(&callEnv,nullptr)!=JNI_OK){
        terminal(directory,"jni-attach-failed",0,token);return;
    }
    const jint result=init(vm,nullptr);
    if(!wasAttached)vm->DetachCurrentThread();
    __android_log_print(ANDROID_LOG_INFO, "PCCBridgeCanary", "pid=%d JNI-result=%x", getpid(), result);
    terminal(directory,"entry",result,token);
    // Successful library handles/memfd remain alive for the process lifetime.
}
class Canary final : public zygisk::ModuleBase {
    zygisk::Api *api{}; JNIEnv *env{}; JavaVM *vm{};
    std::string directory; std::vector<unsigned char> bytes;
public:
    void onLoad(zygisk::Api *a, JNIEnv *e) override { api=a; env=e; env->GetJavaVM(&vm); }
    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        if(!emulator_only()){api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);return;}
        if (!args || !args->nice_name || !args->app_data_dir) { api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); return; }
        const char *name=env->GetStringUTFChars(args->nice_name, nullptr);
        const bool target=name && strcmp(name, "com.nianticlabs.pikmin")==0;
        if (name) env->ReleaseStringUTFChars(args->nice_name, name);
        if (!target) { api->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY); return; }
        const int guard=api->connectCompanion();
        const bool safe=pcc::read_guard_result(guard); if (guard>=0) close(guard);
        if (!safe) { log_stage("isolation-denied"); return; }
        const int dir=api->getModuleDir();
        const int fd=dir>=0?openat(dir, "payload-arm64.so", O_RDONLY|O_CLOEXEC|O_NOFOLLOW):-1;
        if (dir>=0) close(dir);
        struct stat st{};
        constexpr size_t maxPayload=
#ifdef PCC_CANARY_CORE
            8*1024*1024;
#else
            1024*1024;
#endif
        if (fd<0 || fstat(fd, &st)!=0 || !S_ISREG(st.st_mode) || st.st_size<sizeof(Elf64_Ehdr) || st.st_size>maxPayload) {
            if (fd>=0) close(fd); log_stage("payload-invalid"); return;
        }
        bytes.resize(static_cast<size_t>(st.st_size)); size_t offset=0;
        while (offset<bytes.size()) {
            const ssize_t n=read(fd, bytes.data()+offset, bytes.size()-offset);
            if (n<=0) break; offset+=static_cast<size_t>(n);
        }
        close(fd);
        const auto *elf=reinterpret_cast<const Elf64_Ehdr *>(bytes.data());
        if (offset!=bytes.size() || memcmp(elf->e_ident, ELFMAG, SELFMAG) || elf->e_ident[EI_CLASS]!=ELFCLASS64 || elf->e_machine!=EM_AARCH64) {
            bytes.clear(); log_stage("payload-not-arm64"); return;
        }
        const char *data=env->GetStringUTFChars(args->app_data_dir, nullptr);
        if (data) { directory=data; env->ReleaseStringUTFChars(args->app_data_dir, data); }
    }
    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!directory.empty() && !bytes.empty() && vm) {
            log_stage("x64-loader-start");
            std::thread(load_payload, vm, directory, std::move(bytes)).detach();
        }
    }
};
REGISTER_ZYGISK_MODULE(Canary)
REGISTER_ZYGISK_COMPANION(companion)

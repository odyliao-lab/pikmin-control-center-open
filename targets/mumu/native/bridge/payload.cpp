// Read-only ARM64 execution/thread/JNI/IL2CPP canary. No hooks or game RPC.
#include <jni.h>
#include <android/log.h>
#include <elf.h>
#include <unistd.h>
#include <sys/stat.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include "xdl.h"
#ifdef PCC_CANARY_SELF_HOOK
#include "And64InlineHook.hpp"

using Fixture=int (*)(int);
static Fixture originalFixture=nullptr;
// This function is owned by the canary. Never hook a game function here.
__attribute__((noinline,optnone)) static int fixture(int input) { return input+7; }
__attribute__((noinline)) static int replacement(int input) { return originalFixture?originalFixture(input)+100:-999; }
#endif

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    if (!vm || reserved) return JNI_ERR;
    // Deliberately narrow diagnostic: validated MuMu user0 / target app only.
    // No custom context is smuggled through JNI_OnLoad's reserved parameter.
    const std::string directory="/data/user/0/com.nianticlabs.pikmin";
    struct stat owner{};
    if(getuid()/100000!=0||lstat(directory.c_str(),&owner)!=0||!S_ISDIR(owner.st_mode)||owner.st_uid!=getuid())return JNI_ERR;
    std::thread([vm, directory] {
        JNIEnv *env=nullptr;
        const bool attached=vm->AttachCurrentThread(&env, nullptr)==JNI_OK;
        // JNI round trip tests the VM interface from translated ARM code.
        jint javaVersion=attached?env->GetVersion():0;
#ifdef PCC_CANARY_SELF_HOOK
        Fixture volatile callFixture=fixture;
        const int before=callFixture(7); // Warm the translator's existing code cache.
        __android_log_print(ANDROID_LOG_INFO,"PCCBridgeCanary","pid=%d self-hook-before=%d",getpid(),before);
        void *trampoline=nullptr;
        A64HookFunction(reinterpret_cast<void *>(fixture),reinterpret_cast<void *>(replacement),&trampoline);
        originalFixture=reinterpret_cast<Fixture>(trampoline);
        const int original=originalFixture?originalFixture(7):-999;
        const int after=callFixture(7);
        const bool selfHook=before==14 && original==14 && after==114;
        __android_log_print(ANDROID_LOG_INFO,"PCCBridgeCanary","pid=%d self-hook-after=%d original=%d pass=%d",getpid(),after,original,selfHook?1:0);
#else
        const int before=0, original=0, after=0;
        const bool selfHook=false;
        __android_log_print(ANDROID_LOG_INFO,"PCCBridgeCanary","pid=%d self-hook-disabled (read-only build)",getpid());
#endif
        __android_log_print(ANDROID_LOG_INFO, "PCCBridgeCanary", "pid=%d arm-xdl-open", getpid());
        void *library=xdl_open("libil2cpp.so", XDL_DEFAULT);
        using AssembliesGet=const void **(*)(void *, size_t *);
        auto assembliesGet=library?reinterpret_cast<AssembliesGet>(xdl_sym(library,"il2cpp_domain_get_assemblies",nullptr)):nullptr;
        xdl_info_t info{};
        const bool infoOk=library && xdl_info(library, XDL_DI_DLINFO, &info)==0 && info.dli_fbase;
        const unsigned machine=infoOk?static_cast<const Elf64_Ehdr *>(info.dli_fbase)->e_machine:0;
        // Verified Pikmin154 accessor: ignores x0, reads an existing vector,
        // stores its count through x1, returns. Refuse unknown instruction sets.
        // domain_get is NOT a pure read: it may lazily allocate before GC init.
        const uint32_t accessorCode[]{0xf004ff88,0x9110c108,0xa9402500,0xcb000128,
            0x9343fd08,0xf9000028,0xd65f03c0};
        const bool accessor=assembliesGet && !memcmp(reinterpret_cast<const void *>(assembliesGet),accessorCode,sizeof(accessorCode));
        const bool symbols=machine==EM_AARCH64 && assembliesGet && accessor;
        __android_log_print(ANDROID_LOG_INFO, "PCCBridgeCanary", "pid=%d arm-symbols=%d machine=%u", getpid(), symbols?1:0, machine);
        const std::string file=directory+"/files/emulator_bridge_canary.tsv", tmp=file+".tmp";
        for (int i=0; i<60; ++i) {
            size_t assemblyCount=0;
            // This exact verified accessor ignores the domain argument. Do not
            // call domain_get or thread_attach during startup at all.
            if (symbols) assembliesGet(nullptr,&assemblyCount);
            const auto now=std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
            FILE *f=fopen(tmp.c_str(), "w");
            if (f) {
                const bool ok=fprintf(f, "v5\t%lld\t%d\tarm64-v8a\t%d\t%x\t%d\t%d\t%u\t%d\t%zu\t%d\t%d\t%d\t%d\n",
                    static_cast<long long>(now), getpid(), attached?1:0, javaVersion, i,
                    symbols?1:0,machine,accessor?1:0,assemblyCount,before,after,original,selfHook?1:0)>0;
                const bool closed=fclose(f)==0;
                if (ok && closed) { chmod(tmp.c_str(), 0600); rename(tmp.c_str(), file.c_str()); }
            }
            sleep(1);
        }
        if (library) xdl_close(library);
        if (attached) vm->DetachCurrentThread();
    }).detach();
    return JNI_VERSION_1_6;
}

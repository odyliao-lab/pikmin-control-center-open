// MuMu-only late-load probe. Exactly one verified Update hook; no game RPC,
// allocations/metadata calls on the callback, or control-file consumption.
#include <jni.h>
#include <elf.h>
#include <sys/stat.h>
#include <sys/mman.h>
#include <unistd.h>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <thread>
#include "xdl.h"
#include "And64InlineHook.hpp"

namespace {
using Update=void (*)(void*,void*);
std::atomic<Update> original{};
std::atomic<unsigned long long> entered{}, completed{};
std::atomic<int> callbackTid{};
void observe(void *self,void *method) {
    auto call=original.load(std::memory_order_acquire);
    entered.fetch_add(1,std::memory_order_relaxed);
    callbackTid.store(gettid(),std::memory_order_relaxed);
    call(self,method);
    completed.fetch_add(1,std::memory_order_relaxed);
}
long long milliseconds(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved) {
    JNIEnv *caller=nullptr;
    if(!vm||reserved||vm->GetEnv(reinterpret_cast<void**>(&caller),JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    const char *directory="/data/user/0/com.nianticlabs.pikmin";
    struct stat owner{};
    if(getuid()/100000!=0||lstat(directory,&owner)||!S_ISDIR(owner.st_mode)||owner.st_uid!=getuid())return JNI_ERR;
    std::thread([vm] {
        JNIEnv *env=nullptr;
        if(vm->AttachCurrentThread(&env,nullptr)!=JNI_OK)return;
        const int version=env->GetVersion();
        void *library=xdl_open("libil2cpp.so",XDL_DEFAULT);
        xdl_info_t info{};struct stat size{};
        bool verified=library&&xdl_info(library,XDL_DI_DLINFO,&info)==0&&info.dli_fbase&&info.dli_fname&&
            static_cast<Elf64_Ehdr*>(info.dli_fbase)->e_machine==EM_AARCH64&&
            !stat(info.dli_fname,&size)&&size.st_size==255014640;
        // All instructions copied from the actual 154.0 ARM binary. Check the
        // whole displaced prefix, not just its common first instruction.
        constexpr uint32_t prefix[]{0xf81a0ffe,0xa9016ffc,0xa90267fa,0xa9035ff8,0xa90457f6,0xa9054ff4};
        auto entry=verified?static_cast<unsigned char*>(info.dli_fbase)+0x5A78FF4:nullptr;
        verified=entry&&!memcmp(entry,prefix,sizeof(prefix));
        void *trampoline=nullptr;
        if(verified){
            void *storage=mmap(nullptr,4096,PROT_READ|PROT_WRITE|PROT_EXEC,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
            if(storage!=MAP_FAILED){
                // This hook API fills/flushes trampoline before patching entry.
                // Publish its fixed address first: no callback spins waiting
                // for the installing thread while the translator holds locks.
                original.store(reinterpret_cast<Update>(storage),std::memory_order_release);
                trampoline=A64HookFunctionV(entry,reinterpret_cast<void*>(observe),storage,4096);
                // Keep storage even on an ambiguous hook failure; no unsafe
                // unmap of code another thread might already have entered.
            }
        }
        const char *file="/data/user/0/com.nianticlabs.pikmin/files/emulator_bridge_tick.tsv";
        const char *tmp="/data/user/0/com.nianticlabs.pikmin/files/emulator_bridge_tick.tsv.tmp";
        for(int sample=0;sample<120;++sample){
            FILE *f=fopen(tmp,"w");
            if(f){
                const bool ok=fprintf(f,"v1\t%lld\t%d\tarm64-v8a\t%x\t%d\t%d\t%llu\t%llu\t%d\t%d\n",
                    milliseconds(),getpid(),version,verified?1:0,trampoline?1:0,
                    entered.load(),completed.load(),callbackTid.load(),sample)>0;
                const bool closed=fclose(f)==0;
                if(ok&&closed){chmod(tmp,0600);rename(tmp,file);}
            }
            sleep(1);
        }
        vm->DetachCurrentThread();
        // No unload/unhook: trampoline and code must outlive the game process.
    }).detach();
    return caller->GetVersion();
}

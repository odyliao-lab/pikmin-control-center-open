#include "hc_engine.h"
#include <android/api-level.h>
#include <lsplant.hpp>
#include <dobby.h>
#include <xdl.h>
#include <mutex>
#include <string>
#include <thread>
#include <atomic>
namespace pcc::hc {
static std::mutex init_mutex;
static bool attempted{};
static std::atomic<bool> ready{false};
static bool initialize_attached(JNIEnv *env) {
    void *art=xdl_open("libart.so",XDL_DEFAULT);
    if (!art) return false;
    lsplant::InitInfo info{
        .inline_hooker=[](void *target,void *replacement)->void* {
            void *original=nullptr;
            return DobbyHook(target,replacement,&original)==0 ? original : nullptr;
        },
        .inline_unhooker=[](void *target){return DobbyDestroy(target)==0;},
        .art_symbol_resolver=[art](std::string_view name)->void* {
            std::string symbol(name);
            void *result=xdl_dsym(art,symbol.c_str(),nullptr);
            return result ? result : xdl_sym(art,symbol.c_str(),nullptr);
        },
        .art_symbol_prefix_resolver=[art](std::string_view name)->void* {
            std::string prefix(name);return xdl_dsym_prefix(art,prefix.c_str());
        },
        .generated_class_name="PccHcHook_",
        .generated_source_name="PCC-HC"
    };
    // The resolver's handle must outlive LSPlant initialization and its callbacks.
    return lsplant::Init(env,info);
}
bool initialize(JNIEnv *env) {
    const int sdk=android_get_device_api_level();
    if (sdk<28 || sdk>33) return false;
    std::lock_guard lock(init_mutex);
    if (attempted) return ready.load();
    attempted=true;
    JavaVM *vm=nullptr;if(env->GetJavaVM(&vm)!=JNI_OK||!vm)return false;
    bool initialized=false;
    // ART initialization needs a JNI context without an untrusted Java caller frame.
    // Do this on our own attached native thread; no global hidden-API exemptions,
    // LSPosed services, health grants or SDK spoofing are used.
    std::thread bootstrap([vm,&initialized]{
        JNIEnv *native_env=nullptr;
        if(vm->AttachCurrentThread(&native_env,nullptr)!=JNI_OK)return;
        initialized=initialize_attached(native_env);
        if(native_env->ExceptionCheck())native_env->ExceptionClear();
        vm->DetachCurrentThread();
    });
    bootstrap.join();ready.store(initialized);return initialized;
}
jobject hook(JNIEnv *env,jobject method,jobject owner,jobject callback) {
    return ready ? lsplant::Hook(env,method,owner,callback) : nullptr;
}
bool deoptimize(JNIEnv *env,jobject method) {return ready && lsplant::Deoptimize(env,method);}
}

// Minimal standard cross-ABI JNI probe: no worker, xDL, or game API.
#include <jni.h>
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env=nullptr;
    if(!vm||reserved||vm->GetEnv(reinterpret_cast<void **>(&env),JNI_VERSION_1_6)!=JNI_OK)return JNI_ERR;
    return env->GetVersion();
}

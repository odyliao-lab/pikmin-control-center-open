#pragma once
#include <jni.h>
namespace pcc::hc {
bool initialize(JNIEnv *env);
jobject hook(JNIEnv *env, jobject method, jobject owner, jobject callback);
bool deoptimize(JNIEnv *env, jobject method);
}

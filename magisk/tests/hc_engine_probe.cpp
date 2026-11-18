#include "hc_engine.h"
extern "C" JNIEXPORT jboolean JNICALL Java_dev_ody_pikmincontrol_validation_HcEngineProbe_initialize(JNIEnv *e,jclass){return pcc::hc::initialize(e);}
extern "C" JNIEXPORT jobject JNICALL Java_dev_ody_pikmincontrol_validation_HcEngineProbe_hook(JNIEnv *e,jclass,jobject target,jobject owner,jobject callback){return pcc::hc::hook(e,target,owner,callback);}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_ody_pikmincontrol_validation_HcEngineProbe_deopt(JNIEnv *e,jclass,jobject target){return pcc::hc::deoptimize(e,target);}

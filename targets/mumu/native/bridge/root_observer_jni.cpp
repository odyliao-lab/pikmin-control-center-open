#include "root_observer_lease.h"

// Product observation entry points only. Deliberately excludes fixture probes
// and all gameplay/owner/GPS APIs. Root launcher authenticates these bytes.
namespace {
emu_payload::RootObserverLease root_observer_lease;
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_ody_pccbridge_RootApkPublisher_publish(JNIEnv *env,jclass,jobject pm,jint pid){
    if(!root_observer_lease.valid())return false;
    const int dir=open("/data/local/tmp/pcc-emulator-apk-proof",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(dir<0)return false;
    const bool ok=emu_payload::publish_root_apk_observation(env,pm,dir,pid);
    const bool closed=close(dir)==0;return ok&&closed&&root_observer_lease.valid();
}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_ody_pccbridge_RootApkPublisher_acquire(JNIEnv *,jclass){return root_observer_lease.acquire();}
extern "C" JNIEXPORT jboolean JNICALL Java_dev_ody_pccbridge_RootApkPublisher_release(JNIEnv *,jclass){return root_observer_lease.release();}

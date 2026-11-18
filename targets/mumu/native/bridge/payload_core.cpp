// Emulator-only integrated payload. Never compiled into the phone/core asset.
#include <jni.h>
#include <elf.h>
#include <sys/stat.h>
#include <unistd.h>
#include <chrono>
#include <cstring>
#include <cstdio>
#include <thread>
#include "xdl.h"
void hack_prepare(const char*,JavaVM*);
void hack_prepare(const char*,void*,size_t);
bool pcc_gps_ready();
bool pikmin_capabilities_publish(const char*,bool,const char*,int);
#ifdef PCC_CANARY_DAILY_PAIR
#include "loaded_payload.h"
#include "installed_apk.h"
#include "root_apk_observation.h"
#include "loaded_core_pair.h"
#include "pair_observation_worker.h"
#include "gps_observation_worker.h"
#include "gps_observation_snapshot.h"
#include "planting_command_paired_owner.h"
#include <new>
// These objects intentionally live until OS process teardown. Detached
// observation must never race C++ static destructors or destroy its image.
// Payload unload is unsupported; the existing JNI/load lifetime is retained.
static auto *loaded_payload_image=new(std::nothrow) emu_payload::LoadedImage;
static auto *pair_observation_worker=new(std::nothrow) emu_payload::PairObservationWorker;
static auto *gps_observation_worker=new(std::nothrow) emu_gps_work::GpsObservationWorker;
// Independent byte-hashing worker only: maps I/O must not run on Map Update.
// These observations are not APK equivalence or an ownership grant.
bool pcc_emulator_loaded_image_ready(){return loaded_payload_image&&loaded_payload_image->recheck();}
int pcc_emulator_loaded_image_fd(){return loaded_payload_image?loaded_payload_image->duplicate():-1;}
bool pcc_emulator_loaded_image_sha256(std::string &out){return loaded_payload_image&&loaded_payload_image->fingerprint(out);}
bool pcc_emulator_installed_apk(JNIEnv *env,jobject pm,emu_payload::InstalledApk &out){return emu_payload::read_installed_apk(env,pm,out);}
bool pcc_emulator_root_apk_observation(emu_payload::RootApkObservation &out){return emu_payload::read_root_apk_observation(out);}
bool pcc_emulator_loaded_core_pair(emu_payload::LoadedCorePair &out){return pair_observation_worker&&pair_observation_worker->read(out);}
extern "C" void pcc_emulator_stop_pair_observation(){
    if(pair_observation_worker)pair_observation_worker->stop();
    if(gps_observation_worker)gps_observation_worker->stop();
}
extern "C" bool pcc_emulator_read_gps_fence_snapshot(int *pid,uint64_t *ticks,char *boot,char *nonce,
    uint64_t *device,uint64_t *inode,uint64_t *sampled,uint64_t *until){
    emu_gps_work::FenceObservation v;
    if(!pid||!ticks||!boot||!nonce||!device||!inode||!sampled||!until||!gps_observation_worker||
       !gps_observation_worker->read(v)||v.boot.size()!=36||v.nonce.size()!=36)return false;
    *pid=v.pid;*ticks=v.ticks;memcpy(boot,v.boot.c_str(),37);memcpy(nonce,v.nonce.c_str(),37);
    *device=v.device;*inode=v.inode;*sampled=v.sampled;*until=v.until;return true;
}
extern "C" bool pcc_emulator_read_pair_snapshot(int *pid,int *uid,char *boot,char *core,char *apk,int64_t *sampled,int64_t *until){
    emu_payload::LoadedCorePair pair;
    if(!pid||!uid||!boot||!core||!apk||!sampled||!until||!pcc_emulator_loaded_core_pair(pair)||
       pair.installed.process.boot.size()!=36||pair.loaded_sha.size()!=64||pair.installed.apk.sha.size()!=64)return false;
    *pid=pair.installed.process.pid;*uid=pair.installed.process.uid;
    memcpy(boot,pair.installed.process.boot.c_str(),37);memcpy(core,pair.loaded_sha.c_str(),65);memcpy(apk,pair.installed.apk.sha.c_str(),65);
    *sampled=pair.sampled;*until=pair.installed.until;return true;
}
#endif

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm,void *reserved){
    JNIEnv *env=nullptr;
    const char *dir="/data/user/0/com.nianticlabs.pikmin";
    struct stat owner{};
    if(!vm||reserved||vm->GetEnv(reinterpret_cast<void**>(&env),JNI_VERSION_1_6)!=JNI_OK||
        getuid()/100000!=0||lstat(dir,&owner)||!S_ISDIR(owner.st_mode)||owner.st_uid!=getuid())return JNI_ERR;
    void *handle=xdl_open("libil2cpp.so",XDL_DEFAULT);
    xdl_info_t info{};struct stat size{};
    if(!handle||xdl_info(handle,XDL_DI_DLINFO,&info)||!info.dli_fbase||!info.dli_fname||
        static_cast<Elf64_Ehdr*>(info.dli_fbase)->e_machine!=EM_AARCH64||
        stat(info.dli_fname,&size)||size.st_size!=257043464)return JNI_ERR;
    struct Entry{size_t rva;uint32_t code[6];};
    constexpr Entry entries[]{
        {0x5b87ed0,{0xf81d0ffe,0xa90157f6,0xa9024ff4,0xb4000301,0xaa0103f3,0xaa0003f4}},
        {0x5b89bc4,{0xf81a0ffe,0xa9016ffc,0xa90267fa,0xa9035ff8,0xa90457f6,0xa9054ff4}},
        {0x5b9a9c4,{0xa9bd5ffe,0xa90157f6,0xa9024ff4,0xf9408c14,0xaa1f03e1,0xaa0003f3}},
        {0x73f3114,{0xf81c0ffe,0xa9015ff8,0xa90257f6,0xa9034ff4,0xb0040a74,0xaa0003f3}},
        {0x73f2a10,{0xd10203ff,0xf90023fe,0xa9055ff8,0xa90657f6,0xa9074ff4,0xd0040a75}},
        {0x6088f44,{0xa9be57fe,0xa9014ff4,0xf004a574,0xaa0003f3,0x396eca88,0x37000108}},
        {0x6089fcc,{0xd10243ff,0xa90567fe,0xa9065ff8,0xa90757f6,0xa9084ff4,0xd004a576}},
        {0x608a808,{0xf81d0ffe,0xa90157f6,0xa9024ff4,0xb004a575,0xaa0103f4,0xaa0003f3}},
        {0x7327ef8,{0xf81c0ffe,0xa9015ff8,0xa90257f6,0xa9034ff4,0xb00410d4,0xaa0003f3}}
    };
    for(const auto &entry:entries)
        if(memcmp(static_cast<unsigned char*>(info.dli_fbase)+entry.rva,entry.code,sizeof(entry.code)))return JNI_ERR;
#ifdef PCC_CANARY_DAILY_PAIR
    // Failure keeps daily pair proof unavailable; never substitutes a module
    // path/version report or turns this into automatic load/command authority.
    if(loaded_payload_image)loaded_payload_image->capture(reinterpret_cast<void *>(&JNI_OnLoad));
#endif
    hack_prepare(dir,vm);
    hack_prepare(dir,nullptr,0);
#ifdef PCC_CANARY_DAILY_PAIR
    // Observations only. Missing Root producer leaves cache unavailable and
    // cannot connect OwnerEnvironment or authorize gameplay.
    if(pair_observation_worker&&loaded_payload_image&&loaded_payload_image->recheck())pair_observation_worker->start(*loaded_payload_image);
    if(gps_observation_worker)gps_observation_worker->start();
#endif
    std::thread([]{
        const char *tmp="/data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv.tmp";
        const char *file="/data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv";
        for(;;){
            const auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
            FILE *f=fopen(tmp,"w");
            if(f){bool ok=fprintf(f,"v1\t%lld\t%d\t0.20.3\t900178\t%d\n",static_cast<long long>(ms),getpid(),pcc_gps_ready()?1:0)>0;
                bool closed=fclose(f)==0;if(ok&&closed){chmod(tmp,0644);rename(tmp,file);}}
            pikmin_capabilities_publish("/data/user/0/com.nianticlabs.pikmin",pcc_gps_ready(),"0.20.3",900178);
            sleep(1);
        }
    }).detach();
    return env->GetVersion();
}

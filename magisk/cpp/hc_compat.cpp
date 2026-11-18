#include "hc_compat.h"
#include "hc_engine.h"
#include "hc_owner_policy.h"
#include "hc_dex.inc"
#include <android/api-level.h>
#include <array>
#include <string>
#include <mutex>
#include <thread>
#include <unistd.h>
#include <sys/stat.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
namespace {
std::mutex status_mutex;
std::array<std::string,7> status{"waiting-app","unknown","unknown","unknown","unknown","none","0"};
int sdk{};
bool primary{};
bool requested() {
    int fd=open("/data/local/tmp/pikmin-hc-compat.txt",O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC);if(fd<0)return false;
    struct stat st{};if(fstat(fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=0||(st.st_mode&0022)!=0||st.st_size>8){close(fd);return false;}
    char b[9]{};ssize_t n=read(fd,b,8);close(fd);
    return (n==2&&!std::memcmp(b,"on",2))||(n==3&&!std::memcmp(b,"on\n",3));
}
bool clear(JNIEnv *e){if(!e->ExceptionCheck())return false;e->ExceptionClear();return true;}
void state(const char *value){std::lock_guard lock(status_mutex);status[0]=value;}
jboolean init(JNIEnv *e,jclass){return pcc::hc::initialize(e);}
jobject hook(JNIEnv *e,jclass,jobject target,jobject owner,jobject callback){return pcc::hc::hook(e,target,owner,callback);}
jboolean deopt(JNIEnv *e,jclass,jobject method){return pcc::hc::deoptimize(e,method);}
void report(JNIEnv *e,jclass,jstring phase,jstring provider,jstring feature,jstring fg,jstring bg,jstring query,jlong at) {
    std::array<jstring,6> input{phase,provider,feature,fg,bg,query};
    std::array<std::string,7> next;
    for(size_t i=0;i<input.size();i++){
        if(!input[i])return;const char *v=e->GetStringUTFChars(input[i],nullptr);if(!v)return;
        next[i]=v;e->ReleaseStringUTFChars(input[i],v);
        if(next[i].empty()||next[i].size()>48||next[i].find_first_not_of("abcdefghijklmnopqrstuvwxyz-")!=std::string::npos)return;
    }
    next[6]=std::to_string(at>0?at:0);std::lock_guard lock(status_mutex);status=next;
}
void worker(JavaVM *vm,bool legacy) {
    JNIEnv *e=nullptr;if(vm->AttachCurrentThread(&e,nullptr)!=JNI_OK){state("jni-failed");return;}
    bool done=false;
    for(int attempt=0;attempt<60&&!done;attempt++){
        if(e->PushLocalFrame(32)!=JNI_OK)break;
        jclass activity=e->FindClass("android/app/ActivityThread");
        jmethodID current=activity?e->GetStaticMethodID(activity,"currentApplication","()Landroid/app/Application;"):nullptr;
        jobject app=current?e->CallStaticObjectMethod(activity,current):nullptr;
        if(!clear(e)&&app) {
            jclass context=e->FindClass("android/content/Context");
            jmethodID getLoader=context?e->GetMethodID(context,"getClassLoader","()Ljava/lang/ClassLoader;"):nullptr;
            jobject parent=getLoader?e->CallObjectMethod(app,getLoader):nullptr;
            jclass dex=e->FindClass("dalvik/system/InMemoryDexClassLoader");
            jmethodID ctor=dex?e->GetMethodID(dex,"<init>","(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V"):nullptr;
            jobject bytes=e->NewDirectByteBuffer(const_cast<unsigned char*>(pcc_hc_dex),sizeof(pcc_hc_dex));
            jobject loader=ctor&&parent&&bytes?e->NewObject(dex,ctor,bytes,parent):nullptr;
            jclass cl=e->FindClass("java/lang/ClassLoader");
            jmethodID load=cl?e->GetMethodID(cl,"loadClass","(Ljava/lang/String;)Ljava/lang/Class;"):nullptr;
            jstring name=e->NewStringUTF("dev.ody.pikmincontrol.hc.CoreHcBridge");
            auto bridge=static_cast<jclass>(loader&&load?e->CallObjectMethod(loader,load,name):nullptr);
            if(!clear(e)&&bridge){
                JNINativeMethod natives[]{
                    {const_cast<char*>("nativeInit"),const_cast<char*>("()Z"),reinterpret_cast<void*>(init)},
                    {const_cast<char*>("nativeHook"),const_cast<char*>("(Ljava/lang/reflect/Method;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;"),reinterpret_cast<void*>(hook)},
                    {const_cast<char*>("nativeDeopt"),const_cast<char*>("(Ljava/lang/reflect/Method;)Z"),reinterpret_cast<void*>(deopt)},
                    {const_cast<char*>("nativeReport"),const_cast<char*>("(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;J)V"),reinterpret_cast<void*>(report)}
                };
                if(e->RegisterNatives(bridge,natives,4)==JNI_OK){
                    jmethodID begin=e->GetStaticMethodID(bridge,"begin","(Landroid/content/Context;Z)V");
                    if(begin)e->CallStaticVoidMethod(bridge,begin,app,legacy?JNI_TRUE:JNI_FALSE);
                    done=begin&&!clear(e);
                }
            }
            if(!done){clear(e);state("bridge-failed");e->PopLocalFrame(nullptr);break;}
        }
        clear(e);e->PopLocalFrame(nullptr);if(!done)sleep(1);
    }
    if(!done)state("bridge-failed");vm->DetachCurrentThread();
}
}
void pcc_hc_start(JavaVM *vm,const char *directory,bool legacy) {
    sdk=android_get_device_api_level();
    primary=directory && pcc::hc::primary_owner(directory,getuid());
    if(!primary){state("unsupported-user");return;}
    if(sdk>=34){state("not-needed");return;}
    if(sdk<28){state("unsupported-sdk");return;}
    if(!vm){state("jni-failed");return;}
    std::thread(worker,vm,legacy).detach();
}
void pcc_hc_heartbeat(const char *directory){
    if(!primary)return;
    std::array<std::string,7> snapshot;{std::lock_guard lock(status_mutex);snapshot=status;}
    const auto now=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    const std::string path=std::string(directory)+"/files/hc_compat_status.tsv",tmp=path+".tmp";
    FILE *out=std::fopen(tmp.c_str(),"w");if(!out)return;
    const bool ok=std::fprintf(out,"v1\t%lld\t%d\t%d\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n",static_cast<long long>(now),getpid(),sdk,snapshot[0].c_str(),sdk<=33&&requested()?"on":"off",snapshot[1].c_str(),snapshot[2].c_str(),snapshot[3].c_str(),snapshot[4].c_str(),snapshot[5].c_str(),snapshot[6].c_str())>0;
    const bool closed=std::fclose(out)==0;if(ok&&closed){chmod(tmp.c_str(),0644);rename(tmp.c_str(),path.c_str());}
}

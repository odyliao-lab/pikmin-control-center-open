#pragma once
#include "payload_sha256.h"
#include <jni.h>
#include <vector>

namespace emu_payload {
// MuMu flavor only. Read-only observations, never a pair/owner grant. Caller
// supplies a real Android PackageManager, on an off-Update worker. Binder calls
// cannot be cancelled here: expired/blocked observations must not be published.
// Same-UID isolated MuMu testing denied this package to the game UID. Do not
// assume this is a usable game-side OwnerEnvironment: the Root observer needs
// an independently authenticated/protected handoff; no visibility bypass or
// version/report-only fallback is permitted.
struct InstalledApk {
    int uid{};int64_t code{},updated{};std::string path,sha,signer;
};
namespace apk_detail {
constexpr char package[]="dev.ody.pikmincontrol.mumu";
constexpr char signer[]="47457c8438477d9d187a900dc11792871cb82a659b76cd0d9cc7b57300933de2";
struct LocalFrame {
    JNIEnv *env;bool held{};
    explicit LocalFrame(JNIEnv *e):env(e){if(e&&!e->ExceptionCheck()){held=e->PushLocalFrame(64)==JNI_OK;if(e->ExceptionCheck()){e->ExceptionClear();held=false;}}}
    ~LocalFrame(){if(held)env->PopLocalFrame(nullptr);}
};
struct Api {
    JNIEnv *env;
    bool ok(){if(env->ExceptionCheck()){env->ExceptionClear();return false;}return true;}
    jclass type(const char *name){auto c=env->FindClass(name);return ok()?c:nullptr;}
    jmethodID method(jclass c,const char *name,const char *signature){if(!c)return nullptr;auto m=env->GetMethodID(c,name,signature);return ok()?m:nullptr;}
    jfieldID field(jclass c,const char *name,const char *signature){if(!c)return nullptr;auto f=env->GetFieldID(c,name,signature);return ok()?f:nullptr;}
    bool text(jstring value,std::string &out,size_t limit){
        if(!value)return false;const auto length=env->GetStringUTFLength(value);if(!ok()||length<=0||static_cast<size_t>(length)>limit)return false;
        const auto *bytes=env->GetStringUTFChars(value,nullptr);if(!ok()||!bytes)return false;
        std::string result(bytes,length);env->ReleaseStringUTFChars(value,bytes);if(!ok())return false;
        for(unsigned char c:result)if(c<33||c>126)return false;out=result;return true;
    }
};
inline bool source_path(const std::string &path){
    if(path.size()>1024||!path.starts_with("/data/app/")||!path.ends_with("/base.apk"))return false;
    unsigned components=0;size_t start=1;
    for(size_t i=1;i<=path.size();++i){
        if(i<path.size()&&path[i]!='/'){
            const unsigned char c=path[i];if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='+'||c=='-'||c=='_'||c=='='||c=='.'||c=='~'))return false;continue;
        }
        const auto part=path.substr(start,i-start);if(part.empty()||part=="."||part=="..")return false;
        if(++components==4&&!part.starts_with("dev.ody.pikmincontrol.mumu-"))return false;
        start=i+1;
    }
    return components==5;
}
inline bool package_snapshot(JNIEnv *env,jobject pm,InstalledApk &out){
    LocalFrame frame(env);if(!frame.held||!pm)return false;Api api{env};
    const auto pm_class=api.type("android/content/pm/PackageManager");
    const auto pi_class=api.type("android/content/pm/PackageInfo");
    const auto ai_class=api.type("android/content/pm/ApplicationInfo");
    const auto si_class=api.type("android/content/pm/SigningInfo");
    const auto sig_class=api.type("android/content/pm/Signature");
    if(!pm_class||!pi_class||!ai_class||!si_class||!sig_class)return false;
    const bool instance=env->IsInstanceOf(pm,pm_class);if(!api.ok()||!instance)return false;
    const auto lookup=api.method(pm_class,"getPackageInfo","(Ljava/lang/String;I)Landroid/content/pm/PackageInfo;");
    const auto code=api.method(pi_class,"getLongVersionCode","()J");
    const auto multiple=api.method(si_class,"hasMultipleSigners","()Z");
    const auto signatures=api.method(si_class,"getApkContentsSigners","()[Landroid/content/pm/Signature;");
    const auto signature_bytes=api.method(sig_class,"toByteArray","()[B");
    const auto name=api.field(pi_class,"packageName","Ljava/lang/String;");
    const auto application=api.field(pi_class,"applicationInfo","Landroid/content/pm/ApplicationInfo;");
    const auto signing=api.field(pi_class,"signingInfo","Landroid/content/pm/SigningInfo;");
    const auto updated=api.field(pi_class,"lastUpdateTime","J");
    const auto uid=api.field(ai_class,"uid","I");
    const auto enabled=api.field(ai_class,"enabled","Z");
    const auto source=api.field(ai_class,"sourceDir","Ljava/lang/String;");
    const auto split=api.field(ai_class,"splitSourceDirs","[Ljava/lang/String;");
    if(!lookup||!code||!multiple||!signatures||!signature_bytes||!name||!application||!signing||!updated||!uid||!enabled||!source||!split)return false;
    const auto query=env->NewStringUTF(package);if(!api.ok()||!query)return false;
    const auto info=env->CallObjectMethod(pm,lookup,query,0x08000000);if(!api.ok()||!info)return false; // GET_SIGNING_CERTIFICATES
    const auto observed_name=static_cast<jstring>(env->GetObjectField(info,name));if(!api.ok())return false;
    std::string package_name;if(!api.text(observed_name,package_name,64)||package_name!=package)return false;
    const auto app=env->GetObjectField(info,application);if(!api.ok()||!app)return false;
    const auto signing_info=env->GetObjectField(info,signing);if(!api.ok()||!signing_info)return false;
    InstalledApk result;
    result.code=env->CallLongMethod(info,code);if(!api.ok()||result.code<=0||result.code>999999999)return false;
    result.updated=env->GetLongField(info,updated);if(!api.ok()||result.updated<1000000000000LL||result.updated>9999999999999LL)return false;
    result.uid=env->GetIntField(app,uid);if(!api.ok()||result.uid<10000||result.uid>=100000)return false;
    const bool active=env->GetBooleanField(app,enabled);if(!api.ok()||!active)return false;
    const auto splits=env->GetObjectField(app,split);if(!api.ok()||splits)return false; // current owned flavor is a single base APK
    const auto path=static_cast<jstring>(env->GetObjectField(app,source));if(!api.ok()||!api.text(path,result.path,1024)||!source_path(result.path))return false;
    const bool multi=env->CallBooleanMethod(signing_info,multiple);if(!api.ok()||multi)return false;
    const auto certs=static_cast<jobjectArray>(env->CallObjectMethod(signing_info,signatures));if(!api.ok()||!certs)return false;
    const auto count=env->GetArrayLength(certs);if(!api.ok()||count!=1)return false;
    const auto certificate=env->GetObjectArrayElement(certs,0);if(!api.ok()||!certificate)return false;
    const auto bytes=static_cast<jbyteArray>(env->CallObjectMethod(certificate,signature_bytes));if(!api.ok()||!bytes)return false;
    const auto length=env->GetArrayLength(bytes);if(!api.ok()||length<=0||length>65536)return false;
    std::vector<unsigned char> data(length);env->GetByteArrayRegion(bytes,0,length,reinterpret_cast<jbyte *>(data.data()));if(!api.ok())return false;
    if(!sha256_bytes(data.data(),data.size(),result.signer)||result.signer!=signer)return false;
    out=result;return true;
}
inline bool same_package(const InstalledApk &a,const InstalledApk &b){return a.uid==b.uid&&a.code==b.code&&a.updated==b.updated&&a.path==b.path&&a.signer==b.signer;}
inline bool protected_directory(const struct stat &s){
    return S_ISDIR(s.st_mode)&&(s.st_uid==0||s.st_uid==1000)&&!(s.st_mode&0002)&&(!(s.st_mode&0020)||s.st_gid==0||s.st_gid==1000);
}
inline int open_apk(const std::string &path){
    if(!source_path(path))return -1;
    int parent=open("/",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(parent<0)return -1;
    size_t start=1;
    for(size_t i=1;i<=path.size();++i){
        if(i<path.size()&&path[i]!='/')continue;
        struct stat st{};if(fstat(parent,&st)!=0||!protected_directory(st)){close(parent);return -1;}
        const auto part=path.substr(start,i-start);
        const int child=openat(parent,part.c_str(),i==path.size()?O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC:O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        const bool closed=close(parent)==0;if(child<0)return -1;if(!closed){close(child);return -1;}parent=child;start=i+1;
    }
    return parent;
}
inline bool protected_apk(const struct stat &s){return S_ISREG(s.st_mode)&&(s.st_uid==0||s.st_uid==1000)&&s.st_nlink==1&&!(s.st_mode&0022)&&s.st_size>0&&s.st_size<=12*1024*1024;}
inline bool same_file(const struct stat &a,const struct stat &b){
    return protected_apk(a)&&protected_apk(b)&&a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_uid==b.st_uid&&a.st_gid==b.st_gid&&a.st_mode==b.st_mode&&
        a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;
}
}
inline bool read_installed_apk(JNIEnv *env,jobject pm,InstalledApk &out){
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(2);
    InstalledApk before,after;if(!apk_detail::package_snapshot(env,pm,before)||std::chrono::steady_clock::now()>=deadline)return false;
    int fd=apk_detail::open_apk(before.path);struct stat identity{};
    if(fd<0)return false;
    bool good=fstat(fd,&identity)==0&&apk_detail::protected_apk(identity);off_t at=0;
    sha256_detail::Digest digest;std::array<unsigned char,16384> buffer{};
    while(good&&at<identity.st_size){
        if(std::chrono::steady_clock::now()>=deadline){good=false;break;}
        const auto count=std::min<size_t>(buffer.size(),static_cast<size_t>(identity.st_size-at));
        const auto n=pread(fd,buffer.data(),count,at);if(n<0&&errno==EINTR)continue;
        if(n<=0||static_cast<size_t>(n)>count){good=false;break;}digest.update(buffer.data(),n);at+=n;
    }
    unsigned char extra{};ssize_t end=-1;struct stat current{};
    if(good){do{if(std::chrono::steady_clock::now()>=deadline){good=false;break;}end=pread(fd,&extra,1,at);}while(end<0&&errno==EINTR);}
    good=good&&end==0&&fstat(fd,&current)==0&&apk_detail::same_file(identity,current);
    const bool closed=close(fd)==0;if(!good||!closed)return false;
    if(!apk_detail::package_snapshot(env,pm,after)||!apk_detail::same_package(before,after)||std::chrono::steady_clock::now()>=deadline)return false;
    fd=apk_detail::open_apk(after.path);if(fd<0)return false;
    good=fstat(fd,&current)==0&&apk_detail::same_file(identity,current);const bool reclosed=close(fd)==0;
    if(!good||!reclosed||std::chrono::steady_clock::now()>=deadline)return false;
    before.sha=digest.finish();out=before;return true;
}
}

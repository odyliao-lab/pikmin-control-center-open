#pragma once
#include "installed_apk.h"
namespace emu_payload {
struct CoreAssets {std::string version,zip_sha,arm_sha,loader_sha;int64_t code{};};
inline bool core_version(const std::string &v){
    if(v.empty()||v.size()>32)return false;unsigned dots=0;bool digit=false;
    for(char c:v){if(c=='.'){if(!digit||++dots>2)return false;digit=false;}else if(c>='0'&&c<='9')digit=true;else return false;}
    return dots==2&&digit;
}
inline bool apk_fd_matches(int fd,const struct stat &identity,const std::string &expected,
                           std::chrono::steady_clock::time_point deadline){
    if(expected.size()!=64||!apk_detail::protected_apk(identity))return false;
    for(char c:expected)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    struct stat before{},after{};
    if(fd<0||fstat(fd,&before)!=0||!apk_detail::same_file(identity,before))return false;
    sha256_detail::Digest digest;std::array<unsigned char,16384> buffer{};off_t at=0;
    while(at<before.st_size){
        if(std::chrono::steady_clock::now()>=deadline)return false;
        const auto count=std::min<size_t>(buffer.size(),static_cast<size_t>(before.st_size-at));
        const auto n=pread(fd,buffer.data(),count,at);if(n<0&&errno==EINTR)continue;
        if(n<=0||static_cast<size_t>(n)>count)return false;digest.update(buffer.data(),n);at+=n;
    }
    unsigned char extra{};ssize_t eof;
    do{if(std::chrono::steady_clock::now()>=deadline)return false;eof=pread(fd,&extra,1,at);}while(eof<0&&errno==EINTR);
    return eof==0&&fstat(fd,&after)==0&&apk_detail::same_file(identity,after)&&
        std::chrono::steady_clock::now()<deadline&&digest.finish()==expected;
}
// Caller has already independently verified this installed signed APK. Java
// only opens a held protected FD alias; re-observe PM/bytes before publication.
inline bool read_apk_core_assets(JNIEnv *env,jobject pm,const InstalledApk &apk,CoreAssets &out){
    const auto deadline=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    apk_detail::LocalFrame frame(env);if(!frame.held)return false;
    int fd=apk_detail::open_apk(apk.path);if(fd<0)return false;struct stat before{},after{};
    bool good=fstat(fd,&before)==0&&apk_detail::protected_apk(before)&&apk_fd_matches(fd,before,apk.sha,deadline);
    CoreAssets result;apk_detail::Api api{env};
    if(good){
        auto klass=api.type("dev/ody/pccbridge/CoreAssets");
        auto method=klass?env->GetStaticMethodID(klass,"observe","(Ljava/lang/String;J)[Ljava/lang/String;"):nullptr;
        good=api.ok()&&method;
        if(good){
            const auto alias="/proc/self/fd/"+std::to_string(fd);auto path=env->NewStringUTF(alias.c_str());good=api.ok()&&path;
            auto fields=good?static_cast<jobjectArray>(env->CallStaticObjectMethod(klass,method,path,static_cast<jlong>(apk.code))):nullptr;
            good=api.ok()&&fields;
            if(good){const auto count=env->GetArrayLength(fields);good=api.ok()&&count==5;}
            std::string values[5];
            for(int i=0;good&&i<5;++i){auto field=static_cast<jstring>(env->GetObjectArrayElement(fields,i));good=api.ok()&&api.text(field,values[i],64);}
            if(good){
                result.version=values[0];result.zip_sha=values[2];result.arm_sha=values[3];result.loader_sha=values[4];
                good=core_version(values[0])&&!values[1].empty()&&values[1].size()<=9&&values[1][0]!='0';
                for(char c:values[1])if(c<'0'||c>'9')good=false;
                if(good)result.code=std::strtoll(values[1].c_str(),nullptr,10);
                good=good&&result.code>0&&result.code<=999999999;
                for(int i=2;i<5;++i){good=good&&values[i].size()==64;for(char c:values[i])if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))good=false;}
            }
        }
    }
    good=good&&apk_fd_matches(fd,before,apk.sha,deadline)&&fstat(fd,&after)==0&&apk_detail::same_file(before,after);const bool closed=close(fd)==0;
    if(!good||!closed||std::chrono::steady_clock::now()>=deadline)return false;
    InstalledApk fresh;if(!read_installed_apk(env,pm,fresh)||!apk_detail::same_package(apk,fresh)||apk.sha!=fresh.sha||std::chrono::steady_clock::now()>=deadline)return false;
    out=result;return true;
}
}

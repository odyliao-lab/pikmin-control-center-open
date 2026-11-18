#pragma once
#include "root_provider_service.h"
#include <sys/syscall.h>
namespace emu_gps_work {
// Immutable Root preparation, not a STOP/START authorization or live lease.
// One active original is retained; no implicit replacement/new operation.
struct ProviderPreparation {std::string operation;ProviderProcessObservation original;std::string service;};
inline std::string provider_preparation_wire(const ProviderPreparation &v){
    const auto process=provider_process_wire(v.original);
    if(!fence_observation_detail::token(v.operation)||process.empty()||!provider_service_detail::token(v.service))return {};
    return "gp-prepared2\t"+v.operation+"\t"+v.service+"\tPREPARED_ONLY\n"+process;
}
inline bool decode_provider_preparation(const std::string &text,ProviderPreparation &out){
    const auto end=text.find('\n');if(end==std::string::npos||end<65||end>80)return false;
    ProviderPreparation v;v.operation=text.substr(13,36);
    const auto sep=text.find('\t',50);if(sep==std::string::npos||sep>=end)return false;v.service=text.substr(50,sep-50);
    if(!decode_provider_process(text.substr(end+1),v.original)||provider_preparation_wire(v)!=text)return false;
    out=std::move(v);return true;
}
namespace provider_journal_detail {
constexpr const char *leaf="provider-prepared.tsv",*temp="provider-prepared.tsv.tmp";
inline bool metadata(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&
    (s.st_mode&07777)==0600&&s.st_nlink==1&&s.st_size>0&&s.st_size<=768;}
inline bool absent(int dir,const char *name){struct stat s{};return fstatat(dir,name,&s,AT_SYMLINK_NOFOLLOW)!=0&&errno==ENOENT;}
inline bool read(int dir,ProviderPreparation &out){
    using namespace fence_observation_detail;struct stat before{},opened{},after{},named{};std::string text;
    if(!absent(dir,temp)||fstatat(dir,leaf,&before,AT_SYMLINK_NOFOLLOW)!=0||!metadata(before))return false;
    int fd=openat(dir,leaf,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    bool good=fstat(fd,&opened)==0&&metadata(opened)&&same(before,opened)&&small(fd,text,768)&&
        fstat(fd,&after)==0&&same(opened,after);
    const bool closed=close(fd)==0;ProviderPreparation value;
    good=good&&closed&&decode_provider_preparation(text,value)&&fstatat(dir,leaf,&named,AT_SYMLINK_NOFOLLOW)==0&&
        metadata(named)&&same(opened,named)&&absent(dir,temp);
    if(!good)return false;out=std::move(value);return true;
}
}
inline bool read_root_provider_preparation(RootGpsFence &fence,ProviderPreparation &out){
    if(geteuid()!=0||!fence.valid())return false;int dir=fence.observation_directory();if(dir<0)return false;
    ProviderPreparation value;bool good=provider_journal_detail::read(dir,value)&&fence.valid();
    const bool closed=close(dir)==0;if(!good||!closed)return false;out=std::move(value);return true;
}
inline bool prepare_root_provider(RootGpsFence &fence,const std::string &operation,ProviderPreparation &out){
    using namespace provider_journal_detail;
    if(geteuid()!=0||!fence_observation_detail::token(operation)||!fence.valid())return false;
    int dir=fence.observation_directory();if(dir<0)return false;ProviderPreparation value;
    bool good=absent(dir,temp);const bool missing=absent(dir,leaf);
    if(good&&!missing){
        // Recovery reads the same historical original; never resamples it.
        good=read(dir,value)&&value.operation==operation;
    }else if(good){
        value.operation=operation;good=capture_root_provider_service(fence,value.original,value.service);
        const auto text=good?provider_preparation_wire(value):std::string{};
        int fd=text.empty()?-1:openat(dir,temp,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        good=good&&fd>=0;size_t at=0;
        while(good&&at<text.size()){const auto n=write(fd,text.data()+at,text.size()-at);
            if(n<0&&errno==EINTR)continue;if(n<=0){good=false;break;}at+=n;}
        if(fd>=0){good=good&&fchmod(fd,0600)==0&&fsync(fd)==0;const bool closed=close(fd)==0;good=good&&closed;}
        // Atomic no-replace. Failed/partial/unknown temp remains for review.
        good=good&&fence.valid()&&syscall(SYS_renameat2,dir,temp,dir,leaf,1 /* RENAME_NOREPLACE */)==0;
    }
    // Also make a complete crash-recovered original durable before returning.
    int stable=good?openat(dir,leaf,O_RDONLY|O_NOFOLLOW|O_NONBLOCK|O_CLOEXEC):-1;
    if(stable>=0){good=fsync(stable)==0&&good;const bool closed=close(stable)==0;good=good&&closed;}else good=false;
    ProviderPreparation readback;
    good=good&&fsync(dir)==0&&read(dir,readback)&&provider_preparation_wire(value)==provider_preparation_wire(readback)&&fence.valid();
    const bool closed=close(dir)==0;if(!good||!closed)return false;out=std::move(readback);return true;
}
}

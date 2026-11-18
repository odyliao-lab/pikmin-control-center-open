#pragma once
#include "root_gps_fence.h"
#include <ctime>
#include <cstdint>
#include <vector>

namespace emu_gps_work {
// Independent Root observation ONLY, not WriterLease or exclusion evidence.
// A fresh witness says the authenticated producer observed its own held inode;
// it cannot prove all writers excluded, queued intents drained, or game ready.
struct FenceObservation {
    std::string boot,nonce;int pid{};uint64_t ticks{},device{},inode{},sampled{},until{};
};
namespace fence_observation_detail {
inline bool token(const std::string &s){
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i){if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;}
    return true;
}
inline bool number(const std::string &s,uint64_t &out){
    if(s.empty()||s.size()>20||(s.size()>1&&s[0]=='0'))return false;uint64_t n=0;
    for(char c:s){if(c<'0'||c>'9'||n>(UINT64_MAX-(c-'0'))/10)return false;n=n*10+c-'0';}
    out=n;return true;
}
inline uint64_t now(){timespec t{};if(clock_gettime(CLOCK_BOOTTIME,&t)!=0||t.tv_sec<0)return 0;
    return uint64_t(t.tv_sec)*1000+uint64_t(t.tv_nsec)/1000000;}
inline bool small(int fd,std::string &out,size_t limit){
    std::string value;char bytes[256];unsigned tries=0;
    for(;;){if(++tries>32)return false;const auto n=read(fd,bytes,sizeof(bytes));
        if(n<0&&errno==EINTR)continue;if(n<0)return false;if(!n)break;
        value.append(bytes,n);if(value.size()>limit)return false;}
    out=std::move(value);return true;
}
inline bool read_path(const std::string &path,std::string &out,size_t limit){
    int fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    const bool good=small(fd,out,limit);const bool closed=close(fd)==0;return good&&closed;
}
inline bool boot(std::string &out){std::string b;
    if(!read_path("/proc/sys/kernel/random/boot_id",b,64)||b.size()!=37||b.back()!='\n'||!token(b.substr(0,36)))return false;
    out=b.substr(0,36);return true;}
inline bool process(int pid,uint64_t &ticks){
    if(pid<=0||pid>999999999)return false;const auto path="/proc/"+std::to_string(pid);
    struct stat before{},after{};std::string text;
    if(lstat(path.c_str(),&before)!=0||!S_ISDIR(before.st_mode)||before.st_uid!=0||
       !read_path(path+"/stat",text,4096)||!text.starts_with(std::to_string(pid)+" ("))return false;
    const auto end=text.rfind(')');if(end==std::string::npos||end+2>=text.size())return false;
    std::vector<std::string> fields;size_t at=end+2;
    for(size_t i=at;i<=text.size();++i)if(i==text.size()||text[i]==' '||text[i]=='\n'){
        if(i>at)fields.push_back(text.substr(at,i-at));at=i+1;}
    uint64_t value{};
    if(fields.size()<20||fields[0]=="Z"||fields[0]=="X"||!number(fields[19],value)||!value||
       lstat(path.c_str(),&after)!=0||after.st_uid!=0||before.st_dev!=after.st_dev||before.st_ino!=after.st_ino)return false;
    ticks=value;return true;
}
inline bool directory(const struct stat &s){return S_ISDIR(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0711;}
inline bool leaf(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0444&&s.st_nlink==1&&s.st_size>0&&s.st_size<=512;}
inline bool lock(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0600&&s.st_nlink==1&&s.st_size==0;}
inline bool same(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&
    a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;}
inline std::string wire(const FenceObservation &v){
    if(!token(v.boot)||!token(v.nonce)||v.pid<=0||v.pid>999999999||!v.ticks||!v.inode||!v.sampled||
       v.until<=v.sampled||v.until-v.sampled!=500)return {};
    return "gf-observe1\t"+v.boot+"\t"+v.nonce+"\t"+std::to_string(v.pid)+"\t"+std::to_string(v.ticks)+"\t"+
        std::to_string(v.device)+"\t"+std::to_string(v.inode)+"\t"+std::to_string(v.sampled)+"\t"+std::to_string(v.until)+"\tFENCE_ONLY\n";
}
inline bool decode(const std::string &text,FenceObservation &out){
    if(text.empty()||text.size()>512||text.find('\r')!=std::string::npos||text.find('\0')!=std::string::npos)return false;
    std::vector<std::string> f;size_t at=0;for(size_t i=0;i<text.size();++i)if(text[i]=='\t'){f.push_back(text.substr(at,i-at));at=i+1;}f.push_back(text.substr(at));
    if(f.size()!=10||f[0]!="gf-observe1"||f[9]!="FENCE_ONLY\n")return false;
    FenceObservation v;uint64_t pid{};v.boot=f[1];v.nonce=f[2];
    if(!number(f[3],pid)||pid>999999999||!number(f[4],v.ticks)||!number(f[5],v.device)||!number(f[6],v.inode)||
       !number(f[7],v.sampled)||!number(f[8],v.until))return false;
    v.pid=static_cast<int>(pid);if(wire(v)!=text)return false;out=std::move(v);return true;
}
}
inline bool publish_fence_observation(RootGpsFence &fence,const std::string &nonce){
    using namespace fence_observation_detail;
    if(geteuid()!=0||!token(nonce)||!fence.valid())return false;
    int dir=fence.observation_directory();if(dir<0)return false;
    struct stat parent{},held{},old{};FenceObservation v;v.pid=getpid();v.nonce=nonce;
    bool good=fstat(dir,&parent)==0&&directory(parent)&&fstatat(dir,"gps.lock",&held,AT_SYMLINK_NOFOLLOW)==0&&lock(held)&&boot(v.boot)&&process(v.pid,v.ticks);
    v.device=held.st_dev;v.inode=held.st_ino;v.sampled=now();v.until=v.sampled+500;
    if(fstatat(dir,"fence.tsv",&old,AT_SYMLINK_NOFOLLOW)==0){if(!leaf(old))good=false;}else if(errno!=ENOENT)good=false;
    const auto text=good?wire(v):std::string{};int fd=-1;
    if(!text.empty())fd=openat(dir,"fence.tsv.tmp",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    good=good&&fd>=0;size_t at=0;
    while(good&&at<text.size()){const auto n=write(fd,text.data()+at,text.size()-at);if(n<0&&errno==EINTR)continue;
        if(n<=0){good=false;break;}at+=n;}
    if(fd>=0){good=good&&fchmod(fd,0444)==0&&fsync(fd)==0;const bool closed=close(fd)==0;good=good&&closed;}
    const auto end=now();good=good&&end>=v.sampled&&end-v.sampled<250&&fence.valid();
    if(good)good=renameat(dir,"fence.tsv.tmp",dir,"fence.tsv")==0&&fsync(dir)==0&&fence.valid();
    // Failed/unknown temporary state is retained, never repaired or replayed.
    const bool closed=close(dir)==0;return good&&closed;
}
// Off-Map I/O only. Root-owned FD/inode is authentication, not wire flags or a
// checksum. Expiry/process/lock checks reject stale witnesses after actor exit.
// Unauthenticated metadata hint only. Never feed this directly into a cache
// or WriterLease; the strict wrapper or authenticated IPC must verify liveness.
inline bool read_fence_candidate_at(int dir,FenceObservation &out){
    using namespace fence_observation_detail;
    struct stat parent{},parent_after{},before{},held{},after{},named{},lock_before{},lock_after{};
    const auto begin=now();std::string current_boot;
    if(!begin||!boot(current_boot)||fstat(dir,&parent)!=0||!directory(parent)||
       fstatat(dir,"fence.tsv.tmp",&named,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT||
       fstatat(dir,"gps.lock",&lock_before,AT_SYMLINK_NOFOLLOW)!=0||!lock(lock_before)||
       fstatat(dir,"fence.tsv",&before,AT_SYMLINK_NOFOLLOW)!=0||!leaf(before))return false;
    int fd=openat(dir,"fence.tsv",O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    std::string text;bool good=fstat(fd,&held)==0&&leaf(held)&&same(before,held)&&small(fd,text,512)&&
        fstat(fd,&after)==0&&leaf(after)&&same(held,after);
    const bool closed=close(fd)==0;FenceObservation v;
    if(!good||!closed||!decode(text,v)||v.boot!=current_boot||v.sampled>begin||v.until<=begin||
       v.device!=uint64_t(lock_before.st_dev)||v.inode!=uint64_t(lock_before.st_ino)||
       fstatat(dir,"fence.tsv",&named,AT_SYMLINK_NOFOLLOW)!=0||!leaf(named)||!same(held,named)||
       fstatat(dir,"gps.lock",&lock_after,AT_SYMLINK_NOFOLLOW)!=0||!lock(lock_after)||!same(lock_before,lock_after)||
       fstat(dir,&parent_after)!=0||!directory(parent_after)||parent.st_dev!=parent_after.st_dev||parent.st_ino!=parent_after.st_ino||
       fstatat(dir,"fence.tsv.tmp",&named,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
    const auto end=now();if(end<begin||end-begin>100||end>=v.until)return false;
    out=std::move(v);return true;
}
inline bool read_fence_observation_at(int dir,FenceObservation &out){
    FenceObservation v;uint64_t ticks{};
    if(!read_fence_candidate_at(dir,v)||!fence_observation_detail::process(v.pid,ticks)||ticks!=v.ticks||
       fence_observation_detail::now()>=v.until)return false;
    out=std::move(v);return true;
}
inline int open_fence_observation_directory(){
    // O_PATH permits traversal of Root0711 without directory listing rights.
    // Walk each ancestor independently; final-component O_NOFOLLOW is not enough.
    int dir=open("/",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(dir<0)return -1;
    for(const char *part:{"data","local","tmp","pcc-emulator-gps-work"}){
        const int next=openat(dir,part,O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        const bool closed=close(dir)==0;dir=next;if(!closed||dir<0){if(dir>=0)close(dir);return -1;}
    }
    return dir;
}
inline bool read_fence_observation(FenceObservation &out){
    int dir=open_fence_observation_directory();if(dir<0)return false;
    struct stat before{},after{};FenceObservation v;
    bool good=fstat(dir,&before)==0&&read_fence_observation_at(dir,v);
    // Reopen the canonical path: a renamed trusted directory is not the current
    // product channel, even if its retained FD and old leaves still look valid.
    int fresh=good?open_fence_observation_directory():-1;
    good=good&&fresh>=0&&fstat(fresh,&after)==0&&fence_observation_detail::directory(after)&&
        before.st_dev==after.st_dev&&before.st_ino==after.st_ino&&fence_observation_detail::now()<v.until;
    if(fresh>=0){const bool closed=close(fresh)==0;good=good&&closed;}
    const bool closed=close(dir)==0;
    if(!good||!closed)return false;out=std::move(v);return true;
}
}

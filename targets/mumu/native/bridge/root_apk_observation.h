#pragma once
#include "installed_apk.h"
#include "apk_core_assets.h"
#include "planting_command_policy.h"
#include <sys/file.h>

namespace emu_payload {
// Root-to-game APK observation ONLY. It never grants ownership, pairs the
// loaded core, excludes GPS writers, creates a command store or sends effects.
struct ProcessBinding {int pid{},uid{};uint64_t ticks{};std::string boot;};
struct RootApkObservation {ProcessBinding process;InstalledApk apk;CoreAssets core;int64_t sampled{},until{};};
namespace root_apk_detail {
inline int64_t now(){return std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
inline bool read_small(const std::string &path,std::string &out,size_t limit){
    const int fd=open(path.c_str(),O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    std::string result;char bytes[1024];bool good=true;
    for(unsigned attempts=0;;++attempts){
        if(attempts>16){good=false;break;}const auto n=::read(fd,bytes,sizeof(bytes));
        if(n<0&&errno==EINTR)continue;if(n<0){good=false;break;}if(n==0)break;
        result.append(bytes,n);if(result.size()>limit){good=false;break;}
    }
    const bool closed=close(fd)==0;if(!good||!closed)return false;out=result;return true;
}
inline bool same_process(const ProcessBinding &a,const ProcessBinding &b){return a.pid==b.pid&&a.uid==b.uid&&a.ticks==b.ticks&&a.boot==b.boot;}
inline bool binding(int pid,ProcessBinding &out){
    if(pid<=0||pid>999999999)return false;const auto root="/proc/"+std::to_string(pid);struct stat before{},after{};
    if(lstat(root.c_str(),&before)!=0||!S_ISDIR(before.st_mode)||before.st_uid<10000||before.st_uid>=100000)return false;
    std::string boot,command,status;
    if(!read_small("/proc/sys/kernel/random/boot_id",boot,64)||boot.size()!=37||boot.back()!='\n'||
       !emu_planting_command::uuid(boot.substr(0,36))||!read_small(root+"/cmdline",command,1024)||
       command.size()<23||command.substr(0,23)!=std::string("com.nianticlabs.pikmin\0",23)||!read_small(root+"/stat",status,4096))return false;
    // Android setArgV0 leaves the old argument allocation padded with NULs.
    for(size_t i=23;i<command.size();++i)if(command[i]!='\0')return false;
    const auto comm_end=status.rfind(')');if(comm_end==std::string::npos||comm_end+2>=status.size())return false;
    if(!status.starts_with(std::to_string(pid)+" ("))return false;
    std::vector<std::string> fields;size_t start=comm_end+2;
    for(size_t i=start;i<=status.size();++i)if(i==status.size()||status[i]==' '||status[i]=='\n'){
        if(i>start)fields.push_back(status.substr(start,i-start));start=i+1;
    }
    uint64_t ticks{}; // suffix starts at field3; starttime is field22
    if(fields.size()<20||!emu_planting_command::number(fields[19],UINT64_MAX,ticks)||ticks==0||
       lstat(root.c_str(),&after)!=0||before.st_uid!=after.st_uid||before.st_ino!=after.st_ino||before.st_dev!=after.st_dev)return false;
    ProcessBinding p;p.pid=pid;p.uid=before.st_uid;p.ticks=ticks;p.boot=boot.substr(0,36);out=p;return true;
}
inline bool directory(const struct stat &s){return S_ISDIR(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0711;}
inline bool leaf(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0444&&s.st_nlink==1&&s.st_size>0&&s.st_size<=1024;}
inline bool same_file(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;}
inline std::string body(const RootApkObservation &o){
    using namespace emu_planting_command;
    if(o.process.pid<=0||o.process.pid>999999999||o.process.uid<10000||o.process.uid>=100000||!o.process.ticks||!uuid(o.process.boot)||
       o.apk.uid<10000||o.apk.uid>=100000||o.apk.uid==o.process.uid||o.apk.code<=0||o.apk.code>999999999||
       o.apk.updated<1000000000000LL||o.apk.updated>9999999999999LL||!digest(o.apk.sha)||o.apk.signer!=apk_detail::signer||
       !core_version(o.core.version)||o.core.code<=0||o.core.code>999999999||!digest(o.core.zip_sha)||!digest(o.core.arm_sha)||!digest(o.core.loader_sha)||
       o.sampled<1000000000000LL||o.until>9999999999999LL||o.until<=o.sampled||o.until-o.sampled>5000)return {};
    return "ra2\t"+o.process.boot+"\t"+std::to_string(o.process.pid)+"\t"+std::to_string(o.process.uid)+"\t"+std::to_string(o.process.ticks)+"\t"+
        std::to_string(o.apk.uid)+"\t"+std::to_string(o.apk.code)+"\t"+std::to_string(o.apk.updated)+"\t"+o.apk.sha+"\t"+o.apk.signer+"\t"+
        o.core.version+"\t"+std::to_string(o.core.code)+"\t"+o.core.zip_sha+"\t"+o.core.arm_sha+"\t"+o.core.loader_sha+"\t"+
        std::to_string(o.sampled)+"\t"+std::to_string(o.until)+"\tend\n";
}
inline std::string wire(const RootApkObservation &o){
    const auto text=body(o);std::string sum;if(text.empty()||!sha256_bytes(reinterpret_cast<const unsigned char *>(text.data()),text.size(),sum))return {};
    // Corruption detection, not authentication. Root-owned path/inode is the
    // trust boundary; a checksum can never replace those ownership checks.
    return text+"sha256\t"+sum+"\n";
}
inline bool decode(const std::string &text,RootApkObservation &out){
    using namespace emu_planting_command;
    if(text.empty()||text.size()>1024||text.find('\r')!=std::string::npos||text.find('\0')!=std::string::npos)return false;
    const auto newline=text.find('\n');if(newline==std::string::npos)return false;const auto line=text.substr(0,newline+1);
    std::vector<std::string> f;size_t start=0;for(size_t i=0;i<line.size();++i)if(line[i]=='\t'){f.push_back(line.substr(start,i-start));start=i+1;}f.push_back(line.substr(start));
    if(f.size()!=18||f[0]!="ra2"||f[17]!="end\n")return false;
    uint64_t pid{},uid{},ticks{},apk_uid{},code{},updated{},core_code{},sampled{},until{};
    if(!number(f[2],999999999,pid)||!number(f[3],99999,uid)||!number(f[4],UINT64_MAX,ticks)||!number(f[5],99999,apk_uid)||
       !number(f[6],999999999,code)||!number(f[7],9999999999999ULL,updated)||!number(f[11],999999999,core_code)||!number(f[15],9999999999999ULL,sampled)||!number(f[16],9999999999999ULL,until))return false;
    RootApkObservation o;o.process={static_cast<int>(pid),static_cast<int>(uid),ticks,f[1]};o.apk.uid=apk_uid;o.apk.code=code;o.apk.updated=updated;o.apk.sha=f[8];o.apk.signer=f[9];o.sampled=sampled;o.until=until;
    o.core={f[10],f[12],f[13],f[14],static_cast<int64_t>(core_code)};
    if(wire(o)!=text)return false;out=o;return true;
}
}
// Independent Root PM observation + independently read current game process.
// Producer owns creation/locking of the Root directory; no mkdir/repair here.
inline bool publish_root_apk_observation(JNIEnv *env,jobject pm,int dir,int game_pid){
    if(geteuid()!=0)return false;struct stat parent{};
    if(fstat(dir,&parent)!=0||!root_apk_detail::directory(parent)||flock(dir,LOCK_EX|LOCK_NB)!=0)return false;
    RootApkObservation observation;ProcessBinding fresh;
    bool good=root_apk_detail::binding(game_pid,observation.process)&&read_installed_apk(env,pm,observation.apk)&&
        read_apk_core_assets(env,pm,observation.apk,observation.core)&&
        root_apk_detail::binding(game_pid,fresh)&&root_apk_detail::same_process(observation.process,fresh);
    const auto sampled=root_apk_detail::now();observation.sampled=sampled;observation.until=sampled+5000;
    const auto text=good?root_apk_detail::wire(observation):std::string{};
    struct stat old{};
    if(fstatat(dir,"apk.tsv",&old,AT_SYMLINK_NOFOLLOW)==0){if(!root_apk_detail::leaf(old))good=false;}else if(errno!=ENOENT)good=false;
    int fd=-1;bool created=false;
    if(good&&!text.empty()){fd=openat(dir,"apk.tsv.tmp",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);created=fd>=0;good=created;}else good=false;
    size_t at=0;while(good&&at<text.size()){
        if(root_apk_detail::now()<sampled||root_apk_detail::now()-sampled>1000){good=false;break;}
        const auto n=::write(fd,text.data()+at,text.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0){good=false;break;}at+=n;
    }
    if(fd>=0){good=good&&fchmod(fd,0444)==0&&fsync(fd)==0;const bool closed=close(fd)==0;good=good&&closed;}
    if(good)good=root_apk_detail::binding(game_pid,fresh)&&root_apk_detail::same_process(observation.process,fresh)&&root_apk_detail::now()>=sampled&&root_apk_detail::now()-sampled<=1000;
    if(good)good=renameat(dir,"apk.tsv.tmp",dir,"apk.tsv")==0&&fsync(dir)==0;
    // Preserve a failed/unknown temp for review; never unlink or replay it.
    const bool unlocked=flock(dir,LOCK_UN)==0;return good&&unlocked;
}
// Low-level protected-FD reader, shared with isolated fixtures. Caller derives
// binding independently; production wrapper below ONLY uses its own process.
inline bool read_root_apk_at(int dir,const ProcessBinding &expected,int64_t now,RootApkObservation &out){
    if(now<1000000000000LL||now>9999999999999LL)return false;
    struct stat parent{},before{},held{},after{},leaf{},parent_after{};
    if(fstat(dir,&parent)!=0||!root_apk_detail::directory(parent)||fstatat(dir,"apk.tsv.tmp",&leaf,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT||
       fstatat(dir,"apk.tsv",&before,AT_SYMLINK_NOFOLLOW)!=0||!root_apk_detail::leaf(before))return false;
    int fd=openat(dir,"apk.tsv",O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    bool good=fstat(fd,&held)==0&&root_apk_detail::leaf(held)&&root_apk_detail::same_file(before,held);
    std::string text(good?held.st_size:0,'\0');size_t at=0;unsigned attempts=0;
    while(good&&at<text.size()){
        if(++attempts>16){good=false;break;}const auto n=::read(fd,text.data()+at,text.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0){good=false;break;}at+=n;
    }
    char extra{};const auto end=good?::read(fd,&extra,1):-1;
    good=good&&end==0&&fstat(fd,&after)==0&&root_apk_detail::leaf(after)&&root_apk_detail::same_file(held,after);
    const bool closed=close(fd)==0;if(!good||!closed)return false;
    RootApkObservation value;
    if(!root_apk_detail::decode(text,value)||!root_apk_detail::same_process(value.process,expected)||now<value.sampled||now>=value.until||now-value.sampled>5000||
       fstatat(dir,"apk.tsv",&leaf,AT_SYMLINK_NOFOLLOW)!=0||!root_apk_detail::leaf(leaf)||!root_apk_detail::same_file(held,leaf)||
       fstat(dir,&parent_after)!=0||!root_apk_detail::directory(parent_after)||parent.st_dev!=parent_after.st_dev||parent.st_ino!=parent_after.st_ino||
       fstatat(dir,"apk.tsv.tmp",&leaf,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
    out=value;return true;
}
inline bool read_root_apk_observation(RootApkObservation &out){
    ProcessBinding expected,after;
    if(!root_apk_detail::binding(getpid(),expected)||expected.uid!=geteuid())return false;
    // Fixed path, no command-owned source path or configurable witness.
    int dir=open("/data/local/tmp/pcc-emulator-apk-proof",O_PATH|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(dir<0)return false;
    RootApkObservation value;const auto begin=root_apk_detail::now();bool good=read_root_apk_at(dir,expected,begin,value);
    const bool closed=close(dir)==0;const auto end=root_apk_detail::now();
    if(!good||!closed||!root_apk_detail::binding(getpid(),after)||!root_apk_detail::same_process(expected,after)||end<begin||end-begin>1000||end>=value.until)return false;
    out=value;return true;
}
}

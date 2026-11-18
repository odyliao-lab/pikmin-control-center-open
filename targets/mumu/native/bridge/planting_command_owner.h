#pragma once
#include "planting_command_authority.h"
#include "planting_command_store.h"

namespace emu_planting_command {
// Owner record independently produced by the future host/root handoff, not a
// Command copy. Heartbeats may refresh timestamps for the SAME operation; they
// do not authorize replacement/replay of accepted or unresolved command work.
struct OwnerRecord {
    int pid{};uint64_t epoch{};
    std::string account,owner,token,operation,boot,core_sha,apk_sha;
    int64_t written{},until{};
};
inline std::string owner_body(const OwnerRecord &r){
    if(r.pid<=0||r.pid>999999999||r.epoch==0||!digest(r.account)||!uuid(r.owner)||!uuid(r.token)||
       !uuid(r.operation)||!uuid(r.boot)||!digest(r.core_sha)||!digest(r.apk_sha)||
       r.written<1000000000000LL||r.until>9999999999999LL||r.until<=r.written||r.until-r.written>15000)return {};
    return "o1\t"+std::to_string(r.pid)+"\t"+std::to_string(r.epoch)+"\t"+r.account+"\t"+
        r.owner+"\t"+r.token+"\t"+r.operation+"\t"+r.boot+"\t"+r.core_sha+"\t"+r.apk_sha+"\t"+
        std::to_string(r.written)+"\t"+std::to_string(r.until)+"\tend\n";
}
inline std::string owner_wire(const OwnerRecord &r){
    const auto body=owner_body(r);if(body.empty())return {};
    char crc[9]{};std::snprintf(crc,sizeof(crc),"%08x",crc32_bytes(body));return body+"crc32\t"+crc+"\n";
}
inline bool owner_decode(const std::string &wire,OwnerRecord &out){
    if(wire.empty()||wire.size()>1024||wire.find('\r')!=std::string::npos||wire.find('\0')!=std::string::npos)return false;
    const auto newline=wire.find('\n');if(newline==std::string::npos)return false;
    const auto body=wire.substr(0,newline+1);
    std::vector<std::string> f;size_t from=0;
    for(size_t i=0;i<body.size();++i)if(body[i]=='\t'){f.push_back(body.substr(from,i-from));from=i+1;}
    f.push_back(body.substr(from));if(f.size()!=13||f[0]!="o1"||f[12]!="end\n")return false;
    uint64_t pid{},epoch{},written{},until{};
    if(!number(f[1],999999999,pid)||!number(f[2],std::numeric_limits<uint64_t>::max(),epoch)||
       !number(f[10],9999999999999ULL,written)||!number(f[11],9999999999999ULL,until))return false;
    OwnerRecord r;r.pid=static_cast<int>(pid);r.epoch=epoch;r.account=f[3];r.owner=f[4];r.token=f[5];
    r.operation=f[6];r.boot=f[7];r.core_sha=f[8];r.apk_sha=f[9];r.written=written;r.until=until;
    if(owner_wire(r)!=wire)return false;out=r;return true;
}

struct RuntimePair {
    int pid{};uint64_t epoch{};
    std::string boot,account,owner,token,operation,core_sha,apk_sha;
    int64_t sampled{};
    bool writers_excluded{};
};
struct OwnerEnvironment {
    virtual ~OwnerEnvironment()=default;
    // MUST independently verify loaded same-PID core + installed signed APK
    // equivalence AND exclusion of ALL legacy/App/root GPS writers. No record or
    // command argument, no default implementation. Runtime TSV != payload proof.
    virtual bool read(RuntimePair &)=0;
    virtual int64_t now()=0;
};

// Concrete namespace ownership/record reader, NOT a completed global handoff.
// No mkdir/write/repair/recovery. Existing zero-byte private work.lock and
// authority.tsv must be created by an independently audited producer. CRC32 is
// corruption detection only, not root/game-UID authentication.
class OwnerAuthority final:public Authority {
    FileStore &store_;OwnerEnvironment &environment_;
    std::string root_;
    NativeFd directory_,work_;
    struct stat directory_identity_{},work_identity_{};
    bool entered_{};
    static bool same(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
    static bool regular(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==geteuid()&&s.st_nlink==1&&(s.st_mode&0777)==0600;}
    bool identities(){
        if(!entered_||!store_.is_open_at(root_,directory_identity_))return false;
        struct stat dir{},lock{},leaf{};
        return fstat(directory_.fd,&dir)==0&&S_ISDIR(dir.st_mode)&&dir.st_uid==geteuid()&&
            (dir.st_mode&0777)==0700&&same(dir,directory_identity_)&&
            fstat(work_.fd,&lock)==0&&regular(lock)&&lock.st_size==0&&same(lock,work_identity_)&&
            fstatat(directory_.fd,"work.lock",&leaf,AT_SYMLINK_NOFOLLOW)==0&&regular(leaf)&&
            leaf.st_size==0&&same(leaf,work_identity_);
    }
    bool temporary_absent(){struct stat s{};if(fstatat(directory_.fd,"authority.tsv.tmp",&s,AT_SYMLINK_NOFOLLOW)==0)return false;return errno==ENOENT;}
    bool record(OwnerRecord &out,std::string &wire){
        if(!identities()||!temporary_absent())return false;
        struct stat before{},held{},after{},leaf{};
        if(fstatat(directory_.fd,"authority.tsv",&before,AT_SYMLINK_NOFOLLOW)!=0||!regular(before)||
           before.st_size<=0||before.st_size>1024)return false;
        NativeFd fd(openat(directory_.fd,"authority.tsv",O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC));
        if(fd.fd<0||fstat(fd.fd,&held)!=0||!regular(held)||!same(before,held)||held.st_size!=before.st_size)return false;
        std::string data(static_cast<size_t>(held.st_size),'\0');size_t at=0;
        while(at<data.size()){auto n=::read(fd.fd,&data[at],data.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        char extra{};ssize_t n;do{n=::read(fd.fd,&extra,1);}while(n<0&&errno==EINTR);
        if(n!=0||fstat(fd.fd,&after)!=0||!regular(after)||after.st_size!=held.st_size||
           fstatat(directory_.fd,"authority.tsv",&leaf,AT_SYMLINK_NOFOLLOW)!=0||!regular(leaf)||
           !same(held,leaf)||leaf.st_size!=held.st_size||!fd.close_checked()||!identities()||!temporary_absent())return false;
        OwnerRecord value;if(!owner_decode(data,value))return false;out=value;wire=data;return true;
    }
    static bool boot_id(std::string &boot){
        // Fixed proc path; proc files commonly report size 0. Do not substitute
        // a configurable fixture path or trust a boot UUID from the owner file.
        NativeFd fd(::open("/proc/sys/kernel/random/boot_id",O_RDONLY|O_NOFOLLOW|O_CLOEXEC));
        struct stat st{};char data[39]{};
        if(fd.fd<0||fstat(fd.fd,&st)!=0||!S_ISREG(st.st_mode)||st.st_uid!=0)return false;
        ssize_t n;do{n=::read(fd.fd,data,sizeof(data));}while(n<0&&errno==EINTR);
        if(n!=37||data[36]!='\n')return false;
        char extra{};ssize_t more;do{more=::read(fd.fd,&extra,1);}while(more<0&&errno==EINTR);
        if(more!=0||!fd.close_checked())return false;std::string value(data,36);if(!uuid(value))return false;boot=value;return true;
    }
    bool runtime(RuntimePair &p,int64_t at){
        if(!environment_.read(p))return false;
        const auto checked=environment_.now();
        return checked>=at&&checked-at<=1000&&p.pid==getpid()&&p.epoch>0&&uuid(p.boot)&&digest(p.account)&&
            uuid(p.owner)&&uuid(p.token)&&uuid(p.operation)&&
            digest(p.core_sha)&&digest(p.apk_sha)&&p.writers_excluded&&p.sampled>=1000000000000LL&&
            p.sampled<=checked&&checked-p.sampled<=5000;
    }
public:
    OwnerAuthority(FileStore &store,OwnerEnvironment &environment,const std::string &root):
        store_(store),environment_(environment),root_(root){}
    ~OwnerAuthority(){leave();}
    OwnerAuthority(const OwnerAuthority &)=delete;OwnerAuthority &operator=(const OwnerAuthority &)=delete;
    bool enter(){
        if(entered_||!store_.is_open_at(root_))return false;
        directory_.fd=::open(root_.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(directory_.fd<0||fstat(directory_.fd,&directory_identity_)!=0){leave();return false;}
        struct stat leaf{};
        if(fstatat(directory_.fd,"work.lock",&leaf,AT_SYMLINK_NOFOLLOW)!=0||!regular(leaf)||leaf.st_size!=0){leave();return false;}
        work_.fd=openat(directory_.fd,"work.lock",O_RDWR|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(work_.fd<0||fstat(work_.fd,&work_identity_)!=0||!regular(work_identity_)||work_identity_.st_size!=0||
           !same(leaf,work_identity_)||flock(work_.fd,LOCK_EX|LOCK_NB)!=0){leave();return false;}
        entered_=true;if(!identities()){leave();return false;}return true;
    }
    void leave(){entered_=false;if(work_.fd>=0)work_.close_checked();if(directory_.fd>=0)directory_.close_checked();}
    bool read(Grant &out)override {
        if(!identities())return false;
        const auto begin=environment_.now();
        if(begin<1000000000000LL||begin>9999999999999LL)return false;
        std::string boot,before,after;OwnerRecord r,rechecked;RuntimePair live,fresh;
        if(!boot_id(boot)||!record(r,before)||!runtime(live,begin)||r.pid!=live.pid||r.epoch!=live.epoch||
           r.boot!=boot||live.boot!=boot||r.account!=live.account||r.core_sha!=live.core_sha||r.apk_sha!=live.apk_sha||
           r.owner!=live.owner||r.token!=live.token||r.operation!=live.operation||
           begin<r.written||begin>=r.until)return false;
        const auto end=environment_.now();
        if(end<begin||end-begin>1000||end>=r.until||!runtime(fresh,end)||
           fresh.pid!=live.pid||fresh.epoch!=live.epoch||fresh.boot!=live.boot||fresh.account!=live.account||
           fresh.owner!=live.owner||fresh.token!=live.token||fresh.operation!=live.operation||fresh.sampled<live.sampled||
           fresh.core_sha!=live.core_sha||fresh.apk_sha!=live.apk_sha||!record(rechecked,after)||after!=before||!identities())return false;
        const auto final_now=environment_.now();
        if(final_now<end||final_now-begin>1000||final_now>=r.until||
           final_now-live.sampled>5000||final_now-fresh.sampled>5000)return false;
        Grant g;g.pid=live.pid;g.epoch=live.epoch;g.boot=boot;g.account=live.account;g.owner=r.owner;
        g.token=r.token;g.operation=r.operation;g.sampled=final_now;
        g.core_sha=live.core_sha;g.apk_sha=live.apk_sha;
        g.namespace_lock=true;g.global_lock=true;g.legacy_quiescent=true;g.core_paired=true;g.apk_paired=true;
        out=g;return true;
    }
};
}

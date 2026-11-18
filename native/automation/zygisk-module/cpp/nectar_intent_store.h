#pragma once
#include <atomic>
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>
#include "nectar_intent_policy.h"

namespace nectar_intent {
class FileStore {
    std::string directory_;
    static constexpr const char* leaf="nectar_intent.tsv";
    static bool regular(const struct stat&s){return S_ISREG(s.st_mode)&&s.st_uid==getuid()&&s.st_nlink==1;}
    struct Lease {
        int dir{-1},lock{-1};bool held{};
        explicit Lease(const std::string& path){
            dir=open(path.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            struct stat s{};
            if(dir<0||fstat(dir,&s)||!S_ISDIR(s.st_mode)||s.st_uid!=getuid()||(s.st_mode&0002)||((s.st_mode&0020)&&s.st_gid!=getgid()))return;
            lock=openat(dir,"nectar_intent.lock",O_RDWR|O_CREAT|O_NOFOLLOW|O_CLOEXEC,0600);
            if(lock<0||fstat(lock,&s)||!regular(s))return;
            held=flock(lock,LOCK_EX|LOCK_NB)==0;
        }
        ~Lease(){if(held)flock(lock,LOCK_UN);if(lock>=0)close(lock);if(dir>=0)close(dir);}
    };
    static bool read_locked(int dir,Record& record,bool& exists,struct stat& identity){
        exists=false;int fd=openat(dir,leaf,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0)return errno==ENOENT;
        bool ok=fstat(fd,&identity)==0&&regular(identity)&&identity.st_size>0&&identity.st_size<=1024;
        char buffer[1025];size_t used=0;
        while(ok&&used<sizeof(buffer)){
            ssize_t n=::read(fd,buffer+used,sizeof(buffer)-used);
            if(n<0&&errno==EINTR)continue;
            if(n<0){ok=false;break;}if(n==0)break;used+=static_cast<size_t>(n);
        }
        if(close(fd))ok=false;
        if(!ok||used!=static_cast<size_t>(identity.st_size)||used>1024)return false;
        exists=true;return decode(std::string(buffer,used),record);
    }
public:
    explicit FileStore(std::string directory):directory_(std::move(directory)){}
    static std::string quarantine_leaf(const std::string& account,const std::string& target){
        return "nectar-unconfirmed-"+pikmin::account_digest(account+"\t"+target)+".tsv";
    }
    bool excluded(const std::string& account,const std::string& target,bool& found){
        found=false;if(!hex64(account)||!hex64(target))return false;
        Lease lease(directory_);if(!lease.held)return false;
        const auto name=quarantine_leaf(account,target);
        int fd=openat(lease.dir,name.c_str(),O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0)return errno==ENOENT;
        struct stat s{};char bytes[1025];ssize_t n=::read(fd,bytes,sizeof(bytes));
        bool ok=fstat(fd,&s)==0&&regular(s)&&n>0&&n<=1024&&n==s.st_size;
        if(close(fd))ok=false;
        Record archived;
        ok=ok&&decode(std::string(bytes,n>0?static_cast<size_t>(n):0),archived)&&archived.phase=="pending"&&archived.account==account&&archived.target==target;
        found=ok;return ok;
    }
    bool quarantine(const Record& expected){
        if(!valid(expected)||expected.phase!="pending")return false;
        Lease lease(directory_);if(!lease.held)return false;
        Record actual;bool exists=false;struct stat before{};
        if(!read_locked(lease.dir,actual,exists,before)||!exists||!equal(actual,expected))return false;
        // No replacement: preserve the exact original inode before retiring the
        // active slot. A crash or uncertain IO fails closed, never authorizes RPC.
        const auto name=quarantine_leaf(expected.account,expected.target);
        struct stat current{};
        if(fstatat(lease.dir,leaf,&current,AT_SYMLINK_NOFOLLOW)||current.st_dev!=before.st_dev||current.st_ino!=before.st_ino)return false;
        // Atomic NOREPLACE rename also works under Android's app SELinux policy,
        // which may deny hard links. Unsupported kernels fail closed.
        if(syscall(SYS_renameat2,lease.dir,leaf,lease.dir,name.c_str(),1)||fsync(lease.dir))return false;
        return true;
    }
    bool read(Record& record,bool& exists){
        Lease lease(directory_);struct stat ignored{};
        return lease.held&&read_locked(lease.dir,record,exists,ignored);
    }
    bool replace(const Record* expected,const Record& next){
        if(!transition(expected,next))return false;
        Lease lease(directory_);if(!lease.held)return false;
        Record actual;bool exists=false;struct stat before{};
        if(!read_locked(lease.dir,actual,exists,before)||exists!=(expected!=nullptr)||
            (exists&&!equal(actual,*expected)))return false;
        if(next.phase=="pending"){
            struct stat excluded{};
            if(fstatat(lease.dir,quarantine_leaf(next.account,next.target).c_str(),&excluded,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
        }
        const auto bytes=encode(next);if(bytes.empty())return false;
        static std::atomic<uint64_t> sequence{0};
        const auto temp=std::string(leaf)+".tmp."+std::to_string(getpid())+"."+std::to_string(++sequence);
        int fd=openat(lease.dir,temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return false;
        bool ok=true;size_t done=0;
        while(done<bytes.size()){
            ssize_t n=::write(fd,bytes.data()+done,bytes.size()-done);
            if(n<0&&errno==EINTR)continue;
            if(n<=0){ok=false;break;}done+=static_cast<size_t>(n);
        }
        if(fsync(fd))ok=false;if(close(fd))ok=false;
        struct stat current{};int current_result=fstatat(lease.dir,leaf,&current,AT_SYMLINK_NOFOLLOW);
        if(exists){
            ok=ok&&current_result==0&&regular(current)&&current.st_dev==before.st_dev&&
                current.st_ino==before.st_ino&&current.st_size==before.st_size;
        }else ok=ok&&current_result<0&&errno==ENOENT;
        bool renamed=false;
        if(ok){renamed=renameat(lease.dir,temp.c_str(),lease.dir,leaf)==0;ok=renamed;}
        // Only our own temporary file is removed. Never remove the receipt.
        if(!renamed)unlinkat(lease.dir,temp.c_str(),0);
        if(ok&&fsync(lease.dir))ok=false;
        return ok;
    }
};
}

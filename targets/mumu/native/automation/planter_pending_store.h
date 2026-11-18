#pragma once
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace pikmin::planter {
// Private per-game-directory journal. No parser is required to preserve unknown
// old versions: archival atomically retains the original inode without replacement.
class PendingStore {
    std::string directory_;
    static constexpr const char *leaf="planter_pending.tsv";
    static bool regular(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==getuid();}
    struct Lease {
        int dir{-1},lock{-1};bool held{};
        explicit Lease(const std::string &path){
            dir=open(path.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            struct stat s{};
            if(dir<0||fstat(dir,&s)||!S_ISDIR(s.st_mode)||s.st_uid!=getuid()||(s.st_mode&0002)||((s.st_mode&0020)&&s.st_gid!=getgid()))return;
            lock=openat(dir,"planter_pending.lock",O_RDWR|O_CREAT|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC,0600);
            if(lock<0||fstat(lock,&s)||!regular(s)||s.st_nlink!=1)return;
            held=flock(lock,LOCK_EX|LOCK_NB)==0;
        }
        ~Lease(){if(held)flock(lock,LOCK_UN);if(lock>=0)close(lock);if(dir>=0)close(dir);}
    };
public:
    enum class Archive { absent, retained, retry };
    explicit PendingStore(std::string directory):directory_(std::move(directory)){}
    bool create(const std::string &bytes){
        if(bytes.empty()||bytes.size()>2048)return false;
        Lease l(directory_);if(!l.held)return false;
        int fd=openat(l.dir,leaf,O_WRONLY|O_CREAT|O_EXCL|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return false; // Never overwrite an old, partial, or foreign request.
        bool ok=true;size_t done=0;
        while(done<bytes.size()){
            ssize_t n=write(fd,bytes.data()+done,bytes.size()-done);
            if(n<0&&errno==EINTR)continue;
            if(n<=0){ok=false;break;}done+=static_cast<size_t>(n);
        }
        if(fsync(fd))ok=false;
        if(close(fd))ok=false;
        if(fsync(l.dir))ok=false;
        // Partial writes stay as UNKNOWN evidence; the next tick archives them.
        return ok;
    }
    Archive archive(bool confirmed,int64_t monotonic,std::string &retained){
        retained.clear();Lease l(directory_);if(!l.held)return Archive::retry;
        struct stat before{};
        if(fstatat(l.dir,leaf,&before,AT_SYMLINK_NOFOLLOW))return errno==ENOENT?Archive::absent:Archive::retry;
        if(!regular(before))return Archive::retry; // No FIFO reads, symlink following, or directory moves.
        static std::atomic<uint64_t> sequence{0};
        for(int i=0;i<64;++i){
            const auto name=std::string(leaf)+(confirmed?".confirmed.":".unknown.")+
                std::to_string(getpid())+"."+std::to_string(monotonic)+"."+std::to_string(++sequence);
            struct stat current{};
            if(fstatat(l.dir,leaf,&current,AT_SYMLINK_NOFOLLOW)||!regular(current)||
               current.st_dev!=before.st_dev||current.st_ino!=before.st_ino||current.st_size!=before.st_size)return Archive::retry;
            // Android app SELinux may forbid hard links. NOREPLACE rename keeps
            // every byte/inode, never overwrites a collision, and leaves .recovered
            // alone. Unsupported kernels fail closed; no copy/unlink fallback.
            if(syscall(SYS_renameat2,l.dir,leaf,l.dir,name.c_str(),1 /* RENAME_NOREPLACE */)){
                if(errno==EEXIST)continue;
                return Archive::retry;
            }
            if(fsync(l.dir))return Archive::retry; // The archived original is retained even on uncertain durability.
            if(fstatat(l.dir,name.c_str(),&current,AT_SYMLINK_NOFOLLOW)||!regular(current)||
               current.st_dev!=before.st_dev||current.st_ino!=before.st_ino||current.st_size!=before.st_size)return Archive::retry;
            retained=name;return Archive::retained;
        }
        return Archive::retry;
    }
};
}

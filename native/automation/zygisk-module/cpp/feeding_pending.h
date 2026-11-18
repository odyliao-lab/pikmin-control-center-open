#pragma once
#include <cerrno>
#include <cstdint>
#include <string>
#include <sstream>
#include <vector>
#include <set>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace feeding_pending {
// A durable intent is not proof that an RPC ran. Keep it until it is either
// confirmed or atomically moved to an unconfirmed audit file. A new send must
// never overwrite an unresolved intent in place.
struct Intent {
    std::string request, account, action, kind, pikmin;
    int64_t epoch{}, nectar_before{-1}, petals_before{-1}, cost{-1}, yield{-1};
    // v2 batches retain every target identity. Empty preserves canonical v1.
    std::vector<std::string> targets;
};
inline bool token(const std::string &s, size_t limit) {
    if (s.empty() || s.size() > limit) return false;
    for (unsigned char c : s) if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
        (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == ':')) return false;
    return true;
}
inline bool valid(const Intent &v) {
    if (!token(v.request,96) || v.account.size()!=64 || !token(v.kind,96) || !token(v.pikmin,256)) return false;
    for(char c:v.account) if(!((c>='0'&&c<='9')||(c>='a'&&c<='f'))) return false;
    if(!v.targets.empty()) {
        if(v.targets.size()>40 || v.targets.front()!=v.pikmin) return false;
        std::set<std::string> unique;
        for(const auto &id:v.targets)if(!token(id,256)||!unique.insert(id).second)return false;
        if(v.action=="feed" && v.cost!=static_cast<int64_t>(v.targets.size()))return false;
    }
    if(v.action=="rotate" && (v.targets.empty()||v.targets.size()%2||v.kind!="squad-rotation"||v.cost!=0||v.yield!=0))return false;
    return (v.action=="feed" || v.action=="collect" || v.action=="rotate") && v.epoch>0 &&
        v.nectar_before>=0 && v.nectar_before<=1000000 && v.petals_before>=0 && v.petals_before<=1000000 &&
        v.cost>=0 && v.cost<=1000000 && v.yield>=0 && v.yield<=1000000 &&
        (v.action=="feed" ? v.cost>0 : v.cost==0);
}
inline std::string encode(const Intent &v) {
    if(!valid(v)) return {};
    std::string result=std::string(v.targets.empty()?"v1\t":"v2\t")+v.request+"\t"+v.account+"\t"+v.action+"\t"+v.kind+"\t"+v.pikmin+"\t"+
        std::to_string(v.epoch)+"\t"+std::to_string(v.nectar_before)+"\t"+std::to_string(v.petals_before)+"\t"+
        std::to_string(v.cost)+"\t"+std::to_string(v.yield)+"\n";
    for(const auto &id:v.targets)result+="target\t"+id+"\n";
    return result+"end\n";
}
inline bool decode(const std::string &s, Intent &out) {
    out={}; if(s.size()>16384) return false;
    std::istringstream in(s); Intent v; std::string version,end,extra;
    if(!(in>>version>>v.request>>v.account>>v.action>>v.kind>>v.pikmin>>v.epoch>>v.nectar_before>>v.petals_before>>v.cost>>v.yield))return false;
    if(version!="v1" && version!="v2")return false;
    while(in>>end && end=="target") {
        if(version!="v2" || !(in>>extra) || v.targets.size()>=40)return false;
        v.targets.push_back(extra);
    }
    if(end!="end" || (version=="v2" && v.targets.empty()) || (in>>extra) || !valid(v) || encode(v)!=s)return false;
    out=v; return true;
}
enum class State { absent, pending, unreadable };
class DirectoryLock {
    int fd_;
public:
    bool held{};
    explicit DirectoryLock(int fd):fd_(fd),held(fd>=0 && flock(fd,LOCK_EX)==0){}
    ~DirectoryLock(){if(held)flock(fd_,LOCK_UN);}
};
class Journal {
    int dir_{-1};
public:
    // Caller provides a dedicated existing private game directory. Never mkdir
    // through untrusted account/ID components or follow a final symlink.
    explicit Journal(const char *directory):dir_(open(directory,O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC)) {}
    ~Journal(){if(dir_>=0)close(dir_);}
    Journal(const Journal&)=delete;
    Journal& operator=(const Journal&)=delete;
    State load(Intent &out) const {
        out={}; if(dir_<0)return State::unreadable;
        int fd=openat(dir_,"feeding_pending.tsv",O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0)return errno==ENOENT ? State::absent : State::unreadable;
        struct stat st{}; std::string data; bool ok=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_size<=16384;
        char buf[512]; ssize_t n=0;
        while(ok && (n=read(fd,buf,sizeof(buf)))!=0) {
            if(n<0){if(errno==EINTR)continue;ok=false;break;}
            data.append(buf,static_cast<size_t>(n)); if(data.size()>16384)ok=false;
        }
        close(fd);
        return ok && decode(data,out) ? State::pending : State::unreadable;
    }
    bool prepare(const Intent &v) const {
        const std::string bytes=encode(v); if(dir_<0||bytes.empty())return false;
        DirectoryLock lock(dir_);if(!lock.held)return false;
        struct stat completed{};
        const auto receipt="feeding-confirmed-"+v.request+".tsv";
        if(fstatat(dir_,receipt.c_str(),&completed,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return false;
        const auto resolved="feeding-resolved-"+v.request+".tsv";
        if(fstatat(dir_,resolved.c_str(),&completed,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return false;
        const auto unconfirmed="feeding-unconfirmed-"+v.request+".tsv";
        if(fstatat(dir_,unconfirmed.c_str(),&completed,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return false;
        // O_EXCL is the interprocess send gate. Do not unlink on any error:
        // even a partial write remains visible as unreadable/pending review.
        int fd=openat(dir_,"feeding_pending.tsv",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return false;
        size_t offset=0; bool ok=true;
        while(offset<bytes.size()) {
            ssize_t n=write(fd,bytes.data()+offset,bytes.size()-offset);
            if(n<0&&errno==EINTR)continue;
            if(n<=0){ok=false;break;} offset+=static_cast<size_t>(n);
        }
        if(fsync(fd)!=0)ok=false;
        if(close(fd)!=0)ok=false;
        if(fsync(dir_)!=0)ok=false;
        return ok;
    }
    // Caller must first validate receipt evidence. This operation never clears
    // an arbitrary pending file: exact canonical intent must still match, and
    // its durable receipt must exist before unlinking the send gate.
    bool archive_confirmed(const Intent &expected,const std::string &receipt,bool all_confirmed=true) const {
        if(dir_<0 || !valid(expected) || receipt.empty() || receipt.size()>32768)return false;
        DirectoryLock lock(dir_);if(!lock.held)return false;
        Intent current;
        if(load(current)!=State::pending || encode(current)!=encode(expected))return false;
        const auto name=std::string(all_confirmed?"feeding-confirmed-":"feeding-resolved-")+expected.request+".tsv";
        int fd=openat(dir_,name.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return false; // Existing/partial evidence requires review, not overwrite.
        size_t offset=0;bool ok=true;
        while(offset<receipt.size()) {
            ssize_t n=write(fd,receipt.data()+offset,receipt.size()-offset);
            if(n<0 && errno==EINTR)continue;
            if(n<=0){ok=false;break;}offset+=static_cast<size_t>(n);
        }
        if(fsync(fd)!=0)ok=false;
        if(close(fd)!=0)ok=false;
        if(fsync(dir_)!=0)ok=false;
        if(!ok)return false;
        if(unlinkat(dir_,"feeding_pending.tsv",0)!=0)return false;
        return fsync(dir_)==0;
    }
    // The RPC may have run even when its in-memory Task was lost. Preserve the
    // exact original intent before releasing the single-send gate. The caller
    // must first persist a target cooldown; rename is atomic within this dir.
    bool archive_unconfirmed(const Intent &expected) const {
        if(dir_<0 || !valid(expected))return false;
        DirectoryLock lock(dir_);if(!lock.held)return false;
        Intent current;
        if(load(current)!=State::pending || encode(current)!=encode(expected))return false;
        const auto name=std::string("feeding-unconfirmed-")+expected.request+".tsv";
        struct stat st{};
        if(fstatat(dir_,name.c_str(),&st,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return false;
        if(renameat(dir_,"feeding_pending.tsv",dir_,name.c_str())!=0)return false;
        return fsync(dir_)==0;
    }
    // A torn regular intent has no trustworthy account/target fields. Keep its
    // raw bytes, then let the coordinator apply a durable global cooldown.
    // A symlink, non-regular file or I/O failure is not safe to quarantine.
    bool archive_unreadable(int64_t epoch) const {
        if(dir_<0 || epoch<=0)return false;
        DirectoryLock lock(dir_);if(!lock.held)return false;
        struct stat source{},destination{};
        if(fstatat(dir_,"feeding_pending.tsv",&source,AT_SYMLINK_NOFOLLOW)!=0 ||
            !S_ISREG(source.st_mode) || source.st_size>16384 || source.st_size<0)return false;
        Intent ignored;
        if(load(ignored)!=State::unreadable)return false;
        const auto name="feeding-unreadable-"+std::to_string(getpid())+"-"+std::to_string(epoch)+".tsv";
        if(fstatat(dir_,name.c_str(),&destination,AT_SYMLINK_NOFOLLOW)==0 || errno!=ENOENT)return false;
        if(renameat(dir_,"feeding_pending.tsv",dir_,name.c_str())!=0)return false;
        return fsync(dir_)==0;
    }
    bool has_archived(const Intent &expected) const {
        if(dir_<0 || !valid(expected))return false;
        const auto prefix=encode(expected);
        for(const char *kind:{"unconfirmed","confirmed","resolved"}) {
            const auto name=std::string("feeding-")+kind+"-"+expected.request+".tsv";
            int fd=openat(dir_,name.c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
            if(fd<0)continue;
            struct stat st{};bool good=fstat(fd,&st)==0 && S_ISREG(st.st_mode) && st.st_size>=0 && st.st_size<=32768;
            std::string raw;char buf[512];ssize_t n=0;
            while(good && (n=read(fd,buf,sizeof(buf)))!=0) {
                if(n<0){if(errno==EINTR)continue;good=false;break;}
                raw.append(buf,static_cast<size_t>(n));if(raw.size()>32768)good=false;
            }
            close(fd);
            if(good && raw.compare(0,prefix.size(),prefix)==0 &&
                (std::string(kind)!="unconfirmed" || raw==prefix))return true;
        }
        return false;
    }
};
}

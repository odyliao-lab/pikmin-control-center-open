#pragma once
#include <cerrno>
#include <fcntl.h>
#include <string>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace emu_gps_work {
// Common kernel fence for cooperating Root GPS actors. NOT an all-writer
// attestation: every App/legacy/Root writer must join this protocol, and the
// provider queue needs an independently verified barrier before daily admission.
// This class does not publish WriterLease, send intents, repair or remove files.
class RootGpsFence final {
    int directory_{-1},lock_{-1};pid_t holder_{};
    struct stat parent_{},held_{};std::string path_;
    static bool same(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
    static bool parent(const struct stat &s){return S_ISDIR(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0711;}
    static bool leaf(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0600&&s.st_nlink==1&&s.st_size==0;}
    // Traverse held directory FDs: O_NOFOLLOW on only the final path component
    // would still admit a symlink in an ancestor. Never mkdir or follow '..'.
    static int open_directory(const std::string &path){
        if(path.empty()||path.size()>512||path[0]!='/'||path.back()=='/'||path.find('\0')!=std::string::npos)return -1;
        int fd=open("/",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return -1;
        size_t at=1;
        while(at<path.size()){
            const auto end=path.find('/',at);const auto part=path.substr(at,end==std::string::npos?end:end-at);
            if(part.empty()||part=="."||part==".."){close(fd);return -1;}
            const int next=openat(fd,part.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            const bool closed=close(fd)==0;fd=next;
            if(!closed||fd<0){if(fd>=0)close(fd);return -1;}
            if(end==std::string::npos)break;at=end+1;
        }
        return fd;
    }
    bool acquire_at(const std::string &path){
        if(geteuid()!=0||directory_>=0||lock_>=0)return false;
        path_=path;holder_=getpid();directory_=open_directory(path);
        if(directory_<0||fstat(directory_,&parent_)!=0||!parent(parent_)){release();return false;}
        struct stat before{};bool created=false;
        if(fstatat(directory_,"gps.lock",&before,AT_SYMLINK_NOFOLLOW)!=0){
            if(errno!=ENOENT){release();return false;}
            lock_=openat(directory_,"gps.lock",O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);created=true;
        }else{
            if(!leaf(before)){release();return false;}
            lock_=openat(directory_,"gps.lock",O_RDWR|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        }
        if(lock_<0||fstat(lock_,&held_)!=0||!leaf(held_)||(!created&&!same(before,held_))||
           flock(lock_,LOCK_EX|LOCK_NB)!=0||!valid()){release();return false;}
        return true;
    }
public:
    RootGpsFence()=default;
    RootGpsFence(const RootGpsFence &)=delete;
    RootGpsFence &operator=(const RootGpsFence &)=delete;
    ~RootGpsFence(){release();}
    bool acquire(){return acquire_at("/data/local/tmp/pcc-emulator-gps-work");}
    // Root producer only. Caller owns the duplicate, and must recheck valid()
    // before/after publishing. This is not a transferable lock or authority.
    int observation_directory(){return valid()?fcntl(directory_,F_DUPFD_CLOEXEC,3):-1;}
#ifdef PCC_GPS_FENCE_FIXTURE
    // Compiled only into isolated fixtures, never product JNI/loader targets.
    bool acquire_fixture(const std::string &path){return acquire_at(path);}
#endif
    bool valid(){
        if(geteuid()!=0||holder_!=getpid()||directory_<0||lock_<0)return false;
        struct stat dir{},lock{},named{},path{};
        const int fresh=open_directory(path_);if(fresh<0)return false;
        const bool current=fstat(fresh,&path)==0&&parent(path)&&same(path,parent_);
        const bool closed=close(fresh)==0;
        return current&&closed&&fstat(directory_,&dir)==0&&parent(dir)&&same(dir,parent_)&&
            fstat(lock_,&lock)==0&&leaf(lock)&&same(lock,held_)&&
            fstatat(directory_,"gps.lock",&named,AT_SYMLINK_NOFOLLOW)==0&&leaf(named)&&same(lock,named);
    }
    bool release(){
        // Close, never LOCK_UN: after fork an explicit unlock on the shared
        // open-file description could silently release the parent's fence.
        bool ok=true;
        if(lock_>=0){const int fd=lock_;lock_=-1;ok=close(fd)==0;}
        if(directory_>=0){const int fd=directory_;directory_=-1;ok=(close(fd)==0)&&ok;}
        holder_=0;path_.clear();return ok;
    }
};
}

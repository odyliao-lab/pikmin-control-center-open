#pragma once
#include <cstdio>
#include <cstdint>
#include <cerrno>
#include <string>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
namespace emu_atomic_projection {
// Read-only projection publication, not gameplay/admission authority. Readers
// keep the previous complete inode until every new row is flushed and durable.
class Writer {
    int dir{-1};FILE *out{};std::string leaf,temp;struct stat prior{};
    bool existed{},owned{};
    static bool safe(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==getuid()&&s.st_nlink==1;}
    bool destination_unchanged(){
        struct stat current{};int result=fstatat(dir,leaf.c_str(),&current,AT_SYMLINK_NOFOLLOW);
        if(!existed)return result<0&&errno==ENOENT;
        return result==0&&safe(current)&&current.st_dev==prior.st_dev&&current.st_ino==prior.st_ino&&current.st_size==prior.st_size;
    }
public:
    Writer(const char *path,int64_t stamp){
        if(!path||stamp<=0)return;std::string p(path);auto slash=p.find_last_of('/');
        if(slash==std::string::npos||slash+1==p.size())return;
        leaf=p.substr(slash+1);if(leaf!="dispatch_candidates.tsv")return;
        dir=open(p.substr(0,slash).c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(dir<0)return;struct stat d{};
        if(fstat(dir,&d)!=0||!S_ISDIR(d.st_mode)||d.st_uid!=getuid())return;
        if(fstatat(dir,leaf.c_str(),&prior,AT_SYMLINK_NOFOLLOW)==0){if(!safe(prior))return;existed=true;}
        else if(errno!=ENOENT)return;
        temp=leaf+".snapshot."+std::to_string(getpid())+"."+std::to_string(stamp);
        int fd=openat(dir,temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
        if(fd<0)return;owned=true;struct stat s{};
        if(fstat(fd,&s)!=0||!safe(s)){close(fd);return;}
        out=fdopen(fd,"w");if(!out)close(fd);
    }
    Writer(const Writer &)=delete;Writer &operator=(const Writer &)=delete;
    FILE *stream()const{return out;}
    bool commit(){
        if(!out)return false;
        bool ok=fflush(out)==0&&!ferror(out)&&fchmod(fileno(out),0644)==0&&fsync(fileno(out))==0;
        if(fclose(out)!=0)ok=false;out=nullptr;
        if(!ok||!destination_unchanged())return false;
        if(renameat(dir,temp.c_str(),dir,leaf.c_str())!=0)return false;
        owned=false;return fsync(dir)==0;
    }
    ~Writer(){if(out)fclose(out);if(owned&&dir>=0)unlinkat(dir,temp.c_str(),0);if(dir>=0)close(dir);}
};
}

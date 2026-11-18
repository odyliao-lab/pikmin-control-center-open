#pragma once
#include "root_apk_observation.h"
namespace emu_payload {
// Exclusivity for the Root OBSERVATION producer only. Not the gameplay/GPS
// owner lock. Existing malformed files are preserved, never repaired/deleted.
class RootObserverLease {
    int directory_{-1},lock_{-1};struct stat parent_{},held_{};
    static bool regular(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0600&&s.st_nlink==1&&s.st_size==0;}
    static bool same(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
public:
    bool valid(){
        struct stat parent{},path{},held{},leaf{};
        return geteuid()==0&&directory_>=0&&lock_>=0&&fstat(directory_,&parent)==0&&root_apk_detail::directory(parent)&&same(parent,parent_)&&
            lstat("/data/local/tmp/pcc-emulator-apk-proof",&path)==0&&root_apk_detail::directory(path)&&same(path,parent_)&&
            fstat(lock_,&held)==0&&regular(held)&&same(held,held_)&&
            fstatat(directory_,"observer.lock",&leaf,AT_SYMLINK_NOFOLLOW)==0&&regular(leaf)&&same(held,leaf);
    }
    bool acquire(){
        if(geteuid()!=0||directory_>=0||lock_>=0)return false;
        directory_=open("/data/local/tmp/pcc-emulator-apk-proof",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
        if(directory_<0||fstat(directory_,&parent_)!=0||!root_apk_detail::directory(parent_)){release();return false;}
        struct stat leaf{};bool created=false;
        if(fstatat(directory_,"observer.lock",&leaf,AT_SYMLINK_NOFOLLOW)!=0){
            if(errno!=ENOENT){release();return false;}
            lock_=openat(directory_,"observer.lock",O_RDWR|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);created=true;
        }else{
            if(!regular(leaf)){release();return false;}
            lock_=openat(directory_,"observer.lock",O_RDWR|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        }
        if(lock_<0||fstat(lock_,&held_)!=0||!regular(held_)||(!created&&!same(leaf,held_))||flock(lock_,LOCK_EX|LOCK_NB)!=0||!valid()){
            release();return false;
        }
        return true;
    }
    bool release(){
        bool good=true;if(lock_>=0){const int fd=lock_;lock_=-1;good=close(fd)==0;}
        if(directory_>=0){const int fd=directory_;directory_=-1;good=(close(fd)==0)&&good;}return good;
    }
    ~RootObserverLease(){release();}
    RootObserverLease()=default;
    RootObserverLease(const RootObserverLease &)=delete;
    RootObserverLease &operator=(const RootObserverLease &)=delete;
};
}

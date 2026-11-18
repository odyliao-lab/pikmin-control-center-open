#pragma once
#include <array>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <string>
#include "daily_resources_policy.h"

// Passive counter storage only. Never reads/clears a predecessor's .tmp, never
// sends a gameplay request. Capture and publication freshness gates stay intact.
namespace emu_resource_store {
enum class Stage { capture, directory, destination, create, metadata, write,
                   sync, close, recheck, rename, directory_sync, complete, blocked, count };
inline const char *name(Stage s) {
    static const char *names[]={"capture","directory","destination","create","metadata","write",
        "sync","close","recheck","rename","directory-sync","complete","blocked"};
    return names[static_cast<unsigned>(s)];
}
struct State {
    uint64_t sequence=0;
    bool blocked=false;
    std::array<bool,static_cast<unsigned>(Stage::count)> recorded{};
};
struct Result {Stage stage;int error;bool evidence=false;bool cleaned=false;};
inline bool regular(const struct stat &s) {
    return S_ISREG(s.st_mode) && s.st_uid==getuid() && s.st_nlink==1 && (s.st_mode&0777)==0600;
}
inline bool same(const struct stat &a,const struct stat &b) {
    return a.st_dev==b.st_dev && a.st_ino==b.st_ino && a.st_uid==b.st_uid &&
        a.st_gid==b.st_gid && a.st_mode==b.st_mode;
}
struct Posix {
    static ssize_t write_file(int fd,const void *p,size_t n){return ::write(fd,p,n);}
    static int sync_file(int fd){return ::fsync(fd);}
    static int close_file(int fd){return ::close(fd);}
};
// O_EXCL + retained fstat identity proves which NEW temporary belongs to this
// call. The first failed bytes per stage/process are hard-linked exclusively
// before the owned temp is unlinked. Later same-stage failures may discard only
// their own unpublished temp, leaving the first evidence and live report intact.
// Unknown inode, close result or evidence operation remains retained and blocks
// this publisher: never widen cleanup to an old/foreign path to keep it running.
inline Result failed(State &state,int dir,const std::string &temp,const struct stat &held,
                     bool closed,Stage stage,int error) {
    Result result{stage,error};
    struct stat actual{};
    if(!closed || fstatat(dir,temp.c_str(),&actual,AT_SYMLINK_NOFOLLOW)!=0 ||
       !regular(actual) || !same(held,actual)) {state.blocked=true;return result;}
    auto index=static_cast<unsigned>(stage);
    if(!state.recorded[index]) {
        const auto evidence="emulator_daily_resources.tsv.failed24."+std::to_string(getpid())+"."+
            std::to_string(state.sequence)+"."+name(stage);
        if(linkat(dir,temp.c_str(),dir,evidence.c_str(),0)!=0) {state.blocked=true;return result;}
        struct stat copy{},after{};
        if(fstatat(dir,evidence.c_str(),&copy,AT_SYMLINK_NOFOLLOW)!=0 ||
           fstatat(dir,temp.c_str(),&after,AT_SYMLINK_NOFOLLOW)!=0 || !same(held,copy) ||
           !same(held,after) || copy.st_nlink!=2 || after.st_nlink!=2 || fsync(dir)!=0) {
            state.blocked=true;return result;
        }
        result.evidence=true;
    }
    // Recheck immediately before unlink: only the temp created by this call.
    const auto links=result.evidence?2:1;
    if(fstatat(dir,temp.c_str(),&actual,AT_SYMLINK_NOFOLLOW)!=0 || !same(held,actual) ||
       actual.st_nlink!=links || unlinkat(dir,temp.c_str(),0)!=0 || fsync(dir)!=0) {
        state.blocked=true;return result;
    }
    state.recorded[index]=true;result.cleaned=true;return result;
}
template<class Backend,class IO=Posix>
Result publish(const std::string &directory,const emu_daily_resources::Snapshot &snapshot,
               Backend &backend,State &state) {
    using namespace emu_daily_resources;
    if(state.blocked || state.sequence==UINT64_MAX)return {Stage::blocked,0};
    const auto data=packet(snapshot);
    if(data.empty() || data.size()>2048 || !backend.ready() ||
       !(backend.binding()==snapshot.binding) || backend.revision()!=snapshot.revision)
        return {Stage::capture,0};
    int dir=open(directory.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
    if(dir<0)return {Stage::directory,errno};
    struct stat directory_stat{};
    if(fstat(dir,&directory_stat)!=0 || !S_ISDIR(directory_stat.st_mode) || directory_stat.st_uid!=getuid()) {
        const int error=errno;close(dir);return {Stage::directory,error};
    }
    const char *dest="emulator_daily_resources.tsv";
    struct stat before{};
    const bool existed=fstatat(dir,dest,&before,AT_SYMLINK_NOFOLLOW)==0;
    if((existed && !regular(before)) || (!existed && errno!=ENOENT)) {
        const int error=errno;close(dir);return {Stage::destination,error};
    }
    const auto temp=std::string(dest)+".tmp24."+std::to_string(getpid())+"."+std::to_string(++state.sequence);
    int fd=openat(dir,temp.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0){const int error=errno;close(dir);return {Stage::create,error};}
    struct stat held{};
    Stage stage=Stage::complete;int error=0;
    if(fstat(fd,&held)!=0 || !regular(held)) {stage=Stage::metadata;error=errno;}
    if(stage==Stage::complete && IO::write_file(fd,data.data(),data.size())!=static_cast<ssize_t>(data.size())) {stage=Stage::write;error=errno;}
    if(stage==Stage::complete && IO::sync_file(fd)!=0) {stage=Stage::sync;error=errno;}
    const bool closed=IO::close_file(fd)==0;
    if(!closed){stage=Stage::close;error=errno;}
    struct stat after{};
    if(stage==Stage::complete) {
        const bool present=fstatat(dir,dest,&after,AT_SYMLINK_NOFOLLOW)==0;
        const auto now=backend.now();
        if(!(existed?present&&regular(after)&&same(before,after):!present&&errno==ENOENT) ||
           now<snapshot.captured || now-snapshot.captured>200 || !backend.ready() ||
           !(backend.binding()==snapshot.binding) || backend.revision()!=snapshot.revision)
            stage=Stage::recheck;
    }
    Result result{stage,error};
    if(stage!=Stage::complete)result=failed(state,dir,temp,held,closed,stage,error);
    else if(renameat(dir,temp.c_str(),dir,dest)!=0)result=failed(state,dir,temp,held,true,Stage::rename,errno);
    else if(fsync(dir)!=0){state.blocked=true;result={Stage::directory_sync,errno};}
    close(dir);return result;
}
}

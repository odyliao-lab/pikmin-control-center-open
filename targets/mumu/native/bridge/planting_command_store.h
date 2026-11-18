#pragma once
#include "planting_command_ledger.h"
#include "planting_stop_snapshot.h"
#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

namespace emu_planting_command {
// Corruption detection only, NOT authentication against the owning UID/root.
inline uint32_t crc32_bytes(const std::string &data){
    uint32_t crc=0xffffffffU;for(unsigned char c:data){crc^=c;for(int i=0;i<8;i++)crc=(crc>>1)^((crc&1)?0xedb88320U:0U);}return crc^0xffffffffU;
}
inline std::string file_wire(const Ledger &s){
    auto body=encode(s);if(body.empty())return {};char hex[9]{};std::snprintf(hex,sizeof(hex),"%08x",crc32_bytes(body));return body+"crc32\t"+hex+"\n";
}
inline bool file_decode(const std::string &wire,Ledger &out){
    if(wire.size()>2063)return false;const auto end=wire.find('\n');if(end==std::string::npos)return false;
    Ledger s;if(!decode(wire.substr(0,end+1),s)||file_wire(s)!=wire)return false;out=s;return true;
}
struct NativeFd {
    int fd{-1};explicit NativeFd(int value=-1):fd(value){}~NativeFd(){if(fd>=0)::close(fd);}
    NativeFd(const NativeFd &)=delete;NativeFd &operator=(const NativeFd &)=delete;
    bool close_checked(){if(fd<0)return false;int v=fd;fd=-1;return ::close(v)==0;}
};
// POSIX FileStore candidate, NOT connected to any game path or loader.
// Existing absolute root must end /files/emulator-command, owner=current UID,
// mode0700, no symlink component. No mkdir, recovery, delete, fallback or replay.
// Per-file flock is NOT the global GPS work lock. Non-cooperating/root races
// cannot be made atomic CAS with pathname rechecks; caller must exclude writers.
class FileStore final:public Store {
    static constexpr const char *journal="command-journal.tsv",*temporary="command-journal.tsv.tmp",*lock_name="command-journal.lock";
    NativeFd directory,lock;
    std::string root,cached;
    struct stat dir_identity{},lock_identity{};
    bool opened{},has_read{},cached_present{};
    static bool same_inode(const struct stat &a,const struct stat &b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino;}
    static bool regular(const struct stat &s){return S_ISREG(s.st_mode)&&s.st_uid==geteuid()&&s.st_nlink==1&&(s.st_mode&0777)==0600;}
    static bool private_dir(const struct stat &s){return S_ISDIR(s.st_mode)&&s.st_uid==geteuid()&&(s.st_mode&0777)==0700&&s.st_nlink>0;}
    static int open_directory(const std::string &path){
        const std::string suffix="/files/emulator-command";
        if(path.empty()||path[0]!='/'||path.size()<=suffix.size()||path.substr(path.size()-suffix.size())!=suffix||path.find('\0')!=std::string::npos)return -1;
        int current=::open("/",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);if(current<0)return -1;
        size_t from=1;
        while(from<path.size()){
            const auto slash=path.find('/',from);const auto component=path.substr(from,slash==std::string::npos?slash:slash-from);
            if(component.empty()||component=="."||component==".."){::close(current);return -1;}
            int next=::openat(current,component.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);
            ::close(current);if(next<0)return -1;current=next;
            if(slash==std::string::npos)break;from=slash+1;
        }return current;
    }
    bool identities(){
        if(!opened)return false;struct stat ds{},ls{},leaf{};NativeFd current(open_directory(root));
        return current.fd>=0&&fstat(current.fd,&ds)==0&&private_dir(ds)&&same_inode(ds,dir_identity)&&
            fstat(directory.fd,&ds)==0&&private_dir(ds)&&same_inode(ds,dir_identity)&&
            fstat(lock.fd,&ls)==0&&regular(ls)&&ls.st_size==0&&same_inode(ls,lock_identity)&&
            fstatat(directory.fd,lock_name,&leaf,AT_SYMLINK_NOFOLLOW)==0&&regular(leaf)&&leaf.st_size==0&&same_inode(leaf,lock_identity);
    }
    bool temp_absent(){struct stat s{};if(fstatat(directory.fd,temporary,&s,AT_SYMLINK_NOFOLLOW)==0)return false;return errno==ENOENT;}
    bool request_temps_absent(){
        for(const auto name:{"request.tsv.tmp","request.tsv.bak","request.tsv.new"}){
            struct stat s{};if(fstatat(directory.fd,name,&s,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
        }return true;
    }
    static bool unchanged_request(const struct stat &a,const struct stat &b){
        return regular(b)&&same_inode(a,b)&&a.st_size==b.st_size&&
            a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&
            a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;
    }
    bool stop_temps_absent(bool own_temporary=false){
        for(const auto name:{"stop-snapshot.tsv.tmp","stop-snapshot.tsv.bak","stop-snapshot.tsv.new"}){
            if(own_temporary&&std::string(name)=="stop-snapshot.tsv.tmp")continue;
            struct stat s{};if(fstatat(directory.fd,name,&s,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
        }return true;
    }
    bool stop_raw(bool &present,std::string &wire,bool own_temporary=false){
        if(!identities()||!temp_absent()||!stop_temps_absent(own_temporary))return false;
        struct stat before{},held{},after{},leaf{};
        if(fstatat(directory.fd,"stop-snapshot.tsv",&before,AT_SYMLINK_NOFOLLOW)!=0){
            if(errno!=ENOENT||!identities()||!stop_temps_absent(own_temporary))return false;
            present=false;wire.clear();return true;
        }
        if(!regular(before)||before.st_size<=0||before.st_size>1024)return false;
        NativeFd fd(openat(directory.fd,"stop-snapshot.tsv",O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC));
        if(fd.fd<0||fstat(fd.fd,&held)!=0||!unchanged_request(before,held))return false;
        std::string bytes(static_cast<size_t>(held.st_size),'\0');size_t at=0;
        while(at<bytes.size()){auto n=::read(fd.fd,&bytes[at],bytes.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        char extra{};ssize_t n;do{n=::read(fd.fd,&extra,1);}while(n<0&&errno==EINTR);
        if(n!=0||fstat(fd.fd,&after)!=0||!unchanged_request(held,after)||
           fstatat(directory.fd,"stop-snapshot.tsv",&leaf,AT_SYMLINK_NOFOLLOW)!=0||!unchanged_request(held,leaf)||
           !fd.close_checked()||!identities()||!temp_absent()||!stop_temps_absent(own_temporary))return false;
        StopSnapshot parsed;if(!stop_snapshot_decode(bytes,parsed))return false;present=true;wire=bytes;return true;
    }
    bool read_raw(bool &present,std::string &wire,bool check_temp){
        if(!identities()||(check_temp&&!temp_absent()))return false;
        struct stat before{};if(fstatat(directory.fd,journal,&before,AT_SYMLINK_NOFOLLOW)!=0){if(errno!=ENOENT)return false;present=false;wire.clear();return identities();}
        if(!regular(before)||before.st_size<=0||before.st_size>2063)return false;
        NativeFd fd(::openat(directory.fd,journal,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC));struct stat held{};
        if(fd.fd<0||fstat(fd.fd,&held)!=0||!regular(held)||!same_inode(before,held)||held.st_size!=before.st_size)return false;
        std::string bytes(static_cast<size_t>(held.st_size),'\0');size_t at=0;
        while(at<bytes.size()){auto n=::read(fd.fd,&bytes[at],bytes.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        char extra{};ssize_t n;do{n=::read(fd.fd,&extra,1);}while(n<0&&errno==EINTR);
        struct stat after{},leaf{};if(n!=0||fstat(fd.fd,&after)!=0||!regular(after)||after.st_size!=held.st_size||
            fstatat(directory.fd,journal,&leaf,AT_SYMLINK_NOFOLLOW)!=0||!regular(leaf)||!same_inode(held,leaf)||leaf.st_size!=held.st_size||
            !fd.close_checked()||!identities()||(check_temp&&!temp_absent()))return false;
        Ledger parsed;if(!file_decode(bytes,parsed))return false;present=true;wire=bytes;return true;
    }
public:
    explicit FileStore(const std::string &path):root(path){
        if(getuid()!=geteuid())return;
        directory.fd=open_directory(path);if(directory.fd<0||fstat(directory.fd,&dir_identity)!=0||!private_dir(dir_identity))return;
        // Validate an existing lock before opening it; never repair bad metadata.
        struct stat existing{};int found=fstatat(directory.fd,lock_name,&existing,AT_SYMLINK_NOFOLLOW);
        if(found==0&&(!regular(existing)||existing.st_size!=0))return;
        if(found!=0&&errno!=ENOENT)return;
        int flags=O_RDWR|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC|(found!=0?O_CREAT|O_EXCL:0);
        lock.fd=::openat(directory.fd,lock_name,flags,0600);
        if(lock.fd<0||fstat(lock.fd,&lock_identity)!=0||!regular(lock_identity)||lock_identity.st_size!=0||
            (found==0&&!same_inode(existing,lock_identity))||flock(lock.fd,LOCK_EX|LOCK_NB)!=0)return;
        opened=true;if(!identities())opened=false;
    }
    FileStore(const FileStore &)=delete;FileStore &operator=(const FileStore &)=delete;
    bool is_open(){return identities();}
    bool is_open_at(const std::string &path){return path==root&&identities();}
    bool is_open_at(const std::string &path,const struct stat &identity){return path==root&&same_inode(dir_identity,identity)&&identities();}
    bool read_stop_report(bool &present,StopSnapshot &out){
        bool exists=false;std::string wire;if(!stop_raw(exists,wire))return false;
        StopSnapshot s;if(exists&&!stop_snapshot_decode(wire,s))return false;
        present=exists;if(exists)out=s;return true;
    }
    // Data report, NOT authority. Caller supplies an independent live proof
    // guard rechecked before temp creation, immediately before rename and after.
    // Unknown write outcome is preserved; never delete/repair a leftover temp.
    template<class Guard> bool publish_stop_report(const StopSnapshot &s,const std::string &expected_prior,Guard live){
        const auto wire=stop_snapshot_wire(s);bool exists=false,present=false;std::string old;Ledger entry;
        if(wire.empty()||!read(present,entry)||!present||entry.phase!=ReceiptPhase::started||
           entry.command.action!=Action::start||entry.command.pid!=s.pid||entry.command.epoch!=s.epoch||
           entry.command.boot!=s.boot||entry.command.account!=s.account||entry.command.owner!=s.owner||
           entry.command.token!=s.token||entry.command.operation!=s.start_operation||entry.command.petal!=s.petal||
           entry.native_started!=s.started||!stop_raw(exists,old)||
           (exists?old!=expected_prior:!expected_prior.empty()))return false;
        if(exists){
            StopSnapshot prior;if(!stop_snapshot_decode(old,prior)||!same_stop_session(prior,s)||s.captured<prior.captured)return false;
            if(same_stop_round(prior,s)){
                if(prior.phase==NativeStopPhase::closed&&s.phase!=NativeStopPhase::closed)return false;
            }else if(prior.token==s.token||prior.start_operation==s.start_operation||
                     !entry.tokens.count(prior.token)||!entry.operations.count(prior.start_operation))return false;
            // A NEW confirmed START in actual native history can supersede an
            // old pending report after its STOP receipt closed. No scalar adoption.
        }
        const bool old_present=exists;const auto ledger=encode(entry);
        if(!live(s))return false;
        NativeFd fd(openat(directory.fd,"stop-snapshot.tsv.tmp",O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));struct stat written{};
        if(fd.fd<0||fstat(fd.fd,&written)!=0||!regular(written))return false;
        size_t at=0;while(at<wire.size()){auto n=::write(fd.fd,wire.data()+at,wire.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        if(fsync(fd.fd)!=0||fstat(fd.fd,&written)!=0||!regular(written)||written.st_size!=static_cast<off_t>(wire.size())||!fd.close_checked())return false;
        Ledger after;std::string rechecked;
        if(!read(present,after)||!present||encode(after)!=ledger||!stop_raw(exists,rechecked,true)||
           exists!=old_present||rechecked!=old||!live(s))return false;
        struct stat temp{};
        if(fstatat(directory.fd,"stop-snapshot.tsv.tmp",&temp,AT_SYMLINK_NOFOLLOW)!=0||!unchanged_request(written,temp)||!identities())return false;
        if(renameat(directory.fd,"stop-snapshot.tsv.tmp",directory.fd,"stop-snapshot.tsv")!=0||fsync(directory.fd)!=0)return false;
        std::string actual;if(!stop_raw(exists,actual)||!exists||actual!=wire||!live(s))return false;
        return true;
    }
    // Read-only fixed command ingress. Host producer/ownership transaction still
    // mandatory. Failure preserves outputs, never means absence or permits repair.
    bool read_request(bool &present,std::string &out){
        if(!identities()||!temp_absent()||!request_temps_absent())return false;
        struct stat before{},held{},after{},leaf{};
        if(fstatat(directory.fd,"request.tsv",&before,AT_SYMLINK_NOFOLLOW)!=0){
            if(errno!=ENOENT||!identities()||!temp_absent()||!request_temps_absent())return false;
            present=false;out.clear();return true;
        }
        if(!regular(before)||before.st_size<=0||before.st_size>600)return false;
        NativeFd fd(openat(directory.fd,"request.tsv",O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC));
        if(fd.fd<0||fstat(fd.fd,&held)!=0||!unchanged_request(before,held))return false;
        std::string bytes(static_cast<size_t>(held.st_size),'\0');size_t at=0;
        while(at<bytes.size()){auto n=::read(fd.fd,&bytes[at],bytes.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        char extra{};ssize_t n;do{n=::read(fd.fd,&extra,1);}while(n<0&&errno==EINTR);
        if(n!=0||fstat(fd.fd,&after)!=0||!unchanged_request(held,after)||
           fstatat(directory.fd,"request.tsv",&leaf,AT_SYMLINK_NOFOLLOW)!=0||!unchanged_request(held,leaf)||
           !fd.close_checked()||!identities()||!temp_absent()||!request_temps_absent())return false;
        Command command;if(!parse(bytes,command)||command_wire(command)!=bytes)return false;
        present=true;out=bytes;return true;
    }
    bool read(bool &present,Ledger &out)override{
        has_read=false;bool exists=false;std::string wire;if(!read_raw(exists,wire,true))return false;
        Ledger s;if(exists&&!file_decode(wire,s))return false;
        cached_present=exists;cached=wire;has_read=true;present=exists;if(exists)out=s;return true;
    }
    bool save(const Ledger &s)override{
        const auto wire=file_wire(s);if(!has_read||wire.empty())return false;
        const bool expected_present=cached_present;const auto expected=cached;
        bool exists=false;std::string old;
        if(!read_raw(exists,old,true)||exists!=expected_present||old!=expected){has_read=false;return false;}
        has_read=false;
        NativeFd fd(::openat(directory.fd,temporary,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));struct stat written{};
        if(fd.fd<0||fstat(fd.fd,&written)!=0||!regular(written))return false;
        size_t at=0;while(at<wire.size()){auto n=::write(fd.fd,wire.data()+at,wire.size()-at);if(n<0&&errno==EINTR)continue;if(n<=0)return false;at+=static_cast<size_t>(n);}
        if(fsync(fd.fd)!=0||fstat(fd.fd,&written)!=0||!regular(written)||written.st_size!=static_cast<off_t>(wire.size())||!fd.close_checked())return false;
        if(!read_raw(exists,old,false)||exists!=expected_present||old!=expected)return false;
        struct stat temp{};if(fstatat(directory.fd,temporary,&temp,AT_SYMLINK_NOFOLLOW)!=0||!regular(temp)||!same_inode(temp,written)||temp.st_size!=written.st_size||!identities())return false;
        if(renameat(directory.fd,temporary,directory.fd,journal)!=0||fsync(directory.fd)!=0)return false;
        std::string actual;if(!read_raw(exists,actual,true)||!exists||actual!=wire)return false;
        cached=actual;cached_present=true;has_read=true;return true;
    }
};
}

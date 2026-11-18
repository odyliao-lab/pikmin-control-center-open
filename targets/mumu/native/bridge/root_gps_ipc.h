#pragma once
#include "root_gps_observation.h"
#include "root_provider_process.h"
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/random.h>
#include <poll.h>
#include <cstring>
namespace emu_gps_work {
namespace fence_ipc_detail {
inline std::string leaf(const std::string &nonce){return "peer-"+nonce+".sock";}
inline bool socket_leaf(const struct stat &s){return S_ISSOCK(s.st_mode)&&s.st_uid==0&&s.st_gid==0&&(s.st_mode&07777)==0666&&s.st_nlink==1;}
inline bool address(int dir,const std::string &nonce,sockaddr_un &out){
    if(!fence_observation_detail::token(nonce))return false;
    const auto path="/proc/self/fd/"+std::to_string(dir)+"/"+leaf(nonce);
    if(path.size()>=sizeof(out.sun_path))return false;out={};out.sun_family=AF_UNIX;memcpy(out.sun_path,path.c_str(),path.size()+1);return true;
}
inline bool peer(int fd,ucred &out){socklen_t size=sizeof(out);return getsockopt(fd,SOL_SOCKET,SO_PEERCRED,&out,&size)==0&&size==sizeof(out)&&out.pid>0;}
inline bool wait(int fd,short events,int ms){pollfd p{fd,events,0};const int n=poll(&p,1,ms);return n==1&&(p.revents&events)&&!(p.revents&(POLLERR|POLLNVAL));}
inline bool challenge(const std::string &s){if(s.size()!=32)return false;for(char c:s)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;return true;}
inline bool random_challenge(std::string &out){
    unsigned char b[16];if(getrandom(b,sizeof(b),GRND_NONBLOCK)!=sizeof(b))return false;
    const char hex[]="0123456789abcdef";std::string s;for(auto c:b){s+=hex[c>>4];s+=hex[c&15];}out=std::move(s);return true;
}
inline bool game_peer(const ucred &p){
    if(p.uid<10000||p.uid>=100000)return false;const auto path="/proc/"+std::to_string(p.pid);
    struct stat app{},before{},after{};std::string command;
    // Process names are caller-controlled. Tie the credential UID to the
    // actual game data directory, not merely a matching argv[0].
    if(lstat("/data/user/0/com.nianticlabs.pikmin",&app)!=0||!S_ISDIR(app.st_mode)||app.st_uid!=p.uid||
       lstat(path.c_str(),&before)!=0||!S_ISDIR(before.st_mode)||before.st_uid!=p.uid||
       !fence_observation_detail::read_path(path+"/cmdline",command,1024)||command.size()<23||
       command.substr(0,23)!=std::string("com.nianticlabs.pikmin\0",23))return false;
    for(size_t i=23;i<command.size();++i)if(command[i]!=0)return false;
    return lstat(path.c_str(),&after)==0&&after.st_uid==p.uid&&after.st_dev==before.st_dev&&after.st_ino==before.st_ino;
}
}
// Live Root peer proof, still FENCE_ONLY. No intents, WriterLease or game APIs.
class RootFenceIpcServer final {
    int fd_{-1},dir_{-1};RootGpsFence *fence_{};std::string nonce_;
#ifdef PCC_GPS_FENCE_FIXTURE
    uid_t fixture_uid_{};
#endif
    bool accepts(const ucred &identity){
#ifdef PCC_GPS_FENCE_FIXTURE
        if(fixture_uid_)return identity.uid==fixture_uid_;
#endif
        return fence_ipc_detail::game_peer(identity);
    }
    bool start_at(RootGpsFence &fence,const std::string &nonce){
        using namespace fence_ipc_detail;
        if(fd_>=0||dir_>=0||geteuid()!=0||!fence.valid())return false;
        dir_=fence.observation_directory();nonce_=nonce;fence_=&fence;
        if(dir_<0)return false;sockaddr_un a{};struct stat existing{},created{};
        if(!address(dir_,nonce,a)||fstatat(dir_,leaf(nonce).c_str(),&existing,AT_SYMLINK_NOFOLLOW)==0||errno!=ENOENT)return false;
        fd_=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0);
        if(fd_<0||bind(fd_,reinterpret_cast<sockaddr *>(&a),sizeof(a))!=0||
           fchmodat(dir_,leaf(nonce).c_str(),0666,0)!=0||listen(fd_,4)!=0||
           fstatat(dir_,leaf(nonce).c_str(),&created,AT_SYMLINK_NOFOLLOW)!=0||!socket_leaf(created)||!fence.valid())return false;
        return true;
    }
public:
    RootFenceIpcServer()=default;RootFenceIpcServer(const RootFenceIpcServer &)=delete;
    ~RootFenceIpcServer(){if(fd_>=0)close(fd_);if(dir_>=0)close(dir_);} // retain endpoint, never unlink/rebind old nonce
    bool start(RootGpsFence &fence,const std::string &nonce){return start_at(fence,nonce);}
#ifdef PCC_GPS_FENCE_FIXTURE
    bool start_fixture(RootGpsFence &fence,const std::string &nonce,uid_t uid){
        if(uid<10000||uid>=100000)return false;fixture_uid_=uid;return start_at(fence,nonce);
    }
#endif
    int descriptor()const{return fd_;}
    bool serve_one(){
        using namespace fence_ipc_detail;
        int client=accept4(fd_,nullptr,nullptr,SOCK_NONBLOCK|SOCK_CLOEXEC);
        if(client<0)return errno==EAGAIN||errno==EWOULDBLOCK;
        ucred identity{};bool admitted=peer(client,identity)&&accepts(identity);
        char request[128]{};ssize_t n=-1;
        if(admitted&&wait(client,POLLIN,100))n=recv(client,request,sizeof(request),MSG_TRUNC|MSG_DONTWAIT);
        std::string challenge;bool provider_query=false;
        if(n==42&&(std::string(request,10)=="gf-query1\t"||std::string(request,10)=="gp-query1\t")){
            provider_query=std::string(request,10)=="gp-query1\t";challenge.assign(request+10,32);
        }
        FenceObservation value;
        bool good=admitted&&fence_ipc_detail::challenge(challenge)&&fence_&&fence_->valid()&&
            read_fence_observation_at(dir_,value)&&value.pid==getpid()&&value.nonce==nonce_;
        if(good){
            ProviderProcessObservation provider;std::string extension;
            if(provider_query){
                good=capture_root_provider_process(*fence_,provider)&&provider.boot==value.boot;
                bool same_game=provider.game_pid==identity.pid;
#ifdef PCC_GPS_FENCE_FIXTURE
                if(fixture_uid_)same_game=true; // standalone UID probe, not game attachment
#endif
                good=good&&same_game;extension=good?provider_process_wire(provider):std::string{};
            }
            const auto reply=std::string(provider_query?"gp-reply1\t":"gf-reply1\t")+challenge+"\t"+fence_observation_detail::wire(value)+extension;
            good=good&&fence_->valid()&&accepts(identity)&&send(client,reply.data(),reply.size(),MSG_NOSIGNAL|MSG_DONTWAIT)==static_cast<ssize_t>(reply.size());
        }
        const bool closed=close(client)==0;
        // Malformed/unapproved peer is refused, not a reason to restart actor.
        return closed&&fence_&&fence_->valid();
    }
};
// Protected file is only an endpoint hint. Root SO_PEERCRED + unpredictable
// challenge response supplies current peer liveness despite hidden Root /proc.
inline bool read_fence_ipc_at(int dir,FenceObservation &out,ProviderProcessObservation *provider=nullptr){
    using namespace fence_ipc_detail;FenceObservation hint,value;struct stat before{},after{};
    if(!read_fence_candidate_at(dir,hint)||fstatat(dir,leaf(hint.nonce).c_str(),&before,AT_SYMLINK_NOFOLLOW)!=0||!socket_leaf(before))return false;
    sockaddr_un a{};if(!address(dir,hint.nonce,a))return false;
    int fd=socket(AF_UNIX,SOCK_SEQPACKET|SOCK_NONBLOCK|SOCK_CLOEXEC,0);if(fd<0)return false;
    const auto begin=fence_observation_detail::now();
    bool good=connect(fd,reinterpret_cast<sockaddr *>(&a),sizeof(a))==0;
    ucred root{};std::string challenge;
    good=good&&peer(fd,root)&&root.uid==0&&root.pid==hint.pid&&random_challenge(challenge);
    const auto request=std::string(provider?"gp-query1\t":"gf-query1\t")+challenge;
    good=good&&send(fd,request.data(),request.size(),MSG_NOSIGNAL|MSG_DONTWAIT)==static_cast<ssize_t>(request.size())&&wait(fd,POLLIN,200);
    char bytes[1024]{};const auto n=good?recv(fd,bytes,sizeof(bytes),MSG_TRUNC|MSG_DONTWAIT):-1;
    const auto prefix=std::string(provider?"gp-reply1\t":"gf-reply1\t")+challenge+"\t";
    std::string body;ProviderProcessObservation process;
    if(n>static_cast<ssize_t>(prefix.size())&&n<static_cast<ssize_t>(sizeof(bytes)))body.assign(bytes+prefix.size(),n-prefix.size());
    if(provider){
        const auto split=body.find('\n');
        good=good&&split!=std::string::npos&&decode_provider_process(body.substr(split+1),process)&&
            process.boot==hint.boot&&process.game_uid==static_cast<int>(geteuid());
        if(split!=std::string::npos)body.resize(split+1);
    }
    good=good&&n>static_cast<ssize_t>(prefix.size())&&n<static_cast<ssize_t>(sizeof(bytes))&&
        std::string(bytes,prefix.size())==prefix&&fence_observation_detail::decode(body,value)&&
        value.pid==root.pid&&value.pid==hint.pid&&value.ticks==hint.ticks&&value.boot==hint.boot&&value.nonce==hint.nonce&&
        value.device==hint.device&&value.inode==hint.inode;
    const auto end=fence_observation_detail::now();
    good=good&&end>=begin&&end-begin<=250&&value.sampled<=end&&end<value.until&&
        fstatat(dir,leaf(hint.nonce).c_str(),&after,AT_SYMLINK_NOFOLLOW)==0&&socket_leaf(after)&&fence_observation_detail::same(before,after);
    const bool closed=close(fd)==0;if(!good||!closed)return false;out=std::move(value);if(provider)*provider=std::move(process);return true;
}
inline bool read_fence_ipc(FenceObservation &out){
    int dir=open_fence_observation_directory();if(dir<0)return false;
    struct stat before{},after{};FenceObservation value;
    bool good=fstat(dir,&before)==0&&read_fence_ipc_at(dir,value);int fresh=good?open_fence_observation_directory():-1;
    good=good&&fresh>=0&&fstat(fresh,&after)==0&&fence_observation_detail::directory(after)&&before.st_dev==after.st_dev&&before.st_ino==after.st_ino;
    if(fresh>=0){const bool closed=close(fresh)==0;good=good&&closed;}
    const bool closed=close(dir)==0;if(!good||!closed)return false;out=std::move(value);return true;
}
}

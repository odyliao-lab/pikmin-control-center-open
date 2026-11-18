#pragma once
#include "root_provider_process.h"
#include <poll.h>
#include <signal.h>
#include <sys/wait.h>
namespace emu_gps_work {
namespace provider_service_detail {
constexpr const char *component="com.thejhijuqoqy.bdujvrwdcajvzin/com.theappninjas.fakegpsjoystick.service.OverlayService";
inline bool token(const std::string &s){if(s.empty()||s.size()>16)return false;
    for(char c:s)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;return true;}
// Fixed read-only executable/argv. No shell, caller path or command payload.
enum class ReadQuery {SERVICE,DISABLED_ORIGINAL,MOCK_ALLOW};
inline bool query(std::string &out,ReadQuery kind=ReadQuery::SERVICE){
    if(kind!=ReadQuery::SERVICE&&kind!=ReadQuery::DISABLED_ORIGINAL&&kind!=ReadQuery::MOCK_ALLOW)return false;
    if(geteuid()!=0)return false;int p[2];if(pipe2(p,O_CLOEXEC|O_NONBLOCK)!=0)return false;
    const auto child=fork();if(child<0){close(p[0]);close(p[1]);return false;}
    if(child==0){
        close(p[0]);int null=open("/dev/null",O_RDONLY|O_CLOEXEC);
        if(null<0||dup2(null,STDIN_FILENO)<0||fcntl(p[1],F_SETFL,0)!=0||dup2(p[1],STDOUT_FILENO)<0||dup2(p[1],STDERR_FILENO)<0)_exit(126);
        close(null);close(p[1]);
        if(kind==ReadQuery::SERVICE)execl("/system/bin/dumpsys","dumpsys","-t","2","activity","services",component,nullptr);
        else if(kind==ReadQuery::DISABLED_ORIGINAL)execl("/system/bin/cmd","cmd","package","list","packages","-d","--user","0","dev.ody.pikmincontrol",nullptr);
        else execl("/system/bin/cmd","cmd","appops","query-op","android:mock_location","allow",nullptr);
        _exit(127);
    }
    close(p[1]);std::string text;bool good=true,eof=false,terminal=false;int status{};
    const auto begin=fence_observation_detail::now();
    while(good&&!(eof&&terminal)){
        const auto now=fence_observation_detail::now();if(now<begin||now-begin>3000){good=false;break;}
        if(!eof){pollfd ready{p[0],POLLIN,0};int n=poll(&ready,1,20);
            if(n<0&&errno!=EINTR){good=false;break;}
            if(n>0&&(ready.revents&(POLLIN|POLLHUP))){char b[1024];ssize_t count=read(p[0],b,sizeof(b));
                if(count>0){text.append(b,count);if(text.size()>16384)good=false;}
                else if(count==0)eof=true;else if(errno!=EINTR&&errno!=EAGAIN)good=false;}
            if(n>0&&(ready.revents&(POLLERR|POLLNVAL)))good=false;
        }else usleep(1000);
        if(!terminal){const auto w=waitpid(child,&status,WNOHANG);if(w==child)terminal=true;else if(w<0&&errno!=EINTR){good=false;break;}}
    }
    // Only this owned child; never kill a game/provider PID or restart on timeout.
    if(!terminal){kill(child,SIGKILL);pid_t w;do{w=waitpid(child,&status,0);}while(w<0&&errno==EINTR);terminal=w==child;}
    const bool closed=close(p[0])==0;
    if(!good||!eof||!terminal||!closed||!WIFEXITED(status)||WEXITSTATUS(status)!=0)return false;
    out=std::move(text);return true;
}
inline bool parse(const std::string &text,const ProviderProcessObservation &p,std::string &out){
    if(text.empty()||text.size()>16384||text.find('\0')!=std::string::npos||text.find('\r')!=std::string::npos)return false;
    bool header=false,user=false,foreground=false,app=false;std::string id;unsigned records=0,apps=0,fgs=0;
    size_t at=0;while(at<text.size()){
        const auto end=text.find('\n',at);auto line=text.substr(at,end==std::string::npos?end:end-at);
        const auto first=line.find_first_not_of(' ');line=first==std::string::npos?std::string{}:line.substr(first);
        if(line=="ACTIVITY MANAGER SERVICES (dumpsys activity services)")header=true;
        if(line=="User 0 active services:")user=true;
        if(line.starts_with("* ServiceRecord{")){
            if(++records!=1||!header||!user)return false;
            const auto sep=line.find(' ',16);if(sep==std::string::npos)return false;id=line.substr(16,sep-16);
            const auto expected=" u0 "+std::string(component);
            if(!token(id)||!line.substr(sep).starts_with(expected)||line.size()<=sep+expected.size()||
               (line[sep+expected.size()]!=' '&&line[sep+expected.size()]!='}'))return false;
        }
        if(line.starts_with("app=")){
            if(++apps!=1||records!=1||!line.starts_with("app=ProcessRecord{"))return false;
            const auto space=line.find(' ',18);if(space==std::string::npos)return false;
            const auto name=std::to_string(p.provider_pid)+":com.thejhijuqoqy.bdujvrwdcajvzin:overlay/u0a"+std::to_string(p.provider_uid-10000)+"}";
            app=line.substr(space+1)==name;
        }
        if(line.starts_with("isForeground=")){
            if(++fgs!=1||records!=1)return false;foreground=line.starts_with("isForeground=true foregroundId=");
        }
        if(end==std::string::npos)break;at=end+1;
    }
    if(!header||!user||records!=1||!app||!foreground)return false;out=std::move(id);return true;
}
inline bool same_process(const ProviderProcessObservation &a,const ProviderProcessObservation &b){return a.boot==b.boot&&a.game_pid==b.game_pid&&
    a.game_uid==b.game_uid&&a.game_ticks==b.game_ticks&&a.provider_pid==b.provider_pid&&a.provider_uid==b.provider_uid&&a.provider_ticks==b.provider_ticks&&b.clock.follows(a.clock);}
inline bool isolated_profile(const std::string &disabled,const std::string &allowed){
    return disabled=="package:dev.ody.pikmincontrol\n"&&allowed=="com.thejhijuqoqy.bdujvrwdcajvzin\n";
}
inline bool same_game(RootGpsFence &fence,const ProviderProcessObservation &expected,emu_planting_command::ProviderClock &clock){
    using namespace provider_process_detail;uid_t game{},provider{};int pid{};uint64_t ticks{};std::string boot;
    return fence.valid()&&!provider_process_wire(expected).empty()&&owner("/data/user/0/com.nianticlabs.pikmin",game)&&
        owner("/data/user/0/com.thejhijuqoqy.bdujvrwdcajvzin",provider)&&game==static_cast<uid_t>(expected.game_uid)&&
        provider==static_cast<uid_t>(expected.provider_uid)&&fence_observation_detail::boot(boot)&&boot==expected.boot&&
        unique(game,"com.nianticlabs.pikmin",pid,ticks)&&pid==expected.game_pid&&ticks==expected.game_ticks&&
        provider_process_detail::clock(clock)&&clock.follows(expected.clock)&&fence.valid();
}
}
// Two known capability restrictions only. Does NOT establish all-writer
// exclusion, pending work drain, route idle, or an authenticated WriterLease.
// No provider liveness requirement: it remains usable after the old PID exits.
inline bool observe_root_isolated_profile(RootGpsFence &fence,const ProviderProcessObservation &expected){
    using namespace provider_service_detail;emu_planting_command::ProviderClock first,last;std::string disabled,allowed,disabled_after,allowed_after;
    if(!same_game(fence,expected,first)||!query(disabled,ReadQuery::DISABLED_ORIGINAL)||!query(allowed,ReadQuery::MOCK_ALLOW)||
       !isolated_profile(disabled,allowed)||!same_game(fence,expected,last)||!last.follows(first)||
       !query(disabled_after,ReadQuery::DISABLED_ORIGINAL)||!query(allowed_after,ReadQuery::MOCK_ALLOW)||
       disabled_after!=disabled||allowed_after!=allowed||!same_game(fence,expected,last)||!last.follows(first))return false;
    return last.after-first.before<=12000&&fence.valid();
}
inline bool capture_root_provider_service(RootGpsFence &fence,ProviderProcessObservation &out,std::string &service){
    ProviderProcessObservation before,after;std::string text,id;
    if(!capture_root_provider_process(fence,before)||!provider_service_detail::query(text)||
       !provider_service_detail::parse(text,before,id)||!capture_root_provider_process(fence,after)||
       !provider_service_detail::same_process(before,after)||!fence.valid())return false;
    out=std::move(after);service=std::move(id);return true;
}
}

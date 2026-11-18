#pragma once
#include "root_gps_observation.h"
#include "planting_command_provider_gate.h"
#include <dirent.h>
#include <chrono>
namespace emu_gps_work {
// Root-derived preparation facts ONLY. Process presence does not establish
// service readiness, route idle, provider restoration, or writer exclusion.
struct ProviderProcessObservation {
    std::string boot;int game_pid{},game_uid{},provider_pid{},provider_uid{};
    uint64_t game_ticks{},provider_ticks{};
    emu_planting_command::ProviderClock clock;
};
namespace provider_process_detail {
inline bool identity(int pid,uid_t uid,const std::string &name,uint64_t &ticks){
    if(pid<=0||uid<10000||uid>=100000)return false;
    const auto root="/proc/"+std::to_string(pid);struct stat before{},after{};std::string command,text;
    if(lstat(root.c_str(),&before)!=0||!S_ISDIR(before.st_mode)||before.st_uid!=uid||
       !fence_observation_detail::read_path(root+"/cmdline",command,1024)||command.size()<name.size()+1||
       command.substr(0,name.size()+1)!=name+std::string(1,'\0'))return false;
    for(size_t i=name.size()+1;i<command.size();++i)if(command[i]!=0)return false;
    if(!fence_observation_detail::read_path(root+"/stat",text,4096)||!text.starts_with(std::to_string(pid)+" ("))return false;
    const auto end=text.rfind(')');if(end==std::string::npos||end+2>=text.size())return false;
    std::vector<std::string> fields;size_t at=end+2;
    for(size_t i=at;i<=text.size();++i)if(i==text.size()||text[i]==' '||text[i]=='\n'){
        if(i>at)fields.push_back(text.substr(at,i-at));at=i+1;}
    uint64_t value{};
    if(fields.size()<20||fields[0]=="Z"||fields[0]=="X"||!fence_observation_detail::number(fields[19],value)||!value||
       lstat(root.c_str(),&after)!=0||after.st_uid!=uid||before.st_dev!=after.st_dev||before.st_ino!=after.st_ino)return false;
    ticks=value;return true;
}
inline bool unique(uid_t uid,const std::string &name,int &pid,uint64_t &ticks){
    DIR *dir=opendir("/proc");if(!dir)return false;int found=0;uint64_t found_ticks{};bool good=true;unsigned entries=0;
    errno=0;
    while(auto *entry=readdir(dir)){
        if(++entries>8192){good=false;break;}uint64_t id{};
        if(!fence_observation_detail::number(entry->d_name,id)||id==0||id>999999999){errno=0;continue;}
        uint64_t start{};
        if(identity(static_cast<int>(id),uid,name,start)){
            if(found){good=false;break;}found=static_cast<int>(id);found_ticks=start;
        }
        errno=0; // vanished unrelated /proc entries are not a directory failure
    }
    if(errno)good=false;const bool closed=closedir(dir)==0;
    if(!good||!closed||!found)return false;pid=found;ticks=found_ticks;return true;
}
inline bool owner(const char *path,uid_t &uid){struct stat s{};
    if(lstat(path,&s)!=0||!S_ISDIR(s.st_mode)||s.st_uid<10000||s.st_uid>=100000)return false;uid=s.st_uid;return true;}
inline bool clock(emu_planting_command::ProviderClock &out){
    const auto before=fence_observation_detail::now();
    const auto wall=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::system_clock::now().time_since_epoch()).count();
    const auto after=fence_observation_detail::now();
    emu_planting_command::ProviderClock value{static_cast<int64_t>(before),wall,static_cast<int64_t>(after)};
    if(!value.valid())return false;out=value;return true;
}
}
inline bool capture_root_provider_process(RootGpsFence &fence,ProviderProcessObservation &out){
    using namespace provider_process_detail;
    if(geteuid()!=0||!fence.valid())return false;ProviderProcessObservation value;uid_t game{},provider{};
    const auto begin=fence_observation_detail::now();
    if(!owner("/data/user/0/com.nianticlabs.pikmin",game)||
       !owner("/data/user/0/com.thejhijuqoqy.bdujvrwdcajvzin",provider)||game==provider||
       !fence_observation_detail::boot(value.boot)||!unique(game,"com.nianticlabs.pikmin",value.game_pid,value.game_ticks)||
       !unique(provider,"com.thejhijuqoqy.bdujvrwdcajvzin:overlay",value.provider_pid,value.provider_ticks)||!clock(value.clock))return false;
    value.game_uid=game;value.provider_uid=provider;
    uint64_t game_after{},provider_after{};std::string boot_after;uid_t game_owner{},provider_owner{};
    if(!identity(value.game_pid,game,"com.nianticlabs.pikmin",game_after)||game_after!=value.game_ticks||
       !identity(value.provider_pid,provider,"com.thejhijuqoqy.bdujvrwdcajvzin:overlay",provider_after)||provider_after!=value.provider_ticks||
       !owner("/data/user/0/com.nianticlabs.pikmin",game_owner)||game_owner!=game||
       !owner("/data/user/0/com.thejhijuqoqy.bdujvrwdcajvzin",provider_owner)||provider_owner!=provider||
       !fence_observation_detail::boot(boot_after)||boot_after!=value.boot||!fence.valid())return false;
    const auto end=fence_observation_detail::now();if(end<begin||end-begin>100)return false;
    out=std::move(value);return true;
}
inline std::string provider_process_wire(const ProviderProcessObservation &v){
    if(!fence_observation_detail::token(v.boot)||v.game_pid<=0||v.provider_pid<=0||v.game_pid==v.provider_pid||
       v.game_uid<10000||v.game_uid>=100000||v.provider_uid<10000||v.provider_uid>=100000||v.game_uid==v.provider_uid||
       !v.game_ticks||!v.provider_ticks||!v.clock.valid())return {};
    return "gp-process1\t"+v.boot+"\t"+std::to_string(v.game_pid)+"\t"+std::to_string(v.game_uid)+"\t"+std::to_string(v.game_ticks)+"\t"+
        std::to_string(v.provider_pid)+"\t"+std::to_string(v.provider_uid)+"\t"+std::to_string(v.provider_ticks)+"\t"+
        std::to_string(v.clock.before)+"\t"+std::to_string(v.clock.wall)+"\t"+std::to_string(v.clock.after)+"\tPROCESS_ONLY\n";
}
inline bool decode_provider_process(const std::string &text,ProviderProcessObservation &out){
    if(text.empty()||text.size()>512||text.find('\r')!=std::string::npos||text.find('\0')!=std::string::npos)return false;
    std::vector<std::string> f;size_t at=0;for(size_t i=0;i<text.size();++i)if(text[i]=='\t'){f.push_back(text.substr(at,i-at));at=i+1;}f.push_back(text.substr(at));
    if(f.size()!=12||f[0]!="gp-process1"||f[11]!="PROCESS_ONLY\n")return false;
    uint64_t n[9]{};for(size_t i=0;i<9;++i)if(!fence_observation_detail::number(f[i+2],n[i]))return false;
    if(n[0]>999999999||n[1]>99999||n[3]>999999999||n[4]>99999||n[6]>INT64_MAX||n[7]>INT64_MAX||n[8]>INT64_MAX)return false;
    ProviderProcessObservation v{f[1],static_cast<int>(n[0]),static_cast<int>(n[1]),static_cast<int>(n[3]),static_cast<int>(n[4]),n[2],n[5],
        {static_cast<int64_t>(n[6]),static_cast<int64_t>(n[7]),static_cast<int64_t>(n[8])}};
    if(provider_process_wire(v)!=text)return false;out=std::move(v);return true;
}
}

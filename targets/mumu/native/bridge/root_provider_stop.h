#pragma once
#include "root_provider_journal.h"
namespace emu_gps_work {
struct ProviderStopIntent {
    ProviderPreparation prepared;emu_planting_command::ProviderClock before_stop;
};
inline std::string provider_stop_wire(const ProviderStopIntent &v){
    const auto original=provider_preparation_wire(v.prepared);
    if(original.empty()||!v.before_stop.follows(v.prepared.original.clock))return {};
    return "gp-stop1\t"+std::to_string(v.before_stop.before)+"\t"+std::to_string(v.before_stop.wall)+"\t"+
        std::to_string(v.before_stop.after)+"\tSTOP_INTENT\n"+original;
}
inline bool decode_provider_stop(const std::string &text,ProviderStopIntent &out){
    const auto end=text.find('\n');if(end==std::string::npos||end>128)return false;
    const auto header=text.substr(0,end);std::vector<std::string> f;size_t at=0;
    for(size_t i=0;i<header.size();++i)if(header[i]=='\t'){f.push_back(header.substr(at,i-at));at=i+1;}f.push_back(header.substr(at));
    if(f.size()!=5||f[0]!="gp-stop1"||f[4]!="STOP_INTENT")return false;
    uint64_t values[3]{};for(size_t i=0;i<3;++i)if(!fence_observation_detail::number(f[i+1],values[i])||values[i]>INT64_MAX)return false;
    ProviderStopIntent v;v.before_stop={static_cast<int64_t>(values[0]),static_cast<int64_t>(values[1]),static_cast<int64_t>(values[2])};
    if(!decode_provider_preparation(text.substr(end+1),v.prepared)||provider_stop_wire(v)!=text)return false;
    out=std::move(v);return true;
}
namespace provider_stop_detail {
constexpr const char *leaf="provider-stop-intent.tsv",*temp="provider-stop-intent.tsv.tmp";
inline bool game_current(RootGpsFence &fence,const ProviderPreparation &prepared){
    using namespace provider_process_detail;const auto &old=prepared.original;
    uid_t uid{};int pid{};uint64_t ticks{};std::string boot;emu_planting_command::ProviderClock now;
    return fence.valid()&&owner("/data/user/0/com.nianticlabs.pikmin",uid)&&uid==static_cast<uid_t>(old.game_uid)&&
        fence_observation_detail::boot(boot)&&boot==old.boot&&unique(uid,"com.nianticlabs.pikmin",pid,ticks)&&
        pid==old.game_pid&&ticks==old.game_ticks&&clock(now)&&now.follows(old.clock)&&fence.valid();
}
inline bool read(int dir,ProviderStopIntent &out){
    using namespace fence_observation_detail;struct stat before{},opened{},after{},named{};std::string text;
    if(!provider_journal_detail::absent(dir,temp)||fstatat(dir,leaf,&before,AT_SYMLINK_NOFOLLOW)!=0||!provider_journal_detail::metadata(before))return false;
    int fd=openat(dir,leaf,O_RDONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);if(fd<0)return false;
    bool good=fstat(fd,&opened)==0&&same(before,opened)&&small(fd,text,768)&&fstat(fd,&after)==0&&same(opened,after);
    const bool closed=close(fd)==0;ProviderStopIntent value;
    good=good&&closed&&decode_provider_stop(text,value)&&fstatat(dir,leaf,&named,AT_SYMLINK_NOFOLLOW)==0&&
        same(opened,named)&&provider_journal_detail::absent(dir,temp);
    if(!good)return false;out=std::move(value);return true;
}
inline bool publish(RootGpsFence &fence,int dir,const ProviderStopIntent &value){
    if(!fence.valid()||!provider_journal_detail::absent(dir,leaf)||!provider_journal_detail::absent(dir,temp))return false;
    const auto text=provider_stop_wire(value);int fd=text.empty()?-1:openat(dir,temp,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600);
    if(fd<0)return false;bool good=true;size_t at=0;
    while(good&&at<text.size()){const auto n=write(fd,text.data()+at,text.size()-at);if(n<0&&errno==EINTR)continue;
        if(n<=0){good=false;break;}at+=n;}
    good=good&&fchmod(fd,0600)==0&&fsync(fd)==0;const bool closed=close(fd)==0;good=good&&closed;
    good=good&&fence.valid()&&syscall(SYS_renameat2,dir,temp,dir,leaf,1)==0&&fsync(dir)==0;
    ProviderStopIntent back;return good&&read(dir,back)&&provider_stop_wire(value)==provider_stop_wire(back)&&fence.valid();
}
inline bool original_live(RootGpsFence &fence,const ProviderPreparation &prepared,ProviderProcessObservation &out){
    ProviderProcessObservation current;std::string service;const auto &old=prepared.original;
    if(!capture_root_provider_service(fence,current,service)||service!=prepared.service||current.boot!=old.boot||current.game_pid!=old.game_pid||
       current.game_uid!=old.game_uid||current.game_ticks!=old.game_ticks||current.provider_pid!=old.provider_pid||
       current.provider_uid!=old.provider_uid||current.provider_ticks!=old.provider_ticks||!current.clock.follows(old.clock))return false;
    out=std::move(current);return true;
}
// Root observation only, bound back to the immutable original journal. Neither
// failed proc parsing nor a reused PID is accepted as absence. This does not
// exclude another provider instance or authorize START/restoration.
inline bool original_absent(RootGpsFence &fence,const ProviderPreparation &prepared){
    ProviderPreparation saved;uid_t uid{};
    if(!read_root_provider_preparation(fence,saved)||provider_preparation_wire(saved)!=provider_preparation_wire(prepared)||
       !game_current(fence,prepared)||!provider_process_detail::owner("/data/user/0/com.thejhijuqoqy.bdujvrwdcajvzin",uid)||
       uid!=static_cast<uid_t>(prepared.original.provider_uid))return false;
    const auto path="/proc/"+std::to_string(prepared.original.provider_pid);struct stat st{};
    errno=0;if(lstat(path.c_str(),&st)==0||errno!=ENOENT)return false;
    if(!game_current(fence,prepared)||!provider_process_detail::owner("/data/user/0/com.thejhijuqoqy.bdujvrwdcajvzin",uid)||
       uid!=static_cast<uid_t>(prepared.original.provider_uid))return false;
    errno=0;return lstat(path.c_str(),&st)!=0&&errno==ENOENT&&fence.valid();
}
}
// No production Port is supplied until independent service/route/exclusion
// evidence exists. A caller boolean or App journal must not implement this.
struct RootProviderStopPort {
    virtual ~RootProviderStopPort()=default;
    virtual bool writers_excluded(const ProviderPreparation &)=0;
    virtual bool original_route_idle(const ProviderPreparation &)=0;
    virtual void stop_original(const ProviderPreparation &)=0;
    virtual bool original_exited(const ProviderPreparation &)=0;
};
enum class RootStopResult {REFUSED,INTENT_RETAINED,EXIT_OBSERVED};
inline RootStopResult stop_root_provider_once(RootGpsFence &fence,const std::string &operation,RootProviderStopPort &port){
    using namespace provider_stop_detail;
    ProviderPreparation prepared;if(!read_root_provider_preparation(fence,prepared)||prepared.operation!=operation)return RootStopResult::REFUSED;
    int dir=fence.observation_directory();if(dir<0)return RootStopResult::REFUSED;
    ProviderStopIntent intent;bool created=false,good=game_current(fence,prepared)&&
        observe_root_isolated_profile(fence,prepared.original)&&provider_journal_detail::absent(dir,temp);
    if(good&&provider_journal_detail::absent(dir,leaf)){
        ProviderProcessObservation current;
        good=original_live(fence,prepared,current)&&port.writers_excluded(prepared)&&port.original_route_idle(prepared)&&
            original_live(fence,prepared,current)&&port.writers_excluded(prepared)&&fence.valid();
        intent={prepared,current.clock};
        if(good){good=publish(fence,dir,intent);created=good;}
    }else if(good)good=read(dir,intent)&&provider_preparation_wire(intent.prepared)==provider_preparation_wire(prepared);
    const bool closed=close(dir)==0;if(!good||!closed||!fence.valid())return RootStopResult::REFUSED;
    if(created){
        // Any failure after intent publication leaves it retained, even if no
        // effect was sent. Recovery must observe; it cannot retry the command.
        ProviderProcessObservation current;
        if(!observe_root_isolated_profile(fence,prepared.original)||!original_live(fence,prepared,current)||!port.writers_excluded(prepared)||!port.original_route_idle(prepared)||
           !original_live(fence,prepared,current)||!port.writers_excluded(prepared)||!fence.valid())return RootStopResult::INTENT_RETAINED;
        port.stop_original(prepared);
    }
    // A dead original service cannot answer route STATUS. Recovery only observes
    // its exit, while writer exclusion must remain true on both sides.
    if(!game_current(fence,prepared)||!port.writers_excluded(prepared)||!port.original_exited(prepared)||
       !port.writers_excluded(prepared)||!observe_root_isolated_profile(fence,prepared.original)||!game_current(fence,prepared))return RootStopResult::INTENT_RETAINED;
    return RootStopResult::EXIT_OBSERVED; // not persisted OLD_EXITED or permission to START
}
}

#pragma once
#include "planting_command_policy.h"

namespace emu_planting_command {
enum class NativeStopPhase { pending, closed };
// Read-only, in-process evidence. No serialized file is execution authority.
struct StopSnapshot {
    int pid{};uint64_t epoch{};
    std::string boot,account,core_sha,apk_sha,owner,token,start_operation,petal;
    int64_t captured{},started{};
    NativeStopPhase phase{NativeStopPhase::pending};
};
inline std::string stop_snapshot_wire(const StopSnapshot &s){
    if(s.pid<=0||s.pid>999999999||s.epoch==0||!uuid(s.boot)||!digest(s.account)||
       !digest(s.core_sha)||!digest(s.apk_sha)||!uuid(s.owner)||!uuid(s.token)||
       !uuid(s.start_operation)||!petal_id(s.petal)||s.captured<1000000000000LL||
       s.captured>9999999999999LL||s.started<=0||
       (s.phase!=NativeStopPhase::pending&&s.phase!=NativeStopPhase::closed))return {};
    return "s1\t"+std::to_string(s.captured)+"\t"+std::to_string(s.pid)+"\t"+
        std::to_string(s.epoch)+"\t"+s.boot+"\t"+s.account+"\t"+s.core_sha+"\t"+
        s.apk_sha+"\t"+s.owner+"\t"+s.token+"\t"+s.start_operation+"\t"+s.petal+
        "\t"+std::to_string(s.started)+"\t"+(s.phase==NativeStopPhase::pending?"pending":"closed")+"\tend\n";
}
inline bool stop_snapshot_decode(const std::string &wire,StopSnapshot &out){
    if(wire.empty()||wire.size()>1024||wire.back()!='\n'||wire.find('\r')!=std::string::npos||
       wire.find('\0')!=std::string::npos||wire.find('\n')!=wire.size()-1)return false;
    std::vector<std::string> f;size_t from=0;
    for(size_t i=0;i<wire.size();++i)if(wire[i]=='\t'){f.push_back(wire.substr(from,i-from));from=i+1;}
    f.push_back(wire.substr(from));if(f.size()!=15||f[0]!="s1"||f[14]!="end\n")return false;
    uint64_t captured{},pid{},epoch{},started{};
    if(!number(f[1],9999999999999ULL,captured)||!number(f[2],999999999,pid)||
       !number(f[3],std::numeric_limits<uint64_t>::max(),epoch)||
       !number(f[12],std::numeric_limits<int64_t>::max(),started))return false;
    StopSnapshot s;s.captured=static_cast<int64_t>(captured);s.pid=static_cast<int>(pid);s.epoch=epoch;
    s.boot=f[4];s.account=f[5];s.core_sha=f[6];s.apk_sha=f[7];s.owner=f[8];s.token=f[9];
    s.start_operation=f[10];s.petal=f[11];s.started=static_cast<int64_t>(started);
    if(f[13]=="pending")s.phase=NativeStopPhase::pending;else if(f[13]=="closed")s.phase=NativeStopPhase::closed;else return false;
    if(stop_snapshot_wire(s)!=wire)return false;out=s;return true;
}
inline bool same_stop_session(const StopSnapshot &a,const StopSnapshot &b){
    return a.pid==b.pid&&a.epoch==b.epoch&&a.boot==b.boot&&a.account==b.account&&
        a.core_sha==b.core_sha&&a.apk_sha==b.apk_sha&&a.owner==b.owner;
}
inline bool same_stop_round(const StopSnapshot &a,const StopSnapshot &b){
    return same_stop_session(a,b)&&a.token==b.token&&a.start_operation==b.start_operation&&
        a.petal==b.petal&&a.started==b.started;
}
}

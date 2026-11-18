#pragma once
#include <cstdint>
#include <limits>
#include <string>
#include <vector>

// Unwired MuMu command envelope. Parsing/context checks are NOT execution authority:
// the adapter must independently verify file ownership, global owner/lease,
// manual/core/resources, persist operation intent/readback, then recheck before RPC.
namespace emu_planting_command {
enum class Action { select, start, stop };
struct Command {
    int pid{};
    uint64_t epoch{};
    std::string account,owner,token,operation,petal,boot;
    Action action{Action::select};
    int64_t started{},expires{};
};
inline bool uuid(const std::string &s) {
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i) {
        if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
        else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;
    }return true;
}
inline bool digest(const std::string &s) {
    if(s.size()!=64)return false;
    for(char c:s)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    return true;
}
inline bool petal_id(const std::string &s) {
    if(s.empty()||s.size()>80||s=="-")return false;
    for(char c:s)if(!((c>='A'&&c<='Z')||(c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c==':'||c=='-'))return false;
    return true;
}
inline bool number(const std::string &s,uint64_t max,uint64_t &out) {
    if(s.empty()||s.size()>20||(s.size()>1&&s[0]=='0'))return false;
    uint64_t n=0;
    for(char c:s){if(c<'0'||c>'9')return false;const auto digit=static_cast<uint64_t>(c-'0');if(n>max/10||(n==max/10&&digit>max%10))return false;n=n*10+digit;}
    out=n;return true;
}
inline bool parse(const std::string &wire,Command &out) {
    // Failure leaves caller's previous output untouched. Never use partial fields.
    if(wire.empty()||wire.size()>600||wire.back()!='\n'||wire.find('\r')!=std::string::npos||wire.find('\0')!=std::string::npos||wire.find('\n')!=wire.size()-1)return false;
    std::vector<std::string> f;size_t from=0;
    for(size_t i=0;i<wire.size();++i)if(wire[i]=='\t'){f.push_back(wire.substr(from,i-from));from=i+1;}
    f.push_back(wire.substr(from));
    if(f.size()!=13||f[0]!="v2"||f[12]!="end\n"||!uuid(f[11]))return false;
    Command c;uint64_t pid{},epoch{},started{},expires{};
    if(!number(f[1],999999999,pid)||pid==0||!number(f[2],std::numeric_limits<uint64_t>::max(),epoch)||epoch==0||
       !digest(f[3])||!uuid(f[4])||!uuid(f[5])||!uuid(f[6])||!petal_id(f[8])||
       !number(f[9],std::numeric_limits<int64_t>::max(),started)||!number(f[10],9999999999999ULL,expires)||expires<1000000000000ULL)return false;
    if(f[7]=="select")c.action=Action::select;else if(f[7]=="start")c.action=Action::start;else if(f[7]=="stop")c.action=Action::stop;else return false;
    if((c.action==Action::stop)!=(started>0))return false;
    c.pid=static_cast<int>(pid);c.epoch=epoch;c.account=f[3];c.owner=f[4];c.token=f[5];c.operation=f[6];c.petal=f[8];c.started=static_cast<int64_t>(started);c.expires=static_cast<int64_t>(expires);
    c.boot=f[11];out=c;return true;
}
struct Context {
    int pid{};uint64_t epoch{};
    std::string account,owner,token,operation,selected,boot;
    int64_t now{},owned_started{},closed_started{};
    bool trusted_session{},known{},active{},owned{},ready_to_start{},result_closed{},owned_stop_pending{};
};
inline bool matches(const Command &c,const Context &f) {
    if(c.pid<=0||c.pid>999999999||c.epoch==0||!uuid(c.boot)||!digest(c.account)||!uuid(c.owner)||!uuid(c.token)||!uuid(c.operation)||!petal_id(c.petal)||
       c.expires<1000000000000LL||c.expires>9999999999999LL||c.started<0||
       (c.action!=Action::select&&c.action!=Action::start&&c.action!=Action::stop)||((c.action==Action::stop)!=(c.started>0)))return false;
    if(!f.trusted_session||f.pid<=0||f.epoch==0||!uuid(f.boot)||c.boot!=f.boot||!digest(f.account)||!uuid(f.owner)||!uuid(f.token)||!uuid(f.operation)||
       c.pid!=f.pid||c.epoch!=f.epoch||c.account!=f.account||c.owner!=f.owner||c.token!=f.token||
       c.operation!=f.operation||
       f.now<1000000000000LL||f.now>=c.expires||c.expires-f.now>15000||!f.known)return false;
    if(c.action==Action::stop){
        if(c.petal!=f.selected)return false;
        // An autonomous reserve/lease STOP may precede the host STOP intent.
        // The adapter must prove the same in-process owned round; inactivity
        // alone, a saved scalar journal or a foreign stop is never sufficient.
        const bool stopping=f.owned&&(f.active||f.owned_stop_pending)&&
            f.owned_started>0&&c.started==f.owned_started;
        const bool closed=!f.active&&!f.owned&&f.result_closed&&
            f.closed_started>0&&c.started==f.closed_started;
        return stopping||closed;
    }
    return f.ready_to_start&&!f.active&&!f.owned&&(c.action==Action::select||c.petal==f.selected);
}
}

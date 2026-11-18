#pragma once
#include <string>
#include <vector>
#include <map>
#include <set>
#include <sstream>
#include <cstdint>
namespace auto_petals {
struct Config { std::string revision; bool enabled{}; int reserve{50}; std::vector<std::string> order; };
inline bool id(const std::string &s) {
    if(s.empty()||s.size()>80)return false;
    for(unsigned char c:s)if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='.'||c=='_'||c==':'||c=='-'))return false;
    return true;
}
inline bool uuid(const std::string &s) {
    if(s.size()!=36)return false;
    for(size_t i=0;i<s.size();++i)if(i==8||i==13||i==18||i==23){if(s[i]!='-')return false;}
    else if(!((s[i]>='0'&&s[i]<='9')||(s[i]>='a'&&s[i]<='f')))return false;
    return true;
}
inline std::string encode(const Config &c) {
    std::string s="v1\t"+c.revision+"\t"+(c.enabled?"on":"off")+"\t"+std::to_string(c.reserve)+"\n";
    for(const auto &x:c.order)s+="petal\t"+x+"\n";
    return s+"end\n";
}
inline bool parse(const std::string &raw,Config &c) {
    c={}; if(raw.size()>8192)return false;
    std::istringstream in(raw);std::string version,mode,tag,item;int reserve;
    if(!(in>>version>>c.revision>>mode>>reserve)||version!="v1"||!uuid(c.revision)||
        (mode!="on"&&mode!="off")||reserve<0||reserve>1000000)return false;
    c.enabled=mode=="on";c.reserve=reserve;std::set<std::string> seen;
    while(in>>tag){if(tag=="end")break;if(tag!="petal"||!(in>>item)||!id(item)||!seen.insert(item).second||seen.size()>64)return false;c.order.push_back(item);}
    return tag=="end"&&encode(c)==raw;
}
struct RecoveryWindow {
    long long since{}, last{};
    uint64_t epoch{};
    bool ready(bool quiet, uint64_t current_epoch, long long now) {
        if (!quiet || !current_epoch || now <= 0) {
            *this = {};
            return false;
        }
        // Require consecutive observations. Suspension, clock rollback, or
        // re-login starts a new window, even for the same account.
        if (!since || current_epoch != epoch || now < last || now - last > 5000) since = now;
        epoch = current_epoch;
        last = now;
        return now - since >= 10000;
    }
};
enum class Action { unknown, keep, change, exhausted, outside };
struct Choice {Action action{Action::unknown};std::string next;};
inline bool receipt(bool known,bool same_account,bool same_epoch,long long expected_round,long long actual_round,
                    const std::string &next,const std::string &actual,bool active,long long before_change,long long after_change,bool owned_stop_task_succeeded=false){
    if(!known||!same_account||!same_epoch||expected_round<=0)return false;
    // Game clears round/id after stop; inactive alone is not a receipt.
    if(next=="STOP")return owned_stop_task_succeeded&&!active&&(actual_round==0||actual_round==expected_round);
    return expected_round==actual_round&&active&&actual==next&&after_change>before_change;
}
// A start acknowledgement can arrive before the local controller leaves its
// startup critical section. Require both sides + a stable 4-second window.
inline bool ready_to_change(bool server_active,bool controller_active,bool starting,bool result,long long since,long long now){
    return server_active&&controller_active&&!starting&&!result&&since>0&&now>=since&&now-since>=4000;
}
// A finite early-switch margin; it is not a guarantee of an exact retained count.
constexpr int margin=5;
inline Choice choose(const Config &c,const std::string &current,const std::map<std::string,int> &stocks,bool complete) {
    if(!c.enabled||c.order.empty()||!complete)return {};
    size_t at=0;while(at<c.order.size()&&c.order[at]!=current)++at;
    if(at==c.order.size())return {Action::outside,{}};
    const auto held=stocks.find(current);if(held==stocks.end()||held->second<0)return {};
    if(held->second>c.reserve+margin)return {Action::keep,{}};
    // Advance only: replenishment cannot make this round oscillate A -> B -> A.
    for(size_t i=at+1;i<c.order.size();++i){auto x=stocks.find(c.order[i]);
        if(x==stocks.end()||x->second<0)return {};
        if(x->second>c.reserve+margin)return {Action::change,x->first};}
    return {Action::exhausted,{}};
}
}

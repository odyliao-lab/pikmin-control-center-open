#pragma once
#include <string>
#include <vector>
#include <set>

namespace emu_petal {
constexpr int reserve=50;
struct Row {std::string id;int color{},stock{};bool ordinary{};};
struct Snapshot {
    long long captured{},revision{};
    int pid{};unsigned long long epoch{};
    std::string account;
    bool complete{};
    std::vector<Row> rows;
};
inline bool id_valid(const std::string &id) {
    if(id.empty() || id.size()>80) return false;
    for(unsigned char c:id) if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||
        (c>='0'&&c<='9')||c=='.'||c=='_'||c==':'||c=='-')) return false;
    return true;
}
inline bool coherent(const Snapshot &s,long long now,int pid,unsigned long long epoch,const std::string &account) {
    if(!s.complete || s.captured<=0 || s.revision<=0 || s.pid<=0 || s.pid!=pid || s.epoch!=epoch || epoch==0 ||
       s.account!=account || account.size()!=64 || now<s.captured || now-s.captured>12000 || s.rows.size()>512) return false;
    for(char c:account)if(!((c>='0'&&c<='9')||(c>='a'&&c<='f')))return false;
    std::set<std::string> ids;std::set<int> ordinary_colors;
    for(const auto &r:s.rows) {
        if(!id_valid(r.id) || r.color<1 || r.color>4 || r.stock<0 || r.stock>1000000 || !ids.insert(r.id).second) return false;
        if(r.ordinary && !ordinary_colors.insert(r.color).second) return false;
    }
    return true;
}
inline const Row *available(const Snapshot &s,const std::string &selected) {
    if(!coherent(s,s.captured,s.pid,s.epoch,s.account)) return nullptr;
    const Row *best{};
    // All confirmed inventory flower kinds are eligible, including seasonal
    // petals. The ordinary flag remains metadata, not a selection restriction.
    for(const auto &r:s.rows) if(r.stock>reserve) {
        if(r.id==selected) return &r;
        if(!best || r.stock>best->stock || (r.stock==best->stock &&
           (r.color<best->color || (r.color==best->color && r.id<best->id)))) best=&r;
    }
    return best;
}
// Backend must read the real server-confirmed item, reject active predictors,
// and translate color through the game's ToHoneyType (not a guessed mapping).
// Nothing below selects a petal, starts planting, writes a file or sends RPC.
template<class Backend> Snapshot capture(Backend &b) {
    Snapshot s;s.captured=b.now();s.pid=b.pid();s.epoch=b.epoch();s.account=b.account();
    s.revision=b.revision();
    if(!b.ready() || s.revision<=0 || !s.epoch || s.account.size()!=64) return s;
    int count{};
    if(!b.count(count) || count<0 || count>512) return s;
    for(int i=0;i<count;++i) {
        Row row;
        if(b.now()<s.captured || b.now()-s.captured>200 || !b.row(i,row)) return s;
        s.rows.push_back(row);
    }
    int final_count{};
    if(!b.count(final_count) || final_count!=count || b.now()<s.captured || b.now()-s.captured>200 || !b.ready() || b.revision()!=s.revision ||
       b.pid()!=s.pid || b.epoch()!=s.epoch || b.account()!=s.account) return s;
    s.complete=true;
    if(!coherent(s,b.now(),s.pid,s.epoch,s.account)) s.complete=false;
    return s;
}
}

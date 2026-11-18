#pragma once
#include <cstdint>
#include <map>
#include <string>
#include <set>

namespace pikmin {
// Scheduling hints ONLY. No managed pointers or permission to dispatch.
class DispatchBudget {
public:
    struct Hint { int64_t checked{}, duration{}; double lat{},lng{}; int picked{}; };
    std::map<std::string,Hint> hints;
    int used{};
    int64_t begun{};
    void begin(int64_t now){used=0;begun=now;}
    bool capacity(int64_t now) const {return used<4 && (used==0 || now-begun<4);}
    bool due(const std::string &id,int64_t now,double moved,bool settling) const {
        auto it=hints.find(id);
        if(settling||it==hints.end()||moved>=25)return true;
        const auto &h=it->second;
        const int64_t delay=h.duration>600000?30000:2000;
        return now<h.checked||now-h.checked>=delay;
    }
    void prune(const std::set<std::string> &live){
        for(auto it=hints.begin();it!=hints.end();)if(!live.count(it->first))it=hints.erase(it);else++it;
    }
};
}

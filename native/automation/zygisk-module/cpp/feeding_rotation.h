#pragma once
#include "feeding_observation.h"
#include <set>
namespace feeding_rotation {
struct Plan {std::vector<std::string> out,in;bool empty()const{return out.empty();}};
inline bool outgoing(const feeding_observation::Pikmin &p,int hearts) {
    return p.idle() && (p.flower==1||p.flower==3||p.flower==4||p.flower==6) && p.heart_milli>=0 &&
        (p.flower==6 || p.heart_milli>=hearts*1000);
}
inline bool incoming(const feeding_observation::Pikmin &p,int hearts) {
    return p.status==32 && !p.restricted && p.heart_milli>=0 && p.heart_milli<hearts*1000 &&
        (p.flower==1 || p.flower==3 || p.flower==4);
}
// One-for-one, at most 20 pairs (journal limit 40 IDs). Never remove without
// an eligible replacement. Pending collectible flowers stay in the troop.
inline Plan plan(const feeding_observation::Snapshot &s,int hearts,const std::set<std::string> &blocked={}) {
    Plan p;if(!s.complete||!s.roster_complete||hearts<1||hearts>8)return p;
    std::set<std::string> seen;
    for(const auto &v:s.squad)if(v.id.empty()||!seen.insert(v.id).second)return {};
    for(const auto &v:s.standby)if(v.id.empty()||!seen.insert(v.id).second)return {};
    for(const auto &v:s.squad)if(outgoing(v,hearts)&&!blocked.count(v.id)&&p.out.size()<20)p.out.push_back(v.id);
    for(const auto &v:s.standby)if(incoming(v,hearts)&&!blocked.count(v.id)&&p.in.size()<p.out.size())p.in.push_back(v.id);
    p.out.resize(p.in.size());return p;
}
inline bool confirmed(const feeding_observation::Snapshot &s,const std::vector<std::string> &ids) {
    if(!s.complete||ids.empty()||ids.size()%2)return false;
    std::set<std::string> troop;for(const auto &p:s.squad)troop.insert(p.id);
    const auto half=ids.size()/2;
    for(size_t i=0;i<half;++i)if(troop.count(ids[i])||!troop.count(ids[i+half]))return false;
    return true;
}
}

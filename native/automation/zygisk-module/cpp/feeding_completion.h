#pragma once
#include "feeding_pending.h"
#include "feeding_observation.h"
#include <map>
#include <algorithm>

namespace feeding_completion {
inline std::string signature(const feeding_observation::Pikmin &p) {
    return std::to_string(p.flower)+":"+std::to_string(p.flower_state_count)+":"+p.flower_key;
}
// Each outcome is sticky within this request: a later squad swap must not
// erase a previously observed completion or cause an old target to be resent.
inline void observe(const feeding_pending::Intent &intent,const feeding_observation::Snapshot &s,
                    const std::map<std::string,int> &flowers,const std::map<std::string,int> &plucks,
                    std::map<std::string,std::string> &outcomes,int64_t now) {
    const auto ids=intent.targets.empty()?std::vector<std::string>{intent.pikmin}:intent.targets;
    for(const auto &id:ids) {
        if(outcomes.count(id))continue;
        const auto p=std::find_if(s.squad.begin(),s.squad.end(),[&](const auto &x){return x.id==id;});
        if(p==s.squad.end()){outcomes[id]="squad-left";continue;}
        const auto f=flowers.find(id),c=plucks.find(id);
        bool changed=false;
        if(f!=flowers.end())changed=intent.action=="feed"?
            (p->flower!=f->second && (p->flower==3 || p->flower==5)):
            ((f->second==5 && (p->flower==4 || p->flower==6)) ||
             (c!=plucks.end() && p->flower_state_count>c->second));
        if(changed)outcomes[id]="confirmed";
        else if(now>=intent.epoch && now-intent.epoch>=30000)outcomes[id]="confirmation-timeout";
    }
}
inline bool complete(const feeding_pending::Intent &intent,const std::map<std::string,std::string> &outcomes) {
    const auto ids=intent.targets.empty()?std::vector<std::string>{intent.pikmin}:intent.targets;
    if(outcomes.size()!=ids.size())return false;
    for(const auto &id:ids) {
        const auto it=outcomes.find(id);
        if(it==outcomes.end() || (it->second!="confirmed" && it->second!="squad-left" && it->second!="confirmation-timeout"))return false;
    }
    return true;
}
// A timed-out target is delayed, never permanently disabled. Version 1 had
// no expiry and is migrated to a bounded cooldown by the coordinator.
struct Block {std::string signature;int64_t until{};};
using Blocks=std::map<std::pair<std::string,std::string>,Block>;
inline std::string encode_blocks(const Blocks &blocks) {
    std::string out="v2\n";
    for(const auto &b:blocks)out+="block\t"+b.first.first+"\t"+b.first.second+"\t"+
        b.second.signature+"\t"+std::to_string(b.second.until)+"\n";
    return out+"end\n";
}
inline bool decode_blocks(const std::string &raw,Blocks &out) {
    out.clear();if(raw.size()>262144)return false;
    std::istringstream in(raw);std::string v,tag,account,id,sig;
    if(!(in>>v)||(v!="v1"&&v!="v2"))return false;
    while(in>>tag && tag=="block") {
        int64_t until=0;
        if(!(in>>account>>id>>sig)||account.size()!=64||!feeding_pending::token(account,64)||
            !feeding_pending::token(id,256)||!feeding_pending::token(sig,128)||out.size()>=512||
            (v=="v2"&&(!(in>>until)||until<=0))||
            !out.emplace(std::make_pair(account,id),Block{sig,until}).second)return false;
    }
    if(tag!="end" || (in>>account))return false;
    if(v=="v2")return encode_blocks(out)==raw;
    std::string legacy="v1\n";
    for(const auto &b:out)legacy+="block\t"+b.first.first+"\t"+b.first.second+"\t"+b.second.signature+"\n";
    return legacy+"end\n"==raw;
}
}

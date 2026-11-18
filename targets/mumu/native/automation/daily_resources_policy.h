#pragma once
#include <array>
#include "game_daily_steps_policy.h"

// Passive MuMu resource protocol. No control, selection or gameplay authority.
namespace emu_daily_resources {
using Binding=emu_daily_steps::Binding;
struct Values {
    int64_t pikmin_used=-1,pikmin_max=-1,seed_used=-1,seed_max=-1;
    std::array<int64_t,4> ordinary{{-1,-1,-1,-1}};
    bool operator==(const Values &v) const {
        return pikmin_used==v.pikmin_used && pikmin_max==v.pikmin_max &&
            seed_used==v.seed_used && seed_max==v.seed_max && ordinary==v.ordinary;
    }
    bool valid() const {
        if(pikmin_used<0 || seed_used<0 || pikmin_max<=0 || seed_max<=0 ||
           pikmin_used>1000000 || seed_used>1000000 || pikmin_max>1000000 || seed_max>1000000)return false;
        for(auto n:ordinary)if(n<0 || n>1000000)return false;
        return true;
    }
};
struct Snapshot {
    Binding binding;
    Values values;
    int64_t captured=0,revision=0;
    bool complete=false;
};
template<class Backend> Snapshot capture(Backend &b) {
    Snapshot s;s.captured=b.now();s.binding=b.binding();
    if(s.captured<=0 || !s.binding.valid() || !b.ready())return s;
    s.revision=b.revision();
    if(s.revision<=0 || !b.read_confirmed(s.values) || !s.values.valid())return s;
    Values again;
    if(!b.read_confirmed(again) || !(s.values==again))return s;
    const auto end=b.now();
    s.complete=b.ready() && s.binding==b.binding() && s.revision==b.revision() &&
        end>=s.captured && end-s.captured<=200;
    return s;
}
inline std::string packet(const Snapshot &s) {
    if(!s.complete || !s.binding.valid() || !s.values.valid() || s.captured<=0 || s.revision<=0)return {};
    const auto &v=s.values;
    std::string out="v1\t"+std::to_string(s.captured)+"\t"+std::to_string(s.binding.pid)+"\t"+
        std::to_string(s.binding.epoch)+"\t"+s.binding.account+"\t"+std::to_string(s.revision)+"\t"+
        std::to_string(v.pikmin_used)+"\t"+std::to_string(v.pikmin_max)+"\t"+
        std::to_string(v.seed_used)+"\t"+std::to_string(v.seed_max);
    for(auto n:v.ordinary)out+='\t'+std::to_string(n);
    return out+"\tcomplete\tend\n";
}
}

#pragma once
#include <string>
#include <vector>
#include <cstdint>
#include <map>
namespace feeding_observation {
// Only absence in a successfully enumerated, confirmed petal inventory means
// zero. An incomplete/failed read remains unknown and cannot authorize feeding.
inline int confirmed_petal_stock(const std::map<std::pair<std::string,int>,int> &stocks,
                                 const std::string &family,int color,bool complete) {
    if (!complete || family.empty() || color < 1 || color > 4) return -1;
    const auto found=stocks.find(std::make_pair(family,color));
    return found==stocks.end()?0:found->second;
}
struct Nectar {
    std::string key, item_id;
    int stock{-1}, petals{-1}, capacity{-1}, color{};
    bool ordinary{};
};
struct Pikmin {
    std::string id, flower_key;
    // `FlowerStateFlowerCount` is a raw game observation.  Its semantics are
    // not a verified per-Pikmin feeding counter, so it must never authorize or
    // block a feeding action on its own.
    int status{}, heart_milli{-1}, flower{}, flower_state_count{-1}, next_petals{-1};
    bool restricted{true};
    bool idle() const {return status==1 && !restricted;}
};
struct Snapshot {
    std::string account;
    int64_t started{}, captured{};
    int64_t server_revision{};
    bool complete{};
    std::vector<Nectar> nectar;
    std::vector<Pikmin> squad;
    std::vector<Pikmin> standby;
    // Complete current troop + a bounded page of standby candidates, NOT a
    // claim that all owned Pikmin were checked for replacement eligibility.
    bool roster_complete{};
    bool fresh(int64_t now,const std::string &current_account) const {
        return complete && !account.empty() && account==current_account && started>0 &&
            captured>=started && now>=captured && now-started<=1000;
    }
};
}

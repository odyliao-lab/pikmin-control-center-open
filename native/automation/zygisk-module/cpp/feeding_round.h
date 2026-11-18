#pragma once
#include "feeding_observation.h"
#include <set>
#include <string>
#include <vector>

namespace feeding_round {
// Scheduling fairness only, not evidence of a successful feed. The durable
// journal remains authoritative for in-flight/uncertain work after a restart.
// Keep at most the current squad's IDs; do not retain an inventory-wide cursor.
class Round {
    std::string account_, revision_;
    std::set<std::string> served_;
public:
    void sync(const feeding_observation::Snapshot &s, const std::string &revision) {
        if(account_!=s.account || revision_!=revision) {
            served_.clear(); account_=s.account; revision_=revision;
        }
        std::set<std::string> retained;
        for(const auto &p:s.squad) if(served_.count(p.id) && retained.size()<40) retained.insert(p.id);
        served_.swap(retained);
    }
    bool served(const std::string &id) const { return served_.count(id)!=0; }
    void sent(const std::vector<std::string> &ids) {
        for(const auto &id:ids) if(served_.size()<40) served_.insert(id);
    }
    bool next() {
        if(served_.empty()) return false;
        served_.clear(); return true;
    }
};
}

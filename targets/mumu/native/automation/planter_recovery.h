#pragma once
#include <cstdint>
#include <string>
#include "planter_pending_store.h"

namespace pikmin::planter {
// All deadlines use the process monotonic clock. Wall time is audit data only.
struct Observation {
    static constexpr int64_t rpc_limit_ms = 30000;
    static constexpr int64_t inventory_limit_ms = 30000;
    static constexpr int64_t retry_delay_ms = 30000;
    int64_t began{}, responded_at{};
    uint64_t epoch{};
    std::string account;
    bool active{}, responded{};
    void begin(int64_t now, uint64_t session, const std::string &owner) {
        began=now; epoch=session; account=owner; responded_at=0; active=true; responded=false;
    }
    bool same_session(uint64_t session, const std::string &owner) const {
        return !owner.empty() && epoch==session && account==owner;
    }
    void response(int64_t now) { responded=true; responded_at=now; }
    const char *expiry(int64_t now) const {
        if(!active)return nullptr;
        if(now<began || (responded && now<responded_at))return "clock-reset-unknown";
        if(!responded && now-began>=rpc_limit_ms)return "rpc-timeout-unknown";
        if(responded && (now-responded_at>=inventory_limit_ms || now-began>=rpc_limit_ms+inventory_limit_ms))return "inventory-timeout-unknown";
        return nullptr;
    }
};
// A retirement never immediately authorizes another mutation. At least one
// later complete capture in the current session and the bounded delay are needed.
struct Adoption {
    bool waiting{true}, seen{};
    int64_t not_before{}, last_clock{};
    uint64_t epoch{};
    std::string account;
    void wait(int64_t now, bool anomaly) {
        waiting=true; seen=false; last_clock=now;
        not_before=now+(anomaly?Observation::retry_delay_ms:5000);
    }
    bool observe(bool complete, uint64_t session, const std::string &owner, int64_t now) {
        if(now<last_clock){not_before=now+Observation::retry_delay_ms;seen=false;waiting=true;}
        last_clock=now;
        if(!complete||owner.empty()){seen=false;return false;}
        if(!seen||epoch!=session||account!=owner){
            epoch=session;account=owner;seen=true;return false;
        }
        if(now<not_before)return false;
        waiting=false;return true;
    }
};
}

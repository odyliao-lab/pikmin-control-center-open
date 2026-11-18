#pragma once
#include <algorithm>
#include <cstdint>

namespace feeding_policy {
// Side-effect-free preflight. The executor must supply fresh account-bound
// observations and persist pending BEFORE any request; policy is not RPC proof.
struct Config {
    bool enabled{}, collect{true}, aggressive_pick{true}, allowed{};
    int hearts{4};
    int64_t reserve{100}, ceiling{250};
};
struct Facts {
    bool complete{}, account_ready{}, in_squad{}, idle{}, can_feed{}, can_collect{}, priority_work{}, pending{};
    int64_t nectar{-1}, petals{-1}, capacity{-1}, reserved_nectar{}, reserved_petals{}, cost{-1}, yield{-1};
    // Observation-only: do not treat FlowerStateFlowerCount as a feed limit.
    int heart_milli{-1}, flower_state_count{-1};
};
inline const char *decide(const Config &c, const Facts &f) {
    if (!c.enabled) return "off";
    if (f.pending) return "pending-review";
    if (c.hearts < 1 || c.hearts > 8 || c.reserve < 0 || c.reserve > 1000000 || c.ceiling < 1 || c.ceiling > 1000000)
        return "waiting-data";
    if (!f.complete || !f.account_ready) return "waiting-data";
    if (f.priority_work) return "waiting-other-work";
    if (!f.in_squad || !f.idle) return "waiting-squad";
    if (f.nectar < 0 || f.petals < 0 || f.capacity <= 0 || f.petals > f.capacity ||
        f.reserved_nectar < 0 || f.reserved_petals < 0 || f.heart_milli < 0 || f.heart_milli > 8000 || f.flower_state_count < 0)
        return "waiting-data";
    const auto limit = std::min(f.capacity, c.ceiling);
    if (f.can_collect) {
        if (!c.collect) return "waiting-collect";
        if (f.yield < 0 || f.reserved_petals > limit || f.yield > limit - f.reserved_petals ||
            f.petals > limit - f.reserved_petals - f.yield) return "petal-limit";
        return "collect";
    }
    if (!c.allowed) return "not-allowed";
    if (f.heart_milli >= c.hearts * 1000) return "friendship-reached";
    // The game owns its own action restriction gate.  FlowerStateFlowerCount
    // remains in the diagnostic record only: it is not a verified feed-count
    // API and must not become a local fifth/sixth action cutoff.
    if (!f.can_feed) return "waiting-recovery";
    if (f.cost <= 0 || f.yield < 0) return "waiting-data";
    if (f.reserved_nectar > f.nectar || f.cost > f.nectar - f.reserved_nectar ||
        f.nectar - f.reserved_nectar - f.cost < c.reserve) return "nectar-reserve";
    if (f.petals >= limit || f.reserved_petals > limit - f.petals || f.yield > limit - f.petals - f.reserved_petals)
        return "petal-limit";
    return "feed";
}
}

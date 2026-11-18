#pragma once

#include <cstdint>

namespace pikmin {

enum class DispatchScanScope { none, full, batch_target };

// A target probe may supplement, never replace, the complete five-second
// projection used for inventory confirmation and capability attribution.
struct DispatchScanCadence {
    static constexpr int64_t kWalkingFullMs = 250;
    static constexpr int64_t kOtherFullMs = 5000;
    static constexpr int64_t kBatchTargetMs = 2000;

    static bool due(int64_t now, int64_t last, int64_t period) {
        return last <= 0 || now < last || now - last >= period;
    }

    static DispatchScanScope choose(int64_t now, int64_t last_full,
                                    int64_t last_target, bool walking,
                                    bool has_batch_target) {
        if (due(now, last_full, walking ? kWalkingFullMs : kOtherFullMs))
            return DispatchScanScope::full;
        if (has_batch_target && due(now, last_target, kBatchTargetMs))
            return DispatchScanScope::batch_target;
        return DispatchScanScope::none;
    }
};

}  // namespace pikmin

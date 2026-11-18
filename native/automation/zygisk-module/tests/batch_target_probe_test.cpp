#include "../cpp/batch_target_probe.h"

#include <cassert>
#include <cstdio>

int main() {
    using pikmin::DispatchScanCadence;
    using pikmin::DispatchScanScope;
    constexpr int64_t t = 1'800'000'000'000;
    assert(DispatchScanCadence::choose(t, 0, 0, false, false) == DispatchScanScope::full);
    assert(DispatchScanCadence::choose(t, t - 4999, t - 1999, false, true) == DispatchScanScope::none);
    assert(DispatchScanCadence::choose(t, t - 4999, t - 2000, false, true) == DispatchScanScope::batch_target);
    assert(DispatchScanCadence::choose(t, t - 5000, t - 2000, false, true) == DispatchScanScope::full);
    assert(DispatchScanCadence::choose(t, t - 4999, t - 5000, false, false) == DispatchScanScope::none);
    assert(DispatchScanCadence::choose(t, t - 249, 0, true, false) == DispatchScanScope::none);
    assert(DispatchScanCadence::choose(t, t - 250, 0, true, false) == DispatchScanScope::full);
    assert(DispatchScanCadence::choose(t, t + 1, t + 1, false, true) == DispatchScanScope::full);
    std::puts("PASS batch_target_probe_test");
}

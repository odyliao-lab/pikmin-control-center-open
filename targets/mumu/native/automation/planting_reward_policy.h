#pragma once
#include <cstdint>

// MuMu candidate only; process-local roots cannot be recovered from a journal.
namespace pcc_owned_reward {
struct Context {
    int64_t now{}, stop_sent{}, seen{};
    bool stopping{}, same_session{}, manual_clear{}, storage_ok{}, mode_off{};
    bool namespace_clear{}, close_journaled{}, record_owned{}, new_task{}, inactive{};
    bool capture_matches{}, close_completed{}, consumed{};
};
inline bool authorized(const Context &c) {
    return c.stop_sent>0 && c.now>=c.stop_sent && c.now-c.stop_sent<=60000 &&
        c.stopping && c.same_session && c.manual_clear && c.storage_ok && c.mode_off &&
        c.namespace_clear && c.close_journaled && c.record_owned && c.new_task && c.inactive;
}
inline bool collectible(const Context &c) {
    return authorized(c) && c.capture_matches && c.close_completed && !c.consumed &&
        c.seen>=c.stop_sent && c.now>=c.seen && c.now-c.seen>=1000;
}
}

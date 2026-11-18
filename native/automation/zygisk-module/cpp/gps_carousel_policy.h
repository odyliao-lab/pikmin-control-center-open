#pragma once
#include <cstdint>
#include <string>

namespace gps_carousel {
// Called on the game UI thread. Ambiguous/hidden/disabled pages cancel a
// pending copy; a transient preloaded neighbour must never overwrite the clip.
class Selection {
    std::u16string pending_, delivered_;
    int64_t since_{};
public:
    void cancel() { pending_.clear(); delivered_.clear(); since_=0; }
    bool observe(const std::u16string &key, int64_t now, bool eligible) {
        if(!eligible || key.empty()) { cancel(); return false; }
        if(key!=pending_) { pending_=key; since_=now; }
        return delivered_!=key && now>=since_ && now-since_>=400;
    }
    void copied() { delivered_=pending_; }
};
}

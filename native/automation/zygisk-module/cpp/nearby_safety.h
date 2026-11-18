#pragma once
#include <cmath>
#include <cstdint>

namespace pikmin {
inline constexpr int64_t kNearbyMaxDurationMs = 300000;
inline constexpr double kNearbyMaxMovingLagMetres = 40.0;
inline bool nearby_duration_safe(int64_t ms) { return ms > 0 && ms <= kNearbyMaxDurationMs; }
struct NearbyFix {
    bool valid{};
    double raw_lat{}, raw_lng{}, game_lat{}, game_lng{};
    int64_t raw_wall_ms{}; // Game field name; CLOCK_BOOTTIME milliseconds, not Unix time.
};
inline bool valid_point(double lat, double lng) {
    return std::isfinite(lat) && std::isfinite(lng) && std::abs(lat) <= 90 && std::abs(lng) <= 180;
}
inline double nearby_metres(double a, double b, double c, double d) {
    constexpr double rad = 3.14159265358979323846 / 180;
    const double x = std::sin((c-a)*rad/2), y = std::sin((d-b)*rad/2);
    const double h = x*x + std::cos(a*rad)*std::cos(c*rad)*y*y;
    return 12742000 * std::asin(std::sqrt(std::fmin(1.0, std::fmax(0.0, h))));
}
/** Only processed game coordinates matched to fresh raw game fixes can open this gate.
 * One epoch invalidates ALL unsent selections on a jump, mismatch, stale input or clock rollback.
 * Re-reading one fix never counts as several fresh location updates. */
class NearbyLocationGate {
    bool anchored_{};
    double anchor_lat_{}, anchor_lng_{}, raw_lat_{}, raw_lng_{};
    int64_t since_{}, last_seen_{}, last_fix_{}, last_elapsed_{}, game_seen_{};
    double window_raw_lat_{}, window_raw_lng_{}, window_game_lat_{}, window_game_lng_{};
    int64_t window_since_{}, window_fix_{};
    unsigned samples_{};
    uint64_t epoch_{};
    const char *reason_{"waiting-game-location"};
public:
    void reset(const char *reason) {
        anchored_ = false; samples_ = 0; since_ = last_fix_ = 0;
        reason_ = reason; ++epoch_;
    }
    // elapsed must share the game's boot-time clock (includes device suspend).
    // steady is monotonic time for cross-tick selection/heartbeat intervals.
    void observe(const NearbyFix &f, int64_t elapsed, int64_t steady) {
        if ((last_seen_ && (steady < last_seen_ || steady-last_seen_ > 5000)) ||
                (last_elapsed_ && elapsed < last_elapsed_)) reset("location-gap");
        last_seen_ = steady; last_elapsed_ = elapsed;
        if (!f.valid || !valid_point(f.raw_lat, f.raw_lng) || !valid_point(f.game_lat, f.game_lng)) {
            reset("waiting-game-location"); return;
        }
        if (elapsed <= 0 || f.raw_wall_ms <= 0 || f.raw_wall_ms > elapsed || elapsed-f.raw_wall_ms > 5000) {
            reset("stale-game-location"); return;
        }
        const double lag = nearby_metres(f.raw_lat, f.raw_lng, f.game_lat, f.game_lng);
        if (lag > kNearbyMaxMovingLagMetres) {
            reset("game-location-catching-up"); return;
        }
        // Raw fixes and the game's interpolation have different clocks.
        // A cached raw fix must not count as another fix, but the processed
        // point can legitimately move between raw updates. Bound that movement
        // by observation time, not raw-fix time (which can be unchanged).
        if (anchored_) {
            const int64_t dt = f.raw_wall_ms-last_fix_;
            const int64_t game_dt = steady-game_seen_;
            const double raw_step = nearby_metres(raw_lat_, raw_lng_, f.raw_lat, f.raw_lng);
            const double game_step = nearby_metres(anchor_lat_, anchor_lng_, f.game_lat, f.game_lng);
            const int64_t window_dt = steady-window_since_;
            const double game_window_step = nearby_metres(window_game_lat_, window_game_lng_, f.game_lat, f.game_lng);
            if (dt < 0 || dt > 5000 || (dt == 0 && raw_step > 0.5) ||
                    (dt > 0 && raw_step > 7.0*dt/1000.0+2.0) ||
                    game_dt < 0 || (game_dt == 0 && game_step > 0.5) ||
                    (game_dt > 0 && game_step > 7.0*game_dt/1000.0+2.0) ||
                    game_window_step > 7.0*window_dt/1000.0+2.0) {
                reset("location-moving");
            } else if (window_dt >= 1000) {
                // A single 1ms timestamp refresh or a frame at a turn does not
                // prove either position stopped. Check actual progress over a
                // window containing >=500ms of raw-fix time instead. The same
                // window also prevents spending 2m jitter on every frame.
                const double raw_window_step = nearby_metres(window_raw_lat_, window_raw_lng_, f.raw_lat, f.raw_lng);
                if (lag > 8.0 && f.raw_wall_ms-window_fix_ >= 500 &&
                        (raw_window_step < 0.5 || game_window_step < 0.5)) {
                    reset("game-location-catching-up");
                } else {
                    window_since_ = steady; window_fix_ = f.raw_wall_ms;
                    window_raw_lat_ = f.raw_lat; window_raw_lng_ = f.raw_lng;
                    window_game_lat_ = f.game_lat; window_game_lng_ = f.game_lng;
                }
            }
        }
        if (!anchored_) {
            anchored_ = true;
            since_ = steady; samples_ = 1; last_fix_ = f.raw_wall_ms;
            raw_lat_ = f.raw_lat; raw_lng_ = f.raw_lng;
            window_since_ = steady; window_fix_ = f.raw_wall_ms;
            window_raw_lat_ = f.raw_lat; window_raw_lng_ = f.raw_lng;
            window_game_lat_ = f.game_lat; window_game_lng_ = f.game_lng;
        } else if (f.raw_wall_ms > last_fix_) {
            last_fix_ = f.raw_wall_ms; if (samples_ < 3) ++samples_;
            raw_lat_ = f.raw_lat; raw_lng_ = f.raw_lng;
        }
        anchor_lat_ = f.game_lat; anchor_lng_ = f.game_lng; game_seen_ = steady;
        reason_ = samples_ >= 3 && steady-since_ >= 3000 ? "ready" : "location-settling";
    }
    bool ready(int64_t steady) const {
        return anchored_ && samples_ >= 3 && steady >= since_ && steady-since_ >= 3000
                && steady >= last_seen_ && steady-last_seen_ <= 2000;
    }
    uint64_t epoch() const { return epoch_; }
    unsigned samples() const { return samples_; }
    const char *reason() const { return reason_; }
};
}

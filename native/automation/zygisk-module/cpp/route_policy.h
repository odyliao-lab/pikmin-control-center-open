#pragma once
#include "nearby_safety.h"
#include <cstdio>
#include <string>
#include <deque>

namespace pikmin {
struct RouteLease {
    int pid{}; long long until{}; std::string token, phase, account;
    static RouteLease parse(const std::string &raw, int expected, long long now) {
        RouteLease r; char token[65]{},phase[16]{},account[65]{}; int end{};
        if(raw.size()>200 || std::sscanf(raw.c_str(),"v2 %d %lld %64s %15s %64s %n",&r.pid,&r.until,token,phase,account,&end)!=5
                || end!=static_cast<int>(raw.size()) || r.pid!=expected || now<=0 || r.until<=now || r.until-now>15000) return {};
        r.token=token;r.phase=phase;r.account=account;
        if(r.account.size()!=64||r.account.find_first_not_of("0123456789abcdef")!=std::string::npos)return {};
        if(r.token.size()!=36 || r.token.find_first_not_of("0123456789abcdef-")!=std::string::npos
                || (r.phase!="prepare"&&r.phase!="walk"&&r.phase!="hold")) return {};
        return r;
    }
    bool valid() const { return !token.empty(); }
};
// Dedicated route gate.  JoyStick moves continuously but the game commonly
// advances its processed position in several delayed hops.  Keep a bounded raw
// trail while that happens; never authorize a start from stale input, an
// implausible raw jump, or a processed position more than 80m behind.
class RouteLocationGate {
    struct Sample { NearbyFix f; int64_t elapsed; };
    std::deque<Sample> history;
    uint64_t epoch_{};
    bool ready_{}, catching_{}, recovered_window_{};
    int64_t seen_{}, matched_since_{}, catching_since_{};
    unsigned matched_samples_{};
    unsigned catchup_events_{}, over_40_samples_{}, over_80_events_{}, stale_events_{}, recoveries_{};
    const char *reason_{"route-waiting"};

    static constexpr int64_t kFreshMs = 8000;
    static constexpr int64_t kCatchupGraceMs = 6000;
    static constexpr int64_t kTrailMs = 20000;
    static constexpr double kTrailMatchMetres = 8.0;
    static constexpr double kCatchupLagMetres = 12.0;
    static constexpr double kWarnLagMetres = 40.0;
    static constexpr double kMaxLagMetres = 80.0;

    void invalidate(const char *reason, bool clear_history) {
        ready_ = false; matched_since_ = 0; matched_samples_ = 0;
        recovered_window_ = false; reason_ = reason; ++epoch_;
        if (clear_history) history.clear();
    }
    void enter_catchup(int64_t elapsed) {
        if (!catching_) {
            catching_ = true; catching_since_ = elapsed; ++catchup_events_;
            invalidate("route-catching-up", false);
        }
        if (elapsed >= catching_since_ && elapsed-catching_since_ > kCatchupGraceMs) {
            // Keep only the newest raw trail after a prolonged stall.  Recovery
            // still needs two fresh matched fixes, but does not pay the old ten
            // second warm-up again.
            while (history.size() > 1 && elapsed-history.front().elapsed > kCatchupGraceMs)
                history.pop_front();
            reason_ = "route-catching-up";
        }
    }
public:
    void begin_session() {
        history.clear(); ready_=catching_=recovered_window_=false;
        seen_=matched_since_=catching_since_=0; matched_samples_=0;
        catchup_events_=over_40_samples_=over_80_events_=stale_events_=recoveries_=0;
        reason_="route-waiting"; ++epoch_;
    }
    void reset() {
        history.clear(); ready_=catching_=recovered_window_=false;
        seen_=matched_since_=catching_since_=0; matched_samples_=0;
        reason_="route-waiting"; ++epoch_;
    }
    void observe(const NearbyFix &f,int64_t elapsed) {
        if(!f.valid || !valid_point(f.raw_lat,f.raw_lng)||!valid_point(f.game_lat,f.game_lng)
                ||f.raw_wall_ms<=0||elapsed<f.raw_wall_ms||elapsed-f.raw_wall_ms>kFreshMs) {
            ++stale_events_; catching_=false; invalidate("route-stale",true); seen_=elapsed; return;
        }
        if(!history.empty()) {
            const auto &p=history.back().f; auto dt=f.raw_wall_ms-p.raw_wall_ms;
            if(elapsed<seen_||elapsed-seen_>kFreshMs||dt<0||dt>kFreshMs
                    ||nearby_metres(p.raw_lat,p.raw_lng,f.raw_lat,f.raw_lng)>7.0*dt/1000+2) {
                ++stale_events_; catching_=false; invalidate("route-discontinuous",true); seen_=elapsed; return;
            }
            if(dt==0) {
                // A producer may repeat the same snapshot.  Accept an exact
                // duplicate. The game's processed point is allowed to catch
                // up while the JoyStick raw timestamp stays unchanged; only
                // raw movement without raw clock progress is unverifiable.
                const double raw_delta=nearby_metres(p.raw_lat,p.raw_lng,f.raw_lat,f.raw_lng);
                const double game_delta=nearby_metres(p.game_lat,p.game_lng,f.game_lat,f.game_lng);
                if(raw_delta>0.5) {
                    ++stale_events_; catching_=false; invalidate("route-discontinuous",true);
                    seen_=elapsed; return;
                }
                if(game_delta<=0.5) { seen_=elapsed; return; }
            }
        }
        const double raw_step = history.empty() ? 0.0 : nearby_metres(
                history.back().f.raw_lat,history.back().f.raw_lng,f.raw_lat,f.raw_lng);
        const double game_step = history.empty() ? 0.0 : nearby_metres(
                history.back().f.game_lat,history.back().f.game_lng,f.game_lat,f.game_lng);
        const double lag = nearby_metres(f.raw_lat,f.raw_lng,f.game_lat,f.game_lng);
        if (lag > kWarnLagMetres) ++over_40_samples_;
        if (lag > kMaxLagMetres) {
            ++over_80_events_; catching_=false; invalidate("route-lag-over-80m",true);
            seen_=elapsed; history.push_back({f,elapsed}); return;
        }
        seen_=elapsed; history.push_back({f,elapsed});
        while(history.size()>1 && elapsed-history.front().elapsed>kTrailMs)history.pop_front();
        bool matches=false;
        for(const auto &p:history) if(elapsed-p.elapsed<=kTrailMs
                &&nearby_metres(p.f.raw_lat,p.f.raw_lng,f.game_lat,f.game_lng)<=kTrailMatchMetres) {matches=true;break;}
        const bool stalled = history.size()>1 && raw_step>0.5 && game_step<0.5 && lag>kCatchupLagMetres;
        if(!matches || stalled) { enter_catchup(elapsed); return; }

        if(catching_) {
            catching_=false; catching_since_=0; ++recoveries_; recovered_window_=true;
            matched_since_=elapsed; matched_samples_=1; ready_=false; reason_="route-settling"; return;
        }
        if(matched_since_==0) { matched_since_=elapsed; matched_samples_=1; }
        else if(matched_samples_<2) ++matched_samples_;
        ready_=matched_samples_>=2 && elapsed>=matched_since_ && elapsed-matched_since_>=1000;
        reason_=ready_?"ready":"route-settling";
    }
    bool ready(int64_t elapsed) const {return ready_ && elapsed>=seen_ && elapsed-seen_<=kFreshMs;}
    uint64_t epoch() const {return epoch_;}
    const char *reason() const {return reason_;}
    bool recovered_window() const {return recovered_window_;}
    unsigned catchup_events() const {return catchup_events_;}
    unsigned over_40_samples() const {return over_40_samples_;}
    unsigned over_80_events() const {return over_80_events_;}
    unsigned stale_events() const {return stale_events_;}
    unsigned recoveries() const {return recoveries_;}
};
}

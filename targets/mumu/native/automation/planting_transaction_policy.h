#pragma once
#include <cstdint>

// MuMu-only policy. No game pointers, file I/O, RPC or coordinates.
namespace emu_planting_tx {
enum class Phase { idle, start_pending, active, stop_pending, stopped, review };
enum class Task { unknown, pending, success, rejected, faulted };
enum class Review { none, unsafe, start_timeout, stop_timeout };
// Only a live, originally owned reserve-stop may finish with mode still ON.
// This is not timeout/restart reconciliation permission. All managed Task,
// record, session and deadline checks remain in the caller.
inline bool cleared_result_allowed(bool known,bool active,bool manual_clear,
                                   bool mode_off,bool reserve_stop,bool same_lease) {
    return known && !active && manual_clear && (mode_off || (reserve_stop && same_lease));
}
struct State {
    Phase phase{Phase::idle};
    // Process-local provenance only. Loading a journal never reconstructs this
    // authorization or managed Task roots. A restart remains manual REVIEW.
    Review review{Review::none};
    int64_t sent{}, first_match{}, last_match{};
    unsigned matches{};
    void submitted(bool start, int64_t now) {
        phase=start?Phase::start_pending:Phase::stop_pending;
        review=Review::none;
        sent=now; first_match=last_match=0; matches=0;
    }
    // Result success alone is insufficient; state samples must follow send.
    // Inactive while starting is never proof of a failed/unsent request.
    void observe(int64_t now, bool same_session, bool known, bool planting,
                 Task task, bool dialog_closed) {
        if(phase!=Phase::start_pending && phase!=Phase::stop_pending) return;
        if(!same_session || now<sent ||
           task==Task::rejected || task==Task::faulted) {
            review=Review::unsafe;phase=Phase::review; return;
        }
        if(now-sent>60000) {
            review=phase==Phase::stop_pending?Review::stop_timeout:Review::start_timeout;
            phase=Phase::review;first_match=last_match=0;matches=0;return;
        }
        const bool expected=phase==Phase::start_pending;
        if(!known || planting!=expected || task!=Task::success ||
           (!expected && !dialog_closed)) {
            first_match=last_match=0; matches=0; return;
        }
        if(now<=sent || now<=last_match) return;
        if(matches==0) first_match=now;
        last_match=now; ++matches;
        if(matches>=2 && now-first_match>=1000)
            phase=expected?Phase::active:Phase::stopped;
    }
    // A separate bounded reconciliation of the ORIGINAL stop, never a new
    // request or a reset of its 60-second execution/confirmation deadline.
    // Only live in-process stop-timeout provenance permits it. Initial review
    // is retained until two fresh samples prove the original result closed.
    bool late_stop_window(int64_t now) const {
        return phase==Phase::review && review==Review::stop_timeout &&
            sent>0 && now>sent && now-sent>60000 && now-sent<=120000;
    }
    void reconcile_stop(int64_t now,bool same,bool known,bool planting,Task task,bool closed) {
        if(!late_stop_window(now) || !same || task==Task::faulted || task==Task::rejected) {
            review=Review::unsafe;first_match=last_match=0;matches=0;return;
        }
        if(!known || planting || task!=Task::success || !closed) {
            first_match=last_match=0;matches=0;return;
        }
        if(now<=last_match)return;
        if(matches==0)first_match=now;
        last_match=now;++matches;
        if(matches>=2 && now-first_match>=1000){phase=Phase::stopped;review=Review::none;}
    }
};
}

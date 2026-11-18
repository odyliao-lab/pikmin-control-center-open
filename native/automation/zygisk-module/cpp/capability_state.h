// Read-only capability/diagnostic state for core_capabilities.tsv.
//
// Pure C++ (no Android, no IL2CPP, no I/O): this exact header is compiled into
// the core and into the host interleaving tests, so the synchronisation that
// is tested is the synchronisation that ships.
//
// Design: docs/handoffs/2026-09-16-phase-b-native-sync-design.md (N01).
//
//  * One diagnostic mutex (`Registry::mu_`) guards a POD `State`. Every writer
//    (bootstrap thread, game callbacks, scan producer) does lock → assign a
//    few plain values → unlock. Nothing inside the lock does I/O, allocates,
//    touches IL2CPP or takes another lock. The registry never acquires the
//    business `identity_mutex`; callers must not hold it while calling in
//    (documented ordering: diagnostic events are published *before* the
//    business identity is invalidated, so a scan can never pass on a stale
//    diagnostic epoch while the identity source already moved).
//
//  * `session_epoch` is the single authority for scan attribution. Login and
//    logout both advance it. A scan takes a token (epoch + validity) under the
//    lock before reading game data and commits under the same lock; commit
//    succeeds only if the epoch is unchanged and the identity is not logged
//    out. Because logout's epoch++ and the scan's check-and-publish run under
//    the same mutex, there is no check-to-publish window (invariant 3).
//
//  * Per-session captures carry the epoch they observed. A capture whose epoch
//    is older than the current one is recorded as observed but does not clear
//    the stale bit of the newer session (invariant 4). Late "ready" events
//    carrying an older epoch are ignored.
//
//  * `snapshot()` copies the whole State under the lock and bumps `seq`; the
//    heartbeat thread formats and writes outside the lock. The file may
//    therefore lag until the next successful publish (the documented disk-
//    snapshot window, with no fixed bound); a reader must treat it as "valid
//    at sample time" and judge staleness only by the sampled time.
#pragma once
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <mutex>

namespace pcc_cap {

constexpr int kSchemaVersion = 1;
constexpr int kStepCount = 14;
constexpr int kCapCount = 12;
constexpr size_t kLineCapacity = 8192;   // fixed output buffer; format() reports truncation instead of overflowing

enum class Stage : uint8_t { boot = 0, lib_wait, lib_timeout, symbols_missing, incompatible, hooks_called };
enum class Domain : uint8_t { pending = 0, ready };
enum class Identity : uint8_t { unknown = 0, ready, logged_out };
enum class StepState : uint8_t { not_reached = 0, ok, failed, blocked };

// Failure codes shared by `failed` (bootstrap) and step entries.
enum class Code : uint8_t {
    none = 0, class_missing, field_missing, entry_mismatch, method_missing, no_entry, ctor_missing,
    method_unavailable, rpc_entry_mismatch, proto_layout_mismatch, symbol, il2cpp_size, lib_timeout, count_
};

// Steps in the real resolution order (initialize_runtime_metadata → install_return_diagnostic_hook → …).
enum Step : uint8_t {
    return_action_manager_class = 0, return_blocker_capture, return_complete_task_hook, return_preparer_ctor_hook,
    return_prepare_completion_hook, return_readiness_taskid, return_task_list_time, expedition_methods,
    availability_base, availability_selected, availability_gift, expedition_pick_fastest, wire_probe, logout_hook
};

// Capability bits. "Captured/resolved", never "call succeeded".
enum Cap : uint32_t {
    cap_preparer = 1u << 0,             // per-session instance
    cap_expedition_start = 1u << 1,
    cap_action_manager = 1u << 2,       // per-session instance
    cap_complete_task = 1u << 3,
    cap_planter = 1u << 4,              // per-process: planter methods resolved once; the instance it uses is the preparer's (see cap_preparer)
    cap_planting_controller = 1u << 5,  // per-session instance
    cap_location_controller = 1u << 6,  // per-session instance
    cap_feeding_probe = 1u << 7,
    cap_nectar_claim = 1u << 8,
    cap_availability_gift = 1u << 9,
    cap_availability_selected = 1u << 10,
    cap_pick_fastest = 1u << 11
};
constexpr uint32_t kPerSessionCaps = cap_preparer | cap_action_manager | cap_planting_controller | cap_location_controller;
constexpr uint32_t kAllCaps = (1u << kCapCount) - 1u;

struct StepEntry { StepState state; Code code; uint8_t parent; };

// Plain values only. Copied whole under the lock; formatted outside it.
struct State {
    uint64_t seq = 0;
    Stage stage = Stage::boot;
    Code failed = Code::none;
    int64_t failed_detail = 0;          // e.g. observed libil2cpp size
    Domain domain = Domain::pending;
    StepEntry steps[kStepCount] = {};
    uint32_t caps = 0;
    uint32_t stale = 0;
    uint64_t session_gen = 0;           // logins seen
    uint64_t session_epoch = 0;         // login and logout both advance it
    Identity identity = Identity::unknown;
    int64_t last_login_ms = 0;
    int64_t last_map_ms = 0;
    uint64_t scan_epoch = 0;
    int64_t scan_ms = 0;
    int32_t scan_count = -1;            // -1 = never published
    bool gps = false;
};

struct ScanToken { uint64_t epoch; bool valid; };

class Registry {
public:
    // ---- bootstrap (any thread) ----
    void set_stage(Stage s) { std::lock_guard<std::mutex> g(mu_); st_.stage = s; }
    void set_bootstrap_failure(Stage s, Code c, int64_t detail) { std::lock_guard<std::mutex> g(mu_); st_.stage = s; st_.failed = c; st_.failed_detail = detail; }
    void set_domain(Domain d) { std::lock_guard<std::mutex> g(mu_); st_.domain = d; }
    void set_gps(bool ready) { std::lock_guard<std::mutex> g(mu_); st_.gps = ready; }

    // ---- resolution chain (game thread, once) ----
    void step_ok(Step s) { set_step(s, StepState::ok, Code::none, 0xFF); }
    void step_failed(Step s, Code c) { set_step(s, StepState::failed, c, 0xFF); }
    void step_blocked(Step s, Step parent) { set_step(s, StepState::blocked, Code::none, static_cast<uint8_t>(parent)); }
    /** Marks every not-reached step in [from, to] as blocked by `parent` (a helper early return). */
    void block_range(Step from, Step to, Step parent) {
        std::lock_guard<std::mutex> g(mu_);
        for (int i = from; i <= to && i < kStepCount; ++i)
            if (st_.steps[i].state == StepState::not_reached) st_.steps[i] = {StepState::blocked, Code::none, static_cast<uint8_t>(parent)};
    }

    // ---- session events: the ONLY writers of session_epoch ----
    /** Login callback seen. Returns the epoch this login owns; use it for later ready/capture events. */
    uint64_t on_login(int64_t now_ms) {
        std::lock_guard<std::mutex> g(mu_);
        ++st_.session_gen; ++st_.session_epoch;
        st_.identity = Identity::unknown;
        st_.last_login_ms = now_ms;
        st_.stale |= (st_.caps & kPerSessionCaps);   // everything captured before this login must be re-captured
        return st_.session_epoch;
    }
    /** Logout callback seen. Advances the epoch so any in-flight scan cannot commit; marks per-session captures stale. */
    void on_logout() {
        std::lock_guard<std::mutex> g(mu_);
        ++st_.session_epoch;
        st_.identity = Identity::logged_out;
        st_.stale |= (st_.caps & kPerSessionCaps);
    }
    /**
     * The business identity was reset without a logout callback (RpcManager re-constructed).
     * Advances the epoch like a logout so in-flight scans cannot commit, marks per-session
     * captures stale, but reports `unknown` rather than `logged_out` (nothing was logged out).
     */
    void on_identity_reset() {
        std::lock_guard<std::mutex> g(mu_);
        ++st_.session_epoch;
        st_.identity = Identity::unknown;
        st_.stale |= (st_.caps & kPerSessionCaps);
    }
    /** Identity became ready for the login that owned `epoch_seen`. Ignored if a newer event already happened. */
    bool on_identity_ready(uint64_t epoch_seen) {
        std::lock_guard<std::mutex> g(mu_);
        if (epoch_seen != st_.session_epoch) return false;
        st_.identity = Identity::ready;
        return true;
    }
    /** Map callback seen. Attributed to the epoch the hook took at its entry; ignored if the session moved meanwhile. */
    bool on_map(int64_t now_ms, uint64_t epoch_seen) {
        std::lock_guard<std::mutex> g(mu_);
        if (epoch_seen != st_.session_epoch) return false;
        st_.last_map_ms = now_ms;
        return true;
    }

    /** Current epoch, for callbacks that capture something and need to attribute it. */
    uint64_t current_epoch() { std::lock_guard<std::mutex> g(mu_); return st_.session_epoch; }

    // ---- captures ----
    /**
     * A manager/method became available. `epoch_seen` MUST be the epoch the hook read at
     * its entry, before calling the original / reading managed memory, so an object created
     * in an older session is never attributed to a newer one. Per-process bits are simply set.
     * Per-session bits: a capture belonging to the current epoch (and not logged out) becomes
     * live; a capture with an older epoch is recorded as "observed" (stale) only if the bit
     * was not captured before, and never touches a bit that a newer capture already made live
     * (invariant 4, both directions).
     */
    void capture(uint32_t bit, uint64_t epoch_seen) {
        std::lock_guard<std::mutex> g(mu_);
        st_.caps |= (bit & ~kPerSessionCaps);   // per-process part
        bit &= kPerSessionCaps;
        if (!bit) return;
        if (epoch_seen == st_.session_epoch && st_.identity != Identity::logged_out) { st_.caps |= bit; st_.stale &= ~bit; return; }
        const uint32_t fresh = bit & ~st_.caps;   // observed for the first time, but not attributable to this session
        st_.caps |= fresh; st_.stale |= fresh;
    }

    // Publish captured native pointers and their capability under one epoch gate.
    // The callback must contain only pointer/value assignment: no managed calls,
    // blocking work, or re-entry into Registry while its mutex is held.
    template<class Publish>
    bool capture_current(uint32_t bit, uint64_t epoch_seen, Publish &&publish) {
        std::lock_guard<std::mutex> g(mu_);
        if(epoch_seen!=st_.session_epoch || st_.identity==Identity::logged_out)return false;
        publish();
        st_.caps |= bit;
        st_.stale &= ~(bit & kPerSessionCaps);
        return true;
    }

    // ---- scan attribution ----
    ScanToken begin_scan() {
        std::lock_guard<std::mutex> g(mu_);
        return {st_.session_epoch, st_.identity != Identity::logged_out};
    }
    /**
     * Publishes scan_epoch/scan_ms/scan_count atomically iff the token is still valid:
     * same epoch, identity not logged out, and the projection was complete. A legal
     * zero-item projection is publishable; an incomplete one never is. On rejection
     * nothing changes (the previous scan time is not refreshed).
     */
    bool commit_scan(ScanToken t, int64_t now_ms, int32_t count, bool complete) {
        return commit_scan_observed(t, now_ms, count, complete, [](const State &) {});
    }
    /**
     * Test-only variant: `between` runs INSIDE the lock after the check and before the
     * publish, so a host test can prove that no logout can interleave there. `between`
     * must not call back into the registry (same non-recursive mutex) and must not block
     * on another thread that needs this registry. Production code calls commit_scan().
     */
    template <class F>
    bool commit_scan_observed(ScanToken t, int64_t now_ms, int32_t count, bool complete, F &&between) {
        std::lock_guard<std::mutex> g(mu_);
        if (!t.valid || !complete || count < 0) return false;
        if (t.epoch != st_.session_epoch || st_.identity == Identity::logged_out) return false;
        between(static_cast<const State &>(st_));
        st_.scan_epoch = t.epoch; st_.scan_ms = now_ms; st_.scan_count = count;
        return true;
    }

    // ---- publication ----
    /** Whole-state copy under the lock; `seq` counts copies. Formatting and I/O happen outside. */
    State snapshot() {
        std::lock_guard<std::mutex> g(mu_);
        ++st_.seq;
        return st_;
    }

private:
    void set_step(Step s, StepState state, Code c, uint8_t parent) {
        std::lock_guard<std::mutex> g(mu_);
        if (s < kStepCount) st_.steps[s] = {state, c, parent};
    }
    std::mutex mu_;
    State st_;
};

// ----------------------------------------------------------------- names

inline const char *stage_name(Stage s) {
    switch (s) {
        case Stage::boot: return "boot"; case Stage::lib_wait: return "lib_wait"; case Stage::lib_timeout: return "lib_timeout";
        case Stage::symbols_missing: return "symbols_missing"; case Stage::incompatible: return "incompatible"; case Stage::hooks_called: return "hooks_called";
    }
    return "boot";
}
inline const char *code_name(Code c) {
    switch (c) {
        case Code::none: return "-"; case Code::class_missing: return "class_missing"; case Code::field_missing: return "field_missing";
        case Code::entry_mismatch: return "entry_mismatch"; case Code::method_missing: return "method_missing"; case Code::no_entry: return "no_entry";
        case Code::ctor_missing: return "ctor_missing"; case Code::method_unavailable: return "method_unavailable";
        case Code::rpc_entry_mismatch: return "rpc_entry_mismatch"; case Code::proto_layout_mismatch: return "proto_layout_mismatch";
        case Code::symbol: return "symbol"; case Code::il2cpp_size: return "il2cpp_size"; case Code::lib_timeout: return "lib_timeout";
        default: return "-";
    }
}
inline const char *step_name(int i) {
    static const char *const names[kStepCount] = {
        "return.action_manager_class", "return.blocker_capture", "return.complete_task_hook", "return.preparer_ctor_hook",
        "return.prepare_completion_hook", "return.readiness_taskid", "return.task_list_time", "expedition.methods",
        "availability.base", "availability.selected", "availability.gift", "expedition.pick_fastest", "wire_probe", "logout_hook"};
    return i >= 0 && i < kStepCount ? names[i] : "?";
}
inline const char *identity_name(Identity i) { return i == Identity::ready ? "ready" : i == Identity::logged_out ? "logged_out" : "unknown"; }
inline const char *domain_name(Domain d) { return d == Domain::ready ? "ready" : "pending"; }

// ---------------------------------------------------------------- format
//
// One line, TAB separated, 23 columns, trailing "end":
// v1 epochMs pid version code game seq stage failed domain steps caps stale
//    session_gen session_epoch identity last_login_ms last_map_ms scan_epoch scan_ms scan_count gps end
// `failed` is "name" or "name:detail" or "-". `steps` is "name=ok|failed:code|blocked:parent|-" comma-joined.
// Returns the number of bytes written (excluding NUL) or -1 when the buffer could not hold the line;
// on -1 the buffer content is unspecified and must not be published.
inline int format(const State &s, int64_t epoch_ms, int pid, const char *version, int code, const char *game, char *buf, size_t n) {
    if (!buf || n == 0) return -1;
    size_t off = 0;
    auto put = [&](const char *fmt, auto... args) -> bool {
        int w = std::snprintf(buf + off, n - off, fmt, args...);
        if (w < 0 || static_cast<size_t>(w) >= n - off) return false;
        off += static_cast<size_t>(w);
        return true;
    };
    if (!put("v%d\t%lld\t%d\t%s\t%d\t%s\t%llu\t%s\t", kSchemaVersion, static_cast<long long>(epoch_ms), pid, version, code, game,
             static_cast<unsigned long long>(s.seq), stage_name(s.stage))) return -1;
    if (s.failed == Code::none) { if (!put("%s", "-\t")) return -1; }
    else if (s.failed_detail != 0) { if (!put("%s:%lld\t", code_name(s.failed), static_cast<long long>(s.failed_detail))) return -1; }
    else { if (!put("%s\t", code_name(s.failed))) return -1; }
    if (!put("%s\t", domain_name(s.domain))) return -1;
    for (int i = 0; i < kStepCount; ++i) {
        const StepEntry &e = s.steps[i];
        const char *sep = i == 0 ? "" : ",";
        bool ok;
        switch (e.state) {
            case StepState::ok: ok = put("%s%s=ok", sep, step_name(i)); break;
            case StepState::failed: ok = put("%s%s=failed:%s", sep, step_name(i), code_name(e.code)); break;
            case StepState::blocked: ok = put("%s%s=blocked:%s", sep, step_name(i), step_name(e.parent)); break;
            default: ok = put("%s%s=-", sep, step_name(i));
        }
        if (!ok) return -1;
    }
    if (!put("\t%x\t%x\t%llu\t%llu\t%s\t%lld\t%lld\t%llu\t%lld\t%d\t%d\tend\n",
             s.caps, s.stale, static_cast<unsigned long long>(s.session_gen), static_cast<unsigned long long>(s.session_epoch),
             identity_name(s.identity), static_cast<long long>(s.last_login_ms), static_cast<long long>(s.last_map_ms),
             static_cast<unsigned long long>(s.scan_epoch), static_cast<long long>(s.scan_ms), s.scan_count, s.gps ? 1 : 0)) return -1;
    return static_cast<int>(off);
}

// ------------------------------------------------------------- publisher
//
// Single writer. `Sink` abstracts tmp-write + rename so the host test can inject
// failures. On any failure the previous valid file is left untouched; the
// caller must not log per tick.
struct Sink {
    virtual ~Sink() = default;
    virtual bool write_tmp(const char *line, size_t len) = 0;   // create/truncate tmp, write all, fsync-less close; false on any error
    virtual bool rename_tmp() = 0;                              // rename tmp over the final file; false on error
    virtual void discard_tmp() = 0;                             // best-effort cleanup after a failure
};

enum class PublishResult : uint8_t { published = 0, format_truncated, write_failed, rename_failed };

/**
 * A snapshot bound to the wall-clock time taken BEFORE the lock was acquired.
 * The heartbeat may be descheduled or block on the lock between "now" and the
 * copy, and again before the file lands; stamping the line with a time taken
 * after the copy could label an older state as newer than it is. Taking the
 * time first means epochMs is never later than the state: the computed age is an upper bound (never understated).
 */
struct Sampled { State state; int64_t sampled_ms; };
template <class Clock>
inline Sampled sample(Registry &r, Clock now_ms) {
    const int64_t t = now_ms();
    return {r.snapshot(), t};
}

inline PublishResult publish(const State &snap, int64_t epoch_ms, int pid, const char *version, int code, const char *game, Sink &sink, char *buf, size_t n) {
    int len = format(snap, epoch_ms, pid, version, code, game, buf, n);
    if (len < 0) return PublishResult::format_truncated;
    if (!sink.write_tmp(buf, static_cast<size_t>(len))) { sink.discard_tmp(); return PublishResult::write_failed; }
    if (!sink.rename_tmp()) { sink.discard_tmp(); return PublishResult::rename_failed; }
    return PublishResult::published;
}

}  // namespace pcc_cap

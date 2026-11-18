// Deterministic interleaving tests for capability_state.h (N01).
// No sleeps: every cross-thread ordering is forced with a condition-variable
// gate. Runs on the host (x86_64 static Android binary under WSL2, see
// run-host-tests.ps1) and on a device via run-native-tests.ps1.
#include "../cpp/capability_state.h"

#include <atomic>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

using namespace pcc_cap;

namespace {

#define CHECK(cond) do { if (!(cond)) { std::fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); std::abort(); } } while (0)

// A one-shot gate: `wait()` blocks until `open()` was called. No timeouts, no sleeps.
struct Gate {
    std::mutex m; std::condition_variable cv; bool opened = false;
    void open() { { std::lock_guard<std::mutex> g(m); opened = true; } cv.notify_all(); }
    void wait() { std::unique_lock<std::mutex> l(m); cv.wait(l, [&] { return opened; }); }
};

// In-memory sink modelling tmp+rename. Failure injection per call.
struct MemSink : Sink {
    std::string tmp, final_;
    bool fail_write = false, fail_rename = false;
    int writes = 0, renames = 0, discards = 0;
    bool write_tmp(const char *line, size_t len) override { ++writes; if (fail_write) return false; tmp.assign(line, len); return true; }
    bool rename_tmp() override { ++renames; if (fail_rename) return false; final_ = tmp; tmp.clear(); return true; }
    void discard_tmp() override { ++discards; tmp.clear(); }
};

int columns(const std::string &line) { int n = 1; for (char c : line) if (c == '\t') ++n; return n; }
uint64_t login_ready_epoch(Registry &r, int64_t at) { uint64_t e = r.on_login(at); r.on_identity_ready(e); return e; }
std::string column(const std::string &line, int idx) {
    size_t start = 0; int i = 0;
    while (i < idx) { start = line.find('\t', start); CHECK(start != std::string::npos); ++start; ++i; }
    size_t end = line.find('\t', start); if (end == std::string::npos) end = line.size();
    std::string v = line.substr(start, end - start);
    if (!v.empty() && v.back() == '\n') v.pop_back();
    return v;
}

// ----------------------------------------------------------- invariant 1
void test_epoch_advances_and_old_events_ignored() {
    Registry r;
    uint64_t e1 = r.on_login(1000);
    CHECK(e1 == 1);
    CHECK(r.on_identity_ready(e1));
    CHECK(r.snapshot().identity == Identity::ready);
    r.on_logout();                                    // epoch 2
    CHECK(!r.on_identity_ready(e1));                  // late ready from session 1 is ignored
    State s = r.snapshot();
    CHECK(s.session_epoch == 2 && s.identity == Identity::logged_out && s.session_gen == 1);
    uint64_t e2 = r.on_login(2000);                   // epoch 3
    CHECK(e2 == 3);
    CHECK(!r.on_identity_ready(e1));                  // still ignored after re-login
    CHECK(r.snapshot().identity == Identity::unknown);
    CHECK(r.on_identity_ready(e2));
    CHECK(r.snapshot().identity == Identity::ready);
    // identity reset (RpcManager re-constructed) advances the epoch but is not a logout
    ScanToken t = r.snapshot().identity == Identity::ready ? r.begin_scan() : ScanToken{0, false};
    r.on_identity_reset();
    s = r.snapshot();
    CHECK(s.session_epoch == 4 && s.identity == Identity::unknown && s.session_gen == 2);
    CHECK(!r.commit_scan(t, 1, 1, true));             // token from before the reset is dead
    CHECK(!r.on_identity_ready(e2));                  // ready for the pre-reset login is ignored
    std::puts("PASS epoch: login/logout/reset advance, late ready ignored");
}

// ----------------------------------------------------------- invariant 2
void test_scan_commit_rules() {
    Registry r;
    uint64_t e = r.on_login(10); r.on_identity_ready(e);
    ScanToken t = r.begin_scan();
    CHECK(t.valid && t.epoch == 1);
    CHECK(!r.commit_scan(t, 500, 7, /*complete=*/false));   // partial list never publishes
    State s = r.snapshot(); CHECK(s.scan_count == -1 && s.scan_ms == 0);
    CHECK(r.commit_scan(t, 600, 0, true));                   // legal zero-item projection publishes
    s = r.snapshot(); CHECK(s.scan_count == 0 && s.scan_ms == 600 && s.scan_epoch == 1);
    CHECK(!r.commit_scan(t, 700, -1, true));                 // negative count is not a projection
    CHECK(!r.commit_scan({t.epoch, false}, 700, 3, true));   // token issued while logged out
    s = r.snapshot(); CHECK(s.scan_ms == 600);               // rejected commits never refresh scan_ms
    // token taken before first login (epoch 0, identity unknown) is valid: pre-login scan attributes to epoch 0
    Registry r0; ScanToken t0 = r0.begin_scan(); CHECK(t0.valid && t0.epoch == 0);
    std::puts("PASS scan: partial rejected, zero accepted, rejection keeps last scan_ms");
}

// Scan in flight, then: (a) logout only, (b) logout+login, (c) nothing.
void test_scan_vs_session_change(int variant) {
    Registry r;
    uint64_t e = r.on_login(10); r.on_identity_ready(e);
    Gate token_taken, session_changed;
    bool committed = true;
    std::thread scanner([&] {
        ScanToken t = r.begin_scan();
        token_taken.open();
        session_changed.wait();                       // "reading game data" while the other thread acts
        committed = r.commit_scan(t, 900, 5, true);
    });
    token_taken.wait();
    if (variant == 0) r.on_logout();
    if (variant == 1) { r.on_logout(); uint64_t e2 = r.on_login(20); r.on_identity_ready(e2); }
    session_changed.open();
    scanner.join();
    State s = r.snapshot();
    if (variant == 2) { CHECK(committed && s.scan_epoch == 1 && s.scan_count == 5); }
    else { CHECK(!committed && s.scan_count == -1 && s.scan_ms == 0 && s.scan_epoch == 0); }
    if (variant == 1) CHECK(s.identity == Identity::ready && s.session_epoch == 3);
    std::printf("PASS scan vs session change variant %d (%s)\n", variant,
                variant == 0 ? "logout only" : variant == 1 ? "logout then login" : "no change");
}

// Identity source already invalidated in the diagnostic registry, the carrier thread is
// blocked before it reaches the business identity; the scan must not pass on the old state.
void test_identity_changed_carrier_blocked() {
    Registry r;
    uint64_t e = r.on_login(10); r.on_identity_ready(e);
    ScanToken t = r.begin_scan();
    Gate diag_invalidated, release_carrier;
    std::atomic<bool> business_invalidated{false};
    std::thread carrier([&] {
        r.on_logout();                                // documented order: diagnostic first ...
        diag_invalidated.open();
        release_carrier.wait();                       // ... then (blocked here) invalidate_identity()
        business_invalidated = true;
    });
    diag_invalidated.wait();
    CHECK(!business_invalidated.load());
    CHECK(!r.commit_scan(t, 900, 5, true));           // old token rejected although business identity is untouched
    release_carrier.open();
    carrier.join();
    CHECK(r.snapshot().scan_count == -1);
    std::puts("PASS identity changed + carrier blocked: old token cannot commit");
}

// ----------------------------------------------------------- invariant 3
// The check and the publish run under one lock. A logout that arrives after the check
// must wait for the publish; it cannot slip between them. We observe it from inside.
void test_logout_cannot_interleave_check_and_publish() {
    Registry r;
    uint64_t e = r.on_login(10); r.on_identity_ready(e);
    ScanToken t = r.begin_scan();
    Gate checked, logout_started;
    uint64_t epoch_at_check = 0, epoch_at_publish = 0;
    std::thread scanner([&] {
        bool ok = r.commit_scan_observed(t, 900, 5, true, [&](const State &s) {
            epoch_at_check = s.session_epoch;
            checked.open();
            logout_started.wait();                    // logout thread is now trying to take the lock
            epoch_at_publish = s.session_epoch;       // still inside the lock: cannot have moved
        });
        CHECK(ok);                                    // logout could not reject it: it was still waiting for the lock
    });
    checked.wait();
    std::thread logouter([&] {
        logout_started.open();                        // announce, then block on the registry mutex
        r.on_logout();
    });
    scanner.join(); logouter.join();
    CHECK(epoch_at_check == 1 && epoch_at_publish == 1);
    // The commit succeeded (scan_count 5) and the logout landed after it (epoch 2): the only order the lock allows.
    State s = r.snapshot();
    CHECK(s.scan_epoch == 1 && s.scan_count == 5 && s.session_epoch == 2 && s.identity == Identity::logged_out);
    // The reader sees scan_epoch != session_epoch: an "old snapshot taken before invalidation", not a
    // "new commit after invalidation". The second case is impossible, shown by the rejected re-commit:
    CHECK(!r.commit_scan(t, 950, 5, true));
    std::puts("PASS check-to-publish: logout serialised after publish, re-commit after logout rejected");
}

// ----------------------------------------------------------- invariant 4
void test_capture_attribution() {
    Registry r;
    // capture before first login: observed, but the first login marks it stale (must be re-captured)
    r.capture(cap_preparer, r.current_epoch());
    CHECK(r.snapshot().caps == cap_preparer && r.snapshot().stale == 0);
    uint64_t e1 = r.on_login(10); r.on_identity_ready(e1);
    CHECK(r.snapshot().stale == cap_preparer);
    r.capture(cap_preparer, e1);                      // re-captured in session 1
    r.capture(cap_action_manager, e1);
    r.capture(cap_complete_task, e1);                 // per-process bit
    State s = r.snapshot(); CHECK(s.stale == 0 && s.caps == (cap_preparer | cap_action_manager | cap_complete_task));
    r.on_logout();
    s = r.snapshot(); CHECK(s.stale == (cap_preparer | cap_action_manager)); // per-process bit not stale
    uint64_t e2 = r.on_login(20); r.on_identity_ready(e2);
    r.capture(cap_action_manager, e1);                // late capture from session 1 arrives after new login
    s = r.snapshot(); CHECK((s.stale & cap_action_manager) != 0);   // must not clear the new session's stale
    r.capture(cap_action_manager, e2);                // real re-capture in session 2
    s = r.snapshot(); CHECK((s.stale & cap_action_manager) == 0 && (s.stale & cap_preparer) != 0);
    // re-login without manager rebuild: preparer stays stale until captured again
    r.on_logout(); uint64_t e3 = r.on_login(30); r.on_identity_ready(e3);
    s = r.snapshot(); CHECK(s.stale == (cap_preparer | cap_action_manager));
    // capture while logged out attributes to the logged-out epoch: kept stale
    r.on_logout(); r.capture(cap_location_controller, r.current_epoch());
    s = r.snapshot(); CHECK((s.caps & cap_location_controller) && (s.stale & cap_location_controller));
    // per-process bits (planter methods resolved once) never become stale, even when mixed with a session bit in one call
    Registry m; uint64_t em = login_ready_epoch(m, 5);
    m.capture(cap_preparer | cap_planter, em); m.on_logout();
    s = m.snapshot(); CHECK((s.caps & (cap_preparer | cap_planter)) == (cap_preparer | cap_planter) && s.stale == cap_preparer);
    m.capture(cap_preparer | cap_planter, 0);   // old token, mixed: planter stays live (per-process), preparer stays stale
    s = m.snapshot(); CHECK(s.stale == cap_preparer && (s.stale & cap_planter) == 0);
    std::puts("PASS capture attribution: pre-login, late old-session, re-login without rebuild, per-process never stale");
}

void test_old_events_after_new_session_concurrent() {
    // Old session's "ready" and "capture" events race a new logout/login; forced order: new first.
    Registry r;
    uint64_t e1 = r.on_login(10); r.on_identity_ready(e1);
    Gate new_session_done;
    std::thread old_events([&] {
        new_session_done.wait();
        CHECK(!r.on_identity_ready(e1));
        r.capture(cap_location_controller, e1);
    });
    r.on_logout(); uint64_t e2 = r.on_login(20);
    new_session_done.open();
    old_events.join();
    State s = r.snapshot();
    CHECK(s.identity == Identity::unknown && s.session_epoch == 3 && (s.stale & cap_location_controller));
    CHECK(r.on_identity_ready(e2));
    std::puts("PASS old ready/manager events after new logout+login are ignored / kept stale");
}

// ----------------------------------------------------------- R1: hook entry token, both directions
// Mirrors the production hook shape: the epoch is read at hook ENTRY, then the original
// runs (blocked here), then the capture uses the entry token. A logout+login in between
// must leave the captured object attributed to the old session (stale), never live.
void test_hook_entry_token_survives_relogin() {
    Registry r;
    uint64_t e1 = login_ready_epoch(r, 10);
    Gate entered, session_replaced;
    std::thread hook([&] {
        const uint64_t epoch_at_entry = r.current_epoch();   // == production: before original / managed reads
        entered.open();
        session_replaced.wait();                              // "original_...()" runs here, slowly
        r.capture(cap_action_manager, epoch_at_entry);        // object created in session e1
    });
    entered.wait();
    r.on_logout(); uint64_t e2 = login_ready_epoch(r, 20);
    session_replaced.open(); hook.join();
    State s = r.snapshot();
    CHECK(e1 == 1 && e2 == 3 && s.session_epoch == 3);
    CHECK((s.caps & cap_action_manager) && (s.stale & cap_action_manager));   // observed, not attributable to the new session
    // Counter-example of the rejected shape (token read AFTER the original): the same
    // object would be attributed to the new session. This is what R1 forbids.
    Registry wrong; login_ready_epoch(wrong, 10); wrong.on_logout(); login_ready_epoch(wrong, 20);
    wrong.capture(cap_action_manager, wrong.current_epoch());
    CHECK((wrong.snapshot().stale & cap_action_manager) == 0);
    // Map time uses the same rule: an entry token from the old session does not stamp the new one.
    Registry mp; uint64_t em = login_ready_epoch(mp, 10);
    mp.on_logout(); login_ready_epoch(mp, 20);
    CHECK(!mp.on_map(999, em) && mp.snapshot().last_map_ms == 0);
    CHECK(mp.on_map(1000, mp.current_epoch()) && mp.snapshot().last_map_ms == 1000);
    std::puts("PASS hook entry token: relogin during original keeps capture stale; map time attributed");
}

// A late capture with an OLD token must not re-pollute a bit that a NEW-token capture
// already confirmed live (the safe direction of invariant 4).
void test_old_token_never_pollutes_live_bit() {
    Registry r;
    uint64_t e1 = login_ready_epoch(r, 10);
    r.capture(cap_preparer, e1);
    r.on_logout(); uint64_t e2 = login_ready_epoch(r, 20);
    r.capture(cap_preparer, e2);                              // rebuilt and captured in the new session
    CHECK((r.snapshot().stale & cap_preparer) == 0);
    Gate ready, go;
    std::thread late([&] { ready.open(); go.wait(); r.capture(cap_preparer, e1); });   // straggler from session 1
    ready.wait(); go.open(); late.join();
    State s = r.snapshot();
    CHECK((s.stale & cap_preparer) == 0 && (s.caps & cap_preparer));
    // …and a never-seen bit arriving with an old token is recorded as observed-but-stale, not live.
    r.capture(cap_location_controller, e1);
    s = r.snapshot(); CHECK((s.caps & cap_location_controller) && (s.stale & cap_location_controller));
    std::puts("PASS old token never pollutes a live bit; unseen old-token capture is stale");
}

// RpcManager re-construction (on_identity_reset) vs in-flight scan and capture.
void test_reset_vs_scan_and_capture() {
    Registry r;
    uint64_t e1 = login_ready_epoch(r, 10);
    r.capture(cap_preparer, e1);
    Gate token_taken, reset_done;
    bool committed = true;
    std::thread scanner([&] {
        ScanToken t = r.begin_scan(); token_taken.open(); reset_done.wait();
        committed = r.commit_scan(t, 900, 5, true);
        r.capture(cap_action_manager, t.epoch);               // capture with the pre-reset token
    });
    token_taken.wait(); r.on_identity_reset(); reset_done.open(); scanner.join();
    State s = r.snapshot();
    CHECK(!committed && s.scan_count == -1);
    CHECK(s.identity == Identity::unknown && s.session_epoch == 2);
    CHECK((s.stale & cap_preparer) && (s.stale & cap_action_manager));   // reset invalidates; unknown ≠ valid again
    CHECK(!r.on_identity_ready(e1));                                     // stale ready for the pre-reset login ignored
    ScanToken t2 = r.begin_scan(); CHECK(t2.valid && t2.epoch == 2);    // a new scan may start (identity unknown), attributed to epoch 2
    CHECK(r.commit_scan(t2, 950, 1, true) && r.snapshot().scan_epoch == 2);
    std::puts("PASS reset: in-flight scan rejected, pre-reset captures stale, ready ignored");
}

// ----------------------------------------------------------- invariant 5 + publisher window
void test_writer_snapshot_then_logout_then_failures() {
    Registry r; MemSink sink; char buf[kLineCapacity];
    uint64_t e = r.on_login(10); r.on_identity_ready(e);
    CHECK(r.commit_scan(r.begin_scan(), 100, 3, true));
    Gate snap_taken, logout_done;
    State snapA;
    std::thread writer([&] {
        snapA = r.snapshot();                         // heartbeat copies E ...
        snap_taken.open();
        logout_done.wait();                           // ... logout lands before the rename ...
        CHECK(publish(snapA, 1000, 4242, "1.5.23", 91, "156.1", sink, buf, sizeof buf) == PublishResult::published);
    });
    snap_taken.wait(); r.on_logout(); logout_done.open(); writer.join();
    // Disk-snapshot window: the file legitimately shows the pre-logout state until the next successful publish (no fixed bound; freshness decides).
    std::string fileA = sink.final_;
    CHECK(column(fileA, 15) == "ready" && column(fileA, 14) == "1" && column(fileA, 20) == "3");
    // Next round, write fails: old valid file is kept, nothing half-written, tmp discarded.
    State snapB = r.snapshot(); CHECK(snapB.identity == Identity::logged_out);
    sink.fail_write = true;
    CHECK(publish(snapB, 2000, 4242, "1.5.23", 91, "156.1", sink, buf, sizeof buf) == PublishResult::write_failed);
    CHECK(sink.final_ == fileA && sink.tmp.empty() && sink.discards == 1);
    sink.fail_write = false; sink.fail_rename = true;
    CHECK(publish(snapB, 2000, 4242, "1.5.23", 91, "156.1", sink, buf, sizeof buf) == PublishResult::rename_failed);
    CHECK(sink.final_ == fileA && sink.tmp.empty() && sink.discards == 2);
    // Next successful round publishes the logged-out state; seq strictly increased.
    sink.fail_rename = false;
    State snapC = r.snapshot();
    CHECK(publish(snapC, 3000, 4242, "1.5.23", 91, "156.1", sink, buf, sizeof buf) == PublishResult::published);
    CHECK(column(sink.final_, 15) == "logged_out" && column(sink.final_, 14) == "2" && column(sink.final_, 18) == "1");
    CHECK(snapC.seq > snapB.seq && snapB.seq > snapA.seq);
    // Truncation: a buffer too small must not publish and must keep the previous file.
    char tiny[64];
    CHECK(publish(snapC, 3000, 4242, "1.5.23", 91, "156.1", sink, tiny, sizeof tiny) == PublishResult::format_truncated);
    CHECK(column(sink.final_, 1) == "3000");
    std::puts("PASS writer: snapshot-then-logout window, write/rename failure keep old file, truncation not published");
}

// R7: the published time is taken before the snapshot; a heartbeat that is descheduled
// between copy and publish (or between now() and the lock) cannot stamp an old state as newer.
void test_sampled_time_precedes_snapshot_and_delayed_publish() {
    Registry r; MemSink sink; char buf[kLineCapacity];
    uint64_t e = login_ready_epoch(r, 10); (void)e;
    int64_t clock = 1000;
    auto now = [&] { clock += 100; return clock; };            // every call advances: 1100 for the sample …
    Gate sampled, logout_done;
    Sampled snap{};
    std::thread heartbeat([&] {
        snap = sample(r, now);                                  // sampled_ms = 1100, state copied after
        sampled.open();
        logout_done.wait();                                     // descheduled here; the world moves on
        clock = 9000;                                           // a late now() would say 9100
        CHECK(publish(snap.state, snap.sampled_ms, 4242, "1.5.23", 91, "156.1", sink, buf, sizeof buf) == PublishResult::published);
    });
    sampled.wait(); r.on_logout(); logout_done.open(); heartbeat.join();
    CHECK(snap.sampled_ms == 1100);
    CHECK(column(sink.final_, 1) == "1100" && column(sink.final_, 15) == "ready");   // old state carries its old time, not 9100
    // The next round samples again: a strictly later time with the logged-out state.
    Sampled next = sample(r, now);
    CHECK(next.sampled_ms > snap.sampled_ms && next.state.identity == Identity::logged_out && next.state.seq > snap.state.seq);
    std::puts("PASS sampled time precedes snapshot; delayed publish keeps the pre-snapshot time");
}

void test_format_shape_and_capacity() {
    Registry r; char buf[kLineCapacity];
    // Longest legal line: every step failed with the longest code, all caps + stale, big numbers.
    r.set_bootstrap_failure(Stage::incompatible, Code::il2cpp_size, 9223372036854775807LL);
    r.set_domain(Domain::ready);
    for (int i = 0; i < kStepCount; ++i) r.step_failed(static_cast<Step>(i), Code::proto_layout_mismatch);
    for (int i = 0; i < kCapCount; ++i) r.capture(1u << i, 0);
    r.set_gps(true);
    State s = r.snapshot();
    s.seq = ~0ull; s.session_gen = ~0ull; s.session_epoch = ~0ull; s.last_login_ms = s.last_map_ms = s.scan_ms = 9223372036854775807LL;
    s.scan_epoch = ~0ull; s.scan_count = 2147483647; s.stale = kAllCaps;
    int len = format(s, 9223372036854775807LL, 2147483647, "255.255.255", 2147483647, "255.255", buf, sizeof buf);
    CHECK(len > 0);
    std::string line(buf, len);
    CHECK(columns(line) == 23 && line.back() == '\n' && column(line, 22) == "end" && column(line, 0) == "v1");
    std::printf("longest legal line = %d bytes (capacity %zu)\n", len, kLineCapacity);
    CHECK(static_cast<size_t>(len) < kLineCapacity / 4);
    // blocked steps render their parent; block_range never overrides ok/failed
    Registry b; b.step_ok(return_action_manager_class); b.step_failed(return_preparer_ctor_hook, Code::ctor_missing);
    b.block_range(return_preparer_ctor_hook, expedition_pick_fastest, return_preparer_ctor_hook);
    State sb = b.snapshot();
    CHECK(sb.steps[return_preparer_ctor_hook].state == StepState::failed);
    CHECK(sb.steps[return_prepare_completion_hook].state == StepState::blocked && sb.steps[wire_probe].state == StepState::not_reached);
    len = format(sb, 1, 2, "1.5.23", 91, "156.1", buf, sizeof buf); CHECK(len > 0);
    std::string steps = column(std::string(buf, len), 10);
    CHECK(steps.find("return.action_manager_class=ok") == 0);
    CHECK(steps.find("return.preparer_ctor_hook=failed:ctor_missing") != std::string::npos);
    CHECK(steps.find("return.prepare_completion_hook=blocked:return.preparer_ctor_hook") != std::string::npos);
    CHECK(steps.find("wire_probe=-") != std::string::npos);
    // default state renders "-" failed, pending domain, all steps "-", caps 0
    Registry d; len = format(d.snapshot(), 1, 2, "1.5.23", 91, "156.1", buf, sizeof buf); CHECK(len > 0);
    std::string dl(buf, len);
    CHECK(column(dl, 7) == "boot" && column(dl, 8) == "-" && column(dl, 9) == "pending" && column(dl, 11) == "0" && column(dl, 20) == "-1");
    std::puts("PASS format: 23 columns, longest legal line fits, blocked/failed/not-reached rendering");
}

// ----------------------------------------------------------- multi-writer consistency
void test_multi_writer_snapshot_consistency() {
    Registry r;
    constexpr int kWriters = 4, kRounds = 2000;
    Gate start, first_snapshot, first_write, checked_first_write;
    std::atomic<bool> done{false};
    std::vector<std::thread> ws;
    for (int w = 0; w < kWriters; ++w) ws.emplace_back([&, w] {
        start.wait();
        first_snapshot.wait();
        for (int i = 0; i < kRounds; ++i) {
            uint64_t e = r.on_login(i);
            r.on_identity_ready(e);
            r.capture(cap_preparer | cap_planter | cap_location_controller, e);
            ScanToken t = r.begin_scan();
            r.commit_scan(t, i, w, true);
            r.step_ok(static_cast<Step>(i % kStepCount));
            if (i % 3 == 0) r.on_logout();
            // Keep one writer alive until the reader has inspected a mutated
            // snapshot. Thread scheduling alone must not make this vacuous.
            if (w == 0 && i == 0) { first_write.open(); checked_first_write.wait(); }
        }
    });
    std::thread reader([&] {
        start.wait();
        uint64_t last_seq = 0; long checked = 0;
        auto check = [&] {
            State s = r.snapshot();
            CHECK(s.seq > last_seq); last_seq = s.seq;                    // seq strictly monotonic
            CHECK(s.scan_epoch <= s.session_epoch);
            CHECK(s.session_gen <= s.session_epoch);
            CHECK(s.scan_count >= -1 && s.scan_count < kWriters);
            if (s.identity == Identity::logged_out) CHECK((s.caps & kPerSessionCaps & ~s.stale) == 0); // logged out => every per-session cap stale
            ++checked;
        };
        check();
        first_snapshot.open();
        first_write.wait();
        check();
        checked_first_write.open();
        while (!done.load()) check();
        std::printf("reader checked %ld whole-state snapshots\n", checked);
        CHECK(checked >= 2);
    });
    start.open();
    for (auto &t : ws) t.join();
    done = true; reader.join();
    State s = r.snapshot();
    CHECK(s.session_gen == static_cast<uint64_t>(kWriters) * kRounds);
    std::puts("PASS multi-writer: whole-state snapshot invariants hold under contention");
}

void test_stale_constructor_cannot_publish_pointer() {
    Registry r;
    auto old = r.on_login(1);
    r.on_identity_ready(old);
    int old_manager=1, new_manager=2; int *manager=nullptr;
    Gate old_entered, new_published;
    std::thread old_ctor([&] {
        const auto at_entry=r.current_epoch(); old_entered.open(); new_published.wait();
        CHECK(!r.capture_current(cap_preparer, at_entry, [&]{manager=&old_manager;}));
    });
    old_entered.wait(); r.on_logout(); auto fresh=r.on_login(2);r.on_identity_ready(fresh);
    CHECK(r.capture_current(cap_preparer, fresh, [&]{manager=&new_manager;}));
    new_published.open(); old_ctor.join();
    CHECK(manager==&new_manager);
    CHECK(!(r.snapshot().stale & cap_preparer));
    r.on_logout();
    CHECK(!r.capture_current(cap_preparer, fresh, [&]{manager=&old_manager;}));
    CHECK(manager==&new_manager);
    std::puts("PASS stale constructor cannot replace current inventory pointer");
}

}  // namespace

int main() {
    test_stale_constructor_cannot_publish_pointer();
    test_epoch_advances_and_old_events_ignored();
    test_scan_commit_rules();
    test_scan_vs_session_change(0);
    test_scan_vs_session_change(1);
    test_scan_vs_session_change(2);
    test_identity_changed_carrier_blocked();
    test_logout_cannot_interleave_check_and_publish();
    test_capture_attribution();
    test_old_events_after_new_session_concurrent();
    test_hook_entry_token_survives_relogin();
    test_old_token_never_pollutes_live_bit();
    test_reset_vs_scan_and_capture();
    test_writer_snapshot_then_logout_then_failures();
    test_sampled_time_precedes_snapshot_and_delayed_publish();
    test_format_shape_and_capacity();
    test_multi_writer_snapshot_consistency();
    std::puts("PASS capability_state_test");
    return 0;
}

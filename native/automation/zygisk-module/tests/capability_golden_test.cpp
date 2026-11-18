// Golden producer for core_capabilities.tsv (N02). Drives the real registry and
// formatter through fixed scenarios and prints "<name>\t<line>" per scenario.
// run-host-tests.ps1 stores/compares the lines under
// android-app/tests/fixtures/core_capabilities/<name>.tsv so the Java parser is
// tested against native output, not hand-written copies. Also self-checks the
// line shape so it works as a plain test.
#include "../cpp/capability_state.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

using namespace pcc_cap;

namespace {

constexpr int64_t kNow = 1800000000000LL;   // matches the Java fixture clock
constexpr int kPid = 4242;

void all_steps_ok(Registry &r) { for (int i = 0; i < kStepCount; ++i) r.step_ok(static_cast<Step>(i)); }
void process_caps(Registry &r, bool gift = true) {
    r.capture(cap_feeding_probe, 0); r.capture(cap_nectar_claim, 0); r.capture(cap_complete_task, 0);
    r.capture(cap_expedition_start, 0); r.capture(cap_availability_selected, 0); if (gift) r.capture(cap_availability_gift, 0);
    r.capture(cap_pick_fastest, 0);
}
uint64_t login_ready(Registry &r, int64_t at) { uint64_t e = r.on_login(at); r.on_identity_ready(e); return e; }
void session_caps(Registry &r, uint64_t e) {
    r.capture(cap_preparer, e); r.capture(cap_action_manager, e); r.capture(cap_planter, 0);
    r.capture(cap_planting_controller, e); r.capture(cap_location_controller, e);
}
void healthy_base(Registry &r) {
    r.set_stage(Stage::hooks_called); r.set_domain(Domain::ready); all_steps_ok(r); process_caps(r);
    uint64_t e = login_ready(r, kNow - 60000); session_caps(r, e); r.on_map(kNow - 30000, r.current_epoch());
    r.set_gps(true);
}

using Scenario = std::pair<const char *, void (*)(Registry &)>;

const std::vector<Scenario> &scenarios() {
    static const std::vector<Scenario> list = {
        {"healthy", [](Registry &r) { healthy_base(r); r.commit_scan(r.begin_scan(), kNow - 5000, 7, true); }},
        {"boot_lib_wait", [](Registry &r) { r.set_stage(Stage::lib_wait); }},
        {"lib_timeout", [](Registry &r) { r.set_bootstrap_failure(Stage::lib_timeout, Code::lib_timeout, 0); }},
        {"symbols_missing", [](Registry &r) { r.set_bootstrap_failure(Stage::symbols_missing, Code::symbol, 0); }},
        {"incompatible", [](Registry &r) { r.set_bootstrap_failure(Stage::incompatible, Code::il2cpp_size, 123456789); }},
        {"domain_pending", [](Registry &r) { r.set_stage(Stage::hooks_called); process_caps(r); r.on_login(kNow - 1000); r.set_domain(Domain::pending); }},
        {"return_helper_blocked", [](Registry &r) {
            r.set_stage(Stage::hooks_called); r.set_domain(Domain::ready);
            r.step_ok(return_action_manager_class); r.step_ok(return_blocker_capture); r.step_ok(return_complete_task_hook);
            r.step_failed(return_preparer_ctor_hook, Code::ctor_missing);
            r.block_range(return_preparer_ctor_hook, expedition_pick_fastest, return_preparer_ctor_hook);
            r.step_ok(wire_probe); r.step_ok(logout_hook);
            r.capture(cap_feeding_probe, 0); r.capture(cap_nectar_claim, 0);
            uint64_t e = login_ready(r, kNow - 60000); r.capture(cap_action_manager, e); r.on_map(kNow - 30000, r.current_epoch());
            r.set_gps(true);   // GPS hook installs once the domain is ready, independent of the return helper
        }},
        {"gift_only_missing", [](Registry &r) {   // Allows() unresolved: step failed AND the bit never captured
            r.set_stage(Stage::hooks_called); r.set_domain(Domain::ready); all_steps_ok(r); r.step_failed(availability_gift, Code::method_unavailable);
            process_caps(r, false);
            uint64_t e = login_ready(r, kNow - 60000); session_caps(r, e); r.on_map(kNow - 30000, r.current_epoch()); r.set_gps(true);
            r.commit_scan(r.begin_scan(), kNow - 5000, 3, true);
        }},
        {"wire_probe_failed_only", [](Registry &r) {
            healthy_base(r); r.step_failed(wire_probe, Code::proto_layout_mismatch);
            r.commit_scan(r.begin_scan(), kNow - 5000, 3, true);
        }},
        {"logged_out", [](Registry &r) { healthy_base(r); r.commit_scan(r.begin_scan(), kNow - 5000, 7, true); r.on_logout(); }},
        {"relogin_not_recaptured", [](Registry &r) { healthy_base(r); r.on_logout(); login_ready(r, kNow - 10000); r.on_map(kNow - 8000, r.current_epoch()); }},
        {"relogin_recaptured", [](Registry &r) {
            healthy_base(r); r.on_logout(); uint64_t e = login_ready(r, kNow - 10000); session_caps(r, e); r.on_map(kNow - 8000, r.current_epoch());
            r.commit_scan(r.begin_scan(), kNow - 2000, 4, true);
        }},
        {"scan_stale_epoch", [](Registry &r) {
            healthy_base(r); r.commit_scan(r.begin_scan(), kNow - 20000, 7, true);
            r.on_logout(); uint64_t e = login_ready(r, kNow - 10000); session_caps(r, e);
        }},
        {"scan_zero", [](Registry &r) { healthy_base(r); r.commit_scan(r.begin_scan(), kNow - 5000, 0, true); }},
        {"scan_partial_never_published", [](Registry &r) { healthy_base(r); r.commit_scan(r.begin_scan(), kNow - 5000, 9, false); }},
        {"identity_unknown", [](Registry &r) {
            r.set_stage(Stage::hooks_called); r.set_domain(Domain::ready); all_steps_ok(r); process_caps(r);
            uint64_t e = r.on_login(kNow - 60000); session_caps(r, e); r.set_gps(true);
        }},
        {"no_map_yet", [](Registry &r) {
            r.set_stage(Stage::hooks_called); r.set_domain(Domain::ready); all_steps_ok(r); process_caps(r);
            login_ready(r, kNow - 60000); r.set_gps(true);
        }},
        {"gps_off", [](Registry &r) { healthy_base(r); r.set_gps(false); r.commit_scan(r.begin_scan(), kNow - 5000, 7, true); }},
    };
    return list;
}

int columns(const std::string &line) { int n = 1; for (char c : line) if (c == '\t') ++n; return n; }

}  // namespace

int main() {
    char buf[kLineCapacity];
    for (const auto &sc : scenarios()) {
        Registry r;
        sc.second(r);
        State s = r.snapshot();
        s.seq = 1;   // deterministic golden output; the live counter depends on heartbeat count
        int len = format(s, kNow, kPid, "1.5.23", 91, "157.0", buf, sizeof buf);
        if (len <= 0) { std::fprintf(stderr, "FAIL format %s\n", sc.first); return 1; }
        std::string line(buf, static_cast<size_t>(len));
        if (columns(line) != 23 || line.back() != '\n' || line.compare(0, 3, "v1\t") != 0) { std::fprintf(stderr, "FAIL shape %s\n", sc.first); return 1; }
        line.pop_back();
        std::printf("GOLDEN\t%s\t%s\n", sc.first, line.c_str());
    }
    std::printf("PASS capability_golden_test (%zu scenarios)\n", scenarios().size());
    return 0;
}

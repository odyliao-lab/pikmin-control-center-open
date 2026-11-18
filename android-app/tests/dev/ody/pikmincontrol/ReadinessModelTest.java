package dev.ody.pikmincontrol;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.ody.pikmincontrol.ReadinessModel.FeatureReadiness;
import dev.ody.pikmincontrol.ReadinessModel.Readiness;
import dev.ody.pikmincontrol.ReadinessModel.Result;
import dev.ody.pikmincontrol.ReadinessModel.Setting;
import dev.ody.pikmincontrol.ReadinessModel.Work;
import dev.ody.pikmincontrol.ReadinessSample.Read;

/**
 * Work-order test cases T01–T11 against the pure readiness model. No root, no Android, no side effects.
 * Features whose only evidence is a PID-less file (scan, nearby, batch, farm point/route) can never be READY
 * with the current core; the healthy baseline asserts that explicitly.
 */
public final class ReadinessModelTest {
    static final long NOW = 1_800_000_000_000L;
    static final String PID = "4242";
    static final ReadinessSample.AppFacts APP = ReadinessSample.AppFacts.simple("1.5.22", 90, true, true, "");

    /** A fully healthy round on the map with one candidate, one returnable task, two growing slots. */
    static Map<String, Read> healthy() {
        Map<String, Read> m = new LinkedHashMap<>();
        m.put(ReadinessSample.PID_BEFORE, Read.ok(PID + "\n"));
        m.put(ReadinessSample.MODULE, Read.ok("version=1.5.22\nversionCode=90\ndisabled=no\nstaged=no\n"));
        m.put(ReadinessSample.COMPATIBILITY, Read.ok("v157.0\tcompatible\t257043464\t257043464\n"));
        m.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t1.5.22\t90\t157.0\n"));
        m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t1.5.22\t90\t1\n"));
        m.put(ReadinessSample.CORE_CAPABILITIES, Read.missing());   // old core: the pre-N03 judgement applies (see ReadinessCapabilitiesTest for core ≥ 1.5.23)
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.ok((NOW - 3000) + "\tobserved\t1\tno-dispatch\n"));
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\ntaskA\t1\t3\t" + (NOW - 5000) + "\t1\tfruit x1 honey=-\nend\t1\n"));
        m.put(ReadinessSample.RETURN_STATUS, Read.ok((NOW - 60000) + "\tbatch-confirmed\t0\t2\t0\tkeep\n"));
        m.put(ReadinessSample.PLANTER_STATUS, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\tidle\tauto\n"));
        m.put(ReadinessSample.PLANTER_INVENTORY, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\t880\t900\nsteps\t14\nslot\t0\t0\t1\tseedA\t2333\t5000\tgrowing\tred\nslot\t1\t0\t1\tseedB\t1094\t5000\tgrowing\tblue\nseed\tx\nend\t1\n"));
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 2000) + "\t" + PID + "\t-\twaiting-eligible-squad\nend\n"));
        m.put(ReadinessSample.GPS_COPY_STATUS, Read.ok((NOW - 90000) + "\tready\t0.0000000\t0.0000000\n"));
        m.put(ReadinessSample.GPS_COPY_MODE, Read.missing());
        m.put(ReadinessSample.ROUTE_STATUS, Read.ok("v1\t" + (NOW - 500) + "\t" + PID + "\t-\toff\t1\t0\t300000\n"));
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 1000) + "\tobserve\t0\t0\tobserve\t-\n"));
        m.put(ReadinessSample.SESSION, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t" + hex64() + "\tready\tidle\tend\n"));
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.ok("armed\n"));
        m.put(ReadinessSample.CTRL_RETURN_MODE, Read.ok("all\n"));
        m.put(ReadinessSample.CTRL_PLANTER_MODE, Read.ok("auto\n"));
        m.put(ReadinessSample.CTRL_FEEDING_CONFIG, Read.ok("v2\tabc\t1\t4\t20\t100\t1\t1\t3\n"));
        m.put(ReadinessSample.CTRL_FEEDING_PROBE, Read.missing());
        m.put(ReadinessSample.CTRL_NEARBY_KINDS, Read.ok("fruit seed\n"));
        m.put(ReadinessSample.PID_AFTER, Read.ok(PID + "\n"));
        return m;
    }
    static String hex64() { StringBuilder b = new StringBuilder(); for (int i = 0; i < 64; i++) b.append('a'); return b.toString(); }
    static ReadinessSample sample(Map<String, Read> m) { return new ReadinessSample(NOW, true, false, m, APP); }
    static ReadinessSample sample(Map<String, Read> m, ReadinessSample.AppFacts app) { return new ReadinessSample(NOW, true, false, m, app); }
    static Result eval(Map<String, Read> m) { return ReadinessModel.evaluate(sample(m)); }
    static FeatureReadiness f(Result r, String id) { for (FeatureReadiness x : r.features) if (x.id.equals(id)) return x; throw new AssertionError("missing " + id); }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static void all(Result r, Readiness expected, String what) { for (FeatureReadiness x : r.features) check(x.readiness == expected, what + ": " + x.id + " was " + x.readiness + " (" + x.reason + ")"); }
    static void none(Result r, Readiness forbidden, String what) { for (FeatureReadiness x : r.features) check(x.readiness != forbidden, what + ": " + x.id + " was " + x.readiness); }
    static final String[] PID_BOUND = {"return", "planter", "feeding", "gps_copy"};
    static final String[] PIDLESS = {"scan", "nearby", "batch", "farm_point", "farm_route"};

    public static void main(String[] args) {
        healthyBaseline();
        t01GameNotRunningOrPidReadFailed();
        t02CoreLoadedMetadataNotReady();
        t03OldPidChangedPidFutureStale();
        t04LegalEmptyScanAndNoRewards();
        t05GrowingSlots();
        t06LocationOnlyAffectsLocationFeatures();
        t07MissingCapabilityAndSchema();
        t08MissingBrokenTruncatedDeniedTimeout();
        t09UserSettingsSeparateFromReadiness();
        t10InstalledNotRebootedOrMismatch();
        t11PendingWorkStaysReadOnly();
        System.out.println("PASS ReadinessModel: T01-T11 fixtures, 9 features; PID-less sources never reach READY");
    }

    static void healthyBaseline() {
        Result r = eval(healthy());
        check(r.core.coreLoaded && r.core.gpsLoaded && r.core.probeFresh, "healthy core facts");
        for (String id : PID_BOUND) check(f(r, id).readiness == Readiness.READY, id + " ready: " + f(r, id).reason);
        for (String id : PIDLESS) {
            check(f(r, id).readiness == Readiness.UNKNOWN && f(r, id).codes.contains("capability_unconfirmed_no_pid"), id + " must stay unconfirmed: " + f(r, id).reason);
            check(f(r, id).next.contains("B04"), id + " points at the native gap");
        }
        check(f(r, "scan").work == Work.HAS_ITEMS, "scan has items");
        check(f(r, "return").work == Work.HAS_ITEMS && f(r, "return").reason.contains("1 筆已返程"), "return counts ready rows");
        check(f(r, "planter").work == Work.GROWING, "planter growing");
        check(f(r, "feeding").work == Work.NO_MATCH, "feeding waiting squad is no-match not failure");
        check(f(r, "gps_copy").reason.contains("不代表已實測"), "gps copy ready is not click proof");
        check(f(r, "nearby").setting == Setting.ON && f(r, "return").setting == Setting.ON && f(r, "planter").setting == Setting.ON, "settings read");
        check(r.oneLine().equals("功能就緒：4 項就緒・0 項等待・0 項受阻・5 項待核對"), "one line: " + r.oneLine());
        check(!r.warning(), "no warning colour");
    }

    static void t01GameNotRunningOrPidReadFailed() {
        // An OK read with empty text means a producer positively confirmed "no process" (MARK_NOPROC). The current adapter never
        // emits it (pidof alone cannot prove absence); the model path is kept for a future producer with verified enumeration.
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.PID_BEFORE, Read.ok("")); m.put(ReadinessSample.PID_AFTER, Read.ok(""));
        Result r = eval(m);
        all(r, Readiness.WAITING_GAME, "game not running");
        for (FeatureReadiness x : r.features) check(!x.next.contains("安裝") && !x.codes.contains("core_not_installed"), "must not suggest reinstall: " + x.id);
        check(f(r, "nearby").setting == Setting.ON, "setting kept while game closed");

        m = healthy();
        m.put(ReadinessSample.PID_BEFORE, Read.error()); m.put(ReadinessSample.PID_AFTER, Read.error());
        r = eval(m);
        all(r, Readiness.UNKNOWN, "pid read failed");
        check(f(r, "scan").codes.contains("pid_read_failed"), "pid_read_failed code");
        check(r.coreSummary.contains("無法確認") && r.coreSummary.contains("不代表遊戲未開"), "summary says unconfirmed, not closed");
    }

    static void t02CoreLoadedMetadataNotReady() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t1.5.22\t90\t0\n"));
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.missing());
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.missing());
        m.put(ReadinessSample.PLANTER_STATUS, Read.missing()); m.put(ReadinessSample.PLANTER_INVENTORY, Read.missing());
        m.put(ReadinessSample.FEEDING_STATE, Read.missing());
        m.put(ReadinessSample.PLANTING_CONTROL, Read.missing());
        Result r = eval(m);
        check(r.core.coreLoaded, "core loaded");
        none(r, Readiness.READY, "metadata not ready");
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("core_lacks_init_diagnostic"), "scan waits init and flags native gap");
        check(f(r, "gps_copy").readiness == Readiness.WAITING_INIT, "gps waits hook");
        check(f(r, "return").readiness == Readiness.WAITING_INIT, "return waits init");
        check(f(r, "feeding").readiness == Readiness.WAITING_INIT && !f(r, "feeding").next.contains("開啟餵食"), "feeding waits without asking to enable feeding");
    }

    static void t03OldPidChangedPidFutureStale() {
        Map<String, Read> m = healthy();
        String old = "1111";
        for (String id : new String[]{ReadinessSample.CORE_RUNTIME, ReadinessSample.UNIFIED_RUNTIME, ReadinessSample.NEARBY_GATE,
                ReadinessSample.RETURN_CANDIDATES, ReadinessSample.PLANTER_STATUS, ReadinessSample.PLANTER_INVENTORY,
                ReadinessSample.FEEDING_STATE, ReadinessSample.ROUTE_STATUS, ReadinessSample.SESSION})
            m.put(id, Read.ok(m.get(id).text.replace("\t" + PID + "\t", "\t" + old + "\t").replace("\t" + PID + "\n", "\t" + old + "\n")));
        Result r = eval(m);
        none(r, Readiness.READY, "old pid");
        check(f(r, "scan").codes.contains("core_runtime_stale"), "old pid runtime is stale, not loaded");

        m = healthy(); m.put(ReadinessSample.PID_AFTER, Read.ok("4343\n"));
        r = eval(m);
        all(r, Readiness.UNKNOWN, "pid changed");
        check(f(r, "planter").codes.contains("pid_changed"), "pid_changed code");
        check(r.coreSummary.contains("作廢"), "summary discards the round");

        m = healthy(); m.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW + 60_000) + "\t" + PID + "\t1.5.22\t90\t157.0\n"));
        r = eval(m);
        none(r, Readiness.READY, "future core timestamp");
        check(f(r, "scan").codes.contains("core_runtime_stale"), "future timestamp treated as stale");

        m = healthy();
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.ok((NOW - 120_000) + "\tobserved\t1\tno-dispatch\n"));
        m.put(ReadinessSample.PLANTER_STATUS, Read.ok("v1\t" + (NOW - 120_000) + "\t" + PID + "\tidle\tauto\n"));
        r = eval(m);
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("scan_heartbeat_stale"), "stale heartbeat");
        check(f(r, "planter").readiness == Readiness.WAITING_INIT && f(r, "planter").codes.contains("planter_stale"), "stale planter");
        check(f(r, "gps_copy").readiness == Readiness.READY, "gps unaffected by scan staleness");
    }

    static void t04LegalEmptyScanAndNoRewards() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.ok((NOW - 3000) + "\tarmed\t0\tno-dispatch\n"));
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\nend\t0\n"));
        Result r = eval(m);
        check(f(r, "scan").work == Work.NONE_PENDING && f(r, "scan").reason.contains("合法零筆"), "legal empty scan is not a failure");
        check(f(r, "scan").readiness == Readiness.UNKNOWN, "…but still not READY without PID");
        check(f(r, "return").readiness == Readiness.READY && f(r, "return").work == Work.NONE_PENDING && f(r, "return").reason.contains("不需送出"), "no rewards pending, no RPC");
        check(f(r, "nearby").work == Work.NONE_PENDING && f(r, "batch").work == Work.NONE_PENDING, "nothing to do propagated");
    }

    static void t05GrowingSlots() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.PLANTER_INVENTORY, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\t880\t900\nsteps\t14\nslot\t0\t0\t1\tseedA\t2333\t5000\tgrowing\tred\nslot\t1\t0\t1\tseedB\t1094\t5000\tgrowing\tblue\nend\t0\n"));
        Result r = eval(m);
        FeatureReadiness p = f(r, "planter");
        check(p.readiness == Readiness.READY && p.work == Work.GROWING, "two growing slots are growing, not failure");
        check(p.reason.contains("不代表核心故障"), "explains step stall");
        check(f(eval(m), "planter").work == Work.GROWING, "unchanged steps still growing");
        m.put(ReadinessSample.PLANTER_INVENTORY, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\t880\t900\nsteps\t14\nslot\t0\t0\t1\tseedA\t5000\t5000\tready\tred\nslot\t1\t0\t1\t\t0\t0\tempty\t\nend\t0\n"));
        check(f(eval(m), "planter").work == Work.HAS_ITEMS, "ready slot reported");
    }

    static void t06LocationOnlyAffectsLocationFeatures() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\twaiting-game-location\t7\t0\t0\t0\t0\t0\t0\t300000\n"));
        Result r = eval(m);
        check(f(r, "nearby").readiness == Readiness.WAITING_LOCATION, "nearby waits location");
        check(f(r, "farm_point").readiness == Readiness.WAITING_LOCATION && f(r, "farm_route").readiness == Readiness.WAITING_LOCATION, "farm modes wait location");
        for (String id : PID_BOUND) check(f(r, id).readiness == Readiness.READY, id + " must not follow the location gate");
        m.put(ReadinessSample.NEARBY_GATE, Read.missing());
        r = eval(m);
        check(f(r, "farm_point").codes.contains("location_report_missing"), "no location report");
        check(f(r, "nearby").readiness == Readiness.CORE_NO_DIAGNOSTIC, "nearby says core lacks gate report");
        check(f(r, "return").readiness == Readiness.READY, "return still ready");
        m = healthy();
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 40_000) + "\t300000\n"));
        r = eval(m);
        check(f(r, "farm_route").codes.contains("raw_location_stale"), "raw stale flagged for farm");
    }

    static void t07MissingCapabilityAndSchema() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.ROUTE_STATUS, Read.missing());
        Result r = eval(m);
        check(f(r, "farm_route").readiness == Readiness.CORE_NO_DIAGNOSTIC && f(r, "farm_route").codes.contains("route_status_missing"), "route capability missing named");
        check(f(r, "farm_point").readiness == Readiness.UNKNOWN, "point mode unaffected by route file");
        m = healthy(); m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v9\t1\t2\nend\t0\n"));
        r = eval(m);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("return_candidates_malformed"), "unknown schema is unknown, not ready");
        check(f(r, "planter").readiness == Readiness.READY, "planter does not inherit return failure");
        m = healthy(); m.put(ReadinessSample.PLANTER_STATUS, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\tmetadata-unavailable\tauto\n"));
        r = eval(m);
        check(f(r, "planter").readiness == Readiness.BLOCKED && f(r, "planter").codes.contains("planter_metadata_unavailable"), "planter blocked by metadata");
        check(f(r, "feeding").readiness == Readiness.READY, "feeding not inferred from planter");
        Map<String, Read> lm = healthy();
        lm.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t1.4.33\t67\t157.0\n"));
        ReadinessSample legacy = sample(lm, ReadinessSample.AppFacts.simple("1.4.33", 67, false, true, ""));
        check(f(ReadinessModel.evaluate(legacy), "gps_copy").readiness == Readiness.CORE_NO_DIAGNOSTIC, "legacy pair gps not covered");
    }

    static void t08MissingBrokenTruncatedDeniedTimeout() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok(""));
        Result r = eval(m);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").work != Work.NONE_PENDING, "blank file is not zero items");
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\ntaskA\t1\t3\t0\t1\tx\n"));
        check(f(eval(m), "return").codes.contains("return_candidates_incomplete"), "missing end row is incomplete");
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.truncated("v1\t" + (NOW - 2000) + "\t" + PID + "\n"));
        check(f(eval(m), "return").codes.contains("return_candidates_truncated"), "truncated flagged");
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.denied());
        check(f(eval(m), "return").readiness == Readiness.UNKNOWN, "denied is unknown");
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.missing());
        check(f(eval(m), "return").readiness == Readiness.WAITING_INIT, "missing is waiting, distinct from zero");
        m = healthy(); m.put(ReadinessSample.PLANTER_INVENTORY, Read.ok("v1\t" + (NOW - 4000) + "\t" + PID + "\t880\t900\nslot\t0\t0\t1\tseedA\t1\t5000\tgrowing\tred\n"));
        check(f(eval(m), "planter").codes.contains("planter_inventory_incomplete"), "planter incomplete");
        ReadinessSample timedOut = ReadinessSample.fromSections(null, false, true, NOW, APP);
        r = ReadinessModel.evaluate(timedOut);
        all(r, Readiness.UNKNOWN, "round timed out");
        check(r.coreSummary.contains("逾時"), "summary reports timeout");
    }

    static void t09UserSettingsSeparateFromReadiness() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.ok("off\n"));
        m.put(ReadinessSample.CTRL_RETURN_MODE, Read.ok("off\n"));
        m.put(ReadinessSample.CTRL_PLANTER_MODE, Read.ok("off\n"));
        m.put(ReadinessSample.CTRL_FEEDING_CONFIG, Read.ok("v2\tabc\t0\t4\t20\t100\t1\t1\t0\n"));
        m.put(ReadinessSample.GPS_COPY_MODE, Read.ok("off\n"));
        Result r = eval(m);
        for (String id : new String[]{"nearby", "return", "planter", "feeding", "gps_copy"}) check(f(r, id).setting == Setting.OFF, id + " setting off");
        for (String id : new String[]{"return", "planter", "feeding", "gps_copy"}) check(f(r, id).readiness == Readiness.READY, id + " readiness independent of setting: " + f(r, id).reason);
        check(f(r, "nearby").readiness == Readiness.UNKNOWN && f(r, "nearby").codes.contains("setting_off_kept"), "nearby setting kept, readiness judged on evidence");
        check(f(r, "feeding").codes.contains("feeding_whitelist_empty"), "empty whitelist noted");
    }

    static void t10InstalledNotRebootedOrMismatch() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.MODULE, Read.ok("version=1.5.22\nversionCode=90\ndisabled=no\nstaged=yes\n"));
        m.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t1.5.20\t88\t157.0\n"));
        Result r = eval(m);
        all(r, Readiness.BLOCKED, "loaded other version");
        check(f(r, "scan").codes.contains("core_pending_reboot") && f(r, "scan").next.contains("重開") && !f(r, "scan").next.contains("安裝"), "says reboot, not reinstall");
        check(r.coreSummary.contains("其他版本 1.5.20") && r.coreSummary.contains("待重開"), "summary distinguishes installed vs loaded");
        m.put(ReadinessSample.PID_BEFORE, Read.ok("")); m.put(ReadinessSample.PID_AFTER, Read.ok(""));
        r = eval(m);
        all(r, Readiness.WAITING_GAME, "closed with staged");
        check(f(r, "scan").codes.contains("core_pending_reboot"), "staged noted while closed");
        m = healthy(); m.put(ReadinessSample.COMPATIBILITY, Read.ok("v157.0\tincompatible\t257043464\t254000000\n"));
        m.put(ReadinessSample.CORE_RUNTIME, Read.missing());
        r = eval(m);
        all(r, Readiness.INCOMPATIBLE, "incompatible with no runtime");
        check(f(r, "scan").reason.contains("254000000") && f(r, "scan").reason.contains("無 PID"), "reports observed size and the file's limits");
        m = healthy(); m.put(ReadinessSample.MODULE, Read.ok("version=1.5.22\nversionCode=90\ndisabled=yes\nstaged=no\n"));
        check(f(eval(m), "scan").codes.contains("core_disabled"), "disabled named");
        m = healthy(); m.put(ReadinessSample.MODULE, Read.missing());
        check(f(eval(m), "scan").codes.contains("core_not_installed"), "not installed named");
        m = healthy(); m.put(ReadinessSample.MODULE, Read.error());
        check(f(eval(m), "scan").readiness == Readiness.UNKNOWN && f(eval(m), "scan").codes.contains("module_read_failed"), "module read failure is unknown");
    }

    static void t11PendingWorkStaysReadOnly() {
        Map<String, Read> m = healthy();
        Result r = ReadinessModel.evaluate(sample(m, ReadinessSample.AppFacts.simple("1.5.22", 90, true, true, "批次探險")));
        check(f(r, "batch").work == Work.WORKING && f(r, "batch").codes.contains("gps_work_busy"), "batch shows work busy");
        check(f(r, "farm_point").work == Work.WORKING && f(r, "farm_route").work == Work.WORKING, "farm shows busy");
        check(f(r, "return").readiness == Readiness.READY, "return unaffected by lock");
        m.put(ReadinessSample.ROUTE_STATUS, Read.ok("v1\t" + (NOW - 500) + "\t" + PID + "\t" + "11111111-1111-1111-1111-111111111111" + "\twalk\t0\t1\t300000\n"));
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.ok("route\n"));
        r = eval(m);
        check(f(r, "farm_route").readiness == Readiness.BLOCKED && f(r, "farm_route").codes.contains("route_lease_orphaned"), "walk lease without owner and without stop is orphaned");
        check(f(r, "nearby").setting == Setting.TAKEN_OVER && f(r, "nearby").codes.contains("dispatch_mode_route"), "nearby setting taken over under route");
        check(r.features.size() == 9, "nine features");
    }
}

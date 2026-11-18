package dev.ody.pikmincontrol;

import java.util.Map;

import dev.ody.pikmincontrol.ReadinessModel.FeatureReadiness;
import dev.ody.pikmincontrol.ReadinessModel.Readiness;
import dev.ody.pikmincontrol.ReadinessModel.Result;
import dev.ody.pikmincontrol.ReadinessModel.Setting;
import dev.ody.pikmincontrol.ReadinessModel.Work;
import dev.ody.pikmincontrol.ReadinessSample.Read;

/**
 * Counterexamples from the 2026-09-15 phase B review (R1–R4). Each case is a
 * situation the first cut mis-graded as READY or as the wrong failure.
 */
public final class ReadinessReviewTest {
    static final long NOW = ReadinessModelTest.NOW;
    static final String PID = ReadinessModelTest.PID;
    static Map<String, Read> healthy() { return ReadinessModelTest.healthy(); }
    static Result eval(Map<String, Read> m) { return ReadinessModelTest.eval(m); }
    static FeatureReadiness f(Result r, String id) { return ReadinessModelTest.f(r, id); }
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) {
        r1FeedingStateMap();
        r2ProbeStatusNeverProvesThisProcess();
        r2ReturnCandidatesRowsAndCount();
        r2PlantingControlRealFormat();
        r2LocationSchemaAndRouteFlags();
        r3RuntimeOutranksStaleCompatibility();
        r3UnifiedRuntimeVersion();
        r3PendingTransactionsAreReported();
        r3ControlFileReadFailureIsUnknown();
        r4FreshnessUsesCompletionTime();
        r4ByteOverflowAndMarkers();
        t2PairUnknownIsNotMismatch();
        t2EachUnreadablePendingFact();
        System.out.println("PASS ReadinessReview: S1 feeding map (26 unique native states), T2 pair-unknown, R2 completeness/no-PID, R3 core priority/pending/settings, R4 time/bytes");
    }

    static Map<String, Read> feeding(String state) {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 2000) + "\t" + PID + "\t-\t" + state + "\nend\n"));
        return m;
    }

    /** S1: the map must cover exactly what feeding_coordinator::status() and feeding_rpc::send() emit. */
    static void r1FeedingStateMap() {
        // Emitted before capture: nothing proven.
        check(f(eval(feeding("off")), "feeding").readiness == Readiness.UNKNOWN, "off is not ready");
        check(f(eval(feeding("waiting-other-work")), "feeding").readiness == Readiness.UNKNOWN && f(eval(feeding("waiting-other-work")), "feeding").work == Work.WORKING, "other work: unproven");
        check(f(eval(feeding("config-unavailable")), "feeding").readiness == Readiness.BLOCKED, "config-unavailable blocked");
        check(f(eval(feeding("config-changed")), "feeding").readiness == Readiness.WAITING_INIT, "config-changed waits");
        // Native writes waiting-data exactly when the snapshot is NOT fresh.
        FeatureReadiness wd = f(eval(feeding("waiting-data")), "feeding");
        check(wd.readiness == Readiness.WAITING_INIT && wd.codes.contains("feeding_waiting_data") && !wd.reason.contains("資料就緒"), "waiting-data is waiting, never ready");
        // Pending path: waiting-inventory-confirmation is ALSO written when the snapshot is stale or the account differs → never counted ready.
        FeatureReadiness wic = f(eval(feeding("waiting-inventory-confirmation")), "feeding");
        check(wic.readiness == Readiness.UNKNOWN && wic.work == Work.WORKING && wic.codes.contains("result_pending") && wic.reason.contains("不重送"), "inventory confirmation pending is unproven");
        FeatureReadiness rpc = f(eval(feeding("waiting-rpc-no-retry")), "feeding");
        check(rpc.readiness == Readiness.UNKNOWN && rpc.work == Work.WORKING && rpc.codes.contains("result_pending"), "rpc in flight is unproven");
        for (String st : new String[]{"pending-review", "unconfirmed-cooldown"}) {
            FeatureReadiness x = f(eval(feeding(st)), "feeding");
            check(x.readiness == Readiness.UNKNOWN && x.work == Work.WORKING &&
                (x.reason.contains("核對") || x.reason.contains("冷卻")), st + " recovers automatically");
        }
        for (String st : new String[]{"receipt-write-review", "recovery-storage-retry"})
            check(f(eval(feeding(st)), "feeding").readiness == Readiness.BLOCKED &&
                f(eval(feeding(st)), "feeding").reason.contains("自動重試"), st + " retries storage");
        // Emitted after a fresh, account-matched snapshot in that tick.
        check(f(eval(feeding("waiting-eligible-squad")), "feeding").readiness == Readiness.READY && f(eval(feeding("waiting-eligible-squad")), "feeding").reason.contains("不另證明"), "eligible-squad ready, collect side not claimed");
        FeatureReadiness sent = f(eval(feeding("sent")), "feeding");
        check(sent.readiness == Readiness.READY && sent.work == Work.WORKING && sent.codes.contains("result_pending"), "sent = ready + working + pending");
        for (String st : new String[]{"feed-confirmed", "collect-confirmed"}) check(f(eval(feeding(st)), "feeding").readiness == Readiness.READY && f(eval(feeding(st)), "feeding").work == Work.NONE_PENDING, st);
        check(f(eval(feeding("batch-confirmation-timeout")), "feeding").codes.contains("feeding_partial_timeout") && f(eval(feeding("batch-confirmation-timeout")), "feeding").reason.contains("有限冷卻"), "timeout resolved with bounded cooldown");
        check(f(eval(feeding("batch-squad-changed")), "feeding").codes.contains("feeding_partial_squad_changed"), "squad changed resolved");
        // feeding_rpc::send failures that did NOT send.
        for (String st : new String[]{"runtime-unavailable", "method-mismatch"}) {
            FeatureReadiness x = f(eval(feeding(st)), "feeding");
            check(x.readiness == Readiness.BLOCKED && x.codes.contains("feeding_entry_unavailable") && x.reason.contains("未送出"), st + " entry unavailable, not sent");
        }
        check(f(eval(feeding("account-changed")), "feeding").readiness == Readiness.WAITING_INIT && f(eval(feeding("account-changed")), "feeding").reason.contains("未送出"), "account-changed waits, not sent");
        for (String st : new String[]{"preflight-invalid", "preflight-expired", "item-id-invalid", "request-root-failed", "request-build-failed", "intent-write-failed"}) {
            FeatureReadiness x = f(eval(feeding(st)), "feeding");
            check(x.readiness == Readiness.BLOCKED && x.codes.contains("feeding_send_precondition_failed") && x.reason.contains("未送出") && !x.next.contains("重送"), st + " precondition failed, not sent");
        }
        // feeding_rpc::send failures where the request MAY have gone out.
        for (String st : new String[]{"rpc-start-uncertain", "task-root-failed"}) {
            FeatureReadiness x = f(eval(feeding(st)), "feeding");
            check(x.readiness == Readiness.UNKNOWN && x.work == Work.WORKING && x.codes.contains("feeding_send_uncertain") && x.reason.contains("可能已送出") && x.reason.contains("冷卻"), st + " possibly sent → cooldown");
        }
        // Return-reward states are NOT feeding states: they must fall through to unknown, never be mapped.
        for (String st : new String[]{"one-dispatched", "batch-dispatched", "all-complete-no-due", "one-confirmed", "batch-paused-limit", "batch-stopped-timeout"}) {
            FeatureReadiness x = f(eval(feeding(st)), "feeding");
            check(x.readiness == Readiness.UNKNOWN && x.codes.contains("feeding_state_unknown"), st + " is a return-reward state, not a feeding state");
        }
        FeatureReadiness unk = f(eval(feeding("waiting-for-a-miracle")), "feeding");
        check(unk.readiness == Readiness.UNKNOWN && unk.codes.contains("feeding_state_unknown"), "unknown state is unknown");
        // Missing state file never suggests enabling feeding to get data.
        Map<String, Read> m = healthy(); m.put(ReadinessSample.FEEDING_STATE, Read.missing());
        check(!f(eval(m), "feeding").next.contains("開啟餵食"), "no enable-to-diagnose advice");
    }

    static void r2ProbeStatusNeverProvesThisProcess() {
        Result r = eval(healthy());
        for (String id : ReadinessModelTest.PIDLESS) {
            FeatureReadiness x = f(r, id);
            check(x.readiness != Readiness.READY, id + " never READY from PID-less probe");
            check(!x.reason.contains("清單完整"), id + " never claims a complete list");
        }
        // Residual scan from a previous process, 10 s old, with a fresh new runtime: still only observed.
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.ok((NOW - 10_000) + "\tarmed\t41\tno-dispatch\n"));
        FeatureReadiness scan = f(eval(m), "scan");
        check(scan.readiness == Readiness.UNKNOWN && scan.codes.contains("capability_unconfirmed_no_pid") && scan.reason.contains("無 PID"), "residual scan is unconfirmed");
        // Other PID-bound files may not be borrowed to confirm the scan.
        check(!scan.evidence.toString().contains("return_candidates"), "no borrowed evidence");
    }

    static void r2ReturnCandidatesRowsAndCount() {
        Map<String, Read> m = healthy();
        // Header + end 10 with zero rows.
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\nend\t10\n"));
        FeatureReadiness x = f(eval(m), "return");
        check(x.readiness == Readiness.UNKNOWN && x.codes.contains("return_candidates_count_mismatch"), "declared 10, got 0");
        // Row with a bad ready flag.
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\ntaskA\t1\t3\t0\tmaybe\tx\nend\t1\n"));
        check(f(eval(m), "return").codes.contains("return_candidates_row_malformed"), "bad row flag");
        // Row with five fields only.
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\ntaskA\t1\t3\t0\t1\nend\t1\n"));
        check(f(eval(m), "return").codes.contains("return_candidates_row_malformed"), "short row");
        // Exact legal zero and legal two.
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\nend\t0\n"));
        check(f(eval(m), "return").readiness == Readiness.READY && f(eval(m), "return").work == Work.NONE_PENDING, "legal zero");
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.ok("v1\t" + (NOW - 2000) + "\t" + PID + "\na\t1\t3\t0\t0\tx\nb\t2\t3\t5\t1\ty\nend\t2\n"));
        check(f(eval(m), "return").work == Work.HAS_ITEMS && f(eval(m), "return").reason.contains("其中 1 筆"), "legal two, one ready");
    }

    static void r2PlantingControlRealFormat() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 1000) + "\ton\t0\t1\tcontroller-unavailable\t-\n"));
        FeatureReadiness p = f(eval(m), "farm_point");
        check(p.readiness == Readiness.BLOCKED && p.codes.contains("planting_controller_unavailable"), "controller unavailable blocks farm");
        check(f(eval(m), "farm_route").readiness == Readiness.BLOCKED, "route too");
        // Stale file (written by a previous process) is not accepted.
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 90_000) + "\tobserve\t0\t0\tobserve\t-\n"));
        check(f(eval(m), "farm_point").readiness == Readiness.WAITING_INIT && f(eval(m), "farm_point").codes.contains("planting_control_stale"), "stale planting control");
        // The old fixture format (v1/at/planting/pid/idle) is rejected as malformed, not accepted by length.
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok("v1\t" + (NOW - 1000) + "\t0\t" + PID + "\tidle\n"));
        check(f(eval(m), "farm_point").codes.contains("planting_control_malformed"), "old fixture format rejected");
        // Fresh and valid: still only observed (no PID), never READY.
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 1000) + "\ton\t1\t1\tobserve\tpetal\n"));
        FeatureReadiness ok = f(eval(m), "farm_point");
        check(ok.readiness == Readiness.UNKNOWN && ok.codes.contains("planting_active") && ok.codes.contains("capability_unconfirmed_no_pid"), "valid planting control is observed only");
    }

    static void r2LocationSchemaAndRouteFlags() {
        Map<String, Read> m = healthy();
        // Unknown gate state string.
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tteleporting\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        check(f(eval(m), "nearby").codes.contains("nearby_gate_unknown_state"), "unknown gate state");
        check(f(eval(m), "farm_point").codes.contains("location_state_unknown"), "farm sees unknown location state");
        // Wrong limit column.
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t120000\n"));
        check(f(eval(m), "nearby").codes.contains("nearby_gate_unknown_state"), "limit mismatch treated as unknown");
        // Route: bad flag value.
        m = healthy();
        m.put(ReadinessSample.ROUTE_STATUS, Read.ok("v1\t" + (NOW - 500) + "\t" + PID + "\t-\toff\t2\t0\t300000\n"));
        check(f(eval(m), "farm_route").codes.contains("route_status_malformed"), "flag 2 rejected");
        // Route: unknown phase.
        m.put(ReadinessSample.ROUTE_STATUS, Read.ok("v1\t" + (NOW - 500) + "\t" + PID + "\t-\tflying\t1\t0\t300000\n"));
        check(f(eval(m), "farm_route").codes.contains("route_status_malformed"), "phase rejected");
        // Route: walk lease present, stop not ok, nobody owns it → blocked, not working.
        m.put(ReadinessSample.ROUTE_STATUS, Read.ok("v1\t" + (NOW - 500) + "\t" + PID + "\t11111111-1111-1111-1111-111111111111\thold\t0\t1\t300000\n"));
        check(f(eval(m), "farm_route").codes.contains("route_lease_orphaned"), "orphaned lease");
        // Session identity malformed hash → route waits identity.
        m = healthy();
        m.put(ReadinessSample.SESSION, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\tnot-a-hash\tready\tidle\tend\n"));
        check(f(eval(m), "farm_route").codes.contains("session_identity_not_ready"), "bad identity hash");
    }

    static void r3RuntimeOutranksStaleCompatibility() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.COMPATIBILITY, Read.ok("v157.0\tincompatible\t257043464\t200000000\n"));
        Result r = eval(m);
        check(r.core.coreLoaded && r.core.compatConflict, "fresh runtime wins, conflict flagged");
        for (FeatureReadiness x : r.features) check(x.readiness != Readiness.INCOMPATIBLE, x.id + " must not be marked incompatible over a fresh PID-bound runtime");
        check(f(r, "return").readiness == Readiness.READY && f(r, "return").codes.contains("compat_conflict_runtime_fresh"), "conflict code carried");
        check(r.coreSummary.contains("已載入配對核心") && r.coreSummary.contains("不一致"), "summary consistent with gate");
        // With no runtime at all, the stale compatibility file speaks but says so.
        m.put(ReadinessSample.CORE_RUNTIME, Read.missing());
        r = eval(m);
        check(f(r, "return").readiness == Readiness.INCOMPATIBLE && f(r, "return").reason.contains("上次掛接嘗試"), "without runtime: incompatible with provenance caveat");
    }

    static void r3UnifiedRuntimeVersion() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t1.5.20\t88\t1\n"));
        FeatureReadiness g = f(eval(m), "gps_copy");
        check(g.readiness == Readiness.BLOCKED && g.codes.contains("unified_runtime_version_mismatch"), "gps ready flag with wrong version is not ready");
        check(eval(m).coreSummary.contains("loader 版本與配對不符"), "summary shows loader mismatch");
    }

    static void r3PendingTransactionsAreReported() {
        Map<String, Read> m = healthy();
        ReadinessSample.AppFacts pending = new ReadinessSample.AppFacts("1.5.22", 90, true, true, false, false, false, "", false, true, "", "", -1);
        Result r = ReadinessModel.evaluate(ReadinessModelTest.sample(m, pending));
        check(r.core.blockers.contains("core_transaction_pending"), "blocker recorded");
        for (String id : new String[]{"batch", "farm_point", "farm_route"}) {
            FeatureReadiness x = f(r, id);
            check(x.readiness == Readiness.BLOCKED && x.codes.contains("gps_work_blocked_by_pending") && x.reason.contains("核心更新待重開／核對"), id + " blocked by pending");
            check(x.next.contains("不會清除"), id + " promises not to clear");
        }
        check(f(r, "return").readiness == Readiness.READY && f(r, "return").codes.contains("core_transaction_pending"), "non-GPS feature keeps readiness, carries code");
        check(r.coreSummary.contains("待核對：核心更新待重開／核對"), "summary lists pending");
        // Stop-all error and stop-incomplete.
        ReadinessSample.AppFacts stops = new ReadinessSample.AppFacts("1.5.22", 90, true, false, false, false, false, "batch off unconfirmed", true, true, "", "", -1);
        r = ReadinessModel.evaluate(ReadinessModelTest.sample(m, stops));
        check(f(r, "batch").reason.contains("停止全部尚未確認") && f(r, "batch").reason.contains("前次工作停止未完成"), "both stop blockers named");
        // Takeover keeps the original setting visible.
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.ok("batch\n"));
        ReadinessSample.AppFacts takeover = new ReadinessSample.AppFacts("1.5.22", 90, true, false, false, false, false, "", false, true, "批次探險", "armed", -1);
        FeatureReadiness n = f(ReadinessModel.evaluate(ReadinessModelTest.sample(m, takeover)), "nearby");
        check(n.setting == Setting.TAKEN_OVER && n.codes.contains("nearby_original_armed"), "batch takeover shows original armed");
    }

    static void r3ControlFileReadFailureIsUnknown() {
        Map<String, Read> m = healthy();
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.denied());
        m.put(ReadinessSample.CTRL_RETURN_MODE, Read.error());
        m.put(ReadinessSample.CTRL_PLANTER_MODE, Read.ok(""));
        m.put(ReadinessSample.CTRL_FEEDING_CONFIG, Read.truncated("v2\tabc"));
        m.put(ReadinessSample.GPS_COPY_MODE, Read.ok("maybe\n"));
        Result r = eval(m);
        for (String id : new String[]{"nearby", "return", "planter", "feeding", "gps_copy"}) check(f(r, id).setting == Setting.UNKNOWN, id + " setting unknown on read failure: " + f(r, id).setting);
        check(f(r, "return").readiness == Readiness.READY, "readiness still judged on evidence");
        // Missing control files keep the documented defaults.
        m = healthy();
        m.put(ReadinessSample.CTRL_DISPATCH_MODE, Read.missing()); m.put(ReadinessSample.CTRL_RETURN_MODE, Read.missing()); m.put(ReadinessSample.CTRL_PLANTER_MODE, Read.missing());
        r = eval(m);
        check(f(r, "nearby").setting == Setting.OFF && f(r, "return").setting == Setting.OFF && f(r, "planter").setting == Setting.OFF, "missing = default off");
    }

    static void r4FreshnessUsesCompletionTime() {
        // Round started at NOW-9s, completed at NOW. A file written at NOW-1s must be fresh, not "future".
        Map<String, Read> m = healthy();
        ReadinessSample slow = new ReadinessSample(NOW - 9000, NOW, 9000, true, false, m, ReadinessModelTest.APP);
        Result r = ReadinessModel.evaluate(slow);
        check(r.core.coreLoaded && f(r, "return").readiness == Readiness.READY, "completion time is the freshness reference");
        check(r.sampleCompletedAt == NOW && r.sampleElapsedMs == 9000, "sample times carried");
        // Same files, but the round took 25 s: a 1 s-old-at-start file is now 25 s old and stale for 12 s readers.
        ReadinessSample slower = new ReadinessSample(NOW - 25_000, NOW, 25_000, true, true, m, ReadinessModelTest.APP);
        r = ReadinessModel.evaluate(slower);
        ReadinessModelTest.all(r, Readiness.UNKNOWN, "over-deadline round is not judged");
        check(r.coreSummary.contains("逾時"), "timeout reported");
        // Details show the sample time and cache flag, not "now".
        String d = ReadinessGateway.details(ReadinessModel.evaluate(slow), true);
        check(d.contains("樣本完成") && d.contains("耗時 9000 ms") && d.contains("快取"), "details name sample time and cache");
    }

    static void r4ByteOverflowAndMarkers() {
        // 32767 bytes + newline = 32768 bytes: within limit. 32768 + newline = 32769 bytes: head printed limit+1, overflow possible → conservative TRUNCATED. Multibyte text is measured in bytes.
        StringBuilder ascii = new StringBuilder(); while (ascii.length() < ReadinessSample.PER_READ_LIMIT - 1) ascii.append('x');
        check(ReadinessSample.fromSection(ascii + "\n").state == ReadinessSample.ReadState.OK, "limit-1 plus newline ok");
        check(ReadinessSample.fromSection(ascii + "x\n").state == ReadinessSample.ReadState.TRUNCATED, "limit+1 bytes is conservatively truncated");
        StringBuilder cjk = new StringBuilder(); while (cjk.length() < 11_000) cjk.append('花'); // 33 000 bytes UTF-8
        check(ReadinessSample.fromSection(cjk.toString()).state == ReadinessSample.ReadState.TRUNCATED, "multibyte overflow detected by bytes");
        // Partial payload followed by an error marker is an error, not a short OK read.
        check(ReadinessSample.fromSection("v1\t1\t2\n" + ReadinessSample.MARK_ERROR + "\n").state == ReadinessSample.ReadState.ERROR, "tail error marker");
        // No-process marker becomes an empty OK PID; a non-numeric PID section is an error.
        ReadinessSample s = ReadinessSample.fromSections(sections(ReadinessSample.MARK_NOPROC + "\n", "garbage\n"), true, false, NOW, ReadinessModelTest.APP);
        check(s.read(ReadinessSample.PID_BEFORE).ok() && s.read(ReadinessSample.PID_BEFORE).trimmed().isEmpty(), "noproc = no process");
        check(s.read(ReadinessSample.PID_AFTER).state == ReadinessSample.ReadState.ERROR, "garbage pid is error");
        check(!s.gameRunning() && ReadinessModel.core(s).pidReadFailed, "no false running state; read failure wins");
        // The trailing newline is stripped only after the byte check; payload newlines inside stay.
        check(ReadinessSample.fromSection("a\nb\n").text.equals("a\nb"), "inner newline preserved");
    }

    static String[] sections(String pidBefore, String pidAfter) {
        String[] p = new String[ReadinessSample.ORDER.length];
        for (int i = 0; i < p.length; i++) p[i] = ReadinessSample.MARK_MISSING + "\n";
        p[0] = pidBefore; p[p.length - 1] = pidAfter;
        return p;
    }

    /** T2: an unreadable pair expectation is "cannot verify", never "version mismatch". */
    static void t2PairUnknownIsNotMismatch() {
        ReadinessSample.AppFacts noPair = new ReadinessSample.AppFacts("", -1, false, false, false, false, false, "", false, true, "", "", -1, java.util.Arrays.asList("core_pair"));
        Result r = ReadinessModel.evaluate(ReadinessModelTest.sample(healthy(), noPair));
        check(!r.core.pairKnown && r.core.coreLoadedUnverified && !r.core.coreLoadedOtherVersion && !r.core.coreLoaded, "observed but unverified");
        check(r.core.loadedVersion.equals("1.5.22"), "observed version kept");
        for (FeatureReadiness x : r.features) {
            check(x.readiness == Readiness.UNKNOWN && x.codes.contains("core_pair_unreadable"), x.id + " unknown with core_pair_unreadable: " + x.readiness);
            check(!x.codes.contains("core_version_mismatch") && !x.codes.contains("unified_runtime_version_mismatch"), x.id + " never mismatch");
            check(!x.next.contains("相容核心") && !x.next.contains("重裝") && !x.reason.contains("不符") || x.reason.contains("不是版本不符"), x.id + " no reinstall / compatible-core advice: " + x.next + " / " + x.reason);
        }
        check(r.coreSummary.contains("期望值讀取失敗") && r.coreSummary.contains("已載入核心 1.5.22") && !r.coreSummary.contains("其他版本"), "summary: loaded but unverified: " + r.coreSummary);
        // The whole app-facts read threw: same outcome.
        ReadinessSample.AppFacts thrown = new ReadinessSample.AppFacts("", -1, false, false, false, false, false, "", false, false, "", "", -1, java.util.Arrays.asList("app_facts:IllegalStateException"));
        r = ReadinessModel.evaluate(ReadinessModelTest.sample(healthy(), thrown));
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.UNKNOWN && x.codes.contains("core_pair_unreadable"), x.id + " unknown after app_facts exception");
        // Empty pair without an unreadable marker is still "unknown", not a mismatch.
        r = ReadinessModel.evaluate(ReadinessModelTest.sample(healthy(), ReadinessSample.AppFacts.simple("", -1, true, true, "")));
        check(f(r, "return").codes.contains("core_pair_unreadable") && !f(r, "return").codes.contains("core_version_mismatch"), "empty pair is unknown");
        // With a known pair the same runtime is a genuine mismatch.
        r = ReadinessModel.evaluate(ReadinessModelTest.sample(healthy(), ReadinessSample.AppFacts.simple("1.5.20", 88, true, true, "")));
        check(f(r, "return").readiness == Readiness.BLOCKED && f(r, "return").codes.contains("core_version_mismatch"), "known pair mismatch still blocked");
    }

    /** T2: each pending/stop/lock fact individually unreadable, with a normal pair: GPS-work features unknown, others keep their evidence. */
    static void t2EachUnreadablePendingFact() {
        for (String name : new String[]{"core_pending", "setup_pending", "update_pending", "route_pending", "stop_all_error", "stop_incomplete", "gps_work_owner", "nearby_original"}) {
            ReadinessSample.AppFacts facts = new ReadinessSample.AppFacts("1.5.22", 90, true, false, false, false, false, "", false, true, "", "", -1, java.util.Arrays.asList(name));
            Result r = ReadinessModel.evaluate(ReadinessModelTest.sample(healthy(), facts));
            check(r.core.pairKnown && r.core.coreLoaded, name + ": pair still verified");
            for (String id : new String[]{"batch", "farm_point", "farm_route"}) {
                FeatureReadiness x = f(r, id);
                check(x.readiness == Readiness.UNKNOWN && x.codes.contains("pending_state_unreadable") && x.reason.contains(name), name + ": " + id + " unknown, names the fact");
            }
            for (String id : new String[]{"return", "planter", "feeding", "gps_copy"}) {
                FeatureReadiness x = f(r, id);
                check(x.readiness == Readiness.READY && x.codes.contains("pending_state_unreadable:" + name), name + ": " + id + " keeps its own evidence and carries the flag");
            }
            check(r.coreSummary.contains(name), name + " listed in summary");
        }
    }
}

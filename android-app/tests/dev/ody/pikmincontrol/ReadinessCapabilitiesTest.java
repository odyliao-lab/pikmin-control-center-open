package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import dev.ody.pikmincontrol.ReadinessModel.FeatureReadiness;
import dev.ody.pikmincontrol.ReadinessModel.Readiness;
import dev.ody.pikmincontrol.ReadinessModel.Result;
import dev.ody.pikmincontrol.ReadinessModel.Work;
import dev.ody.pikmincontrol.ReadinessSample.Read;

/**
 * N03: core_capabilities.tsv (core ≥ 1.5.23) as PID-bound readiness evidence.
 *
 * The parser is fed the native formatter's golden output (android-app/tests/
 * fixtures/core_capabilities/*.tsv, produced and re-checked by
 * native/automation/zygisk-module/tests/run-host-tests.ps1), not hand-written
 * copies; mutations of those lines cover schema/framing/range rejection. The
 * model cases follow the work order: missing file, unknown schema, truncated,
 * wrong PID/version, stale/future time, epoch mismatch, logged out, not
 * re-captured, zero/partial list, partial failure not spreading, gift-only
 * missing, pair unknown, read failure/timeout/overflow.
 */
public final class ReadinessCapabilitiesTest {
    static final long NOW = ReadinessModelTest.NOW;      // == the golden fixtures' epochMs
    static final String PID = ReadinessModelTest.PID;    // == the golden fixtures' pid
    static final String VER = "1.5.23"; static final int CODE = 91;   // the pair the golden lines carry
    static final ReadinessSample.AppFacts APP = ReadinessSample.AppFacts.simple(VER, CODE, true, true, "");
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    static FeatureReadiness f(Result r, String id) { return ReadinessModelTest.f(r, id); }

    static Path fixtures() {
        String prop = System.getProperty("pcc.fixtures");
        Path p = prop != null && !prop.isEmpty() ? Paths.get(prop, "core_capabilities") : Paths.get("android-app", "tests", "fixtures", "core_capabilities");
        if (!Files.isDirectory(p)) p = Paths.get("tests", "fixtures", "core_capabilities");
        check(Files.isDirectory(p), "golden fixture directory not found (cwd " + Paths.get("").toAbsolutePath() + "); run native run-host-tests.ps1 -UpdateGolden");
        return p;
    }
    static String golden(String name) throws Exception {
        String t = new String(Files.readAllBytes(fixtures().resolve(name + ".tsv")), StandardCharsets.UTF_8).replace("\r\n", "\n");
        check(t.endsWith("\n") && t.indexOf('\n') == t.length() - 1, "golden " + name + " is one line");
        return t;
    }
    static CoreCapabilities parse(String text) { return CoreCapabilities.parse(Read.ok(text), PID, VER, CODE, NOW); }

    /** Healthy round (1.5.23 pair) with the named golden capabilities line. */
    static Map<String, Read> round(String goldenName) throws Exception {
        Map<String, Read> m = ReadinessModelTest.healthy();
        m.put(ReadinessSample.MODULE, Read.ok("version=" + VER + "\nversionCode=" + CODE + "\ndisabled=no\nstaged=no\n"));
        m.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t157.0\n"));
        m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t1\n"));
        m.put(ReadinessSample.CORE_CAPABILITIES, goldenName == null ? Read.missing() : Read.ok(golden(goldenName)));
        return m;
    }
    /** Same round but without the feature-owned PID files a core in that state could not have written (return/planter/feeding). */
    static Map<String, Read> bare(String goldenName) throws Exception {
        Map<String, Read> m = round(goldenName);
        m.put(ReadinessSample.RETURN_CANDIDATES, Read.missing()); m.put(ReadinessSample.PLANTER_STATUS, Read.missing());
        m.put(ReadinessSample.PLANTER_INVENTORY, Read.missing()); m.put(ReadinessSample.FEEDING_STATE, Read.missing());
        return m;
    }
    static Result eval(Map<String, Read> m) { return ReadinessModel.evaluate(new ReadinessSample(NOW, true, false, m, APP)); }
    static Result eval(Map<String, Read> m, ReadinessSample.AppFacts app) { return ReadinessModel.evaluate(new ReadinessSample(NOW, true, false, m, app)); }
    static String col(String line, int idx, String value) {
        String[] f = line.replace("\n", "").split("\t", -1); f[idx] = value; return String.join("\t", f) + "\n";
    }
    static void allNotReady(Result r, String what) { for (FeatureReadiness x : r.features) check(x.readiness != Readiness.READY, what + ": " + x.id + " was READY (" + x.reason + ")"); }
    static void codeOn(Result r, String id, String code, String what) { check(f(r, id).codes.contains(code), what + ": " + id + " codes " + f(r, id).codes + " reason " + f(r, id).reason); }

    public static void main(String[] args) throws Exception {
        goldenLinesParse();
        parserRejectsMutations();
        missingFileKeepsMainJudgement();
        healthyCapabilitiesMakeAllNineReady();
        invalidFileNeverReady();
        stageAndDomain();
        bootstrapWithoutCoreRuntime();
        returnHelperBlockedDoesNotSpread();
        giftOnlyMissing();
        sessionCases();
        scanCases();
        taskListNonZeroCandidatesZero();
        apkCoreCombinations();
        locationAndPlantingBeforeLogin();
        capabilityVersusUnattributedResults();
        pairUnknownAndConflicts();
        realShellReadsTheFile();
        System.out.println("PASS ReadinessCapabilities: 18 golden lines parsed, mutation rejection, per-feature dependencies, legacy path unchanged, conflicts shown");
    }

    // ------------------------------------------------------------- parser

    static void goldenLinesParse() throws Exception {
        String[] names = {"healthy", "boot_lib_wait", "lib_timeout", "symbols_missing", "incompatible", "domain_pending", "return_helper_blocked",
                "gift_only_missing", "wire_probe_failed_only", "logged_out", "relogin_not_recaptured", "relogin_recaptured", "scan_stale_epoch",
                "scan_zero", "scan_partial_never_published", "identity_unknown", "no_map_yet", "gps_off"};
        for (String n : names) {
            CoreCapabilities k = parse(golden(n));
            check(k.ok(), "golden " + n + " parses: " + k.status + " " + k.detail);
            check(k.pid.equals(PID) && k.version.equals(VER) && k.code == CODE && k.game.equals("157.0") && k.steps.size() == 14, "golden " + n + " fields");
        }
        CoreCapabilities h = parse(golden("healthy"));
        check(h.stage.equals("hooks_called") && h.domainReady() && h.identityReady() && h.caps == CoreCapabilities.CAP_ALL && h.stale == 0, "healthy content");
        check(h.sessionGen == 1 && h.sessionEpoch == 1 && h.scanEpoch == 1 && h.scanCount == 7 && h.scanMs == NOW - 5000 && h.gps, "healthy session/scan");
        for (String s : CoreCapabilities.STEPS) check(h.stepOk(s), "healthy step " + s);
        CoreCapabilities blocked = parse(golden("return_helper_blocked"));
        check(blocked.step("return.preparer_ctor_hook").state.equals("failed") && blocked.step("return.preparer_ctor_hook").code.equals("ctor_missing"), "failed step parsed");
        check(blocked.step("expedition.methods").state.equals("blocked") && blocked.step("expedition.methods").parent.equals("return.preparer_ctor_hook"), "blocked step parsed");
        check(blocked.stepOk("wire_probe") && blocked.stepOk("logout_hook") && !blocked.has(CoreCapabilities.CAP_PREPARER) && blocked.has(CoreCapabilities.CAP_ACTION_MANAGER), "independent steps ok, caps hex");
        CoreCapabilities inc = parse(golden("incompatible"));
        check(inc.stage.equals("incompatible") && inc.failed.equals("il2cpp_size") && inc.failedDetail == 123456789 && !inc.domainReady(), "bootstrap failure with detail");
        CoreCapabilities lo = parse(golden("logged_out"));
        check(lo.loggedOut() && lo.sessionEpoch == 2 && lo.stale == CoreCapabilities.CAP_PER_SESSION && !lo.live(CoreCapabilities.CAP_PREPARER) && lo.live(CoreCapabilities.CAP_COMPLETE_TASK), "logged out: per-session stale, per-process live");
        CoreCapabilities partial = parse(golden("scan_partial_never_published"));
        check(partial.scanCount == -1 && partial.scanMs == 0 && partial.scanEpoch == 0, "partial projection never published");
        CoreCapabilities stale = parse(golden("scan_stale_epoch"));
        check(stale.scanEpoch == 1 && stale.sessionEpoch == 3 && !stale.scanCurrent(NOW), "old-session scan is not current");
    }

    static void parserRejectsMutations() throws Exception {
        String h = golden("healthy");
        check(CoreCapabilities.parse(Read.missing(), PID, VER, CODE, NOW).status == CoreCapabilities.Status.MISSING, "missing");
        check(CoreCapabilities.parse(Read.error(), PID, VER, CODE, NOW).status == CoreCapabilities.Status.UNREADABLE, "error");
        check(CoreCapabilities.parse(Read.denied(), PID, VER, CODE, NOW).status == CoreCapabilities.Status.UNREADABLE, "denied");
        check(CoreCapabilities.parse(Read.truncated(h), PID, VER, CODE, NOW).status == CoreCapabilities.Status.TRUNCATED, "truncated");
        check(parse("").status == CoreCapabilities.Status.MALFORMED, "empty");
        check(parse(col(h, 0, "v2")).status == CoreCapabilities.Status.UNKNOWN_SCHEMA, "unknown schema");
        check(parse(h.replace("\tend\n", "\n")).status == CoreCapabilities.Status.MALFORMED, "missing end column");
        check(parse(h.replace("\tend\n", "\textra\tend\n")).status == CoreCapabilities.Status.MALFORMED, "extra column");
        check(parse(h.replace("\tend\n", "\tend\nv1\n")).status == CoreCapabilities.Status.MALFORMED, "second line");
        check(parse(h.substring(0, h.length() / 2)).status == CoreCapabilities.Status.MALFORMED, "cut in half");
        check(parse(col(h, 1, "abc")).status == CoreCapabilities.Status.MALFORMED, "epochMs text");
        check(parse(col(h, 2, "4243")).status == CoreCapabilities.Status.OLD_PID, "other pid");
        check(CoreCapabilities.parse(Read.ok(h), "", VER, CODE, NOW).status == CoreCapabilities.Status.OLD_PID, "round pid unknown");
        check(parse(col(h, 3, "1.5.22")).status == CoreCapabilities.Status.VERSION_MISMATCH, "core version");
        check(parse(col(h, 4, "90")).status == CoreCapabilities.Status.VERSION_MISMATCH, "core code");
        check(parse(col(h, 5, "152.0")).status == CoreCapabilities.Status.VERSION_MISMATCH, "game version");
        check(CoreCapabilities.parse(Read.ok(h), PID, "", -1, NOW).status == CoreCapabilities.Status.VERSION_UNVERIFIED, "pair unknown");
        check(parse(col(h, 1, Long.toString(NOW - 13_000))).status == CoreCapabilities.Status.STALE, "stale");
        check(parse(col(h, 1, Long.toString(NOW + 6_000))).status == CoreCapabilities.Status.FUTURE, "future");
        check(parse(col(h, 1, Long.toString(NOW + 4_000))).ok(), "within clock slack");
        check(parse(col(h, 7, "running")).status == CoreCapabilities.Status.MALFORMED, "unknown stage");
        check(parse(col(h, 8, "boom")).status == CoreCapabilities.Status.MALFORMED, "unknown failure code");
        check(parse(col(h, 8, "il2cpp_size:x")).status == CoreCapabilities.Status.MALFORMED, "failure detail text");
        check(parse(col(h, 9, "maybe")).status == CoreCapabilities.Status.MALFORMED, "domain enum");
        String steps = h.split("\t", -1)[10];
        check(parse(col(h, 10, steps.replace("wire_probe=ok,", ""))).status == CoreCapabilities.Status.MALFORMED, "missing step");
        check(parse(col(h, 10, steps.replace("wire_probe=ok", "wire_probe=maybe"))).status == CoreCapabilities.Status.MALFORMED, "step value");
        check(parse(col(h, 10, steps.replace("wire_probe=ok", "wire_probe=failed:nope"))).status == CoreCapabilities.Status.MALFORMED, "step failure code");
        check(parse(col(h, 10, steps.replace("wire_probe=ok", "wire_probe=blocked:nothing"))).status == CoreCapabilities.Status.MALFORMED, "blocked parent unknown");
        check(parse(col(h, 10, steps.replace("wire_probe=ok,logout_hook=ok", "logout_hook=ok,wire_probe=ok"))).status == CoreCapabilities.Status.MALFORMED, "step order");
        check(parse(col(h, 11, "1fff")).status == CoreCapabilities.Status.MALFORMED, "caps over range");
        check(parse(col(h, 11, "FFF")).status == CoreCapabilities.Status.MALFORMED, "caps uppercase hex");
        check(parse(col(h, 12, "8")).status == CoreCapabilities.Status.MALFORMED, "stale on per-process cap");
        check(parse(col(col(h, 11, "ffe"), 12, "1")).status == CoreCapabilities.Status.MALFORMED, "stale bit without cap");
        check(parse(col(h, 13, "2")).status == CoreCapabilities.Status.MALFORMED, "gen > epoch");
        check(parse(col(h, 15, "idle")).status == CoreCapabilities.Status.MALFORMED, "identity enum");
        check(parse(col(h, 18, "5")).status == CoreCapabilities.Status.MALFORMED, "scan epoch > session epoch");
        check(parse(col(h, 20, "-2")).status == CoreCapabilities.Status.MALFORMED, "scan count below -1");
        // R4: the parser's failure sentinel must never be mistaken for the legal -1.
        String partial = golden("scan_partial_never_published");
        check(parse(partial).ok() && parse(partial).scanCount == -1, "legal -1 kept");
        for (String bad : new String[]{"garbage", "-2", "9223372036854775808", "-", "", "1e3", "+1", "01x", "-01", "99999999999"}) {
            CoreCapabilities k = parse(col(partial, 20, bad));
            check(k.status == CoreCapabilities.Status.MALFORMED, "scan_count '" + bad + "' must be MALFORMED, got " + k.status);
        }
        check(parse(col(col(col(h, 20, "0"), 19, Long.toString(NOW - 5000)), 18, "1")).ok(), "legal 0 kept");
        check(parse(col(h, 20, "2147483647")).ok() && parse(col(h, 20, "2147483648")).status == CoreCapabilities.Status.MALFORMED, "int boundary");
        // numeric boundaries on other fields: 19-digit overflow and leading sign are rejected everywhere
        check(parse(col(h, 6, "9223372036854775808")).status == CoreCapabilities.Status.MALFORMED, "seq overflow");
        check(parse(col(h, 1, "+1800000000000")).status == CoreCapabilities.Status.MALFORMED, "signed epochMs");
        check(parse(col(h, 14, "-1")).status == CoreCapabilities.Status.MALFORMED, "negative epoch");
        // schema-semantic contradictions
        check(parse(col(col(h, 20, "-1"), 19, "5")).status == CoreCapabilities.Status.MALFORMED, "-1 with scan_ms set");
        check(parse(col(col(h, 18, "0"), 20, "-1")).status == CoreCapabilities.Status.MALFORMED, "-1 with scan_ms still set");
        check(parse(col(h, 13, "0")).status == CoreCapabilities.Status.MALFORMED, "identity ready with session_gen 0");
        check(parse(col(col(h, 13, "0"), 15, "unknown")).status == CoreCapabilities.Status.MALFORMED, "gen 0 with login time");
        check(parse(col(h, 19, "0")).status == CoreCapabilities.Status.MALFORMED, "scan published without time");
        check(parse(col(h, 21, "2")).status == CoreCapabilities.Status.MALFORMED, "gps flag");
        check(parse(col(h, 16, "-5")).status == CoreCapabilities.Status.MALFORMED, "negative time");
        check(parse(h).ok(), "unchanged healthy line still ok after mutations");
    }

    // -------------------------------------------------------------- model

    /** Old core: no file → every judgement identical to main's, plus the marker code. */
    static void missingFileKeepsMainJudgement() throws Exception {
        Result legacy = ReadinessModelTest.eval(ReadinessModelTest.healthy());
        Result r = eval(round(null));
        for (FeatureReadiness x : r.features) {
            FeatureReadiness y = f(legacy, x.id);
            check(x.readiness == y.readiness && x.work == y.work && x.reason.equals(y.reason), "legacy unchanged for " + x.id + ": " + x.readiness + " vs " + y.readiness);
            codeOn(r, x.id, "core_capabilities_missing", "marker");
        }
        check(r.oneLine().equals("功能就緒：4 項就緒・0 項等待・0 項受阻・5 項待核對"), "legacy one-line: " + r.oneLine());
        for (String id : ReadinessModelTest.PIDLESS) check(f(r, id).next.contains("B04"), id + " still points at the native gap");
    }

    static void healthyCapabilitiesMakeAllNineReady() throws Exception {
        Result r = eval(round("healthy"));
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.READY, x.id + " ready: " + x.reason + " " + x.codes);
        check(r.oneLine().equals("功能就緒：9 項就緒・0 項等待・0 項受阻・0 項待核對"), r.oneLine());
        check(f(r, "scan").work == Work.UNKNOWN && f(r, "scan").reason.contains("完整任務清單 7 筆") && f(r, "scan").reason.contains("非待派遣") && f(r, "scan").reason.contains("不代表"), "scan reason: " + f(r, "scan").reason);
        check(f(r, "batch").work == Work.UNKNOWN && f(r, "batch").codes.contains("scan_count_is_task_list_size"), "batch work not inferred from task-list size");
        check(f(r, "scan").evidence.toString().contains("core_capabilities@" + NOW) && f(r, "scan").evidence.toString().contains("scan@" + (NOW - 5000)), "evidence " + f(r, "scan").evidence);
        check(f(r, "nearby").reason.contains("不代表") && f(r, "batch").reason.contains("不代表") && f(r, "farm_route").reason.contains("不代表"), "ready is not success");
        check(!f(r, "nearby").codes.contains("availability_gift_missing"), "gift present");
        check(r.coreSummary.contains("能力回報：階段 hooks_called") && r.coreSummary.contains("帳號 就緒"), "summary: " + r.coreSummary);
        for (FeatureReadiness x : r.features) check(!x.codes.contains("core_capabilities_missing"), "no missing marker when present");
        // dispatch_probe_status is only observation; when it is absent the PID-bound scan still stands but the disagreement is shown.
        Map<String, Read> m = round("healthy"); m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.missing());
        r = eval(m);
        check(f(r, "scan").readiness == Readiness.READY && f(r, "scan").codes.contains("probe_status_disagrees"), "probe missing shown, not hidden");
    }

    static void invalidFileNeverReady() throws Exception {
        String h = golden("healthy");
        Object[][] cases = {
            {Read.ok(col(h, 0, "v2")), "core_capabilities_unknown_schema"},
            {Read.truncated(h), "core_capabilities_truncated"},
            {Read.error(), "core_capabilities_unreadable"},
            {Read.denied(), "core_capabilities_unreadable"},
            {Read.ok(h.substring(0, 40)), "core_capabilities_malformed"},
            {Read.ok(col(h, 2, "9999")), "core_capabilities_old_pid"},
            {Read.ok(col(h, 3, "1.5.22")), "core_capabilities_version_mismatch"},
            {Read.ok(col(h, 5, "152.0")), "core_capabilities_version_mismatch"},
            {Read.ok(col(h, 1, Long.toString(NOW - 20_000))), "core_capabilities_stale"},
            {Read.ok(col(h, 1, Long.toString(NOW + 60_000))), "core_capabilities_future"},
        };
        Result legacy = ReadinessModelTest.eval(ReadinessModelTest.healthy());
        for (Object[] c : cases) {
            Map<String, Read> m = round(null); m.put(ReadinessSample.CORE_CAPABILITIES, (Read) c[0]);
            Result r = eval(m);
            allNotReady(r, (String) c[1]);
            for (FeatureReadiness x : r.features) {
                FeatureReadiness y = f(legacy, x.id);
                // R3: the legacy judgement is kept (same work state, own reason retained); only READY is capped to UNKNOWN.
                check(x.readiness == (y.readiness == Readiness.READY ? Readiness.UNKNOWN : y.readiness) && x.work == y.work, c[1] + ": " + x.id + " " + x.readiness + "/" + x.work + " vs legacy " + y.readiness + "/" + y.work);
                if (y.readiness == Readiness.READY) check(x.reason.contains(y.reason) && x.reason.contains("不能判就緒"), c[1] + ": own evidence retained in reason: " + x.reason);
                codeOn(r, x.id, (String) c[1], "code");
                check(!x.next.contains("安裝"), c[1] + ": never suggests reinstall");
            }
            check(r.coreSummary.contains("能力回報："), "summary names the file: " + r.coreSummary);
        }
        // R3 counter-example: an invalid file must not swallow an independent safety state or its no-resend note.
        Map<String, Read> m = round(null); m.put(ReadinessSample.CORE_CAPABILITIES, Read.error());
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 2000) + "\t" + PID + "\t-\tpending-review\nend\n"));
        Result r = eval(m);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("feeding_review_pending") && f(r, "feeding").reason.contains("自動恢復") && f(r, "feeding").codes.contains("core_capabilities_unreadable"), "pending-review survives an unreadable capabilities file: " + f(r, "feeding").readiness + " " + f(r, "feeding").reason);
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 2000) + "\t" + PID + "\t-\trpc-start-uncertain\nend\n"));
        r = eval(m);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("feeding_send_uncertain"), "uncertain send survives");
        ReadinessSample.AppFacts pendingCore = new ReadinessSample.AppFacts(VER, CODE, true, true, false, false, false, "", false, true, "", "", -1);
        r = eval(m, pendingCore);
        check(f(r, "batch").readiness == Readiness.BLOCKED && f(r, "batch").codes.contains("gps_work_blocked_by_pending"), "pending core transaction still blocks batch under an invalid file");
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 2000) + "\t" + PID + "\t-\tsent\nend\n"));
        r = eval(m);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").work == Work.WORKING && f(r, "feeding").codes.contains("result_pending"), "in-flight send: READY capped to UNKNOWN, work kept: " + f(r, "feeding").reason);
        // Round-level failures still win: timeout / overflow of the whole round.
        Result t = ReadinessModel.evaluate(new ReadinessSample(NOW, false, true, round("healthy"), APP));
        for (FeatureReadiness x : t.features) check(x.readiness == Readiness.UNKNOWN && x.codes.contains("round_timeout"), "timeout wins: " + x.id);
    }

    /** R2: bootstrap diagnostics must show through with a missing or old core_runtime, without becoming "core loaded". */
    static void bootstrapWithoutCoreRuntime() throws Exception {
        String[][] stages = {{"boot_lib_wait", "WAITING_INIT", "caps_stage_lib_wait"}, {"lib_timeout", "BLOCKED", "caps_stage_lib_timeout"},
                {"symbols_missing", "BLOCKED", "caps_stage_symbols_missing"}, {"incompatible", "INCOMPATIBLE", "caps_stage_incompatible"},
                {"domain_pending", "WAITING_INIT", "caps_domain_pending"}};
        Read[] runtimes = {Read.missing(), Read.ok("v1\t" + (NOW - 1000) + "\t9999\t" + VER + "\t" + CODE + "\t157.0\n"), Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t157.0\n")};
        String[] runtimeNames = {"missing", "old-pid", "fresh"};
        for (String[] st : stages) for (int i = 0; i < runtimes.length; i++) {
            Map<String, Read> m = bare(st[0]); m.put(ReadinessSample.CORE_RUNTIME, runtimes[i]);
            m.put(ReadinessSample.UNIFIED_RUNTIME, Read.missing());   // loader heartbeat may be absent too; never claim GPS
            Result r = eval(m);
            String what = st[0] + "/" + runtimeNames[i];
            check(r.core.coreLoaded == (i == 2), what + ": coreLoaded only with a fresh same-PID runtime");
            check(!r.core.gpsLoaded, what + ": GPS never claimed");
            for (FeatureReadiness x : r.features) {
                if (i != 2) check(x.readiness == Readiness.valueOf(st[1]) && x.codes.contains(st[2]), what + ": " + x.id + " " + x.readiness + " " + x.codes);
                else {
                    // a fresh same-PID runtime can only be written by a hook: a line saying hooks were never called is a contradiction, never READY
                    check(x.readiness != Readiness.READY, what + ": " + x.id + " must not be READY");
                    check(x.codes.contains("caps_conflict:core_runtime") == !st[0].equals("domain_pending"), what + ": " + x.id + " conflict code: " + x.codes);
                    check(!x.codes.contains("core_runtime_missing"), what + ": no generic missing code with a fresh runtime");
                }
            }
            check(r.coreSummary.contains(st[0].equals("boot_lib_wait") ? "lib_wait" : st[0].equals("domain_pending") ? "domain pending" : st[0]), what + ": summary names the stage: " + r.coreSummary);
            check(i == 2 || r.coreSummary.contains(i == 0 ? "尚未寫入" : "過期或屬舊程序"), what + ": summary says the runtime is missing/old: " + r.coreSummary);
        }
        // hooks called + domain ready, runtime not yet written (before the first map callback): waiting, not "core loaded"
        Map<String, Read> m = bare("no_map_yet"); m.put(ReadinessSample.CORE_RUNTIME, Read.missing()); m.put(ReadinessSample.UNIFIED_RUNTIME, Read.missing());
        Result r = eval(m);
        check(!r.core.coreLoaded, "not loaded without runtime");
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.WAITING_INIT && x.codes.contains("core_runtime_pending_caps_ready") && x.codes.contains("core_runtime_missing"), "pre-map: " + x.id + " " + x.codes);
        // the PID-less compatibility file disagreeing with a PID-bound hooks_called line is flagged, not trusted
        m.put(ReadinessSample.COMPATIBILITY, Read.ok("v157.0\tincompatible\t257043464\t1\n"));
        r = eval(m);
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("compat_file_incompatible_but_caps_disagree"), "compat conflict shown: " + f(r, "scan").codes);
        // a capabilities line whose pair does not match cannot explain anything: the generic gate applies
        m = bare("lib_timeout"); m.put(ReadinessSample.CORE_RUNTIME, Read.missing());
        m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(golden("lib_timeout"), 3, "1.5.22")));
        r = eval(m);
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("core_runtime_missing") && !f(r, "scan").codes.contains("caps_stage_lib_timeout"), "mismatched pair cannot explain: " + f(r, "scan").codes);
    }

    static void stageAndDomain() throws Exception {
        Result r = eval(round("boot_lib_wait"));
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_stage_lib_wait"), "lib wait: " + f(r, "scan").reason);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_conflict:return_candidates"), "fresh return list while the core is still waiting for libil2cpp is a contradiction: " + f(r, "return").reason);
        Map<String, Read> lw = round("boot_lib_wait"); lw.put(ReadinessSample.RETURN_CANDIDATES, Read.missing());
        check(f(eval(lw), "return").readiness == Readiness.WAITING_INIT && f(eval(lw), "return").codes.contains("caps_stage_lib_wait"), "without an own file the stage explains the wait");
        r = eval(round("lib_timeout"));
        check(f(r, "scan").readiness == Readiness.BLOCKED && f(r, "scan").codes.contains("caps_stage_lib_timeout") && f(r, "scan").reason.contains("逾時"), "lib timeout blocked");
        r = eval(round("symbols_missing"));
        check(f(r, "nearby").readiness == Readiness.BLOCKED && f(r, "nearby").codes.contains("caps_stage_symbols_missing"), "symbols missing blocked");
        r = eval(bare("incompatible"));
        check(f(r, "scan").readiness == Readiness.INCOMPATIBLE && f(r, "scan").reason.contains("123456789") && f(r, "scan").reason.contains("本程序回報"), "incompatible with PID-bound size: " + f(r, "scan").reason);
        check(f(r, "planter").readiness == Readiness.INCOMPATIBLE && f(r, "return").readiness == Readiness.INCOMPATIBLE, "planter/return incompatible too");
        r = eval(round("incompatible"));
        check(f(r, "planter").readiness == Readiness.UNKNOWN && f(r, "planter").codes.contains("caps_conflict:planter_status"), "fresh planter file vs incompatible core → shown as conflict");
        Map<String, Read> dp = round("domain_pending");
        dp.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t0\n"));   // GPS hook installs only after the domain is ready
        r = eval(dp);
        for (String id : new String[]{"scan", "nearby", "batch", "farm_point", "farm_route"}) check(f(r, id).readiness == Readiness.WAITING_INIT && f(r, id).codes.contains("caps_domain_pending") && f(r, id).reason.contains("重試"), id + " domain pending: " + f(r, id).reason);
        check(f(r, "gps_copy").readiness == Readiness.WAITING_INIT && f(r, "gps_copy").codes.contains("gps_hook_not_installed") && !f(r, "gps_copy").codes.contains("caps_domain_pending"), "gps copy waits for its own hook, not for the resolution chain");
        // domain pending 1 s after login while own files (stamped 2-4 s ago) are fresh: those files predate the login (R3), never a green light.
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_data_before_login:return_candidates"), "return predates login: " + f(r, "return").reason);
        check(f(r, "planter").readiness == Readiness.UNKNOWN && f(r, "planter").codes.contains("caps_data_before_login:planter_status"), "planter predates login");
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("caps_data_before_login:feeding_state"), "feeding predates login");
        // …and when they are stamped after the login, the domain-pending chain contradicts them instead.
        for (String id : new String[]{ReadinessSample.RETURN_CANDIDATES, ReadinessSample.PLANTER_STATUS, ReadinessSample.PLANTER_INVENTORY, ReadinessSample.FEEDING_STATE})
            dp.put(id, Read.ok(dp.get(id).text.replace(Long.toString(NOW - 2000), Long.toString(NOW - 500)).replace(Long.toString(NOW - 4000), Long.toString(NOW - 500))));
        r = eval(dp);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_conflict:return_candidates"), "return conflict: " + f(r, "return").reason);
        check(f(r, "planter").readiness == Readiness.UNKNOWN && f(r, "planter").codes.contains("caps_conflict:planter_status"), "planter conflict: " + f(r, "planter").reason);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("caps_conflict:feeding_state"), "feeding conflict (identity unknown vs fresh eligible state): " + f(r, "feeding").reason);
        // no map yet: scan not published → waiting; nothing blocked.
        r = eval(round("no_map_yet"));
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_missing:preparer"), "no map: managers not captured yet: " + f(r, "scan").reason);
        check(f(r, "feeding").readiness == Readiness.READY, "feeding_probe is resolved at bootstrap and identity is ready");
        check(!r.warning(), "no blocked feature");
    }

    static void returnHelperBlockedDoesNotSpread() throws Exception {
        Result r = eval(round("return_helper_blocked"));
        // preparer ctor failed → task_list_time blocked: scan/dispatch blocked with the parent named, no retry claimed.
        check(f(r, "scan").readiness == Readiness.BLOCKED && f(r, "scan").codes.contains("caps_step_blocked:return.task_list_time") && f(r, "scan").reason.contains("return.preparer_ctor_hook"), "scan blocked by parent: " + f(r, "scan").reason);
        check(f(r, "scan").reason.contains("不重試"), "no retry promised");
        check(f(r, "nearby").readiness == Readiness.BLOCKED && f(r, "batch").readiness == Readiness.BLOCKED && f(r, "farm_point").readiness == Readiness.BLOCKED && f(r, "farm_route").readiness == Readiness.BLOCKED, "dispatch family blocked");
        // return: complete_task hook ok but task_list blocked; the fresh candidates list contradicts it → inconsistency, not READY.
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_conflict:return_candidates"), "return: " + f(r, "return").reason);
        // wire probe / logout hook independent (ok); feeding and gps are unaffected by the return helper.
        check(f(r, "feeding").readiness == Readiness.READY, "feeding unaffected: " + f(r, "feeding").reason);
        check(f(r, "gps_copy").readiness == Readiness.READY, "gps unaffected");
        // With no own return file the capability failure is the explanation (BLOCKED), not a contradiction.
        Map<String, Read> m = round("return_helper_blocked"); m.put(ReadinessSample.RETURN_CANDIDATES, Read.missing());
        r = eval(m);
        check(f(r, "return").readiness == Readiness.BLOCKED && f(r, "return").codes.contains("caps_step_blocked:return.task_list_time"), "return blocked explains missing list: " + f(r, "return").reason);
        // A failed wire probe only affects nothing here (it is not a dependency of any feature) but is visible in the summary.
        r = eval(round("wire_probe_failed_only"));
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.READY, "wire probe failure does not spread: " + x.id + " " + x.reason);
        check(r.coreSummary.contains("失敗 1"), "summary counts the failed step: " + r.coreSummary);
    }

    static void giftOnlyMissing() throws Exception {
        Result r = eval(round("gift_only_missing"));
        check(f(r, "nearby").readiness == Readiness.READY && f(r, "nearby").codes.contains("availability_gift_missing") && f(r, "nearby").reason.contains("禮物盒"), "gift missing: nearby still ready, gift named: " + f(r, "nearby").reason);
        check(f(r, "batch").readiness == Readiness.READY && f(r, "batch").codes.contains("availability_gift_missing"), "batch ready with gift note");
        check(f(r, "scan").readiness == Readiness.READY && f(r, "farm_point").readiness == Readiness.READY, "fruit/seed paths unaffected");
        check(r.coreSummary.contains("失敗 1"), "summary shows the failed availability.gift step");
    }

    static void sessionCases() throws Exception {
        // logged out: session-bound features wait; per-process ones (gps) stay.
        Result r = eval(round("logged_out"));
        for (String id : new String[]{"scan", "nearby", "batch", "farm_point", "farm_route"}) check(f(r, id).readiness == Readiness.WAITING_INIT && f(r, id).codes.contains("caps_logged_out"), id + " logged out: " + f(r, id).reason);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_logged_out") && f(r, "return").codes.contains("caps_conflict:return_candidates"), "return: fresh list vs logged out → conflict: " + f(r, "return").reason);
        check(f(r, "gps_copy").readiness == Readiness.READY, "gps independent of session");
        check(r.coreSummary.contains("帳號 已登出") && r.coreSummary.contains("未重新捕捉"), "summary: " + r.coreSummary);
        // re-login without manager rebuild: stale per-session bits → UNKNOWN (never READY), pointer-not-null is not confirmation.
        r = eval(round("relogin_not_recaptured"));
        check(f(r, "scan").readiness == Readiness.UNKNOWN && f(r, "scan").codes.contains("caps_stale:preparer") && f(r, "scan").reason.contains("指標非空"), "scan stale preparer: " + f(r, "scan").reason);
        check(f(r, "nearby").readiness == Readiness.UNKNOWN && f(r, "planter").readiness == Readiness.UNKNOWN && f(r, "planter").codes.contains("caps_conflict:planter_status"), "planter stale vs fresh file → conflict");
        check(f(r, "feeding").readiness == Readiness.READY, "feeding_probe is per-process and identity is ready");
        // re-login with rebuild: everything captured again, scan re-published in the new session → ready.
        r = eval(round("relogin_recaptured"));
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.READY, "recaptured: " + x.id + " " + x.reason + " " + x.codes);
        check(f(r, "scan").reason.contains("完整任務清單 4 筆"), "new session scan count");
        // identity unknown (login seen, hash not ready): feeding and farm_route wait; scan is allowed but unpublished here.
        r = eval(round("identity_unknown"));
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("caps_identity_not_ready") && f(r, "feeding").codes.contains("caps_conflict:feeding_state"), "feeding needs identity ready: " + f(r, "feeding").reason);
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_scan_not_published"), "identity unknown: scan not yet published");
        // R3: same PID, fresh, but stamped BEFORE the latest login → belongs to the previous account/session, never READY.
        String hl = golden("healthy");
        Map<String, Read> pre = round(null); pre.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(hl, 16, Long.toString(NOW - 100))));   // last_login_ms = NOW-100
        pre.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\t-\tfeed-confirmed\nend\n"));
        r = eval(pre);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("caps_data_before_login:feeding_state") && f(r, "feeding").reason.contains("舊帳號"), "feed-confirmed from before login: " + f(r, "feeding").readiness + " " + f(r, "feeding").reason);
        check(f(r, "return").readiness == Readiness.UNKNOWN && f(r, "return").codes.contains("caps_data_before_login:return_candidates"), "return list from before login: " + f(r, "return").codes);
        check(f(r, "planter").readiness == Readiness.UNKNOWN && f(r, "planter").codes.contains("caps_data_before_login:planter_status"), "planter from before login");
        check(f(r, "farm_route").readiness == Readiness.UNKNOWN && f(r, "farm_route").codes.toString().contains("caps_data_before_login:"), "route/location/session from before login: " + f(r, "farm_route").codes);
        check(f(r, "scan").readiness == Readiness.READY, "scan is session-bound by the line itself, unaffected");
        // data stamped after the login passes this check (necessary, not sufficient — documented)
        pre.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 50) + "\t" + PID + "\t-\tfeed-confirmed\nend\n"));
        r = eval(pre);
        check(f(r, "feeding").readiness == Readiness.READY && !f(r, "feeding").codes.contains("caps_data_before_login:feeding_state"), "after-login data accepted");
        // farm_route: automation_session fresh+ready in the fixture but capabilities identity unknown → conflict, not ready.
        Map<String, Read> m = round("identity_unknown");
        String h = golden("healthy");
        m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(h, 15, "unknown")));
        r = eval(m);
        check(f(r, "farm_route").readiness == Readiness.UNKNOWN && f(r, "farm_route").codes.contains("caps_conflict:automation_session"), "farm_route identity conflict: " + f(r, "farm_route").reason);
    }

    /** S1: location (report + raw) and planting-control reports stamped before the login are refused in VALID mode; legacy untouched. */
    static void locationAndPlantingBeforeLogin() throws Exception {
        String h = golden("healthy");
        // login NOW-2000, scan NOW-500; nearby_gate report and raw NOW-3000 → refused
        Map<String, Read> m = round(null);
        m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(col(h, 16, Long.toString(NOW - 2000)), 19, Long.toString(NOW - 500))));
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 3000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 3000) + "\t300000\n"));
        Result r = eval(m);
        for (String id : new String[]{"nearby", "farm_point", "farm_route"}) {
            check(f(r, id).readiness == Readiness.UNKNOWN && f(r, id).codes.contains("caps_data_before_login:nearby_gate_status"), id + ": location report before login: " + f(r, id).readiness + " " + f(r, id).codes);
            check(!f(r, id).reason.contains("皆屬目前 session"), id + ": no session claim");
        }
        check(f(r, "scan").readiness == Readiness.READY && f(r, "batch").readiness == Readiness.READY, "scan/batch do not depend on the location report");
        // report after login but raw fix before it → refused on the raw time
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 2500) + "\t300000\n"));
        r = eval(m);
        check(f(r, "nearby").codes.contains("caps_data_before_login:nearby_gate_status.raw") && f(r, "farm_point").codes.contains("caps_data_before_login:nearby_gate_status.raw"), "raw before login: " + f(r, "nearby").codes);
        // everything fresh, planting_control NOW-3000, login NOW-2000 → farm refused; nearby (no planting dependency) READY without any no-pid claim
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 3000) + "\tobserve\t0\t0\tobserve\t-\n"));
        r = eval(m);
        check(f(r, "farm_point").readiness == Readiness.UNKNOWN && f(r, "farm_point").codes.contains("caps_data_before_login:planting_control_status"), "planting report before login: " + f(r, "farm_point").codes);
        check(f(r, "nearby").readiness == Readiness.READY && !f(r, "nearby").codes.contains("capability_unconfirmed_no_pid"), "nearby ready without no-pid claim");
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 1000) + "\tobserve\t0\t0\tobserve\t-\n"));
        r = eval(m);
        check(f(r, "farm_point").readiness == Readiness.READY && !f(r, "farm_point").codes.contains("capability_unconfirmed_no_pid") && f(r, "farm_point").codes.contains("planting_control_observed_no_session"), "farm_point ready: planting report is observation, not a no-pid capability claim: " + f(r, "farm_point").codes);
        check(f(r, "farm_point").reason.contains("觀察") && !f(r, "farm_point").reason.contains("皆屬"), "farm_point reason separates capability from observation: " + f(r, "farm_point").reason);
        // the same fields' wrong-PID / malformed / stale behaviour is unchanged
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t9999\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        r = eval(m);
        check(f(r, "nearby").codes.contains("nearby_gate_invalid") && f(r, "farm_point").codes.contains("location_report_missing"), "wrong PID still handled by the old checks");
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 20000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        r = eval(m);
        check(f(r, "nearby").codes.contains("nearby_gate_stale"), "stale still handled by the old check");
        m.put(ReadinessSample.NEARBY_GATE, Read.ok("v2\t" + (NOW - 1000) + "\t" + PID + "\tready\t7\t3\t25.0\t121.0\t25.0\t121.0\t" + (NOW - 1500) + "\t300000\n"));
        m.put(ReadinessSample.PLANTING_CONTROL, Read.ok((NOW - 1000) + "\tobserve\tx\t0\tobserve\t-\n"));
        check(f(eval(m), "farm_point").codes.contains("planting_control_malformed"), "malformed planting report still handled");
        // LEGACY (old core): nothing changes
        Result legacy = ReadinessModelTest.eval(ReadinessModelTest.healthy());
        check(f(legacy, "nearby").readiness == Readiness.UNKNOWN && f(legacy, "farm_point").codes.contains("capability_unconfirmed_no_pid"), "legacy untouched");
    }

    /** S2: capability readiness is separated from work/results that carry no session evidence. */
    static void capabilityVersusUnattributedResults() throws Exception {
        String h = golden("healthy");
        // (1) old account's callback written after the new login: feed-confirmed NOW-50, login NOW-2000
        Map<String, Read> m = round(null);
        m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(h, 16, Long.toString(NOW - 2000))));
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 50) + "\t" + PID + "\t-\tfeed-confirmed\nend\n"));
        for (String id : new String[]{ReadinessSample.PLANTER_STATUS, ReadinessSample.PLANTER_INVENTORY})
            m.put(id, Read.ok(m.get(id).text.replace(Long.toString(NOW - 4000), Long.toString(NOW - 500))));   // planter files after the login
        Result r = eval(m);
        FeatureReadiness fe = f(r, "feeding");
        check(fe.readiness == Readiness.READY && fe.work == Work.UNKNOWN && fe.codes.contains("work_result_unattributed:feeding_state"), "feeding: capability ready, result unattributed: " + fe.readiness + "/" + fe.work + " " + fe.codes);
        check(fe.reason.contains("本帳號未核對") && !fe.reason.contains("上一批已確認完成"), "feeding reason never claims this account's completion: " + fe.reason);
        check(f(r, "return").readiness == Readiness.READY && f(r, "return").work == Work.UNKNOWN && f(r, "return").codes.contains("work_result_unattributed:return_candidates") && f(r, "return").reason.contains("本帳號未核對"), "return: capability vs observed list: " + f(r, "return").reason);
        check(f(r, "planter").readiness == Readiness.READY && f(r, "planter").work == Work.UNKNOWN && f(r, "planter").codes.contains("work_result_unattributed:planter_status"), "planter: capability vs observed slots: " + f(r, "planter").reason);
        // "sent" keeps its no-resend safety code while the work stays unattributed
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 50) + "\t" + PID + "\t-\tsent\nend\n"));
        r = eval(m);
        check(f(r, "feeding").readiness == Readiness.READY && f(r, "feeding").work == Work.UNKNOWN && f(r, "feeding").codes.contains("result_pending") && f(r, "feeding").codes.contains("work_result_unattributed:feeding_state"), "sent: safety code kept, work unattributed: " + f(r, "feeding").codes);
        // (2) reset after the login: last_login_ms still set, identity unknown, per-session bits stale → nothing READY from fresh files
        String reset = col(col(col(h, 15, "unknown"), 14, "2"), 12, "65");   // epoch 2, stale = preparer|action_manager|planting|location
        m = round(null); m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(reset));
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 50) + "\t" + PID + "\t-\tfeed-confirmed\nend\n"));
        r = eval(m);
        for (FeatureReadiness x : r.features) if (!x.id.equals("gps_copy")) check(x.readiness != Readiness.READY, "after reset: " + x.id + " must not be READY: " + x.readiness + " " + x.codes);
        check(f(r, "return").codes.contains("caps_stale:action_manager") && f(r, "feeding").codes.contains("caps_identity_not_ready"), "reset reasons: " + f(r, "return").codes + " " + f(r, "feeding").codes);
        // (3) two files each fresh but from different sessions: re-login at NOW-10000; feeding stamped NOW-11000, return list NOW-2000
        m = round("relogin_recaptured");
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 11000) + "\t" + PID + "\t-\tfeed-confirmed\nend\n"));
        r = eval(m);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("caps_data_before_login:feeding_state"), "feeding from the previous session refused");
        check(f(r, "return").readiness == Readiness.READY && f(r, "return").work == Work.UNKNOWN, "return after login: capability ready, list unattributed");
        // (4) data without attribution but a safety pending is visible
        m = round("healthy");
        m.put(ReadinessSample.FEEDING_STATE, Read.ok("v2\t" + (NOW - 50) + "\t" + PID + "\t-\tpending-review\nend\n"));
        r = eval(m);
        check(f(r, "feeding").readiness == Readiness.UNKNOWN && f(r, "feeding").codes.contains("feeding_review_pending") && f(r, "feeding").reason.contains("自動恢復"), "pending recovery visible under a valid line: " + f(r, "feeding").reason);
        // healthy line: nine READY still, but no work claim is borrowed from unattributed files
        r = eval(round("healthy"));
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.READY, "healthy still ready: " + x.id);
        for (String id : new String[]{"return", "planter", "feeding"}) check(f(r, id).work == Work.UNKNOWN && f(r, id).codes.toString().contains("work_result_unattributed"), id + " work unattributed under VALID");
        // LEGACY keeps the old work states
        Result legacy = ReadinessModelTest.eval(ReadinessModelTest.healthy());
        check(f(legacy, "return").work == Work.HAS_ITEMS && f(legacy, "planter").work == Work.GROWING && f(legacy, "feeding").work == Work.NO_MATCH, "legacy work states unchanged");
    }

    /** R7: the four APK × core combinations as the model actually judges them. */
    static void apkCoreCombinations() throws Exception {
        // new APK (pair 1.5.23/91) + old core 1.5.22/90 reporting, no capabilities file → version mismatch, not LEGACY
        Map<String, Read> m = round(null);
        m.put(ReadinessSample.MODULE, Read.ok("version=1.5.22\nversionCode=90\ndisabled=no\nstaged=no\n"));
        m.put(ReadinessSample.CORE_RUNTIME, Read.ok("v1\t" + (NOW - 1000) + "\t" + PID + "\t1.5.22\t90\t157.0\n"));
        m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t1.5.22\t90\t1\n"));
        Result r = eval(m);
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.BLOCKED && x.codes.contains("core_version_mismatch"), "new APK + old core: " + x.id + " " + x.readiness + " " + x.codes);
        check(!f(r, "scan").codes.contains("core_capabilities_missing"), "the missing file is never reached behind the mismatch gate");
        // old APK (pair 1.5.22/90) + new core 1.5.23/91 (writes the file, but the old APK does not read it; here it is read but the pair fails)
        m = round("healthy");
        ReadinessSample.AppFacts oldApk = ReadinessSample.AppFacts.simple("1.5.22", 90, true, true, "");
        m.put(ReadinessSample.MODULE, Read.ok("version=1.5.23\nversionCode=91\ndisabled=no\nstaged=no\n"));
        r = eval(m, oldApk);
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.BLOCKED && x.codes.contains("core_version_mismatch"), "old APK + new core: " + x.id + " " + x.codes);
        // same-version pairs: 0.14.0-style (1.5.22 + no file) keeps main's judgement; 0.14.1 + 1.5.23 + file takes the VALID path
        r = ReadinessModelTest.eval(ReadinessModelTest.healthy());
        check(r.oneLine().equals("功能就緒：4 項就緒・0 項等待・0 項受阻・5 項待核對"), "1.5.22 pair without file: " + r.oneLine());
        r = eval(round("healthy"));
        check(r.oneLine().equals("功能就緒：9 項就緒・0 項等待・0 項受阻・0 項待核對"), "1.5.23 pair with file: " + r.oneLine());
        // LEGACY only applies once the pair matches: 1.5.23 pair, 1.5.23 core, file missing (e.g. loader could not write)
        r = eval(round(null));
        check(r.oneLine().equals("功能就緒：4 項就緒・0 項等待・0 項受阻・5 項待核對") && f(r, "scan").codes.contains("core_capabilities_missing"), "same pair, file missing → legacy: " + r.oneLine());
    }

    /** R5: a non-zero task list with zero dispatchable candidates must not read as "has items". */
    static void taskListNonZeroCandidatesZero() throws Exception {
        Map<String, Read> m = round("healthy");   // task list 7
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.ok((NOW - 3000) + "\tobserved\t0\tno-dispatch\n"));
        Result r = eval(m);
        for (String id : new String[]{"scan", "batch", "nearby"}) {
            FeatureReadiness x = f(r, id);
            check(x.readiness == Readiness.READY && x.work != Work.HAS_ITEMS, id + ": ready but no items inferred: " + x.work);
        }
        check(f(r, "scan").work == Work.UNKNOWN && f(r, "scan").codes.contains("probe_candidates_zero(no-pid)"), "scan shows the PID-less zero-candidate observation: " + f(r, "scan").codes);
        // probe missing: still no inference
        m.put(ReadinessSample.DISPATCH_PROBE_STATUS, Read.missing());
        r = eval(m);
        check(f(r, "scan").work == Work.UNKNOWN && !f(r, "scan").codes.toString().contains("probe_candidates"), "no probe, no candidate claim");
        // zero task list is the only case that says "nothing pending"
        r = eval(round("scan_zero"));
        check(f(r, "scan").work == Work.NONE_PENDING && f(r, "batch").work == Work.NONE_PENDING, "zero list → none pending");
    }

    static void scanCases() throws Exception {
        Result r = eval(round("scan_zero"));
        check(f(r, "scan").readiness == Readiness.READY && f(r, "scan").work == Work.NONE_PENDING && f(r, "scan").reason.contains("合法零筆"), "zero items is legal: " + f(r, "scan").reason);
        check(f(r, "nearby").readiness == Readiness.READY && f(r, "nearby").work == Work.NONE_PENDING && f(r, "batch").work == Work.NONE_PENDING, "zero items → no pending work");
        r = eval(round("scan_partial_never_published"));
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_scan_not_published") && f(r, "scan").work != Work.HAS_ITEMS, "partial projection is not evidence: " + f(r, "scan").reason);
        r = eval(round("scan_stale_epoch"));
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_scan_old_session") && f(r, "scan").reason.contains("舊 session"), "old-session scan: " + f(r, "scan").reason);
        check(f(r, "nearby").readiness == Readiness.WAITING_INIT && f(r, "batch").readiness == Readiness.WAITING_INIT, "dispatch waits for a new-session scan");
        check(f(r, "return").readiness == Readiness.READY, "return does not need the scan (recaptured managers)");
        // scan older than 30 s in the current session → stale, waiting.
        String h = golden("healthy");
        Map<String, Read> m = round(null); m.put(ReadinessSample.CORE_CAPABILITIES, Read.ok(col(h, 19, Long.toString(NOW - 31_000))));
        r = eval(m);
        check(f(r, "scan").readiness == Readiness.WAITING_INIT && f(r, "scan").codes.contains("caps_scan_stale"), "scan stale: " + f(r, "scan").reason);
    }

    static void pairUnknownAndConflicts() throws Exception {
        // pair unknown: gate already stops at core_pair_unreadable; the capabilities file must not rescue it.
        ReadinessSample.AppFacts noPair = new ReadinessSample.AppFacts("", -1, true, false, false, false, false, "", false, true, "", "", -1, java.util.Arrays.asList("core_pair"));
        Result r = eval(round("healthy"), noPair);
        for (FeatureReadiness x : r.features) check(x.readiness == Readiness.UNKNOWN && x.codes.contains("core_pair_unreadable") && x.next.contains("不要重裝核心"), "pair unknown: " + x.id + " " + x.readiness + " " + x.next);
        // gps flag disagreement between unified_runtime and the capabilities line → conflict, not ready.
        Map<String, Read> m = round("gps_off");
        r = eval(m);
        check(f(r, "gps_copy").readiness == Readiness.UNKNOWN && f(r, "gps_copy").codes.contains("caps_conflict:unified_runtime"), "gps flag conflict: " + f(r, "gps_copy").reason);
        check(f(r, "scan").readiness == Readiness.READY, "gps conflict does not spread");
        m = round("gps_off"); m.put(ReadinessSample.UNIFIED_RUNTIME, Read.ok("v1\t" + (NOW - 900) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t0\n"));
        r = eval(m);
        check(f(r, "gps_copy").readiness == Readiness.WAITING_INIT && f(r, "gps_copy").codes.contains("gps_hook_not_installed"), "both say off → waiting hook");
        // capabilities never override safety: pending blockers still block GPS work even when everything is ready.
        ReadinessSample.AppFacts pending = new ReadinessSample.AppFacts(VER, CODE, true, true, false, false, false, "", false, true, "", "", -1);
        r = eval(round("healthy"), pending);
        check(f(r, "batch").readiness == Readiness.BLOCKED && f(r, "batch").codes.contains("gps_work_blocked_by_pending"), "pending core transaction still blocks batch");
        check(f(r, "scan").readiness == Readiness.READY && f(r, "scan").codes.contains("core_transaction_pending"), "scan ready but carries the blocker code");
        // work ownership: busy owner still reported as WORKING/UNKNOWN, not READY.
        ReadinessSample.AppFacts busy = ReadinessSample.AppFacts.simple(VER, CODE, true, true, "batch");
        r = eval(round("healthy"), busy);
        check(f(r, "batch").readiness == Readiness.UNKNOWN && f(r, "batch").work == Work.WORKING, "busy owner");
    }

    /** The real shell path reads the new file like any other capped source (only when a POSIX sh is available; CI must have one). */
    static void realShellReadsTheFile() throws Exception {
        String sh = ReadinessGatewayTest.findSh();
        if (sh == null) { ReadinessGatewayTest.skip("no POSIX sh on this host (capabilities file read)"); return; }
        Map<String, String> env = ReadinessGatewayTest.toolEnv(sh);
        if (!ReadinessGatewayTest.missingTools(sh, env).isEmpty()) { ReadinessGatewayTest.skip("tools missing (capabilities file read)"); return; }
        Path dir = Files.createTempDirectory("pcc-caps-");
        String base = dir.toAbsolutePath().toString().replace('\\', '/');
        if (base.contains(" ")) { ReadinessGatewayTest.skip("temp path has spaces"); return; }
        try {
            ReadinessGatewayTest.write(dir.resolve("mod/module.prop"), "id=pikmin_nectar_rpc\nversion=" + VER + "\nversionCode=" + CODE + "\n");
            ReadinessGatewayTest.write(dir.resolve("game/core_runtime.tsv"), "v1\t" + (NOW - 1000) + "\t" + PID + "\t" + VER + "\t" + CODE + "\t157.0\n");
            ReadinessGatewayTest.write(dir.resolve("game/core_capabilities.tsv"), golden("healthy"));
            ReadinessGateway.Paths p = new ReadinessGateway.Paths(base + "/game/", base + "/tmp/", base + "/mod", base + "/staged");
            ReadinessSample s = ReadinessGatewayTest.run(sh, env, p, "pidof() { echo " + PID + "; }");
            Read k = s.read(ReadinessSample.CORE_CAPABILITIES);
            check(k.ok() && k.text.equals(golden("healthy").replace("\n", "")), "capabilities file read through the real shell: " + k.state);
            check(CoreCapabilities.parse(k, PID, VER, CODE, NOW).ok(), "…and parses");
            System.out.println("PASS real shell reads core_capabilities.tsv via " + sh);
        } finally { ReadinessGatewayTest.deleteTree(dir); }
    }
}

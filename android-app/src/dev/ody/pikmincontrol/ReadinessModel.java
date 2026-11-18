package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Per-feature readiness judgement over one {@link ReadinessSample}.
 *
 * Pure Java, no Android, no root, no RPC. Every feature is evaluated on its
 * own dependencies; a green light on one feature never implies another. The
 * output separates three dimensions so a switched-off feature cannot hide a
 * broken core and an empty list is never read as a failure:
 * user setting / readiness / work.
 *
 * "Ready" only means the data source and entry point are present, fresh and
 * bound to the current game PID. Sources that carry no PID
 * (dispatch_probe_status, planting_control_status, compatibility_status,
 * gps_copy_status) can only yield "observed, capability unconfirmed"; with an
 * old core (no core_capabilities.tsv) the features that depend on them
 * therefore cannot reach READY and the judgement is exactly the pre-N03 one.
 * With core ≥ 1.5.23 the PID-bound core_capabilities.tsv (see
 * {@link CoreCapabilities}) supplies the missing evidence per feature: stage,
 * domain, resolution steps, captured managers with session attribution and a
 * session-bound scan. A present-but-invalid capabilities file never lets any
 * feature reach READY, and a valid one that contradicts an older file is
 * reported as an inconsistency, not resolved by picking the friendlier side.
 * Evidence is never borrowed from a different file to stand in for a missing
 * PID or session.
 *
 * Freshness limits follow the reader that already consumes each file
 * (CorePair 12 s, NearbyGuard 10 s, PlanterState 60 s, FeedingSnapshot 12 s,
 * SessionIdentity 12 s, RouteState 2 s). Display-only limits new to this
 * diagnostic: dispatch_probe_status 30 s, return_candidates 30 s,
 * planting_control_status 12 s.
 */
final class ReadinessModel {
    private ReadinessModel() {}

    enum Setting { ON, OFF, UNKNOWN, NOT_APPLICABLE, TAKEN_OVER }
    enum Readiness { READY, WAITING_GAME, WAITING_INIT, WAITING_LOCATION, BLOCKED, UNKNOWN, INCOMPATIBLE, CORE_NO_DIAGNOSTIC }
    enum Work { NONE_PENDING, GROWING, NO_MATCH, WORKING, HAS_ITEMS, UNKNOWN, NOT_APPLICABLE }

    static final long CORE_RUNTIME_FRESH_MS = 12_000L;
    static final long PROBE_STATUS_FRESH_MS = 30_000L;
    static final long RETURN_CANDIDATES_FRESH_MS = 30_000L;
    static final long NEARBY_GATE_FRESH_MS = 10_000L;
    static final long PLANTER_FRESH_MS = 60_000L;
    static final long FEEDING_FRESH_MS = 12_000L;
    static final long SESSION_FRESH_MS = 12_000L;
    static final long ROUTE_FRESH_MS = 2_000L;
    static final long RAW_LOCATION_FRESH_MS = 5_000L;
    static final long PLANTING_CONTROL_FRESH_MS = 12_000L;
    static final long CLOCK_SLACK_MS = 5_000L;
    static final String EXPECTED_GAME = "157.0";

    static final class FeatureReadiness {
        final String id, label;
        final Setting setting;
        final Readiness readiness;
        final Work work;
        final String reason;      // one primary Chinese reason
        final String next;        // navigation hint only; never an action that consumes items
        final List<String> codes; // every contributing reason code, most important first
        final long dataAt;        // newest PID-bound game-side timestamp used, 0 when none
        final List<String> evidence;
        FeatureReadiness(String id, String label, Setting setting, Readiness readiness, Work work,
                         String reason, String next, List<String> codes, long dataAt, List<String> evidence) {
            this.id = id; this.label = label; this.setting = setting; this.readiness = readiness; this.work = work;
            this.reason = reason; this.next = next; this.codes = Collections.unmodifiableList(new ArrayList<>(codes));
            this.dataAt = dataAt; this.evidence = Collections.unmodifiableList(new ArrayList<>(evidence));
        }
        String line() {
            return label + "：" + readinessLabel(readiness)
                    + (setting == Setting.ON ? "（開）" : setting == Setting.OFF ? "（關）" : setting == Setting.TAKEN_OVER ? "（暫時接管）" : setting == Setting.UNKNOWN ? "（設定未知）" : "")
                    + (work == Work.NOT_APPLICABLE || work == Work.UNKNOWN ? "" : " · " + workLabel(work));
        }
    }

    /** Process-level facts shared by every feature. */
    static final class CoreFacts {
        String pid = ""; long roundNow;
        boolean roundInvalid, roundTimedOut, pidUnstable, gameRunning, pidReadFailed, moduleReadFailed;
        String installedVersion = ""; int installedCode = -1; boolean installedKnown, disabled, stagedPending;
        String compat = "unknown"; long compatObservedSize = -1; boolean compatConflict;
        boolean pairKnown;              // the APK's expected pair (version/code) was read; without it no mismatch can be claimed
        boolean coreLoaded, coreLoadedOtherVersion, coreLoadedUnverified, coreStale; String loadedVersion = ""; long coreAt;
        boolean gpsLoaded, gpsStale, gpsOtherVersion, gpsUnverified; long gpsAt;
        boolean probeObserved, probeFresh; long probeAt; int probeCount = -1; String probeMode = ""; String probeResult = "";
        boolean sessionFresh, sessionReady, sessionIdle; long sessionAt;
        CoreCapabilities caps = CoreCapabilities.parse(ReadinessSample.Read.missing(), "", "", -1, 0);   // MISSING until parsed
        final List<String> blockers = new ArrayList<>(); // pending transactions / unfinished stops, read-only
        final List<String> blockersUnreadable = new ArrayList<>(); // facts whose read threw: pending state UNKNOWN, not "none"
        final List<String> notes = new ArrayList<>();
    }

    static final class Result {
        final CoreFacts core;
        final List<FeatureReadiness> features;
        final String coreSummary;
        final long sampleCompletedAt, sampleElapsedMs;
        Result(CoreFacts core, List<FeatureReadiness> features, String coreSummary, long sampleCompletedAt, long sampleElapsedMs) {
            this.core = core; this.features = Collections.unmodifiableList(features); this.coreSummary = coreSummary;
            this.sampleCompletedAt = sampleCompletedAt; this.sampleElapsedMs = sampleElapsedMs;
        }
        /** One-line aggregate for the status row. */
        String oneLine() {
            int ready = 0, waiting = 0, blocked = 0, unknown = 0;
            for (FeatureReadiness f : features) {
                switch (f.readiness) {
                    case READY: ready++; break;
                    case WAITING_GAME: case WAITING_INIT: case WAITING_LOCATION: waiting++; break;
                    case BLOCKED: case INCOMPATIBLE: blocked++; break;
                    default: unknown++;
                }
            }
            return "功能就緒：" + ready + " 項就緒・" + waiting + " 項等待・" + blocked + " 項受阻・" + unknown + " 項待核對";
        }
        boolean warning() {
            for (FeatureReadiness f : features) if (f.readiness == Readiness.BLOCKED || f.readiness == Readiness.INCOMPATIBLE) return true;
            return false;
        }
    }

    /** Result for a round that produced no sample at all (sampler threw, executor rejected). */
    static Result unavailable(String why, long at) {
        ReadinessSample s = ReadinessSample.fromSections(null, false, false, at, ReadinessSample.AppFacts.simple("", -1, false, false, ""));
        Result r = evaluate(s);
        return new Result(r.core, r.features, "本輪檢查未執行：" + why + "。請再檢查一次", at, 0);
    }

    // ---------------------------------------------------------------- core

    static CoreFacts core(ReadinessSample s) {
        CoreFacts c = new CoreFacts();
        long now = s.completedAt; c.roundNow = now;
        c.roundInvalid = !s.roundOk; c.roundTimedOut = s.roundTimedOut;
        c.pidReadFailed = !s.read(ReadinessSample.PID_BEFORE).ok() || !s.read(ReadinessSample.PID_AFTER).ok();
        ReadinessSample.ReadState ms = s.read(ReadinessSample.MODULE).state;
        c.moduleReadFailed = ms != ReadinessSample.ReadState.OK && ms != ReadinessSample.ReadState.MISSING;
        c.pidUnstable = s.pidChanged();
        c.gameRunning = s.gameRunning();
        c.pid = s.stablePid();
        boolean factsFailed = false;
        for (String u : s.app.unreadable) if (u.equals("core_pair") || u.startsWith("app_facts:")) factsFailed = true;
        c.pairKnown = !factsFailed && !s.app.pairVersion.isEmpty() && s.app.pairCode > 0;

        ReadinessSample.Read module = s.read(ReadinessSample.MODULE);
        if (module.ok()) {
            for (String line : module.text.split("\n")) {
                String l = line.trim();
                if (l.startsWith("version=")) { c.installedVersion = l.substring(8).trim(); c.installedKnown = !c.installedVersion.isEmpty(); }
                else if (l.startsWith("versionCode=")) c.installedCode = parseInt(l.substring(12).trim(), -1);
                else if (l.equals("disabled=yes")) c.disabled = true;
                else if (l.equals("staged=yes")) c.stagedPending = true;
            }
        }

        ReadinessSample.Read compat = s.read(ReadinessSample.COMPATIBILITY);
        if (compat.ok() && !compat.trimmed().isEmpty()) {
            String[] f = compat.trimmed().split("\t", -1);
            if (f.length >= 4 && f[0].equals("v157.0")) { c.compat = f[1]; c.compatObservedSize = parseLong(f[3], -1); }
            else c.compat = "malformed";
        } else if (compat.state == ReadinessSample.ReadState.MISSING) c.compat = "missing";

        if (!c.pid.isEmpty() && !c.pidUnstable) {
            ReadinessSample.Read rt = s.read(ReadinessSample.CORE_RUNTIME);
            if (rt.ok() && !rt.trimmed().isEmpty()) {
                String[] f = rt.trimmed().split("\t", -1);
                if (f.length == 6 && f[0].equals("v1") && f[2].equals(c.pid)) {
                    long at = parseLong(f[1], -1);
                    c.coreAt = at;
                    if (fresh(at, now, CORE_RUNTIME_FRESH_MS)) {
                        c.loadedVersion = f[3];
                        if (!c.pairKnown) c.coreLoadedUnverified = true;   // observed, but nothing to compare against
                        else {
                            boolean pairMatch = f[3].equals(s.app.pairVersion) && f[4].equals(Integer.toString(s.app.pairCode)) && f[5].equals(EXPECTED_GAME);
                            c.coreLoaded = pairMatch; c.coreLoadedOtherVersion = !pairMatch;
                        }
                    } else c.coreStale = true;
                } else if (f.length == 6 && f[0].equals("v1")) c.coreStale = true; // old PID
            }
            // A fresh PID-bound runtime outranks the PID-less compatibility file; a conflict is reported, not resolved by guessing.
            if ((c.coreLoaded || c.coreLoadedOtherVersion || c.coreLoadedUnverified) && c.compat.equals("incompatible")) { c.compatConflict = true; c.notes.add("compat_conflict_runtime_fresh"); }

            ReadinessSample.Read ut = s.read(ReadinessSample.UNIFIED_RUNTIME);
            if (ut.ok() && !ut.trimmed().isEmpty()) {
                String[] f = ut.trimmed().split("\t", -1);
                if (f.length == 6 && f[0].equals("v1") && f[2].equals(c.pid)) {
                    long at = parseLong(f[1], -1);
                    c.gpsAt = at;
                    if (!fresh(at, now, CORE_RUNTIME_FRESH_MS)) c.gpsStale = true;
                    else if (!c.pairKnown) c.gpsUnverified = true;
                    else if (!f[3].equals(s.app.pairVersion) || !f[4].equals(Integer.toString(s.app.pairCode))) c.gpsOtherVersion = true;
                    else c.gpsLoaded = f[5].equals("1");
                } else if (f.length == 6) c.gpsStale = true;
            }
            c.caps = CoreCapabilities.parse(s.read(ReadinessSample.CORE_CAPABILITIES), c.pid, c.pairKnown ? s.app.pairVersion : "", c.pairKnown ? s.app.pairCode : -1, now);
            if (c.caps.ok() && c.gpsLoaded != c.caps.gps) c.notes.add("gps_flag_disagrees_unified_runtime");
            ReadinessSample.Read ps = s.read(ReadinessSample.DISPATCH_PROBE_STATUS);
            if (ps.ok() && !ps.trimmed().isEmpty()) {
                String[] f = ps.trimmed().split("\t", -1);
                if (f.length >= 4) {
                    long at = parseLong(f[0], -1);
                    c.probeObserved = at > 0; c.probeAt = at; c.probeMode = f[1]; c.probeCount = parseInt(f[2], -1); c.probeResult = f[3];
                    c.probeFresh = fresh(at, now, PROBE_STATUS_FRESH_MS);
                }
            }
            ReadinessSample.Read se = s.read(ReadinessSample.SESSION);
            if (se.ok() && !se.trimmed().isEmpty()) {
                String[] f = se.trimmed().split("\t", -1);
                if (f.length == 7 && f[0].equals("v1") && f[2].equals(c.pid) && f[3].matches("[a-f0-9]{64}") && f[6].equals("end")
                        && fresh(parseLong(f[1], -1), now, SESSION_FRESH_MS)) {
                    c.sessionFresh = true; c.sessionReady = f[4].equals("ready"); c.sessionIdle = f[5].equals("idle"); c.sessionAt = parseLong(f[1], -1);
                }
            }
        }
        if (s.app.corePending) c.blockers.add("core_transaction_pending");
        if (s.app.setupPending) c.blockers.add("setup_install_pending");
        if (s.app.updatePending) c.blockers.add("apk_update_pending");
        if (s.app.routePending) c.blockers.add("route_cleanup_pending");
        if (!s.app.stopAllError.isEmpty()) c.blockers.add("stop_all_error");
        if (s.app.stopIncomplete) c.blockers.add("stop_incomplete");
        c.blockersUnreadable.addAll(s.app.unreadable);
        return c;
    }

    static String blockerLabel(String code) {
        switch (code) {
            case "core_transaction_pending": return "核心更新待重開／核對";
            case "setup_install_pending": return "裝置部署待核對";
            case "apk_update_pending": return "APK 更新安裝確認中";
            case "route_cleanup_pending": return "前次沿路工作待清理";
            case "stop_all_error": return "停止全部尚未確認";
            case "stop_incomplete": return "前次工作停止未完成";
            default: return code;
        }
    }

    static String coreSummary(CoreFacts c, ReadinessSample s) {
        if (c.roundTimedOut) return "本輪讀取逾時（超過 " + (ReadinessSample.ROUND_DEADLINE_MS / 1000) + " 秒），不作判定，請再檢查一次";
        if (c.roundInvalid) return "本輪讀取未完成（截斷或框架損壞），不作判定，請再檢查一次";
        if (c.pidReadFailed) return "未找到或無法確認遊戲程序（pidof 未回報 PID），本輪不作判定；不代表遊戲未開";
        if (c.pidUnstable) return "讀取期間遊戲程序改變，本輪證據作廢，請再檢查一次";
        StringBuilder b = new StringBuilder();
        b.append("套件：").append(c.moduleReadFailed ? "無法讀取" : c.installedKnown ? c.installedVersion + (c.disabled ? "（已停用）" : "") : "未安裝");
        if (c.stagedPending) b.append("，另有待重開的新核心");
        b.append("　配對：").append(c.pairKnown ? s.app.pairVersion : "期望值讀取失敗（無法核對）");
        b.append("\n本次程序：");
        if (!c.gameRunning) b.append("遊戲未開啟");
        else if (c.coreLoaded) b.append("已載入配對核心");
        else if (c.coreLoadedUnverified) b.append("已載入核心 ").append(c.loadedVersion).append("（配對期望值未知，未核對）");
        else if (c.coreLoadedOtherVersion) b.append("已載入其他版本 ").append(c.loadedVersion);
        else if (c.caps.ok()) b.append(c.coreStale ? "核心回報過期或屬舊程序" : "core_runtime 尚未寫入").append("；本程序能力回報：").append(capsSummary(c.caps));
        else if (c.coreStale) b.append("核心回報過期或屬舊程序");
        else if (c.compat.equals("incompatible")) b.append("相容檔記錄不相容（該檔無 PID，屬上次掛接嘗試）");
        else b.append("等待核心回報（登入／地圖回呼後才寫入）");
        if (c.compatConflict) b.append("　注意：相容檔為不相容但本程序有新鮮回報，兩來源不一致");
        if (c.gameRunning && (c.coreLoaded || c.coreLoadedOtherVersion || c.coreLoadedUnverified))
            b.append("　GPS：").append(c.gpsLoaded ? "同程序就緒" : c.gpsUnverified ? "回報存在，配對未核對" : c.gpsOtherVersion ? "loader 版本與配對不符" : c.gpsStale ? "回報過期" : "尚未安裝 hook");
        if (c.gameRunning && (c.coreLoaded || c.coreLoadedOtherVersion || c.coreLoadedUnverified) && c.caps.present()) b.append("　能力回報：").append(capsSummary(c.caps));
        if (!c.blockers.isEmpty()) { b.append("\n待核對："); for (int i = 0; i < c.blockers.size(); i++) b.append(i > 0 ? "、" : "").append(blockerLabel(c.blockers.get(i))); }
        if (!c.blockersUnreadable.isEmpty()) b.append("\n阻擋狀態讀取失敗（視為未知，不視為無阻擋）：").append(String.join("、", c.blockersUnreadable));
        return b.toString();
    }

    /** One clause for the core summary: what core_capabilities.tsv says about this process. */
    static String capsSummary(CoreCapabilities k) {
        if (!k.ok()) return CoreCapabilities.statusLabel(k.status);
        StringBuilder b = new StringBuilder();
        b.append("階段 ").append(k.stage);
        if (!k.failed.equals("-")) b.append("（").append(k.failed).append(k.failedDetail != 0 ? ":" + k.failedDetail : "").append("）");
        b.append("，domain ").append(k.domain).append("，帳號 ").append(k.identity.equals("ready") ? "就緒" : k.identity.equals("logged_out") ? "已登出" : "未知");
        int failed = 0, blocked = 0, pending = 0;
        for (CoreCapabilities.Step st : k.steps.values()) { if (st.state.equals("failed")) failed++; else if (st.state.equals("blocked")) blocked++; else if (st.state.equals("-")) pending++; }
        if (failed + blocked + pending > 0) b.append("，解析步驟 失敗 ").append(failed).append("／受阻 ").append(blocked).append("／未到 ").append(pending);
        int stale = k.stale;
        if (stale != 0) { b.append("，未重新捕捉："); boolean first = true; for (int bit = 1; bit <= CoreCapabilities.CAP_ALL; bit <<= 1) if ((stale & bit) != 0) { b.append(first ? "" : "、").append(CoreCapabilities.capName(bit)); first = false; } }
        return b.toString();
    }

    // ------------------------------------------------------------ features

    static Result evaluate(ReadinessSample s) {
        CoreFacts c = core(s);
        List<FeatureReadiness> out = new ArrayList<>();
        out.add(scan(s, c));
        out.add(nearby(s, c));
        out.add(batch(s, c));
        out.add(autoReturn(s, c));
        out.add(planter(s, c));
        out.add(feeding(s, c));
        out.add(farmPoint(s, c));
        out.add(farmRoute(s, c));
        out.add(gpsCopy(s, c));
        return new Result(c, out, coreSummary(c, s), s.completedAt, s.elapsedMs);
    }

    private static final class Build {
        final String id, label; Setting setting = Setting.NOT_APPLICABLE; Readiness readiness = Readiness.UNKNOWN; Work work = Work.NOT_APPLICABLE;
        String reason = "", next = ""; final List<String> codes = new ArrayList<>(); final List<String> evidence = new ArrayList<>(); long dataAt;
        String capsInvalid = "";   // R3: label of a present-but-unusable capabilities file; caps READY at done(), keeps every other state
        Build(String id, String label) { this.id = id; this.label = label; }
        Build code(String code) { codes.add(code); return this; }
        Build set(Readiness r, String reason, String next, String code) { this.readiness = r; this.reason = reason; this.next = next; codes.add(code); return this; }
        Build ev(String e) { evidence.add(e); return this; }
        FeatureReadiness done() {
            if (!capsInvalid.isEmpty() && readiness == Readiness.READY) {
                readiness = Readiness.UNKNOWN;
                reason = capsInvalid + "，不能判就緒；自有證據：" + reason;
                next = next.isEmpty() ? "再檢查一次" : next;
            }
            return new FeatureReadiness(id, label, setting, readiness, work, reason, next, codes, dataAt, evidence);
        }
    }

    /** Reads a control file into a Setting: read failures are UNKNOWN, a missing file is the documented default. */
    private static Setting setting(ReadinessSample.Read r, String onValue, String offDefault) {
        if (r.state == ReadinessSample.ReadState.MISSING) return offDefault.equals("on") ? Setting.ON : Setting.OFF;
        if (!r.ok()) return Setting.UNKNOWN;
        String v = r.trimmed();
        if (v.isEmpty()) return Setting.UNKNOWN;
        return v.equals(onValue) ? Setting.ON : Setting.OFF;
    }

    /** Shared gate. Returns true when the feature must stop here. */
    private static boolean gate(Build b, CoreFacts c, ReadinessSample s) {
        if (c.roundTimedOut) { b.set(Readiness.UNKNOWN, s.skipReason.isEmpty() ? "本輪讀取逾時" : "本輪未執行讀取：" + s.skipReason, "再檢查一次", s.skipReason.isEmpty() ? "round_timeout" : "round_budget_exhausted"); return true; }
        if (c.roundInvalid) { b.set(Readiness.UNKNOWN, "本輪讀取未完成", "再檢查一次", "round_incomplete"); return true; }
        if (c.pidReadFailed) { b.set(Readiness.UNKNOWN, "未找到或無法確認遊戲程序（pidof 未回報 PID；本檢查不推論遊戲未開）", "開啟遊戲後再檢查；持續則確認 Root 授權", "pid_read_failed"); return true; }
        if (c.pidUnstable) { b.set(Readiness.UNKNOWN, "讀取期間遊戲程序改變，本輪證據作廢", "再檢查一次", "pid_changed"); return true; }
        if (c.moduleReadFailed) { b.set(Readiness.UNKNOWN, "核心套件資訊讀取失敗", "確認 Root 授權後再檢查", "module_read_failed"); return true; }
        if (!c.installedKnown) { b.set(Readiness.BLOCKED, "核心套件未安裝", "到系統頁核心區安裝或核對", "core_not_installed"); return true; }
        if (c.disabled) { b.set(Readiness.BLOCKED, "核心模組已在 Magisk 停用", "到 Magisk 模組頁啟用後重開", "core_disabled"); return true; }
        if (!c.gameRunning) {
            b.set(Readiness.WAITING_GAME, c.stagedPending ? "遊戲未開啟；另有新核心待重開機" : "遊戲未開啟", "開啟 Pikmin Bloom 後再檢查",
                    c.stagedPending ? "core_pending_reboot" : "game_not_running"); return true;
        }
        // PID-bound runtime first; the PID-less compatibility file only speaks when the core has not reported.
        if (c.coreLoadedUnverified) {
            b.set(Readiness.UNKNOWN, "本程序已載入核心 " + c.loadedVersion + "，但本 APK 的配對期望值讀取失敗，無法核對是否相符（不是版本不符）",
                    "重開 App 後再檢查；不要重裝核心", "core_pair_unreadable");
            for (String name : c.blockersUnreadable) b.code("pending_state_unreadable:" + name);
            return true;
        }
        if (c.coreLoadedOtherVersion) {
            b.set(Readiness.BLOCKED, "本次程序載入的是核心 " + c.loadedVersion + "，不是本 APK 配對的 " + s.app.pairVersion,
                    c.stagedPending ? "新核心待重開機，重開後再檢查" : "到系統頁核心區核對", c.stagedPending ? "core_pending_reboot" : "core_version_mismatch");
            if (c.compatConflict) b.code("compat_conflict_runtime_fresh");
            return true;
        }
        if (!c.coreLoaded) {
            // R2: a valid, PID-bound, pair-matched capabilities line can explain WHY core_runtime is absent
            // (bootstrap failure, domain pending, before the map callback) without ever standing in for it:
            // coreLoaded stays false, GPS is not claimed, and the feature stops here.
            if (c.caps.ok()) {
                b.ev("core_capabilities@" + c.caps.epochMs + "#" + c.caps.seq); b.dataAt = Math.max(b.dataAt, c.caps.epochMs);
                b.code(c.coreStale ? "core_runtime_stale" : "core_runtime_missing");
                if (c.compat.equals("incompatible") && !c.caps.stage.equals("incompatible")) b.code("compat_file_incompatible_but_caps_disagree");
                if (!capsInit(b, c.caps)) return true;
                b.set(Readiness.WAITING_INIT, (c.coreStale ? "核心回報過期或屬舊程序" : "core_runtime 尚未寫入") + "；能力回報顯示 hook 已呼叫、domain 就緒（core_runtime 在地圖回呼後才每秒寫入）",
                        "進入遊戲地圖後再檢查", "core_runtime_pending_caps_ready");
                return true;
            }
            if (c.coreStale) b.set(Readiness.WAITING_INIT, "核心回報過期或屬舊程序", "切回遊戲畫面等幾秒再檢查", "core_runtime_stale");
            else if (c.compat.equals("incompatible")) b.set(Readiness.INCOMPATIBLE, "相容檔記錄 libil2cpp 大小 " + c.compatObservedSize + " 不符（該檔無 PID，屬上次掛接嘗試），本程序無核心回報", "等待相容核心；不要重複安裝", "game_incompatible_last_attempt");
            else b.set(Readiness.WAITING_INIT, "核心尚未回報（登入／地圖回呼後才寫入）", "進入遊戲地圖後再檢查", "core_runtime_missing");
            return true;
        }
        if (c.compatConflict) b.code("compat_conflict_runtime_fresh");
        for (String blocker : c.blockers) b.code(blocker);
        for (String name : c.blockersUnreadable) b.code("pending_state_unreadable:" + name);
        b.dataAt = c.coreAt; b.ev("core_runtime@" + c.coreAt);
        return false;
    }

    /** GPS-work features are refused by GpsWorkLock while any of these is pending; report it, never clear it. */
    private static boolean gpsWorkBlocked(Build b, CoreFacts c) {
        if (!c.blockersUnreadable.isEmpty()) {
            b.set(Readiness.UNKNOWN, "安全阻擋狀態讀取失敗（" + String.join("、", c.blockersUnreadable) + "），不能當作沒有阻擋", "重開 App 後再檢查；持續則回報", "pending_state_unreadable");
            return true;
        }
        if (c.blockers.isEmpty()) return false;
        StringBuilder names = new StringBuilder();
        for (String code : c.blockers) names.append(names.length() > 0 ? "、" : "").append(blockerLabel(code));
        b.set(Readiness.BLOCKED, "受既有安全阻擋：" + names, "到對應頁面完成核對；本檢查不會清除", "gps_work_blocked_by_pending");
        return true;
    }

    // ------------------------------------------------ core_capabilities (core ≥ 1.5.23)

    /** How the capabilities file takes part in a feature's judgement. INVALID is folded into LEGACY plus a READY cap (see Build.capsInvalid). */
    enum CapsMode { LEGACY, VALID }

    /**
     * Decides the mode once per feature. LEGACY (file missing) keeps the
     * pre-N03 judgement untouched apart from a marker code. INVALID (present
     * but not usable: unreadable, truncated, malformed, unknown schema, old
     * PID, version/game mismatch, pair unknown, stale, future) runs the LEGACY
     * judgement so independent safety facts and work states (pending review,
     * uncertain sends, safety stops) stay visible, but Build.done() caps the
     * outcome: such a file must not let anything be READY. VALID adds the file
     * as PID-bound evidence.
     */
    private static CapsMode capsMode(Build b, CoreFacts c) {
        CoreCapabilities k = c.caps;
        if (k.status == CoreCapabilities.Status.MISSING) { b.code("core_capabilities_missing"); return CapsMode.LEGACY; }
        if (!k.ok()) {
            String code = "core_capabilities_" + k.status.name().toLowerCase();
            String next = k.status == CoreCapabilities.Status.VERSION_UNVERIFIED ? "重開 App 後再檢查；不要重裝核心"
                    : k.status == CoreCapabilities.Status.VERSION_MISMATCH ? "到系統頁核心區核對" : "再檢查一次";
            b.code(code);
            b.capsInvalid = CoreCapabilities.statusLabel(k.status) + (k.detail.isEmpty() ? "" : "（" + k.detail + "）") + "；" + next;
            return CapsMode.LEGACY;
        }
        b.ev("core_capabilities@" + k.epochMs + "#" + k.seq); b.dataAt = Math.max(b.dataAt, k.epochMs);
        if (c.coreLoaded && !k.stage.equals("hooks_called")) b.code("caps_conflict:core_runtime");   // runtime is written by a hook, yet the line says hooks were not called
        return CapsMode.VALID;
    }

    /** Bootstrap stage and domain: everything before the resolution chain. Returns false when the feature must stop. */
    private static boolean capsInit(Build b, CoreCapabilities k) {
        switch (k.stage) {
            case "boot": case "lib_wait": b.set(Readiness.WAITING_INIT, "核心等待 libil2cpp 載入", "保持遊戲開啟；新版核心會低頻重試", "caps_stage_" + k.stage); return false;
            case "lib_timeout": b.set(Readiness.BLOCKED, "核心等待程式庫已逾時，初始化未完成", "開啟程序守護可有限次復原，或手動重開遊戲", "caps_stage_lib_timeout"); return false;
            case "symbols_missing": b.set(Readiness.BLOCKED, "IL2CPP 符號無法解析，本程序未安裝 hook", "重開遊戲後再檢查；持續則回報", "caps_stage_symbols_missing"); return false;
            case "incompatible": b.set(Readiness.INCOMPATIBLE, "本程序 libil2cpp 大小 " + k.failedDetail + " 與核心支援的版本不符（本程序回報）", "等待相容核心；不要重複安裝", "caps_stage_incompatible"); return false;
            default: break;
        }
        if (!k.domainReady()) { b.set(Readiness.WAITING_INIT, "等待登入／地圖回呼（IL2CPP domain 尚未就緒，會重試）", "登入並進入地圖後再檢查", "caps_domain_pending"); return false; }
        return true;
    }

    /** Every named step must be ok. failed/blocked are permanent for this process; "-" is still initialising. */
    private static boolean capsSteps(Build b, CoreCapabilities k, String... names) {
        for (String name : names) {
            CoreCapabilities.Step st = k.step(name);
            if (st == null || st.ok()) continue;
            if (st.state.equals("-")) b.set(Readiness.WAITING_INIT, "等待初始化：" + st.problem(), "進入遊戲地圖後再檢查", "caps_step_pending:" + name);
            else b.set(Readiness.BLOCKED, "受阻：" + st.problem(), "重開遊戲後再檢查；持續則回報（本程序不重試）", "caps_step_" + st.state + ":" + name);
            return false;
        }
        return true;
    }

    /** Session gate: a logged-out identity invalidates every per-session capability and scan. */
    private static boolean capsSession(Build b, CoreCapabilities k) {
        if (k.loggedOut()) { b.set(Readiness.WAITING_INIT, "帳號已登出（本程序尚未重新登入）", "登入完成後再檢查", "caps_logged_out"); return false; }
        return true;
    }

    /** Named capability bits must be captured; per-session bits must also be captured in the current session. */
    private static boolean capsLive(Build b, CoreCapabilities k, int... bits) {
        for (int bit : bits) {
            String name = CoreCapabilities.capName(bit);
            if (!k.has(bit)) { b.set(Readiness.WAITING_INIT, "能力尚未解析／捕捉：" + name, "進入遊戲地圖後再檢查；持續則重開遊戲", "caps_missing:" + name); return false; }
            if (!k.live(bit)) { b.set(Readiness.UNKNOWN, "本 session 未重新捕捉 " + name + "（登出／重登入後遊戲未重建，指標非空不算確認），待核對", "重開遊戲後再檢查", "caps_stale:" + name); return false; }
        }
        return true;
    }

    /**
     * R3: a feature-owned report that is fresh and same-PID may still belong to the
     * previous account when the core re-logged in inside the freshness window. With a
     * valid capabilities line, data stamped before last_login_ms is refused. This is a
     * necessary check only: a timestamp after login is not proof of the same session
     * (the old TSVs carry no session token), so the caller must not claim more than it has.
     */
    private static boolean dataBeforeLogin(Build b, CoreFacts c, long at, String file) {
        CoreCapabilities k = c.caps;
        if (!k.ok() || k.sessionGen == 0 || at <= 0 || at >= k.lastLoginMs) return false;
        b.set(Readiness.UNKNOWN, file + " 回報時間早於本 session 登入（登入 " + age(k.lastLoginMs, c.roundNow) + "，回報 " + age(at, c.roundNow) + "），屬舊帳號／舊 session 的資料，待核對",
                "登入完成後等幾秒再檢查", "caps_data_before_login:" + file);
        return true;
    }

    /** Session-bound scan evidence. Returns false when the feature must stop. */
    private static boolean capsScan(Build b, CoreCapabilities k, long now) {
        if (k.scanCount < 0) { b.set(Readiness.WAITING_INIT, "本程序尚未發布完整的探險清單掃描", "進入遊戲地圖幾秒後再檢查", "caps_scan_not_published"); return false; }
        if (k.scanEpoch != k.sessionEpoch) { b.set(Readiness.WAITING_INIT, "掃描證據屬舊 session（登入／登出後尚未重新掃描）", "停在遊戲地圖幾秒後再檢查", "caps_scan_old_session"); return false; }
        if (!k.scanCurrent(now)) { b.set(Readiness.WAITING_INIT, "本程序掃描回報過期（" + age(k.scanMs, now) + "）", "切回遊戲地圖後再檢查", "caps_scan_stale"); return false; }
        b.ev("scan@" + k.scanMs + "(epoch " + k.scanEpoch + ")"); b.dataAt = Math.max(b.dataAt, k.scanMs);
        return true;
    }

    /** Scan prerequisites shared by scan / nearby / batch / farm: chain, session, preparer, session-bound scan. */
    private static boolean capsScanReady(Build b, CoreFacts c, long now) {
        CoreCapabilities k = c.caps;
        if (!capsInit(b, k) || !capsSteps(b, k, "return.task_list_time") || !capsSession(b, k)
                || !capsLive(b, k, CoreCapabilities.CAP_PREPARER) || !capsScan(b, k, now)) return false;
        if (!c.probeFresh) b.code("probe_status_disagrees");   // PID-less observation missing/stale while the PID-bound scan is current: shown, not hidden
        return true;
    }

    /** Dispatch prerequisites on top of the scan: expedition methods, availability (base+selected), picker. Gift is optional. */
    private static boolean capsDispatchReady(Build b, CoreCapabilities k) {
        if (!capsSteps(b, k, "expedition.methods", "availability.base", "availability.selected", "expedition.pick_fastest")) return false;
        if (!capsLive(b, k, CoreCapabilities.CAP_EXPEDITION_START, CoreCapabilities.CAP_AVAILABILITY_SELECTED, CoreCapabilities.CAP_PICK_FASTEST)) return false;
        if (!k.has(CoreCapabilities.CAP_AVAILABILITY_GIFT)) b.code("availability_gift_missing");   // only gift-box tasks are affected; fruit/seed stay eligible
        return true;
    }

    /**
     * For features that already have PID-bound evidence of their own: the
     * capabilities file must agree. When its chain fails while the feature's
     * own file is fresh, the two sources disagree; that is reported as an
     * inconsistency (UNKNOWN), never resolved by trusting the friendlier one.
     */
    private static void capsConflict(Build b, String ownFile) {
        b.readiness = Readiness.UNKNOWN;
        b.reason = "能力回報與 " + ownFile + " 不一致：" + b.reason + "，但 " + ownFile + " 新鮮且屬本程序；不採信任一方";
        b.next = "再檢查一次；持續則回報";
        b.code("caps_conflict:" + ownFile);
    }

    /**
     * dispatch_probe_status carries no PID, so it can only show that a scan
     * was observed recently; it cannot prove that this process can enumerate.
     * Returns true when observation is fresh; the caller must still not claim READY.
     */
    private static boolean scanObserved(Build b, CoreFacts c, long now) {
        if (c.probeFresh) { b.ev("dispatch_probe_status@" + c.probeAt + "(no-pid)"); b.code("capability_unconfirmed_no_pid"); return true; }
        if (c.probeObserved) b.set(Readiness.WAITING_INIT, "探險清單掃描回報已過期（最後 " + age(c.probeAt, now) + "，該檔無 PID）", "切回遊戲地圖後再檢查", "scan_heartbeat_stale");
        else b.set(Readiness.WAITING_INIT, "尚未觀察到探險清單掃描；核心目前不回報初始化細節", "進入遊戲地圖後再檢查", "scan_heartbeat_missing");
        b.code("core_lacks_init_diagnostic");
        return false;
    }

    static FeatureReadiness scan(ReadinessSample s, CoreFacts c) {
        Build b = new Build("scan", "探險掃描");
        if (gate(b, c, s)) return b.done();
        CapsMode mode = capsMode(b, c);
        if (mode == CapsMode.VALID) {
            if (!capsScanReady(b, c, s.completedAt)) return b.done();
            CoreCapabilities k = c.caps;
            b.readiness = Readiness.READY; b.code("scan_ready");
            // R5: scan_count is the size of the complete task list (mushrooms, already-dispatched and other
            // non-dispatchable entries included). Zero means nothing at all; non-zero says nothing about
            // dispatchable targets, so the work state stays UNKNOWN; the PID-less probe count is only surfaced as a code.
            b.work = k.scanCount == 0 ? Work.NONE_PENDING : Work.UNKNOWN;
            b.reason = "本程序已完成完整任務清單掃描（" + age(k.scanMs, s.completedAt) + "，" + (k.scanCount == 0 ? "合法零筆" : "完整任務清單 " + k.scanCount + " 筆，含非待派遣項目") + "，屬目前 session）；就緒不代表有可派遣目標";
            if (k.scanCount > 0) {
                b.code("scan_count_is_task_list_size");
                if (c.probeFresh && c.probeCount >= 0) b.code(c.probeCount == 0 ? "probe_candidates_zero(no-pid)" : "probe_candidates_" + c.probeCount + "(no-pid)");
            }
            return b.done();
        }
        if (!scanObserved(b, c, s.completedAt)) return b.done();
        b.readiness = Readiness.UNKNOWN;
        b.code("scan_observed_unconfirmed");
        String count = c.probeCount == 0 ? "目前 0 筆（合法零筆）" : c.probeCount > 0 ? "目前 " + c.probeCount + " 筆" : "筆數無法辨識";
        b.reason = "已觀察到掃描回報（" + age(c.probeAt, s.completedAt) + "，" + count + "），但該回報無 PID，無法證明屬本程序；本核心無法確認列舉能力";
        b.next = "需要 B04 的 core_capabilities 回報才能判就緒";
        b.work = c.probeCount == 0 ? Work.NONE_PENDING : c.probeCount > 0 ? Work.HAS_ITEMS : Work.UNKNOWN;
        return b.done();
    }

    static FeatureReadiness nearby(ReadinessSample s, CoreFacts c) {
        Build b = new Build("nearby", "附近自動派遣");
        ReadinessSample.Read m = s.read(ReadinessSample.CTRL_DISPATCH_MODE);
        String mode = m.ok() ? m.trimmed() : "";
        if (mode.equals("batch") || mode.equals("route")) {
            b.setting = Setting.TAKEN_OVER; b.code("dispatch_mode_" + mode);
            if (!s.app.nearbyOriginal.isEmpty()) b.code("nearby_original_" + s.app.nearbyOriginal);
        } else b.setting = setting(m, "armed", "off");
        if (gate(b, c, s)) return b.done();
        CapsMode caps = capsMode(b, c);
        if (caps == CapsMode.VALID) {
            if (!capsScanReady(b, c, s.completedAt) || !capsDispatchReady(b, c.caps) || !capsLive(b, c.caps, CoreCapabilities.CAP_LOCATION_CONTROLLER)) return b.done();
        } else if (!scanObserved(b, c, s.completedAt)) return b.done();
        ReadinessSample.Read g = s.read(ReadinessSample.NEARBY_GATE);
        String[] f = g.ok() ? g.trimmed().split("\t", -1) : new String[0];
        if (f.length != 12 || !(f[0].equals("v1") || f[0].equals("v2")) || !f[2].equals(c.pid)) {
            if (g.state == ReadinessSample.ReadState.MISSING) b.set(Readiness.CORE_NO_DIAGNOSTIC, "核心未提供附近派遣定位回報", "確認核心版本 ≥ 1.4.23", "nearby_gate_missing");
            else b.set(Readiness.UNKNOWN, "附近派遣定位回報屬舊程序或格式不符", "再檢查一次", "nearby_gate_invalid");
            return b.done();
        }
        long at = parseLong(f[1], -1);
        if (!fresh(at, s.completedAt, NEARBY_GATE_FRESH_MS)) { b.set(Readiness.UNKNOWN, "附近派遣定位回報過期（" + age(at, s.completedAt) + "）", "再檢查一次", "nearby_gate_stale"); return b.done(); }
        String state = f[3];
        if (!knownGateState(state) || !f[11].equals("300000")) { b.set(Readiness.UNKNOWN, "附近派遣定位回報狀態未知：" + state, "再檢查一次", "nearby_gate_unknown_state"); return b.done(); }
        if (dataBeforeLogin(b, c, at, "nearby_gate_status") || dataBeforeLogin(b, c, parseLong(f[10], -1), "nearby_gate_status.raw")) return b.done();   // S1
        b.ev("nearby_gate_status@" + at); b.dataAt = Math.max(b.dataAt, at);
        if (state.equals("ready") && caps == CapsMode.VALID) {
            b.readiness = Readiness.READY; b.code("nearby_ready");
            // S2: capability (methods + managers, session-bound by the line) vs observation (location report: same PID, after login, no session token).
            b.reason = "派遣能力就緒（方法與 manager 屬本程序目前 session）；定位回報已觀察（同 PID、晚於登入，無 session 證據）；就緒不代表有符合條件的目標" + (b.codes.contains("availability_gift_missing") ? "；禮物盒任務除外（Allows 未解析）" : "");
        } else if (state.equals("ready")) {
            b.readiness = Readiness.UNKNOWN; b.code("nearby_location_ready");
            b.reason = "定位就緒；但掃描回報無 PID，本程序列舉能力未確認，不能判就緒";
            b.next = "需要 B04 的 core_capabilities 回報";
        } else b.set(Readiness.WAITING_LOCATION, "等待遊戲定位：" + gateStateLabel(state), "在遊戲地圖停留幾秒後再檢查", "nearby_location_" + state);
        b.work = caps == CapsMode.VALID ? (c.caps.scanCount == 0 ? Work.NONE_PENDING : Work.UNKNOWN) : (c.probeCount == 0 ? Work.NONE_PENDING : Work.UNKNOWN);
        if (caps == CapsMode.VALID ? c.caps.scanCount > 0 : c.probeCount > 0) b.code("eligibility_needs_dispatch_time_recheck");
        if (b.setting == Setting.OFF) b.code("setting_off_kept");
        return b.done();
    }

    static FeatureReadiness batch(ReadinessSample s, CoreFacts c) {
        Build b = new Build("batch", "批次探險");
        if (gate(b, c, s)) return b.done();
        if (gpsWorkBlocked(b, c)) return b.done();
        CapsMode mode = capsMode(b, c);
        if (mode == CapsMode.VALID) { if (!capsScanReady(b, c, s.completedAt) || !capsDispatchReady(b, c.caps)) return b.done(); }
        else if (!scanObserved(b, c, s.completedAt)) return b.done();
        if (!s.app.joystickInstalled) { b.set(Readiness.BLOCKED, "未安裝 GPS JoyStick，無法飛往目標", "安裝指定 JoyStick 5.3.3", "joystick_missing"); return b.done(); }
        if (!s.app.gpsWorkOwner.isEmpty()) { b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "GPS 工作權由「" + s.app.gpsWorkOwner + "」持有中"; b.code("gps_work_busy"); return b.done(); }
        if (mode == CapsMode.VALID) {
            b.readiness = Readiness.READY; b.code("batch_ready");
            b.work = c.caps.scanCount == 0 ? Work.NONE_PENDING : Work.UNKNOWN;   // R5: task-list size is not "has dispatchable items"
            if (c.caps.scanCount > 0) b.code("scan_count_is_task_list_size");
            b.reason = "JoyStick 與工作權可用，本程序掃描、派遣方法與 manager 就緒（屬目前 session）；就緒不代表目標可達或已選定" + (b.codes.contains("availability_gift_missing") ? "；禮物盒任務除外" : "");
            return b.done();
        }
        b.readiness = Readiness.UNKNOWN; b.code("scan_observed_unconfirmed");
        b.work = c.probeCount == 0 ? Work.NONE_PENDING : c.probeCount > 0 ? Work.HAS_ITEMS : Work.UNKNOWN;
        b.reason = "JoyStick 與工作權可用；掃描回報無 PID，本程序列舉能力未確認，不能判就緒";
        b.next = "需要 B04 的 core_capabilities 回報";
        return b.done();
    }

    static FeatureReadiness autoReturn(ReadinessSample s, CoreFacts c) {
        Build b = new Build("return", "自動領取");
        ReadinessSample.Read m = s.read(ReadinessSample.CTRL_RETURN_MODE);
        if (m.state == ReadinessSample.ReadState.MISSING) b.setting = Setting.OFF;           // core defaults missing to dry-run
        else if (!m.ok() || m.trimmed().isEmpty()) b.setting = Setting.UNKNOWN;
        else b.setting = m.trimmed().equals("off") || m.trimmed().equals("dry-run") ? Setting.OFF : Setting.ON;
        if (gate(b, c, s)) return b.done();
        CapsMode mode = capsMode(b, c);
        boolean capsOk = mode != CapsMode.VALID || (capsInit(b, c.caps) && capsSteps(b, c.caps, "return.complete_task_hook", "return.task_list_time")
                && capsSession(b, c.caps) && capsLive(b, c.caps, CoreCapabilities.CAP_ACTION_MANAGER, CoreCapabilities.CAP_PREPARER, CoreCapabilities.CAP_COMPLETE_TASK));
        ReadinessSample.Read r = s.read(ReadinessSample.RETURN_CANDIDATES);
        if (!capsOk && !returnCandidatesFresh(r, c, s.completedAt)) return b.done();   // capabilities explain why the list is missing/stale
        if (r.state == ReadinessSample.ReadState.MISSING) { b.set(Readiness.WAITING_INIT, "尚未收到返程清單回報", "進入遊戲地圖後再檢查", "return_candidates_missing"); return b.done(); }
        if (r.state == ReadinessSample.ReadState.TRUNCATED) { b.set(Readiness.UNKNOWN, "返程清單超過讀取上限，未完整讀取", "再檢查一次", "return_candidates_truncated"); return b.done(); }
        if (!r.ok()) { b.set(Readiness.UNKNOWN, "返程清單讀取失敗", "再檢查一次", "return_candidates_read_error"); return b.done(); }
        String[] lines = r.text.split("\n", -1);
        if (lines.length > 0 && lines[lines.length - 1].isEmpty()) lines = java.util.Arrays.copyOf(lines, lines.length - 1);
        String[] h = lines.length > 0 ? lines[0].split("\t", -1) : new String[0];
        if (h.length != 3 || !h[0].equals("v1")) { b.set(Readiness.UNKNOWN, "返程清單格式無法辨識", "再檢查一次", "return_candidates_malformed"); return b.done(); }
        if (!h[2].equals(c.pid)) { b.set(Readiness.WAITING_INIT, "返程清單屬舊程序", "進入遊戲地圖後再檢查", "return_candidates_old_pid"); return b.done(); }
        long at = parseLong(h[1], -1);
        if (!fresh(at, s.completedAt, RETURN_CANDIDATES_FRESH_MS)) { b.set(Readiness.WAITING_INIT, "返程清單回報過期（" + age(at, s.completedAt) + "）", "切回遊戲地圖後再檢查", "return_candidates_stale"); return b.done(); }
        if (dataBeforeLogin(b, c, at, "return_candidates")) return b.done();
        String[] end = lines.length >= 2 ? lines[lines.length - 1].split("\t", -1) : new String[0];
        int count = end.length == 2 && end[0].equals("end") ? parseInt(end[1], -1) : -1;
        if (count < 0) { b.set(Readiness.UNKNOWN, "返程清單不完整（缺少結尾）", "再檢查一次", "return_candidates_incomplete"); return b.done(); }
        int rows = lines.length - 2, ready = 0;
        if (rows != count) { b.set(Readiness.UNKNOWN, "返程清單筆數不符（宣告 " + count + "，實際 " + rows + "）", "再檢查一次", "return_candidates_count_mismatch"); return b.done(); }
        for (int i = 1; i <= rows; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length != 6 || f[0].isEmpty() || parseInt(f[1], Integer.MIN_VALUE) == Integer.MIN_VALUE || parseInt(f[2], Integer.MIN_VALUE) == Integer.MIN_VALUE
                    || parseLong(f[3], -1) < 0 || !f[4].matches("[01]")) {
                b.set(Readiness.UNKNOWN, "返程清單第 " + i + " 列格式不符", "再檢查一次", "return_candidates_row_malformed"); return b.done();
            }
            if (f[4].equals("1")) ready++;
        }
        b.ev("return_candidates@" + at); b.dataAt = Math.max(b.dataAt, at);
        if (!capsOk) { capsConflict(b, "return_candidates"); return b.done(); }
        b.readiness = Readiness.READY; b.code("return_ready");
        if (mode == CapsMode.VALID) {
            // S2: readiness comes from the session-bound capabilities line; the list carries no session token, so its
            // contents are an observation ("本帳號未核對"), never a claim about this account's pending work.
            b.work = Work.UNKNOWN; b.code("work_result_unattributed:return_candidates");
            b.reason = "領取能力就緒（manager 與方法屬本程序目前 session）；觀察到返程清單 " + count + " 筆" + (count > 0 ? "、其中 " + ready + " 筆標記可領" : "") + "（同 PID、晚於登入，本帳號未核對，不作為待領證據）";
        } else if (count == 0) { b.work = Work.NONE_PENDING; b.reason = "清單完整，目前沒有待領項目（正常，不需送出領取）"; }
        else if (ready == 0) { b.work = Work.NO_MATCH; b.reason = "任務 " + count + " 筆，尚無已返程可領項目"; }
        else { b.work = Work.HAS_ITEMS; b.reason = "任務 " + count + " 筆，其中 " + ready + " 筆已返程可領"; }
        if (b.setting == Setting.OFF) b.code("setting_off_kept");
        return b.done();
    }

    /** Header-only freshness check used to decide whether a capabilities failure is the explanation or a contradiction. */
    private static boolean returnCandidatesFresh(ReadinessSample.Read r, CoreFacts c, long now) {
        if (!r.ok()) return false;
        String[] h = r.text.split("\n", -1)[0].split("\t", -1);
        return h.length == 3 && h[0].equals("v1") && h[2].equals(c.pid) && fresh(parseLong(h[1], -1), now, RETURN_CANDIDATES_FRESH_MS);
    }

    static FeatureReadiness planter(ReadinessSample s, CoreFacts c) {
        Build b = new Build("planter", "育苗");
        ReadinessSample.Read m = s.read(ReadinessSample.CTRL_PLANTER_MODE);
        String mode = m.ok() ? m.trimmed() : "";
        if (m.state == ReadinessSample.ReadState.MISSING) b.setting = Setting.OFF;
        else if (!m.ok() || mode.isEmpty()) b.setting = Setting.UNKNOWN;
        else b.setting = mode.equals("auto") || mode.equals("pluck") ? Setting.ON : Setting.OFF;
        if (gate(b, c, s)) return b.done();
        CapsMode caps = capsMode(b, c);
        boolean capsOk = caps != CapsMode.VALID || (capsInit(b, c.caps) && capsSession(b, c.caps) && capsLive(b, c.caps, CoreCapabilities.CAP_PREPARER, CoreCapabilities.CAP_PLANTER));
        ReadinessSample.Read st = s.read(ReadinessSample.PLANTER_STATUS), inv = s.read(ReadinessSample.PLANTER_INVENTORY);
        if (!capsOk && !(st.ok() && inv.ok() && planterFresh(st, inv, c, s.completedAt))) return b.done();
        if (st.state == ReadinessSample.ReadState.MISSING || inv.state == ReadinessSample.ReadState.MISSING) {
            b.set(Readiness.WAITING_INIT, "尚未收到育苗狀態回報", "進入遊戲地圖後再檢查", "planter_missing"); return b.done();
        }
        if (!st.ok() || !inv.ok()) { b.set(Readiness.UNKNOWN, "育苗回報讀取失敗或超過上限", "再檢查一次", "planter_read_error"); return b.done(); }
        String[] state = st.trimmed().split("\t", -1);
        String[] rows = inv.trimmed().split("\n");
        String[] header = rows[0].split("\t", -1);
        if (state.length < 5 || !state[0].equals("v1") || header.length != 5 || !header[0].equals("v1")) {
            b.set(Readiness.UNKNOWN, "育苗回報格式無法辨識", "再檢查一次", "planter_malformed"); return b.done();
        }
        if (!state[2].equals(c.pid) || !header[2].equals(c.pid)) { b.set(Readiness.WAITING_INIT, "育苗回報屬舊程序", "進入遊戲地圖後再檢查", "planter_old_pid"); return b.done(); }
        long at = Math.min(parseLong(state[1], -1), parseLong(header[1], -1));
        if (!fresh(at, s.completedAt, PLANTER_FRESH_MS)) { b.set(Readiness.WAITING_INIT, "育苗回報過期（" + age(at, s.completedAt) + "）", "切回遊戲後再檢查", "planter_stale"); return b.done(); }
        if (dataBeforeLogin(b, c, at, "planter_status")) return b.done();
        b.ev("planter_status@" + at); b.dataAt = Math.max(b.dataAt, at);
        if (state[3].equals("metadata-unavailable")) { b.set(Readiness.BLOCKED, "核心無法取得育苗資料（metadata-unavailable）", "重開遊戲後再檢查；持續則回報", "planter_metadata_unavailable"); return b.done(); }
        int seeds = 0; for (String row : rows) if (row.startsWith("seed\t")) seeds++;
        String[] end = rows[rows.length - 1].split("\t", -1);
        boolean complete = end.length == 2 && end[0].equals("end") && parseInt(end[1], -2) == seeds;
        if (state[3].equals("inventory-incomplete") || !complete) { b.set(Readiness.UNKNOWN, "育苗庫存回報不完整", "再檢查一次", "planter_inventory_incomplete"); return b.done(); }
        if (!capsOk) { capsConflict(b, "planter_status"); return b.done(); }
        b.readiness = Readiness.READY; b.code("planter_ready");
        int permanent = 0, empty = 0, ready = 0, growing = 0; boolean stepsKnown = false;
        for (String row : rows) {
            String[] f = row.split("\t", -1);
            if (f.length == 2 && f[0].equals("steps")) stepsKnown = parseInt(f[1], -1) >= 0;
            if (f.length < 9 || !f[0].equals("slot") || !f[2].equals("0")) continue;
            permanent++;
            if (f[4].isEmpty()) empty++; else if (f[7].equals("ready")) ready++; else growing++;
        }
        if (!stepsKnown) b.code("planter_steps_unknown");
        if (caps == CapsMode.VALID) {
            b.work = Work.UNKNOWN; b.code("work_result_unattributed:planter_status");   // S2
            b.reason = "育苗能力就緒（方法已解析、inventory manager 屬本程序目前 session）；觀察到永久槽 " + permanent + "：可拔苗 " + ready + "，成長中 " + growing + "，空 " + empty + "（同 PID、晚於登入，本帳號未核對，不作為槽位狀態證據）";
        } else if (ready > 0) { b.work = Work.HAS_ITEMS; b.reason = "永久槽 " + permanent + "：可拔苗 " + ready + "，成長中 " + growing + "，空 " + empty; }
        else if (growing > 0) { b.work = Work.GROWING; b.reason = "永久槽 " + permanent + "：成長中 " + growing + "，空 " + empty + "（步數不增加時不代表核心故障）"; }
        else if (empty > 0) { b.work = Work.NONE_PENDING; b.reason = "永久槽 " + permanent + " 全空；補種依設定與庫存另行判斷"; }
        else { b.work = Work.UNKNOWN; b.reason = "未讀到永久槽資料"; b.code("planter_no_permanent_slots"); }
        if (!mode.isEmpty() && !mode.equals(state[4])) b.code("planter_mode_not_applied_yet");
        return b.done();
    }

    private static boolean planterFresh(ReadinessSample.Read st, ReadinessSample.Read inv, CoreFacts c, long now) {
        String[] state = st.trimmed().split("\t", -1), header = inv.trimmed().split("\n")[0].split("\t", -1);
        return state.length >= 5 && header.length == 5 && state[2].equals(c.pid) && header[2].equals(c.pid)
                && fresh(Math.min(parseLong(state[1], -1), parseLong(header[1], -1)), now, PLANTER_FRESH_MS);
    }

    /**
     * Explicit map of the states actually written by feeding_coordinator::status()
     * (14 coordinator outputs, counting feed-/collect-confirmed) and returned by
     * feeding_rpc::send() (13 results, written through the same status());
     * pending-review is shared, so the union is 26 unique states. Only states emitted after the
     * coordinator verified a fresh, account-matched snapshot in that tick count
     * as READY. States written on the pending path (waiting-inventory-confirmation,
     * waiting-rpc-no-retry) are also written when the snapshot is stale, so they
     * stay UNKNOWN with Work.WORKING. Send failures are split into "not sent"
     * (BLOCKED, entry/precondition) and "possibly sent" (BLOCKED, review). The
     * collect (petal) side is judged natively per Pikmin and is not proven here.
     * Return-reward states (one-dispatched, batch-paused-limit, ...) belong to
     * write_return_status and are deliberately absent.
     */
    static FeatureReadiness feeding(ReadinessSample s, CoreFacts c) {
        Build b = new Build("feeding", "餵精華／收花瓣（協調器）");
        ReadinessSample.Read cfg = s.read(ReadinessSample.CTRL_FEEDING_CONFIG);
        String[] h = cfg.ok() ? cfg.trimmed().split("\n")[0].split("\t", -1) : new String[0];
        if (cfg.state == ReadinessSample.ReadState.MISSING) b.setting = Setting.OFF;
        else if (!cfg.ok()) b.setting = Setting.UNKNOWN;
        else if ((h.length >= 9 && h[0].equals("v2"))||(h.length >= 10 && h[0].equals("v3"))) { b.setting = h[2].equals("1") ? Setting.ON : Setting.OFF; if (parseInt(h[h[0].equals("v3")?9:8], 0) == 0) b.code("feeding_whitelist_empty"); }
        else { b.setting = Setting.UNKNOWN; b.code("feeding_config_malformed"); }
        if (gate(b, c, s)) return b.done();
        CapsMode mode = capsMode(b, c);
        boolean capsOk = mode != CapsMode.VALID || (capsLive(b, c.caps, CoreCapabilities.CAP_FEEDING_PROBE) && capsSession(b, c.caps) && feedingIdentity(b, c.caps));
        ReadinessSample.Read st = s.read(ReadinessSample.FEEDING_STATE);
        if (!capsOk && !feedingFresh(st, c, s.completedAt)) return b.done();
        if (st.state == ReadinessSample.ReadState.MISSING) {
            b.set(Readiness.WAITING_INIT, "尚未收到餵食協調狀態；本唯讀檢查無法取得隊伍／庫存證據", "精華頁「隊伍診斷」可發只讀請求查看，本檢查不啟用餵食", "feeding_state_missing"); return b.done();
        }
        if (!st.ok()) { b.set(Readiness.UNKNOWN, "餵食狀態讀取失敗", "再檢查一次", "feeding_state_read_error"); return b.done(); }
        String[] lines = st.trimmed().split("\n");
        String[] f = lines[0].split("\t", -1);
        if (f.length != 5 || !f[0].equals("v2") || lines.length != 2 || !lines[1].trim().equals("end") || !(f[3].equals("-") || f[3].matches("[a-f0-9-]{36}"))) {
            b.set(Readiness.UNKNOWN, "餵食狀態格式無法辨識", "再檢查一次", "feeding_state_malformed"); return b.done();
        }
        if (!f[2].equals(c.pid)) { b.set(Readiness.WAITING_INIT, "餵食狀態屬舊程序", "切回遊戲後再檢查", "feeding_state_old_pid"); return b.done(); }
        long at = parseLong(f[1], -1);
        if (!fresh(at, s.completedAt, FEEDING_FRESH_MS)) { b.set(Readiness.WAITING_INIT, "餵食狀態過期（" + age(at, s.completedAt) + "）", "切回遊戲後再檢查", "feeding_state_stale"); return b.done(); }
        if (dataBeforeLogin(b, c, at, "feeding_state")) return b.done();
        b.ev("feeding_state@" + at); b.dataAt = Math.max(b.dataAt, at);
        String state = f[4];
        b.code("feeding_state_" + state);
        if (!capsOk && (state.equals("waiting-eligible-squad") || state.equals("sent") || state.equals("feed-confirmed") || state.equals("collect-confirmed")
                || state.equals("batch-confirmation-timeout") || state.equals("batch-squad-changed"))) { capsConflict(b, "feeding_state"); return b.done(); }
        switch (state) {
            // Returned before any capture: nothing about squad/inventory is proven.
            // --- feeding_coordinator::tick, emitted BEFORE any capture: squad/inventory unproven ---
            case "off": b.readiness = Readiness.UNKNOWN; b.work = Work.NOT_APPLICABLE; b.reason = "餵食關閉，協調器未探查隊伍／庫存（唯讀檢查無法取得）"; b.next = "精華頁「隊伍診斷」可發只讀請求查看"; break;
            case "config-unavailable": b.set(Readiness.BLOCKED, "核心讀不到餵食設定檔", "到精華頁重新保存設定後再檢查", "feeding_config_unavailable"); break;
            case "config-changed": b.set(Readiness.WAITING_INIT, "設定已變更，協調器重新讀取中", "幾秒後再檢查", "feeding_config_changed"); break;
            case "waiting-other-work": b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "其他高優先工作進行中，協調器未探查（隊伍／庫存未證明）"; break;
            // --- emitted when the snapshot is NOT fresh or the account does not match ---
            case "waiting-data": b.set(Readiness.WAITING_INIT, "等待隊伍／庫存快照（快照不新鮮或帳號未就緒）", "停在遊戲畫面幾秒後再檢查", "feeding_waiting_data"); break;
            // --- pending phase (a journal entry exists): the snapshot at this tick may be stale; never counted ready ---
            case "waiting-inventory-confirmation": b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "等待庫存確認（此狀態也在快照不新鮮或帳號不一致時寫出，資料未證明；不重送）"; b.code("result_pending"); break;
            case "waiting-rpc-no-retry": b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "餵食 RPC 進行中（不重試）"; b.code("result_pending"); break;
            case "unconfirmed-cooldown": b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "舊請求結果未確認，已留原始紀錄；受影響目標冷卻後重新判斷，其他目標可繼續"; b.code("feeding_unconfirmed_cooldown"); break;
            case "recovery-storage-retry": b.set(Readiness.BLOCKED, "待確認紀錄或冷卻狀態暫時無法安全保存；核心會自動重試，不需手動重送", "檢查儲存空間與核心日誌；恢復後自動續行", "feeding_recovery_storage_retry"); break;
            case "pending-review": b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "餵食紀錄正在核對，本輪不重送；核心會自動恢復"; b.code("feeding_review_pending"); break;
            case "receipt-write-review": b.set(Readiness.BLOCKED, "收據／冷卻清單寫入失敗；核心會自動重試，不需手動重送", "檢查儲存空間與核心日誌", "feeding_review_pending"); break;
            // --- emitted after a fresh, account-matched snapshot was verified in this tick ---
            case "rotation-sent": b.readiness=Readiness.UNKNOWN; b.work=Work.WORKING; b.reason="换隊請求已送出，尚待隊伍確認"; b.code("result_pending"); break;
            case "rotate-confirmed": case "rotation-no-replacements": b.readiness=Readiness.UNKNOWN; b.work=Work.UNKNOWN; b.reason="觀察到換隊回報："+FeedingHistory.label(state)+"（本帳號未核對）"; b.code("work_result_unattributed:feeding_state"); break;
            case "rotation-preflight-changed": b.set(Readiness.WAITING_INIT,"換隊條件已變更，等待下一次快照","稍候重查","rotation_waiting"); break;
            case "rotation-method-unavailable": b.set(Readiness.BLOCKED,"換隊入口未解析（未送出）","確認配對核心與遊戲版本","rotation_unavailable"); break;
            case "waiting-eligible-squad": b.readiness = Readiness.READY; b.work = Work.NO_MATCH; b.reason = "隊伍與庫存快照新鮮；目前無符合條件的隊員（不是載入失敗）；收瓣條件由原生逐隻判定，本檢查不另證明"; break;
            case "sent": b.readiness = Readiness.READY; b.work = Work.WORKING; b.reason = "已送出餵食／收瓣請求，等待確認（結果未知期間不重送）"; b.code("result_pending"); break;
            case "feed-confirmed": case "collect-confirmed": b.readiness = Readiness.READY; b.work = Work.NONE_PENDING; b.reason = "上一批已確認完成（" + state + "）"; break;
            case "batch-confirmation-timeout": b.readiness = Readiness.READY; b.work = Work.NONE_PENDING; b.reason = "上一批已結案，部分目標逾時未確認並進入有限冷卻；其他目標可繼續"; b.code("feeding_partial_timeout"); break;
            case "batch-squad-changed": b.readiness = Readiness.READY; b.work = Work.NONE_PENDING; b.reason = "上一批已結案，部分目標因隊伍變動未確認（不重送）"; b.code("feeding_partial_squad_changed"); break;
            // --- feeding_rpc::send failures, NOT sent: no duplication risk, but an entry or precondition failed ---
            case "runtime-unavailable": b.set(Readiness.BLOCKED, "核心餵食入口不可用（runtime-unavailable，未送出）", "重開遊戲後再檢查；持續則回報", "feeding_entry_unavailable"); break;
            case "method-mismatch": b.set(Readiness.BLOCKED, "核心餵食方法與遊戲版本不符（未送出）", "等待相容核心", "feeding_entry_unavailable"); break;
            case "account-changed": b.set(Readiness.WAITING_INIT, "帳號在送出前變更（未送出）", "登入完成後再檢查", "feeding_account_changed"); break;
            case "preflight-invalid": case "preflight-expired": case "item-id-invalid": case "request-root-failed": case "request-build-failed": case "intent-write-failed":
                b.set(Readiness.BLOCKED, "送出前置檢查失敗（" + state + "，未送出）", "到精華頁查看紀錄；持續則回報", "feeding_send_precondition_failed"); break;
            // --- feeding_rpc::send failures where the request MAY have gone out: result unknown, review required ---
            case "rpc-start-uncertain": case "task-root-failed":
                b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "請求可能已送出但結果未知（" + state + "）；保留紀錄，冷卻後依新快照重判"; b.code("feeding_send_uncertain"); break;
            default: b.readiness = Readiness.UNKNOWN; b.work = Work.UNKNOWN; b.reason = "協調器狀態未知：" + state; b.code("feeding_state_unknown");
        }
        if (mode == CapsMode.VALID && b.readiness == Readiness.READY) {
            // S2: READY here means the capability (probe resolved, identity ready in this session). The coordinator
            // state carries no session token: an old account's in-flight callback may still write after a new login,
            // so "上一批完成 / 無符合隊員 / 已送出" is observation only. Safety codes (result_pending etc.) stay.
            b.work = Work.UNKNOWN; b.code("work_result_unattributed:feeding_state");
            b.reason = "餵食能力就緒（探針已解析、本檔 identity 就緒）；觀察到協調器回報「" + state + "」（" + age(at, s.completedAt) + "，同 PID、晚於登入，本帳號未核對，不作為本帳號已完成或進行中的證據）";
        }
        if (b.setting == Setting.OFF) b.code("setting_off_kept");
        return b.done();
    }

    private static boolean feedingIdentity(Build b, CoreCapabilities k) {
        if (!k.identityReady()) { b.set(Readiness.WAITING_INIT, "帳號識別尚未就緒（能力回報 identity=" + k.identity + "），餵食需要它", "登入完成後再檢查", "caps_identity_not_ready"); return false; }
        return true;
    }
    private static boolean feedingFresh(ReadinessSample.Read st, CoreFacts c, long now) {
        if (!st.ok()) return false;
        String[] f = st.trimmed().split("\n")[0].split("\t", -1);
        return f.length == 5 && f[2].equals(c.pid) && fresh(parseLong(f[1], -1), now, FEEDING_FRESH_MS);
    }

    /** Location shared by both farm modes: JoyStick installed, gate report fresh with a known state, raw fix fresh. */
    private static boolean locationReady(Build b, ReadinessSample s, CoreFacts c) {
        if (!s.app.joystickInstalled) { b.set(Readiness.BLOCKED, "未安裝 GPS JoyStick", "安裝指定 JoyStick 5.3.3", "joystick_missing"); return false; }
        ReadinessSample.Read g = s.read(ReadinessSample.NEARBY_GATE);
        String[] f = g.ok() ? g.trimmed().split("\t", -1) : new String[0];
        if (f.length != 12 || !(f[0].equals("v1") || f[0].equals("v2")) || !f[2].equals(c.pid) || !fresh(parseLong(f[1], -1), s.completedAt, NEARBY_GATE_FRESH_MS)) {
            b.set(Readiness.WAITING_LOCATION, "尚無新鮮的遊戲定位回報", "開啟 JoyStick 定位並停在地圖後再檢查", "location_report_missing"); return false;
        }
        if (!knownGateState(f[3]) || !f[11].equals("300000")) { b.set(Readiness.UNKNOWN, "定位回報狀態未知：" + f[3], "再檢查一次", "location_state_unknown"); return false; }
        long rawAt = parseLong(f[10], -1);
        if (!fresh(rawAt, s.completedAt, RAW_LOCATION_FRESH_MS)) {
            b.set(Readiness.WAITING_LOCATION, "遊戲尚未收到新定位（raw " + (rawAt > 0 ? age(rawAt, s.completedAt) : "缺") + "）", "確認 JoyStick 定位運作中", "raw_location_stale"); return false;
        }
        if (dataBeforeLogin(b, c, parseLong(f[1], -1), "nearby_gate_status") || dataBeforeLogin(b, c, rawAt, "nearby_gate_status.raw")) return false;   // S1
        b.ev("nearby_gate_status@" + parseLong(f[1], -1));
        return true;
    }

    /**
     * planting_control_status is `epochMs mode planting owned action petalId`
     * with no PID. It can name a hard failure (controller-unavailable) but
     * cannot prove this process has a planting controller.
     * @return false when the feature must stop; true when observed (still unconfirmed)
     */
    private static boolean plantingObserved(Build b, ReadinessSample s, CoreFacts c, boolean valid) {
        ReadinessSample.Read pc = s.read(ReadinessSample.PLANTING_CONTROL);
        if (pc.state == ReadinessSample.ReadState.MISSING) { b.set(Readiness.WAITING_INIT, "尚未觀察到原生種花控制回報", "進入遊戲地圖後再檢查", "planting_control_missing"); return false; }
        String[] f = pc.ok() ? pc.trimmed().split("\t", -1) : new String[0];
        if (f.length != 6 || !f[1].matches("on|off|observe") || !f[2].matches("[01]") || !f[3].matches("[01]")) {
            b.set(Readiness.UNKNOWN, "種花控制回報格式不符", "再檢查一次", "planting_control_malformed"); return false;
        }
        long at = parseLong(f[0], -1);
        if (!fresh(at, s.completedAt, PLANTING_CONTROL_FRESH_MS)) { b.set(Readiness.WAITING_INIT, "種花控制回報過期（" + age(at, s.completedAt) + "，該檔無 PID）", "切回遊戲後再檢查", "planting_control_stale"); return false; }
        if (f[4].equals("controller-unavailable")) { b.set(Readiness.BLOCKED, "原生回報種花控制器不可用", "重開遊戲後再檢查；持續則回報", "planting_controller_unavailable"); return false; }
        if (dataBeforeLogin(b, c, at, "planting_control_status")) return false;   // S1 (file has no PID either; time is the only handle)
        b.ev("planting_control_status@" + at + "(no-pid)");
        if (!valid) b.code("capability_unconfirmed_no_pid"); else b.code("planting_control_observed_no_session");
        b.code(f[2].equals("1") ? "planting_active" : "planting_idle");
        return true;
    }

    static FeatureReadiness farmPoint(ReadinessSample s, CoreFacts c) {
        Build b = new Build("farm_point", "花田逐點");
        if (gate(b, c, s)) return b.done();
        if (gpsWorkBlocked(b, c)) return b.done();
        CapsMode mode = capsMode(b, c);
        if (mode == CapsMode.VALID) {
            if (!capsScanReady(b, c, s.completedAt) || !capsDispatchReady(b, c.caps)
                    || !capsLive(b, c.caps, CoreCapabilities.CAP_PLANTING_CONTROLLER, CoreCapabilities.CAP_LOCATION_CONTROLLER)) return b.done();
        } else if (!scanObserved(b, c, s.completedAt)) return b.done();
        if (!locationReady(b, s, c)) return b.done();
        if (!plantingObserved(b, s, c, mode == CapsMode.VALID)) return b.done();
        if (!s.app.gpsWorkOwner.isEmpty()) { b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "GPS 工作權由「" + s.app.gpsWorkOwner + "」持有中"; b.code("gps_work_busy"); return b.done(); }
        if (mode == CapsMode.VALID) {
            b.readiness = Readiness.READY; b.work = Work.NOT_APPLICABLE; b.code("farm_point_ready");
            b.reason = "能力就緒：掃描、派遣方法、種花與定位控制器屬本程序目前 session；定位與種花控制回報只是觀察（晚於登入，無 session 證據，種花回報無 PID）；就緒不代表花田路線或花瓣已備妥";
            return b.done();
        }
        b.readiness = Readiness.UNKNOWN; b.work = Work.NOT_APPLICABLE;
        b.reason = "定位就緒；掃描與種花控制回報皆無 PID，本程序派遣／種花執行能力未確認，不能判就緒";
        b.next = "需要 B04 的 core_capabilities 回報";
        return b.done();
    }

    static FeatureReadiness farmRoute(ReadinessSample s, CoreFacts c) {
        Build b = new Build("farm_route", "花田沿路");
        if (gate(b, c, s)) return b.done();
        if (gpsWorkBlocked(b, c)) return b.done();
        CapsMode mode = capsMode(b, c);
        if (mode == CapsMode.VALID) {
            if (!capsScanReady(b, c, s.completedAt) || !capsDispatchReady(b, c.caps)
                    || !capsLive(b, c.caps, CoreCapabilities.CAP_PLANTING_CONTROLLER, CoreCapabilities.CAP_LOCATION_CONTROLLER)) return b.done();
        } else if (!scanObserved(b, c, s.completedAt)) return b.done();
        if (!locationReady(b, s, c)) return b.done();
        if (!plantingObserved(b, s, c, mode == CapsMode.VALID)) return b.done();
        ReadinessSample.Read rs = s.read(ReadinessSample.ROUTE_STATUS);
        String[] f = rs.ok() ? rs.trimmed().split("\t", -1) : new String[0];
        if (rs.state == ReadinessSample.ReadState.MISSING) { b.set(Readiness.CORE_NO_DIAGNOSTIC, "核心未提供沿路狀態回報", "確認核心版本 ≥ 1.4.30", "route_status_missing"); return b.done(); }
        if (f.length != 8 || !f[0].equals("v1") || !f[7].equals("300000") || !f[5].matches("[01]") || !f[6].matches("[01]")
                || !f[4].matches("off|prepare|walk|hold") || !(f[3].equals("-") || f[3].matches("[0-9a-f-]{36}"))) {
            b.set(Readiness.UNKNOWN, "沿路狀態回報格式或旗標不符", "再檢查一次", "route_status_malformed"); return b.done();
        }
        if (!f[2].equals(c.pid)) { b.set(Readiness.WAITING_INIT, "沿路狀態屬舊程序", "切回遊戲後再檢查", "route_status_old_pid"); return b.done(); }
        long at = parseLong(f[1], -1);
        if (!fresh(at, s.completedAt, ROUTE_FRESH_MS)) { b.set(Readiness.UNKNOWN, "沿路狀態回報過期（" + age(at, s.completedAt) + "，門檻 2 秒）", "再檢查一次", "route_status_stale"); return b.done(); }
        if (dataBeforeLogin(b, c, at, "route_status")) return b.done();
        b.ev("route_status@" + at); b.dataAt = Math.max(b.dataAt, at);
        if (!c.sessionFresh || !c.sessionReady) { b.set(Readiness.WAITING_INIT, "帳號識別尚未就緒，沿路租約需要它", "登入完成後再檢查", "session_identity_not_ready"); return b.done(); }
        if (dataBeforeLogin(b, c, c.sessionAt, "automation_session")) return b.done();
        if (mode == CapsMode.VALID && !c.caps.identityReady()) { b.set(Readiness.UNKNOWN, "automation_session 為 ready 但能力回報 identity=" + c.caps.identity + "，兩來源不一致", "再檢查一次；持續則回報", "caps_conflict:automation_session"); return b.done(); }
        if (!f[4].equals("off") && f[5].equals("0") && s.app.gpsWorkOwner.isEmpty()) { b.set(Readiness.BLOCKED, "沿路租約 " + f[4] + " 仍在但無工作持有，停花未確認", "到花田頁復原確認", "route_lease_orphaned"); return b.done(); }
        if (!s.app.gpsWorkOwner.isEmpty()) { b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "GPS 工作權由「" + s.app.gpsWorkOwner + "」持有中（route phase " + f[4] + "）"; b.code("gps_work_busy"); return b.done(); }
        if (!f[4].equals("off")) { b.readiness = Readiness.UNKNOWN; b.work = Work.WORKING; b.reason = "沿路租約仍為 " + f[4]; b.code("route_lease_active"); return b.done(); }
        if (mode == CapsMode.VALID) {
            b.readiness = Readiness.READY; b.work = Work.NOT_APPLICABLE; b.code("farm_route_ready");
            b.reason = "能力就緒：派遣／種花方法與 manager 屬本程序目前 session，本檔 identity 就緒；定位、沿路與 automation_session 回報只是觀察（晚於登入，無 session 證據）；就緒不代表路線已規劃或會採果";
            return b.done();
        }
        b.readiness = Readiness.UNKNOWN; b.work = Work.NOT_APPLICABLE;
        b.reason = "定位、沿路回報與帳號識別就緒；掃描與種花控制回報無 PID，本程序執行能力未確認，不能判就緒；不借用散步派遣證據";
        b.next = "需要 B04 的 core_capabilities 回報";
        return b.done();
    }

    static FeatureReadiness gpsCopy(ReadinessSample s, CoreFacts c) {
        Build b = new Build("gps_copy", "GPS Copy");
        ReadinessSample.Read m = s.read(ReadinessSample.GPS_COPY_MODE);
        b.setting = setting(m, "on", "on");
        if (m.ok() && !m.trimmed().isEmpty() && !m.trimmed().equals("on") && !m.trimmed().equals("off")) b.setting = Setting.UNKNOWN;
        if (gate(b, c, s)) return b.done();
        if (!s.app.pairUnified) { b.set(Readiness.CORE_NO_DIAGNOSTIC, "非整合核心，GPS 由獨立模組提供，本檢查不涵蓋", "", "gps_not_unified"); return b.done(); }
        CapsMode mode = capsMode(b, c);
        if (mode == CapsMode.VALID && c.gpsLoaded != c.caps.gps) {
            b.set(Readiness.UNKNOWN, "unified_runtime 與能力回報的 GPS 旗標不一致（" + (c.gpsLoaded ? 1 : 0) + " vs " + (c.caps.gps ? 1 : 0) + "）", "再檢查一次；持續則回報", "caps_conflict:unified_runtime"); return b.done();
        }
        if (c.gpsLoaded) { b.readiness = Readiness.READY; b.reason = "同程序 GPS hook 已安裝（loader 版本與配對相符）；就緒不代表已實測點擊複製"; b.code("gps_ready"); b.ev("unified_runtime@" + c.gpsAt); b.dataAt = Math.max(b.dataAt, c.gpsAt); }
        else if (c.gpsUnverified) b.set(Readiness.UNKNOWN, "loader 回報存在，但配對期望值讀取失敗，無法核對", "重開 App 後再檢查", "core_pair_unreadable");
        else if (c.gpsOtherVersion) b.set(Readiness.BLOCKED, "loader 回報版本與本 APK 配對不符", "到系統頁核心區核對", "unified_runtime_version_mismatch");
        else if (c.gpsStale) b.set(Readiness.UNKNOWN, "loader 回報過期", "再檢查一次", "unified_runtime_stale");
        else b.set(Readiness.WAITING_INIT, "GPS hook 在登入／地圖回呼且 metadata 就緒後才安裝", "進入遊戲地圖後再檢查", "gps_hook_not_installed");
        ReadinessSample.Read st = s.read(ReadinessSample.GPS_COPY_STATUS);
        if (st.ok() && !st.trimmed().isEmpty()) { String[] f = st.trimmed().split("\t", -1); if (f.length >= 2) b.code("gps_last_status_" + f[1] + "(no-pid)"); }
        b.work = Work.NOT_APPLICABLE;
        if (b.setting == Setting.OFF) b.code("setting_off_kept");
        return b.done();
    }

    // ------------------------------------------------------------- helpers

    static boolean fresh(long at, long now, long limit) { return at > 0 && now + CLOCK_SLACK_MS >= at && now - at <= limit; }
    static long parseLong(String v, long fallback) { try { return Long.parseLong(v.trim()); } catch (Exception e) { return fallback; } }
    static int parseInt(String v, int fallback) { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return fallback; } }
    static String age(long at, long now) {
        if (at <= 0) return "缺";
        long a = now - at;
        if (a < 0) return "時間在未來";
        return a < 60_000 ? (a / 1000) + " 秒前" : (a / 60_000) + " 分鐘前";
    }
    static boolean knownGateState(String state) {
        switch (state) {
            case "ready": case "waiting-game-location": case "stale-game-location": case "game-location-catching-up":
            case "location-settling": case "location-gap": case "location-moving": return true;
            default: return false;
        }
    }
    static String gateStateLabel(String state) {
        switch (state) {
            case "waiting-game-location": return "等待遊戲定位";
            case "stale-game-location": return "遊戲定位過期";
            case "game-location-catching-up": return "遊戲位置追趕中";
            case "location-settling": return "定位穩定中";
            case "location-gap": return "raw／processed 差距過大";
            case "location-moving": return "移動中";
            default: return state;
        }
    }
    static String readinessLabel(Readiness r) {
        switch (r) {
            case READY: return "就緒";
            case WAITING_GAME: return "等待遊戲";
            case WAITING_INIT: return "等待初始化";
            case WAITING_LOCATION: return "等待定位";
            case BLOCKED: return "受阻";
            case INCOMPATIBLE: return "版本不相容";
            case CORE_NO_DIAGNOSTIC: return "核心無此診斷";
            default: return "待核對";
        }
    }
    static String workLabel(Work w) {
        switch (w) {
            case NONE_PENDING: return "無待處理";
            case GROWING: return "成長中";
            case NO_MATCH: return "無符合條件";
            case WORKING: return "工作中";
            case HAS_ITEMS: return "有項目";
            default: return "";
        }
    }
    static String settingLabel(Setting s) {
        switch (s) { case ON: return "開"; case OFF: return "關"; case UNKNOWN: return "未知（讀取失敗）"; case TAKEN_OVER: return "暫時接管"; default: return "—"; }
    }
}

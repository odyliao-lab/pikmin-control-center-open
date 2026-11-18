package dev.ody.pikmincontrol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strict read-only parser for {@code core_capabilities.tsv} (core ≥ 1.5.23,
 * schema v1, 23 TAB columns, single line, trailing {@code end}).
 *
 * The producer is the loader heartbeat (one line per second, tmp+rename), so
 * the file is "valid at sample time": freshness is judged like core_runtime
 * (12 s). Anything that does not match the schema exactly — column count,
 * enum values, step names/order, hex bit ranges, numeric ranges, PID, version
 * pair, game version, time — is a distinct non-OK status and the model must
 * never treat such a file as evidence for READY. The parser is pure Java and
 * is exercised against the native formatter's golden output in tests.
 *
 * Column layout (index: name):
 * 0 v1 · 1 epochMs · 2 pid · 3 version · 4 code · 5 game · 6 seq · 7 stage · 8 failed · 9 domain · 10 steps
 * · 11 caps(hex) · 12 stale(hex) · 13 session_gen · 14 session_epoch · 15 identity · 16 last_login_ms
 * · 17 last_map_ms · 18 scan_epoch · 19 scan_ms · 20 scan_count · 21 gps · 22 end
 */
final class CoreCapabilities {
    static final int COLUMNS = 23;
    static final String SCHEMA = "v1";
    static final String EXPECTED_GAME = "157.0";
    static final long FRESH_MS = 12_000L;
    static final long SCAN_FRESH_MS = 30_000L;

    enum Status {
        OK, MISSING, UNREADABLE, TRUNCATED, MALFORMED, UNKNOWN_SCHEMA, OLD_PID, VERSION_MISMATCH, VERSION_UNVERIFIED, STALE, FUTURE
    }

    /** Step names in producer order. The file must list exactly these, in this order. */
    static final String[] STEPS = {
        "return.action_manager_class", "return.blocker_capture", "return.complete_task_hook", "return.preparer_ctor_hook",
        "return.prepare_completion_hook", "return.readiness_taskid", "return.task_list_time", "expedition.methods",
        "availability.base", "availability.selected", "availability.gift", "expedition.pick_fastest", "wire_probe", "logout_hook"
    };
    static final List<String> STAGES = Arrays.asList("boot", "lib_wait", "lib_timeout", "symbols_missing", "incompatible", "hooks_called");
    static final List<String> CODES = Arrays.asList("class_missing", "field_missing", "entry_mismatch", "method_missing", "no_entry", "ctor_missing",
            "method_unavailable", "rpc_entry_mismatch", "proto_layout_mismatch", "symbol", "il2cpp_size", "lib_timeout");
    static final List<String> IDENTITIES = Arrays.asList("unknown", "ready", "logged_out");

    /** Capability bits; "resolved/captured", never "call succeeded". */
    static final int CAP_PREPARER = 1, CAP_EXPEDITION_START = 1 << 1, CAP_ACTION_MANAGER = 1 << 2, CAP_COMPLETE_TASK = 1 << 3,
            CAP_PLANTER = 1 << 4, CAP_PLANTING_CONTROLLER = 1 << 5, CAP_LOCATION_CONTROLLER = 1 << 6, CAP_FEEDING_PROBE = 1 << 7,
            CAP_NECTAR_CLAIM = 1 << 8, CAP_AVAILABILITY_GIFT = 1 << 9, CAP_AVAILABILITY_SELECTED = 1 << 10, CAP_PICK_FASTEST = 1 << 11;
    static final int CAP_ALL = (1 << 12) - 1;
    /** Bits bound to a session (instance captured by a hook). planter is per-process: its instance is the preparer's. */
    static final int CAP_PER_SESSION = CAP_PREPARER | CAP_ACTION_MANAGER | CAP_PLANTING_CONTROLLER | CAP_LOCATION_CONTROLLER;

    static String capName(int bit) {
        switch (bit) {
            case CAP_PREPARER: return "preparer"; case CAP_EXPEDITION_START: return "expedition_start"; case CAP_ACTION_MANAGER: return "action_manager";
            case CAP_COMPLETE_TASK: return "complete_task"; case CAP_PLANTER: return "planter"; case CAP_PLANTING_CONTROLLER: return "planting_controller";
            case CAP_LOCATION_CONTROLLER: return "location_controller"; case CAP_FEEDING_PROBE: return "feeding_probe"; case CAP_NECTAR_CLAIM: return "nectar_claim";
            case CAP_AVAILABILITY_GIFT: return "availability_gift"; case CAP_AVAILABILITY_SELECTED: return "availability_selected"; case CAP_PICK_FASTEST: return "pick_fastest";
            default: return "cap" + bit;
        }
    }

    /** One resolution step: state ∈ ok/failed/blocked/-; code for failed; parent for blocked. */
    static final class Step {
        final String name, state, code, parent;
        Step(String name, String state, String code, String parent) { this.name = name; this.state = state; this.code = code; this.parent = parent; }
        boolean ok() { return state.equals("ok"); }
        /** Human line for a non-ok step; "" when ok. */
        String problem() {
            switch (state) {
                case "failed": return name + "=" + code + "（本程序不重試）";
                case "blocked": return parent + " 失敗使 " + name + " 未執行（本程序不重試）";
                case "-": return name + " 尚未到達";
                default: return "";
            }
        }
    }

    final Status status;
    final String detail;          // why non-OK, for evidence
    final long epochMs; final String pid, version; final int code; final String game; final long seq;
    final String stage, failed; final long failedDetail; final String domain;
    final Map<String, Step> steps;
    final int caps, stale; final long sessionGen, sessionEpoch; final String identity;
    final long lastLoginMs, lastMapMs, scanEpoch, scanMs; final int scanCount; final boolean gps;

    private CoreCapabilities(Status status, String detail, long epochMs, String pid, String version, int code, String game, long seq,
                             String stage, String failed, long failedDetail, String domain, Map<String, Step> steps, int caps, int stale,
                             long sessionGen, long sessionEpoch, String identity, long lastLoginMs, long lastMapMs, long scanEpoch, long scanMs,
                             int scanCount, boolean gps) {
        this.status = status; this.detail = detail; this.epochMs = epochMs; this.pid = pid; this.version = version; this.code = code; this.game = game;
        this.seq = seq; this.stage = stage; this.failed = failed; this.failedDetail = failedDetail; this.domain = domain;
        this.steps = Collections.unmodifiableMap(new LinkedHashMap<>(steps)); this.caps = caps; this.stale = stale;
        this.sessionGen = sessionGen; this.sessionEpoch = sessionEpoch; this.identity = identity; this.lastLoginMs = lastLoginMs;
        this.lastMapMs = lastMapMs; this.scanEpoch = scanEpoch; this.scanMs = scanMs; this.scanCount = scanCount; this.gps = gps;
    }

    private static CoreCapabilities bad(Status status, String detail) {
        return new CoreCapabilities(status, detail, 0, "", "", -1, "", -1, "", "", 0, "", Collections.<String, Step>emptyMap(), 0, 0, 0, 0, "", 0, 0, 0, 0, -1, false);
    }

    boolean ok() { return status == Status.OK; }
    /** Present in some form on disk (any status except MISSING). */
    boolean present() { return status != Status.MISSING; }
    boolean has(int bit) { return ok() && (caps & bit) == bit; }
    /** Captured in the current session: bit set and not stale. Per-process bits are never stale. */
    boolean live(int bit) { return has(bit) && (stale & bit) == 0; }
    Step step(String name) { return steps.get(name); }
    boolean stepOk(String name) { Step s = steps.get(name); return s != null && s.ok(); }
    boolean domainReady() { return ok() && domain.equals("ready"); }
    boolean identityReady() { return ok() && identity.equals("ready"); }
    boolean loggedOut() { return ok() && identity.equals("logged_out"); }
    /** Scan evidence belongs to the current session and is recent. */
    boolean scanCurrent(long now) { return ok() && scanCount >= 0 && scanEpoch == sessionEpoch && scanMs > 0 && now + ReadinessModel.CLOCK_SLACK_MS >= scanMs && now - scanMs <= SCAN_FRESH_MS; }

    /**
     * @param read      the source read (state decides MISSING/UNREADABLE/TRUNCATED before any parsing)
     * @param pid       stable game PID of this round ("" when unknown → OLD_PID cannot be decided → MALFORMED-level refusal)
     * @param pairVersion expected core version ("" when the pair is unknown → VERSION_UNVERIFIED)
     * @param pairCode  expected core code (≤0 when unknown)
     * @param now       freshness reference (sample completedAt)
     */
    static CoreCapabilities parse(ReadinessSample.Read read, String pid, String pairVersion, int pairCode, long now) {
        if (read == null) return bad(Status.UNREADABLE, "no read");
        switch (read.state) {
            case MISSING: return bad(Status.MISSING, "file missing");
            case TRUNCATED: return bad(Status.TRUNCATED, "over per-read limit");
            case OK: break;
            default: return bad(Status.UNREADABLE, read.state.name().toLowerCase());
        }
        String text = read.text;
        if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
        if (text.isEmpty()) return bad(Status.MALFORMED, "empty");
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) return bad(Status.MALFORMED, "multi-line");
        String[] f = text.split("\t", -1);
        if (f.length < 1 || !f[0].startsWith("v")) return bad(Status.MALFORMED, "no schema tag");
        if (!f[0].equals(SCHEMA)) return bad(Status.UNKNOWN_SCHEMA, f[0]);
        if (f.length != COLUMNS) return bad(Status.MALFORMED, "columns=" + f.length);
        if (!f[22].equals("end")) return bad(Status.MALFORMED, "no end");
        long epochMs = num(f[1], 1, Long.MAX_VALUE); if (epochMs < 0) return bad(Status.MALFORMED, "epochMs");
        if (!f[2].matches("[1-9][0-9]*")) return bad(Status.MALFORMED, "pid");
        if (!f[3].matches("[0-9]+\\.[0-9]+\\.[0-9]+")) return bad(Status.MALFORMED, "version");
        long code = num(f[4], 1, Integer.MAX_VALUE); if (code < 0) return bad(Status.MALFORMED, "code");
        if (!f[5].matches("[0-9]+\\.[0-9]+")) return bad(Status.MALFORMED, "game");
        long seq = num(f[6], 0, Long.MAX_VALUE); if (seq < 0) return bad(Status.MALFORMED, "seq");
        if (!STAGES.contains(f[7])) return bad(Status.MALFORMED, "stage=" + f[7]);
        String failed = f[8]; long failedDetail = 0;
        if (!failed.equals("-")) {
            int colon = failed.indexOf(':');
            String fc = colon < 0 ? failed : failed.substring(0, colon);
            if (!CODES.contains(fc)) return bad(Status.MALFORMED, "failed=" + failed);
            if (colon >= 0) {
                String d = failed.substring(colon + 1);
                if (!d.matches("-?[0-9]{1,18}")) return bad(Status.MALFORMED, "failed detail");
                failedDetail = Long.parseLong(d);
            }
            failed = fc;
        }
        if (!f[9].equals("pending") && !f[9].equals("ready")) return bad(Status.MALFORMED, "domain=" + f[9]);
        Map<String, Step> steps = parseSteps(f[10]); if (steps == null) return bad(Status.MALFORMED, "steps");
        int caps = hex(f[11]), stale = hex(f[12]);
        if (caps < 0 || caps > CAP_ALL || stale < 0 || stale > CAP_ALL) return bad(Status.MALFORMED, "caps/stale");
        if ((stale & ~caps) != 0) return bad(Status.MALFORMED, "stale bit without cap");
        if ((stale & ~CAP_PER_SESSION) != 0) return bad(Status.MALFORMED, "stale on per-process cap");
        long sessionGen = num(f[13], 0, Long.MAX_VALUE), sessionEpoch = num(f[14], 0, Long.MAX_VALUE);
        if (sessionGen < 0 || sessionEpoch < 0 || sessionGen > sessionEpoch) return bad(Status.MALFORMED, "session counters");
        if (!IDENTITIES.contains(f[15])) return bad(Status.MALFORMED, "identity=" + f[15]);
        long lastLogin = num(f[16], 0, Long.MAX_VALUE), lastMap = num(f[17], 0, Long.MAX_VALUE);
        if (lastLogin < 0 || lastMap < 0) return bad(Status.MALFORMED, "login/map ms");
        long scanEpoch = num(f[18], 0, Long.MAX_VALUE), scanMs = num(f[19], 0, Long.MAX_VALUE);
        // scan_count: the literal "-1" (never published) or a non-negative int. num()'s -1 sentinel must not be confused with it.
        long scanCount = f[20].equals("-1") ? -1 : num(f[20], 0, Integer.MAX_VALUE);
        if (scanCount == -1 && !f[20].equals("-1")) return bad(Status.MALFORMED, "scan_count=" + f[20]);
        if (scanEpoch < 0 || scanMs < 0 || scanEpoch > sessionEpoch) return bad(Status.MALFORMED, "scan fields");
        if (scanCount == -1 && (scanMs != 0 || scanEpoch != 0)) return bad(Status.MALFORMED, "scan never published but fields set");
        if (scanCount >= 0 && scanMs == 0) return bad(Status.MALFORMED, "scan published without time");
        if (sessionGen > 0 && lastLogin == 0) return bad(Status.MALFORMED, "login without time");
        if (sessionGen == 0 && lastLogin != 0) return bad(Status.MALFORMED, "login time without login");
        if (!f[21].equals("0") && !f[21].equals("1")) return bad(Status.MALFORMED, "gps=" + f[21]);
        if (f[15].equals("ready") && sessionGen == 0) return bad(Status.MALFORMED, "ready without login");

        // Values are structurally valid; now bind them to this round.
        CoreCapabilities parsed = new CoreCapabilities(Status.OK, "", epochMs, f[2], f[3], (int) code, f[5], seq, f[7], failed, failedDetail, f[9], steps,
                caps, stale, sessionGen, sessionEpoch, f[15], lastLogin, lastMap, scanEpoch, scanMs, (int) scanCount, f[21].equals("1"));
        if (pid == null || pid.isEmpty()) return parsed.with(Status.OLD_PID, "round pid unknown");
        if (!f[2].equals(pid)) return parsed.with(Status.OLD_PID, "pid " + f[2] + " ≠ " + pid);
        if (!f[5].equals(EXPECTED_GAME)) return parsed.with(Status.VERSION_MISMATCH, "game " + f[5]);
        if (pairVersion == null || pairVersion.isEmpty() || pairCode <= 0) return parsed.with(Status.VERSION_UNVERIFIED, "pair unknown");
        if (!f[3].equals(pairVersion) || code != pairCode) return parsed.with(Status.VERSION_MISMATCH, f[3] + "/" + code + " ≠ " + pairVersion + "/" + pairCode);
        if (epochMs > now + ReadinessModel.CLOCK_SLACK_MS) return parsed.with(Status.FUTURE, "epochMs " + (epochMs - now) + " ms ahead");
        if (now - epochMs > FRESH_MS) return parsed.with(Status.STALE, (now - epochMs) / 1000 + " s old");
        return parsed;
    }

    private CoreCapabilities with(Status status, String detail) {
        return new CoreCapabilities(status, detail, epochMs, pid, version, code, game, seq, stage, failed, failedDetail, domain, steps, caps, stale,
                sessionGen, sessionEpoch, identity, lastLoginMs, lastMapMs, scanEpoch, scanMs, scanCount, gps);
    }

    /** Exactly STEPS.length entries in STEPS order; every entry `name=ok|failed:<code>|blocked:<known step>|-`. */
    static Map<String, Step> parseSteps(String field) {
        String[] parts = field.split(",", -1);
        if (parts.length != STEPS.length) return null;
        Map<String, Step> out = new LinkedHashMap<>();
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) return null;
            String name = parts[i].substring(0, eq), value = parts[i].substring(eq + 1);
            if (!name.equals(STEPS[i])) return null;
            if (value.equals("ok") || value.equals("-")) out.put(name, new Step(name, value, "", ""));
            else if (value.startsWith("failed:")) { String c = value.substring(7); if (!CODES.contains(c)) return null; out.put(name, new Step(name, "failed", c, "")); }
            else if (value.startsWith("blocked:")) { String p = value.substring(8); if (!Arrays.asList(STEPS).contains(p) || p.equals(name)) return null; out.put(name, new Step(name, "blocked", "", p)); }
            else return null;
        }
        return out;
    }

    /** Decimal within [min,max] with min ≥ 0; -1 on any failure (so -1 is never a legal return value of this helper). */
    private static long num(String v, long min, long max) {
        if (min < 0) throw new IllegalArgumentException("num() cannot represent negative ranges");
        if (v == null || v.isEmpty() || !v.matches("[0-9]{1,18}")) return -1;
        try { long n = Long.parseLong(v); return n >= min && n <= max ? n : -1; } catch (Exception e) { return -1; }
    }
    private static int hex(String v) {
        if (v == null || v.isEmpty() || v.length() > 8 || !v.matches("[0-9a-f]+")) return -1;
        try { return Integer.parseInt(v, 16); } catch (Exception e) { return -1; }
    }

    /** Human label for a non-OK status. */
    static String statusLabel(Status s) {
        switch (s) {
            case MISSING: return "核心未提供能力回報（核心 < 1.5.23 或尚未寫入）";
            case UNREADABLE: return "能力回報讀取失敗";
            case TRUNCATED: return "能力回報超過讀取上限";
            case MALFORMED: return "能力回報格式不符";
            case UNKNOWN_SCHEMA: return "能力回報 schema 版本未知";
            case OLD_PID: return "能力回報屬舊程序";
            case VERSION_MISMATCH: return "能力回報的核心／遊戲版本與配對不符";
            case VERSION_UNVERIFIED: return "能力回報存在，但配對期望值未知，無法核對";
            case STALE: return "能力回報過期";
            case FUTURE: return "能力回報時間在未來";
            default: return "";
        }
    }
}

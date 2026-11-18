package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One read-only sampling round for the per-feature readiness check.
 *
 * Pure data: no Android, no root, no side effects. The adapter fills it from a
 * single bounded shell; tests fill it directly. Every source is a {@link Read}
 * that records whether the file was missing, unreadable, truncated or read
 * normally, so the model can tell "no file" from "empty list" from "read
 * failed". PID is sampled before and after the reads; a change between the two
 * invalidates every game-side value in this round.
 *
 * Time: {@code startedAt} is taken before the root shell and app-side facts,
 * {@code completedAt} after parsing. Freshness is judged against
 * {@code completedAt} so a slow round cannot make just-written data look like
 * it is from the future, nor make stale data look fresh.
 */
final class ReadinessSample {
    /** How a single source read ended. */
    enum ReadState { OK, MISSING, ERROR, TRUNCATED, DENIED }

    static final class Read {
        final ReadState state;
        final String text;
        Read(ReadState state, String text) { this.state = state; this.text = text == null ? "" : text; }
        boolean ok() { return state == ReadState.OK; }
        String trimmed() { return text.trim(); }
        static Read ok(String text) { return new Read(ReadState.OK, text); }
        static Read missing() { return new Read(ReadState.MISSING, ""); }
        static Read error() { return new Read(ReadState.ERROR, ""); }
        static Read truncated(String text) { return new Read(ReadState.TRUNCATED, text); }
        static Read denied() { return new Read(ReadState.DENIED, ""); }
    }

    /** Source identifiers, in the order the adapter reads them. */
    static final String PID_BEFORE = "pid_before";
    static final String MODULE = "module";
    static final String COMPATIBILITY = "compatibility_status";
    static final String CORE_RUNTIME = "core_runtime";
    static final String UNIFIED_RUNTIME = "unified_runtime";
    static final String CORE_CAPABILITIES = "core_capabilities";   // core ≥ 1.5.23; optional, see CoreCapabilities
    static final String DISPATCH_PROBE_STATUS = "dispatch_probe_status";
    static final String NEARBY_GATE = "nearby_gate_status";
    static final String RETURN_CANDIDATES = "return_candidates";
    static final String RETURN_STATUS = "return_rpc_status";
    static final String PLANTER_STATUS = "planter_status";
    static final String PLANTER_INVENTORY = "planter_inventory";
    static final String FEEDING_STATE = "feeding_state";
    static final String GPS_COPY_STATUS = "gps_copy_status";
    static final String GPS_COPY_MODE = "gps_copy_mode";
    static final String ROUTE_STATUS = "route_status";
    static final String PLANTING_CONTROL = "planting_control_status";
    static final String SESSION = "automation_session";
    static final String CTRL_DISPATCH_MODE = "ctrl_dispatch_mode";
    static final String CTRL_RETURN_MODE = "ctrl_return_mode";
    static final String CTRL_PLANTER_MODE = "ctrl_planter_mode";
    static final String CTRL_FEEDING_CONFIG = "ctrl_feeding_config";
    static final String CTRL_FEEDING_PROBE = "ctrl_feeding_probe";
    static final String CTRL_NEARBY_KINDS = "ctrl_nearby_kinds";
    static final String PID_AFTER = "pid_after";

    static final String[] ORDER = {
        PID_BEFORE, MODULE, COMPATIBILITY, CORE_RUNTIME, UNIFIED_RUNTIME, CORE_CAPABILITIES, DISPATCH_PROBE_STATUS, NEARBY_GATE,
        RETURN_CANDIDATES, RETURN_STATUS, PLANTER_STATUS, PLANTER_INVENTORY, FEEDING_STATE, GPS_COPY_STATUS,
        GPS_COPY_MODE, ROUTE_STATUS, PLANTING_CONTROL, SESSION, CTRL_DISPATCH_MODE, CTRL_RETURN_MODE,
        CTRL_PLANTER_MODE, CTRL_FEEDING_CONFIG, CTRL_FEEDING_PROBE, CTRL_NEARBY_KINDS, PID_AFTER
    };

    /** App-side facts that do not come from the game process. All read-only. */
    static final class AppFacts {
        final String pairVersion; final int pairCode; final boolean pairUnified;
        final boolean corePending;        // CoreUpdater transaction awaiting review/reboot
        final boolean setupPending;       // SetupDeployment install awaiting review
        final boolean updatePending;      // UpdateInstaller session awaiting confirmation
        final boolean routePending;       // RouteSession left a lease to clean up
        final String stopAllError;        // WorkStatus stop_all_error, "" when none
        final boolean stopIncomplete;     // batch or farm state is stop-incomplete
        final boolean joystickInstalled;
        final String gpsWorkOwner;        // "" when no Control Center GPS work holds the lock
        final String nearbyOriginal;      // "armed"/"off" saved before a batch/farm takeover, "" when none saved
        final long il2cppSize;            // -1 when unknown
        /** Names of facts whose read threw; their boolean above is NOT trustworthy (three-state: true/false/unreadable). */
        final java.util.List<String> unreadable;
        AppFacts(String pairVersion, int pairCode, boolean pairUnified, boolean corePending, boolean setupPending,
                 boolean updatePending, boolean routePending, String stopAllError, boolean stopIncomplete,
                 boolean joystickInstalled, String gpsWorkOwner, String nearbyOriginal, long il2cppSize) {
            this(pairVersion, pairCode, pairUnified, corePending, setupPending, updatePending, routePending, stopAllError, stopIncomplete,
                 joystickInstalled, gpsWorkOwner, nearbyOriginal, il2cppSize, java.util.Collections.<String>emptyList());
        }
        AppFacts(String pairVersion, int pairCode, boolean pairUnified, boolean corePending, boolean setupPending,
                 boolean updatePending, boolean routePending, String stopAllError, boolean stopIncomplete,
                 boolean joystickInstalled, String gpsWorkOwner, String nearbyOriginal, long il2cppSize, java.util.List<String> unreadable) {
            this.pairVersion = pairVersion == null ? "" : pairVersion; this.pairCode = pairCode; this.pairUnified = pairUnified;
            this.corePending = corePending; this.setupPending = setupPending; this.updatePending = updatePending;
            this.routePending = routePending; this.stopAllError = stopAllError == null ? "" : stopAllError; this.stopIncomplete = stopIncomplete;
            this.joystickInstalled = joystickInstalled; this.gpsWorkOwner = gpsWorkOwner == null ? "" : gpsWorkOwner;
            this.nearbyOriginal = nearbyOriginal == null ? "" : nearbyOriginal; this.il2cppSize = il2cppSize;
            this.unreadable = Collections.unmodifiableList(new java.util.ArrayList<>(unreadable == null ? java.util.Collections.<String>emptyList() : unreadable));
        }
        /** Convenience for tests and simple callers: no pending state at all. */
        static AppFacts simple(String pairVersion, int pairCode, boolean pairUnified, boolean joystickInstalled, String gpsWorkOwner) {
            return new AppFacts(pairVersion, pairCode, pairUnified, false, false, false, false, "", false, joystickInstalled, gpsWorkOwner, "", -1);
        }
    }

    final long startedAt;           // wall clock before the shell
    final long completedAt;         // wall clock after parsing; freshness reference
    final long elapsedMs;           // monotonic duration of the whole round
    final boolean roundOk;          // shell finished, framing intact, within deadline
    final boolean roundTimedOut;
    final String skipReason;        // "" when the shell ran; otherwise why it was not started (budget exhausted)
    final Map<String, Read> reads;
    final AppFacts app;

    ReadinessSample(long startedAt, long completedAt, long elapsedMs, boolean roundOk, boolean roundTimedOut, Map<String, Read> reads, AppFacts app) {
        this(startedAt, completedAt, elapsedMs, roundOk, roundTimedOut, "", reads, app);
    }
    ReadinessSample(long startedAt, long completedAt, long elapsedMs, boolean roundOk, boolean roundTimedOut, String skipReason, Map<String, Read> reads, AppFacts app) {
        this.startedAt = startedAt; this.completedAt = completedAt; this.elapsedMs = elapsedMs;
        this.roundOk = roundOk; this.roundTimedOut = roundTimedOut; this.skipReason = skipReason == null ? "" : skipReason;
        this.reads = Collections.unmodifiableMap(new LinkedHashMap<>(reads)); this.app = app;
    }
    /** Test convenience: started == completed. */
    ReadinessSample(long now, boolean roundOk, boolean roundTimedOut, Map<String, Read> reads, AppFacts app) {
        this(now, now, 0, roundOk, roundTimedOut, reads, app);
    }

    Read read(String id) { Read r = reads.get(id); return r == null ? Read.error() : r; }
    /** Position of a source in {@link #ORDER}; -1 when unknown. */
    static int index(String id) { for (int i = 0; i < ORDER.length; i++) if (ORDER[i].equals(id)) return i; return -1; }

    /** Game PID if it was the same non-empty numeric value before and after the reads, else "". */
    String stablePid() {
        String before = read(PID_BEFORE).trimmed(), after = read(PID_AFTER).trimmed();
        return before.matches("[1-9][0-9]*") && before.equals(after) ? before : "";
    }
    boolean gameRunning() { return read(PID_BEFORE).trimmed().matches("[1-9][0-9]*") || read(PID_AFTER).trimmed().matches("[1-9][0-9]*"); }
    boolean pidChanged() { return !read(PID_BEFORE).trimmed().equals(read(PID_AFTER).trimmed()); }

    /** Markers the adapter's shell emits per source; parsed here so the model never sees them. */
    static final String MARK_MISSING = "__PCC_MISSING__";
    static final String MARK_ERROR = "__PCC_ERROR__";
    static final String MARK_DENIED = "__PCC_DENIED__";
    /** Confirmed "no process". The current adapter never emits it (pidof alone cannot prove absence); kept so a future producer with verified enumeration can. */
    static final String MARK_NOPROC = "__PCC_NOPROC__";
    static final String MARK_TOOLARGE = "__PCC_TOOLARGE__";
    static final int PER_READ_LIMIT = 32768;
    static final long ROUND_DEADLINE_MS = 10_000L;

    /**
     * Convert one framed section into a Read. The shell prints at most
     * PER_READ_LIMIT+1 bytes per source, so a payload longer than the limit in
     * UTF-8 bytes means the file was longer than allowed. A marker anywhere at
     * the tail (after a partial payload) still counts as that failure; a marker
     * alone is that state. One trailing newline is the file's own terminator
     * and is removed only after the overflow check.
     */
    static Read fromSection(String section) {
        String s = section == null ? "" : section;
        String t = s.trim();
        if (t.equals(MARK_NOPROC)) return Read.ok("");
        if (t.equals(MARK_MISSING)) return Read.missing();
        if (t.equals(MARK_DENIED)) return Read.denied();
        if (t.equals(MARK_TOOLARGE) || t.endsWith(MARK_TOOLARGE)) return Read.truncated("");
        if (t.equals(MARK_ERROR) || t.endsWith(MARK_ERROR) || t.endsWith(MARK_DENIED)) return Read.error();
        int bytes = s.getBytes(StandardCharsets.UTF_8).length;
        if (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        if (bytes > PER_READ_LIMIT) return Read.truncated(s);
        return Read.ok(s);
    }

    static ReadinessSample fromSections(String[] sections, boolean roundOk, boolean timedOut, long startedAt, long completedAt, long elapsedMs, AppFacts app) {
        return fromSections(sections, roundOk, timedOut, "", startedAt, completedAt, elapsedMs, app);
    }
    static ReadinessSample fromSections(String[] sections, boolean roundOk, boolean timedOut, String skipReason, long startedAt, long completedAt, long elapsedMs, AppFacts app) {
        Map<String, Read> reads = new LinkedHashMap<>();
        for (int i = 0; i < ORDER.length; i++) {
            reads.put(ORDER[i], sections != null && i < sections.length ? fromSection(sections[i]) : Read.error());
        }
        // A PID section must be digits or the explicit no-process marker; anything else is a read error.
        for (String id : new String[]{PID_BEFORE, PID_AFTER}) {
            Read r = reads.get(id);
            if (r.ok() && !r.trimmed().isEmpty() && !r.trimmed().matches("[1-9][0-9]*")) reads.put(id, Read.error());
        }
        return new ReadinessSample(startedAt, completedAt, elapsedMs, roundOk, timedOut, skipReason, reads, app);
    }
    static ReadinessSample fromSections(String[] sections, boolean roundOk, boolean timedOut, long now, AppFacts app) {
        return fromSections(sections, roundOk, timedOut, now, now, 0, app);
    }
}

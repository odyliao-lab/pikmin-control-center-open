package dev.ody.pikmincontrol;

import android.content.Context;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Read-only sampling adapter for {@link ReadinessModel}.
 *
 * One bounded root shell per round: inner `timeout` = what is left of the
 * 10 s round budget minus 2 s outer slack (max 8 s), 256 KiB total output.
 * Twenty-one sources are dumped through `head -c 32769` so overflow is
 * detectable in bytes; module.prop and the feeding config are size-guarded
 * by `stat` (exit status checked first, then numeric output) and filtered
 * by `sed` directly. There is no pipeline anywhere in the command, so every
 * producer's exit status reaches the error marker. pidof is the only source
 * of "process running"; nothing else is inferred. The shell runs
 * pidof/test/stat/head/sed/echo; it never writes a file, never touches
 * control files and never sends a read request to the core
 * (FeedingGateway.read() does, so it is deliberately not reused).
 *
 * Budget limits: app facts run synchronously and cannot be cancelled; the
 * root shell is started only with the remaining budget and not at all when
 * under 1 s is left; a result that arrives after the deadline is discarded
 * (not judged). BoundedCommand's reader join (250 ms) and process cleanup
 * are outside the inner timeout, so "10 s" is the budget for starting work
 * and for accepting results, not a hard wall-clock return guarantee.
 *
 * {@link Coordinator} gives single-flight with a 5 s coalescing window
 * (monotonic), a generation token so a page that was left never receives a
 * stale result, and a follow-up round for a page that re-entered while the
 * cancelled round was still running. It is Context-free for tests.
 */
final class ReadinessGateway {
    private ReadinessGateway() {}

    static final int INNER_TIMEOUT_S = 8;
    static final int TOTAL_LIMIT = 262144;
    static final long COALESCE_MS = 5_000L;
    /** Outer wait BoundedCommand adds on top of the inner `timeout`; the minimum useful inner budget is 1 s. */
    static final long OUTER_SLACK_MS = 2_000L;
    static final int MIN_INNER_S = 1;
    private static final String CAP = "head -c " + (ReadinessSample.PER_READ_LIMIT + 1);

    /** Source locations; overridable so a real shell can be run against a fixture directory in tests. */
    static final class Paths {
        final String game, tmp, mod, staged;
        Paths(String game, String tmp, String mod, String staged) { this.game = game; this.tmp = tmp; this.mod = mod; this.staged = staged; }
        static final Paths DEVICE = new Paths("/data/user/0/com.nianticlabs.pikmin/files/", "/data/local/tmp/",
                "/data/adb/modules/pcc_emulator_bridge_canary", "/data/adb/modules_update/pcc_emulator_bridge_canary");
    }

    /** A capped read straight from the file (no pipeline): head's own exit status decides, and a partial payload followed by the marker is still an error. */
    static String capped(String path) {
        return "if test -e " + path + "; then if test -r " + path + "; then " + CAP + " " + path
                + " 2>/dev/null || echo " + ReadinessSample.MARK_ERROR + "; else echo " + ReadinessSample.MARK_DENIED
                + "; fi; else echo " + ReadinessSample.MARK_MISSING + "; fi";
    }

    /**
     * Size-guarded read for sources that must be filtered (sed) rather than dumped.
     * Only a regular file is accepted. `stat` must exit 0 (its status is saved
     * before anything else runs) AND print a number; only a number above the
     * limit is "too large". Everything else (stat missing / non-zero — even with
     * numeric output —, non-numeric, producer non-zero) is a read error, and the
     * producer runs directly so no pipeline can mask its exit status. The size
     * check precedes the read (TOCTOU): a file growing in between is still
     * bounded by the round's total output limit and timeout, not by this guard.
     */
    static String guarded(String path, String producer) {
        return "if test -e " + path + "; then if test -f " + path + " && test -r " + path + "; then sz=$(stat -c %s " + path + " 2>/dev/null); rc=$?; "
                + "if test $rc -ne 0; then echo " + ReadinessSample.MARK_ERROR + "; else case \"$sz\" in ''|*[!0-9]*) echo " + ReadinessSample.MARK_ERROR
                + ";; *) if test \"$sz\" -le " + ReadinessSample.PER_READ_LIMIT + "; then " + producer + " " + path + " 2>/dev/null || echo " + ReadinessSample.MARK_ERROR
                + "; else echo " + ReadinessSample.MARK_TOOLARGE + "; fi;; esac; fi; else echo " + ReadinessSample.MARK_DENIED + "; fi; else echo " + ReadinessSample.MARK_MISSING + "; fi";
    }

    /**
     * PID read: the game is considered running only when pidof exists, exits 0
     * and prints a value. Every other outcome — pidof missing, any non-zero
     * exit (including its documented "not found"), empty output — is a read
     * ERROR that the model reports as "未找到或無法確認遊戲程序" and never as
     * "game closed". There is deliberately no /proc fallback: proving "no
     * process" would require every enumeration producer's exit status to be
     * verified, which a shell command substitution cannot guarantee.
     */
    static String pidRead(Paths p) {
        return "if ! command -v pidof >/dev/null 2>&1; then echo " + ReadinessSample.MARK_ERROR + "; else "
                + "pid=$(pidof com.nianticlabs.pikmin 2>/dev/null); rc=$?; "
                + "if test $rc -eq 0 && test -n \"$pid\"; then echo \"$pid\"; else echo " + ReadinessSample.MARK_ERROR + "; fi; fi";
    }

    static String command() { return command(Paths.DEVICE); }

    /** The whole round as one framed command. Order must match ReadinessSample.ORDER. */
    static String command(Paths p) {
        final String GAME = p.game, TMP = p.tmp, MOD = p.mod, STAGED = p.staged;
        return ProbeSnapshot.command(
            pidRead(p),
            // disabled/staged first so a sed failure leaves the error marker at the tail of the section.
            "if test -e " + MOD + "/module.prop; then printf 'disabled=%s\\n' \"$(test -e " + MOD + "/disable && echo yes || echo no)\"; printf 'staged=%s\\n' \"$(test -e " + STAGED + "/module.prop && echo yes || echo no)\"; "
                + guarded(MOD + "/module.prop", "sed -n -e 's/^version=/version=/p' -e 's/^versionCode=/versionCode=/p'") + "; else echo " + ReadinessSample.MARK_MISSING + "; fi",
            capped(GAME + "compatibility_status.tsv"),
            capped(GAME + "core_runtime.tsv"),
            capped(GAME + "unified_runtime.tsv"),
            capped(GAME + "core_capabilities.tsv"),
            capped(GAME + "dispatch_probe_status.tsv"),
            capped(GAME + "nearby_gate_status.tsv"),
            capped(GAME + "return_candidates.tsv"),
            capped(GAME + "return_rpc_status.tsv"),
            capped(GAME + "planter_status.tsv"),
            capped(GAME + "planter_inventory.tsv"),
            capped(GAME + "feeding_state.tsv"),
            capped(GAME + "gps_copy_status.tsv"),
            capped(GAME + "gps_copy_mode.txt"),
            capped(GAME + "route_status.tsv"),
            capped(GAME + "planting_control_status.tsv"),
            capped(GAME + "automation_session.tsv"),
            capped(TMP + "pikmin-dispatch-mode.txt"),
            capped(TMP + "pikmin-return-mode.txt"),
            capped(TMP + "pikmin-planter-mode.txt"),
            guarded(TMP + "pikmin-feeding-config.tsv", "sed -n -e 1p"),
            capped(TMP + "pikmin-feeding-probe.txt"),
            capped(TMP + "pikmin-nearby-kinds.txt"),
            pidRead(p));
    }

    /** Runs the shell with the remaining inner budget in seconds; the only place root is touched. */
    interface Runner { BoundedCommand.Result run(String command, int innerSeconds) throws Exception; }
    /** Wall clock for reporting, monotonic clock for durations and coalescing. */
    interface Clock { long wallNow(); long monoNow(); }

    /** App-side facts are read inside the round so the deadline covers them. */
    interface Facts { ReadinessSample.AppFacts read(); }

    static ReadinessSample sample(Runner runner, Facts facts, Clock clock) { return sample(runner, facts, clock, Paths.DEVICE); }

    /**
     * One round under a single deadline: app facts, root shell and parsing all
     * draw from {@link ReadinessSample#ROUND_DEADLINE_MS}. The shell is started
     * only with what is left (inner `timeout` = remaining − outer slack, capped
     * at 8 s); when less than 1 s remains no root process is started at all.
     */
    static ReadinessSample sample(Runner runner, Facts facts, Clock clock, Paths paths) {
        long startedWall = clock.wallNow(), startedMono = clock.monoNow();
        ReadinessSample.AppFacts app;
        try { app = facts.read(); }
        catch (RuntimeException e) {
            app = new ReadinessSample.AppFacts("", -1, false, false, false, false, false, "", false, false, "", "", -1, java.util.Arrays.asList("app_facts:" + e.getClass().getSimpleName()));
        }
        long remaining = ReadinessSample.ROUND_DEADLINE_MS - (clock.monoNow() - startedMono);
        int inner = (int) Math.min(INNER_TIMEOUT_S, (remaining - OUTER_SLACK_MS) / 1000L);
        if (inner < MIN_INNER_S) {
            return ReadinessSample.fromSections(null, false, true, "讀取前置耗時已用盡 10 秒預算，未啟動 root 讀取", startedWall, clock.wallNow(), clock.monoNow() - startedMono, app);
        }
        BoundedCommand.Result r;
        try { r = runner.run(command(paths), inner); }
        catch (Exception e) {
            return ReadinessSample.fromSections(null, false, false, startedWall, clock.wallNow(), clock.monoNow() - startedMono, app);
        }
        boolean roundOk = r.ok && !r.timedOut && !r.truncated;
        String[] sections = null;
        if (roundOk) {
            try { sections = ProbeSnapshot.sections(r.text, ReadinessSample.ORDER.length); }
            catch (RuntimeException framing) { roundOk = false; }
        }
        long completedWall = clock.wallNow(), elapsed = clock.monoNow() - startedMono;
        boolean timedOut = r.timedOut || elapsed > ReadinessSample.ROUND_DEADLINE_MS;
        return ReadinessSample.fromSections(sections, roundOk && !timedOut, timedOut, startedWall, completedWall, elapsed, app);
    }

    static final Clock SYSTEM_CLOCK = new Clock() {
        @Override public long wallNow() { return System.currentTimeMillis(); }
        @Override public long monoNow() { return android.os.SystemClock.elapsedRealtime(); }
    };

    static ReadinessSample sample(final Context c) {
        return sample(new Runner() {
            @Override public BoundedCommand.Result run(String command, int innerSeconds) throws Exception { return BoundedCommand.root(command, innerSeconds, TOTAL_LIMIT); }
        }, new Facts() {
            @Override public ReadinessSample.AppFacts read() { return appFacts(c); }
        }, SYSTEM_CLOCK);
    }

    /**
     * App-side facts; every call here is an existing read-only accessor. A fact
     * whose read throws is recorded in {@code unreadable} instead of being
     * silently reported as false, so a broken preference can never look like
     * "no pending transaction / no unfinished stop".
     */
    static ReadinessSample.AppFacts appFacts(Context c) {
        java.util.List<String> unreadable = new java.util.ArrayList<>();
        String version = ""; int code = -1; boolean unified = false;
        try { CorePair p = CoreUpdater.pair(c); version = p.version; code = p.code; unified = p.unified; } catch (Exception e) { unreadable.add("core_pair"); }
        boolean corePending = probe(unreadable, "core_pending", () -> CoreUpdater.pending(c));
        boolean setupPending = probe(unreadable, "setup_pending", () -> SetupDeployment.pending(c));
        boolean updatePending = probe(unreadable, "update_pending", () -> UpdateInstaller.pending(c));
        boolean routePending = probe(unreadable, "route_pending", () -> RouteSession.pending(c));
        String stopAllError = ""; boolean stopIncomplete = false;
        try { stopAllError = WorkStatus.allError(c); } catch (Exception e) { unreadable.add("stop_all_error"); }
        try { stopIncomplete = "stop-incomplete".equals(WorkStatus.state(c, GpsWorkLock.BATCH)) || "stop-incomplete".equals(WorkStatus.state(c, GpsWorkLock.FLOWER_FARM)); }
        catch (Exception e) { unreadable.add("stop_incomplete"); }
        boolean joystick;
        try { c.getPackageManager().getApplicationInfo(JoystickAdapter.PACKAGE, 0); joystick = true; } catch (Exception e) { joystick = false; }
        String owner = "";
        try {
            for (String candidate : new String[]{GpsWorkLock.BATCH, GpsWorkLock.FLOWER_FARM, GpsWorkLock.JOYSTICK_STARTUP, "settings-restore", "nearby-settings"}) {
                if (GpsWorkLock.observedHeldBy(c, candidate)) { owner = GpsWorkLock.label(candidate); break; }
            }
        } catch (Exception e) { unreadable.add("gps_work_owner"); }
        String nearbyOriginal = "";
        try { if (NearbyWorkState.pending(c)) nearbyOriginal = NearbyWorkState.originallyArmed(c) ? "armed" : "off"; } catch (Exception e) { unreadable.add("nearby_original"); }
        return new ReadinessSample.AppFacts(version, code, unified, corePending, setupPending, updatePending, routePending,
                stopAllError, stopIncomplete, joystick, owner, nearbyOriginal, EnvironmentGateway.il2cppSize(c), unreadable);
    }
    private interface Probe { boolean read() throws Exception; }
    private static boolean probe(java.util.List<String> unreadable, String name, Probe p) {
        try { return p.read(); } catch (Exception e) { unreadable.add(name); return false; }
    }

    // ---------------------------------------------------------- coordinator

    interface Sampler { ReadinessSample sample(); }
    interface Listener { void onResult(int generation, ReadinessModel.Result result, boolean cached); }

    /**
     * Single-flight, coalescing, cancellable, with follow-up.
     * - refresh while a round is in flight: remembered; when that round ends it is
     *   delivered if still valid, otherwise a fresh round starts for the newest listener.
     * - refresh within 5 s (monotonic) of the last completed round: cached result re-delivered, flagged cached.
     * - cancel(): bumps the generation; in-flight results are dropped and never cached.
     * - sampler exception or executor rejection: an explicit "unavailable" result is delivered, never silence.
     */
    static final class Coordinator {
        private final Clock clock; private final Executor executor; private final Sampler sampler;
        private final Object lock = new Object();
        private int generation;
        private boolean inFlight;
        private long lastCompletedMono = Long.MIN_VALUE;
        private ReadinessModel.Result last;
        private Listener pending;          // newest listener that asked while a round was in flight
        private int started, delivered, dropped, coalesced, followUps;

        Coordinator(Clock clock, Executor executor, Sampler sampler) { this.clock = clock; this.executor = executor; this.sampler = sampler; }

        void cancel() { synchronized (lock) { generation++; pending = null; } }

        ReadinessModel.Result last() { synchronized (lock) { return last; } }
        int started() { synchronized (lock) { return started; } }
        int delivered() { synchronized (lock) { return delivered; } }
        int dropped() { synchronized (lock) { return dropped; } }
        int coalesced() { synchronized (lock) { return coalesced; } }
        int followUps() { synchronized (lock) { return followUps; } }
        boolean inFlight() { synchronized (lock) { return inFlight; } }

        /** @return true when a new round was started now. */
        boolean refresh(final Listener listener) {
            final int gen;
            synchronized (lock) {
                long now = clock.monoNow();
                if (inFlight) { pending = listener; coalesced++; return false; }
                if (last != null && now - lastCompletedMono < COALESCE_MS) {
                    coalesced++;
                    final ReadinessModel.Result cached = last; final int g = generation;
                    listener.onResult(g, cached, true);
                    return false;
                }
                inFlight = true; started++; gen = generation;
            }
            Runnable round = new Runnable() {
                @Override public void run() {
                    ReadinessModel.Result result;
                    try { result = ReadinessModel.evaluate(sampler.sample()); }
                    catch (RuntimeException e) { result = ReadinessModel.unavailable(e.getClass().getSimpleName(), clock.wallNow()); }
                    finish(gen, result, listener);
                }
            };
            try { executor.execute(round); }
            catch (RejectedExecutionException rejected) {
                finish(gen, ReadinessModel.unavailable("執行緒不可用", clock.wallNow()), listener);
            }
            return true;
        }

        private void finish(int gen, ReadinessModel.Result result, Listener listener) {
            Listener deliverTo = null; Listener followUp = null; boolean cache;
            synchronized (lock) {
                inFlight = false;
                boolean valid = gen == generation;
                cache = valid && result != null;
                if (cache) { last = result; lastCompletedMono = clock.monoNow(); }
                if (valid) { delivered++; deliverTo = pending != null ? pending : listener; }
                else { dropped++; followUp = pending; }
                pending = null;
            }
            if (deliverTo != null) deliverTo.onResult(gen, result, false);
            if (followUp != null) { synchronized (lock) { followUps++; } refresh(followUp); }
        }
    }

    /** Human-readable detail block. No IDs, coordinates or raw rows. */
    static String details(ReadinessModel.Result r, boolean cached) {
        StringBuilder b = new StringBuilder();
        b.append("樣本完成：").append(clock(r.sampleCompletedAt));
        if (r.sampleElapsedMs > 0) b.append("（耗時 ").append(r.sampleElapsedMs).append(" ms）");
        if (cached) b.append("　※ 5 秒內重複刷新，顯示快取樣本");
        b.append('\n').append(r.coreSummary).append('\n');
        for (ReadinessModel.FeatureReadiness f : r.features) {
            b.append('\n').append(f.label).append("　設定：").append(ReadinessModel.settingLabel(f.setting))
             .append("　就緒：").append(ReadinessModel.readinessLabel(f.readiness));
            if (f.work != ReadinessModel.Work.NOT_APPLICABLE) b.append("　工作：").append(f.work == ReadinessModel.Work.UNKNOWN ? "未知" : ReadinessModel.workLabel(f.work));
            b.append('\n');
            if (!f.reason.isEmpty()) b.append("　原因：").append(f.reason).append('\n');
            if (!f.next.isEmpty()) b.append("　下一步：").append(f.next).append('\n');
            if (f.dataAt > 0) b.append("　資料時點：").append(clock(f.dataAt)).append('\n');
            if (!f.codes.isEmpty()) b.append("　代碼：").append(String.join(", ", f.codes)).append('\n');
        }
        b.append("\n就緒＝資料來源與入口存在、新鮮且綁定本程序 PID，不等於該功能已實測成功。「待核對」含無 PID 來源只能觀察的情況。本檢查唯讀，不安裝、不重開、不清交易、不改開關。");
        return b.toString();
    }

    private static String clock(long ms) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.TAIWAN).format(new java.util.Date(ms));
    }
}

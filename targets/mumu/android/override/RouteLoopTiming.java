package dev.ody.pikmincontrol;

import java.util.Arrays;
import java.util.function.LongSupplier;

/**
 * Bounded timing statistics, pacing and exit diagnostics for the walk loop
 * (route harvest observation spec §4.3 / §4.4, review round 1 R1/R3).
 *
 * Pure Java (clock injected). Each segment of one loop (lease heartbeat, STATUS
 * wait, root observation read, resource check) is timed through {@link #timed},
 * which records in a {@code finally} so a segment that throws or times out still
 * leaves its duration and is remembered as the failed stage. Untimed decision
 * points register their name with {@link #stage} so an exception thrown there is
 * classified too. The whole loop is closed exactly once per iteration by
 * {@link #endLoop}, also from a {@code finally}.
 *
 * Statistics: per segment a ring of the last {@link #CAPACITY} samples with
 * nearest-rank p50 / p95 / max. The max is therefore the max of the retained
 * window, not of the whole trip; the loop count is unbounded. The per-loop log
 * line is emitted every loop; the p50/p95/max summary every 20 loops and at the end.
 *
 * Pacing: the wait after a loop is {@link #PERIOD_MS} minus the loop cost,
 * clamped to [{@link #MIN_WAIT_MS}, {@link #MAX_WAIT_MS}]. Consequences, stated
 * exactly: the shortest observation period is MAX_WAIT_MS (≈2 observations per
 * second when the loop itself is instantaneous), a slow loop (~950 ms) adds only
 * the minimum yield instead of a blind 250 ms, a fast loop waits longer than the
 * old fixed sleep, and no loop ever busy-spins. Only the observation read is paced
 * by this; the lease heartbeat (every 3 s) and the resource check (every 10 s)
 * are separate root operations and are not counted in that rate.
 */
final class RouteLoopTiming {
    static final long PERIOD_MS = 1000, MIN_WAIT_MS = 100, MAX_WAIT_MS = 500;
    static final int CAPACITY = 128;
    static final String[] SEGMENTS = {"heartbeat", "status", "read", "status-reply", "resource", "provider-recheck", "loop"};
    static final int DIAG_LIMIT = 1024;

    interface Op<T> { T run() throws Exception; }

    private final LongSupplier clock;
    private final long[][] ring = new long[SEGMENTS.length][CAPACITY];
    private final int[] count = new int[SEGMENTS.length];
    private int loops;
    private long loopAt = -1, lastLoopMs = -1;
    private String stage = "", failedStage = "", stopCode = "", stopDetail = "";
    // Per-loop sample attribution (round 2, S1): what THIS loop learned about the fix, never the previous loop's numbers.
    private String currentSample = "none", lastAccepted = "none", exitSample = "none";
    private String exitCode = "";
    private final StringBuilder milestones = new StringBuilder();
    static final int MILESTONE_LIMIT = 160;

    RouteLoopTiming(LongSupplier elapsedMs) { this.clock = elapsedMs; }

    /** Bounded wait after a loop that took {@code loopMs}. See the class comment for the exact rate consequences. */
    static long waitMs(long loopMs) {
        if (loopMs < 0) loopMs = 0;
        long wait = PERIOD_MS - loopMs;
        return Math.max(MIN_WAIT_MS, Math.min(MAX_WAIT_MS, wait));
    }

    /** Runs one timed segment; the duration is recorded even when it throws, and a throw marks the failed stage. */
    <T> T timed(String segment, Op<T> op) throws Exception {
        index(segment);
        stage = segment;
        long t0 = clock.getAsLong();
        try { return op.run(); }
        catch (Exception e) { if (failedStage.isEmpty()) failedStage = segment; throw e; }
        finally { record(segment, clock.getAsLong() - t0); }
    }
    /** Names an untimed decision point (require / verify / progress / active) so an exception there is classified. */
    void stage(String name) { stage = name; }
    /** Called on any exception that leaves the loop: the stage in effect becomes the failed stage unless a timed segment already claimed it. */
    void failed() { if (failedStage.isEmpty()) failedStage = stage; }
    String failedStage() { return failedStage; }
    String stage() { return stage; }

    void beginLoop() { loopAt = clock.getAsLong(); currentSample = "unknown"; }
    /**
     * The progress judge was consulted in this loop: record its detail as this loop's sample.
     * {@code accepted} means the sample passed (no progress failure); it then also becomes lastAccepted.
     * Until this is called, the loop's sample stays "unknown" (segment failed before a fix was judged).
     */
    void observed(String detail, boolean accepted) {
        currentSample = detail == null || detail.isEmpty() ? "unknown" : detail;
        if (accepted) lastAccepted = currentSample;
    }
    /** Closes the current loop exactly once (no-op when no loop is open). Safe from a finally. */
    void endLoop() {
        if (loopAt < 0) return;
        lastLoopMs = Math.max(0, clock.getAsLong() - loopAt);
        record("loop", lastLoopMs);
        loops++; loopAt = -1;
        exitSample = currentSample;   // the loop that exits (throw or break) leaves its own sample, possibly "unknown"
    }
    /** An ordered, bounded list of milestones reached (e.g. observation-endpoint-reached, pause-ok); never a success claim by itself. */
    void milestone(String name) {
        if (milestones.length() + name.length() + 1 > MILESTONE_LIMIT) return;
        if (milestones.length() > 0) milestones.append(',');
        milestones.append(name);
    }
    /** Names the exit decision taken by the caller (first wins): progress:<failure>, route-inactive, finished, ... */
    void exit(String code) { if (exitCode.isEmpty() && code != null && !code.isEmpty()) exitCode = code; }
    /** The loop condition ended the walk without a decision inside a loop: user stop or deadline. No loop sample belongs to this exit. */
    void exitOutsideLoop(boolean stopping) { if (exitCode.isEmpty()) exitCode = stopping ? "user-stop" : "deadline"; exitSample = "none"; }
    /** An exception left the walk: classify from the stage in effect unless a decision was already named. */
    void failed(Exception e) { failed(); if (exitCode.isEmpty()) exitCode = stopCodeFor(failedStage, e); }
    String exitCode() { return exitCode; }
    /** Bounded stop code: stage that failed plus the exception class, never its message (no coordinates/accounts). */
    static String stopCodeFor(String failedStage, Exception e) {
        String stage = failedStage == null || failedStage.isEmpty() ? "unknown" : failedStage;
        String kind = e instanceof InterruptedException ? "interrupted" : e instanceof IllegalStateException ? "failed" : e.getClass().getSimpleName();
        return stage + "-" + kind;
    }
    /**
     * Seals the diagnostics once the walk has left: the named exit (or "unknown"), the sample of the exiting loop
     * ("unknown" when that loop failed before judging a fix, "none" for an exit outside any loop), the last accepted
     * sample separately, gap counters and milestones. Idempotent; first call wins.
     */
    void finish(long maxGapMs, int toleratedGaps) {
        stop(exitCode.isEmpty() ? "unknown" : exitCode,
                "sample=" + exitSample + "; lastAccepted=" + lastAccepted + "; maxGapMs=" + maxGapMs + "; toleratedGaps=" + toleratedGaps
                + "; milestones=" + (milestones.length() == 0 ? "-" : milestones));
    }
    boolean loopOpen() { return loopAt >= 0; }
    /** Duration of the most recently closed loop, -1 before the first. */
    long lastLoopMs() { return lastLoopMs; }

    /** First stop reason wins; later calls (e.g. cleanup) never overwrite it. */
    void stop(String code, String detail) {
        if (!stopCode.isEmpty()) return;
        stopCode = code == null || code.isEmpty() ? "unknown" : code;
        stopDetail = detail == null ? "" : detail;
    }
    String stopCode() { return stopCode; }

    void record(String segment, long ms) {
        int i = index(segment);
        if (ms < 0) ms = 0;
        ring[i][count[i] % CAPACITY] = ms;
        count[i]++;
    }
    int loops() { return loops; }
    int samples(String segment) { return Math.min(count[index(segment)], CAPACITY); }

    /** p50 / p95 / max of the retained (last CAPACITY) samples of one segment; -1 when nothing recorded. */
    long[] percentiles(String segment) {
        int i = index(segment);
        int n = Math.min(count[i], CAPACITY);
        if (n == 0) return new long[]{-1, -1, -1};
        long[] sorted = Arrays.copyOf(ring[i], n);
        Arrays.sort(sorted);
        return new long[]{sorted[rank(n, 50)], sorted[rank(n, 95)], sorted[n - 1]};
    }
    /** Nearest-rank percentile index (1-based rank clamped into the array). */
    static int rank(int n, int percent) {
        int r = (int) Math.ceil(percent / 100.0 * n);
        return Math.max(0, Math.min(n - 1, r - 1));
    }

    /** One-line statistics, e.g. {@code loops=40 heartbeat=p50/p95/max(last128) status=... read=... loop=...}; segments never run are omitted. */
    String summary() {
        StringBuilder b = new StringBuilder("loops=").append(loops);
        for (String s : SEGMENTS) {
            long[] p = percentiles(s);
            if (p[0] < 0) continue;
            b.append(' ').append(s).append('=').append(p[0]).append('/').append(p[1]).append('/').append(p[2]);
        }
        return b.append(" (p50/p95/max of last ").append(CAPACITY).append(')').toString();
    }

    /**
     * Final diagnostics line, generated once the stop reason is known: stop code,
     * failed stage, progress and the timing summary. Bounded to {@link #DIAG_LIMIT};
     * carries no coordinates, accounts or task ids (callers pass only counts/codes).
     */
    String diag(int reached, int total) {
        String line = "stop=" + (stopCode.isEmpty() ? "unknown" : stopCode)
                + "; stage=" + (failedStage.isEmpty() ? "-" : failedStage)
                + "; reached=" + reached + "/" + total
                + (stopDetail.isEmpty() ? "" : "; " + stopDetail)
                + "; " + summary();
        return line.length() > DIAG_LIMIT ? line.substring(0, DIAG_LIMIT) : line;
    }

    private int index(String segment) {
        for (int i = 0; i < SEGMENTS.length; i++) if (SEGMENTS[i].equals(segment)) return i;
        throw new IllegalArgumentException(segment);
    }
}

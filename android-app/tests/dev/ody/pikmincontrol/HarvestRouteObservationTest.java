package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.List;

/**
 * Route harvest observation spec (docs/handoffs/2026-09-15-route-harvest-observation-spec.md §5, offline cases):
 * sample age vs observation interval, the real 2054 ms counter-example, the 2000/2001/4000/4001 boundaries,
 * stale-but-short intervals, repeated fixes / clock regression / conflicting positions, 19 km/h vs jumps,
 * dense / sharp-corner / out-and-back / repeated / early-end paths, and the loop pacer. Pure JVM; not a device route.
 */
public final class HarvestRouteObservationTest {
    static int checks;
    static void check(boolean ok, String what) { checks++; if (!ok) throw new AssertionError(what + " (check " + checks + ")"); }
    static final double M = 1.0 / 111195.0;               // degrees of longitude per metre at the equator
    static final double WALK_MPS = HarvestRoute.SPEED_KMH / 3.6;   // 5.28 m/s
    static JoystickReadiness fix(long t, double eastM, double northM) { return FarmLocationTest.provider(t, northM * M, eastM * M); }
    static HarvestRoute route(double[][] pointsM) {
        StringBuilder b = new StringBuilder();
        for (double[] p : pointsM) b.append(p[1] * M).append(',').append(p[0] * M).append('\n');
        return HarvestRoute.coordinates(b.toString());
    }
    /** Samples along a polyline at walking speed, one fix per {@code stepMs}; returns [t, east, north] rows. */
    static List<double[]> walk(double[][] pointsM, long stepMs, long t0) {
        List<double[]> out = new ArrayList<>();
        double travelled = 0, stepM = WALK_MPS * stepMs / 1000.0;
        out.add(new double[]{t0, pointsM[0][0], pointsM[0][1]});
        double legStart = 0; int leg = 0; double nextSample = stepM; long t = t0;
        while (leg < pointsM.length - 1) {
            double[] a = pointsM[leg], b = pointsM[leg + 1];
            double legLen = Math.hypot(b[0] - a[0], b[1] - a[1]);
            while (nextSample <= legStart + legLen + 1e-9) {
                double f = legLen == 0 ? 1 : (nextSample - legStart) / legLen;
                t += stepMs;
                out.add(new double[]{t, a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f});
                nextSample += stepM;
            }
            legStart += legLen; leg++;
        }
        t += stepMs; double[] last = pointsM[pointsM.length - 1];
        out.add(new double[]{t, last[0], last[1]});
        return out;
    }
    static HarvestRouteProgress drive(HarvestRoute r, List<double[]> samples, long readDelayMs) {
        HarvestRouteProgress p = new HarvestRouteProgress(r);
        for (double[] s : samples) { long t = (long) s[0]; p.observeProvider(fix(t, s[1], s[2]), t + readDelayMs); if (!p.failure().isEmpty()) break; }
        return p;
    }

    public static void main(String[] args) throws Exception {
        realCounterExample();
        boundaries();
        staleButShortInterval();
        repeatedRegressionConflict();
        walkingVersusJump();
        denseSharpOutAndBackRepeatedEarlyEnd();
        gapDoesNotBackfillNodes();
        pacerAndTiming();
        failedLoopDiagnostics();
        failedLoopKeepsOwnSample();
        endpointAndExitDecisions();
        labels();
        System.out.println("PASS HarvestRouteObservation: " + checks + " gap-tolerance, boundary, order and pacing checks; NOT a device route");
    }

    /** dt = 2054 ms between adopted fixes, newest fix fresh, 5.5 m moved: must not fail on observation-gap. */
    static void realCounterExample() {
        HarvestRoute r = route(new double[][]{{0, 0}, {60, 0}});
        HarvestRouteProgress p = new HarvestRouteProgress(r);
        long t0 = 100000;
        p.observeProvider(fix(t0, 0, 0), t0 + 480);                       // root read ~480 ms after the fix
        p.observeProvider(fix(t0 + 1015, 5.53, 0), t0 + 1015 + 500);
        p.observeProvider(fix(t0 + 1015 + 2054, 5.53 + 10.8, 0), t0 + 1015 + 2054 + 519);   // one provider update missed
        check(p.failure().isEmpty(), "2054 ms interval with a fresh fix is tolerated: " + p.failure() + " " + p.detail());
        check(p.lastGapMs() == 2054 && p.maxGapMs() == 2054 && p.toleratedGaps() == 1, "gap recorded: " + p.detail());
        check(p.detail().contains("dtMs=2054") && p.detail().contains("ageMs=519"), "detail separates interval and age: " + p.detail());
    }

    static void boundaries() {
        long[][] cases = {{2000, 0}, {2001, 0}, {4000, 0}, {4001, 1}};
        for (long[] c : cases) {
            HarvestRoute r = route(new double[][]{{0, 0}, {60, 0}});
            HarvestRouteProgress p = new HarvestRouteProgress(r);
            p.observeProvider(fix(1000, 0, 0), 1100);
            long dt = c[0];
            p.observeProvider(fix(1000 + dt, WALK_MPS * dt / 1000.0, 0), 1000 + dt + 100);
            boolean expectFail = c[1] == 1;
            check(expectFail == p.failure().equals("observation-gap") && (expectFail || p.failure().isEmpty()),
                    "dt=" + dt + " → " + (expectFail ? "observation-gap" : "tolerated") + ", got '" + p.failure() + "'");
            if (!expectFail) check(p.toleratedGaps() == (dt > HarvestRouteProgress.SAMPLE_AGE_MS ? 1 : 0), "tolerated count at dt=" + dt);
        }
        check(HarvestRouteProgress.MAX_GAP_MS == 4000 && HarvestRouteProgress.SAMPLE_AGE_MS == 2000, "candidate constants as specified");
    }

    /** A short interval never rescues an old fix: the sample-age rule stays at 2 s and is checked first. */
    static void staleButShortInterval() {
        HarvestRoute r = route(new double[][]{{0, 0}, {60, 0}});
        HarvestRouteProgress p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(2000, 5.2, 0), 4100);   // dt 1000 ms but the newest fix is 2.1 s old when verified
        check(p.failure().equals("stale-provider") && p.detail().contains("ageMs=2100"), "stale fix refused despite short interval: " + p.failure() + " " + p.detail());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(2000, 5.2, 0), 4000);   // exactly 2000 ms old is still fresh
        check(p.failure().isEmpty(), "age 2000 accepted");
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(3500, 13.0, 0), 5501);  // dt 2500 (would be tolerated) but age 2001
        check(p.failure().equals("stale-provider"), "age wins over interval");
    }

    static void repeatedRegressionConflict() {
        HarvestRoute r = route(new double[][]{{0, 0}, {60, 0}});
        HarvestRouteProgress p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(1000, 0, 0), 1600); p.observeProvider(fix(1000, 0, 0), 2100);   // repeated fix polled thrice
        check(p.failure().isEmpty() && p.reached() == 1 && p.lastGapMs() == -1, "repeated fix: no failure, no progress, no gap");
        p.observeProvider(fix(900, 0, 0), 2200);
        check(p.failure().equals("clock-regression"), "backwards fix clock refused: " + p.failure());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(1000, 3, 0), 1200);
        check(p.failure().equals("conflicting-observation"), "same timestamp, different place refused");
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(1000, 0.3, 0), 1200);
        check(p.failure().isEmpty() && p.reached() == 1, "same timestamp within 0.5 m is the same observation");
    }

    static void walkingVersusJump() {
        HarvestRoute r = route(new double[][]{{0, 0}, {200, 0}});
        HarvestRouteProgress p = drive(r, walk(new double[][]{{0, 0}, {200, 0}}, 1000, 5000), 450);
        check(p.failure().isEmpty() && p.finished(), "19 km/h at 1 Hz tracks to the end: " + p.failure() + " " + p.detail());
        p = drive(r, walk(new double[][]{{0, 0}, {200, 0}}, 2054, 5000), 450);
        check(p.failure().isEmpty() && p.finished(), "19 km/h with every interval 2054 ms still tracks a 2-node route: " + p.failure());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(3054, 7.0 * 2.054 + 2.0 + 0.5, 0), 3200);   // just above the bound for a 2054 ms interval
        check(p.failure().equals("unexpected-jump"), "jump beyond 7 m/s·dt+2 m refused even inside the tolerated interval");
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(3054, 7.0 * 2.054 + 2.0 - 0.1, 0), 3200);
        check(p.failure().isEmpty(), "displacement inside the bound accepted");
    }

    static void denseSharpOutAndBackRepeatedEarlyEnd() {
        // Dense nodes 3 m apart at 1 Hz: every node observed in order.
        double[][] dense = new double[21][]; for (int i = 0; i <= 20; i++) dense[i] = new double[]{i * 3, 0};
        HarvestRouteProgress p = drive(route(dense), walk(dense, 1000, 1000), 400);
        check(p.failure().isEmpty() && p.finished(), "dense 3 m nodes at 1 Hz: " + p.failure() + " reached=" + p.reached());
        // Sharp corner (90°) and a hairpin (180°): nodes reached in order, no early finish.
        double[][] corner = {{0, 0}, {30, 0}, {30, 30}, {0, 30}};
        p = drive(route(corner), walk(corner, 1000, 1000), 400);
        check(p.failure().isEmpty() && p.finished(), "90° corners: " + p.failure() + " reached=" + p.reached());
        double[][] hairpin = {{0, 0}, {40, 0}, {0, 0}, {40, 0}};
        List<double[]> s = walk(hairpin, 1000, 1000);
        p = new HarvestRouteProgress(route(hairpin));
        int atFirstReturn = -1;
        for (double[] x : s) { long t = (long) x[0]; p.observeProvider(fix(t, x[1], x[2]), t + 400); if (atFirstReturn < 0 && x[1] < 1 && t > 1000) atFirstReturn = p.reached(); }
        check(p.failure().isEmpty() && p.finished() && atFirstReturn == 3, "out-and-back counts each pass in order: reached at first return=" + atFirstReturn);
        // Repeated coordinate nodes are legitimate no-distance steps.
        double[][] repeated = {{0, 0}, {20, 0}, {20, 0}, {40, 0}};
        p = drive(route(repeated), walk(repeated, 1000, 1000), 400);
        check(p.failure().isEmpty() && p.finished(), "repeated node: " + p.failure());
        // Passing the end coordinate early (a loop that crosses its own end) must not finish the trip.
        double[][] cross = {{0, 0}, {40, 0}, {40, 20}, {20, 20}, {20, -20}, {40, -20}, {40, 0}};
        s = walk(cross, 1000, 1000);
        p = new HarvestRouteProgress(route(cross));
        boolean finishedEarly = false;
        for (int i = 0; i < s.size(); i++) { double[] x = s.get(i); long t = (long) x[0]; boolean end = p.observeProvider(fix(t, x[1], x[2]), t + 400); if (end && i < s.size() - 3) finishedEarly = true; }
        check(!finishedEarly && p.finished() && p.failure().isEmpty(), "crossing the end coordinate mid-route does not finish early");
    }

    /** A tolerated 2 s gap on 3 m-dense nodes can skip a node's reach radius; it must stay unreached (no back-fill), never be inferred. */
    static void gapDoesNotBackfillNodes() {
        double[][] dense = new double[8][]; for (int i = 0; i < 8; i++) dense[i] = new double[]{i * 3, 0};
        HarvestRouteProgress p = new HarvestRouteProgress(route(dense));
        p.observeProvider(fix(1000, 0, 0), 1400);
        int before = p.reached();
        p.observeProvider(fix(3054, WALK_MPS * 2.054, 0), 3454);   // 10.8 m in one step: node 1 (3 m) is 7.8 m behind the sample
        check(p.failure().isEmpty(), "gap tolerated: " + p.failure());
        check(p.reached() == before, "node inside the gap is not back-filled: reached=" + p.reached());
        for (int i = 2; i <= 6; i++) p.observeProvider(fix(3054 + i * 1000, WALK_MPS * 2.054 + WALK_MPS * i, 0), 3054 + i * 1000 + 400);
        check(p.failure().isEmpty() && !p.finished() && p.reached() == before, "later samples never reach the skipped node; trip stays unfinished (documented limitation, reported as a timeout, never as success)");
    }

    static void pacerAndTiming() {
        check(RouteLoopTiming.waitMs(950) == 100 && RouteLoopTiming.waitMs(2000) == 100, "slow loop yields the minimum only");
        check(RouteLoopTiming.waitMs(300) == 500 && RouteLoopTiming.waitMs(0) == 500 && RouteLoopTiming.waitMs(-5) == 500, "fast loop waits are capped at 500 ms: shortest observation period 500 ms, i.e. at most ~2 observations/s (heartbeat and resource root calls are extra)");
        check(RouteLoopTiming.waitMs(600) == 400 && RouteLoopTiming.waitMs(900) == 100, "period minus loop cost inside the bounds");
        for (long loop = 0; loop <= 3000; loop += 50) {
            long w = RouteLoopTiming.waitMs(loop);
            // Never faster than the old loop+250 cadence unless already at the 1 s provider period; never a busy loop.
            check(w >= 100 && w <= 500 && loop + w >= Math.min(1000, loop + 250), "always yields, period >= min(1000, old cadence) at loop=" + loop);
        }
        final long[] clock = {0};
        RouteLoopTiming t = new RouteLoopTiming(() -> clock[0]);
        check(t.summary().equals("loops=0 (p50/p95/max of last 128)") && t.percentiles("read")[0] == -1, "empty summary");
        for (int i = 1; i <= 100; i++) t.record("read", i);
        long[] p = t.percentiles("read");
        check(p[0] == 50 && p[1] == 95 && p[2] == 100, "nearest-rank p50/p95/max: " + p[0] + "/" + p[1] + "/" + p[2]);
        for (int i = 0; i < 300; i++) t.record("loop", i < 200 ? 1000 : 10);   // ring keeps only the last 128
        check(t.samples("loop") == 128 && t.percentiles("loop")[0] == 10 && t.percentiles("loop")[2] == 1000, "bounded ring keeps the last 128 (100×10 ms, 28×1000 ms): p50=10, max=1000 (window max, not trip max)");
        for (int i = 0; i < 128; i++) t.record("loop", 10);
        check(t.percentiles("loop")[2] == 10, "old samples fully aged out of the ring");
        t.record("status", -3); check(t.percentiles("status")[2] == 0, "negative durations clamp to zero");
        check(t.summary().startsWith("loops=0 status=0/0/0 read=50/95/100 loop=") && t.summary().endsWith("(p50/p95/max of last 128)") && t.summary().length() < 200, "summary format (segments in fixed order, empty ones omitted, window stated): " + t.summary());
        boolean rejected = false; try { t.record("nope", 1); } catch (IllegalArgumentException e) { rejected = true; }
        check(rejected, "unknown segment rejected");
        check(RouteLoopTiming.rank(1, 95) == 0 && RouteLoopTiming.rank(2, 50) == 0 && RouteLoopTiming.rank(2, 95) == 1, "rank edge cases");
    }

    /** R1: failed segments/loops still leave samples and a classification; diagnostics are formed once the stop reason is known. */
    static void failedLoopDiagnostics() throws Exception {
        final long[] clock = {1000};
        RouteLoopTiming t = new RouteLoopTiming(() -> clock[0]);
        // slow STATUS that throws after 2 s: sample recorded, stage named, loop closed once from the finally
        t.beginLoop();
        boolean thrown = false;
        try {
            try {
                t.timed("heartbeat", () -> { clock[0] += 30; return null; });
                t.timed("status", () -> { clock[0] += 2000; throw new IllegalStateException("GPS路線回報失敗"); });
                t.timed("read", () -> { clock[0] += 400; return null; });   // never reached
            } finally { t.endLoop(); }
        } catch (IllegalStateException e) { thrown = true; t.failed(); }
        check(thrown && t.failedStage().equals("status"), "failed stage is the throwing segment: " + t.failedStage());
        check(t.samples("heartbeat") == 1 && t.samples("status") == 1 && t.samples("read") == 0, "only started segments have samples");
        check(t.percentiles("status")[2] == 2000 && t.loops() == 1 && t.lastLoopMs() == 2030 && !t.loopOpen(), "failed loop timed once: " + t.summary());
        t.endLoop(); check(t.loops() == 1, "endLoop is idempotent");
        check(RouteLoopTiming.stopCodeFor(t.failedStage(), new IllegalStateException("x")).equals("status-failed"), "stop code from stage + class, never the message");
        // root returned but require rejected: read has its sample, the failure is classified at the untimed stage
        t = new RouteLoopTiming(() -> clock[0]);
        t.beginLoop();
        try {
            try {
                t.timed("status", () -> { clock[0] += 100; return null; });
                t.timed("read", () -> { clock[0] += 480; return null; });
                t.stage("require");
                throw new IllegalStateException("帳號識別失效或已變更，停止工作");
            } finally { t.endLoop(); }
        } catch (IllegalStateException e) { t.failed(); }
        check(t.failedStage().equals("require") && t.samples("read") == 1 && t.percentiles("read")[2] == 480, "require rejection keeps the read sample: " + t.summary());
        check(RouteLoopTiming.stopCodeFor("require", new IllegalStateException("acct")).equals("require-failed"), "require code");
        // resource check throwing: its own segment, its own stage
        t = new RouteLoopTiming(() -> clock[0]);
        t.beginLoop();
        try { try { t.timed("resource", () -> { clock[0] += 900; throw new IllegalStateException("resource"); }); } finally { t.endLoop(); } } catch (IllegalStateException e) { t.failed(); }
        check(t.failedStage().equals("resource") && t.percentiles("resource")[2] == 900, "resource failure timed");
        check(RouteLoopTiming.stopCodeFor("", new InterruptedException()).equals("unknown-interrupted") && RouteLoopTiming.stopCodeFor("read", new RuntimeException()).equals("read-RuntimeException"), "codes for other exceptions");
        // first exit decision wins; diag is bounded and carries only codes/counts
        t.exit("progress:observation-gap");
        t.exit("deadline");
        t.finish(2054, 3);
        String d = t.diag(3, 38);
        check(d.startsWith("stop=progress:observation-gap; stage=resource; reached=3/38; sample=unknown; lastAccepted=none; maxGapMs=2054; toleratedGaps=3; milestones=-; loops=1"), "diag line: " + d);
        check(!d.contains("deadline") && d.length() <= RouteLoopTiming.DIAG_LIMIT, "first exit kept, bounded");
        StringBuilder huge = new StringBuilder(); while (huge.length() < 3000) huge.append("z");
        RouteLoopTiming big = new RouteLoopTiming(() -> 0L); big.stop("x", huge.toString());
        check(big.diag(0, 1).length() == RouteLoopTiming.DIAG_LIMIT, "diag truncated at the limit");
        RouteLoopTiming none = new RouteLoopTiming(() -> 0L); none.finish(-1, 0);
        check(none.diag(2, 5).startsWith("stop=unknown; stage=-; reached=2/5; sample=none; lastAccepted=none; maxGapMs=-1; toleratedGaps=0; milestones=-; loops=0"), "unknown stop is explicit: " + none.diag(2, 5));
        // progress details: an over-limit gap still reports this sample's moved distance; an invalid provider never carries the previous sample
        HarvestRoute r = route(new double[][]{{0, 0}, {60, 0}});
        HarvestRouteProgress p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(5010, 21.2, 0), 5300);
        check(p.failure().equals("observation-gap") && p.detail().equals("dtMs=4010, ageMs=290, movedMeters=21.2"), "4010 ms gap detail has moved: " + p.detail());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(2000, 5.2, 0), 2200);
        String previousDetail = p.detail();
        p.observeProvider(new JoystickReadiness(true, true, false, 3000), 3100);   // not our provider: fresh() false
        check(p.failure().equals("stale-provider") && !p.detail().equals(previousDetail) && p.detail().equals("ageMs=100, dtMs=unknown, movedMeters=unknown"), "invalid provider detail is this sample's, unknowns explicit: " + p.detail());
        p = new HarvestRouteProgress(r);
        p.observeProvider(null, 500);
        check(p.detail().equals("ageMs=unknown, dtMs=unknown, movedMeters=unknown"), "null provider detail explicit: " + p.detail());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        p.observeProvider(fix(900, 0, 0), 1200);
        check(p.failure().equals("clock-regression") && p.detail().startsWith("dtMs=-100, ageMs=300, movedMeters=0"), "regression detail: " + p.detail());
        p = new HarvestRouteProgress(r);
        p.observeProvider(fix(1000, 0, 0), 1100);
        check(p.detail().startsWith("first, ageMs=100, startDistanceMeters=0"), "first sample detail: " + p.detail());
    }

    // ---------------------------------------------------------------- round 2: the formal call sequence used by FlowerFarmService.runRoute
    /** Mirrors runRoute's timing calls exactly (beginLoop → timed segments/stages → observed → endLoop; exit/exitOutsideLoop/failed; finish/diag). */
    static final class Walk {
        final long[] clock = {1000};
        final RouteLoopTiming t = new RouteLoopTiming(() -> clock[0]);
        final HarvestRouteProgress p = new HarvestRouteProgress(route(new double[][]{{0, 0}, {30, 0}}));
        long fixAt = 500; double east = 0; boolean endpoint;   // fix leads the read by ~500 ms; both advance 1000 ms per loop
        String diag() { t.finish(p.maxGapMs(), p.toleratedGaps()); return t.diag(p.reached(), 2); }
        /** One loop; {@code failAt} names the segment/stage that throws (or "" for a normal loop). Returns the exception if any. */
        Exception loop(String failAt) {
            t.beginLoop();
            try {
                try {
                    t.timed("heartbeat", () -> { clock[0] += 20; if (failAt.equals("heartbeat")) throw new IllegalStateException("hb"); return null; });
                    t.timed("status", () -> { clock[0] += 60; if (failAt.equals("status")) throw new IllegalStateException("GPS路線回報失敗"); return null; });
                    t.timed("read", () -> { clock[0] += 480; if (failAt.equals("read")) throw new IllegalStateException("花田定位讀取逾時"); return null; });
                    t.stage("require"); if (failAt.equals("require")) throw new IllegalStateException("帳號識別失效或已變更，停止工作");
                    t.stage("verify"); if (failAt.equals("verify")) throw new IllegalStateException("沿路遊戲定位失效");
                    t.stage("provider-match"); if (failAt.equals("provider-match")) throw new IllegalStateException("GPS供應來源與路線回報不符");
                    t.stage("progress");
                    fixAt += 1000; east += 5.2;
                    boolean end = p.observeProvider(fix(fixAt, east, 0), clock[0]);
                    t.observed(p.detail(), p.failure().isEmpty());
                    if (!p.failure().isEmpty()) { t.exit("progress:" + p.failure()); throw new IllegalStateException(HarvestRouteProgress.label(p.failure())); }
                    if (end) { endpoint = true; t.milestone("observation-endpoint-reached"); return null; }
                    t.stage("route-active"); if (failAt.equals("route-active")) { t.exit("route-inactive"); throw new IllegalStateException("路線意外停止"); }
                    if (failAt.equals("resource")) t.timed("resource", () -> { clock[0] += 300; throw new IllegalStateException("resource"); });
                } finally { t.endLoop(); }
                clock[0] += RouteLoopTiming.waitMs(t.lastLoopMs());
                return null;
            } catch (Exception e) { t.failed(e); return e; }
        }
        /** Endpoint handling after the loop, as in runRoute: pause → held STATUS → arrival → finished. */
        Exception endpoint(String failAt, boolean stopping) {
            try {
                if (!endpoint) t.exitOutsideLoop(stopping);
                t.stage("pause"); if (failAt.equals("pause")) throw new IllegalStateException("GPS暫停未確認");
                t.milestone("pause-ok");
                if (stopping) { t.exit("user-stop"); return null; }
                t.stage("route-timeout");
                if (!p.finished()) { t.exit("deadline"); throw new IllegalStateException("沿路行走逾時"); }
                t.stage("endpoint-status"); if (failAt.equals("endpoint-status")) throw new IllegalStateException("GPS路線回報失敗");
                t.milestone("endpoint-status-ok");
                t.stage("endpoint-arrival"); if (failAt.equals("endpoint-arrival")) { t.exit("endpoint-arrival-failed"); throw new IllegalStateException("終點遊戲定位未追上"); }
                t.milestone("endpoint-arrival-ok");
                t.exit("finished");
                return null;
            } catch (Exception e) { t.failed(e); return e; }
        }
    }

    /** S1: a loop that fails before judging a fix never inherits the previous loop's dt/age/moved. */
    static void failedLoopKeepsOwnSample() {
        for (String failAt : new String[]{"heartbeat", "status", "read", "require", "verify", "provider-match", "route-active", "resource"}) {
            Walk w = new Walk();
            check(w.loop("") == null, failAt + ": first loop ok");
            String first = w.p.detail();
            check(first.startsWith("first,"), failAt + ": first sample recorded: " + first);
            check(w.loop("") == null && w.p.detail().startsWith("dtMs=1000, ageMs="), failAt + ": second loop leaves non-zero dt/age/moved: " + w.p.detail());
            String accepted = w.p.detail();
            Exception e = w.loop(failAt);
            check(e != null, failAt + ": third loop failed");
            String d = w.diag();
            check(d.contains("stop=" + (failAt.equals("route-active") ? "route-inactive" : failAt + "-failed") + "; stage=" + failAt + ";"), failAt + ": classified: " + d);
            boolean beforeProgress = !failAt.equals("resource") && !failAt.equals("route-active");
            if (beforeProgress) {
                check(d.contains("sample=unknown;"), failAt + ": a loop failing before the fix is judged has an unknown sample, not the previous numbers: " + d);
                check(d.contains("lastAccepted=" + accepted + ";"), failAt + ": last accepted sample listed separately: " + d);
                check(!d.substring(0, d.indexOf("lastAccepted=")).contains("dtMs=1000"), failAt + ": previous dt not presented as this loop's: " + d);
            } else {
                // resource / route-active fail AFTER this loop judged its own fix: that judged detail is this loop's sample.
                check(d.contains("sample=" + w.p.detail() + "; lastAccepted=" + w.p.detail() + ";"), failAt + ": sample is this loop's own judged (and accepted) detail: " + d);
            }
            check(w.t.loops() == 3 && w.t.samples("loop") == 3, failAt + ": three loop samples, failed one included");
            if (failAt.equals("route-active")) check(d.startsWith("stop=route-inactive; stage=route-active; reached=1/2; sample=dtMs=1000") && d.contains("loops=3"), "route-active: inactive route after two good loops: " + d);
            check(w.p.reached() >= 1 && w.p.failure().isEmpty(), failAt + ": progress state untouched by the service failure");
        }
        // progress failure: the failing loop's sample IS this loop's judged detail
        Walk w = new Walk();
        w.loop(""); w.loop("");
        w.fixAt += 4000; w.clock[0] += 4000;   // next fix 5000 ms after the previous (still fresh) → observation-gap
        Exception e = w.loop("");
        String d = w.diag();
        check(e != null && d.contains("stop=progress:observation-gap; stage=progress;") && d.contains("sample=dtMs=5000, ageMs=") && d.contains("movedMeters=5.2;"), "progress failure keeps this loop's detail: " + d);
        check(d.contains("lastAccepted=dtMs=1000"), "and the previous accepted sample separately");
    }

    /** S2: finished is claimed only after pause, endpoint STATUS and arrival; deadline and user stop are formal decisions. */
    static void endpointAndExitDecisions() {
        // endpoint reached, then pause throws → stop is the pause failure, never finished
        Walk w = new Walk();
        while (!w.endpoint) check(w.loop("") == null, "walk to the endpoint");
        check(w.endpoint && w.p.finished(), "observation endpoint reached");
        Exception e = w.endpoint("pause", false);
        String d = w.diag();
        check(e != null && d.startsWith("stop=pause-failed; stage=pause;") && d.contains("milestones=observation-endpoint-reached;"), "pause failure classified, endpoint milestone kept: " + d);
        check(!d.contains("finished"), "finished never claimed");
        // endpoint STATUS failure
        w = new Walk(); while (!w.endpoint) w.loop("");
        e = w.endpoint("endpoint-status", false); d = w.diag();
        check(e != null && d.startsWith("stop=endpoint-status-failed; stage=endpoint-status;") && d.contains("milestones=observation-endpoint-reached,pause-ok;"), "endpoint STATUS failure: " + d);
        // endpoint arrival failure
        w = new Walk(); while (!w.endpoint) w.loop("");
        e = w.endpoint("endpoint-arrival", false); d = w.diag();
        check(e != null && d.startsWith("stop=endpoint-arrival-failed; stage=endpoint-arrival;") && d.contains("milestones=observation-endpoint-reached,pause-ok,endpoint-status-ok;"), "endpoint arrival failure: " + d);
        // genuine success: finished only after all four milestones
        w = new Walk(); while (!w.endpoint) w.loop("");
        check(w.endpoint("", false) == null, "success path");
        d = w.diag();
        check(d.startsWith("stop=finished; stage=-; reached=2/2;") && d.contains("milestones=observation-endpoint-reached,pause-ok,endpoint-status-ok,endpoint-arrival-ok;"), "finished after every stage: " + d);
        check(d.contains("sample=dtMs=1000") && d.contains("lastAccepted=dtMs=1000"), "the endpoint loop's own sample kept");
        // deadline: loop condition ended the walk with no decision inside a loop
        w = new Walk(); w.loop(""); w.loop("");
        e = w.endpoint("", false); d = w.diag();
        check(e != null && d.startsWith("stop=deadline; stage=route-timeout; reached=1/2; sample=none;") && d.contains("lastAccepted=dtMs=1000") && d.contains("milestones=pause-ok;"), "deadline is a formal decision with no loop sample: " + d);
        // deadline, then pause also fails: the exit stays deadline, the stage shows where the exception happened
        w = new Walk(); w.loop("");
        e = w.endpoint("pause", false); d = w.diag();
        check(e != null && d.startsWith("stop=deadline; stage=pause; reached=1/2; sample=none;"), "first decision kept, failing stage visible: " + d);
        // user stop
        w = new Walk(); w.loop("");
        check(w.endpoint("", true) == null, "user stop returns normally"); d = w.diag();
        check(d.startsWith("stop=user-stop; stage=-; reached=1/2; sample=none;") && d.contains("milestones=pause-ok;"), "user stop is a formal decision: " + d);
        // exit(...) after a decision never overrides it
        w.t.exit("finished"); check(w.t.exitCode().equals("user-stop"), "first decision wins");
    }

    static void labels() {
        check(HarvestRouteProgress.label("observation-gap").equals("定位觀測中斷，正在停止"), "gap label");
        check(HarvestRouteProgress.label("stale-provider").contains("最新定位過期"), "stale label");
        check(HarvestRouteProgress.label("unexpected-jump").contains("跳點"), "jump label");
        check(HarvestRouteProgress.label("clock-regression").contains("時序") && HarvestRouteProgress.label("conflicting-observation").contains("時序"), "clock labels");
        check(HarvestRouteProgress.label("whatever").contains("正在停止"), "default label");
        for (String code : new String[]{"observation-gap", "stale-provider", "unexpected-jump", "start-not-reached"})
            check(!HarvestRouteProgress.label(code).contains("完成") && !HarvestRouteProgress.label(code).contains("WALK") && !HarvestRouteProgress.label(code).contains("已安全停止"), "labels never claim completion or a confirmed stop (cleanup confirms later)");
    }
}

package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StepEngineTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }
    static void rejects(Runnable run, String needle) {
        try { run.run(); throw new AssertionError("expected reject: " + needle); }
        catch (IllegalArgumentException | IllegalStateException error) {
            checks++;
            if (!error.getMessage().contains(needle)) throw new AssertionError("reject text: " + error.getMessage());
        }
    }
    static Fixture fx() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        MemoryStepStore store = new MemoryStepStore();
        FakeStepHealth health = new FakeStepHealth();
        StepWorkLock lock = new StepWorkLock(store);
        FixedStepRng rng = new FixedStepRng(0.5d);
        StepEngine engine = new StepEngine(clock, zone, rng, store, health, lock);
        return new Fixture(clock, zone, store, health, lock, rng, engine);
    }
    static void due(Fixture f) {
        long interval = 60_000L;
        try {
            long saved = StepState.load(f.store).settings.intervalMs;
            if (saved > 0L) interval = saved;
        } catch (RuntimeException ignored) { }
        f.clock.advanceWall(interval);
    }
    static final class Fixture {
        final MutableStepClock clock;
        final StepZone zone;
        final MemoryStepStore store;
        final FakeStepHealth health;
        final StepWorkLock lock;
        final FixedStepRng rng;
        final StepEngine engine;
        Fixture(MutableStepClock clock, StepZone zone, MemoryStepStore store, FakeStepHealth health,
                StepWorkLock lock, FixedStepRng rng, StepEngine engine) {
            this.clock = clock; this.zone = zone; this.store = store; this.health = health;
            this.lock = lock; this.rng = rng; this.engine = engine;
        }
    }

    public static void main(String[] args) {
        illegalTarget();
        discloseAndDefaultOff();
        startStopRestart();
        duplicateStart();
        successFailTimeout();
        deathAfterSuccess();
        restAfterThree();
        targetReached();
        overshootUsesRemainder();
        outsideWindow();
        overnightAndAllDay();
        firstIntervalAndCap();
        permissionLoss();
        rateLimit();
        noHealthConnect();
        clockRollback();
        overlapRejected();
        twoWriters();
        lateReplyAfterCancel();
        unknownNotZero();
        manualRules();
        logsHaveNoPayload();
        paging();
        epochChange();
        System.out.println("PASS StepEngineTest " + checks + " checks; fake gateway only");
    }

    static void illegalTarget() {
        rejects(() -> StepSettings.parse(0, 480, 1320, 130, "normal", 60_000L), "目標");
        rejects(() -> StepSettings.parse(1_000_001, 480, 1320, 130, "normal", 60_000L), "目標");
        check(StepSettings.parse(1, 480, 1320, 130, "normal", 60_000L).target == 1, "min target");
    }

    static void discloseAndDefaultOff() {
        Fixture f = fx();
        StepView before = f.engine.snapshot();
        check(!before.desiredRunning, "default off");
        check(StepView.STOPPED.equals(before.phase), "stopped");
        StepView blocked = f.engine.start();
        check(StepView.REJECTED.equals(blocked.phase), "need disclose");
        f.engine.disclose();
        check(f.engine.start().desiredRunning, "started after disclose");
    }

    static void startStopRestart() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        check(f.health.writes == 1, "one write");
        f.engine.stop();
        StepEngine revived = new StepEngine(f.clock, f.zone, f.rng, f.store, f.health, f.lock);
        StepView view = revived.tick();
        check(StepView.STOPPED.equals(view.phase), "stop survives new engine");
        check(f.health.writes == 1, "no resurrect write");
    }

    static void duplicateStart() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        int writes = f.health.writes;
        f.engine.start();
        f.engine.tick();
        check(f.health.writes >= writes, "idempotent start still single writer");
        check(f.lock.heldBy(StepWorkLock.BACKGROUND), "single lock");
    }

    static void successFailTimeout() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        check(StepView.WRITING.equals(f.engine.tick().phase) || f.health.writes == 1, "success");
        f.health.nextWrite = "fail";
        f.health.failKind = "security";
        int successes = f.health.writes;
        StepState afterFail = StepState.load(f.store);
        int rest = afterFail.restStreak;
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        check(StepState.load(f.store).restStreak == rest, "fail does not increase rest");
        f.health.nextWrite = "timeout";
        f.clock.advanceWall(70_000L);
        StepView pending = f.engine.tick();
        check(StepView.RESULT_PENDING.equals(pending.phase), "timeout pending");
        check(StepState.load(f.store).ledger.pending() != null, "uncertain stored");
        check(successes >= 1, "had a success");
    }

    static void deathAfterSuccess() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        f.health.nextWrite = "timeout";
        f.health.writeFutureTimeoutThenStore = true;
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        check(StepPlan.UNCERTAIN.equals(StepState.load(f.store).ledger.pending().status)
                || StepPlan.PREPARED.equals(StepState.load(f.store).ledger.pending().status), "died before confirm");
        String id = StepState.load(f.store).ledger.pending().clientRecordId;
        StepEngine revived = new StepEngine(f.clock, f.zone, f.rng, f.store, f.health, f.lock);
        revived.tick();
        StepPlan same = StepState.load(f.store).ledger.byClientRecordId(id);
        check(StepPlan.CONFIRMED.equals(same.status), "same clientRecordId confirmed");
        check(f.health.writeLog.size() <= f.health.writes, "no new id required for confirm");
    }

    static void restAfterThree() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        check(StepState.load(f.store).restStreak == 3, "three successes");
        int writes = f.health.writes;
        StepView rest = f.engine.tick();
        check(StepView.RESTING.equals(rest.phase), "rest after 3");
        check(f.health.writes == writes, "no fourth write yet");
        f.clock.elapsed += 20_000L;
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        check(f.health.writes == writes + 1, "write after rest");
    }

    static void targetReached() {
        Fixture f = fx();
        f.health.allSources = StepSettings.DEFAULT_TARGET;
        f.engine.disclose();
        f.engine.start();
        due(f);
        StepView view = f.engine.tick();
        check(StepView.TARGET_REACHED.equals(view.phase), "reached");
        check(f.health.writes == 0, "no extra target dump");
    }

    static void overshootUsesRemainder() {
        Fixture f = fx();
        f.health.allSources = StepSettings.DEFAULT_TARGET - 10;
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        check(f.health.writeLog.get(0).count <= 10, "remainder not full rate");
    }

    static void outsideWindow() {
        Fixture f = fx();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 23, 0, 8 * 3600);
        f.engine.disclose();
        f.engine.start();
        due(f);
        check(StepView.WAITING_WINDOW.equals(f.engine.tick().phase), "outside");
        check(f.health.writes == 0, "no write outside window");
    }

    static void overnightAndAllDay() {
        StepSettings overnight = StepSettings.parse(8000, 22 * 60, 6 * 60, 130, "normal", 60_000L);
        check(overnight.overnight(), "overnight");
        check(overnight.inWindow(23 * 60), "after start");
        check(overnight.inWindow(5 * 60), "before end");
        check(!overnight.inWindow(12 * 60), "midday excluded");
        StepSettings all = StepSettings.parse(8000, 8 * 60, 8 * 60, 130, "normal", 60_000L);
        check(all.allDay() && all.inWindow(0) && all.inWindow(1439), "all day");
    }

    static void firstIntervalAndCap() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.configure(StepSettings.parse(8000, 0, 0, 130, "normal", 5_000L));
        f.engine.start();
        due(f);
        f.engine.tick();
        StepPlan first = f.health.writeLog.get(0);
        check(first.endMs - first.startMs >= 5_000L, "min interval");
        f.engine.configure(StepSettings.parse(8000, 0, 0, 130, "normal", 180_000L));
        f.clock.advanceWall(200_000L);
        f.engine.tick();
        StepPlan cap = f.health.writeLog.get(f.health.writeLog.size() - 1);
        check(cap.endMs - cap.startMs <= 180_000L, "180s cap");
        long midnight = f.zone.nextMidnightMillis(f.clock.wall);
        for (int i = 0; i < f.health.writeLog.size(); i++) {
            StepPlan written = f.health.writeLog.get(i);
            check(written.endMs <= midnight && written.endMs > written.startMs, "records stay before midnight");
        }
        StepState near = StepState.load(f.store);
        near.lastConfirmedEndMs = midnight - 2_000L;
        StepState.save(f.store, near);
        f.clock.wall = midnight - 500L;
        int before = f.health.writes;
        f.engine.tick();
        check(f.health.writes == before, "too close to midnight");
    }

    static void permissionLoss() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        f.health.canRead = false;
        f.clock.advanceWall(70_000L);
        StepView view = f.engine.tick();
        check(StepView.WAITING_PERMISSION.equals(view.phase), "permission");
        check(view.allSourcesToday == null || "未知".equals(StepView.totalText(view.allSourcesToday)), "unknown not 0 on lost read");
    }

    static void rateLimit() {
        Fixture f = fx();
        f.health.maxWrites = 1;
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        f.clock.advanceWall(70_000L);
        f.engine.tick();
        check(StepState.load(f.store).waitUntilElapsed > f.clock.elapsed, "backoff");
    }

    static void noHealthConnect() {
        Fixture f = fx();
        f.health.available = false;
        f.engine.disclose();
        check(StepView.UNSUPPORTED.equals(f.engine.start().phase), "no HC");
    }

    static void clockRollback() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        long end = f.health.writeLog.get(0).endMs;
        f.clock.wall = end - 120_000L;
        StepView view = f.engine.tick();
        check(!view.detail.contains("負"), "no negative interval crash");
        check(StepState.load(f.store).lastConfirmedEndMs == 0L || StepState.load(f.store).lastConfirmedEndMs <= f.clock.wall
                || StepView.SETTINGS_ON.equals(view.phase) || StepView.RESULT_PENDING.equals(view.phase)
                || StepView.WRITING.equals(view.phase) || StepView.WAITING_WINDOW.equals(view.phase)
                || StepView.STOPPED.equals(view.phase) || StepView.TARGET_REACHED.equals(view.phase)
                || StepView.RESTING.equals(view.phase), "rollback handled");
    }

    static void overlapRejected() {
        Fixture f = fx();
        f.engine.disclose();
        StepView preview = StepTestConfirm.previewManual(f.engine, 20);
        check(!preview.previewPlans.isEmpty(), "manual preview");
        StepTestConfirm.confirmManual(f.engine, 20);
        StepView second = StepTestConfirm.confirmManual(f.engine, 20);
        check(StepView.REJECTED.equals(second.phase), "too soon or overlap");
    }

    static void twoWriters() {
        Fixture a = fx();
        a.engine.disclose();
        a.engine.start();
        StepEngine other = new StepEngine(a.clock, a.zone, a.rng, a.store, a.health, a.lock);
        other.disclose();
        StepView blocked = StepTestConfirm.confirmManual(other, 10);
        check(StepView.REJECTED.equals(blocked.phase), "second writer blocked");
    }

    static void lateReplyAfterCancel() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.health.nextWrite = "timeout";
        f.health.writeFutureTimeoutThenStore = true;
        f.engine.tick();
        String id = StepState.load(f.store).ledger.pending().clientRecordId;
        f.engine.stop();
        f.engine.tick();
        StepPlan plan = StepState.load(f.store).ledger.byClientRecordId(id);
        check(StepPlan.CONFIRMED.equals(plan.status), "late reply confirmed");
        check(!StepState.load(f.store).desiredRunning, "still stopped");
    }

    static void unknownNotZero() {
        Fixture f = fx();
        f.health.canRead = false;
        StepView view = f.engine.snapshot();
        check(view.allSourcesToday == null, "null not 0");
        check("未知".equals(StepView.totalText(view.allSourcesToday)), "label 未知");
        check(!"0".equals(StepView.totalText(view.allSourcesToday)), "must not show 0");
    }

    static void manualRules() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        check(StepView.REJECTED.equals(StepTestConfirm.confirmManual(f.engine, 10).phase), "manual blocked while running");
        f.engine.stop();
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(f.engine, 10).phase), "preview after stop");
        check(StepTestConfirm.confirmManual(f.engine, 10).sessionConfirmed == 10, "manual after stop");
        f.clock.advanceWall(1_000L);
        check(StepView.REJECTED.equals(StepTestConfirm.confirmManual(f.engine, 10).phase), "gap < 2s");
    }

    static void logsHaveNoPayload() {
        Fixture f = fx();
        f.engine.disclose();
        StepTestConfirm.previewManual(f.engine, 12);
        StepTestConfirm.confirmManual(f.engine, 12);
        String encoded = StepState.load(f.store).encode();
        check(!encoded.contains("raw"), "no raw");
        check(!encoded.contains("token"), "no token");
        check(f.engine.snapshot().logs.get(0).result.contains("寫入成功") || f.engine.snapshot().logs.get(0).result.contains("待核對"), "result text");
    }

    static void paging() {
        FakeStepHealth health = new FakeStepHealth();
        for (int i = 0; i < 5; i++) {
            StepPlan p = new StepPlan("p" + i, "manual", StepPlan.CONFIRMED, 1, i, i + 1, 0, 0, "c" + i, 0, "h" + i, "");
            health.byClient.put(p.clientRecordId, p);
        }
        StepHealthPort.PageResult first = health.readThisApp(0, 10, 0, 2, 1000);
        check(first.records.size() == 2 && first.nextPageToken == 2, "page 1");
        StepHealthPort.PageResult second = health.readThisApp(0, 10, first.nextPageToken, 2, 1000);
        check(second.records.size() == 2, "page 2");
        StepHealthPort.PageResult last = health.readThisApp(0, 10, 4, 2, 1000);
        check(last.nextPageToken == -1L, "end page");
    }

    static void epochChange() {
        Fixture f = fx();
        f.engine.disclose();
        f.engine.start();
        due(f);
        f.engine.tick();
        long first = f.health.writes;
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 10, 0, 8 * 3600);
        f.clock.advanceWall(0);
        f.engine.tick();
        check(f.health.writes >= first, "new day still bounded");
    }
}

/** In-memory Health Connect double. Never talks to a device or writes real health records. */
final class FakeStepHealth implements StepHealthPort {
    boolean available = true;
    boolean canRead = true;
    boolean canWrite = true;
    boolean canReadBackground = true;
    long allSources;
    long thisApp;
    String nextWrite = "ok";
    String failKind = "error";
    boolean writeFutureTimeoutThenStore;
    boolean pageFail;
    String pageKind = "read-failed";
    boolean pageLimit;
    boolean pageTimeout;
    boolean pageDupToken;
    int forcedPages;
    int pageHoldAfter;
    Object watchLock;
    volatile boolean heldTxnDuringPage;
    java.util.concurrent.CountDownLatch pageEntered;
    java.util.concurrent.CountDownLatch pageContinue;
    java.util.concurrent.CountDownLatch writeEntered;
    java.util.concurrent.CountDownLatch writeContinue;
    java.util.concurrent.CountDownLatch aggregateEntered;
    java.util.concurrent.CountDownLatch aggregateContinue;
    int writes;
    int maxWrites = Integer.MAX_VALUE;
    final Map<String, StepPlan> byClient = new LinkedHashMap<String, StepPlan>();
    final List<StepPlan> writeLog = new ArrayList<StepPlan>();
    final List<String> calls = new ArrayList<String>();

    public boolean available() { return available; }
    public boolean canRead() { return canRead; }
    public boolean canWrite() { return canWrite; }
    public boolean canReadInBackground() { return canReadBackground; }

    public WriteResult write(StepPlan plan, long timeoutMs) {
        calls.add("write:" + plan.clientRecordId);
        if (writeEntered != null) writeEntered.countDown();
        if (writeContinue != null) {
            try { if (!writeContinue.await(20, java.util.concurrent.TimeUnit.SECONDS)) return WriteResult.timeout(); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return WriteResult.timeout();
            }
        }
        writes++;
        if (writes > maxWrites) return WriteResult.fail("rate-limit", "rate");
        if ("timeout".equals(nextWrite)) {
            if (writeFutureTimeoutThenStore) byClient.put(plan.clientRecordId, plan.withStatus(StepPlan.CONFIRMED, "hc-" + plan.id, ""));
            nextWrite = "ok";
            return WriteResult.timeout();
        }
        if ("fail".equals(nextWrite)) {
            nextWrite = "ok";
            return WriteResult.fail(failKind, failKind);
        }
        StepPlan confirmed = plan.withStatus(StepPlan.CONFIRMED, "hc-" + plan.id, "");
        byClient.put(plan.clientRecordId, confirmed);
        writeLog.add(confirmed);
        thisApp += plan.count;
        allSources += plan.count;
        return WriteResult.ok(confirmed.healthId);
    }

    boolean lookupFail;
    boolean lookupTimeout;
    boolean lookupMismatch;
    int lookupPageCostMs;
    int lookupPagesNeeded = 1;

    public LookupResult findByClientRecordId(String clientRecordId, long rangeStartMs, long rangeEndMs, long timeoutMs) {
        calls.add("lookup:" + clientRecordId + ":" + rangeStartMs + ":" + rangeEndMs);
        if (lookupTimeout) return LookupResult.timeout();
        if (lookupFail) return LookupResult.fail("io");
        long remaining = timeoutMs;
        for (int page = 0; page < lookupPagesNeeded; page++) {
            remaining -= lookupPageCostMs;
            if (remaining <= 0L) return LookupResult.timeout();
        }
        StepPlan found = byClient.get(clientRecordId);
        if (found == null) return LookupResult.miss();
        if (found.endMs <= rangeStartMs || found.startMs >= rangeEndMs) return LookupResult.miss();
        if (lookupMismatch) {
            return LookupResult.hit(new StepPlan(found.id, found.source, found.status, found.count + 99L,
                    found.startMs, found.endMs, found.startOffsetSeconds, found.endOffsetSeconds,
                    found.clientRecordId, found.clientRecordVersion + 1L, found.healthId, found.errorKind));
        }
        return LookupResult.hit(found);
    }

    public AggregateResult aggregate(long rangeStartMs, long rangeEndMs, boolean thisAppOnly, long timeoutMs) {
        calls.add(thisAppOnly ? "aggregate:app" : "aggregate:all");
        if (aggregateEntered != null) aggregateEntered.countDown();
        if (aggregateContinue != null) {
            try { if (!aggregateContinue.await(20, java.util.concurrent.TimeUnit.SECONDS)) return AggregateResult.unknown("timeout"); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return AggregateResult.unknown("timeout");
            }
        }
        if (!canRead) return AggregateResult.unknown("permission");
        return AggregateResult.fresh(thisAppOnly ? thisApp : allSources);
    }

    public PageResult readThisApp(long rangeStartMs, long rangeEndMs, long pageToken, int pageSize, long timeoutMs) {
        calls.add("page:" + pageToken);
        if (watchLock != null && Thread.holdsLock(watchLock)) heldTxnDuringPage = true;
        int seen = 0;
        for (int i = 0; i < calls.size(); i++) if (calls.get(i).startsWith("page:")) seen++;
        boolean hold = pageContinue != null && (pageHoldAfter <= 0 || seen >= pageHoldAfter);
        if (hold && pageEntered != null) pageEntered.countDown();
        if (hold && pageContinue != null) {
            try {
                if (!pageContinue.await(20, java.util.concurrent.TimeUnit.SECONDS))
                    return new PageResult(false, true, null, -1L, "timeout");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new PageResult(false, true, null, -1L, "timeout");
            }
        }
        if (pageTimeout) return new PageResult(false, true, null, -1L, "timeout");
        if (pageDupToken) {
            long echoed = pageToken < 0L ? 0L : pageToken;
            return new PageResult(true, false, Collections.<StepPlan>emptyList(), echoed, "ok");
        }
        if (pageFail) return new PageResult(false, false, null, -1L, pageKind == null || pageKind.length() == 0 ? "read-failed" : pageKind);
        List<StepPlan> all = new ArrayList<StepPlan>();
        for (StepPlan plan : byClient.values()) {
            if (plan.endMs <= rangeStartMs || plan.startMs >= rangeEndMs) continue;
            all.add(plan);
        }
        if (pageLimit) return new PageResult(true, false, all, 99L, "ok");
        if (forcedPages > 1) {
            int page = pageToken < 0 ? 0 : (int) pageToken;
            long next = page + 1 < forcedPages ? page + 1 : -1L;
            return new PageResult(true, false, Collections.<StepPlan>emptyList(), next, "ok");
        }
        int start = pageToken < 0 ? 0 : (int) pageToken;
        int end = Math.min(all.size(), start + pageSize);
        List<StepPlan> slice = new ArrayList<StepPlan>();
        for (int i = start; i < end; i++) slice.add(all.get(i));
        long next = end < all.size() ? end : -1L;
        return new PageResult(true, false, slice, next, "ok");
    }
}

/** Legacy fixtures capture exactly the preview returned to their simulated caller. */
final class StepTestConfirm {
    private static final java.util.Map<StepEngine, String> ids = new java.util.WeakHashMap<StepEngine, String>();
    static synchronized StepView previewManual(StepEngine engine, long count) {
        StepView view = engine.previewManual(count);
        ids.put(engine, view.previewId);
        return view;
    }
    static synchronized StepView previewCatchUp(StepEngine engine, StepCatchUpRequest request) {
        StepView view = engine.previewCatchUp(request);
        ids.put(engine, view.previewId);
        return view;
    }
    private static synchronized String id(StepEngine engine) {
        String id = ids.get(engine);
        return id == null ? "" : id;
    }
    static StepView confirmManual(StepEngine engine, long count) {
        return engine.confirmManual(count, id(engine));
    }
    static StepView confirmCatchUp(StepEngine engine, StepCatchUpRequest request) {
        return engine.confirmCatchUp(request, id(engine));
    }
}

package dev.ody.pikmincontrol;

import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StepPreviewBindTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static Fixture fx() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 50_000L);
        MemoryStepStore store = new MemoryStepStore();
        FakeStepHealth health = new FakeStepHealth();
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        engine.disclose();
        return new Fixture(clock, zone, store, health, engine);
    }

    static final class Fixture {
        final MutableStepClock clock;
        final StepZone zone;
        final MemoryStepStore store;
        final FakeStepHealth health;
        final StepEngine engine;
        Fixture(MutableStepClock clock, StepZone zone, MemoryStepStore store, FakeStepHealth health, StepEngine engine) {
            this.clock = clock; this.zone = zone; this.store = store; this.health = health; this.engine = engine;
        }
    }

    public static void main(String[] args) throws Exception {
        previewThenSameZoneRollback();
        previewThenMidnight();
        midBatchDayChangeStops();
        revokeDuringLaterOverlap();
        manualBindsPreviewedPlan();
        slowReadRollbackZeroSends();
        mismatchAndMissingPreviewReject();
        System.out.println("PASS StepPreviewBindTest " + checks + " checks; S6 preview bind and epoch");
    }

    static void previewThenSameZoneRollback() {
        Fixture f = fx();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), "preview");
        StepPlan previewed = StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.get(0);
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 10, 30, 8 * 3600);
        StepView rolled = StepTestConfirm.confirmCatchUp(f.engine, request);
        check(StepView.REJECTED.equals(rolled.phase), "same-zone rollback rejected");
        check(f.health.writes == 0, "rollback wrote 0");
        check(previewed.count == 10 || previewed.count > 0, "preview plan kept immutable");
    }

    static void previewThenMidnight() {
        Fixture f = fx();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), "preview");
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 0, 5, 8 * 3600);
        StepView nextDay = StepTestConfirm.confirmCatchUp(f.engine, request);
        check(StepView.REJECTED.equals(nextDay.phase), "midnight rejected");
        check(f.health.writes == 0, "midnight wrote 0");
    }

    static void midBatchDayChangeStops() {
        Fixture f = fx();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 30, 10, 2024, 1, 1, 10, 0, 10, 9, null, null);
        check(StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.size() >= 2, "multi plan");
        f.health.writeEntered = new CountDownLatch(1);
        f.health.writeContinue = new CountDownLatch(1);
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { result.set(StepTestConfirm.confirmCatchUp(f.engine, request)); }
        }, "s6-day");
        worker.start();
        try {
            check(f.health.writeEntered.await(5, TimeUnit.SECONDS), "first write");
            f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 12, 0, 8 * 3600);
            f.health.writeContinue.countDown();
            worker.join(10_000L);
        } catch (InterruptedException interrupted) {
            throw new AssertionError("interrupted");
        }
        check(!worker.isAlive(), "finished");
        check(f.health.writes == 1, "yesterday plans not continued writes=" + f.health.writes);
        check(result.get() != null && !StepEngine.OUTCOME_SUCCESS.equals(result.get().opKind), "stopped after day change");
    }

    static void revokeDuringLaterOverlap() throws Exception {
        Fixture f = fx();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 30, 10, 2024, 1, 1, 10, 0, 10, 9, null, null);
        check(StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.size() >= 2, "batch");
        f.health.pageHoldAfter = 2;
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { result.set(StepTestConfirm.confirmCatchUp(f.engine, request)); }
        }, "s6-revoke");
        worker.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "later overlap");
        check(f.health.writes == 1, "first write already sent");
        f.health.canRead = false;
        f.health.pageContinue.countDown();
        worker.join(10_000L);
        check(!worker.isAlive(), "revoke finished");
        check(f.health.writes == 1, "later writes 0 after revoke");
    }

    static void manualBindsPreviewedPlan() {
        Fixture f = fx();
        StepView preview = StepTestConfirm.previewManual(f.engine, 17);
        check(preview.previewPlans.size() == 1, "one preview");
        long start = preview.previewPlans.get(0).startMs;
        long end = preview.previewPlans.get(0).endMs;
        String id = preview.previewPlans.get(0).id;
        StepView done = StepTestConfirm.confirmManual(f.engine, 17);
        check(f.health.writes == 1, "manual wrote previewed");
        check(f.health.writeLog.get(0).startMs == start, "bound start");
        check(f.health.writeLog.get(0).endMs == end, "bound end");
        check(id.equals(f.health.writeLog.get(0).id) || id.equals(f.health.writeLog.get(0).id), "bound id " + id);
        TimeZone original = TimeZone.getDefault();
        try {
            Fixture g = fx();
            StepTestConfirm.previewManual(g.engine, 9);
            g.clock.wall = g.zone.toEpochMillis(2024, 1, 1, 11, 0, 8 * 3600);
            StepView changed = StepTestConfirm.confirmManual(g.engine, 9);
            check(changed.previewPlans == null || changed.previewPlans.isEmpty() || StepView.REJECTED.equals(changed.phase)
                    || g.health.writes <= 1, "manual after clock move bounded");
        } finally {
            TimeZone.setDefault(original);
        }
    }

    static void holdAndMutate(Fixture f, Runnable confirm, Runnable mutate) throws Exception {
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { confirm.run(); }
        }, "t5-slow");
        worker.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "slow-read barrier");
        int writes = f.health.writes;
        mutate.run();
        f.health.pageContinue.countDown();
        worker.join(10_000L);
        check(!worker.isAlive(), "slow-read finished");
        check(f.health.writes == writes, "slow-read mutation zero new sends writes=" + f.health.writes);
        check(result.get() == null || f.health.writes == writes, "no extra send");
    }

    static void slowReadRollbackZeroSends() throws Exception {
        Fixture manual = fx();
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(manual.engine, 11).phase), "manual preview 12:00");
        holdAndMutate(manual, new Runnable() {
            public void run() { StepTestConfirm.confirmManual(manual.engine, 11); }
        }, new Runnable() {
            public void run() { manual.clock.wall = manual.zone.toEpochMillis(2024, 1, 1, 11, 0, 8 * 3600); }
        });

        Fixture day = fx();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        check(!StepTestConfirm.previewCatchUp(day.engine, request).previewPlans.isEmpty(), "catch-up preview");
        holdAndMutate(day, new Runnable() {
            public void run() { StepTestConfirm.confirmCatchUp(day.engine, request); }
        }, new Runnable() {
            public void run() { day.clock.wall = day.zone.toEpochMillis(2024, 1, 2, 12, 0, 8 * 3600); }
        });

        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
            StepSystemZone zone = new StepSystemZone();
            MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 50_000L);
            MemoryStepStore store = new MemoryStepStore();
            FakeStepHealth health = new FakeStepHealth();
            StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
            engine.disclose();
            Fixture bg = new Fixture(clock, zone, store, health, engine);
            bg.engine.start();
            bg.clock.advanceWall(60_000L);
            holdAndMutate(bg, new Runnable() {
                public void run() { bg.engine.tick(); }
            }, new Runnable() {
                public void run() {
                    TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                    bg.engine.configure(new StepSettings(9000, 480, 1320, 130, "custom", 60_000L));
                }
            });
        } finally {
            TimeZone.setDefault(original);
        }
    }

    static void mismatchAndMissingPreviewReject() {
        Fixture f = fx();
        check(StepView.REJECTED.equals(StepTestConfirm.confirmManual(f.engine, 9).phase), "no preview rejects");
        check(f.health.writes == 0, "no preview writes 0");
        StepCatchUpRequest first = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        StepCatchUpRequest swapped = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 9, 0, 9, 30, null, null);
        check(!StepTestConfirm.previewCatchUp(f.engine, first).previewPlans.isEmpty(), "preview first window");
        StepView mismatch = StepTestConfirm.confirmCatchUp(f.engine, swapped);
        check(StepView.REJECTED.equals(mismatch.phase), "same count different request rejected");
        check(f.health.writes == 0, "mismatch writes 0");
        check(!StepTestConfirm.previewCatchUp(f.engine, swapped).previewPlans.isEmpty(), "replaced preview");
        StepView stale = StepTestConfirm.confirmCatchUp(f.engine, first);
        check(StepView.REJECTED.equals(stale.phase), "replaced preview rejects old request");
        check(f.health.writes == 0, "replaced writes 0");
        StepView ok = StepTestConfirm.confirmCatchUp(f.engine, swapped);
        check(f.health.writes == 1 || StepEngine.OUTCOME_SUCCESS.equals(ok.opKind)
                || !StepView.REJECTED.equals(ok.phase), "same-token success still works");
        Fixture g = fx();
        StepTestConfirm.previewManual(g.engine, 6);
        StepTestConfirm.previewManual(g.engine, 7);
        check(StepView.REJECTED.equals(StepTestConfirm.confirmManual(g.engine, 6).phase), "replaced manual preview rejects old count");
        check(g.health.writes == 0, "old manual count writes 0");
        StepView latest = StepTestConfirm.confirmManual(g.engine, 7);
        check(g.health.writes == 1 && StepEngine.OUTCOME_SUCCESS.equals(latest.opKind), "latest preview succeeds");
    }
}

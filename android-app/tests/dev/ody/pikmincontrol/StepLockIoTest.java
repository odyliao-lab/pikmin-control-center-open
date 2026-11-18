package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StepLockIoTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static Fixture fx() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        MemoryStepStore store = new MemoryStepStore();
        FakeStepHealth health = new FakeStepHealth();
        health.watchLock = store.transactionLock();
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
        backgroundSlowReadStopPersistsFirst();
        manualSlowReadStopPersistsFirst();
        catchUpSlowReadStopPersistsFirst();
        multiPageDoesNotHoldLock();
        pageTimeoutDoesNotWrite();
        dupTokenDoesNotWrite();
        System.out.println("PASS StepLockIoTest " + checks + " checks; S1 lock not held across HC I/O");
    }

    static void backgroundSlowReadStopPersistsFirst() throws Exception {
        Fixture f = fx();
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        AtomicReference<StepView> tickView = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { tickView.set(f.engine.tick()); }
        }, "s1-bg-page");
        worker.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "bg entered page");
        long stopStarted = System.currentTimeMillis();
        StepView stopped = f.engine.stop();
        long stopMs = System.currentTimeMillis() - stopStarted;
        check(stopMs < 400L, "STOP did not wait for slow page (" + stopMs + "ms)");
        check(StepView.STOPPED.equals(stopped.phase), "STOP persisted first");
        check(!StepState.load(f.store).desiredRunning, "desiredRunning false before page returns");
        long snapStarted = System.currentTimeMillis();
        StepView snap = f.engine.snapshot();
        long snapMs = System.currentTimeMillis() - snapStarted;
        check(snapMs < 400L, "snapshot did not wait for API (" + snapMs + "ms)");
        check(!snap.desiredRunning, "snapshot sees stop");
        int writes = f.health.writes;
        f.health.pageContinue.countDown();
        worker.join(10_000L);
        check(!worker.isAlive(), "bg worker finished");
        check(f.health.writes == writes, "no write after STOP released page");
        check(!f.health.heldTxnDuringPage, "page did not hold txn lock");
        check(tickView.get() != null && !StepView.WRITING.equals(tickView.get().phase),
                "late tick did not claim writing");
    }

    static void manualSlowReadStopPersistsFirst() throws Exception {
        Fixture f = fx();
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(f.engine, 12).phase), "manual preview");
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { result.set(StepTestConfirm.confirmManual(f.engine, 12)); }
        }, "s1-manual-page");
        worker.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "manual entered page");
        long stopStarted = System.currentTimeMillis();
        StepView stopped = f.engine.stop();
        check(System.currentTimeMillis() - stopStarted < 400L, "manual STOP not blocked by page");
        check(StepView.STOPPED.equals(stopped.phase) || !StepState.load(f.store).desiredRunning, "STOP durable");
        long snapStarted = System.currentTimeMillis();
        f.engine.snapshot();
        check(System.currentTimeMillis() - snapStarted < 400L, "manual snapshot not blocked");
        int writes = f.health.writes;
        f.health.pageContinue.countDown();
        worker.join(10_000L);
        check(!worker.isAlive(), "manual worker finished");
        check(f.health.writes == writes, "no manual write after STOP");
        check(!f.health.heldTxnDuringPage, "manual page did not hold txn lock");
        check(result.get() != null && !StepView.WRITING.equals(result.get().phase), "manual late result not writing");
    }

    static void catchUpSlowReadStopPersistsFirst() throws Exception {
        Fixture f = fx();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600);
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        final StepCatchUpRequest request = new StepCatchUpRequest("window", 30, 10, 2024, 1, 1, 10, 0, 10, 9, null, null);
        check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), "catch-up preview");
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread worker = new Thread(new Runnable() {
            public void run() { result.set(StepTestConfirm.confirmCatchUp(f.engine, request)); }
        }, "s1-catchup-page");
        worker.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "catch-up entered page");
        long stopStarted = System.currentTimeMillis();
        StepView stopped = f.engine.stop();
        check(System.currentTimeMillis() - stopStarted < 400L, "catch-up STOP not blocked");
        check(StepView.STOPPED.equals(stopped.phase), "catch-up STOP persisted");
        long snapStarted = System.currentTimeMillis();
        f.engine.snapshot();
        check(System.currentTimeMillis() - snapStarted < 400L, "catch-up snapshot not blocked");
        int writes = f.health.writes;
        f.health.pageContinue.countDown();
        worker.join(10_000L);
        check(!worker.isAlive(), "catch-up worker finished");
        check(f.health.writes == writes, "no catch-up write after STOP");
        check(!f.health.heldTxnDuringPage, "catch-up page did not hold txn lock");
    }

    static void multiPageDoesNotHoldLock() {
        Fixture f = fx();
        f.health.forcedPages = 3;
        f.health.watchLock = f.store.transactionLock();
        StepTestConfirm.previewManual(f.engine, 8);
        StepTestConfirm.confirmManual(f.engine, 8);
        check(f.health.writes == 1, "multi-page empty proof still writes");
        int pages = 0;
        for (int i = 0; i < f.health.calls.size(); i++) if (f.health.calls.get(i).startsWith("page:")) pages++;
        check(pages >= 3, "used multiple pages");
        check(!f.health.heldTxnDuringPage, "multi-page did not hold txn lock");
    }

    static void pageTimeoutDoesNotWrite() {
        Fixture f = fx();
        f.health.pageTimeout = true;
        StepTestConfirm.previewManual(f.engine, 8);
        StepView view = StepTestConfirm.confirmManual(f.engine, 8);
        check(StepView.REJECTED.equals(view.phase), "timeout rejected");
        check("timeout".equals(view.opKind), "timeout kind");
        check(f.health.writes == 0, "timeout does not write");
        check(!f.health.heldTxnDuringPage, "timeout page did not hold lock");
    }

    static void dupTokenDoesNotWrite() {
        Fixture f = fx();
        f.health.pageDupToken = true;
        StepTestConfirm.previewManual(f.engine, 8);
        StepView view = StepTestConfirm.confirmManual(f.engine, 8);
        check(StepView.REJECTED.equals(view.phase), "dup token rejected");
        check("dup-token".equals(view.opKind), "dup-token kind");
        check(f.health.writes == 0, "dup token does not write");
        check(!f.health.heldTxnDuringPage, "dup-token page did not hold lock");
    }
}

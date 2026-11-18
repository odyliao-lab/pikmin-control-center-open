package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** U1-U3: production controller/engine, fake HC only; not Android lifecycle proof. */
public final class StepTakeoverRegressionTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        previewIdentity();
        slowPastRollback();
        slowPreviewReplacement();
        generationsAndLaunchFailure();
        storageFailureVisible();
        System.out.println("PASS StepTakeoverRegressionTest " + checks + " checks; U1-U3 offline only");
    }
    static StepCatchUpRequest request() {
        return new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
    }
    static void previewIdentity() {
        StepPreviewBindTest.Fixture f = StepPreviewBindTest.fx();
        StepView a = f.engine.previewManual(10);
        f.clock.advanceWall(60000);
        StepView b = f.engine.previewManual(10);
        check(!a.previewId.equals(b.previewId), "distinct preview IDs");
        check(StepView.REJECTED.equals(f.engine.confirmManual(10, a.previewId).phase), "old same-count ID rejected");
        check(f.health.writes == 0, "old ID zero sends");
        check(StepEngine.OUTCOME_SUCCESS.equals(f.engine.confirmManual(10, b.previewId).opKind), "new ID succeeds");
        check(f.health.writes == 1, "exactly one send");
        f = StepPreviewBindTest.fx();
        a = f.engine.previewCatchUp(request());
        b = f.engine.previewCatchUp(request());
        check(StepView.REJECTED.equals(f.engine.confirmCatchUp(request(), a.previewId).phase), "old same-request ID rejected");
        check(f.health.writes == 0, "old catch-up zero sends");
        check(StepEngine.OUTCOME_SUCCESS.equals(f.engine.confirmCatchUp(request(), b.previewId).opKind), "new catch-up succeeds");
        check(f.health.writes == b.previewPlans.size(), "exact approved catch-up plan count");
    }
    static void slowPastRollback() throws Exception {
        final StepPreviewBindTest.Fixture f = StepPreviewBindTest.fx();
        final StepView preview = f.engine.previewCatchUp(request());
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        final AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread t = new Thread(() -> result.set(f.engine.confirmCatchUp(request(), preview.previewId)));
        t.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "overlap blocked");
        f.clock.wall -= 1800000L; // noon -> 11:30, entire requested plan is still in the past.
        f.health.pageContinue.countDown();
        t.join(5000);
        check(!t.isAlive() && result.get() != null, "rollback completes");
        check(f.health.writes == 0, "past-plan rollback zero sends");
    }
    static void slowPreviewReplacement() throws Exception {
        final StepPreviewBindTest.Fixture f = StepPreviewBindTest.fx();
        final StepView a = f.engine.previewManual(10);
        f.health.pageEntered = new CountDownLatch(1);
        f.health.pageContinue = new CountDownLatch(1);
        Thread t = new Thread(() -> f.engine.confirmManual(10, a.previewId));
        t.start();
        check(f.health.pageEntered.await(5, TimeUnit.SECONDS), "manual overlap blocked");
        f.engine.previewManual(10);
        f.health.pageContinue.countDown();
        t.join(5000);
        check(!t.isAlive() && f.health.writes == 0, "replaced during slow read zero sends");
    }
    static void generationsAndLaunchFailure() {
        StepPreviewBindTest.Fixture f = StepPreviewBindTest.fx();
        StepWriterControl.Controller c = new StepWriterControl.Controller();
        long epoch = f.engine.commandEpoch();
        f.engine.startBound(epoch);
        StepWriterControl.Command old = StepWriterControl.startCommand(epoch, 101);
        check(c.accept(f.engine, old), "first admitted");
        f.engine.stop();
        epoch = f.engine.commandEpoch();
        f.engine.startBound(epoch);
        StepWriterControl.Command next = StepWriterControl.startCommand(epoch, 102);
        check(c.accept(f.engine, next), "new run replaces worker not yet finished");
        check(!c.admitted(f.engine, old), "old admission rejected despite desired true");
        f.clock.advanceWall(60000);
        check(StepView.STOPPED.equals(f.engine.tickBound(old.commandEpoch).phase), "stale bound tick stopped");
        check(f.health.writes == 0, "old worker zero sends");
        final int[] cleanups = {0};
        check(!c.finish(old, () -> cleanups[0]++), "old finally cannot cleanup newer worker");
        c.launchFailed(f.engine, old);
        check(f.engine.snapshot().desiredRunning && c.admitted(f.engine, next), "old launch failure does not stop new run");
        check(StepEngine.OUTCOME_SUCCESS.equals(f.engine.tickBound(next.commandEpoch).opKind), "new worker sends");
        check(f.health.writes == 1, "new worker exactly once");
        StepView fail = c.launchFailed(f.engine, next);
        check(!fail.desiredRunning && fail.detail.contains("啟動失敗"), "FGS failure saved stopped with result");
        check(!f.engine.snapshot().desiredRunning, "FGS failure durable");
        check(c.finish(next, () -> cleanups[0]++), "current worker cleanup");
        check(cleanups[0] == 1, "one cleanup");
    }
    static void storageFailureVisible() throws Exception {
        for (String kind : new String[] {"prepared", "confirmed", "stop"}) {
            MemoryStepStore memory = new MemoryStepStore();
            FilteringStepStore store = new FilteringStepStore(memory);
            FakeStepHealth health = new FakeStepHealth();
            MutableStepClock clock = new MutableStepClock(StepFailureFixtureTest.wall(), 10000);
            StepEngine engine = StepFailureFixtureTest.factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5), store, health);
            engine.disclose();
            engine.start();
            clock.advanceWall(60000);
            store.failPrepared = kind.equals("prepared");
            store.failConfirmed = kind.equals("confirmed");
            store.failStop = kind.equals("stop");
            StepOperations queue = new StepOperations(engine);
            CountDownLatch done = new CountDownLatch(1);
            queue.enqueue(() -> { if (kind.equals("stop")) engine.stop(); else engine.tick(); });
            queue.enqueue("barrier", () -> done.countDown());
            check(done.await(5, TimeUnit.SECONDS), "operation returned");
            check(StepView.STORE_ERROR.equals(queue.latest().phase), "queue retains " + kind);
            StepEngine recreated = StepFailureFixtureTest.factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5), store, health);
            check(StepView.STORE_ERROR.equals(recreated.snapshot().phase), "shared error across engine recreation");
            check(StepUiAdapter.statusLine(recreated.snapshot()).contains("儲存"), "presenter displays failure");
            int writes = health.writes;
            recreated.tick();
            check(health.writes == writes, "latched failure prohibits new sends");
            store.failPrepared = store.failConfirmed = store.failStop = false;
            check(StepView.STOPPED.equals(recreated.stop().phase), "explicit durable stop clears error");
            check(!StepView.STORE_ERROR.equals(recreated.snapshot().phase), "recovery visible");
            if (kind.equals("confirmed")) {
                recreated.tick();
                check(health.writes == writes && StepState.load(memory).ledger.pending() == null, "same ID reconciled not resent");
            }
            queue.close();
        }
    }
}

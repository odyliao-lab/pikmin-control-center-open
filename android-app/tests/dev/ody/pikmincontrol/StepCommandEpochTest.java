package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class StepCommandEpochTest {
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
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        engine.disclose();
        return new Fixture(clock, store, health, engine);
    }

    static final class Fixture {
        final MutableStepClock clock;
        final MemoryStepStore store;
        final FakeStepHealth health;
        final StepEngine engine;
        Fixture(MutableStepClock clock, MemoryStepStore store, FakeStepHealth health, StepEngine engine) {
            this.clock = clock; this.store = store; this.health = health; this.engine = engine;
        }
    }

    public static void main(String[] args) throws Exception {
        lateWriteAfterStopStillConfirms();
        queuedStartBeforeStopDoesNotResurrect();
        stopInterleavesAcquire();
        oldServiceFinallyDoesNotStopNewStart();
        queueCapacityAndCoalesce();
        System.out.println("PASS StepCommandEpochTest " + checks + " checks; S2 command epoch and bound queue");
    }

    static void lateWriteAfterStopStillConfirms() throws Exception {
        Fixture f = fx();
        f.health.writeEntered = new CountDownLatch(1);
        f.health.writeContinue = new CountDownLatch(1);
        f.engine.start();
        f.clock.advanceWall(60_000L);
        AtomicReference<StepView> tick = new AtomicReference<StepView>();
        Thread writer = new Thread(new Runnable() {
            public void run() { tick.set(f.engine.tick()); }
        }, "s2-late-write");
        writer.start();
        check(f.health.writeEntered.await(5, TimeUnit.SECONDS), "entered write");
        check(StepView.STOPPED.equals(f.engine.stop().phase), "STOP while write in flight");
        f.health.writeContinue.countDown();
        writer.join(10_000L);
        check(!writer.isAlive(), "writer finished");
        check(!StepState.load(f.store).desiredRunning, "late write did not resurrect");
        StepPlan last = StepState.load(f.store).ledger.lastConfirmed();
        check(last != null && StepPlan.CONFIRMED.equals(last.status), "late success still confirms");
        check(f.health.writes == 1, "single write");
    }

    static void queuedStartBeforeStopDoesNotResurrect() throws Exception {
        Fixture f = fx();
        final CountDownLatch blockEntered = new CountDownLatch(1);
        final CountDownLatch blockContinue = new CountDownLatch(1);
        StepOperations ops = new StepOperations(f.engine);
        check(ops.enqueue("block", new Runnable() {
            public void run() {
                blockEntered.countDown();
                try { blockContinue.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }), "blocker enqueued");
        check(blockEntered.await(5, TimeUnit.SECONDS), "queue occupied");
        final AtomicBoolean startRan = new AtomicBoolean(false);
        final long bound = f.engine.commandEpoch();
        check(ops.enqueue("start", new Runnable() {
            public void run() {
                startRan.set(true);
                f.engine.startBound(bound);
            }
        }), "START queued before STOP");
        check(ops.queued() >= 1, "START is waiting behind blocker");
        check(StepView.STOPPED.equals(f.engine.stop().phase), "STOP advances epoch");
        check(StepState.load(f.store).commandEpoch > bound, "durable epoch advanced");
        blockContinue.countDown();
        ops.close();
        check(!startRan.get(), "stale START never executed");
        check(!StepState.load(f.store).desiredRunning, "queued START did not resurrect");
        check(!f.engine.snapshot().desiredRunning, "snapshot still stopped");
    }

    static void stopInterleavesAcquire() throws Exception {
        Fixture f = fx();
        f.engine.startIntentLatch = new CountDownLatch(1);
        f.engine.startAcquireLatch = new CountDownLatch(1);
        AtomicReference<StepView> started = new AtomicReference<StepView>();
        Thread starter = new Thread(new Runnable() {
            public void run() { started.set(f.engine.start()); }
        }, "s2-acquire");
        starter.start();
        check(f.engine.startIntentLatch.await(5, TimeUnit.SECONDS), "intent persisted");
        check(StepState.load(f.store).desiredRunning, "START published intent");
        StepView stopped = f.engine.stop();
        check(StepView.STOPPED.equals(stopped.phase), "STOP won");
        check(!StepState.load(f.store).desiredRunning, "STOP durable before acquire");
        f.engine.startAcquireLatch.countDown();
        starter.join(10_000L);
        check(!starter.isAlive(), "starter finished");
        check(!StepState.load(f.store).desiredRunning, "acquire after STOP did not resurrect");
        check(StepState.load(f.store).lockId.length() == 0, "exact token released");
        check(started.get() != null && !started.get().desiredRunning, "start result stopped");
    }

    static void oldServiceFinallyDoesNotStopNewStart() {
        long oldEpoch = 3L;
        long oldRun = StepWriterControl.nextRunToken();
        long newEpoch = 4L;
        long newRun = StepWriterControl.nextRunToken();
        StepRunIdentity older = new StepRunIdentity();
        older.begin(oldEpoch, oldRun);
        boolean running = true;
        StepRunIdentity newer = new StepRunIdentity();
        newer.begin(newEpoch, newRun);
        check(newRun > oldRun, "new run id");
        check(!older.isCurrent(), "old identity retired");
        check(newer.isCurrent(), "new identity current");
        if (older.matches(oldEpoch, oldRun)) running = false;
        check(running, "old finally did not clear newer start");
        check(!StepWriterControl.shouldApplyStop(StepWriterControl.stopCommand(oldEpoch, oldRun), newEpoch, newRun),
                "old STOP extras do not apply to newer run");
        check(newer.isCurrent(), "new start still current after old finally");
    }

    static void queueCapacityAndCoalesce() throws Exception {
        Fixture f = fx();
        final CountDownLatch blockEntered = new CountDownLatch(1);
        final CountDownLatch blockContinue = new CountDownLatch(1);
        StepOperations ops = new StepOperations(f.engine);
        check(ops.enqueue("block", new Runnable() {
            public void run() {
                blockEntered.countDown();
                try { blockContinue.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }), "blocker");
        check(blockEntered.await(5, TimeUnit.SECONDS), "draining");
        for (int i = 0; i < StepOperations.CAPACITY; i++) {
            final int n = i;
            check(ops.enqueue("k" + n, new Runnable() { public void run() { } }), "fill " + i);
        }
        check(ops.queued() == StepOperations.CAPACITY, "queue at cap");
        check(!ops.enqueue("overflow", new Runnable() { public void run() { } }), "reject at capacity");
        check(ops.enqueue("k0", new Runnable() { public void run() { } }), "coalesce same kind");
        check(ops.queued() == StepOperations.CAPACITY, "coalesce does not grow");
        blockContinue.countDown();
        ops.close();
    }
}

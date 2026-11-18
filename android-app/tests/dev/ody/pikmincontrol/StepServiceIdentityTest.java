package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Formal Activity/Service/controller path for T1. Not a StepRunIdentity-only unit. */
public final class StepServiceIdentityTest {
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
        StepOperations ops = new StepOperations(engine);
        return new Fixture(clock, store, health, engine, ops, new StepWriterControl.Host(engine, ops));
    }

    static final class Fixture {
        final MutableStepClock clock;
        final MemoryStepStore store;
        final FakeStepHealth health;
        final StepEngine engine;
        final StepOperations ops;
        final StepWriterControl.Host host;
        Fixture(MutableStepClock clock, MemoryStepStore store, FakeStepHealth health, StepEngine engine,
                StepOperations ops, StepWriterControl.Host host) {
            this.clock = clock; this.store = store; this.health = health; this.engine = engine;
            this.ops = ops; this.host = host;
        }
        void close() { ops.close(); }
    }

    public static void main(String[] args) throws Exception {
        expiredAfterQueueDoesNotLaunch();
        stopAfterActivityStartBeforeWorker();
        oldStopDoesNotKillNewerStart();
        freshUserStartStillWrites();
        stoppedPhaseNotInAllowlist();
        System.out.println("PASS StepServiceIdentityTest " + checks + " checks; T1 service command identity");
    }

    static void expiredAfterQueueDoesNotLaunch() throws Exception {
        Fixture f = fx();
        final CountDownLatch blockEntered = new CountDownLatch(1);
        final CountDownLatch blockContinue = new CountDownLatch(1);
        check(f.ops.enqueue("block", new Runnable() {
            public void run() {
                blockEntered.countDown();
                try { blockContinue.await(20, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }), "blocker");
        check(blockEntered.await(5, TimeUnit.SECONDS), "queue occupied");
        f.host.afterQueueEpochCheck = new CountDownLatch(1);
        f.host.holdAfterQueue = new CountDownLatch(1);
        f.host.afterActivityStart = new CountDownLatch(1);
        check(f.host.enqueueUserStart(), "START queued");
        blockContinue.countDown();
        check(f.host.afterQueueEpochCheck.await(5, TimeUnit.SECONDS), "after queue epoch check");
        check(StepView.STOPPED.equals(f.engine.stop().phase), "STOP after admit");
        int writes = f.health.writes;
        f.host.holdAfterQueue.countDown();
        check(f.host.afterActivityStart.await(5, TimeUnit.SECONDS), "after Activity startBound");
        f.ops.close();
        check(!StepState.load(f.store).desiredRunning, "desired=false after expired START");
        check(f.health.writes == writes, "old command zero new writes");
        check(!f.host.running, "Service not launched for STOPPED");
        check(!StepWriterControl.mayLaunchService(new StepView(StepView.STOPPED, "命令已過期，未恢復寫入。",
                null, null, 0L, 8000, false, true, false, false, "", null, null, "")),
                "STOPPED not allowlisted");
        f.close();
    }

    static void stopAfterActivityStartBeforeWorker() throws Exception {
        Fixture f = fx();
        f.host.afterActivityStart = new CountDownLatch(1);
        f.host.holdAfterActivity = new CountDownLatch(1);
        f.host.beforeWorkerEntry = new CountDownLatch(1);
        f.host.afterServiceLaunch = new CountDownLatch(1);
        f.host.holdBeforeWorker = new CountDownLatch(1);
        check(f.host.enqueueUserStart(), "user START");
        check(f.host.afterActivityStart.await(5, TimeUnit.SECONDS), "Activity start succeeded");
        check(StepState.load(f.store).desiredRunning, "authorized before worker");
        StepView stopped = f.host.requestStop();
        check(StepView.STOPPED.equals(stopped.phase) || !StepState.load(f.store).desiredRunning, "STOP bound");
        int writes = f.health.writes;
        f.host.holdAfterActivity.countDown();
        check(f.host.afterServiceLaunch.await(5, TimeUnit.SECONDS), "expired launch decision completed");
        f.host.holdBeforeWorker.countDown();
        f.ops.close();
        waitUntil(new Cond() {
            public boolean ok() { return !f.host.running; }
        }, 5_000L, "old worker exited");
        check(!StepState.load(f.store).desiredRunning, "desired stays false");
        check(f.health.writes == writes, "worker admit invented no write");
        StepView admitted = f.engine.continueAuthorizedRun(0L);
        check(!admitted.desiredRunning, "continueAuthorizedRun does not invent START");
        f.close();
    }

    static void oldStopDoesNotKillNewerStart() throws Exception {
        Fixture f = fx();
        f.host.afterActivityStart = new CountDownLatch(1);
        f.host.afterServiceLaunch = new CountDownLatch(1);
        check(f.host.enqueueUserStart(), "first START");
        check(f.host.afterActivityStart.await(5, TimeUnit.SECONDS), "first authorized");
        check(f.host.afterServiceLaunch.await(5, TimeUnit.SECONDS), "first Service launched");
        check(f.host.liveRun != 0L, "first worker live");
        long oldEpoch = f.host.liveEpoch;
        long oldRun = f.host.liveRun;
        f.host.requestStop();
        waitUntil(new Cond() {
            public boolean ok() { return !StepState.load(f.store).desiredRunning; }
        }, 5_000L, "first stopped");
        f.host.afterActivityStart = new CountDownLatch(1);
        f.host.afterServiceLaunch = new CountDownLatch(1);
        check(f.host.enqueueUserStart(), "fresh START");
        check(f.host.afterActivityStart.await(5, TimeUnit.SECONDS), "second authorized");
        check(f.host.afterServiceLaunch.await(5, TimeUnit.SECONDS), "second Service launched");
        check(f.host.liveRun != 0L && f.host.liveRun != oldRun, "newer run live");
        long newEpoch = f.host.liveEpoch;
        long newRun = f.host.liveRun;
        check(newRun != oldRun, "new generation");
        f.host.applyStop(StepWriterControl.stopCommand(oldEpoch, oldRun));
        check(StepState.load(f.store).desiredRunning, "old STOP did not kill newer desired");
        check(f.host.liveRun == newRun, "newer run still live");
        check(!StepWriterControl.shouldApplyStop(StepWriterControl.stopCommand(oldEpoch, oldRun), newEpoch, newRun),
                "old extras rejected");
        f.host.requestStop();
        f.close();
    }

    static void freshUserStartStillWrites() throws Exception {
        Fixture f = fx();
        f.host.beforeWorkerEntry = new CountDownLatch(1);
        f.host.holdBeforeWorker = new CountDownLatch(1);
        check(f.host.enqueueUserStart(), "START");
        check(f.host.beforeWorkerEntry.await(5, TimeUnit.SECONDS), "formal controller launched worker");
        f.clock.advanceWall(60_000L);
        f.host.holdBeforeWorker.countDown();
        waitUntil(new Cond() {
            public boolean ok() { return !f.host.running; }
        }, 5_000L, "host worker finished its tick");
        check(f.health.writes == 1, "actual host worker wrote exactly once");
        f.host.requestStop();
        check(!StepState.load(f.store).desiredRunning, "STOP after fresh write");
        f.close();
    }

    static void stoppedPhaseNotInAllowlist() {
        check(!StepWriterControl.mayLaunchService(new StepView(StepView.STOPPED, "命令已過期，未恢復寫入。",
                null, null, 0L, 8000, false, true, false, false, "", null, null, "")), "expired STOPPED");
        check(!StepWriterControl.mayLaunchService(new StepView(StepView.STOPPED, "已停止",
                null, null, 0L, 8000, true, true, false, false, "", null, null, "")),
                "STOPPED phase even if desired leaked");
        check(!StepWriterControl.mayLaunchService(new StepView(StepView.SETTINGS_ON, "設定已開，尚未寫入。",
                null, null, 0L, 8000, false, true, false, false, "", null, null, "")), "desired false");
        check(StepWriterControl.mayLaunchService(new StepView(StepView.SETTINGS_ON, "設定已開，尚未寫入。",
                null, null, 0L, 8000, true, true, false, false, "", null, null, "")), "authorized allowlist");
        check(StepWriterControl.mayLaunchService(new StepView(StepView.RESULT_PENDING, "先核對未完成寫入。",
                null, null, 0L, 8000, true, true, false, true, "", null, null, "")), "pending still launches");
    }

    interface Cond { boolean ok(); }

    static void waitUntil(Cond cond, long timeoutMs, String label) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.ok()) return;
            Thread.sleep(20L);
        }
        check(cond.ok(), label);
    }
}

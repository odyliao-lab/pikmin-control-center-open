package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class StepFailureFixtureTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static StepEngine factory(MutableStepClock clock, StepZone zone, StepRng rng, StepStore store, StepHealthPort health) {
        return new StepEngine(clock, zone, rng, store, health, new StepWorkLock(store));
    }

    static long wall() {
        return new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600);
    }

    public static void main(String[] args) throws Exception {
        preparedSaveFailureDoesNotWrite();
        confirmedSaveFailureDoesNotResend();
        stopSaveFailureNotReportedStopped();
        corruptUnknownUnreadableRejected();
        twoFactoryWriteInFlightStop();
        twoSameRoleStarts();
        catchUpStopCancelsRemainder();
        newGenerationIgnoresOldRelease();
        failedAcquireDoesNotHold();
        System.out.println("PASS StepFailureFixtureTest " + checks + " checks; R1/R2 durable store and coordinator");
    }

    static void preparedSaveFailureDoesNotWrite() {
        MemoryStepStore memory = new MemoryStepStore();
        FilteringStepStore store = new FilteringStepStore(memory);
        store.failPrepared = true;
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepEngine engine = factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health);
        engine.disclose();
        engine.start();
        clock.advanceWall(60_000L);
        StepView view = engine.tick();
        check(StepView.STORE_ERROR.equals(view.phase), "prepared save error");
        check(health.writes == 0, "no write without durable PREPARED");
        check(StepState.load(memory).ledger.pending() == null, "no pending after failed PREPARED");
    }

    static void confirmedSaveFailureDoesNotResend() {
        MemoryStepStore memory = new MemoryStepStore();
        FilteringStepStore store = new FilteringStepStore(memory);
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepEngine engine = factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health);
        engine.disclose();
        engine.start();
        clock.advanceWall(60_000L);
        store.failConfirmed = true;
        StepView view = engine.tick();
        check(StepView.STORE_ERROR.equals(view.phase), "confirmed save error");
        check(health.writes == 1, "write happened");
        check(StepState.load(memory).ledger.pending() != null, "still pending locally");
        store.failConfirmed = false;
        check(StepView.STOPPED.equals(engine.stop().phase), "explicit durable stop acknowledges storage recovery");
        engine.tick();
        check(health.writes == 1, "no second blind send");
        check(StepPlan.CONFIRMED.equals(StepState.load(memory).ledger.lastConfirmed().status), "same id confirmed");
    }

    static void stopSaveFailureNotReportedStopped() {
        MemoryStepStore memory = new MemoryStepStore();
        FilteringStepStore store = new FilteringStepStore(memory);
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepEngine engine = factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health);
        engine.disclose();
        engine.start();
        store.failStop = true;
        StepView view = engine.stop();
        check(StepView.STORE_ERROR.equals(view.phase), "stop save error");
        check(!StepView.STOPPED.equals(view.phase), "must not report stopped");
        check(StepState.load(memory).desiredRunning, "desiredRunning remains true");
    }

    static void corruptUnknownUnreadableRejected() {
        MemoryStepStore store = new MemoryStepStore();
        store.save("PCCSTEP/99\ndisclosed=1\ndesiredRunning=1\n");
        try {
            StepState.load(store);
            check(false, "unknown schema must throw");
        } catch (StepStoreException error) {
            check(error.getMessage().contains("schema"), "unknown schema");
        }
        store.save("PCCSTEP/2\ndisclosed=1\n");
        try {
            StepState.load(store);
            check(false, "truncated must throw");
        } catch (StepStoreException error) {
            check(error.getMessage().contains("corrupt"), "truncated");
        }
        store.save("not-a-snapshot");
        try {
            StepState.load(store);
            check(false, "garbage must throw");
        } catch (StepStoreException error) {
            check(error.getMessage().contains("corrupt") || error.getMessage().contains("header"), "garbage");
        }
        FilteringStepStore unread = new FilteringStepStore(new MemoryStepStore());
        unread.unreadable = true;
        MutableStepClock clock = new MutableStepClock(wall(), 1);
        StepEngine engine = factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), unread, new FakeStepHealth());
        StepView view = engine.snapshot();
        check(StepView.STORE_ERROR.equals(view.phase), "unreadable");
        check(!view.desiredRunning, "unreadable is not empty enabled state");
        unread.unreadable = false;
        check(StepState.load(unread).ledger.pending() == null, "did not overwrite with empty");
    }

    static void twoFactoryWriteInFlightStop() throws Exception {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore aStore = shared.view();
        StepStore bStore = shared.view();
        check(StepCoordinator.of(aStore) == StepCoordinator.of(bStore), "shared coordinator");
        FakeStepHealth health = new FakeStepHealth();
        health.writeEntered = new CountDownLatch(1);
        health.writeContinue = new CountDownLatch(1);
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepZone zone = new StepSystemZone("Asia/Taipei");
        FixedStepRng rng = new FixedStepRng(0.5d);
        StepEngine a = factory(clock, zone, rng, aStore, health);
        StepEngine b = factory(clock, zone, rng, bStore, health);
        a.disclose();
        a.start();
        clock.advanceWall(60_000L);
        AtomicReference<StepView> tickView = new AtomicReference<StepView>();
        Thread writer = new Thread(new Runnable() {
            public void run() { tickView.set(a.tick()); }
        }, "step-r2-write");
        writer.start();
        check(health.writeEntered.await(5, TimeUnit.SECONDS), "entered write");
        StepView stopped = b.stop();
        check(StepView.STOPPED.equals(stopped.phase), "B stop durable");
        check(!StepState.load(bStore).desiredRunning, "B persisted false");
        health.writeContinue.countDown();
        writer.join(10_000L);
        check(!writer.isAlive(), "writer finished");
        check(!StepState.load(aStore).desiredRunning, "late reply did not resurrect");
        StepPlan last = StepState.load(aStore).ledger.lastConfirmed();
        check(last != null && StepPlan.CONFIRMED.equals(last.status), "late success updates own ledger");
        check(health.writes == 1, "single write");
    }

    static void twoSameRoleStarts() {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore aStore = shared.view();
        StepStore bStore = shared.view();
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepZone zone = new StepSystemZone("Asia/Taipei");
        StepEngine a = factory(clock, zone, new FixedStepRng(0.5d), aStore, health);
        StepEngine b = factory(clock, zone, new FixedStepRng(0.5d), bStore, health);
        a.disclose();
        a.start();
        String firstId = StepState.load(aStore).lockId;
        b.start();
        check(firstId.equals(StepState.load(bStore).lockId), "second start does not steal lock");
        check(StepWorkLock.BACKGROUND.equals(StepState.load(aStore).lockRole), "single background owner");
    }

    static void catchUpStopCancelsRemainder() throws Exception {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore aStore = shared.view();
        StepStore bStore = shared.view();
        FakeStepHealth health = new FakeStepHealth();
        health.writeEntered = new CountDownLatch(1);
        health.writeContinue = new CountDownLatch(1);
        StepZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 50_000L);
        StepEngine a = factory(clock, zone, new FixedStepRng(0.5d), aStore, health);
        StepEngine b = factory(clock, zone, new FixedStepRng(0.5d), bStore, health);
        a.disclose();
        final StepCatchUpRequest request = new StepCatchUpRequest("window", 300, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        check(!StepTestConfirm.previewCatchUp(a, request).previewPlans.isEmpty(), "catch-up preview");
        AtomicReference<StepView> result = new AtomicReference<StepView>();
        Thread catchUp = new Thread(new Runnable() {
            public void run() { result.set(StepTestConfirm.confirmCatchUp(a, request)); }
        }, "step-r2-catchup");
        catchUp.start();
        check(health.writeEntered.await(5, TimeUnit.SECONDS), "catch-up entered write");
        b.stop();
        health.writeContinue.countDown();
        catchUp.join(10_000L);
        check(!catchUp.isAlive(), "catch-up finished");
        check(health.writes <= 1, "stop cancelled remaining catch-up writes");
        check(!StepCoordinator.of(aStore).held(), "lock released after cancel");
    }

    static void newGenerationIgnoresOldRelease() {
        MemoryStepStore store = new MemoryStepStore();
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(wall(), 10_000L);
        StepEngine engine = factory(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health);
        engine.disclose();
        engine.start();
        StepState afterStart = StepState.load(store);
        StepLockToken old = new StepLockToken(StepWorkLock.BACKGROUND, afterStart.lockId, afterStart.lockGeneration);
        check(old.id.length() > 0, "first lock id");
        engine.stop();
        StepState afterStop = StepState.load(store);
        check(!afterStop.desiredRunning, "stopped");
        check(afterStop.lockId.length() == 0, "lock cleared on stop id=" + afterStop.lockId + " role=" + afterStop.lockRole);
        engine.start();
        StepState afterSecond = StepState.load(store);
        String newId = afterSecond.lockId;
        check(newId.length() > 0 && !newId.equals(old.id), "new generation token");
        StepCoordinator.of(store).release(old);
        check(newId.equals(StepState.load(store).lockId), "old release cannot clear new lock");
    }

    static void failedAcquireDoesNotHold() {
        MemoryStepStore store = new MemoryStepStore();
        StepWorkLock lock = new StepWorkLock(store);
        check(lock.tryAcquire(StepWorkLock.MANUAL), "first");
        check(!lock.tryAcquire(StepWorkLock.BACKGROUND), "second fails");
        check(lock.heldBy(StepWorkLock.MANUAL), "still manual");
        lock.release(StepWorkLock.BACKGROUND);
        check(lock.heldBy(StepWorkLock.MANUAL), "wrong role cannot release");
        lock.release(StepWorkLock.MANUAL);
        check(!lock.held(), "released after failure path");
    }
}

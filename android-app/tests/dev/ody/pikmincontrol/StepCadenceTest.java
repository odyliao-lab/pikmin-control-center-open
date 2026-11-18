package dev.ody.pikmincontrol;

public final class StepCadenceTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
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

    public static void main(String[] args) {
        noWriteBeforeInterval();
        foregroundSleepsUntilPersistedDeadline();
        minFiveSeconds();
        waitingDoesNotAggregate();
        mixedConfirmRest();
        rebootDoesNotStick();
        System.out.println("PASS StepCadenceTest " + checks + " checks; R6 cadence");
    }

    static int aggregateCalls(FakeStepHealth health) {
        int n = 0;
        for (int i = 0; i < health.calls.size(); i++) {
            if (health.calls.get(i).startsWith("aggregate")) n++;
        }
        return n;
    }

    static boolean hasWriteCall(FakeStepHealth health) {
        for (int i = 0; i < health.calls.size(); i++) {
            if (health.calls.get(i).startsWith("write:")) return true;
        }
        return false;
    }

    static void noWriteBeforeInterval() {
        Fixture f = fx();
        long started = f.clock.wall;
        f.engine.start();
        f.engine.tick();
        check(f.health.writes == 0, "60s before first write");
        check(!hasWriteCall(f.health), "no write call");
        f.clock.advanceWall(5_000L);
        f.engine.tick();
        check(f.health.writes == 0, "still before 60s");
        f.clock.advanceWall(55_000L);
        f.engine.tick();
        check(f.health.writes == 1, "write at interval");
        check(f.health.writeLog.get(0).startMs >= started, "no pre-start backfill");
    }

    static void foregroundSleepsUntilPersistedDeadline() {
        Fixture f = fx();
        f.engine.start();
        check(f.engine.nextTickDelayMs() == 60_000L, "service initially sleeps one interval");
        f.clock.advanceWall(17_000L);
        check(f.engine.nextTickDelayMs() == 43_000L, "service subtracts elapsed time without drift");
        f.clock.advanceWall(43_000L);
        check(f.engine.nextTickDelayMs() == StepEngine.MIN_TICK_MS, "due work gets bounded immediate retry");
        f.engine.tick();
        check(f.engine.nextTickDelayMs() == 60_000L, "confirmed write anchors next minute");
    }

    static void minFiveSeconds() {
        Fixture f = fx();
        f.engine.configure(StepSettings.parse(8000, 0, 0, 130, "normal", 5_000L));
        f.engine.start();
        f.engine.tick();
        check(f.health.writes == 0, "not immediate");
        f.clock.advanceWall(5_000L);
        f.engine.tick();
        check(f.health.writes == 1, "5s due");
    }

    static void waitingDoesNotAggregate() {
        Fixture f = fx();
        f.engine.start();
        f.engine.tick();
        f.engine.tick();
        f.engine.tick();
        check(aggregateCalls(f.health) == 0, "waiting ticks do not aggregate");
    }

    static void mixedConfirmRest() {
        Fixture f = fx();
        f.engine.configure(StepSettings.parse(8000, 0, 0, 130, "normal", 5_000L));
        f.engine.start();
        for (int i = 0; i < 3; i++) {
            f.health.nextWrite = "timeout";
            f.health.writeFutureTimeoutThenStore = true;
            f.clock.advanceWall(5_000L);
            f.engine.tick();
            f.engine.tick();
        }
        check(StepState.load(f.store).restStreak == 3, "reconcile successes count toward rest");
        int writes = f.health.writes;
        f.clock.advanceWall(5_000L);
        StepView rest = f.engine.tick();
        check(StepView.RESTING.equals(rest.phase) || f.health.writes == writes, "rest after mixed confirms");
        check(f.health.writes == writes, "no write during rest");
    }

    static void rebootDoesNotStick() {
        Fixture f = fx();
        f.engine.start();
        StepState state = StepState.load(f.store);
        state.waitUntilElapsed = 8_000_000L;
        state.nextDueElapsed = 8_000_000L;
        state.elapsedBasis = 8_000_000L;
        StepState.save(f.store, state);
        f.clock.elapsed = 200L;
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 1, "reboot rebase allows due write");
    }
}

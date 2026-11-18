package dev.ody.pikmincontrol;

public final class StepRunCursorTest {
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

    static StepEngine rebuild(Fixture f) {
        return new StepEngine(f.clock, f.zone, new FixedStepRng(0.5d), f.store, f.health, new StepWorkLock(f.store));
    }

    public static void main(String[] args) {
        stopStartUsesNewAnchor();
        twoFactoryRebuilds();
        rebootElapsedBothWays();
        manualThenBackground();
        crossWindowReenter();
        processRebuildNoBackfill();
        sameProcessSecondFactoryIdempotent();
        rebootClearsStaleElapsedGate();
        sameBootProcessRestartKeepsElapsedGate();
        System.out.println("PASS StepRunCursorTest " + checks + " checks; S5 new-run cursor");
    }

    static void stopStartUsesNewAnchor() {
        Fixture f = fx();
        long firstAnchor = f.clock.wall;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 1, "first write");
        check(f.health.writeLog.get(0).startMs >= firstAnchor, "first from start wall");
        long oldEnd = f.health.writeLog.get(0).endMs;
        f.engine.stop();
        f.clock.advanceWall(3_600_000L);
        long secondAnchor = f.clock.wall;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 2, "second run wrote");
        StepPlan second = f.health.writeLog.get(1);
        check(second.startMs >= secondAnchor, "new run start >= new anchor " + second.startMs + " vs " + secondAnchor);
        check(second.startMs >= oldEnd || second.startMs >= secondAnchor, "not a silent inherit");
        check(second.startMs != oldEnd, "must not resume old lastConfirmedEndMs " + oldEnd);
    }

    static void twoFactoryRebuilds() {
        Fixture f = fx();
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        long oldEnd = f.health.writeLog.get(0).endMs;
        f.engine.stop();
        f.clock.advanceWall(3_600_000L);
        StepEngine a = rebuild(f);
        StepEngine b = rebuild(f);
        long anchor = f.clock.wall;
        a.start();
        f.clock.advanceWall(60_000L);
        b.tick();
        check(f.health.writes == 2, "rebuilt factory wrote once");
        StepPlan written = f.health.writeLog.get(1);
        check(written.startMs >= anchor, "rebuild first interval vs anchor " + written.startMs + " >= " + anchor);
        check(written.startMs > oldEnd, "rebuild does not inherit " + oldEnd);
    }

    static void rebootElapsedBothWays() {
        Fixture f = fx();
        f.engine.start();
        long anchor = StepState.load(f.store).runStartWall;
        StepState state = StepState.load(f.store);
        state.elapsedBasis = 8_000_000L;
        state.nextDueElapsed = 8_000_000L;
        state.waitUntilElapsed = 8_000_000L;
        StepState.save(f.store, state);
        f.clock.elapsed = 200L;
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 1, "small elapsed rebase writes");
        check(f.health.writeLog.get(0).startMs >= anchor, "small elapsed keeps wall anchor");

        Fixture g = fx();
        g.engine.start();
        long gAnchor = StepState.load(g.store).runStartWall;
        StepState gs = StepState.load(g.store);
        gs.elapsedBasis = 100L;
        gs.nextDueElapsed = 100L + gs.settings.intervalMs;
        gs.waitUntilElapsed = 0L;
        StepState.save(g.store, gs);
        g.clock.elapsed = 9_000_000L;
        g.clock.advanceWall(60_000L);
        g.engine.tick();
        check(g.health.writes == 1, "large elapsed still writes when due");
        check(g.health.writeLog.get(0).startMs >= gAnchor, "large elapsed keeps wall anchor");
    }

    static void manualThenBackground() {
        Fixture f = fx();
        StepTestConfirm.previewManual(f.engine, 15);
        StepTestConfirm.confirmManual(f.engine, 15);
        check(f.health.writes == 1, "manual first");
        long manualEnd = f.health.writeLog.get(0).endMs;
        f.clock.advanceWall(3_600_000L);
        long anchor = f.clock.wall;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 2, "bg after manual");
        check(f.health.writeLog.get(1).startMs >= anchor, "bg uses run anchor not manual end " + manualEnd);
    }

    static void crossWindowReenter() {
        Fixture f = fx();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 21, 50, 8 * 3600);
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 1, "inside window write");
        long oldEnd = f.health.writeLog.get(0).endMs;
        f.engine.stop();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 9, 0, 8 * 3600);
        f.clock.elapsed += 60_000L;
        long anchor = f.clock.wall;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.health.writes == 2, "next window write");
        check(f.health.writeLog.get(1).startMs >= anchor, "cross-window first vs anchor");
        check(f.health.writeLog.get(1).startMs > oldEnd, "does not backfill offline " + oldEnd);
    }

    static StepEngine engineWith(Fixture f, StepStore store, StepProcessIdentity identity) {
        return new StepEngine(f.clock, f.zone, new FixedStepRng(0.5d), store, f.health, new StepWorkLock(store),
                true, identity);
    }

    static void processRebuildNoBackfill() {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore firstView = shared.view();
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        FakeStepHealth health = new FakeStepHealth();
        StepProcessIdentity boot1 = new StepProcessIdentity("proc-1", "boot-1");
        StepEngine first = new StepEngine(clock, zone, new FixedStepRng(0.5d), firstView, health,
                new StepWorkLock(firstView), true, boot1);
        first.disclose();
        first.start();
        clock.advanceWall(60_000L);
        first.tick();
        check(health.writes == 1, "original write");
        long oldStart = health.writeLog.get(0).startMs;
        String lockBefore = StepState.load(firstView).lockId;
        clock.advanceWall(3_600_000L);
        long recoverAnchor = clock.wall;
        StepStore rebuiltView = shared.view();
        StepEngine rebuilt = new StepEngine(clock, zone, new FixedStepRng(0.5d), rebuiltView, health,
                new StepWorkLock(rebuiltView), true, new StepProcessIdentity("proc-2", "boot-2"));
        StepView recovered = rebuilt.recoverIfAllowed();
        check(recovered.desiredRunning, "desired stays true without STOP");
        check(StepState.load(rebuiltView).runStartWall >= recoverAnchor,
                "rebuild anchor " + StepState.load(rebuiltView).runStartWall + " >= " + recoverAnchor);
        check(lockBefore.equals(StepState.load(rebuiltView).lockId) || StepState.load(rebuiltView).lockId.length() > 0
                || lockBefore.length() == 0, "did not blindly clear writer lock");
        clock.elapsed = 200L;
        clock.advanceWall(60_000L);
        rebuilt.tick();
        check(health.writes == 2, "rebuild wrote after new anchor");
        check(health.writeLog.get(1).startMs >= recoverAnchor,
                "no offline backfill start=" + health.writeLog.get(1).startMs + " anchor=" + recoverAnchor);
        check(health.writeLog.get(1).startMs != oldStart, "not the 10:01 cursor");

        SharedMemoryStore shared2 = new SharedMemoryStore();
        StepStore v = shared2.view();
        MutableStepClock clock2 = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        FakeStepHealth health2 = new FakeStepHealth();
        StepEngine a = new StepEngine(clock2, zone, new FixedStepRng(0.5d), v, health2, new StepWorkLock(v), true,
                new StepProcessIdentity("p-a", "b-a"));
        a.disclose();
        a.start();
        clock2.advanceWall(60_000L);
        a.tick();
        clock2.advanceWall(3_600_000L);
        clock2.elapsed = 9_000_000L;
        StepEngine large = new StepEngine(clock2, zone, new FixedStepRng(0.5d), shared2.view(), health2,
                new StepWorkLock(shared2.view()), true, new StepProcessIdentity("p-b", "b-b"));
        long largeAnchor = clock2.wall;
        large.recoverIfAllowed();
        clock2.advanceWall(60_000L);
        large.tick();
        check(health2.writes == 2, "large elapsed rebuild wrote");
        check(health2.writeLog.get(1).startMs >= largeAnchor, "large elapsed first write >= new anchor");
    }

    static void sameProcessSecondFactoryIdempotent() {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore view = shared.view();
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        FakeStepHealth health = new FakeStepHealth();
        StepProcessIdentity same = new StepProcessIdentity("same-proc", "same-boot");
        StepEngine a = new StepEngine(clock, zone, new FixedStepRng(0.5d), view, health, new StepWorkLock(view), true, same);
        a.disclose();
        a.start();
        clock.advanceWall(60_000L);
        a.tick();
        long anchor = StepState.load(view).runStartWall;
        String session = StepState.load(view).sessionId;
        StepEngine b = new StepEngine(clock, zone, new FixedStepRng(0.5d), shared.view(), health,
                new StepWorkLock(shared.view()), true, same);
        StepView again = b.start();
        check(again.desiredRunning, "same-process re-START still desired");
        check(session.equals(StepState.load(view).sessionId), "second factory is idempotent");
        check(StepState.load(view).runStartWall == anchor, "same-process keeps anchor");
        b.tick();
        check(health.writes == 1, "idempotent start did not invent a backfill write");
    }

    static void rebootClearsStaleElapsedGate() {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore firstView = shared.view();
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 16, 31, 8 * 3600), 332_683_643L);
        FakeStepHealth health = new FakeStepHealth();
        StepEngine first = new StepEngine(clock, zone, new FixedStepRng(0.5d), firstView, health,
                new StepWorkLock(firstView), true, new StepProcessIdentity("old-process", "old-boot"));
        first.disclose();
        first.start();
        StepState stale = StepState.load(firstView);
        stale.waitUntilElapsed = 332_703_659L;
        stale.lastSuccessElapsed = 332_683_643L;
        stale.elapsedBasis = 332_683_643L;
        stale.restStreak = 3;
        stale.backoffMs = 20_000;
        StepState.save(firstView, stale);

        clock.wall = zone.toEpochMillis(2024, 1, 1, 21, 3, 8 * 3600);
        clock.elapsed = 16_149_513L;
        StepStore rebootedView = shared.view();
        StepEngine rebooted = new StepEngine(clock, zone, new FixedStepRng(0.5d), rebootedView, health,
                new StepWorkLock(rebootedView), true, new StepProcessIdentity("new-process", "new-boot"));
        rebooted.recoverIfAllowed();
        StepState repaired = StepState.load(rebootedView);
        check(repaired.waitUntilElapsed <= clock.elapsed, "reboot clears stale wait deadline");
        check(repaired.lastSuccessElapsed == 0L, "reboot clears stale success clock");
        check(repaired.restStreak == 0, "reboot clears rest streak");
        check(repaired.backoffMs == 0, "reboot clears elapsed backoff");
        long anchor = repaired.runStartWall;
        clock.advanceWall(60_000L);
        rebooted.tick();
        check(health.writes == 1, "rebooted writer resumes after one interval");
        check(health.writeLog.get(0).startMs >= anchor, "reboot does not backfill offline interval");
    }

    static void sameBootProcessRestartKeepsElapsedGate() {
        SharedMemoryStore shared = new SharedMemoryStore();
        StepStore firstView = shared.view();
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        FakeStepHealth health = new FakeStepHealth();
        StepEngine first = new StepEngine(clock, zone, new FixedStepRng(0.5d), firstView, health,
                new StepWorkLock(firstView), true, new StepProcessIdentity("process-a", "same-boot"));
        first.disclose();
        first.start();
        StepState waiting = StepState.load(firstView);
        waiting.waitUntilElapsed = 40_000L;
        waiting.elapsedBasis = 10_000L;
        StepState.save(firstView, waiting);

        StepEngine restarted = new StepEngine(clock, zone, new FixedStepRng(0.5d), shared.view(), health,
                new StepWorkLock(shared.view()), true, new StepProcessIdentity("process-b", "same-boot"));
        restarted.recoverIfAllowed();
        check(StepState.load(firstView).waitUntilElapsed == 40_000L,
                "same-boot process restart preserves valid elapsed deadline");
    }
}

package dev.ody.pikmincontrol;

public final class StepLookupTest {
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

    public static void main(String[] args) {
        crossDayUsesOriginalRange();
        deadlineExhausted();
        payloadMismatchNotSuccess();
        missNotSuccess();
        lookupFailNotSuccess();
        ioWriteStaysUncertain();
        stalledAndRemainingHelpers();
        System.out.println("PASS StepLookupTest " + checks + " checks; R7 original-range lookup");
    }

    static StepPlan seedPending(Fixture f, long start, long end) {
        f.engine.start();
        StepPlan pending = new StepPlan("pend", StepPlan.BACKGROUND, StepPlan.UNCERTAIN, 10, start, end,
                8 * 3600, 8 * 3600, "pcc-step-pend", 0L, "", "timeout");
        StepState state = StepState.load(f.store);
        state.ledger.add(pending);
        StepState.save(f.store, state);
        return pending;
    }

    static void crossDayUsesOriginalRange() {
        Fixture f = fx();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 23, 50, 8 * 3600);
        long start = f.zone.toEpochMillis(2024, 1, 1, 23, 59, 8 * 3600) - 60_000L;
        long end = start + 60_000L;
        StepPlan pending = seedPending(f, start, end);
        f.health.byClient.put(pending.clientRecordId, pending.withStatus(StepPlan.CONFIRMED, "hc-pend", ""));
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 10, 0, 8 * 3600);
        f.engine.tick();
        StepPlan stored = StepState.load(f.store).ledger.byClientRecordId(pending.clientRecordId);
        check(StepPlan.CONFIRMED.equals(stored.status), "cross-day same id confirmed");
        boolean usedOriginal = false;
        for (int i = 0; i < f.health.calls.size(); i++) {
            String call = f.health.calls.get(i);
            if (call.equals("lookup:" + pending.clientRecordId + ":" + start + ":" + end)) usedOriginal = true;
            check(!call.contains("lookup:" + pending.clientRecordId + ":" + f.zone.dayStartMillis(f.clock.wall)),
                    "must not lookup today-only range");
        }
        check(usedOriginal, "lookup used pending start/end");
    }

    static void deadlineExhausted() {
        Fixture f = fx();
        long start = f.clock.wall - 60_000L;
        seedPending(f, start, f.clock.wall);
        f.health.lookupPageCostMs = 5_000;
        f.health.lookupPagesNeeded = 3;
        StepView view = f.engine.tick();
        check(StepView.RESULT_PENDING.equals(view.phase), "deadline keeps pending");
        check(StepState.load(f.store).ledger.pending() != null, "not confirmed on timeout");
        check(!StepPlan.CONFIRMED.equals(StepState.load(f.store).ledger.all().get(StepState.load(f.store).ledger.all().size() - 1).status),
                "timeout is not success");
    }

    static void payloadMismatchNotSuccess() {
        Fixture f = fx();
        long start = f.clock.wall - 60_000L;
        StepPlan pending = seedPending(f, start, f.clock.wall);
        f.health.byClient.put(pending.clientRecordId, pending.withStatus(StepPlan.CONFIRMED, "hc-pend", ""));
        f.health.lookupMismatch = true;
        f.engine.tick();
        StepPlan stored = StepState.load(f.store).ledger.byClientRecordId(pending.clientRecordId);
        check(StepPlan.UNCERTAIN.equals(stored.status), "payload mismatch stays uncertain");
        check(!"CONFIRMED".equals(stored.status), "health id does not mask mismatch");
    }

    static void missNotSuccess() {
        Fixture f = fx();
        long start = f.clock.wall - 60_000L;
        StepPlan pending = seedPending(f, start, f.clock.wall);
        f.engine.tick();
        StepPlan stored = StepState.load(f.store).ledger.byClientRecordId(pending.clientRecordId);
        check(stored.pending(), "miss stays pending");
        check(!StepPlan.CONFIRMED.equals(stored.status), "miss is not success");
    }

    static void lookupFailNotSuccess() {
        Fixture f = fx();
        long start = f.clock.wall - 60_000L;
        StepPlan pending = seedPending(f, start, f.clock.wall);
        f.health.lookupFail = true;
        f.engine.tick();
        check(StepState.load(f.store).ledger.pending() != null, "lookup fail stays pending");
        check(!StepPlan.CONFIRMED.equals(StepState.load(f.store).ledger.byClientRecordId(pending.clientRecordId).status),
                "fail is not success");
    }

    static void ioWriteStaysUncertain() {
        Fixture f = fx();
        f.engine.start();
        f.health.nextWrite = "fail";
        f.health.failKind = "io";
        f.clock.advanceWall(60_000L);
        StepView view = f.engine.tick();
        check(StepView.RESULT_PENDING.equals(view.phase) || StepState.load(f.store).ledger.pending() != null,
                "io write is uncertain not failed-retry");
        check(StepPlan.UNCERTAIN.equals(StepState.load(f.store).ledger.pending().status), "io stays UNCERTAIN");
    }

    static void stalledAndRemainingHelpers() {
        check(!StepHealthGatewayApi34.stalledPage(0, 1L, 1L), "first page may reuse token");
        check(StepHealthGatewayApi34.stalledPage(1, 4L, 4L), "repeat token detected");
        check(!StepHealthGatewayApi34.stalledPage(1, 4L, 5L), "progressing token ok");
        long past = System.nanoTime() - 1_000_000L;
        check(StepHealthGatewayApi34.remainingMs(past) <= 0L, "exhausted deadline");
    }
}

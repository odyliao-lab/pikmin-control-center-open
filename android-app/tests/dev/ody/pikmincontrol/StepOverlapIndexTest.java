package dev.ody.pikmincontrol;

public final class StepOverlapIndexTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        displayTrimKeepsOverlap();
        healthRecordBlocksLocalGap();
        pageFailureCannotClaimClear();
        pageLimitCannotClaimClear();
        System.out.println("PASS StepOverlapIndexTest " + checks + " checks; R4 overlap index");
    }

    static Fixture fx() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 10_000L);
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

    static void displayTrimKeepsOverlap() {
        Fixture f = fx();
        long dayStart = f.zone.dayStartMillis(f.clock.wall);
        StepState state = StepState.load(f.store);
        for (int i = 0; i < 201; i++) {
            long start = dayStart + i * 60_000L;
            String id = "old-" + i;
            state.ledger.add(new StepPlan(id, StepPlan.MANUAL, StepPlan.CONFIRMED, 1, start, start + 60_000L, 28800, 28800, "pcc-" + id, 0, "h" + i, ""));
        }
        StepState.save(f.store, state);
        StepState reloaded = StepState.load(f.store);
        check(reloaded.ledger.all().size() <= 200, "display bounded");
        check(!reloaded.ledger.occupiedSpans().isEmpty(), "safety index kept");
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, new StepCatchUpRequest("window", 1, 1, 2024, 1, 1, 0, 0, 0, 1, null, null));
        check(StepView.REJECTED.equals(preview.phase), "first minute still overlapping after 201");
        check(preview.detail.contains("重疊"), preview.detail);
    }

    static void healthRecordBlocksLocalGap() {
        Fixture f = fx();
        long start = f.clock.wall - 30_000L;
        f.health.byClient.put("hc-old", new StepPlan("hc-old", StepPlan.MANUAL, StepPlan.CONFIRMED, 4, start, start + 10_000L, 28800, 28800, "hc-old", 0, "hid", ""));
        StepView view = StepTestConfirm.confirmManual(f.engine, 3);
        check(StepView.REJECTED.equals(view.phase), "HC record blocks");
        check(f.health.writes == 0, "no overwrite write");
    }

    static void pageFailureCannotClaimClear() {
        Fixture f = fx();
        f.health.pageFail = true;
        StepView view = StepTestConfirm.confirmManual(f.engine, 3);
        check(StepView.REJECTED.equals(view.phase), "page fail rejected");
        check(f.health.writes == 0, "no write without complete read");
    }

    static void pageLimitCannotClaimClear() {
        Fixture f = fx();
        f.health.pageLimit = true;
        StepView view = StepTestConfirm.confirmManual(f.engine, 3);
        check(StepView.REJECTED.equals(view.phase), "page-limit rejected");
        check(f.health.writes == 0, "no write on incomplete pages");
    }
}

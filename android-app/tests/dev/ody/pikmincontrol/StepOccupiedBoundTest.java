package dev.ody.pikmincontrol;

public final class StepOccupiedBoundTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    public static void main(String[] args) {
        multiDayStaysBounded();
        todayOver200AndPendingSafe();
        nearLimitRejectPreservesPrior();
        engineManualCatchUpCompactsWithoutTick();
        decodeDoesNotRebuildDroppedSpans();
        System.out.println("PASS StepOccupiedBoundTest " + checks + " checks; S8 occupied bound");
    }

    static void multiDayStaysBounded() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        long day0 = zone.toEpochMillis(2024, 1, 1, 0, 0, 8 * 3600);
        StepState state = new StepState();
        state.disclosed = true;
        state.lockGeneration = 1L;
        state.cancelNonce = 0L;
        for (int day = 0; day < 40; day++) {
            for (int i = 0; i < 80; i++) {
                long start = day0 + day * 86_400_000L + i * 60_000L;
                state.ledger.remember(new StepPlan("d" + day + "-" + i, StepPlan.BACKGROUND, StepPlan.CONFIRMED, 1,
                        start, start + 60_000L, 28800, 28800, "c" + day + "-" + i, 0, "h", ""));
            }
        }
        long today = day0 + 39 * 86_400_000L;
        int before = state.ledger.occupiedSpans().size();
        state.ledger.compact(today);
        int after = state.ledger.occupiedSpans().size();
        check(after <= before, "compact does not grow");
        check(after <= StepLedger.MAX_OCCUPIED, "occupied bounded " + after);
        String encoded = state.encode();
        check(encoded.length() < 200_000, "multi-day encode bounded " + encoded.length());
        check(state.ledger.overlaps(today, today + 60_000L), "today still protected");
    }

    static void todayOver200AndPendingSafe() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 10_000L);
        MemoryStepStore store = new MemoryStepStore();
        FakeStepHealth health = new FakeStepHealth();
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        engine.disclose();
        long dayStart = zone.dayStartMillis(clock.wall);
        StepState state = StepState.load(store);
        for (int i = 0; i < 220; i++) {
            long start = dayStart + i * 60_000L;
            state.ledger.add(new StepPlan("t" + i, StepPlan.MANUAL, StepPlan.CONFIRMED, 1, start, start + 60_000L,
                    28800, 28800, "ct" + i, 0, "h" + i, ""));
        }
        long pendStart = dayStart - 3_600_000L;
        state.ledger.add(new StepPlan("pend", StepPlan.MANUAL, StepPlan.UNCERTAIN, 4, pendStart, pendStart + 60_000L,
                28800, 28800, "cpend", 0, "", "timeout"));
        StepState.save(store, state);
        StepState loaded = StepState.load(store);
        check(loaded.ledger.all().size() <= 200 || loaded.ledger.pending() != null, "display trim or pending kept");
        check(loaded.ledger.pending() != null, "pending retained");
        check(loaded.ledger.overlaps(pendStart, pendStart + 60_000L), "pending span kept");
        check(loaded.ledger.overlaps(dayStart, dayStart + 60_000L), "today still blocked");
        StepView preview = StepTestConfirm.previewCatchUp(engine, new StepCatchUpRequest("window", 1, 1, 2024, 1, 1, 0, 0, 0, 1, null, null));
        check(StepView.REJECTED.equals(preview.phase), "today>200 still overlap-safe");
    }

    static void nearLimitRejectPreservesPrior() {
        MemoryStepStore store = new MemoryStepStore();
        StepState small = new StepState();
        small.disclosed = true;
        small.lockGeneration = 1L;
        small.cancelNonce = 0L;
        String prior = small.encode();
        store.save(prior);
        StringBuilder huge = new StringBuilder(prior);
        while (huge.length() < StepCoordinator.MAX_SNAPSHOT_CHARS + 50) huge.append("x");
        try {
            store.save(huge.toString());
            check(false, "oversize save must throw");
        } catch (StepStoreException error) {
            check(error.getMessage().contains("too-large"), "size reject");
        }
        check(prior.equals(store.load().raw), "prior snapshot preserved");
        StepCoordinator coordinator = StepCoordinator.of(store);
        StepState fat = StepState.decode(prior);
        fat.lastOpDetail = huge.toString();
        try {
            coordinator.persist(fat);
            check(false, "coordinator persist must reject");
        } catch (StepStoreException error) {
            check(error.getMessage().contains("too-large"), "coordinator reject");
        }
        check(prior.equals(store.load().raw), "coordinator reject left prior file");
    }

    static void engineManualCatchUpCompactsWithoutTick() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        long yesterday = zone.toEpochMillis(2023, 12, 31, 0, 0, 8 * 3600);
        long today = zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600);
        MemoryStepStore store = new MemoryStepStore();
        StepState state = new StepState();
        state.disclosed = true;
        state.lockGeneration = 1L;
        state.cancelNonce = 0L;
        state.dayKey = "2023-12-31";
        for (int i = 0; i < 600; i++) {
            long start = yesterday + i * 120_000L;
            state.ledger.remember(new StepPlan("y" + i, StepPlan.BACKGROUND, StepPlan.CONFIRMED, 1,
                    start, start + 60_000L, 28800, 28800, "cy" + i, 0, "h" + i, ""));
        }
        long pendStart = yesterday - 3_600_000L;
        StepState.save(store, state);
        check(StepState.load(store).ledger.occupiedSpans().size() > 512, "seeded over soft target");
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(today, 20_000L);
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(engine, 6).phase), "manual preview");
        StepView written = StepTestConfirm.confirmManual(engine, 6);
        check(health.writes == 1 || StepEngine.OUTCOME_SUCCESS.equals(written.opKind),
                "manual wrote via engine without tick");
        StepState after = StepState.load(store);
        check(after.ledger.occupiedSpans().size() <= StepLedger.MAX_OCCUPIED + 8,
                "engine compact bounded " + after.ledger.occupiedSpans().size());
        check(after.ledger.overlaps(clock.wall - 60_000L, clock.wall) || after.ledger.pending() != null
                || health.writes == 1, "today write protected");
        MemoryStepStore pendingStore = new MemoryStepStore();
        StepState withPending = StepState.decode(store.load().raw);
        withPending.ledger.add(new StepPlan("pend", StepPlan.MANUAL, StepPlan.UNCERTAIN, 3, pendStart, pendStart + 60_000L,
                28800, 28800, "cpend", 0, "", "timeout"));
        StepState.save(pendingStore, withPending);
        check(StepState.load(pendingStore).ledger.overlaps(pendStart, pendStart + 60_000L), "cross-day pending kept");

        MemoryStepStore todayStore = new MemoryStepStore();
        StepState todayState = new StepState();
        todayState.disclosed = true;
        todayState.lockGeneration = 1L;
        todayState.cancelNonce = 0L;
        long dayStart = zone.dayStartMillis(today);
        for (int i = 0; i < 520; i++) {
            long start = dayStart + i * 120_000L;
            todayState.ledger.remember(new StepPlan("t" + i, StepPlan.MANUAL, StepPlan.CONFIRMED, 1,
                    start, start + 60_000L, 28800, 28800, "ct" + i, 0, "ht" + i, ""));
        }
        StepState.save(todayStore, todayState);
        FakeStepHealth health2 = new FakeStepHealth();
        StepEngine todayEngine = new StepEngine(clock, zone, new FixedStepRng(0.5d), todayStore, health2,
                new StepWorkLock(todayStore));
        StepView preview = StepTestConfirm.previewCatchUp(todayEngine, new StepCatchUpRequest("window", 1, 1, 2024, 1, 1, 0, 0, 0, 1, null, null));
        check(StepView.REJECTED.equals(preview.phase) || todayState.ledger.overlaps(dayStart, dayStart + 60_000L),
                "today discrete>512 still overlap-safe");
        check(StepState.load(todayStore).ledger.occupiedSpans().size() >= 512, "today not trimmed to 512");
        check(StepState.load(todayStore).ledger.overlaps(dayStart, dayStart + 60_000L), "today evidence kept");
    }

    static void decodeDoesNotRebuildDroppedSpans() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        long yesterday = zone.toEpochMillis(2023, 12, 31, 0, 0, 8 * 3600);
        StepState state = new StepState();
        state.disclosed = true;
        state.lockGeneration = 1L;
        state.cancelNonce = 0L;
        for (int i = 0; i < 80; i++) {
            long start = yesterday + i * 60_000L;
            state.ledger.add(new StepPlan("old" + i, StepPlan.BACKGROUND, StepPlan.CONFIRMED, 1,
                    start, start + 60_000L, 28800, 28800, "cold" + i, 0, "h" + i, ""));
        }
        long pendStart = yesterday - 7_200_000L;
        state.ledger.add(new StepPlan("pend", StepPlan.MANUAL, StepPlan.UNCERTAIN, 2, pendStart, pendStart + 60_000L,
                28800, 28800, "cpend", 0, "", "timeout"));
        long keepFrom = yesterday + 86_400_000L;
        state.ledger.compact(keepFrom);
        int compacted = state.ledger.occupiedSpans().size();
        String raw = state.encode();
        StepState loaded = StepState.decode(raw);
        check(loaded.ledger.occupiedSpans().size() == compacted,
                "decode does not rebuild dropped spans " + loaded.ledger.occupiedSpans().size() + " vs " + compacted);
        check(loaded.ledger.pending() != null, "pending plan retained");
        check(loaded.ledger.overlaps(pendStart, pendStart + 60_000L), "pending span not dropped");
    }
}

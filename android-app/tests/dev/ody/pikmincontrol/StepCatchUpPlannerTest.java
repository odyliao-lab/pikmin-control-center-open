package dev.ody.pikmincontrol;

public final class StepCatchUpPlannerTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }
    static void rejects(StepView view, String needle) {
        checks++;
        if (!StepView.REJECTED.equals(view.phase))
            throw new AssertionError("accepted: " + view.phase + " " + view.detail + " vs " + needle);
        if (!view.detail.contains(needle)) throw new AssertionError(view.detail + " vs " + needle);
    }
    static Fixture fx(String zoneId, int hour) {
        StepSystemZone zone = new StepSystemZone(zoneId);
        int offset = zone.offsetSecondsAt(zone.toEpochMillis(2024, 1, 1, hour, 0, zone.offsetsAtLocal(2024, 1, 1, hour, 0).get(0)));
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, hour, 0, offset), 50_000L);
        MemoryStepStore store = new MemoryStepStore();
        return new Fixture(clock, zone, store, new StepEngine(clock, zone, new FixedStepRng(0.5d), store, new FakeStepHealth(), new StepWorkLock(store)));
    }
    static final class Fixture {
        final MutableStepClock clock;
        final StepZone zone;
        final MemoryStepStore store;
        final StepEngine engine;
        Fixture(MutableStepClock clock, StepZone zone, MemoryStepStore store, StepEngine engine) {
            this.clock = clock; this.zone = zone; this.store = store; this.engine = engine;
        }
    }

    public static void main(String[] args) {
        insufficientMinutes();
        midnight();
        future();
        dstGap();
        dstOverlap();
        maxMinutes();
        maxSteps();
        confirmConditionChanged();
        remainderTotal();
        lowCountNoNegative();
        totalModeToday();
        recentBacktracksAndCaps();
        recentSkipsOccupiedMinute();
        recentRejectsUnsafeRateAndInsufficientDay();
        manualUsesRecentMinuteAndCaps();
        System.out.println("PASS StepCatchUpPlannerTest " + checks + " checks");
    }

    static StepCatchUpRequest req(Fixture f, int startH, int startM, int endH, int endM, long steps, int rate) {
        int[] ymd = StepCatchUpPlanner.localYmd(f.zone, f.clock.wall);
        return new StepCatchUpRequest("window", steps, rate, ymd[0], ymd[1], ymd[2], startH, startM, endH, endM, null, null);
    }

    static void insufficientMinutes() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        StepView view = StepTestConfirm.previewCatchUp(f.engine, req(f, 10, 0, 10, 5, 1000, 10));
        check(StepView.REJECTED.equals(view.phase) && view.detail.contains("分鐘不足"), "分鐘不足");
    }

    static void midnight() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(f.engine, new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 23, 0, 1, 0, null, null)), "終點必須晚於起點");
    }

    static void future() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(f.engine, req(f, 13, 0, 14, 0, 10, 10)), "未來");
    }

    static void dstGap() {
        StepSystemZone zone = new StepSystemZone("America/New_York");
        check(!zone.localTimeExists(2024, 3, 10, 2, 30), "gap");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 3, 10, 12, 0, -4 * 3600), 1);
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), new MemoryStepStore(), new FakeStepHealth(), new StepWorkLock(new MemoryStepStore()));
        engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(engine, new StepCatchUpRequest("window", 10, 10, 2024, 3, 10, 2, 30, 12, 0, null, null)), "不存在");
    }

    static void dstOverlap() {
        StepSystemZone zone = new StepSystemZone("America/New_York");
        check(zone.offsetsAtLocal(2024, 11, 3, 1, 30).size() == 2, "overlap");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 11, 3, 12, 0, -5 * 3600), 1);
        MemoryStepStore store = new MemoryStepStore();
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(engine, new StepCatchUpRequest("window", 10, 10, 2024, 11, 3, 1, 30, 3, 0, null, null)), "offset");
        StepView ok = StepTestConfirm.previewCatchUp(engine, new StepCatchUpRequest("window", 10, 10, 2024, 11, 3, 1, 30, 3, 0, Integer.valueOf(-4 * 3600), Integer.valueOf(-5 * 3600)));
        check(!ok.previewPlans.isEmpty(), "explicit offset accepted");
    }

    static void maxMinutes() {
        Fixture f = fx("Asia/Taipei", 23);
        f.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(f.engine, req(f, 0, 0, 12, 1, 10, 1)), "720");
    }

    static void maxSteps() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(f.engine, req(f, 10, 0, 11, 0, 1_000_001L, 10)), "1000000");
    }

    static void confirmConditionChanged() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        StepCatchUpRequest request = req(f, 10, 0, 11, 0, 60, 10);
        check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), "preview");
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 2, 12, 0, 8 * 3600);
        StepView changed = StepTestConfirm.confirmCatchUp(f.engine, request);
        check(StepView.REJECTED.equals(changed.phase), "recheck after confirm");
    }

    static void remainderTotal() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, req(f, 10, 0, 11, 0, 1000, 20));
        long total = 0L;
        for (int i = 0; i < preview.previewPlans.size(); i++) {
            StepPlan p = preview.previewPlans.get(i);
            check(p.endMs > p.startMs, "end>start");
            if (i > 0) check(p.startMs >= preview.previewPlans.get(i - 1).endMs, "no overlap");
            total += p.count;
        }
        check(total == 1000, "remainder keeps total");
        StepView done = StepTestConfirm.confirmCatchUp(f.engine, req(f, 10, 0, 11, 0, 1000, 20));
        check(!StepView.REJECTED.equals(done.phase), "executed");
        check(done.sessionConfirmed == 1000, "session total");
    }

    static void assertLegal(java.util.List<StepPlan> plans, long steps, String label) {
        check(!plans.isEmpty(), label + " not empty");
        long total = 0L;
        for (int i = 0; i < plans.size(); i++) {
            StepPlan p = plans.get(i);
            check(p.count >= 1L, label + " count>0 #" + i + " was " + p.count);
            check(p.endMs > p.startMs, label + " end>start #" + i);
            check(p.endMs - p.startMs <= StepSettings.MAX_RECORD_MS, label + " max span #" + i);
            if (i > 0) check(p.startMs >= plans.get(i - 1).endMs, label + " no overlap");
            total += p.count;
        }
        check(total == steps, label + " total " + total);
    }

    static void lowCountNoNegative() {
        Fixture hour = fx("Asia/Taipei", 12);
        hour.engine.disclose();
        StepView oneHour = StepTestConfirm.previewCatchUp(hour.engine, req(hour, 10, 0, 11, 0, 1, 1));
        assertLegal(oneHour.previewPlans, 1L, "1/60");
        StepView nineteen = StepTestConfirm.previewCatchUp(hour.engine, req(hour, 10, 0, 11, 0, 19, 1));
        assertLegal(nineteen.previewPlans, 19L, "19/60");
        FakeStepHealth health = new FakeStepHealth();
        MemoryStepStore store = new MemoryStepStore();
        StepEngine engine = new StepEngine(hour.clock, hour.zone, new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        engine.disclose();
        StepCatchUpRequest one = req(hour, 10, 0, 11, 0, 1, 1);
        check(!StepTestConfirm.previewCatchUp(engine, one).previewPlans.isEmpty(), "1/60 preview on writer");
        StepView written = StepTestConfirm.confirmCatchUp(engine, one);
        check(!StepView.REJECTED.equals(written.phase), "1/60 executed");
        check(health.writes == 1, "illegal plan never sent extra writes=" + health.writes);
        check(written.sessionConfirmed == 1, "1 step confirmed");

        Fixture longDay = fx("Asia/Taipei", 12);
        longDay.engine.disclose();
        StepView sevenTwenty = StepTestConfirm.previewCatchUp(longDay.engine, req(longDay, 0, 0, 12, 0, 1, 1));
        assertLegal(sevenTwenty.previewPlans, 1L, "1/720");
        check(sevenTwenty.previewPlans.size() < 240, "zero-count spans omitted");
    }

    static void totalModeToday() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        int[] ymd = StepCatchUpPlanner.localYmd(f.zone, f.clock.wall);
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, new StepCatchUpRequest("total", 10, 10, ymd[0], ymd[1], ymd[2], 0, 0, 0, 0, null, null));
        check(!StepView.REJECTED.equals(preview.phase), "total accepted " + preview.detail);
        check(!preview.previewPlans.isEmpty(), "total has plans");
        check(preview.previewPlans.get(0).startMs >= f.zone.dayStartMillis(f.clock.wall), "total within today");
        check(preview.previewPlans.get(preview.previewPlans.size() - 1).endMs <= f.clock.wall, "total not future");
    }

    static StepCatchUpRequest recent(Fixture f, long steps, int rate) {
        int[] ymd = StepCatchUpPlanner.localYmd(f.zone, f.clock.wall);
        return new StepCatchUpRequest("recent", steps, rate, ymd[0], ymd[1], ymd[2],
                0, 0, 0, 0, null, null, f.zone.clockEpoch(f.clock.wall));
    }

    static void recentBacktracksAndCaps() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, recent(f, 5000, 350));
        check(!StepView.REJECTED.equals(preview.phase), "recent accepted " + preview.detail);
        assertLegal(preview.previewPlans, 5000L, "recent 5000");
        long newestEnd = (f.clock.wall / 60_000L) * 60_000L;
        check(preview.previewPlans.get(preview.previewPlans.size() - 1).endMs == newestEnd, "ends at last full minute");
        check(preview.previewPlans.get(0).startMs == newestEnd - 15L * 60_000L, "ceil(5000/350) minutes");
        for (StepPlan plan : preview.previewPlans) {
            long minutes = (plan.endMs - plan.startMs) / 60_000L;
            check(plan.count <= 350L * minutes, "each record capped by rate");
        }

        StepView longPreview = StepTestConfirm.previewCatchUp(f.engine, recent(f, 50_000, 130));
        assertLegal(longPreview.previewPlans, 50_000L, "recent 50000");
        check(longPreview.previewPlans.get(0).startMs == newestEnd - 385L * 60_000L, "50000/130 backtrack");
        check(longPreview.previewPlans.size() <= 129, "three-minute records bound API calls");
    }

    static void recentSkipsOccupiedMinute() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        long end = (f.clock.wall / 60_000L) * 60_000L;
        StepState state = StepState.load(f.store);
        state.ledger.add(new StepPlan("occupied", StepPlan.MANUAL, StepPlan.CONFIRMED, 10,
                end - 2L * 60_000L, end - 60_000L, 8 * 3600, 8 * 3600,
                "pcc-step-occupied", 0L, "health", ""));
        StepState.save(f.store, state);
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, recent(f, 700, 350));
        assertLegal(preview.previewPlans, 700L, "recent skip occupied");
        for (StepPlan plan : preview.previewPlans)
            check(!plan.overlaps(end - 2L * 60_000L, end - 60_000L), "occupied minute skipped");
        check(preview.previewPlans.get(0).startMs == end - 3L * 60_000L, "search continues backward");
    }

    static void recentRejectsUnsafeRateAndInsufficientDay() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(f.engine, recent(f, 100, 351)), "1–350");

        Fixture early = fx("Asia/Taipei", 0);
        early.clock.wall += 5L * 60_000L;
        early.engine.disclose();
        rejects(StepTestConfirm.previewCatchUp(early.engine, recent(early, 1800, 350)), "最多可補 1750");
    }

    static void manualUsesRecentMinuteAndCaps() {
        Fixture f = fx("Asia/Taipei", 12);
        f.engine.disclose();
        StepView preview = StepTestConfirm.previewManual(f.engine, 350);
        check(!StepView.REJECTED.equals(preview.phase), "manual 350 accepted");
        check(preview.previewPlans.size() == 1, "manual one record");
        StepPlan plan = preview.previewPlans.get(0);
        check(plan.endMs == f.clock.wall && plan.endMs - plan.startMs == 60_000L, "manual recent minute");
        rejects(StepTestConfirm.previewManual(f.engine, 351), "1–350");
    }
}

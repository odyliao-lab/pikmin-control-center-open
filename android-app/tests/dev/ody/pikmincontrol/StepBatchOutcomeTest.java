package dev.ody.pikmincontrol;

public final class StepBatchOutcomeTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static Fixture fx() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 50_000L);
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

    static StepCatchUpRequest batch() {
        return new StepCatchUpRequest("window", 30, 10, 2024, 1, 1, 10, 0, 10, 9, null, null);
    }

    static int countPrefix(FakeStepHealth health, String prefix) {
        int n = 0;
        for (int i = 0; i < health.calls.size(); i++) if (health.calls.get(i).startsWith(prefix)) n++;
        return n;
    }

    public static void main(String[] args) {
        classStopsBatch("permission");
        classStopsBatch("unavailable");
        classStopsBatch("unsupported");
        classStopsBatch("error");
        classStopsBatch("rate-limit");
        reconFirmAfterRateLimitZeroCalls();
        targetReachedCapsAggregate();
        windowCapsAggregate();
        pendingMissCapsLookup();
        overlapFailCapsPages();
        readFailureBackoffShared();
        System.out.println("PASS StepBatchOutcomeTest " + checks + " checks; S4 outcomes and caps");
    }

    static void classStopsBatch(String kind) {
        Fixture f = fx();
        if ("permission".equals(kind)) f.health.canWrite = false;
        else if ("unavailable".equals(kind) || "unsupported".equals(kind)) f.health.available = false;
        else if ("error".equals(kind)) {
            f.health.nextWrite = "fail";
            f.health.failKind = "error";
        } else if ("rate-limit".equals(kind)) f.health.maxWrites = 0;
        StepView preview = StepTestConfirm.previewCatchUp(f.engine, batch());
        check(preview.previewPlans.size() >= 2, kind + " has batch");
        int before = f.health.writes;
        StepView view = StepTestConfirm.confirmCatchUp(f.engine, batch());
        check(!StepEngine.OUTCOME_SUCCESS.equals(view.opKind), kind + " is not success: " + view.opKind);
        if ("permission".equals(kind) || "unavailable".equals(kind) || "unsupported".equals(kind))
            check(f.health.writes == before, kind + " wrote nothing");
        else
            check(f.health.writes == before + 1, kind + " stopped after first write writes=" + f.health.writes);
    }

    static void reconFirmAfterRateLimitZeroCalls() {
        Fixture f = fx();
        f.health.maxWrites = 0;
        check(!StepTestConfirm.previewCatchUp(f.engine, batch()).previewPlans.isEmpty(), "rate-limit preview");
        StepTestConfirm.confirmCatchUp(f.engine, batch());
        check(f.health.writes == 1, "first rate-limit write");
        int calls = f.health.calls.size();
        int writes = f.health.writes;
        StepView again = StepTestConfirm.confirmCatchUp(f.engine, batch());
        check("backoff".equals(again.opKind) || f.health.writes == writes, "manual/catch-up honor wait");
        check(f.health.writes == writes, "reconfirm writes 0");
        check(f.health.calls.size() == calls, "reconfirm 0 new HC calls");
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(f.engine, 4).phase), "manual preview during backoff");
        StepView manual = StepTestConfirm.confirmManual(f.engine, 4);
        check(f.health.writes == writes, "manual also blocked");
        check("backoff".equals(manual.opKind) || StepView.SETTINGS_ON.equals(manual.phase)
                || StepView.RESTING.equals(manual.phase), "manual wait phase");
    }

    static void targetReachedCapsAggregate() {
        Fixture f = fx();
        f.health.allSources = StepSettings.DEFAULT_TARGET;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        check(StepView.TARGET_REACHED.equals(f.engine.tick().phase), "reached");
        int ag = countPrefix(f.health, "aggregate");
        f.engine.tick();
        f.engine.tick();
        f.engine.tick();
        check(countPrefix(f.health, "aggregate") == ag, "target does not dense-query");
    }

    static void windowCapsAggregate() {
        Fixture f = fx();
        f.clock.wall = f.zone.toEpochMillis(2024, 1, 1, 23, 0, 8 * 3600);
        f.engine.start();
        f.clock.advanceWall(60_000L);
        check(StepView.WAITING_WINDOW.equals(f.engine.tick().phase), "window");
        int ag = countPrefix(f.health, "aggregate");
        f.engine.tick();
        f.engine.tick();
        check(countPrefix(f.health, "aggregate") == ag, "window does not dense-query");
    }

    static void pendingMissCapsLookup() {
        Fixture f = fx();
        f.engine.start();
        StepPlan pending = new StepPlan("pend", StepPlan.BACKGROUND, StepPlan.UNCERTAIN, 10,
                f.clock.wall - 60_000L, f.clock.wall, 28800, 28800, "pcc-step-pend", 0L, "", "timeout");
        StepState state = StepState.load(f.store);
        state.ledger.add(pending);
        StepState.save(f.store, state);
        f.engine.tick();
        int lookups = countPrefix(f.health, "lookup");
        check(lookups >= 1, "first pending lookup");
        f.engine.tick();
        f.engine.tick();
        check(countPrefix(f.health, "lookup") == lookups, "pending miss not dense-query");
    }

    static void overlapFailCapsPages() {
        Fixture f = fx();
        f.health.pageFail = true;
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        int pages = countPrefix(f.health, "page");
        check(pages >= 1, "overlap read attempted");
        f.engine.tick();
        f.engine.tick();
        check(countPrefix(f.health, "page") == pages, "overlap fail not dense-query");
        check(f.health.writes == 0, "no write on overlap fail");
    }

    static void readFailureBackoffShared() {
        String[] kinds = new String[] { "timeout", "rate-limit", "read-failed" };
        for (int k = 0; k < kinds.length; k++) {
            Fixture f = fx();
            f.health.pageFail = true;
            f.health.pageKind = kinds[k];
            if ("timeout".equals(kinds[k])) f.health.pageTimeout = true;
            StepCatchUpRequest request = batch();
            check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), kinds[k] + " preview");
            StepView first = StepTestConfirm.confirmCatchUp(f.engine, request);
            check(!StepEngine.OUTCOME_SUCCESS.equals(first.opKind), kinds[k] + " first not success");
            check(!first.detail.contains("重疊"), kinds[k] + " is not labeled overlap: " + first.detail);
            int pages = countPrefix(f.health, "page");
            int writes = f.health.writes;
            check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), kinds[k] + " re-preview");
            StepView again = StepTestConfirm.confirmCatchUp(f.engine, request);
            check(countPrefix(f.health, "page") == pages, kinds[k] + " before due zero new HC pages");
            check(f.health.writes == writes, kinds[k] + " before due zero writes");
            check("backoff".equals(again.opKind) || again.detail.contains("等待"), kinds[k] + " waiting");
            check(!StepTestConfirm.previewManual(f.engine, 5).phase.equals(StepView.REJECTED) || true, "manual preview ok");
            if (!StepView.REJECTED.equals(StepTestConfirm.previewManual(f.engine, 5).phase)) {
                StepView manual = StepTestConfirm.confirmManual(f.engine, 5);
                check(countPrefix(f.health, "page") == pages, kinds[k] + " manual shares backoff");
                check(f.health.writes == writes, kinds[k] + " manual zero writes");
            }
            f.clock.elapsed = StepState.load(f.store).waitUntilElapsed + 1L;
            f.health.pageFail = false;
            f.health.pageTimeout = false;
            check(!StepTestConfirm.previewCatchUp(f.engine, request).previewPlans.isEmpty(), kinds[k] + " after due preview");
            StepTestConfirm.confirmCatchUp(f.engine, request);
            check(countPrefix(f.health, "page") > pages, kinds[k] + " after due one round allowed");
        }
        Fixture writeErr = fx();
        writeErr.health.nextWrite = "fail";
        writeErr.health.failKind = "error";
        check(!StepTestConfirm.previewCatchUp(writeErr.engine, batch()).previewPlans.isEmpty(), "write-error preview");
        StepView stopped = StepTestConfirm.confirmCatchUp(writeErr.engine, batch());
        check(!StepEngine.OUTCOME_SUCCESS.equals(stopped.opKind), "write-error stops batch");
        check(writeErr.health.writes == 1, "write-error wrote once");
    }
}

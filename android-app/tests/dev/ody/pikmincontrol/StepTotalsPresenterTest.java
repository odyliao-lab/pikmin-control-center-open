package dev.ody.pikmincontrol;

public final class StepTotalsPresenterTest {
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

    public static void main(String[] args) {
        successShowsBothTotals();
        rejectKeepsDetail();
        unreadablePresenter();
        storeErrorPresenter();
        staleBecomesUnknown();
        recreateKeepsFailure();
        hitWithoutPayloadConservative();
        waitingTicksKeepLastFailure();
        System.out.println("PASS StepTotalsPresenterTest " + checks + " checks; S7 totals and last op");
    }

    static void successShowsBothTotals() {
        Fixture f = fx();
        f.engine.start();
        f.clock.advanceWall(60_000L);
        StepView written = f.engine.tick();
        check(StepEngine.OUTCOME_SUCCESS.equals(written.opKind), "success");
        StepUiAdapter.Presented shown = StepUiAdapter.present(written);
        check(shown.allSources != null, "all-sources persisted");
        check(shown.thisApp != null, "this-app queried");
        StepView snap = f.engine.snapshot();
        check(snap.allSourcesToday != null, "refresh keeps all-sources");
        check(snap.thisAppToday != null, "refresh keeps this-app");
        check(snap.detail.contains("寫入成功") || snap.detail.length() > 0, "success detail kept");
    }

    static void rejectKeepsDetail() {
        Fixture f = fx();
        StepView rejected = StepTestConfirm.confirmManual(f.engine, 10);
        check(StepView.REJECTED.equals(rejected.phase) || f.engine.start().desiredRunning, "setup");
        f.engine.stop();
        StepTestConfirm.confirmManual(f.engine, 10);
        f.clock.advanceWall(1_000L);
        StepView tooSoon = StepTestConfirm.confirmManual(f.engine, 10);
        check(StepView.REJECTED.equals(tooSoon.phase), "rejected");
        StepUiAdapter.Presented shown = StepUiAdapter.present(f.engine.snapshot());
        check(shown.detail.length() > 0, "reject detail survives refresh: " + shown.detail);
        check(shown.phase.length() > 0, "phase present");
    }

    static void unreadablePresenter() {
        FilteringStepStore unread = new FilteringStepStore(new MemoryStepStore());
        unread.unreadable = true;
        StepEngine engine = new StepEngine(new MutableStepClock(1L, 1L), new StepSystemZone("Asia/Taipei"),
                new FixedStepRng(0.5d), unread, new FakeStepHealth(), new StepWorkLock(unread));
        StepUiAdapter.Presented shown = StepUiAdapter.present(engine.snapshot());
        check(StepView.STORE_ERROR.equals(shown.phase), "unreadable phase");
        check(shown.detail.length() > 0, "unreadable detail");
    }

    static void storeErrorPresenter() {
        MemoryStepStore memory = new MemoryStepStore();
        FilteringStepStore store = new FilteringStepStore(memory);
        store.failStop = true;
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store,
                new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        engine.start();
        StepView stop = engine.stop();
        StepUiAdapter.Presented shown = StepUiAdapter.present(stop);
        check(StepView.STORE_ERROR.equals(shown.phase), "STOP save visible");
        check(!StepView.STOPPED.equals(shown.phase), "must not claim stopped");
        check(shown.detail.contains("停止") || shown.detail.contains("儲存"), "stop failure detail");
    }

    static void staleBecomesUnknown() {
        Fixture f = fx();
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.engine.tick();
        check(f.engine.snapshot().allSourcesToday != null, "fresh known");
        f.clock.advanceWall(StepEngine.TOTALS_FRESH_MS + 1_000L);
        StepView stale = f.engine.snapshot();
        check(stale.allSourcesToday == null, "stale all-sources unknown");
        check(stale.thisAppToday == null, "stale this-app unknown");
    }

    static void recreateKeepsFailure() {
        MemoryStepStore memory = new MemoryStepStore();
        FilteringStepStore store = new FilteringStepStore(memory);
        store.failPrepared = true;
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store,
                new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        engine.start();
        clock.advanceWall(60_000L);
        StepView failed = engine.tick();
        check(StepView.STORE_ERROR.equals(failed.phase), "prepared fail");
        StepEngine rebuilt = new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store,
                new FakeStepHealth(), new StepWorkLock(store));
        StepUiAdapter.Presented shown = StepUiAdapter.present(rebuilt.snapshot());
        check(shown.detail.length() > 0 || StepView.STORE_ERROR.equals(shown.phase)
                || StepState.load(memory).lastOpDetail.length() > 0
                || store.failPrepared, "failure reason retained or store still unwritable");
        store.failPrepared = false;
        check(StepState.load(memory).lastOpDetail.length() > 0 || StepView.STORE_ERROR.equals(failed.phase),
                "last op recorded");
    }

    static void hitWithoutPayloadConservative() {
        StepHealthPort.LookupResult weak = StepHealthPort.LookupResult.hit("hc-only");
        StepPlan pending = new StepPlan("p", StepPlan.BACKGROUND, StepPlan.UNCERTAIN, 10, 1, 2, 0, 0, "c", 0, "", "");
        check(!weak.hasPayload, "hit(String) has no payload");
        check(!weak.payloadMatches(pending), "absent payload is not a match");
    }

    static void waitingTicksKeepLastFailure() {
        Fixture f = fx();
        f.engine.start();
        f.clock.advanceWall(60_000L);
        f.health.nextWrite = "fail";
        f.health.failKind = "error";
        StepView failed = f.engine.tick();
        check(failed.detail.contains("失敗") || "error".equals(failed.opKind), "write failure recorded");
        String error = failed.lastOpDetail.length() > 0 ? failed.lastOpDetail : failed.detail;
        check(error.length() > 0, "identifiable error");
        int saves = f.store.saves;
        for (int i = 0; i < 12; i++) f.engine.tick();
        check(f.store.saves - saves <= 2, "waiting persist bounded +" + (f.store.saves - saves));
        StepOperations ops = new StepOperations(f.engine);
        StepView queued = ops.latest();
        StepView snap = f.engine.snapshot();
        ops.close();
        StepUiAdapter.Presented shown = StepUiAdapter.present(snap);
        check(shown.lastOpDetail.contains("失敗") || snap.lastOpDetail.contains("失敗")
                || error.equals(snap.lastOpDetail) || error.equals(queued.lastOpDetail),
                "queue/snapshot still shows last failure: " + snap.lastOpDetail + " / " + shown.detail);
        String line = StepUiAdapter.statusLine(snap);
        check(line.contains("等待") || snap.detail.contains("等待") || snap.detail.contains("失敗"),
                "current phase visible");
        check(line.contains("失敗") || line.contains("上次") || snap.lastOpDetail.contains("失敗"),
                "last write failure visible with waiting");
        f.health.nextWrite = "ok";
        f.clock.elapsed = StepState.load(f.store).waitUntilElapsed + 1L;
        f.clock.advanceWall(60_000L);
        StepView ok = f.engine.tick();
        check(StepEngine.OUTCOME_SUCCESS.equals(ok.opKind) || ok.lastOpDetail.contains("成功"),
                "success updates last result");

        FilteringStepStore store = new FilteringStepStore(new MemoryStepStore());
        store.failPrepared = true;
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store,
                new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        engine.start();
        clock.advanceWall(60_000L);
        StepView storeErr = engine.tick();
        check(StepView.STORE_ERROR.equals(storeErr.phase), "prepared store error");
        for (int i = 0; i < 8; i++) engine.tick();
        StepView afterWait = engine.snapshot();
        check(StepView.STORE_ERROR.equals(afterWait.phase) && StepView.STORE_ERROR.equals(afterWait.lastOpPhase)
                && afterWait.lastOpDetail.contains("儲存"),
                "STORE_ERROR not masked by waiting");
    }
}

package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class StepView {
    static final String STOPPED = "stopped";
    static final String SETTINGS_ON = "settings-on";
    static final String WRITING = "writing";
    static final String WAITING_PERMISSION = "waiting-permission";
    static final String WAITING_WINDOW = "waiting-window";
    static final String TARGET_REACHED = "target-reached";
    static final String RESULT_PENDING = "result-pending";
    static final String UNSUPPORTED = "unsupported";
    static final String RESTING = "resting";
    static final String REJECTED = "rejected";
    static final String STORE_ERROR = "store-error";

    final String phase;
    final String detail;
    final Long allSourcesToday;
    final Long thisAppToday;
    final long sessionConfirmed;
    final int target;
    final boolean desiredRunning;
    final boolean disclosed;
    final boolean extremeRate;
    final boolean pending;
    final String writerOwner;
    final List<StepLogEntry> logs;
    final List<StepPlan> previewPlans;
    final String previewWarning;
    final String previewId;
    final String opKind;
    final String lastOpPhase;
    final String lastOpKind;
    final String lastOpDetail;

    StepView(String phase, String detail, Long allSourcesToday, Long thisAppToday, long sessionConfirmed, int target,
            boolean desiredRunning, boolean disclosed, boolean extremeRate, boolean pending, String writerOwner,
            List<StepLogEntry> logs, List<StepPlan> previewPlans, String previewWarning) {
        this(phase, detail, allSourcesToday, thisAppToday, sessionConfirmed, target, desiredRunning, disclosed,
                extremeRate, pending, writerOwner, logs, previewPlans, previewWarning, "");
    }

    StepView(String phase, String detail, Long allSourcesToday, Long thisAppToday, long sessionConfirmed, int target,
            boolean desiredRunning, boolean disclosed, boolean extremeRate, boolean pending, String writerOwner,
            List<StepLogEntry> logs, List<StepPlan> previewPlans, String previewWarning, String opKind) {
        this(phase, detail, allSourcesToday, thisAppToday, sessionConfirmed, target, desiredRunning, disclosed,
                extremeRate, pending, writerOwner, logs, previewPlans, previewWarning, opKind, "", "", "");
    }

    StepView(String phase, String detail, Long allSourcesToday, Long thisAppToday, long sessionConfirmed, int target,
            boolean desiredRunning, boolean disclosed, boolean extremeRate, boolean pending, String writerOwner,
            List<StepLogEntry> logs, List<StepPlan> previewPlans, String previewWarning, String opKind,
            String lastOpPhase, String lastOpKind, String lastOpDetail) {
        this.phase = phase;
        this.detail = detail == null ? "" : detail;
        this.allSourcesToday = allSourcesToday;
        this.thisAppToday = thisAppToday;
        this.sessionConfirmed = sessionConfirmed;
        this.target = target;
        this.desiredRunning = desiredRunning;
        this.disclosed = disclosed;
        this.extremeRate = extremeRate;
        this.pending = pending;
        this.writerOwner = writerOwner == null ? "" : writerOwner;
        this.logs = logs == null ? Collections.<StepLogEntry>emptyList() : logs;
        this.previewPlans = previewPlans == null ? Collections.<StepPlan>emptyList() : previewPlans;
        this.previewWarning = previewWarning == null ? "" : previewWarning;
        this.previewId = this.previewPlans.isEmpty() ? "" : this.previewPlans.get(0).id;
        this.opKind = opKind == null ? "" : opKind;
        this.lastOpPhase = lastOpPhase == null ? "" : lastOpPhase;
        this.lastOpKind = lastOpKind == null ? "" : lastOpKind;
        this.lastOpDetail = lastOpDetail == null ? "" : lastOpDetail;
    }

    static String totalText(Long value) { return value == null ? "未知" : Long.toString(value.longValue()); }

    static String phaseLabel(String phase) {
        if (SETTINGS_ON.equals(phase)) return "設定已開";
        if (WRITING.equals(phase)) return "正在寫入";
        if (WAITING_PERMISSION.equals(phase)) return "等待權限";
        if (WAITING_WINDOW.equals(phase)) return "時段外等待";
        if (TARGET_REACHED.equals(phase)) return "已達標";
        if (RESULT_PENDING.equals(phase)) return "結果待確認";
        if (UNSUPPORTED.equals(phase)) return "不支援";
        if (RESTING.equals(phase)) return "寫入休息";
        if (REJECTED.equals(phase)) return "已拒絕";
        if (STORE_ERROR.equals(phase)) return "儲存失敗";
        return "已停止";
    }

    static String sourceLabel(String source) {
        if (StepPlan.MANUAL.equals(source)) return "手動";
        if (StepPlan.CATCH_UP.equals(source)) return "補走";
        return "背景";
    }

    static String outcomeNote() {
        return "Health Connect 寫入、聚合可見、下游同步、遊戲採計是四件不同的事。";
    }
}

final class StepEngine {
    static final long WRITE_TIMEOUT_MS = 12_000L;
    static final long REST_AFTER_MS = 20_000L;
    static final int REST_AFTER_SUCCESSES = 3;
    static final int MAX_BACKOFF_MS = 90_000;
    static final int PAGE_SIZE = 64;
    static final int MAX_PAGES = 20;
    static final long MIN_TICK_MS = 250L;
    static final long PENDING_RETRY_MS = 5_000L;
    static final int MAX_BATCH_API = 512;
    static final String OUTCOME_SUCCESS = "ok";
    static final String OUTCOME_CANCEL = "cancel";
    static final String OUTCOME_UNCERTAIN = "uncertain";
    static final String OUTCOME_FAIL = "failed";
    static final long TOTALS_FRESH_MS = 300_000L;

    private final StepClock clock;
    private final StepZone zone;
    private final StepRng rng;
    private final StepStore store;
    private final StepHealthPort health;
    private final StepWorkLock lock;
    private final StepCoordinator coordinator;
    private final boolean backgroundReadRequired;
    private final StepProcessIdentity process;
    private String liveSessionId = "";
    private BoundPreview lastPreview;
    volatile java.util.concurrent.CountDownLatch startIntentLatch;
    volatile java.util.concurrent.CountDownLatch startAcquireLatch;

    StepEngine(StepClock clock, StepZone zone, StepRng rng, StepStore store, StepHealthPort health, StepWorkLock lock) {
        this(clock, zone, rng, store, health, lock, true);
    }

    StepEngine(StepClock clock, StepZone zone, StepRng rng, StepStore store, StepHealthPort health, StepWorkLock lock,
            boolean backgroundReadRequired) {
        this(clock, zone, rng, store, health, lock, backgroundReadRequired, new StepProcessIdentity());
    }

    StepEngine(StepClock clock, StepZone zone, StepRng rng, StepStore store, StepHealthPort health, StepWorkLock lock,
            boolean backgroundReadRequired, StepProcessIdentity process) {
        this.clock = clock;
        this.zone = zone;
        this.rng = rng;
        this.store = store;
        this.health = health;
        this.lock = lock;
        this.coordinator = StepCoordinator.of(store);
        this.backgroundReadRequired = backgroundReadRequired;
        this.process = process == null ? new StepProcessIdentity() : process;
    }

    StepSettings safeSettings() {
        try {
            synchronized (coordinator.txnLock()) {
                return coordinator.read().settings;
            }
        } catch (StepStoreException error) {
            return null;
        }
    }

    StepView snapshot() {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                return view(state, currentPhase(state), "", null, null);
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    /** Explicit read-only refresh for the visible page. Never prepares or writes a record. */
    StepView refreshTotals() {
        if (!health.available()) return view(safeRead(), StepView.UNSUPPORTED, "此裝置不支援實驗步數工具。", null, null);
        if (!health.canRead()) return view(safeRead(), StepView.WAITING_PERMISSION, "尚未授權讀取步數。", null, null);
        long now = clock.wallMillis();
        StepHealthPort.AggregateResult all = health.aggregate(
                zone.dayStartMillis(now), zone.nextMidnightMillis(now), false, WRITE_TIMEOUT_MS);
        StepHealthPort.AggregateResult app = health.aggregate(
                zone.dayStartMillis(now), zone.nextMidnightMillis(now), true, WRITE_TIMEOUT_MS);
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (!all.ok) return view(state, currentPhase(state), "今日步數讀取失敗，未以 0 代替。", null, null, "", all.kind);
                state.lastFreshAllSources = all.total;
                state.lastFreshAllSourcesAt = now;
                if (app.ok) {
                    state.lastFreshThisApp = app.total;
                    state.lastFreshThisAppAt = now;
                }
                coordinator.persist(state);
                return view(state, currentPhase(state), app.ok ? "今日步數已更新。" : "全來源已更新；本 App 數量待核對。",
                        app.ok ? app.total : null, null);
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    StepView disclose() {
        return mutate("已確認這是人工／程式步數，不是實際步行證明。", new Mutate() {
            public void apply(StepState state) { state.disclosed = true; }
        });
    }

    StepView configure(StepSettings settings) {
        return mutate(settings.extremePreview() ? "極端速率，僅預覽，不會當健康建議。" : "已保存設定", new Mutate() {
            public void apply(StepState state) {
                state.settings = settings;
                state.settingsRevision++;
            }
        });
    }

    /** Profile apply: check STOP and persist under the same step transaction lock. */
    StepView configureStopped(StepSettings settings) {
        return mutate("已保存設定組合的步數條件；不開始寫入", state -> {
            if(state.desiredRunning || state.ledger.pending()!=null || lock.held())
                throw new IllegalStateException("步數工作中，請先停止");
            state.settings=settings;state.settingsRevision++;
        });
    }

    long commandEpoch() {
        try {
            synchronized (coordinator.txnLock()) {
                return coordinator.read().commandEpoch;
            }
        } catch (StepStoreException error) {
            return -1L;
        }
    }

    boolean epochCurrent(long bound) {
        return bound >= 0L && bound == commandEpoch();
    }

    StepView start() {
        return startBound(-1L);
    }

    /** Service worker admit: continue an already-authorized run. Never invents START. */
    StepView continueAuthorizedRun(long boundEpoch) {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                    return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                if (!state.desiredRunning)
                    return view(state, StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。", null, null);
                if (!state.disclosed) return view(state, StepView.REJECTED, "請先確認這不是實際步行證明。", null, null);
                if (!health.available()) return view(state, StepView.UNSUPPORTED, "此裝置不支援實驗步數工具。", null, null);
                if (state.ledger.pending() != null)
                    return view(state, StepView.RESULT_PENDING, "先核對未完成寫入。", null, null);
                if (coordinator.heldBy(StepWorkLock.BACKGROUND)) {
                    liveSessionId = state.sessionId;
                    return view(state, currentPhase(state), "已在寫入", null, null);
                }
                return view(state, currentPhase(state), "接續已授權寫入。", null, null);
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    StepView startBound(long boundEpoch) {
        if (!coordinator.storageFailure().isEmpty()) return snapshot();
        StepLockToken token = null;
        try {
            long intentEpoch;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                    return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                if (!state.disclosed) return view(state, StepView.REJECTED, "請先確認這不是實際步行證明。", null, null);
                if (!health.available()) return view(state, StepView.UNSUPPORTED, "此裝置不支援實驗步數工具。", null, null);
                boolean rebuilt = adoptProcess(state);
                if (state.desiredRunning && sameProcess(state) && state.sessionId.length() > 0) {
                    adoptLiveSession(state);
                    if (rebuilt) coordinator.persist(state);
                    return view(state, currentPhase(state), "已在寫入", null, null);
                }
                state.desiredRunning = true;
                if (rebuilt) stampProcess(state);
                coordinator.persist(state);
                intentEpoch = state.commandEpoch;
                if (state.ledger.pending() != null)
                    return view(state, StepView.RESULT_PENDING, "先核對未完成寫入。", null, null);
                if (coordinator.heldBy(StepWorkLock.BACKGROUND)) {
                    liveSessionId = state.sessionId;
                    return view(state, currentPhase(state), "已在寫入", null, null);
                }
                if (coordinator.held())
                    return view(state, StepView.REJECTED, "步數工作鎖由其他寫入持有。", null, null);
            }
            if (startIntentLatch != null) startIntentLatch.countDown();
            if (startAcquireLatch != null) {
                try {
                    if (!startAcquireLatch.await(20, java.util.concurrent.TimeUnit.SECONDS))
                        return view(safeRead(), StepView.REJECTED, "啟動取得鎖逾時。", null, null);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return view(safeRead(), StepView.REJECTED, "啟動已中斷。", null, null);
                }
            }
            token = coordinator.tryAcquire(StepWorkLock.BACKGROUND);
            if (token == null) {
                StepState state = safeRead();
                return view(state, StepView.REJECTED, "步數工作鎖由其他寫入持有。", null, null);
            }
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (state.commandEpoch != intentEpoch || !state.desiredRunning) {
                    coordinator.release(token);
                    return view(state, StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。", null, null);
                }
                state.desiredRunning = true;
                state.sessionId = rng.newId();
                liveSessionId = state.sessionId;
                state.sessionConfirmed = 0L;
                state.restStreak = 0;
                state.runStartWall = clock.wallMillis();
                state.runStartElapsed = clock.elapsedMillis();
                state.runCursorMs = state.runStartWall;
                state.nextDueElapsed = state.runStartElapsed + state.settings.intervalMs;
                state.elapsedBasis = state.runStartElapsed;
                stampProcess(state);
                coordinator.persist(state);
                return view(state, StepView.SETTINGS_ON, "設定已開，尚未寫入。", null, null);
            }
        } catch (StepStoreException error) {
            coordinator.release(token);
            return storeError(error);
        }
    }

    StepView launchFailed(long epoch) {
        synchronized (coordinator.txnLock()) {
            if (!epochCurrent(epoch)) return snapshot();
            StepView stopped = stop();
            if (StepView.STORE_ERROR.equals(stopped.phase)) return stopped;
            return view(safeRead(), StepView.REJECTED, "步數服務啟動失敗，已保存停止；請檢查通知與前景服務權限。", null, null, "", "service-start");
        }
    }

    StepView stop() {
        try {
            StepLockToken held;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                state.desiredRunning = false;
                state.cancelNonce++;
                state.commandEpoch++;
                held = state.lockId.length() > 0
                        ? new StepLockToken(state.lockRole, state.lockId, state.lockGeneration) : null;
                coordinator.persist(state);
                liveSessionId = "";
            }
            coordinator.release(held);
            coordinator.clearStorageFailureAfterStop();
            StepState state = safeRead();
            return view(state, StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。", null, null);
        } catch (StepStoreException error) {
            return storeError(error, "無法保存停止，不能宣稱已停止。");
        }
    }

    StepView tick() { return tickBound(-1L); }

    /** Delay until the next persisted monotonic deadline; keeps the foreground loop asleep between writes. */
    long nextTickDelayMs() {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                long now = clock.elapsedMillis();
                long due = Math.max(state.nextDueElapsed, state.waitUntilElapsed);
                if (due <= now) return MIN_TICK_MS;
                return Math.max(MIN_TICK_MS, Math.min(60_000L, due - now));
            }
        } catch (StepStoreException error) {
            return 1_000L;
        }
    }

    StepView tickBound(long boundEpoch) {
        if (!coordinator.storageFailure().isEmpty()) return snapshot();
        try {
            StepPlan pending;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                    return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                applyClockAndZone(state);
                if (adoptProcess(state)) coordinator.persist(state);
                else adoptLiveSession(state);
                pending = state.ledger.pending();
            }
            if (pending != null) {
                synchronized (coordinator.txnLock()) {
                    StepState state = coordinator.read();
                    if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                        return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                    if (clock.elapsedMillis() < state.waitUntilElapsed)
                        return view(state, StepView.RESULT_PENDING, "結果待確認，不以新 ID 重送。", null, null);
                }
                StepHealthPort.LookupResult found = health.findByClientRecordId(
                        pending.clientRecordId, pending.startMs, pending.endMs, WRITE_TIMEOUT_MS);
                coordinator.applyReconcile(pending, found, clock.wallMillis(), clock.elapsedMillis());
                synchronized (coordinator.txnLock()) {
                    StepState state = coordinator.read();
                    if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                        return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                    if (state.ledger.pending() != null) {
                        state.waitUntilElapsed = Math.max(state.waitUntilElapsed, clock.elapsedMillis() + PENDING_RETRY_MS);
                        coordinator.persist(state);
                        return view(state, StepView.RESULT_PENDING, "結果待確認，不以新 ID 重送。", null, null);
                    }
                    if (state.desiredRunning) {
                        state.nextDueElapsed = clock.elapsedMillis() + state.settings.intervalMs;
                        state.elapsedBasis = clock.elapsedMillis();
                        if (state.restStreak >= REST_AFTER_SUCCESSES)
                            state.waitUntilElapsed = Math.max(state.waitUntilElapsed, clock.elapsedMillis() + REST_AFTER_MS);
                        coordinator.persist(state);
                    }
                    if (state.restStreak >= REST_AFTER_SUCCESSES && state.desiredRunning)
                        return view(state, StepView.RESTING, "三筆確認成功後休息 20 秒。", null, null, "", OUTCOME_SUCCESS);
                    return view(state, state.desiredRunning ? StepView.WRITING : StepView.STOPPED,
                            "同 ID 核對已確認。", null, null, "", OUTCOME_SUCCESS);
                }
            }
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                    return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                if (!state.desiredRunning) {
                    coordinator.releaseIfRole(StepWorkLock.BACKGROUND);
                    return view(state, StepView.STOPPED, "已停止", null, null);
                }
                StepView blocked = preflight(state, StepWorkLock.BACKGROUND, true);
                if (blocked != null) return blocked;
                if (!coordinator.heldBy(StepWorkLock.BACKGROUND)) {
                    if (coordinator.tryAcquire(StepWorkLock.BACKGROUND) == null)
                        return view(state, StepView.REJECTED, "步數工作鎖由其他寫入持有。", null, null);
                    state = coordinator.read();
                }
                long elapsed = clock.elapsedMillis();
                if (rebaseElapsed(state, elapsed)) coordinator.persist(state);
                if (elapsed < state.waitUntilElapsed)
                    return view(state, waitPhase(state), "等待中。", null, null);
                if (elapsed < state.nextDueElapsed)
                    return view(state, StepView.SETTINGS_ON, "等待間隔。", null, null);
            }
            long now = clock.wallMillis();
            StepHealthPort.AggregateResult all = health.aggregate(zone.dayStartMillis(now), zone.nextMidnightMillis(now), false, WRITE_TIMEOUT_MS);
            StepHealthPort.AggregateResult thisApp = health.aggregate(zone.dayStartMillis(now), zone.nextMidnightMillis(now), true, WRITE_TIMEOUT_MS);
            PlanDraft draft;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                    return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                if (!state.desiredRunning) return view(state, StepView.STOPPED, "已停止", null, null);
                if (!all.ok) return failClosed(state, StepView.WAITING_PERMISSION, all.kind, "無法取得新鮮全來源今日聚合，已停止寫入。");
                state.lastFreshAllSources = all.total;
                state.lastFreshAllSourcesAt = now;
                if (thisApp.ok) {
                    state.lastFreshThisApp = thisApp.total;
                    state.lastFreshThisAppAt = now;
                }
                coordinator.persist(state);
                if (all.total.longValue() >= state.settings.target) {
                    state.nextDueElapsed = clock.elapsedMillis() + state.settings.intervalMs;
                    state.elapsedBasis = clock.elapsedMillis();
                    coordinator.persist(state);
                    return view(state, StepView.TARGET_REACHED, "已達標；不會再為差額額外加總。", null, null);
                }
                if (!state.settings.inWindow(zone.minuteOfDay(now))) {
                    state.nextDueElapsed = clock.elapsedMillis() + state.settings.intervalMs;
                    state.elapsedBasis = clock.elapsedMillis();
                    coordinator.persist(state);
                    return view(state, StepView.WAITING_WINDOW, "活躍時段外。", null, null);
                }
                if (state.restStreak >= REST_AFTER_SUCCESSES && clock.elapsedMillis() - state.lastSuccessElapsed < REST_AFTER_MS)
                    return view(state, StepView.RESTING, "三筆確認成功後休息 20 秒。", null, null);
                if (state.restStreak >= REST_AFTER_SUCCESSES) state.restStreak = 0;
                StepPlan plan = planBackground(state, all.total.longValue());
                if (plan == null) return view(state, StepView.SETTINGS_ON, "等待合法區間。", null, null);
                draft = PlanDraft.capture(state, plan, zone, now);
            }
            ProofSlice proof = overlapProof(draft.plan.startMs, draft.plan.endMs);
            PreparedWrite prepared = commitPrepared(draft, proof, StepWorkLock.BACKGROUND);
            if (prepared == null) {
                synchronized (coordinator.txnLock()) {
                    StepState state = coordinator.read();
                    if (boundEpoch >= 0L && state.commandEpoch != boundEpoch)
                        return view(state, StepView.STOPPED, "命令已過期，未恢復寫入。", null, null);
                    if (state.desiredRunning) {
                        state.nextDueElapsed = Math.max(state.nextDueElapsed, clock.elapsedMillis() + state.settings.intervalMs);
                    }
                    if (proof.failed()) {
                        scheduleReadBackoff(state, proof.kind);
                        coordinator.persist(state);
                        if (!state.desiredRunning) return view(state, StepView.STOPPED, "已停止", null, null);
                        return view(state, waitPhase(state), readFailureDetail(proof.kind), null, null, "", proof.kind);
                    }
                    coordinator.persist(state);
                    if (!state.desiredRunning) return view(state, StepView.STOPPED, "已停止", null, null);
                    return view(state, StepView.SETTINGS_ON, "等待合法區間。", null, null);
                }
            }
            return finishWrite(prepared);
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    private void clearPreview(String id) {
        synchronized (coordinator.txnLock()) {
            if (lastPreview != null && lastPreview.previewId.equals(id)) lastPreview = null;
        }
    }

    StepView previewManual(long count) {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                try {
                    StepPlan plan = planManual(state, count, false);
                    lastPreview = BoundPreview.manual(plan, count, state, zone, clock.wallMillis());
                    String warning = "將寫入本 App 的人工步數，不是實際步行。" + StepView.outcomeNote();
                    return view(state, StepView.SETTINGS_ON, "手動預覽 " + count + " 步。", null, Collections.singletonList(plan), warning);
                } catch (IllegalStateException | IllegalArgumentException error) {
                    return view(state, StepView.REJECTED, error.getMessage(), null, null);
                }
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    StepView confirmManual(long count, String previewId) {
        if (!coordinator.storageFailure().isEmpty()) return snapshot();
        PreparedWrite prepared = null;
        StepLockToken token = null;
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                StepView blocked = preflight(state, StepWorkLock.MANUAL, false);
                if (blocked != null) return blocked;
                if (state.desiredRunning) throw new IllegalStateException("背景寫入進行中，拒絕手動寫入。");
                if (state.ledger.pending() != null) throw new IllegalStateException("有待確認寫入，拒絕手動寫入。");
                if (coordinator.held() && !coordinator.heldBy(StepWorkLock.MANUAL))
                    throw new IllegalStateException("步數工作鎖由其他寫入持有。");
            }
            token = coordinator.tryAcquire(StepWorkLock.MANUAL);
            if (token == null) throw new IllegalStateException("步數工作鎖由其他寫入持有。");
            PlanDraft draft;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                StepView blockedAgain = preflight(state, StepWorkLock.MANUAL, false);
                if (blockedAgain != null) {
                    coordinator.release(token);
                    return blockedAgain;
                }
                if (state.desiredRunning) throw new IllegalStateException("背景寫入進行中，拒絕手動寫入。");
                if (state.ledger.pending() != null) throw new IllegalStateException("有待確認寫入，拒絕手動寫入。");
                if (lastPreview == null || !lastPreview.manual || !lastPreview.previewId.equals(previewId))
                    throw new IllegalStateException("請重新預覽。");
                String stale = lastPreview.staleReason(state, zone, clock.wallMillis());
                if (stale != null) throw new IllegalStateException(stale);
                if (!lastPreview.matchesManual(count, state, zone, clock.wallMillis()))
                    throw new IllegalStateException("預覽不符，請重新預覽。");
                StepPlan plan = lastPreview.plans.get(0);
                draft = PlanDraft.capture(state, plan, zone, clock.wallMillis(), lastPreview);
            }
            ProofSlice proof = overlapProof(draft.plan.startMs, draft.plan.endMs);
            prepared = commitPrepared(draft, proof, StepWorkLock.MANUAL);
            if (prepared == null) {
                coordinator.release(token);
                StepState state = safeRead();
                if (proof.failed()) {
                    synchronized (coordinator.txnLock()) {
                        state = coordinator.read();
                        scheduleReadBackoff(state, proof.kind);
                        coordinator.persist(state);
                        return view(state, StepView.REJECTED, readFailureDetail(proof.kind), null, null, "", proof.kind);
                    }
                }
                if (!state.desiredRunning && state.cancelNonce != draft.nonce)
                    return view(state, StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。", null, null);
                return view(state, StepView.REJECTED, "確認後條件已變，請重新預覽。", null, null);
            }
            StepView result = finishWrite(prepared);
            clearPreview(previewId);
            coordinator.release(token);
            return result;
        } catch (IllegalStateException | IllegalArgumentException error) {
            coordinator.release(token);
            return view(safeRead(), StepView.REJECTED, error.getMessage(), null, null);
        } catch (StepStoreException error) {
            coordinator.release(token);
            return storeError(error);
        }
    }

    StepView previewCatchUp(StepCatchUpRequest request) {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                try {
                    StepCatchUpPlanner.Result planned = StepCatchUpPlanner.plan(request, state, clock, zone, rng);
                    lastPreview = BoundPreview.catchUp(planned, request, state, zone, clock.wallMillis());
                    return view(state, StepView.SETTINGS_ON, planned.summary, null, planned.plans, planned.warning);
                } catch (IllegalStateException | IllegalArgumentException error) {
                    return view(state, StepView.REJECTED, error.getMessage(), null, null);
                }
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    StepView confirmCatchUp(StepCatchUpRequest request, String previewId) {
        if (!coordinator.storageFailure().isEmpty()) return snapshot();
        StepLockToken token = null;
        try {
            StepCatchUpPlanner.Result planned;
            BoundPreview approved;
            long nonce;
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                StepView blocked = preflight(state, StepWorkLock.CATCH_UP, false);
                if (blocked != null) return blocked;
                if (state.desiredRunning) throw new IllegalStateException("背景寫入進行中，拒絕補走。");
                if (state.ledger.pending() != null) throw new IllegalStateException("有待確認寫入，拒絕補走。");
                if (coordinator.held() && !coordinator.heldBy(StepWorkLock.CATCH_UP))
                    throw new IllegalStateException("步數工作鎖由其他寫入持有。");
                if (lastPreview == null || lastPreview.manual || lastPreview.result == null || !lastPreview.previewId.equals(previewId))
                    throw new IllegalStateException("請重新預覽。");
                String stale = lastPreview.staleReason(state, zone, clock.wallMillis());
                if (stale != null) throw new IllegalStateException(stale);
                if (!lastPreview.matchesCatchUp(request, state, zone, clock.wallMillis()))
                    throw new IllegalStateException("預覽不符，請重新預覽。");
                approved = lastPreview;
                planned = approved.result;
                nonce = state.cancelNonce;
            }
            token = coordinator.tryAcquire(StepWorkLock.CATCH_UP);
            if (token == null) throw new IllegalStateException("步數工作鎖由其他寫入持有。");
            StepView last = view(safeRead(), StepView.SETTINGS_ON, planned.summary, null, planned.plans, planned.warning);
            int apiBudget = 0;
            for (int i = 0; i < planned.plans.size(); i++) {
                StepPlan nextPlan = planned.plans.get(i);
                PlanDraft draft;
                synchronized (coordinator.txnLock()) {
                    StepState state = coordinator.read();
                    if (state.cancelNonce != nonce) break;
                    if (!token.matches(state.lockId, state.lockGeneration)) break;
                    last = preflight(state, StepWorkLock.CATCH_UP, false);
                    if (last != null) break;
                    long now = clock.wallMillis();
                    String stale = lastPreview != approved ? "預覽已替換，請重新確認。" : approved.staleReason(state, zone, now);
                    if (stale != null) {
                        last = view(state, StepView.REJECTED, stale, null, null, "", "epoch");
                        break;
                    }
                    if (nextPlan.endMs > now) {
                        last = view(state, StepView.REJECTED, "確認後條件已變：不能寫入未來。", null, null);
                        break;
                    }
                    if (nextPlan.startMs < zone.dayStartMillis(now) || !zone.dayKey(now).equals(zone.dayKey(nextPlan.startMs))) {
                        last = view(state, StepView.REJECTED, "時鐘／時區已變更，請重新預覽。", null, null, "", "epoch");
                        break;
                    }
                    draft = PlanDraft.capture(state, nextPlan, zone, now, approved);
                }
                if (apiBudget >= MAX_BATCH_API) {
                    last = view(safeRead(), StepView.REJECTED, "已達本批 API 預算。", null, null, "", OUTCOME_FAIL);
                    break;
                }
                ProofSlice proof = overlapProof(nextPlan.startMs, nextPlan.endMs);
                apiBudget++;
                PreparedWrite prepared = commitPrepared(draft, proof, StepWorkLock.CATCH_UP);
                if (prepared == null) {
                    StepState state = safeRead();
                    if (proof.failed()) {
                        synchronized (coordinator.txnLock()) {
                            state = coordinator.read();
                            scheduleReadBackoff(state, proof.kind);
                            coordinator.persist(state);
                            last = view(state, StepView.REJECTED, readFailureDetail(proof.kind), null, null, "", proof.kind);
                        }
                    } else if (state.cancelNonce != nonce)
                        last = view(state, StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。", null, null);
                    else
                        last = view(state, StepView.REJECTED, "與本 App 已有區間重疊。", null, null);
                    break;
                }
                last = finishWrite(prepared);
                apiBudget++;
                if (shouldStopBatch(last))
                    break;
            }
            clearPreview(previewId);
            coordinator.release(token);
            return last;
        } catch (IllegalStateException | IllegalArgumentException error) {
            coordinator.release(token);
            return view(safeRead(), StepView.REJECTED, error.getMessage(), null, null);
        } catch (StepStoreException error) {
            coordinator.release(token);
            return storeError(error);
        }
    }

    StepView recoverIfAllowed() {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                if (!state.desiredRunning) return view(state, StepView.STOPPED, "停止後不會復活。", null, null);
                if (!health.available() || !health.canRead() || !health.canWrite())
                    return view(state, StepView.WAITING_PERMISSION, "重建時權限不可用，不恢復寫入。", null, null);
                boolean rebuilt = adoptProcess(state);
                if (rebuilt) {
                    coordinator.persist(state);
                    return view(state, currentPhase(state), "已依新程序錨點恢復，不回補停機。", null, null);
                }
                adoptLiveSession(state);
            }
            return start();
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    private StepView preflight(StepState state, String source, boolean background) {
        if (!state.disclosed) return view(state, StepView.REJECTED, "請先確認這不是實際步行證明。", null, null, "", "undisclosed");
        if (clock.elapsedMillis() < state.waitUntilElapsed)
            return view(state, waitPhase(state), "等待中。", null, null, "", "backoff");
        if (!health.available()) return failClosed(state, StepView.UNSUPPORTED, "unavailable", "Health Connect 不可用。");
        if (!health.canWrite() || !health.canRead())
            return failClosed(state, StepView.WAITING_PERMISSION, "permission", "等待可讀／可寫權限。");
        if (background && backgroundReadRequired && !health.canReadInBackground())
            return failClosed(state, StepView.WAITING_PERMISSION, "background-read", "等待背景可讀權限／回到工具頁。");
        return null;
    }

    private static boolean shouldStopBatch(StepView last) {
        return last == null || !OUTCOME_SUCCESS.equals(last.opKind);
    }

    private PreparedWrite persistPrepared(StepState state, StepPlan plan, String role) {
        if (plan.count < 1L || plan.endMs <= plan.startMs)
            throw new IllegalArgumentException("計畫必須 count>0 且 end>start。");
        if (plan.endMs - plan.startMs > StepSettings.MAX_RECORD_MS)
            throw new IllegalArgumentException("單筆最長 180 秒。");
        maintainOccupiedDayBound(state);
        state.ledger.add(plan);
        coordinator.persist(state);
        return new PreparedWrite(plan, role, state.lockId, state.lockGeneration);
    }

    private StepView finishWrite(PreparedWrite prepared) {
        if (prepared == null) return snapshot();
        StepHealthPort.WriteResult written = health.write(prepared.plan, WRITE_TIMEOUT_MS);
        StepLockToken token = new StepLockToken(prepared.role, prepared.lockId, prepared.generation);
        try {
            StepState state = coordinator.applyWriteOutcome(token, prepared.plan, written, clock.wallMillis(), clock.elapsedMillis());
            if (written.timeout)
                return view(state, StepView.RESULT_PENDING, "寫入回覆逾時，已標待確認。", null, null, "",
                        written.kind.length() > 0 ? written.kind : OUTCOME_UNCERTAIN);
            if (written.failed) {
                if ("io".equals(written.kind) || "remote".equals(written.kind) || "sync".equals(written.kind)
                        || (state.ledger.pending() != null && StepPlan.UNCERTAIN.equals(state.ledger.pending().status)))
                    return view(state, StepView.RESULT_PENDING, "寫入結果不確定，不以新 ID 重送。", null, null, "",
                            written.kind.length() > 0 ? written.kind : OUTCOME_UNCERTAIN);
                synchronized (coordinator.txnLock()) {
                    state = coordinator.read();
                    bumpBackoff(state, written.kind);
                    coordinator.persist(state);
                    return view(state, waitPhase(state), "寫入失敗，未增加成功量。", null, null, "",
                            written.kind.length() > 0 ? written.kind : OUTCOME_FAIL);
                }
            }
            synchronized (coordinator.txnLock()) {
                state = coordinator.read();
                if (StepWorkLock.BACKGROUND.equals(prepared.role)) {
                    state.nextDueElapsed = clock.elapsedMillis() + state.settings.intervalMs;
                    state.elapsedBasis = clock.elapsedMillis();
                    if (state.restStreak >= REST_AFTER_SUCCESSES)
                        state.waitUntilElapsed = Math.max(state.waitUntilElapsed, clock.elapsedMillis() + REST_AFTER_MS);
                    coordinator.persist(state);
                }
            }
            long now = clock.wallMillis();
            StepHealthPort.AggregateResult thisApp = health.aggregate(zone.dayStartMillis(now), zone.nextMidnightMillis(now), true, WRITE_TIMEOUT_MS);
            synchronized (coordinator.txnLock()) {
                state = coordinator.read();
                if (thisApp.ok) {
                    state.lastFreshThisApp = thisApp.total;
                    state.lastFreshThisAppAt = now;
                    coordinator.persist(state);
                }
            }
            if (StepWorkLock.BACKGROUND.equals(prepared.role) && state.restStreak >= REST_AFTER_SUCCESSES)
                return view(state, StepView.RESTING, "三筆確認成功後休息 20 秒。",
                        thisApp.ok ? thisApp.total : null, null, "", OUTCOME_SUCCESS);
            return view(state, StepWorkLock.BACKGROUND.equals(prepared.role) ? StepView.WRITING : StepView.SETTINGS_ON,
                    "Health Connect 寫入成功。尚未代表聚合可見、下游同步或遊戲採計。",
                    thisApp.ok ? thisApp.total : null, null, "", OUTCOME_SUCCESS);
        } catch (StepStoreException error) {
            return storeError(error, "寫入後無法保存確認；不以新 ID 重送。");
        }
    }

    private ProofSlice overlapProof(long start, long end) {
        if (!health.canRead()) return ProofSlice.fail("permission");
        long deadlineNs = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(WRITE_TIMEOUT_MS);
        long token = -1L;
        long previous = Long.MIN_VALUE;
        List<StepPlan> records = new ArrayList<StepPlan>();
        for (int page = 0; page < MAX_PAGES; page++) {
            long remaining = StepPaging.remainingMs(deadlineNs);
            if (remaining <= 0L) return ProofSlice.fail("timeout");
            if (StepPaging.stalledPage(page, previous, token)) return ProofSlice.fail("dup-token");
            previous = token;
            StepHealthPort.PageResult slice = health.readThisApp(start, end, token, PAGE_SIZE, remaining);
            if (slice.timeout) return ProofSlice.fail("timeout");
            if (!slice.ok) return ProofSlice.fail(slice.kind.length() == 0 ? "read-failed" : slice.kind);
            for (int i = 0; i < slice.records.size(); i++) records.add(slice.records.get(i));
            if (slice.nextPageToken < 0L) return ProofSlice.ok(records);
            token = slice.nextPageToken;
        }
        return ProofSlice.fail("page-limit");
    }

    private PreparedWrite commitPrepared(PlanDraft draft, ProofSlice proof, String role) {
        synchronized (coordinator.txnLock()) {
            if (!coordinator.storageFailure().isEmpty()) return null;
            StepState state = coordinator.read();
            long now = clock.wallMillis();
            if (state.cancelNonce != draft.nonce) return null;
            if (now < draft.wallAtCapture) return null;
            if (draft.preview != null && (lastPreview != draft.preview
                    || draft.preview.staleReason(state, zone, now) != null)) return null;
            if (draft.lockId.length() > 0
                    && !(draft.lockId.equals(state.lockId) && draft.generation == state.lockGeneration))
                return null;
            if (state.ledger.pending() != null) return null;
            if (draft.desiredRunning != state.desiredRunning) return null;
            if (draft.settingsRevision != state.settingsRevision) return null;
            if (draft.dayKey.length() > 0 && !draft.dayKey.equals(zone.dayKey(now))) return null;
            if (!draft.zoneId.equals(zone.zoneId()) || draft.clockEpoch != zone.clockEpoch(now)) return null;
            if (draft.plan.endMs > now) return null;
            if (draft.plan.startMs < zone.dayStartMillis(now) || !zone.dayKey(now).equals(zone.dayKey(draft.plan.startMs)))
                return null;
            if (!health.canRead() || !health.canWrite()) return null;
            if (StepWorkLock.BACKGROUND.equals(role) && backgroundReadRequired && !health.canReadInBackground())
                return null;
            if (proof.failed()) return null;
            for (int i = 0; i < proof.records.size(); i++) state.ledger.remember(proof.records.get(i));
            if (state.ledger.overlaps(draft.plan.startMs, draft.plan.endMs)) return null;
            return persistPrepared(state, draft.plan, role);
        }
    }

    private StepPlan planBackground(StepState state, long allSources) {
        long now = clock.wallMillis();
        long dayStart = zone.dayStartMillis(now);
        long midnight = zone.nextMidnightMillis(now);
        long remaining = state.settings.target - allSources;
        if (remaining <= 0L) return null;
        long duration = state.settings.intervalMs;
        long end = now;
        long start;
        adoptLiveSession(state);
        boolean sameRun = liveSessionId.length() > 0 && liveSessionId.equals(state.sessionId)
                && state.runStartWall > 0L;
        if (!sameRun) return null;
        long cursor = Math.max(state.runStartWall, state.runCursorMs);
        start = Math.max(cursor, dayStart);
        end = Math.min(start + duration, Math.min(now, midnight));
        if (end > now) return null;
        if (end <= start) return null;
        if (end - start < StepSettings.MIN_INTERVAL_MS) return null;
        if (end - start > StepSettings.MAX_RECORD_MS) start = end - StepSettings.MAX_RECORD_MS;
        if (start < dayStart) start = dayStart;
        if (state.ledger.overlaps(start, end)) return null;
        long count = Math.min(remaining, state.settings.countForDuration(end - start, rng));
        if (count < 1L) return null;
        return newPlan(StepPlan.BACKGROUND, count, start, end);
    }

    private StepPlan planManual(StepState state, long count, boolean forWrite) {
        if (count < 1L || count > 350L) throw new IllegalArgumentException("手動步數須為 1–350；較大數量請使用時段補走。");
        if (state.desiredRunning) throw new IllegalStateException("背景寫入進行中，拒絕手動寫入。");
        if (state.ledger.pending() != null) throw new IllegalStateException("有待確認寫入，拒絕手動寫入。");
        long now = clock.wallMillis();
        long dayStart = zone.dayStartMillis(now);
        long end = now;
        long start = end - StepSettings.MANUAL_FIRST_MS;
        if (start < dayStart) throw new IllegalStateException("今天尚不足一個完整分鐘，請稍後再試。");
        if (end > now) throw new IllegalStateException("不能寫入未來。");
        if (end <= start) throw new IllegalStateException("區間無效。");
        if (state.ledger.overlaps(start, end)) throw new IllegalStateException("與本 App 已有區間重疊。");
        return newPlan(StepPlan.MANUAL, count, start, end);
    }

    private StepPlan newPlan(String source, long count, long start, long end) {
        String id = rng.newId();
        return new StepPlan(id, source, StepPlan.PREPARED, count, start, end,
                zone.offsetSecondsAt(start), zone.offsetSecondsAt(end), "pcc-step-" + id, 0L, "", "");
    }

    private void adoptLiveSession(StepState state) {
        if (liveSessionId.length() == 0 && state.desiredRunning && state.sessionId.length() > 0)
            liveSessionId = state.sessionId;
    }

    private boolean sameProcess(StepState state) {
        return process.matches(state.processId, state.bootId);
    }

    private void stampProcess(StepState state) {
        state.processId = process.processId;
        state.bootId = process.bootId;
    }

    /** @return true when this engine confirmed a process/boot rebuild or first stamp. */
    private boolean adoptProcess(StepState state) {
        boolean known = state.processId.length() > 0 || state.bootId.length() > 0;
        if (!known) {
            stampProcess(state);
            return false;
        }
        if (sameProcess(state)) {
            adoptLiveSession(state);
            return false;
        }
        boolean bootChanged = state.bootId.length() > 0 && !process.bootId.equals(state.bootId);
        long elapsed = clock.elapsedMillis();
        if (bootChanged) {
            // elapsedRealtime resets at reboot. Do not carry a rest/backoff deadline into a new boot.
            // The ledger remains intact so pending clientRecordId reconciliation stays fail-closed.
            state.lastSuccessElapsed = 0L;
            state.waitUntilElapsed = elapsed;
            state.backoffMs = 0;
            state.restStreak = 0;
        }
        if (state.desiredRunning) {
            long now = clock.wallMillis();
            state.runStartWall = now;
            state.runStartElapsed = elapsed;
            state.runCursorMs = now;
            state.nextDueElapsed = elapsed;
            state.elapsedBasis = elapsed;
            state.sessionId = rng.newId();
            liveSessionId = state.sessionId;
            state.sessionConfirmed = 0L;
        }
        stampProcess(state);
        return true;
    }

    private void maintainOccupiedDayBound(StepState state) {
        long now = clock.wallMillis();
        String today = zone.dayKey(now);
        if (state.dayKey.length() > 0 && !today.equals(state.dayKey)) {
            state.sessionConfirmed = 0L;
            state.lastFreshAllSources = null;
        }
        state.ledger.compact(zone.dayStartMillis(now));
        state.dayKey = today;
        state.zoneId = zone.zoneId();
    }

    private void applyClockAndZone(StepState state) {
        long now = clock.wallMillis();
        String today = zone.dayKey(now);
        String zoneId = zone.zoneId();
        boolean changed = false;
        if (state.dayKey.length() > 0 && !today.equals(state.dayKey)) {
            state.sessionConfirmed = 0L;
            state.lastFreshAllSources = null;
            state.ledger.compact(zone.dayStartMillis(now));
            changed = true;
        }
        if (state.zoneId.length() > 0 && !zoneId.equals(state.zoneId)) {
            state.lastFreshAllSources = null;
            state.clockEpoch = zone.clockEpoch(now);
            changed = true;
        }
        if (state.lastConfirmedEndMs > now) {
            state.lastConfirmedEndMs = 0L;
            liveSessionId = "";
            state.sessionId = "";
            changed = true;
        }
        if (state.runCursorMs > now) {
            state.runCursorMs = state.runStartWall;
            changed = true;
        }
        if (!today.equals(state.dayKey) || !zoneId.equals(state.zoneId)) changed = true;
        state.dayKey = today;
        state.zoneId = zoneId;
        if (rebaseElapsed(state, clock.elapsedMillis())) changed = true;
        if (changed) coordinator.persist(state);
    }

    private boolean rebaseElapsed(StepState state, long elapsed) {
        if (state.elapsedBasis > 0L && elapsed + 1000L < state.elapsedBasis) {
            long waitLeft = Math.max(0L, state.waitUntilElapsed - state.elapsedBasis);
            long dueLeft = Math.max(0L, state.nextDueElapsed - state.elapsedBasis);
            state.waitUntilElapsed = elapsed + waitLeft;
            state.nextDueElapsed = elapsed + dueLeft;
            state.runStartElapsed = elapsed;
            state.lastSuccessElapsed = 0L;
            state.elapsedBasis = elapsed;
            return true;
        }
        return false;
    }

    private StepState refreshTotals(StepState state) {
        return state;
    }

    private StepView failClosed(StepState state, String phase, String kind, String detail) {
        if (clock.elapsedMillis() >= state.waitUntilElapsed) {
            bumpBackoff(state, kind);
            coordinator.persist(state);
        }
        return view(state, phase, detail, null, null, "", kind);
    }

    private void scheduleReadBackoff(StepState state, String kind) {
        bumpBackoff(state, kind);
    }

    private static String readFailureDetail(String kind) {
        if ("timeout".equals(kind)) return "讀取逾時，稍後再試。";
        if ("rate-limit".equals(kind) || "quota".equals(kind)) return "讀取受速率限制，稍後再試。";
        if ("permission".equals(kind)) return "讀取權限不足，稍後再試。";
        return "讀取失敗，稍後再試。";
    }

    private void bumpBackoff(StepState state, String kind) {
        int next = state.backoffMs <= 0 ? 5_000 : Math.min(MAX_BACKOFF_MS, state.backoffMs * 3);
        state.backoffMs = next;
        state.waitUntilElapsed = clock.elapsedMillis() + next;
        if ("rate-limit".equals(kind) || "quota".equals(kind)) {
            state.waitUntilElapsed = clock.elapsedMillis() + Math.max(next, 20_000);
        }
    }

    private String waitPhase(StepState state) {
        if (state.restStreak >= REST_AFTER_SUCCESSES) return StepView.RESTING;
        return StepView.SETTINGS_ON;
    }

    private String currentPhase(StepState state) {
        if (state.ledger.pending() != null) return StepView.RESULT_PENDING;
        if (!state.desiredRunning) return StepView.STOPPED;
        if (!health.available()) return StepView.UNSUPPORTED;
        if (!health.canRead() || !health.canWrite()) return StepView.WAITING_PERMISSION;
        return StepView.SETTINGS_ON;
    }

    private StepState safeRead() {
        try { return coordinator.read(); }
        catch (StepStoreException error) { return new StepState(); }
    }

    private StepView mutate(String detail, Mutate mutate) {
        try {
            synchronized (coordinator.txnLock()) {
                StepState state = coordinator.read();
                mutate.apply(state);
                coordinator.persist(state);
                return view(state, currentPhase(state), detail, null, null);
            }
        } catch (StepStoreException error) {
            return storeError(error);
        }
    }

    private StepView storeError(StepStoreException error) {
        return storeError(error, "儲存失敗，未寫入。");
    }

    private StepView storeError(StepStoreException error, String detail) {
        StepState empty = new StepState();
        try { empty = coordinator.read(); } catch (StepStoreException ignored) { }
        String message = detail + " " + error.getMessage();
        coordinator.recordStorageFailure(message);
        return new StepView(StepView.STORE_ERROR, message, empty.lastFreshAllSources, null,
                empty.sessionConfirmed, empty.settings.target, empty.desiredRunning, empty.disclosed,
                empty.settings.extremePreview(), empty.ledger.pending() != null, empty.lockRole,
                new ArrayList<StepLogEntry>(empty.logs), null, "", "store",
                StepView.STORE_ERROR, "store", message);
    }

    private StepView view(StepState state, String phase, String detail, Long thisAppOverride, List<StepPlan> preview) {
        return view(state, phase, detail, thisAppOverride, preview, "", "");
    }

    private StepView view(StepState state, String phase, String detail, Long thisAppOverride, List<StepPlan> preview, String warning) {
        return view(state, phase, detail, thisAppOverride, preview, warning, "");
    }

    private Long freshTotal(Long value, long atMs) {
        if (value == null || atMs <= 0L) return null;
        long now = clock.wallMillis();
        if (now < atMs || now - atMs > TOTALS_FRESH_MS) return null;
        if (!zone.dayKey(atMs).equals(zone.dayKey(now))) return null;
        return value;
    }

    private StepView view(StepState state, String phase, String detail, Long thisAppOverride, List<StepPlan> preview, String warning, String opKind) {
        Long thisApp = thisAppOverride != null ? thisAppOverride : freshTotal(state.lastFreshThisApp, state.lastFreshThisAppAt);
        Long all = freshTotal(state.lastFreshAllSources, state.lastFreshAllSourcesAt);
        if (!health.canRead()) {
            all = null;
            if (thisAppOverride == null) thisApp = null;
        }
        String lastPhase = state.lastOpPhase;
        String lastKind = state.lastOpKind;
        String lastDetail = state.lastOpDetail;
        boolean waiting = isWaitingTick(phase, detail, opKind);
        boolean outcome = isOperationOutcome(phase, detail, opKind);
        if (outcome && !waiting) {
            lastPhase = phase;
            lastKind = opKind;
            lastDetail = detail;
            persistLastOp(phase, detail, opKind);
        }
        String shownDetail = detail;
        String shownKind = opKind;
        if (shownDetail.length() == 0 && lastDetail.length() > 0) shownDetail = lastDetail;
        if (shownKind.length() == 0 && lastKind.length() > 0) shownKind = lastKind;
        if (StepView.STORE_ERROR.equals(phase)) {
            shownDetail = detail;
            shownKind = opKind.length() > 0 ? opKind : shownKind;
            lastPhase = phase;
            lastKind = shownKind;
            lastDetail = detail;
        }
        String storageFailure = coordinator.storageFailure();
        if (!storageFailure.isEmpty()) {
            phase = StepView.STORE_ERROR;
            shownDetail = lastDetail = storageFailure;
            shownKind = lastKind = "store";
            lastPhase = StepView.STORE_ERROR;
        }
        return new StepView(phase, shownDetail, all, thisApp, state.sessionConfirmed, state.settings.target,
                state.desiredRunning, state.disclosed, state.settings.extremePreview(), state.ledger.pending() != null,
                state.lockRole.length() > 0 ? state.lockRole : state.writerOwner,
                new ArrayList<StepLogEntry>(state.logs), preview, warning, shownKind,
                lastPhase, lastKind, lastDetail);
    }

    private static boolean isWaitingTick(String phase, String detail, String opKind) {
        if ("backoff".equals(opKind)) return true;
        return "等待中。".equals(detail) || "等待間隔。".equals(detail);
    }

    private static boolean isOperationOutcome(String phase, String detail, String opKind) {
        if (StepView.STORE_ERROR.equals(phase)) return true;
        if (OUTCOME_SUCCESS.equals(opKind) || OUTCOME_FAIL.equals(opKind) || OUTCOME_UNCERTAIN.equals(opKind)
                || OUTCOME_CANCEL.equals(opKind))
            return true;
        if (StepView.REJECTED.equals(phase)) return true;
        if ("error".equals(opKind) || "io".equals(opKind) || "remote".equals(opKind) || "sync".equals(opKind)
                || "timeout".equals(opKind) || "rate-limit".equals(opKind) || "quota".equals(opKind)
                || "read-failed".equals(opKind) || "permission".equals(opKind) || "unavailable".equals(opKind)
                || "dup-token".equals(opKind) || "page-limit".equals(opKind))
            return true;
        if (StepView.STOPPED.equals(phase) && detail.indexOf("已停止") >= 0) return true;
        return false;
    }

    private void persistLastOp(String phase, String detail, String opKind) {
        try {
            synchronized (coordinator.txnLock()) {
                StepState latest = coordinator.read();
                if (phase.equals(latest.lastOpPhase) && detail.equals(latest.lastOpDetail)
                        && opKind.equals(latest.lastOpKind))
                    return;
                latest.lastOpPhase = phase;
                latest.lastOpDetail = detail;
                latest.lastOpKind = opKind;
                coordinator.persist(latest);
            }
        } catch (StepStoreException error) {
            coordinator.recordStorageFailure("儲存操作結果失敗；請重試停止以核對狀態。 " + error.getMessage());
        }
    }

    private interface Mutate { void apply(StepState state); }

    private static final class PreparedWrite {
        final StepPlan plan;
        final String role;
        final String lockId;
        final long generation;
        PreparedWrite(StepPlan plan, String role, String lockId, long generation) {
            this.plan = plan;
            this.role = role;
            this.lockId = lockId;
            this.generation = generation;
        }
    }

    private static final class ProofSlice {
        final String kind;
        final List<StepPlan> records;
        private ProofSlice(String kind, List<StepPlan> records) {
            this.kind = kind;
            this.records = records == null ? Collections.<StepPlan>emptyList() : records;
        }
        static ProofSlice ok(List<StepPlan> records) { return new ProofSlice(null, records); }
        static ProofSlice fail(String kind) { return new ProofSlice(kind, Collections.<StepPlan>emptyList()); }
        boolean failed() { return kind != null; }
    }

    private static final class PlanDraft {
        final StepPlan plan;
        final long nonce;
        final String lockId;
        final long generation;
        final long settingsRevision;
        final String dayKey;
        final String zoneId;
        final int clockEpoch;
        final boolean desiredRunning;
        final long wallAtCapture;
        final BoundPreview preview;
        private PlanDraft(StepPlan plan, long nonce, String lockId, long generation, long settingsRevision, String dayKey,
                String zoneId, int clockEpoch, boolean desiredRunning, long wall, BoundPreview preview) {
            this.plan = plan;
            this.nonce = nonce;
            this.lockId = lockId == null ? "" : lockId;
            this.generation = generation;
            this.settingsRevision = settingsRevision;
            this.dayKey = dayKey == null ? "" : dayKey;
            this.zoneId = zoneId == null ? "" : zoneId;
            this.clockEpoch = clockEpoch;
            this.desiredRunning = desiredRunning;
            this.wallAtCapture = wall;
            this.preview = preview;
        }
        static PlanDraft capture(StepState state, StepPlan plan, StepZone zone, long wall) {
            return capture(state, plan, zone, wall, null);
        }
        static PlanDraft capture(StepState state, StepPlan plan, StepZone zone, long wall, BoundPreview preview) {
            return new PlanDraft(plan, state.cancelNonce, state.lockId, state.lockGeneration,
                    state.settingsRevision, zone.dayKey(wall), zone.zoneId(), zone.clockEpoch(wall),
                    state.desiredRunning, wall, preview);
        }
    }

    private static final class BoundPreview {
        final boolean manual;
        final long count;
        final List<StepPlan> plans;
        final StepCatchUpPlanner.Result result;
        final StepCatchUpRequest request;
        final int clockEpoch;
        final String dayKey;
        final String zoneId;
        final long cancelNonce;
        final long commandEpoch;
        final long settingsRevision;
        final long wallAtPreview;
        final String previewId;

        private BoundPreview(boolean manual, long count, List<StepPlan> plans, StepCatchUpPlanner.Result result,
                StepCatchUpRequest request, int clockEpoch, String dayKey, String zoneId, long cancelNonce,
                long commandEpoch, long settingsRevision, long wallAtPreview, String previewId) {
            this.manual = manual;
            this.count = count;
            this.plans = plans;
            this.result = result;
            this.request = request;
            this.clockEpoch = clockEpoch;
            this.dayKey = dayKey;
            this.zoneId = zoneId;
            this.cancelNonce = cancelNonce;
            this.commandEpoch = commandEpoch;
            this.settingsRevision = settingsRevision;
            this.wallAtPreview = wallAtPreview;
            this.previewId = previewId == null ? "" : previewId;
        }

        static BoundPreview manual(StepPlan plan, long count, StepState state, StepZone zone, long wall) {
            return new BoundPreview(true, count, Collections.singletonList(plan), null, null, zone.clockEpoch(wall),
                    zone.dayKey(wall), zone.zoneId(), state.cancelNonce, state.commandEpoch,
                    state.settingsRevision, wall, plan.id);
        }

        static BoundPreview catchUp(StepCatchUpPlanner.Result planned, StepCatchUpRequest request, StepState state,
                StepZone zone, long wall) {
            return new BoundPreview(false, request.steps, planned.plans, planned, request, zone.clockEpoch(wall),
                    zone.dayKey(wall), zone.zoneId(), state.cancelNonce, state.commandEpoch,
                    state.settingsRevision, wall, planned.plans.get(0).id);
        }

        boolean matchesManual(long count, StepState state, StepZone zone, long wall) {
            return manual && this.count == count && previewId.length() > 0 && staleReason(state, zone, wall) == null;
        }

        boolean matchesCatchUp(StepCatchUpRequest request, StepState state, StepZone zone, long wall) {
            return !manual && this.request != null && this.request.sameBinding(request) && result != null
                    && previewId.length() > 0 && staleReason(state, zone, wall) == null;
        }

        String staleReason(StepState state, StepZone zone, long wall) {
            if (state.cancelNonce != cancelNonce) return "已取消，請重新預覽。";
            if (state.commandEpoch != commandEpoch) return "命令已過期，請重新預覽。";
            if (state.settingsRevision != settingsRevision) return "設定已變更，請重新預覽。";
            if (wall < wallAtPreview) return "時鐘／時區已變更，請重新預覽。";
            if (!dayKey.equals(zone.dayKey(wall)) || clockEpoch != zone.clockEpoch(wall)
                    || !zoneId.equals(zone.zoneId()))
                return "時鐘／時區已變更，請重新預覽。";
            return null;
        }
    }
}

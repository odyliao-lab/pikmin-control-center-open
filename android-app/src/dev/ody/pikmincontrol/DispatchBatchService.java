package dev.ody.pikmincontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Foreground, one-at-a-time dispatcher. It never sends GPS JoyStick STOP. */
public final class DispatchBatchService extends Service {
    private static final String CHANNEL = "dispatch_batch";
    private static final String START = "dev.ody.pikmincontrol.START_BATCH";
    private static final String STOP = "dev.ody.pikmincontrol.STOP_BATCH";
    private static final int NOTIFICATION_ID = 152;
    // Travel time scales with distance at roughly 271 ms per metre with no
    // measurable fixed cost, so arriving within a few metres makes the round
    // trip effectively instant: 0.1 m measured 37 ms on 2026-08-29, against
    // 140 s for a comparable fruit at 533 m.
    private static final double ARRIVAL_TARGET_METRES = 4.0;
    // Slack for ordinary jitter when re-confirming a proof already established.
    private static final double ARRIVAL_HOLD_METRES = 8.0;
    // Both location sources must agree before dispatching.  The provider is
    // what the game's duration maths follows, while the native module gates on
    // its own reading, so a disagreement means the dispatch is either refused
    // or starts from the wrong place.  The native figure is only meaningful
    // while service.sh keeps nectar_system_gps.tsv fresh -- a stale file once
    // pinned it 90 m away for hours -- so never accept an old row.
    private static final long GAME_SIDE_MAX_AGE_MS = 12_000L;
    // The native scanner refreshes about every five seconds, so arrival needs a
    // few cycles; a dispatch that has not fired within a minute will not.
    private static final long ARRIVAL_WAIT_MS = 60_000L;
    // GPS JoyStick reaches the provider before Pikmin's visible cursor and
    // internal travel-time model settle.  The user consistently observes a
    // 5-7 s lag; a failed gift still reported 598,765 ms at a measured 0.1 m.
    // Wait beyond that window, then require two distinct native list scans so
    // one still-fresh TSV row cannot satisfy both arrival samples.
    private static final long GAME_SYNC_SETTLE_MS = 10_000L;
    // The native module retries selection on every list projection.  Do not
    // treat its first gift rejection as final while the game's location model
    // may still be catching up.
    private static final long GIFT_RETRY_GRACE_MS = 15_000L;
    private static final long START_WAIT_MS = 60_000L;
    // Every confirmation that ever arrived landed at exactly 5 or 10 s
    // (aligned to the native module's ~5 s scan cadence) across 15 successful
    // dispatches in a 2026-08-29 21-item batch, never later. The 40 s this
    // used to be was sized to tolerate a slow-but-eventually-successful
    // confirmation that has never actually been observed -- and now that
    // waitForInventoryConfirmation() short-circuits immediately on
    // start-rpc-faulted, the only thing left for this deadline to cover *is*
    // that slow-success case. 15 s keeps a margin over the observed 10 s max
    // without paying for 25+ more seconds of a wait that, in every sample so
    // far, was never going to end in a confirmation.
    private static final long CONFIRM_WAIT_MS = 15_000L;
    // Expeditions dispatched at arm's length return almost immediately, so this
    // only lets the inventory projection settle before the next teleport.
    private static final long SETTLE_MS = 1_000L;
    // Reward receipt can now confirm before the next inventory scan. Preserve
    // the user's minimum six seconds after observing a start before hopping.
    private static final long MIN_POST_START_HOP_MS = 6_000L;
    // A start request the game accepts can still silently fail to take, and an
    // identical retry minutes later succeeds, so give each item more than one
    // chance before moving on.
    private static final int DISPATCH_ATTEMPTS = 1; // Only proven preflight-busy tasks receive one deferred revisit.
    // Long enough for the native scanner (~5 s cadence) to observe `off` and
    // drop its confirmation lock before we re-arm.
    private static final long LOCK_RELEASE_MS = 7_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile DispatchBatchService activeInstance;
    private volatile boolean stopping, running;
    private NotificationManager notificationManager;
    private DispatchOverlay overlay;
    private volatile DispatchProbeGateway.Candidate currentCandidate;
    private volatile String currentPhase = "handoff";
    private volatile String currentDetail = "";
    private volatile int currentPosition, currentTotal;
    private volatile int currentSuccesses, currentFailures;
    private volatile int currentSkipped;
    private int plannedTotal;
    private String resumeId;
    private WorkCheckpoint checkpoint;
    private DispatchProbeGateway.Candidate arrivalAnchor;
    private double arrivalRadius = ARRIVAL_TARGET_METRES;

    static synchronized void start(Context context, List<DispatchProbeGateway.Candidate> queue) throws Exception {
        start(context,queue,null);
    }
    static synchronized void resume(Context c,String id) throws Exception {
        WorkCheckpoint cp=CheckpointStore.require(c,GpsWorkLock.BATCH,id);
        start(c,CheckpointStore.candidates(cp),id);
    }
    private static synchronized void start(Context context, List<DispatchProbeGateway.Candidate> queue,String resume) throws Exception {
        if(BatchRecoveryGateway.checking() || BatchRecoveryGateway.needed(context))
            throw new IllegalStateException("上次批次待處理，請先按診斷並恢復批次");
        if (isRunning() || GpsWorkLock.heldBy(context, GpsWorkLock.BATCH))
            throw new IllegalStateException("批次工作已在執行或等待啟動");
        GpsWorkLock.acquire(context, GpsWorkLock.BATCH);
        try {
            saveQueue(context, queue);
            Intent intent = new Intent(context, DispatchBatchService.class).setAction(START).putExtra("resume_id",resume);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
        } catch (Exception error) {
            GpsWorkLock.release(context, GpsWorkLock.BATCH);
            throw error;
        }
    }
    static void stop(Context context) {
        if (!isRunning() && !GpsWorkLock.observedHeldBy(context, GpsWorkLock.BATCH)) return;
        Intent intent = new Intent(context, DispatchBatchService.class).setAction(STOP);
        context.startService(intent);
    }
    static boolean isRunning() { DispatchBatchService s = activeInstance; return s != null && s.running; }

    static void refreshOverlay(Context context) {
        DispatchBatchService active = activeInstance;
        if (active != null) active.getMainExecutor().execute(active::syncOverlay);
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        overlay = new DispatchOverlay(this);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (STOP.equals(action)) {
            if (!running) { stopSelfResult(startId); return START_NOT_STICKY; }
            stopping = true;
            saveLifecycle("stopping", "等待工作結束及停止控制檔回讀");
            if (overlay != null) overlay.update("stopping", currentPosition, currentTotal, currentCandidate, "", currentSuccesses, currentFailures, currentSkipped);
            return START_NOT_STICKY;
        }
        if (!START.equals(action)) { stopSelfResult(startId); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        resumeId = intent.getStringExtra("resume_id");
        try { GpsWorkLock.acquire(this, GpsWorkLock.BATCH); }
        catch (IllegalStateException conflict) { GpsWorkLock.release(this, GpsWorkLock.BATCH); stopSelf(); return START_NOT_STICKY; }
        running = true; stopping = false;
        currentSuccesses = 0;
        currentSkipped = 0;
        currentFailures = 0;
        saveLifecycle("running", "準備中");
        notificationManager = getSystemService(NotificationManager.class);
        startForeground(NOTIFICATION_ID, notification("批次探險準備中", null, 0, 0));
        ControlNotifications.refresh(this);
        syncOverlay();
        if (overlay != null) overlay.update("handoff", 0, 0, null, "", 0, 0);
        worker.execute(this::runBatch);
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (overlay != null) overlay.onConfigurationChanged();
    }
    @Override public void onDestroy() {
        stopping = true;
        if (activeInstance == this) activeInstance = null;
        if (overlay != null) overlay.hide();
        worker.shutdownNow();
        stopForeground(STOP_FOREGROUND_REMOVE);
        ControlNotifications.refresh(this);
        super.onDestroy();
    }

    private void syncOverlay() {
        if (overlay == null) return;
        if (running) {
            overlay.syncVisibility();
            overlay.update(currentPhase, currentPosition, currentTotal, currentCandidate, currentDetail, currentSuccesses, currentFailures, currentSkipped);
        } else overlay.hide();
    }

    private void runBatch() {
        List<DispatchProbeGateway.Candidate> queue = BatchSites.group(loadQueue(this));
        DispatchProbeGateway.Candidate settledSite = null;
        BatchRetry retries = new BatchRetry();
        plannedTotal = queue.size();
        String runError = "";
        boolean controlsClaimed = false;
        boolean reviewRequired = false;
        try {
            awaitPairedCoreAfterSwitch();
            NearbyWorkState.capture(this, GpsWorkLock.BATCH);
            controlsClaimed = true;
            if (queue.isEmpty()) { status("released", 0, 0, "empty", "no_queue"); return; }
            if(resumeId!=null)checkpoint=CheckpointStore.require(this,GpsWorkLock.BATCH,resumeId);
            else {
                List<String> entries=new ArrayList<>();
                for(DispatchProbeGateway.Candidate c:queue)entries.add(c.taskId+"\t"+c.kind+"\t"+c.expirationMs+"\t"+c.latitude+"\t"+c.longitude+"\t"+c.distanceMetres);
                checkpoint=CheckpointStore.create(GpsWorkLock.BATCH,1,5,entries);
            }
            CheckpointStore.save(this,checkpoint);
            for (int index = 0; index < queue.size() && !stopping; index++) {
                CheckpointStore.guard(checkpoint);
                DispatchProbeGateway.Candidate candidate = queue.get(index);
                currentCandidate = candidate;
                int position = index + 1;
                while (!stopping && retries.remaining(candidate.taskId,SystemClock.elapsedRealtime()) > 0) {
                    status("preflight",position,plannedTotal,candidate.taskId,"等待隊員冷卻後複查（最多30秒）");
                    Thread.sleep(1000);
                }
                if (stopping) break;
                // Disarm the nearby rule and any previous batch target before
                // advisory checks. No GPS change or native Start in preflight.
                DispatchProbeGateway.stopBatch();
                String preflightReason = confirmedPreflightBlock(candidate, position, queue.size());
                if (stopping) break;
                if (preflightReason != null) {
                    if (retries.defer(candidate.taskId,preflightReason,SystemClock.elapsedRealtime())) {
                        queue.add(candidate);
                        DispatchProbeGateway.appendControllerHistory("preflight-deferred",candidate,0,0);
                        status("preflight",position,plannedTotal,candidate.taskId,"隊員暫忙，排到最後複查一次");
                        continue;
                    }
                    ++currentSkipped;
                    DispatchProbeGateway.appendControllerHistory("preflight-skipped", candidate, 0, 0);
                    status("preflight-skipped", position, queue.size(), candidate.taskId, preflightReason);
                    continue;
                }
                DispatchProbeGateway.beginBatch(candidate.taskId);
                CheckpointStore.guard(checkpoint);
                ResourceGuard.check(this,"seed".equals(candidate.kind),false);
                boolean nearbySite = BatchSites.nearby(settledSite, candidate);
                double supportedRadius = areaRadius();
                boolean reuseSite = nearbySite && atSameSite(settledSite, candidate, supportedRadius);
                DispatchProbeGateway.Candidate stopAnchor = reuseSite ? settledSite : candidate;
                arrivalAnchor = stopAnchor;
                arrivalRadius = reuseSite ? supportedRadius : ARRIVAL_TARGET_METRES;
                settledSite = null;
                if (!reuseSite) {
                    if (nearbySite) DispatchProbeGateway.appendControllerHistory("area-fallback-to-target", candidate, 0, 0);
                    flyToAndReturnToGame(candidate);
                } else {
                    DispatchProbeGateway.appendControllerHistory("same-site-recheck", candidate, 0, 0);
                }
                boolean arrived = waitForArrival(candidate, position, queue.size());
                if (!arrived && reuseSite && !stopping) {
                    // No ready proof has been sent: safe to fall back to a dedicated stop.
                    DispatchProbeGateway.appendControllerHistory("area-fallback-to-target", candidate, 0, 0);
                    arrivalAnchor = stopAnchor = candidate;
                    arrivalRadius = ARRIVAL_TARGET_METRES;
                    flyToAndReturnToGame(candidate);
                    arrived = waitForArrival(candidate, position, queue.size());
                }
                if (!arrived) {
                    if (stopping) break;
                    DispatchProbeGateway.appendControllerHistory("arrival-timeout", candidate, 0, 0);
                    ++currentFailures;
                    status("failed", position, queue.size(), candidate.taskId, "arrival_timeout");
                    continue;
                }
                // A start request can be accepted by the game and still never
                // take: on 2026-08-29 the same fruit, from the same spot and
                // with no other change, produced no inventory confirmation at
                // 18:01:39 and confirmed 11 s after an identical retry at
                // 18:08:59.  So a lost dispatch is expected, not fatal -- retry
                // the item, and never abandon the rest of the queue for it.
                boolean dispatched = false;
                boolean safelySkipped = false;
                String failureDetail = "game_refresh_timeout";
                final long itemAttemptStartedMs = System.currentTimeMillis();
                long startObservedElapsedMs = 0;
                for (int attempt = 1; attempt <= DISPATCH_ATTEMPTS && !dispatched && !stopping; attempt++) {
                    // A late auto-claim receipt may arrive between attempts.
                    // Never wait for a second start of an already collected item.
                    if (ReturnRewardModuleGateway.collectedSince(candidate.taskId, itemAttemptStartedMs)) {
                        status("inventory-confirmed", position, queue.size(), candidate.taskId, "reward_collected");
                        dispatched = true;
                        break;
                    }
                    long attemptStartedMs = System.currentTimeMillis();
                    status("handoff", position, queue.size(), candidate.taskId,
                            "attempt_" + attempt + "_of_" + DISPATCH_ATTEMPTS);
                    // Record what the native module believed at the moment we
                    // authorised, so the two location sources stay comparable.
                    DispatchProbeGateway.GameSide view = DispatchProbeGateway.gameSide(candidate.taskId);
                    DispatchProbeGateway.appendMetric("authorised", candidate,
                            view != null && view.known() ? view.metres : -1.0, view == null ? 0 : view.durationMs);
                    ResourceGuard.check(this,"seed".equals(candidate.kind),false);
                    CheckpointStore.guard(checkpoint);
                    checkpoint.uncertain=true;
                    CheckpointStore.save(this,checkpoint); // durable BEFORE authorising native
                    reviewRequired = true; // Persisted lifecycle remains blocked unless outcome becomes known.
                    DispatchProbeGateway.markArrivalReady(candidate.taskId, System.currentTimeMillis());
                    DispatchProbeGateway.appendControllerHistory("arrival-ready", candidate, 0, 0);
                    status("awaiting-game-list", position, queue.size(), candidate.taskId, "native_gate");
                    int startResult = waitForNativeStart(candidate, position, queue.size(), attemptStartedMs);
                    if (startResult > 0) startObservedElapsedMs = SystemClock.elapsedRealtime();
                    if (startResult < 0) {
                        failureDetail = "gift_pikmin_unavailable";
                        // An earlier timed-out attempt may still complete. Only a
                        // first-attempt rejection can resolve this checkpoint here.
                        if (attempt == 1 && confirmGiftRejectedAfterStop(candidate, itemAttemptStartedMs)) {
                            checkpoint.finishTask(candidate.taskId);
                            checkpoint.uncertain = false;
                            CheckpointStore.save(this, checkpoint);
                            reviewRequired = false;
                            safelySkipped = true;
                        }
                        break;
                    }
                    if (startResult == 0) {
                        // A start that never fires is the visible symptom of a
                        // held confirmation lock, so clear it here as well
                        // rather than burning the remaining attempts on it.
                        DispatchProbeGateway.appendControllerHistory("game-refresh-timeout", candidate, 0, 0);
                        failureDetail = "game_refresh_timeout";
                        releaseNativeConfirmationLock(candidate, attempt < DISPATCH_ATTEMPTS);
                    } else if (waitForInventoryConfirmation(candidate, position, queue.size(), attemptStartedMs)) {
                        dispatched = true;
                    } else {
                        DispatchProbeGateway.appendControllerHistory("inventory-confirm-timeout", candidate, 0, 0);
                        failureDetail = "confirmation_timeout";
                        status("confirmation-timeout", position, queue.size(), candidate.taskId,
                                attempt < DISPATCH_ATTEMPTS ? "retrying" : "giving_up");
                        // The native module takes an in-memory confirmation lock
                        // when it requests a start and releases it only when the
                        // task leaves the inventory projection or the controller
                        // mode goes to `off`.  A dispatch that never took would
                        // otherwise block every later one for the rest of the
                        // batch: on 2026-08-29 one lost dispatch on item 2 left
                        // items 2 and 3 unable to dispatch at all, six straight
                        // game-refresh-timeouts.  Drop to `off` long enough for
                        // the scanner to observe it, then re-arm.
                        releaseNativeConfirmationLock(candidate, attempt < DISPATCH_ATTEMPTS);
                    }
                }
                if (safelySkipped) {
                    ++currentSkipped;
                    DispatchProbeGateway.appendControllerHistory("gift-unavailable-skipped", candidate, 0, 0);
                    status("preflight-skipped", position, queue.size(), candidate.taskId, "指定皮克敏不可用");
                } else if (dispatched) {
                    reviewRequired = false;
                    ++currentSuccesses;
                    status("post-dispatch-wait", position, queue.size(), candidate.taskId, "settle");
                    long remaining = startObservedElapsedMs == 0 ? MIN_POST_START_HOP_MS
                            : MIN_POST_START_HOP_MS - (SystemClock.elapsedRealtime() - startObservedElapsedMs);
                    Thread.sleep(Math.max(SETTLE_MS, remaining));
                    checkpoint.finishTask(candidate.taskId);
                    checkpoint.uncertain=false;
                    CheckpointStore.save(this,checkpoint);
                    // Keep the physical stop, not the last collected item's coordinates.
                    // Otherwise A->B->C could drift beyond the original 100 m anchor.
                    settledSite = stopAnchor;
                } else if (!stopping) {
                    ++currentFailures;
                    status("failed", position, queue.size(), candidate.taskId, BatchRetry.category(failureDetail));
                    if (reviewRequired) throw new IllegalStateException("派遣授權已送出但結果未確認；請核對遊戲與領取紀錄，不自動重送");
                }
            }
        } catch (Exception error) {
            runError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        } finally {
            saveLifecycle("stopping", "正在確認停止控制");
            StringBuilder errors = new StringBuilder();
            if (controlsClaimed) {
                StopChecks.attempt(errors, "關閉派遣", DispatchProbeGateway::stopBatch);
                StopChecks.attempt(errors, "派遣回讀", StopChecks::dispatch);
            }
            if (reviewRequired) errors.append("派遣結果待核對；附近派遣保持暫停，請查看遊戲與紀錄後再處理停止全部");
            if (controlsClaimed && errors.length() == 0)
                StopChecks.attempt(errors, "恢復附近派遣", () -> NearbyWorkState.restore(this, GpsWorkLock.BATCH));
            String terminal = WorkProgress.terminal(stopping, runError, errors.toString());
            String detail = errors.length() > 0 ? errors.toString() : runError;
            try { status("completed".equals(terminal) ? "released" : terminal,
                    currentPosition, queue.size(), "released", detail); }
            catch (Exception statusError) { detail += "；舊狀態紀錄寫入失敗"; }
            saveLifecycle(terminal, detail + "；已送出的原生請求仍可能完成");
            GpsWorkLock.release(this, GpsWorkLock.BATCH);
            running = false;
            if (overlay != null) overlay.hide();
            ControlNotifications.batchResult(this,terminal,currentSuccesses,currentFailures,currentSkipped);
            stopForeground(STOP_FOREGROUND_REMOVE); ControlNotifications.refresh(this); stopSelf();
        }
    }

    /** The confirmation dialog can keep Unity paused past the 12 s runtime freshness window.
     * Check installed bytes once, then wait only for the same-PID heartbeat after the app
     * switches back to Pikmin. Never authorise GPS or a native request on stale evidence. */
    private void awaitPairedCoreAfterSwitch() throws Exception {
        try {
            CoreUpdater.requireReady(this);
            return;
        } catch (IOException notReady) {
            if (!CoreUpdater.GAME_NOT_READY.equals(notReady.getMessage())) throw notReady;
        }
        CorePair pair = CoreUpdater.pair(this);
        status("handoff", 0, plannedTotal, "startup", "waiting_game_core");
        long deadline = SystemClock.elapsedRealtime() + 30_000L;
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            if (!CoreUpdater.runtimePid(this, pair).isEmpty()) return;
            Thread.sleep(1_000L);
        }
        if (stopping) throw new InterruptedException("批次安全停止");
        String bootstrap=CoreBootstrapFailure.read(this);
        throw new IOException("切回 Pikmin 後 30 秒仍未確認同程序配對核心；未移動 GPS 或派遣"
                +(bootstrap.isEmpty()?"":"；"+bootstrap));
    }

    private double areaRadius() {
        try {
            String[] parts=ProbeSnapshot.sections(GpsModuleGateway.farmRead(ProbeSnapshot.command(
                    OwnerGameProcess.command(),
                    "cat /data/user/0/com.nianticlabs.pikmin/files/batch_area_capability.tsv 2>/dev/null || true",
                    OwnerGameProcess.command())),3);
            return BatchSites.supportedRadius(parts[1],parts[0].trim(),parts[2].trim(),System.currentTimeMillis());
        }catch(Exception unknown){return ARRIVAL_TARGET_METRES;}
    }

    private void flyToAndReturnToGame(DispatchProbeGateway.Candidate candidate) throws Exception {
        GpsModuleGateway.flyTo(this, candidate.latitude + "," + candidate.longitude);
        DispatchProbeGateway.appendControllerHistory("gps-requested", candidate, 0, 0);
        // The external JoyStick may bring its own Activity to the foreground
        // after accepting TELEPORT. Unity stops producing the PID-bound native
        // heartbeat while backgrounded, so arrival cannot be proved. Preserve
        // the running JoyStick service and only return focus if it owns the UI.
        Thread.sleep(1500L);
        if (!stopping) RouteSession.returnToGameIfJoystick();
    }

    private boolean atSameSite(DispatchProbeGateway.Candidate anchor, DispatchProbeGateway.Candidate candidate, double radius) {
        try {
            GpsModuleGateway.ArrivalSnapshot snapshot = GpsModuleGateway.arrivalSnapshot(candidate.taskId);
            DispatchProbeGateway.GameSide game = snapshot.candidate;
            return game != null && game.known() && game.fresh(System.currentTimeMillis(), GAME_SIDE_MAX_AGE_MS)
                    && game.observedMs <= System.currentTimeMillis()
                    && game.canStart && game.durationMs > 0 && game.durationMs <= 2000
                    && game.metres <= radius
                    && GpsModuleGateway.distanceMetres(snapshot.provider.latitude, snapshot.provider.longitude,
                        candidate.latitude, candidate.longitude) <= radius
                    && GpsModuleGateway.distanceMetres(snapshot.provider.latitude, snapshot.provider.longitude,
                        anchor.latitude, anchor.longitude) <= ARRIVAL_TARGET_METRES;
        } catch (Exception unknown) { return false; }
    }

    /**
     * Returns the native module to `off` briefly so it drops the confirmation
     * lock left by a dispatch that was requested but never took, then re-arms
     * for this task when another attempt follows.  Without this the lock
     * survives for the whole batch and silently blocks every later dispatch.
     */
    private String confirmedPreflightBlock(DispatchProbeGateway.Candidate candidate, int position, int total) throws Exception {
        try {
            DispatchPreflight.Observation first = DispatchProbeGateway.preflight(candidate);
            if (first == null || !first.blocked()) return null;
            status("preflight", position, total, candidate.taskId, "confirming_availability");
            long deadline = SystemClock.elapsedRealtime() + 7500L;
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(1000L);
                DispatchPreflight.Observation next = DispatchProbeGateway.preflight(candidate);
                if (next == null || !next.blocked()) return null;
                if (next.confirms(first, System.currentTimeMillis())) return next.alreadyDispatched() ? "already-dispatched" : next.reason;
                // Changed identity/reason or process is unknown, not a skip.
                if (next.at > first.at || !next.pid.equals(first.pid)) return null;
            }
        } catch (Exception unavailable) {
            if (unavailable instanceof InterruptedException) throw unavailable;
            // Older modules, missing/truncated data and read errors retain the
            // original guarded flight/selection flow; never claim busy.
        }
        return null;
    }

    private boolean confirmGiftRejectedAfterStop(DispatchProbeGateway.Candidate candidate, long attemptMs) throws Exception {
        DispatchPreflight.Observation before = DispatchProbeGateway.preflight(candidate);
        DispatchProbeGateway.stopBatch();
        StopChecks.dispatch();
        long stoppedAt = System.currentTimeMillis();
        long deadline = SystemClock.elapsedRealtime() + 12000;
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(1000);
            DispatchPreflight.Observation after = DispatchProbeGateway.preflight(candidate);
            if (before == null || after == null || !before.pid.equals(after.pid)) return false;
            if (after.at <= stoppedAt) continue;
            DispatchConfirmation.Evidence evidence = DispatchProbeGateway.evidenceSince(candidate.taskId, attemptMs, true);
            return evidence.safelyRejected();
        }
        return false;
    }

    private void releaseNativeConfirmationLock(DispatchProbeGateway.Candidate candidate, boolean rearm) throws Exception {
        DispatchProbeGateway.stopBatch();
        Thread.sleep(LOCK_RELEASE_MS);
        if (rearm && !stopping) DispatchProbeGateway.beginBatch(candidate.taskId);
    }

    /**
     * Arrival requires both location sources to place us at the spawn: the
     * provider, which the game's duration maths follows, and the native
     * module's own reading, which is what its dispatch gate checks.  Requiring
     * both is what makes the round trip actually short -- agreeing at 0.1 m
     * produced a 37 ms expedition, while trusting one source alone previously
     * dispatched from 85 m away and took 22.9 s.
     */
    private boolean waitForArrival(DispatchProbeGateway.Candidate candidate, int position, int total) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + ARRIVAL_WAIT_MS;
        long providerReachedElapsedMs = 0L, providerReachedWallMs = 0L;
        long lastFocusCheck = SystemClock.elapsedRealtime();
        BatchArrivalProof proof = new BatchArrivalProof();
        while (SystemClock.elapsedRealtime() < deadline && !stopping) {
            CheckpointStore.guard(checkpoint);
            String detail;
            int accepted = 0;
            try {
                // JoyStick can reclaim focus after its teleport Activity has
                // settled. Recheck sparingly, and never steal another app.
                if (SystemClock.elapsedRealtime() - lastFocusCheck >= 5_000L) {
                    lastFocusCheck = SystemClock.elapsedRealtime();
                    RouteSession.returnToGameIfJoystick();
                }
                GpsModuleGateway.ArrivalSnapshot snapshot = GpsModuleGateway.arrivalSnapshot(candidate.taskId);
                GpsModuleGateway.Point actual = snapshot.provider;
                double provider = GpsModuleGateway.distanceMetres(actual.latitude, actual.longitude, arrivalAnchor.latitude, arrivalAnchor.longitude);
                DispatchProbeGateway.GameSide view = snapshot.candidate;
                boolean gameFresh = view != null && view.known() && view.fresh(System.currentTimeMillis(), GAME_SIDE_MAX_AGE_MS);
                if (provider > ARRIVAL_TARGET_METRES) {
                    proof.reset();
                    providerReachedElapsedMs = providerReachedWallMs = 0L;
                    detail = String.format(Locale.US, "provider_%.1fm", provider);
                } else if (providerReachedElapsedMs == 0L) {
                    proof.reset();
                    providerReachedElapsedMs = SystemClock.elapsedRealtime();
                    providerReachedWallMs = System.currentTimeMillis();
                    detail = "settling_game_cursor_0s";
                } else if (SystemClock.elapsedRealtime() - providerReachedElapsedMs < GAME_SYNC_SETTLE_MS) {
                    proof.reset();
                    long remaining = GAME_SYNC_SETTLE_MS - (SystemClock.elapsedRealtime() - providerReachedElapsedMs);
                    detail = "settling_game_cursor_" + Math.max(1L, (remaining + 999L) / 1000L) + "s";
                } else if (!gameFresh) {
                    // A missing candidate between native scans is not a new proof,
                    // but need not erase the first fresh scan while GPS remains here.
                    int held = proof.missing(SystemClock.elapsedRealtime());
                    String reason = view == null ? "candidate_missing" : !view.known()
                            ? "distance_unknown" : "scan_stale";
                    detail = "awaiting_native_scan_" + reason + "_" + held + "_of_2";
                } else if (view.observedMs < providerReachedWallMs + GAME_SYNC_SETTLE_MS) {
                    proof.reset();
                    detail = "awaiting_post_settle_scan";
                } else if (view.metres > arrivalRadius) {
                    proof.reset();
                    detail = String.format(Locale.US, "game_%.1fm", view.metres);
                } else if (arrivalRadius > ARRIVAL_TARGET_METRES && (!view.canStart || view.durationMs <= 0 || view.durationMs > 2000
                        || areaRadius() < arrivalRadius)) {
                    return false; // No authorisation: caller flies to this task and rechecks.
                } else {
                    int before = proof.count(SystemClock.elapsedRealtime());
                    accepted = proof.accept(SystemClock.elapsedRealtime(), view.observedMs);
                    detail = accepted == before
                            ? "awaiting_distinct_native_scan_" + accepted + "_of_2"
                            : String.format(Locale.US, "agreed_%.1fm_%.1fm_%d_of_2", provider, view.metres, accepted);
                }
                status("arriving", position, total, candidate.taskId, detail);
                if (accepted >= 2) return true;
            } catch (Exception ignored) {
                // Provider could not be rechecked; never carry a proof across that gap.
                proof.reset();
                status("arriving", position, total, candidate.taskId, "arrival_read_error");
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private int waitForNativeStart(DispatchProbeGateway.Candidate candidate, int position, int total, long attemptStartedMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + START_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline && !stopping) {
            CheckpointStore.guard(checkpoint);
            // Keep the native proof fresh only while the provider still reports
            // that we are at this destination.  The provider is what the game's
            // duration math tracks; the native distance column is recorded for
            // comparison but is not trusted to authorise a dispatch.
            try {
                GpsModuleGateway.Point actual = GpsModuleGateway.currentMockLocation();
                double metres = GpsModuleGateway.distanceMetres(actual.latitude, actual.longitude, arrivalAnchor.latitude, arrivalAnchor.longitude);
                if (metres <= ARRIVAL_HOLD_METRES && (arrivalRadius <= ARRIVAL_TARGET_METRES
                        || atSameSite(arrivalAnchor,candidate,areaRadius()))) {
                    DispatchProbeGateway.markArrivalReady(candidate.taskId, System.currentTimeMillis());
                } else {
                    status("gps-drift", position, total, candidate.taskId, "awaiting_game_list");
                }
            } catch (Exception ignored) { }
            DispatchConfirmation.Evidence evidence = DispatchProbeGateway.evidenceSince(candidate.taskId, attemptStartedMs, false);
            if (evidence.started) {
                status("start-requested", position, total, candidate.taskId, "await_inventory_confirmation");
                return 1;
            }
            if (evidence.giftUnavailable) {
                long elapsed = System.currentTimeMillis() - attemptStartedMs;
                if (elapsed < GIFT_RETRY_GRACE_MS) {
                    status("gift-sync-grace", position, total, candidate.taskId,
                            "retrying_until_" + (GIFT_RETRY_GRACE_MS - elapsed) + "ms");
                } else {
                    status("gift-pikmin-unavailable", position, total, candidate.taskId,
                            "still_rejected_after_sync_grace");
                    return -1;
                }
            }
            Thread.sleep(1000);
        }
        return 0;
    }

    private boolean waitForInventoryConfirmation(DispatchProbeGateway.Candidate candidate, int position, int total, long attemptStartedMs) throws Exception {
        final long deadline = SystemClock.elapsedRealtime() + CONFIRM_WAIT_MS;
        while (SystemClock.elapsedRealtime() < deadline && !stopping) {
            // Auto-claim can remove the task before the dispatch projection
            // produces its confirmation row. Exact-task reward receipt is
            // stronger evidence than a start request or a missing candidate.
            DispatchConfirmation.Evidence evidence = DispatchProbeGateway.evidenceSince(candidate.taskId, attemptStartedMs, true);
            if (evidence.collected) {
                status("inventory-confirmed", position, total, candidate.taskId, "reward_collected");
                return true;
            }
            if (evidence.inventoryConfirmed) {
                status("inventory-confirmed", position, total, candidate.taskId, "next_task");
                return true;
            }
            // A faulted RPC never confirms: across a 21-item batch on
            // 2026-08-29, 14/14 start-rpc-faulted attempts produced zero
            // confirmations, while every start-rpc-completed attempt
            // confirmed within 5-10 s. Waiting out the full CONFIRM_WAIT_MS
            // after a known fault cost ~40% of that batch's total wall time
            // for a confirmation that was already provably not coming.
            if (evidence.faulted) {
                status("rpc-faulted", position, total, candidate.taskId, "confirmation_impossible");
                return false;
            }
            Thread.sleep(1000);
        }
        return false;
    }

    private Notification notification(String detail, String subText, int position, int total) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Pikmin 批次探險", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent openApp = PendingIntent.getActivity(this, 1,
                new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_DISPATCH, true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                pendingFlags);
        PendingIntent stopBatch = PendingIntent.getService(this, 2,
                new Intent(this, DispatchBatchService.class).setAction(STOP), pendingFlags);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("批次探險"+(total>0?" "+position+" / "+total:"")).setContentText(detail).setOngoing(true)
                .setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止批次", stopBatch);
        if (subText != null && !subText.isEmpty()) builder.setSubText(subText);
        if (total > 0) builder.setProgress(total, Math.max(0, position), false);
        return ControlNotifications.grouped(this,builder);
    }

    /**
     * Every status write used to go straight to a file nothing displayed live,
     * so a long batch gave no visible sign of whether it was progressing or
     * stuck until the user switched tabs and back. This updates the ongoing
     * notification on every write instead, so progress is visible from the
     * notification shade even with the app closed or the screen locked --
     * and a "更新於" timestamp that stops advancing is a stuck batch, not a
     * silent one.
     */
    private void status(String phase, int position, int total, String taskId, String detail) throws Exception {
        if (plannedTotal > 0) {
            total = plannedTotal;
            position = Math.min(total,currentSuccesses + currentFailures + currentSkipped + 1);
        }
        GpsWorkLock.touch(this, GpsWorkLock.BATCH);
        currentPhase = phase;
        currentDetail = detail == null ? "" : detail;
        currentPosition = position;
        currentTotal = total;
        boolean terminal = "released".equals(phase) || "stopped".equals(phase) || "error".equals(phase) || "stop-incomplete".equals(phase);
        if (!terminal) {
            saveLifecycle(stopping ? "stopping" : "running", phaseLabel(phase) + " · " + DispatchProbeGateway.detailLabel(detail));
            if (stopping) phase = "stopping";
        }
        DispatchProbeGateway.writeBatchStatus(phase, position, total, taskId, detail);
        if (overlay != null) overlay.update(phase, position, total, currentCandidate, currentDetail, currentSuccesses, currentFailures, currentSkipped);
        if (notificationManager == null) return;
        String progress = total > 0 ? position + "/" + total + "　" : "";
        String detailHuman = DispatchProbeGateway.detailLabel(detail);
        String phaseHuman = phaseLabel(phase);
        String line = progress + phaseHuman + (detailHuman.isEmpty() || detailHuman.equals(phaseHuman) ? "" : "（" + detailHuman + "）");
        String updated = "更新於 " + new java.text.SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new java.util.Date());
        ControlNotifications.post(this,NOTIFICATION_ID, notification(line, updated, position, total));
    }

    private void saveLifecycle(String state, String detail) {
        WorkStatus.write(this, GpsWorkLock.BATCH, state, currentPosition, currentTotal,
                currentSuccesses, currentFailures, currentSkipped, detail);
    }

    private static String phaseLabel(String phase) {
        switch (phase) {
            case "stopping": return "停止中，等待確認";
            case "stop-incomplete": return "停止未完成";
            case "preflight": return "起飛前複查可用性";
            case "preflight-skipped": return "已略過，未起飛";
            case "handoff": return "準備派遣";
            case "awaiting-game-list": return "等待原生清單";
            case "arriving": return "移動到點中";
            case "gps-drift": return "位置飄移，重新校正";
            case "start-requested": return "已送出派遣，等待確認";
            case "gift-sync-grace": return "禮物盒同步緩衝中";
            case "gift-pikmin-unavailable": return "指定皮克敏不可用，已略過";
            case "rpc-faulted": return "派遣失敗，準備重試";
            case "confirmation-timeout": return "確認逾時";
            case "inventory-confirmed": return "已確認出發";
            case "post-dispatch-wait": return "短暫等待後前往下一筆";
            case "released": return "批次已結束";
            case "stopped": return "已安全停止";
            case "failed": return "本筆失敗，繼續下一筆";
            default: return phase;
        }
    }

    private static File queueFile(Context context) { return new File(context.getFilesDir(), "dispatch-batch-queue.tsv"); }
    private static void saveQueue(Context context, List<DispatchProbeGateway.Candidate> queue) throws Exception {
        try (FileWriter writer = new FileWriter(queueFile(context), false)) {
            for (DispatchProbeGateway.Candidate c : queue) writer.write(c.taskId + "\t" + c.kind + "\t" + c.expirationMs + "\t"
                    + c.latitude + "\t" + c.longitude + "\t" + c.distanceMetres + "\n");
        }
    }
    private static List<DispatchProbeGateway.Candidate> loadQueue(Context context) {
        ArrayList<DispatchProbeGateway.Candidate> queue = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile(context)))) {
            String line; while ((line = reader.readLine()) != null) {
                String[] f = line.split("\\t", 6); if (f.length < 5) continue;
                double distance = f.length >= 6 ? Double.parseDouble(f[5]) : -1.0;
                queue.add(new DispatchProbeGateway.Candidate(f[0], f[1], Long.parseLong(f[2]),
                        Double.parseDouble(f[3]), Double.parseDouble(f[4]), distance));
            }
        } catch (Exception ignored) { }
        return queue;
    }
}

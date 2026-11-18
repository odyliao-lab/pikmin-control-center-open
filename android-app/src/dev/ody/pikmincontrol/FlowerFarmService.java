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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** User-selected one/two-round runner. Never sends GPS JoyStick STOP. */
public final class FlowerFarmService extends Service {
    private static final String CHANNEL = "flower_farm";
    private static final String START = "dev.ody.pikmincontrol.START_FLOWER_FARM";
    private static final String STOP = "dev.ody.pikmincontrol.STOP_FLOWER_FARM";
    private static final int NOTIFICATION_ID = 154;
    private static final long ARRIVAL_WAIT_MS = 45_000L;
    private static final long RESULT_SETTLE_MS = FlowerFarmEstimate.RESULT_SETTLE_MS;
    private static final long PLANTING_START_WAIT_MS = 20_000L;
    private static final long ROUTE_SUMMARY_REFRESH_MS = 10_000L;
    private String plantingPid;
    private long plantingRequestedAt;
    private boolean plantingConfirmed;
    private boolean recoveryConsent, recoveryConfirmed;
    private boolean recoveryRequiredAtStart;
    private boolean resetOnly;
    private boolean routeMode;
    private RouteSession routeSession;
    private int requestedRounds = 1;
    private String resumeId;
    private WorkCheckpoint checkpoint;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static volatile FlowerFarmService activeInstance;
    private volatile boolean stopping, running;
    private DispatchOverlay overlay;
    private volatile int currentPosition, currentTotal;
    private volatile String currentPoint = "";
    private volatile String currentLine = "準備中";
    private FarmRunReport runReport;
    private volatile String reportCounts = "等待本次領取回報";
    private volatile String routeDispatchCounts = "派遣診斷等待中";
    private volatile String routeHarvestCounts = "等待水果出現";
    private volatile String routeOperatorStatus = "準備中";
    private RouteHarvestSummary routeHarvestSummary;
    private long routeHarvestCheckedAt;

    static synchronized void start(Context context, List<Point> points, int dwellSeconds) throws Exception {
        start(context, points, dwellSeconds, false);
    }
    static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover) throws Exception {
        start(context,points,dwellSeconds,recover,null);
    }
    static synchronized void resume(Context c,String id) throws Exception {
        if(c.getSharedPreferences("farm_route",0).getBoolean("last_route",false))throw new IllegalStateException("沿路模式不接續中途節點，請確認後從首點重新開始");
        WorkCheckpoint cp=CheckpointStore.require(c,GpsWorkLock.FLOWER_FARM,id);
        start(c,CheckpointStore.points(cp),cp.dwell,true,cp);
    }
    private static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover,WorkCheckpoint resume) throws Exception {
        start(context,points,dwellSeconds,recover,resume,false);
    }
    static synchronized void startRoute(Context c,HarvestRoute route,boolean recover)throws Exception {
        List<Point> points=new ArrayList<>();for(HarvestRoute.Point p:route.points)points.add(new Point(p.lat,p.lng));
        start(c,points,5,recover,null,true);
    }
    static synchronized void checkAndReset(Context c) throws Exception {
        if(isRunning() || GpsWorkLock.heldBy(c,GpsWorkLock.FLOWER_FARM))throw new IllegalStateException("花田工作仍在執行");
        GpsWorkLock.acquireFarmRecovery(c);
        try {
            Intent i=new Intent(c,FlowerFarmService.class).setAction(START).putExtra("recover",true).putExtra("reset_only",true);
            c.startForegroundService(i);
        } catch(Exception e) { GpsWorkLock.release(c,GpsWorkLock.FLOWER_FARM);throw e; }
    }
    private static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover,WorkCheckpoint resume,boolean route) throws Exception {
        if (isRunning() || GpsWorkLock.heldBy(context, GpsWorkLock.FLOWER_FARM))
            throw new IllegalStateException("花田工作已在執行或等待啟動");
        if (recover) GpsWorkLock.acquireFarmRecovery(context);
        else GpsWorkLock.acquire(context, GpsWorkLock.FLOWER_FARM);
        try {
            save(context, points, dwellSeconds);
            Intent intent = new Intent(context, FlowerFarmService.class).setAction(START).putExtra("recover", recover)
                    .putExtra("rounds", route?1:ControlSettings.flowerFarmRounds(context)).putExtra("route",route);
            if(!context.getSharedPreferences("farm_route",0).edit().putBoolean("last_route",route).commit())throw new IllegalStateException("無法保存工作模式");
            if(resume!=null)intent.putExtra("resume_id",resume.id).putExtra("rounds",resume.rounds);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
        } catch (Exception error) {
            GpsWorkLock.release(context, GpsWorkLock.FLOWER_FARM);
            throw error;
        }
    }
    static void stop(Context context) {
        if (isRunning() || GpsWorkLock.observedHeldBy(context, GpsWorkLock.FLOWER_FARM))
            context.startService(new Intent(context, FlowerFarmService.class).setAction(STOP));
    }
    static boolean isRunning() { FlowerFarmService s = activeInstance; return s != null && s.running; }
    static boolean isCheckingReset() { FlowerFarmService s=activeInstance;return s!=null && s.running && s.resetOnly; }
    static void refreshOverlay(Context context) {
        FlowerFarmService active = activeInstance;
        if (active != null) active.getMainExecutor().execute(active::syncOverlay);
    }
    static String status(Context context) {
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile(context)))) {
            String line = reader.readLine(); return line == null ? "尚未開始花田採果。" : line;
        } catch (Exception ignored) { return "尚未開始花田採果。"; }
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        overlay = new DispatchOverlay(this, "花田採果", () -> FlowerFarmService.stop(this));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (STOP.equals(intent == null ? "" : intent.getAction())) {
            if (!running) { stopSelfResult(startId); return START_NOT_STICKY; }
            stopping = true;
            status("正在安全停止並釋放 GPS 控制");
            return START_NOT_STICKY;
        }
        if (intent == null || !START.equals(intent.getAction())) { stopSelfResult(startId); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        recoveryConsent = intent.getBooleanExtra("recover", false);
        resetOnly = intent.getBooleanExtra("reset_only", false);
        routeMode = intent.getBooleanExtra("route",false);
        resumeId = intent.getStringExtra("resume_id");
        requestedRounds = ControlSettings.normalizeFarmRounds(intent.getIntExtra("rounds", 1));
        recoveryRequiredAtStart=WorkStatus.farmNeedsRecovery(this);
        try {
            if (recoveryConsent) GpsWorkLock.acquireFarmRecovery(this);
            else GpsWorkLock.acquire(this, GpsWorkLock.FLOWER_FARM);
        }
        catch (IllegalStateException conflict) { GpsWorkLock.release(this, GpsWorkLock.FLOWER_FARM); stopSelf(); return START_NOT_STICKY; }
        running = true; stopping = false;
        runReport=null;checkpoint=null;routeSession=null;
        recoveryConfirmed = plantingConfirmed = false;
        routeHarvestSummary = null;
        routeHarvestCheckedAt = 0;
        routeHarvestCounts = "等待水果出現";
        routeOperatorStatus = "準備中";
        currentPosition = currentTotal = 0;
        currentPoint = "";
        currentLine=resetOnly?"正在檢查並重置，不開始採果":"準備中";
        startForeground(NOTIFICATION_ID, notification("準備中"));
        ControlNotifications.refresh(this);
        try { saveLifecycle("running", resetOnly ? "正在檢查並重置，不開始採果" : "準備中"); }
        catch(RuntimeException storage) {
            android.util.Log.e("PikminFarmRecovery","start-state-save-failed",storage);
            running=false;GpsWorkLock.release(this,GpsWorkLock.FLOWER_FARM);
            stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(this);stopSelf();return START_NOT_STICKY;
        }
        syncOverlay();
        worker.execute(this::run);
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
        worker.shutdownNow(); stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(this);super.onDestroy();
    }

    private void run() {
        List<Point> points = load(this); int dwell = dwell(this);
        String runError = "";
        boolean controlsClaimed = false;
        // Captured before running-state overwrites the previous terminal lifecycle.
        final boolean oldRecoveryRequired = recoveryRequiredAtStart;
        try {
            if(!resetOnly) {
                if(resumeId!=null)checkpoint=CheckpointStore.require(this,GpsWorkLock.FLOWER_FARM,resumeId);
                else if(!points.isEmpty()) {
                    List<String> entries=new ArrayList<>();for(Point p:points)entries.add(p.latitude+"\t"+p.longitude);
                    checkpoint=CheckpointStore.create(GpsWorkLock.FLOWER_FARM,requestedRounds,dwell,entries);
                }
                if(checkpoint!=null)CheckpointStore.save(this,checkpoint);
                runReport = new FarmRunReport(java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), checkpoint==null?0:checkpoint.remaining(), dwell);
                runReport.route=routeMode;
                currentTotal = runReport.total;
                FarmReports.save(this, runReport);
                FarmReports.retain(this);
                if (points.isEmpty()) throw new IllegalStateException("沒有有效花田座標");
            }
            if (stopping) return;
            FarmLocation initial = waitStartup();
            if (initial == null || stopping) return;
            CoreUpdater.requireReady(this);
            if(routeMode) {
                checkpoint=checkpoint.bindFreshAccount(SessionIdentity.read());
                CheckpointStore.save(this,checkpoint);
            }
            String sessionPid = initial.pid;
            plantingPid = sessionPid;
            CheckpointStore.guard(checkpoint);
            if(!resetOnly)ResourceGuard.check(this,true,true);
            NearbyWorkState.capture(this, GpsWorkLock.FLOWER_FARM, recoveryConsent);
            controlsClaimed = true; // Include partially failed writes in cleanup.
            // Nearby armed must be off BEFORE the first teleport as well.
            DispatchProbeGateway.setFarmArmed(false);
            routeSession=new RouteSession(this,sessionPid,checkpoint==null?"":checkpoint.account);
            routeSession.open(recoveryConsent);
            status("準備中 · 自動停止既有種花並關閉結算");
            long stopDeadline=SystemClock.elapsedRealtime()+60000;
            RouteState.StopGate stopGate=new RouteState.StopGate();
            while(!stopping && SystemClock.elapsedRealtime()<stopDeadline) {
                routeSession.heartbeat("prepare");
                try {
                    String[] frame=routeSession.requireStatus(true);
                    if(stopGate.observe(new RouteState(String.join("\t",frame),sessionPid,System.currentTimeMillis(),routeSession.token,"prepare"))){recoveryConfirmed=true;break;}
                }catch(IllegalArgumentException|IllegalStateException notReady){stopGate=new RouteState.StopGate();}
                Thread.sleep(500);
            }
            if(stopping)return;
            if(!recoveryConfirmed)throw new IllegalStateException("停止種花或結算關閉未確認，未發出飛行指令");
            if(resetOnly)return; // No checkpoint/report overwrite, TELEPORT, WALK, planting-on or dispatch.
            routeSession.heartbeat("hold");
            // A flower-field run is one continuous planting session.  Toggling
            // planting at each point makes the game present repeated summary
            // dialogs and causes needless stop/start transitions while GPS is
            // moving.  The per-point gate below controls only farm scanning.
            PlantingControlGateway.setFlowerFarmSession(true);
            if(routeMode){runRoute(points,sessionPid);return;}
            status("先前往首點，確認遊戲到達後再開始連續種花");
            int total = checkpoint.remaining(), done = 0;
            currentTotal = total;
            for (int round = 1; round <= requestedRounds && !stopping; ++round) for (int pointIndex=0;pointIndex<points.size();pointIndex++) {
                int checkpointIndex=(round-1)*points.size()+pointIndex;
                if(checkpoint.done.get(checkpointIndex))continue;
                Point point=points.get(pointIndex);
                if (stopping) break;
                checkPlanting();
                ResourceGuard.check(this,true,true);
                ++done; currentPosition = done; currentPoint = "第 " + round + " 輪 · " + point.label();
                runReport.attempted = done;
                FarmReports.save(this, runReport);
                status("第 " + round + " 輪 · " + done + "/" + total + "：飛往 " + point.label());
                long flightWall = System.currentTimeMillis(), flightElapsed = SystemClock.elapsedRealtime();
                GpsModuleGateway.flyTo(this, point.latitude + "," + point.longitude);
                FarmLocation arrived = waitArrival(point, sessionPid, flightWall, flightElapsed);
                if (stopping) break;
                if (arrived == null) {
                    ++runReport.skipped;
                    FarmReports.save(this, runReport);
                    if (!plantingConfirmed) throw new IllegalStateException("首點定位到點逾時，未啟動種花");
                    status("第 " + round + " 輪 · " + done + "/" + total + "：遊戲定位到點逾時，略過"); continue;
                }
                ++runReport.arrived;
                FarmReports.save(this, runReport);
                if (!plantingConfirmed) {
                    status("首點已到達，等待遊戲確認開始種花");
                    plantingRequestedAt = System.currentTimeMillis();
                    PlantingControlGateway.setEnabled(true);
                    if (!waitPlanting()) break;
                    // Starting can take several seconds: recheck arrival before arming.
                    arrived = waitArrival(point, sessionPid, flightWall, flightElapsed);
                    if (arrived == null && !stopping) throw new IllegalStateException("種花啟動後首點定位失效，已停止工作");
                }
                if (stopping) break;
                checkPlanting();
                status("第 " + round + " 輪 · " + done + "/" + total + "：種花並停留 " + dwell + " 秒");
                boolean stayed;
                try {
                    if (stopping) break;
                    runReport.openWindow(System.currentTimeMillis());
                    FarmReports.save(this, runReport);
                    ResourceGuard.check(this,true,true);
                    CheckpointStore.guard(checkpoint);
                    checkpoint.uncertain=true;
                    CheckpointStore.save(this,checkpoint);
                    DispatchProbeGateway.setFarmArmed(true);
                    stayed = dwellAt(point, arrived, dwell, flightWall, flightElapsed);
                } finally {
                    DispatchProbeGateway.setFarmArmed(false);
                    runReport.closeWindow(System.currentTimeMillis());
                    FarmReports.save(this, runReport);
                }
                if (stopping) break;
                if (stayed) ++runReport.completed; else ++runReport.skipped;
                FarmReports.save(this, runReport);
                settleWithPlantingCheck();
                collectReport();
                if(stayed)checkpoint.done.set(checkpointIndex);
                checkpoint.uncertain=runReport.incomplete;
                for(FarmRunReport.Task task:runReport.tasks.values())if(task.collected<=0)checkpoint.uncertain=true;
                CheckpointStore.save(this,checkpoint);
                status("第 " + round + " 輪 · " + done + "/" + total + (stayed ? "：完成，前往下一點" : "：定位失效，已停止本點派遣"));
            }
        } catch (Exception error) { runError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
        finally {
            StringBuilder errors = new StringBuilder();
            StopChecks.attempt(errors,"保存收尾狀態",()->saveLifecycle("stopping","正在確認停止控制"));
            if (controlsClaimed) {
                StopChecks.attempt(errors, "關閉花田派遣", () -> DispatchProbeGateway.setFarmArmed(false));
                StopChecks.attempt(errors, "停止種花", () -> PlantingControlGateway.setEnabled(false));
                if(routeSession!=null) {
                    try { RouteSession.returnToGameIfJoystick(); }
                    catch(Exception focus){android.util.Log.w("PikminRouteCleanup","遊戲前景切換未確認",focus);}
                    StopChecks.attempt(errors,"原生停止與結算確認",()->{
                        long deadline=SystemClock.elapsedRealtime()+20000;
                        while(SystemClock.elapsedRealtime()<deadline){
                            routeSession.heartbeat("hold");
                            try{if(routeSession.stopped())return;}catch(IllegalArgumentException|IllegalStateException waiting){}
                            Thread.sleep(500);
                        }
                        throw new IllegalStateException("遊戲停止未確認");
                    });
                    StopChecks.attempt(errors,"GPS路線收尾",routeSession::close);
                }
                StopChecks.attempt(errors, "關閉花田模式", () -> PlantingControlGateway.setFlowerFarmSession(false));
                StopChecks.attempt(errors, "花田回讀", StopChecks::farm);
            }
            if (recoveryConsent && controlsClaimed && !recoveryConfirmed)
                errors.append("；復原未確認，尚未開始路線；回花田頁按「復原並重新開始」重試。")
                        .append(runError.isEmpty() ? "" : "\n" + runError);
            // Close attribution before rearming nearby dispatch: later requests
            // belong to nearby automation, not this flower-field report.
            if (runReport != null && errors.length() == 0) runReport.closeWindow(System.currentTimeMillis());
            if (controlsClaimed && errors.length() == 0)
                StopChecks.attempt(errors, "恢復附近派遣", () -> NearbyWorkState.restore(this, GpsWorkLock.FLOWER_FARM));
            StopChecks.attempt(errors,"GPS工作權釋放保存",()->GpsWorkLock.releaseFarmChecked(this));
            boolean routePending=RouteSession.pending(this);
            boolean nearbyPending=NearbyWorkState.ownedBy(this,GpsWorkLock.FLOWER_FARM);
            boolean cleanAbort=FarmRecovery.cleanPreflightAbort(controlsClaimed,oldRecoveryRequired,routePending,nearbyPending,errors.length()>0);
            boolean cleanupConfirmed=(controlsClaimed && recoveryConfirmed && errors.length()==0
                    && !routePending && !nearbyPending) || cleanAbort;
            if(controlsClaimed && errors.length()==0 && !cleanupConfirmed)errors.append("收尾紀錄尚未解除，請檢查並重置");
            String terminal = WorkProgress.terminal(stopping, runError, errors.toString());
            if(resetOnly && cleanupConfirmed && runError.isEmpty() && !stopping)terminal="recovered";
            String detail = errors.length() > 0 ? errors.toString() : runError
                    + (controlsClaimed ? "；停止控制檔已回讀確認，遊戲種花停止及已送出請求仍需原生確認"
                            : "；啟動檢查未通過，未發出飛行或種花指令"+(cleanAbort?"；未接管、無殘留，可直接重試":"；保留原恢復狀態"));
            try { WorkStatus.writeFarm(this,terminal,currentPosition,currentTotal,detail,cleanupConfirmed); }
            catch(RuntimeException storage) {
                terminal="stop-incomplete";detail="花田狀態未保存，請檢查並重置";
                android.util.Log.e("PikminFarmRecovery","terminal-state-save-failed",storage);
            }
            writeStatus(WorkProgress.label(terminal) + "\n" + detail);
            if (runReport != null) {
                // Only a confirmed cleanup can close an interrupted scan window.
                if (errors.length() > 0 || "stop-incomplete".equals(terminal)) runReport.incomplete = true;
                runReport.ended = System.currentTimeMillis();
                runReport.terminal = terminal;
            }
            collectReport();
            running = false; stopForeground(STOP_FOREGROUND_REMOVE);
            ControlNotifications.refresh(this);
            completionNotification();
            stopSelf();
        }
    }
    private FarmLocation waitStartup() throws Exception {
        FarmStartup gate = new FarmStartup(SystemClock.elapsedRealtime());
        String reason = "等待 Pikmin 地圖與定位更新";
        while (gate.waiting(SystemClock.elapsedRealtime(), stopping)) {
            FarmLocation fix = null;
            PlantingState planting = null;
            try {
                fix = GpsModuleGateway.farmLocation(this);
                if (fix.valid) planting = PlantingControlGateway.snapshot(fix.pid);
                reason = FarmStartup.reason(fix, planting, System.currentTimeMillis(), SystemClock.elapsedRealtime());
            } catch (IllegalStateException readFailure) {
                reason = "讀取定位暫時失敗：" + readFailure.getMessage();
            }
            if (!gate.waiting(SystemClock.elapsedRealtime(), stopping)) break;
            if (gate.confirmed(fix,planting,System.currentTimeMillis(),SystemClock.elapsedRealtime())) {
                // Starting this work explicitly authorizes one native takeover-stop.
                // The fresh lease and stop/dialog acknowledgement precede all GPS writes.
                android.util.Log.i("PikminFarmStartup", "ready pid=" + fix.pid + " frame=" + fix.at);
                return fix;
            }
            if(reason.isEmpty())reason="等待主空間新鮮回報連續確認（2筆）";
            android.util.Log.i("PikminFarmStartup", reason);
            status("啟動中 · " + reason);
            Thread.sleep(1000);
        }
        if (stopping) return null;
        String bootstrap=CoreBootstrapFailure.read(this);
        if(!bootstrap.isEmpty())reason=bootstrap;
        throw new IllegalStateException("啟動等待逾時（60 秒）：" + reason);
    }

    private void recoverStartup(String sessionPid) throws Exception {
        status("復原中 · 清理花田控制，不移動 GPS");
        DispatchProbeGateway.setFarmArmed(false);
        PlantingControlGateway.setFlowerFarmSession(false);
        FarmRecovery.Gate gate = new FarmRecovery.Gate(SystemClock.elapsedRealtime(), System.currentTimeMillis());
        PlantingControlGateway.prepareRecovery();
        boolean offSent = false;
        String reason = "等待原生確認復原指令";
        while (gate.waiting(SystemClock.elapsedRealtime(), stopping)) {
            FarmLocation fix = GpsModuleGateway.farmLocation(this);
            if (fix.valid && !sessionPid.equals(fix.pid))
                throw new IllegalStateException("復原期間遊戲程序已更換；請在目前帳號地圖重新確認復原");
            PlantingState state = PlantingControlGateway.snapshot(sessionPid);
            String readiness = FarmStartup.reason(fix, state, System.currentTimeMillis(), SystemClock.elapsedRealtime());
            if (readiness.isEmpty()) {
                if (!offSent && gate.observed(state)) {
                    gate.offRequested(System.currentTimeMillis());
                    PlantingControlGateway.setEnabled(false);
                    offSent = true;
                } else if (offSent && gate.ready(state)) {
                    StopChecks.farm();
                    if (!gate.waiting(SystemClock.elapsedRealtime(), stopping)) break;
                    recoveryConfirmed = true;
                    status("復原已確認 · 從第一點重新開始");
                    return;
                }
                reason = state.planting && !state.owned
                        ? "目前為手動種花；請停止種花並關閉結算視窗，確認後自動繼續"
                        : "controller-unavailable".equals(state.action) ? "請回遊戲地圖，等待種花控制器"
                        : "等待遊戲確認停止種花；若有結算視窗請關閉";
            } else { gate.ready(null); reason = readiness; }
            status("復原中 · " + reason);
            Thread.sleep(500);
        }
        if (!stopping) throw new IllegalStateException("復原等待逾時（60 秒）：" + reason);
    }
    private FarmLocation waitArrival(Point point, String sessionPid, long flightWall, long flightElapsed) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + ARRIVAL_WAIT_MS;
        FarmLocation.Arrival gate = new FarmLocation.Arrival();
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            if(routeSession==null)checkPlanting();
            FarmLocation fix = null;
            if(routeSession!=null) {
                routeSession.keepAlive();
                RouteObservation observation=GpsModuleGateway.routeObservation(this,sessionPid);
                observation.require(sessionPid,checkpoint.account,plantingRequestedAt,plantingConfirmed);
                fix=observation.location;
            }
            try {
                if(fix==null)fix = GpsModuleGateway.farmLocation(this);
            } catch (IllegalStateException notReadyYet) { gate = new FarmLocation.Arrival(); }
            if (fix != null) {
                if (fix.valid && !sessionPid.equals(fix.pid)) throw new IllegalStateException("遊戲程序已更換，停止花田工作");
                if (gate.observe(fix, point.latitude, point.longitude, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), flightWall, flightElapsed) && !stopping
                        && SystemClock.elapsedRealtime() < deadline) {
                    android.util.Log.i("PikminFarmArrival", "accepted pid=" + fix.pid + " epoch=" + fix.epoch + " frame=" + fix.at
                            + " game=" + fix.lat + "," + fix.lng + " target=" + point.label());
                    return fix;
                }
            }
            Thread.sleep(1000);
        }
        return null;
    }
    private boolean dwellAt(Point point, FarmLocation arrived, int seconds, long flightWall, long flightElapsed) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            checkPlanting();
            FarmLocation fix = GpsModuleGateway.farmLocation(this);
            if (!arrived.pid.equals(fix.pid) || arrived.epoch != fix.epoch
                    || !fix.atTarget(point.latitude, point.longitude, System.currentTimeMillis(),
                        SystemClock.elapsedRealtime(), flightWall, flightElapsed)) {
                android.util.Log.i("PikminFarmArrival", "dwell invalidated state=" + fix.state + " epoch=" + fix.epoch);
                return false;
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) Thread.sleep(Math.min(1000L, remaining));
        }
        return !stopping;
    }
    private boolean waitPlanting() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + PLANTING_START_WAIT_MS;
        PlantingState.StartGate gate = new PlantingState.StartGate();
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            if(routeSession!=null)routeSession.heartbeat("hold");
            PlantingState state = PlantingControlGateway.snapshot(plantingPid);
            if (gate.observe(state, plantingRequestedAt) && !stopping && SystemClock.elapsedRealtime() < deadline) {
                plantingConfirmed = true;
                android.util.Log.i("PikminFarmArrival", "planting confirmed pid=" + plantingPid + " frame=" + state.at);
                return true;
            }
            Thread.sleep(500);
        }
        if (stopping) return false;
        throw new IllegalStateException("種花未在 20 秒內確認啟動，請檢查花瓣與遊戲提示");
    }
    private void checkPlanting() throws Exception {
        if(routeSession!=null)routeSession.heartbeat(routeSession.phase);
        CheckpointStore.guard(checkpoint);
        if (!plantingConfirmed || stopping) return;
        if (!PlantingControlGateway.snapshot(plantingPid).activeSince(plantingRequestedAt))
            throw new IllegalStateException("遊戲種花已停止或回報失效，已中止花田工作；請檢查花瓣與遊戲提示");
    }
    private void settleWithPlantingCheck() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + RESULT_SETTLE_MS;
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            checkPlanting();
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining > 0) Thread.sleep(Math.min(1000L, remaining));
        }
    }
    private void runRoute(List<Point> points,String pid)throws Exception {
        StringBuilder raw=new StringBuilder();for(Point p:points)raw.append(p.latitude).append(',').append(p.longitude).append('\n');
        HarvestRoute route=HarvestRoute.coordinates(raw.toString());
        Point first=points.get(0);long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        status("沿路採果 · 前往首點");
        GpsModuleGateway.flyTo(this,first.latitude+","+first.longitude);
        if(waitArrival(first,pid,wall,elapsed)==null){if(stopping)return;throw new IllegalStateException("首點未到達");}
        if(stopping)return;
        plantingRequestedAt=System.currentTimeMillis();PlantingControlGateway.setEnabled(true);
        if(!waitPlanting())return;
        if(waitArrival(first,pid,wall,elapsed)==null){if(stopping)return;throw new IllegalStateException("種花後首點定位失效");}
        HarvestRouteProgress progress=new HarvestRouteProgress(route);
        FarmLocation before=GpsModuleGateway.farmLocation(this);
        if(!before.valid||!pid.equals(before.pid))throw new IllegalStateException("路線起點定位失效");
        progress.observeProvider(before.provider,SystemClock.elapsedRealtime());
        if(!progress.failure().isEmpty())throw new IllegalStateException(progress.failure());
        checkpoint.uncertain=true;CheckpointStore.save(this,checkpoint);
        long routeWindowStarted=System.currentTimeMillis();
        runReport.openWindow(routeWindowStarted);FarmReports.save(this,runReport);
        routeHarvestSummary=new RouteHarvestSummary(routeWindowStarted);
        routeSession.walk(route);   // WALK is sent exactly once; a missing first movement never re-sends it.
        long started=SystemClock.elapsedRealtime();
        long deadline=started+(route.estimatedSeconds()+90)*1000, checked=started, lastUi=0;
        RouteLoopTiming timing=new RouteLoopTiming(SystemClock::elapsedRealtime);
        // Exit decisions are named through timing.exit(...)/exitOutsideLoop(...)/failed(e); the finally seals the
        // diagnostics after the WHOLE walk (loop, pause, endpoint STATUS, endpoint arrival), not just the loop.
        boolean endpointReached=false;
        try {
        while(!stopping&&SystemClock.elapsedRealtime()<deadline) {
            timing.beginLoop();   // this loop's sample starts as "unknown"; only a judged fix fills it (S1)
            try {
            // Every segment is timed through RouteLoopTiming.timed: a throw or a timeout still records its duration and names the failed stage.
            timing.timed("heartbeat",()->{routeSession.keepAlive();return null;});
            Intent sample=timing.timed("status",routeSession::status);long now=SystemClock.elapsedRealtime();
            RouteObservation observation=timing.timed("read",()->GpsModuleGateway.routeObservation(this,pid));
            timing.stage("require");
            observation.require(pid,checkpoint.account,plantingRequestedAt);
            FarmLocation fix=observation.location;
            // The root snapshot may contain a fix newer than the STATUS observation.
            // Validate against the clock AFTER that read; progress uses the GPS fix timestamp.
            long verifiedAt=SystemClock.elapsedRealtime();
            timing.stage("verify");
            if(!fix.valid||!pid.equals(fix.pid)||!fix.fresh(System.currentTimeMillis(),verifiedAt))
                throw new IllegalStateException("沿路遊戲定位失效："+fix.readiness+"；state="+fix.state
                        +"；rawAgeMs="+(verifiedAt-fix.rawAt)+"；providerAgeMs="+(fix.provider==null?-1:verifiedAt-fix.provider.fixMs));
            timing.stage("provider-match");
            if(FarmLocation.distance(fix.provider.latitude,fix.provider.longitude,sample.getDoubleExtra("lat",0),sample.getDoubleExtra("lng",0))>10)
                throw new IllegalStateException("GPS供應來源與路線回報不符");
            timing.stage("progress");
            boolean end=progress.observeProvider(fix.provider,verifiedAt);
            timing.observed(progress.detail(),progress.failure().isEmpty());
            if(!progress.failure().isEmpty()){
                // Short operator wording on the UI line; gap/age/move detail and loop timing go to the report and log.
                timing.exit("progress:"+progress.failure());
                throw new IllegalStateException(HarvestRouteProgress.label(progress.failure())+"（"+progress.failure()+"）");
            }
            int priorPosition=currentPosition;
            currentPosition=progress.reached();runReport.attempted=currentPosition;
            // Provider checkpoints are not per-flower game arrival or fruit success.
            if(currentPosition!=priorPosition||verifiedAt-lastUi>=2000){
                refreshRouteDispatchDiagnostics();
                refreshRouteHarvestSummary(false);
                status("沿路採果 · 19 km/h · "+currentPosition+"/"+points.size());lastUi=verifiedAt;
            }
            if(end){endpointReached=true;timing.milestone("observation-endpoint-reached");break;}   // not "finished": pause and endpoint arrival still follow (S2)
            timing.stage("route-active");
            if(!sample.getBooleanExtra("is_route_active",false)&&now-started>5000){timing.exit("route-inactive");throw new IllegalStateException("路線意外停止");}
            if(now-checked>10000){timing.timed("resource",()->{ResourceGuard.check(this,true,true);return null;});checked=now;}
            } finally { timing.endLoop(); }   // exactly one loop sample per iteration, including a failed one
            if(endpointReached)break;
            long loopMs=timing.lastLoopMs();
            // Per-loop line for every continuing loop (an exiting loop is covered by the final summary);
            // the p50/p95/max summary (last 128 samples per segment) every 20 loops.
            android.util.Log.i("PikminRoute","loopMs="+loopMs+" "+progress.detail()+(timing.loops()%20==0?" | "+timing.summary():""));
            // Wait = 1 s cadence minus this loop's cost, clamped to [100, 500] ms: shortest observation period 500 ms
            // (≈2 observations/s at best), a slow loop yields only the minimum; heartbeat and resource reads are extra root calls.
            Thread.sleep(RouteLoopTiming.waitMs(loopMs));
        }
        if(!endpointReached)timing.exitOutsideLoop(stopping);   // user-stop / deadline: no loop sample belongs to this exit
        timing.stage("pause");
        routeSession.pause();
        timing.milestone("pause-ok");
        if(stopping){timing.exit("user-stop");return;}
        timing.stage("route-timeout");
        if(!progress.finished()){timing.exit("deadline");throw new IllegalStateException("沿路行走逾時（已到 "+progress.reached()+"/"+points.size()+"，未觀測到的節點不算完成）");}
        status("已到終點 · 等待遊戲定位追上");
        Point last=points.get(points.size()-1);
        // PAUSE can stop a few metres short; use held provider coordinate, not a synthetic jump.
        timing.stage("endpoint-status");
        Intent held=routeSession.status();
        timing.milestone("endpoint-status-ok");
        Point target=new Point(held.getDoubleExtra("lat",last.latitude),held.getDoubleExtra("lng",last.longitude));
        timing.stage("endpoint-arrival");
        if(waitArrival(target,pid,wall,elapsed)==null){if(stopping){timing.exit("user-stop");return;}timing.exit("endpoint-arrival-failed");throw new IllegalStateException("終點遊戲定位未追上");}
        timing.milestone("endpoint-arrival-ok");
        timing.exit("finished");   // only here: observation endpoint, pause, held STATUS and game arrival all done; cleanup still confirms separately
        runReport.completed=points.size();runReport.arrived=0;
        runReport.closeWindow(System.currentTimeMillis());
        refreshRouteHarvestSummary(true);
        collectReport();
        } catch(Exception e) {
            timing.failed(e);
            throw e;
        } finally {
            // Diagnostics are sealed once the whole walk has left (loop or endpoint handling): stop code, failed stage,
            // the exiting loop's own sample, last accepted sample, gap counters, milestones and timing.
            timing.finish(progress.maxGapMs(),progress.toleratedGaps());
            runReport.routeDiag=timing.diag(progress.reached(),points.size());
            refreshRouteDispatchDiagnostics();
            runReport.routeDispatchDiag=routeDispatchCounts;
            android.util.Log.i("PikminRoute","summary "+runReport.routeDiag);
        }
    }
    private void status(String line) {
        GpsWorkLock.touch(this, GpsWorkLock.FLOWER_FARM);
        if (stopping) line = "停止中，等待清理確認";
        currentLine = line;
        saveLifecycle(stopping ? "stopping" : "running", line);
        writeStatus(line);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) ControlNotifications.post(this,NOTIFICATION_ID, notification(line));
        if (overlay != null) overlay.updateFlowerFarm(routeMode ? routeOverlayStatus() : line, currentPosition, currentTotal,
                routeMode ? routeHarvestCounts : currentPoint + "\n" + reportCounts,
                "");
    }
    private void writeStatus(String line) {
        try (FileWriter writer = new FileWriter(statusFile(this), false)) { writer.write(line + "\n"); } catch (Exception ignored) { }
    }
    private void saveLifecycle(String state, String detail) {
        WorkStatus.writeFarm(this,state,currentPosition,currentTotal,detail,false);
    }

    private void syncOverlay() {
        if (overlay == null) return;
        if (running) {
            overlay.syncVisibility();
            overlay.updateFlowerFarm(routeMode ? routeOverlayStatus() : currentLine, currentPosition, currentTotal,
                    routeMode ? routeHarvestCounts : currentPoint + "\n" + reportCounts,
                    "");
        } else overlay.hide();
    }
    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Pikmin 花田採果", NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 154,
                new Intent(this, MainActivity.class).putExtra("open_flower_farm", true)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 155, new Intent(this, FlowerFarmService.class).setAction(STOP), flags);
        return ControlNotifications.grouped(this,new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle((routeMode?"沿路採果":"逐點採果")+(currentTotal>0?" "+currentPosition+" / "+currentTotal:"")).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(currentTotal, currentPosition, currentTotal == 0)
                .addAction(android.R.drawable.ic_menu_view, "查看進度", open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止採果", stop));
    }
    private void collectReport() {
        if (runReport == null) return;
        FarmReports.collect(this, runReport);
        reportCounts = runReport.counts() + (runReport.incomplete ? "（紀錄不完整）" : "");
    }
    private void refreshRouteDispatchDiagnostics() {
        if (!routeMode || routeSession == null) return;
        try {
            RouteDispatchDiagnostics d=routeSession.dispatchDiagnostics();
            routeDispatchCounts=d.detail();
            routeOperatorStatus=d.operatorStatus();
            android.util.Log.i("PikminRouteDispatch", "at="+d.at+" "+d.detail());
        } catch (Exception e) {
            // Native removes the per-route snapshot as soon as the route lease
            // leaves WALK. Keep the newest valid counters for the final report
            // instead of replacing them with a cleanup-time read failure.
            if ("派遣診斷等待中".equals(routeDispatchCounts)
                    || "派遣診斷待更新".equals(routeDispatchCounts))
                routeDispatchCounts="派遣診斷待更新";
            android.util.Log.w("PikminRouteDispatch", "diagnostics unavailable: "+e.getClass().getSimpleName());
        }
    }
    private void refreshRouteHarvestSummary(boolean finished) {
        if (!routeMode || routeHarvestSummary == null) return;
        long nowElapsed=SystemClock.elapsedRealtime();
        if (!finished && nowElapsed-routeHarvestCheckedAt<ROUTE_SUMMARY_REFRESH_MS) return;
        routeHarvestCheckedAt=nowElapsed;
        try {
            BoundedCommand.Result result=FarmReports.dispatchTail();
            if (result.ok&&!result.truncated) routeHarvestSummary.ingest(result.text,System.currentTimeMillis());
        } catch (Exception readFailure) {
            android.util.Log.w("PikminRouteDispatch","operator summary unavailable",readFailure);
        }
        routeHarvestCounts=routeHarvestSummary.label(finished);
    }
    private String routeOverlayStatus() {
        if (stopping || routeHarvestSummary == null || currentLine.startsWith("已到終點")) return currentLine;
        return routeOperatorStatus;
    }
    private void completionNotification() {
        if (runReport == null) return;
        // Failed runs already have a targeted attention notification; do not double-post.
        if (!"completed".equals(runReport.terminal) && !"stopped".equals(runReport.terminal)) return;
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            PendingIntent open = PendingIntent.getActivity(this, 156,
                    new Intent(this, MainActivity.class).putExtra("open_flower_report", true)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = "花田採果 · " + WorkProgress.label(runReport.terminal);
            String text = (routeMode?"通過路線節點 ":"完成停靠 ") + runReport.completed + "/" + runReport.total + " · " + reportCounts;
            manager.notify(156, new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(open).setCategory(Notification.CATEGORY_STATUS).build());
        } catch (RuntimeException ignored) { android.util.Log.w("PikminFarmReport", "completion notification unavailable"); }
    }
    private static File queueFile(Context c) { return new File(c.getFilesDir(), "flower-farm-points.tsv"); }
    private static File configFile(Context c) { return new File(c.getFilesDir(), "flower-farm-config.txt"); }
    private static File statusFile(Context c) { return new File(c.getFilesDir(), "flower-farm-status.txt"); }
    private static void save(Context c, List<Point> points, int dwell) throws Exception {
        try (FileWriter writer = new FileWriter(queueFile(c), false)) { for (Point p : points) writer.write(p.latitude + "\t" + p.longitude + "\n"); }
        try (FileWriter writer = new FileWriter(configFile(c), false)) { writer.write(Integer.toString(dwell)); }
    }
    private static List<Point> load(Context c) {
        ArrayList<Point> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(queueFile(c)))) { String line; while ((line = reader.readLine()) != null) {
            String[] f = line.split("\\t"); if (f.length == 2) out.add(new Point(Double.parseDouble(f[0]), Double.parseDouble(f[1])));
        }} catch (Exception ignored) { } return out;
    }
    private static int dwell(Context c) { try (BufferedReader r = new BufferedReader(new FileReader(configFile(c)))) { int v = Integer.parseInt(r.readLine()); return v == 5 ? 5 : 10; } catch (Exception ignored) { return 10; } }
    static final class Point { final double latitude, longitude; Point(double a, double b) { latitude = a; longitude = b; } String label() { return String.format(Locale.US, "%.5f,%.5f", latitude, longitude); } }
}

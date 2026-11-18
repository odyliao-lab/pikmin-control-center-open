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
    private long ownedStockCaptured;
    private String ownedStockSummary="";
    private void observeOwnedStock(RouteObservation observation) {
        EmulatorOwnedStockObservation.Result stock=EmulatorOwnedStockObservation.observe(
            observation.ownedCapabilities,observation.ownedJournal,observation.ownedPetals,
            routeSession.pid,checkpoint.account,routeSession.token,plantingRequestedAt,System.currentTimeMillis());
        String summary=stock.summary();
        if(stock.captured>ownedStockCaptured || !summary.equals(ownedStockSummary)) {
            ownedStockCaptured=Math.max(ownedStockCaptured,stock.captured);ownedStockSummary=summary;
            android.util.Log.i("PccOwnedStock","telemetry "+summary);
        }
    }
    private boolean plantingConfirmed;
    private boolean recoveryConsent, recoveryConfirmed;
    private boolean routeMode;
    private volatile EmulatorDailyRouteWork dailyRouteWork;
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
    static void startDailyRoute(Context c,HarvestRoute route,EmulatorDailyRunner.Binding held,String key,long epoch)throws Exception {
        List<Point> points=new ArrayList<>();for(HarvestRoute.Point p:route.points)points.add(new Point(p.lat,p.lng));
        EmulatorDailyRouteWork work=new EmulatorDailyRouteWork(held,key,epoch,System.currentTimeMillis());
        try {
            work.requireStart(c,points);
            start(c,points,5,false,null,true,work);
        }catch(Exception failure){
            if(!work.serviceDispatchAttempted()){
                try{work.notDispatched(c);android.util.Log.w("PccDailyRoute","Not dispatched; terminal fence: "+work.startStage());}
                catch(Exception uncertain){android.util.Log.w("PccDailyRoute","Pre-dispatch fence unconfirmed: "+work.startStage());}
            }else android.util.Log.w("PccDailyRoute","Service dispatch reply unknown; observe only");
            throw failure;
        }
    }
    static void startDevelopmentRoute(Context c,HarvestRoute route,EmulatorDailyRunner.Binding held,String key)throws Exception {
        if(!StepPermissions.notifications(c))throw new IllegalStateException("Visible notification required");
        EmulatorDailyRouteWork.requireIdentity(c,held);
        FarmLocation fix=GpsModuleGateway.farmLocation(c);
        if(!fix.valid||!Integer.toString(held.pid).equals(fix.pid))throw new IllegalStateException("Fresh same-PID location required");
        List<Point> points=new ArrayList<>();for(HarvestRoute.Point p:route.points)points.add(new Point(p.lat,p.lng));
        EmulatorDailyRouteWork work=EmulatorDailyRouteWork.cityDevelopment(held,key,fix.epoch,System.currentTimeMillis(),route);
        work.requireStart(c,points);
        start(c,points,5,false,null,true,work);
    }
    static synchronized boolean ownsDailyRoute(EmulatorDailyRunner.Binding held,String key) {
        FlowerFarmService s=activeInstance;
        return s!=null && s.running && s.dailyRouteWork!=null && s.dailyRouteWork.matches(held,key);
    }
    static EmulatorDailyRunner.Observation observeDailyRoute(Context c,EmulatorDailyRunner.Binding held,String key)throws Exception {
        final FlowerFarmService s;final EmulatorDailyRouteWork work;
        synchronized(FlowerFarmService.class){
            s=activeInstance;
            work=ownsDailyRoute(held,key)?s.dailyRouteWork:null;
        }
        if(work==null)return EmulatorDailyRouteWork.completed(c,held,key);
        work.requireIdentity(c); // slow root reads must not hold the main-thread service lock
        synchronized(FlowerFarmService.class){
            if(activeInstance!=s || !s.running || s.dailyRouteWork!=work)return new EmulatorDailyRunner.Observation(held,key,EmulatorDailyRunner.Evidence.UNKNOWN);
            return work.observation(held,key);
        }
    }
    static void stopDailyRoute(Context c,EmulatorDailyRunner.Binding held,String key)throws Exception {
        final FlowerFarmService s;final EmulatorDailyRouteWork work;
        synchronized(FlowerFarmService.class){
            s=activeInstance;
            if(!ownsDailyRoute(held,key))throw new IllegalStateException("Cannot stop/adopt manual or missing daily work");
            work=s.dailyRouteWork;
        }
        work.requireIdentity(c); // no resource/location readiness needed to stop OWNED work
        synchronized(FlowerFarmService.class){
            if(activeInstance!=s || !s.running || s.dailyRouteWork!=work || !work.matches(held,key))throw new IllegalStateException("Daily work changed before stop");
            s.stopping=true;s.status("每日路線正在安全停止"); // original worker finally owns cleanup
        }
    }
    private boolean dailyRouteWindow() {
        if(dailyRouteWork!=null && !dailyRouteWork.withinRouteWindow(System.currentTimeMillis())){
            stopping=true;return false;
        }
        return !stopping;
    }
    private static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover,WorkCheckpoint resume,boolean route) throws Exception {
        start(context,points,dwellSeconds,recover,resume,route,null);
    }
    private static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover,WorkCheckpoint resume,boolean route,EmulatorDailyRouteWork daily) throws Exception {
        if(daily!=null)daily.beforeGpsLock();
        if (isRunning() || GpsWorkLock.heldBy(context, GpsWorkLock.FLOWER_FARM))
            throw new IllegalStateException("花田工作已在執行或等待啟動");
        if (recover) GpsWorkLock.acquireFarmRecovery(context);
        else GpsWorkLock.acquire(context, GpsWorkLock.FLOWER_FARM);
        try {
            if(daily!=null)daily.beforeLocalSave();
            save(context, points, dwellSeconds);
            Intent intent = new Intent(context, FlowerFarmService.class).setAction(START).putExtra("recover", recover)
                    .putExtra("rounds", route?1:ControlSettings.flowerFarmRounds(context)).putExtra("route",route);
            if(daily!=null)daily.put(intent);
            if(!context.getSharedPreferences("farm_route",0).edit().putBoolean("last_route",route).commit())throw new IllegalStateException("無法保存工作模式");
            if(resume!=null)intent.putExtra("resume_id",resume.id).putExtra("rounds",resume.rounds);
            if(daily!=null)daily.beforeServiceDispatch();
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
        // Construct after parsing the work kind, not while dailyRouteWork is still null.
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized(FlowerFarmService.class){return dailyOnStartCommand(intent,flags,startId);}
    }
    private int dailyOnStartCommand(Intent intent,int flags,int startId) {
        if (STOP.equals(intent == null ? "" : intent.getAction())) {
            if (!running) { stopSelfResult(startId); return START_NOT_STICKY; }
            stopping = true;
            status("正在安全停止並釋放 GPS 控制");
            return START_NOT_STICKY;
        }
        if (intent == null || !START.equals(intent.getAction())) { stopSelfResult(startId); return START_NOT_STICKY; }
        if (running) return START_NOT_STICKY;
        try {
            dailyRouteWork=EmulatorDailyRouteWork.from(intent);
            if(dailyRouteWork!=null && (!intent.getBooleanExtra("route",false) || intent.getBooleanExtra("recover",false) || intent.hasExtra("resume_id")))
                throw new IllegalStateException("Daily route is never recovery/adoption");
        }catch(Exception invalid){stopSelfResult(startId);return START_NOT_STICKY;}
        overlay = new DispatchOverlay(this, EmulatorCityPlantingLabels.title(dailyRouteWork!=null), () -> FlowerFarmService.stop(this));
        recoveryConsent = intent.getBooleanExtra("recover", false);
        routeMode = intent.getBooleanExtra("route",false);
        resumeId = intent.getStringExtra("resume_id");
        requestedRounds = ControlSettings.normalizeFarmRounds(intent.getIntExtra("rounds", 1));
        try {
            if (recoveryConsent) GpsWorkLock.acquireFarmRecovery(this);
            else GpsWorkLock.acquire(this, GpsWorkLock.FLOWER_FARM);
        }
        catch (IllegalStateException conflict) { GpsWorkLock.release(this, GpsWorkLock.FLOWER_FARM); stopSelf(); return START_NOT_STICKY; }
        running = true; stopping = false;
        recoveryConfirmed = plantingConfirmed = false;
        routeHarvestSummary = null;
        routeHarvestCheckedAt = 0;
        routeHarvestCounts = EmulatorCityPlantingLabels.detail(dailyRouteWork!=null,"等待水果出現");
        routeOperatorStatus = "準備中";
        currentPosition = currentTotal = 0;
        currentPoint = "";
        saveLifecycle("running", "準備中");
        if (android.os.Build.VERSION.SDK_INT >= 29) {startForeground(NOTIFICATION_ID, notification("準備中"), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);ControlNotifications.refresh(this);} else {startForeground(NOTIFICATION_ID, notification("準備中"));ControlNotifications.refresh(this);}
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
        worker.shutdownNow(); super.onDestroy();
    }

    private void run() {
        List<Point> points = load(this); int dwell = dwell(this);
        String runError = "";
        boolean controlsClaimed = false;
        try {
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
            ResourceGuard.check(this,true,true);
            routeSession=new RouteSession(this,sessionPid,checkpoint.account);
            if(dailyRouteWork!=null)dailyRouteWork.claim(this,checkpoint,runReport,routeSession,points);
            NearbyWorkState.capture(this, GpsWorkLock.FLOWER_FARM, recoveryConsent);
            controlsClaimed = true; // Include partially failed writes in cleanup.
            // Nearby armed must be off BEFORE the first teleport as well.
            DispatchProbeGateway.setFarmArmed(false);
            routeSession.open(recoveryConsent);
            if(dailyRouteWork!=null)dailyRouteWork.admitted(this,routeSession);
            status("準備中 · 自動停止既有種花並關閉結算");
            long stopDeadline=SystemClock.elapsedRealtime()+60000;
            RouteState.StopGate stopGate=new RouteState.StopGate();
            while(!stopping && SystemClock.elapsedRealtime()<stopDeadline) {
                long stopSampleStart=SystemClock.elapsedRealtime();
                try {
                    String[] frame=routeSession.heartbeatStopStatus();
                    android.util.Log.i("PikminRoutePrepare","stopSampleMs="+(SystemClock.elapsedRealtime()-stopSampleStart));
                    if(stopGate.observe(new RouteState(String.join("\t",frame),sessionPid,System.currentTimeMillis(),routeSession.token,"prepare"))){recoveryConfirmed=true;break;}
                }catch(IllegalArgumentException|IllegalStateException notReady){stopGate=new RouteState.StopGate();}
                Thread.sleep(500);
            }
            if(stopping)return;
            if(!recoveryConfirmed)throw new IllegalStateException("停止種花或結算關閉未確認，未發出飛行指令");
            routeSession.heartbeat("hold");
            // A flower-field run is one continuous planting session.  Toggling
            // planting at each point makes the game present repeated summary
            // dialogs and causes needless stop/start transitions while GPS is
            // moving.  The per-point gate below controls only farm scanning.
            // An owned daily route is movement + planting, NOT special harvesting.
            // Original route/planting ownership, selection, stop and GPS gates remain.
            PlantingControlGateway.setFlowerFarmSession(dailyRouteWork==null);
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
        } catch (Exception error) {
            runError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if(dailyRouteWork!=null)android.util.Log.w("PccDailyRoute","受控路線原始失敗；收尾結果另列",error);
        }
        finally {
            saveLifecycle("stopping", "正在確認停止控制");
            StringBuilder errors = new StringBuilder();
            if (controlsClaimed) {
                StopChecks.attempt(errors, "關閉花田派遣", () -> DispatchProbeGateway.setFarmArmed(false));
                StopChecks.attempt(errors, "停止種花", () -> PlantingControlGateway.setEnabled(false));
                if(routeSession!=null) {
                    try { RouteSession.returnToGameIfJoystick(); }
                    catch(Exception focus){android.util.Log.w("PikminRouteCleanup","遊戲前景切換未確認",focus);}
                    StopChecks.attempt(errors,"原生停止與結算確認",()->{
                        // Observe the ORIGINAL native stop/result only. Match
                        // the existing MuMu petal handoff/native late-stop bound;
                        // no new OFF/RPC, clock reset or old-result adoption.
                        long deadline=SystemClock.elapsedRealtime()+(dailyRouteWork==null?20000:120000);
                        while(SystemClock.elapsedRealtime()<deadline){
                            routeSession.heartbeat("hold");
                            try{if(dailyRouteWork==null?routeSession.stopped():dailyRouteWork.stopConfirmed(this,routeSession,plantingRequestedAt))return;}
                            catch(IllegalArgumentException|IllegalStateException waiting){
                                if(dailyRouteWork!=null)android.util.Log.i("PccDailyStopWait",
                                    "remainingMs="+Math.max(0,deadline-SystemClock.elapsedRealtime())+" pendingClass="+waiting.getClass().getSimpleName());
                            }
                            Thread.sleep(500);
                        }
                        throw new IllegalStateException("遊戲停止未確認");
                    });
                    // Evidence failure must NEVER skip original cleanup.
                    if(dailyRouteWork!=null)StopChecks.attempt(errors,"每日原生閉合取樣",()->dailyRouteWork.beforeFinalClose(this,routeSession,plantingRequestedAt));
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
            String terminal = WorkProgress.terminal(stopping, runError, errors.toString());
            String detail = errors.length() > 0 ? (dailyRouteWork!=null&&!runError.isEmpty()?"原始失敗："+runError+"\n收尾：":"")+errors.toString() : runError
                    + (controlsClaimed ? "；停止控制檔已回讀確認，遊戲種花停止及已送出請求仍需原生確認"
                            : "；啟動檢查未通過，未發出飛行或種花指令");
            saveLifecycle(terminal, detail);
            writeStatus(WorkProgress.label(terminal) + "\n" + detail);
            if (runReport != null) {
                // Only a confirmed cleanup can close an interrupted scan window.
                if (errors.length() > 0) runReport.incomplete = true;
                runReport.ended = System.currentTimeMillis();
                runReport.terminal = terminal;
            }
            GpsWorkLock.release(this, GpsWorkLock.FLOWER_FARM);
            collectReport();
            if(dailyRouteWork!=null)try {
                dailyRouteWork.complete(this,routeSession,plantingRequestedAt,runReport,terminal,errors.toString(),runError);
            }catch(Exception uncertain){android.util.Log.w("PccDailyRoute","每日完成證據未持久確認，保持待確認且不重送",uncertain);}
            running = false; stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(FlowerFarmService.this);
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
            if (reason.isEmpty()) {
                // Starting this work explicitly authorizes one native takeover-stop.
                // The fresh lease and stop/dialog acknowledgement precede all GPS writes.
                android.util.Log.i("PikminFarmStartup", "ready pid=" + fix.pid + " frame=" + fix.at);
                return fix;
            }
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
        EmulatorDailyArrivalGuard dailyArrival=dailyRouteWork==null?null:new EmulatorDailyArrivalGuard(deadline);
        while (!stopping && SystemClock.elapsedRealtime() < deadline) {
            if(routeSession==null)checkPlanting();
            FarmLocation fix = null;
            if(routeSession!=null) {
                routeSession.keepAlive();
                long readStarted=SystemClock.elapsedRealtime();
                RouteObservation observation=GpsModuleGateway.routeObservation(this,sessionPid);
                if(dailyArrival!=null){
                    long now=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis();
                    android.util.Log.i("PccDailyArrival",EmulatorDailyArrivalGuard.diagnostics(observation,wall,now,now-readStarted));
                    if(dailyArrival.observe(observation,sessionPid,checkpoint.account,plantingRequestedAt,plantingConfirmed,
                        point.latitude,point.longitude,wall,now,flightWall,flightElapsed))return observation.location;
                    // Wait under the existing arrival bound; no fly/START/WALK replay.
                    Thread.sleep(500);continue;
                }
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
    private EmulatorRouteRemainder emulatorContinuation;
    private int emulatorOriginalTotal;
    private static final class EmulatorLowPetals extends Exception {
        final EmulatorRouteRemainder remainder;
        EmulatorLowPetals(EmulatorRouteRemainder r){super("owned ordinary petals low or unavailable; controlled pause only");remainder=r;}
    }
    private int emulatorReached(int segment){return emulatorContinuation==null?segment:emulatorContinuation.originalReached(segment);}
    private void runRoute(List<Point> points,String pid)throws Exception {
        emulatorOriginalTotal=points.size();emulatorContinuation=null;
        for(int round=1;round<=4&&!stopping;round++){
            try{runRouteSegment(points,pid);return;}
            catch(EmulatorLowPetals low){
                runReport.closeWindow(System.currentTimeMillis());FarmReports.save(this,runReport);
                android.content.SharedPreferences journal=getSharedPreferences("emulator_petal_handoff",0);
                String key="stop_"+routeSession.token;
                String intent="v1\t"+pid+"\t"+checkpoint.account+"\t"+routeSession.token+"\t"+plantingRequestedAt+"\n"+low.remainder.encode();
                if(journal.contains(key)||!journal.edit().putString(key,intent).commit()||!intent.equals(journal.getString(key,"")))
                    throw new IllegalStateException("Unknown petal stop intent retained; never replay");
                // Original native transaction persists stop-pending before its
                // RPC. OFF cannot replay a pending native stop or result click.
                PlantingControlGateway.setEnabled(false);
                plantingConfirmed=false;
                EmulatorPetalRouteHandoff handoff=new EmulatorPetalRouteHandoff(this,routeSession,plantingRequestedAt,low.remainder);
                long until=SystemClock.elapsedRealtime()+120000;
                boolean closed=false;
                while(!stopping&&SystemClock.elapsedRealtime()<until){
                    status("花瓣暫停 · 已暫停GPS，等待原生停止與結算");routeSession.heartbeat("hold");
                    try{handoff.requireClosed();closed=true;break;}catch(IllegalArgumentException pending){}
                    Thread.sleep(500);
                }
                if(stopping)return;
                if(!closed)throw new IllegalStateException("換瓣前原生結算未閉合，保留原輪且不重啟");
                if(round==4)throw new IllegalStateException("本段四輪普通花瓣工作已結束；安全略過剩餘路線");
                handoff.closePrior();
                if(dailyRouteWork!=null)dailyRouteWork.priorClosed(this,routeSession,plantingRequestedAt,handoff.next);
                routeSession=handoff.next; // retain partial-open cleanup handle BEFORE effect
                if(!dailyRouteWindow())return;
                handoff.openNext();
                RouteState.StopGate gate=new RouteState.StopGate();boolean ready=false;
                until=SystemClock.elapsedRealtime()+60000;
                while(!stopping&&SystemClock.elapsedRealtime()<until){
                    status("換瓣準備 · 核對新輪原生停止回報");
                    try{String[] frame=routeSession.heartbeatStopStatus();
                        if(gate.observe(new RouteState(String.join("\t",frame),pid,System.currentTimeMillis(),routeSession.token,"prepare"))){ready=true;break;}
                    }catch(IllegalArgumentException|IllegalStateException pending){gate=new RouteState.StopGate();}
                    Thread.sleep(500);
                }
                if(stopping)return;
                if(!ready)throw new IllegalStateException("新輪準備未確認，未開始種花或行走");
                routeSession.heartbeat("hold");
                Intent actual=routeSession.status();
                RouteObservation held=GpsModuleGateway.routeObservation(this,pid);held.require(pid,checkpoint.account,plantingRequestedAt,false);
                HarvestRoute remaining;
                try {
                    remaining=low.remainder.resume(held.location.provider,SystemClock.elapsedRealtime(),!actual.getBooleanExtra("is_route_active",true));
                } catch (RuntimeException rejected) {
                    // Post-rejection values only; no timestamps/locations synthesized.
                    JoystickReadiness fix=held.location.provider;
                    long at=SystemClock.elapsedRealtime();
                    android.util.Log.i("PccResumeRejected","paused="+!actual.getBooleanExtra("is_route_active",true)
                        +"; providerFresh="+(fix!=null&&fix.fresh(at))
                        +"; fixAgeMs="+(fix==null?-1:at-fix.fixMs)
                        +"; fixDeltaMs="+(fix==null?-1:fix.fixMs-low.remainder.fixMs)
                        +"; anchorAgeMs="+(at-low.remainder.fixMs)
                        +"; distanceMeters="+(fix==null?-1:HarvestRoute.distance(new HarvestRoute.Point(low.remainder.latitude,low.remainder.longitude),new HarvestRoute.Point(fix.latitude,fix.longitude))));
                    throw rejected;
                }
                emulatorContinuation=low.remainder;points=new ArrayList<>();
                for(HarvestRoute.Point p:remaining.points)points.add(new Point(p.lat,p.lng));
                // New native lease chooses/validates ordinary stock before
                // START. Existing arrival/START/read-back/pre-WALK gates remain.
            }
        }
    }
    private void runRouteSegment(List<Point> points,String pid)throws Exception {
        StringBuilder raw=new StringBuilder();for(Point p:points)raw.append(p.latitude).append(',').append(p.longitude).append('\n');
        HarvestRoute route=HarvestRoute.coordinates(raw.toString());
        Point first=points.get(0);long wall=System.currentTimeMillis(),elapsed=SystemClock.elapsedRealtime();
        status(dailyRouteWork==null?"沿路採果 · 前往首點":"每日種花 · 前往路線起點");
        if(!dailyRouteWindow())return;
        if(emulatorContinuation==null)GpsModuleGateway.flyTo(this,first.latitude+","+first.longitude);
        // Resumed segment begins at actual held provider; never teleport to a saved coordinate.
        if(waitArrival(first,pid,wall,elapsed)==null){if(stopping)return;throw new IllegalStateException("首點未到達");}
        if(stopping)return;
        if(!dailyRouteWindow())return;
        plantingRequestedAt=System.currentTimeMillis();
        if(dailyRouteWork!=null)dailyRouteWork.roundStarted(routeSession,plantingRequestedAt);
        PlantingControlGateway.setEnabled(true);
        if(!waitPlanting())return;
        if(waitArrival(first,pid,wall,elapsed)==null){if(stopping)return;throw new IllegalStateException("種花後首點定位失效");}
        HarvestRouteProgress progress=new HarvestRouteProgress(route,dailyRouteWork!=null);

        checkpoint.uncertain=true;CheckpointStore.save(this,checkpoint);
        long routeWindowStarted=System.currentTimeMillis();
        runReport.openWindow(routeWindowStarted);FarmReports.save(this,runReport);
        routeHarvestSummary=new RouteHarvestSummary(routeWindowStarted);
        final EmulatorRouteFixListener[] callbackFixes=new EmulatorRouteFixListener[1];
        try {
        routeSession.walk(route,()->{
            if(!dailyRouteWindow())throw new IllegalStateException("Daily route window closed before WALK");
            long baselineStart=SystemClock.elapsedRealtime();
            // Read-only farmRead, NOT RouteSession.root/GPS shell flock. This
            // runs within the already-held provider actor; never nest fences.
            RouteObservation baseline=dailyRouteWork==null?GpsModuleGateway.routeObservation(this,pid):
                EmulatorDailyWalkBaseline.await(()->GpsModuleGateway.routeObservation(this,pid),System::currentTimeMillis,
                    SystemClock::elapsedRealtime,Thread::sleep,(frame,at,now,cost)->android.util.Log.i("PccDailyWalkBaseline",
                        EmulatorDailyArrivalGuard.diagnostics(frame,at,now,cost)),pid,checkpoint.account,plantingRequestedAt);
            baseline.require(pid,checkpoint.account,plantingRequestedAt);
            if(dailyRouteWork!=null)dailyRouteWork.captureStock(routeSession,baseline,plantingRequestedAt);
            FarmLocation before=baseline.location;
            long baselineNow=SystemClock.elapsedRealtime();
            if(!before.valid||!pid.equals(before.pid)||!before.fresh(System.currentTimeMillis(),baselineNow))
                throw new IllegalStateException("路線起點定位失效");
            progress.observeProvider(before.provider,baselineNow);
            if(dailyRouteWork==null)callbackFixes[0]=new EmulatorRouteFixListener(this,before.provider,baselineNow);
            if(!progress.failure().isEmpty())throw new IllegalStateException(progress.failure());
            android.util.Log.i("PikminRoutePrepare","walkBaselineMs="+(baselineNow-baselineStart)
                    +" baselineAgeMs="+(baselineNow-before.provider.fixMs));
        }); // One WALK only; progress is seeded before the effect, never afterwards.
        long started=SystemClock.elapsedRealtime();
        long walkSeconds=dailyRouteWork==null?route.estimatedSeconds()+90:
            EmulatorDailyWalkBudget.seconds(route.estimatedSeconds(),points.size());
        long deadline=started+walkSeconds*1000, checked=started, lastUi=0;
        RouteLoopTiming timing=new RouteLoopTiming(SystemClock::elapsedRealtime);
        // Exit decisions are named through timing.exit(...)/exitOutsideLoop(...)/failed(e); the finally seals the
        // diagnostics after the WHOLE walk (loop, pause, endpoint STATUS, endpoint arrival), not just the loop.
        boolean endpointReached=false;
        try {
        while(!stopping&&SystemClock.elapsedRealtime()<deadline) {
            if(!dailyRouteWindow()){timing.exit("daily-route-window-closed");break;}
            timing.beginLoop();   // this loop's sample starts as "unknown"; only a judged fix fills it (S1)
            try {
            // Every segment is timed through RouteLoopTiming.timed: a throw or a timeout still records its duration and names the failed stage.
            // Finish the original STATUS actor and valid two-second reply FIRST.
            // Then read ONE complete fresh native/provider frame under the same
            // shell flock. Never age a pre-effect frame across actor exit/reply.
            timing.timed("status",()->{if(dailyRouteWork!=null)routeSession.beginDailyOverlappedStatus();else routeSession.beginOverlappedStatus();return null;});
            Intent sample=timing.timed("status-reply",routeSession::finishOverlappedStatus);long now=SystemClock.elapsedRealtime();
            RouteObservation observation=timing.timed("read",routeSession::takeWalkingObservation);
            boolean frameRefreshed=false;
            if(dailyRouteWork!=null&&FarmLocation.distance(observation.location.provider.latitude,
                    observation.location.provider.longitude,sample.getDoubleExtra("lat",Double.NaN),sample.getDoubleExtra("lng",Double.NaN))>10){
                // Invalid identity/planting/fix is not a synchronization retry.
                observation.require(pid,checkpoint.account,plantingRequestedAt);
                if(!observation.location.fresh(System.currentTimeMillis(),SystemClock.elapsedRealtime()))
                    throw new IllegalStateException("provider-recheck-first-frame-expired");
                frameRefreshed=true;
                RouteSession.ReplacementObservation replacement=timing.timed("provider-recheck",routeSession::replaceDailyWalkingObservation);
                sample=replacement.status;
                observation=replacement.observation;
                // Original stock, identity, freshness, exact 10m comparison and
                // progress/jump checks below apply to the NEW complete frame.
            }
            FarmLocation fix;
            long verifiedAt;
            for(;;){
            timing.stage("require");
            if(dailyRouteWork!=null)dailyRouteWork.captureStock(routeSession,observation,plantingRequestedAt);
            if(EmulatorDailyCapturedExpiry.eligible(dailyRouteWork!=null,frameRefreshed,observation,
                    pid,checkpoint.account,plantingRequestedAt,System.currentTimeMillis(),SystemClock.elapsedRealtime())){
                frameRefreshed=true; // BEFORE the same-STATUS permit/I/O; failures cannot retry.
                android.util.Log.i("PccCapturedExpiry","one-new-status-frame-requested; no-old-frame-adoption");
                RouteSession.ReplacementObservation replacement=timing.timed("provider-recheck",routeSession::replaceDailyWalkingObservation);
                sample=replacement.status;
                observation=replacement.observation;
                continue; // New complete frame redoes capture/require/stock/freshness/10m/progress.
            }
            EmulatorOwnedStockObservation.Result petal=EmulatorOwnedStockObservation.observe(observation.ownedCapabilities,observation.ownedJournal,observation.ownedPetals,
                pid,checkpoint.account,routeSession.token,plantingRequestedAt,System.currentTimeMillis());
            // A bound UNKNOWN snapshot is not permission to keep walking. Use
            // the same bounded pause/closed-result/fresh-selection boundary.
            // Unbound, stale or changed identity must still abort; unknown
            // stock never becomes a low-petal acceptance artifact.
            if(petal.pauseRequired()){
                observation.require(pid,checkpoint.account,plantingRequestedAt,false);
                // Adopt this already-observed fresh real provider BEFORE PAUSE
                // admission costs; the unchanged progress checks still apply.
                progress.observeProvider(observation.location.provider,SystemClock.elapsedRealtime());
                if(!progress.failure().isEmpty())throw new IllegalStateException(HarvestRouteProgress.label(progress.failure())+" ("+progress.failure()+")");
                routeSession.pause();
                routeSession.beginOverlappedStatus();
                RouteObservation held=GpsModuleGateway.routeObservation(this,pid);
                Intent paused=routeSession.finishOverlappedStatus();
                held.require(pid,checkpoint.account,plantingRequestedAt,false);
                if(dailyRouteWork!=null)dailyRouteWork.captureStock(routeSession,held,plantingRequestedAt);
                FarmLocation heldFix=held.location;long at=SystemClock.elapsedRealtime();
                if(!heldFix.fresh(System.currentTimeMillis(),at)||FarmLocation.distance(heldFix.provider.latitude,heldFix.provider.longitude,paused.getDoubleExtra("lat",Double.NaN),paused.getDoubleExtra("lng",Double.NaN))>10)
                    throw new IllegalStateException("換瓣暫停位置未核對，未接續路線");
                boolean confirmed=!paused.getBooleanExtra("is_route_active",true);
                EmulatorRouteRemainder remainder=emulatorContinuation==null?EmulatorRouteRemainder.capture(route,progress,heldFix.provider,at,confirmed):
                    EmulatorRouteRemainder.captureNext(emulatorContinuation,route,progress,heldFix.provider,at,confirmed);
                throw new EmulatorLowPetals(remainder);
            }
            try {
                observation.require(pid,checkpoint.account,plantingRequestedAt);
            } catch (RuntimeException rejected) {
                // Read-only diagnostics after rejection. Ages measured here are
                // post-read ages, not the earlier parser's validation time.
                FarmLocation rejectedFix=observation.location;
                long rejectedWall=System.currentTimeMillis(),rejectedElapsed=SystemClock.elapsedRealtime();
                android.util.Log.i("PccRouteRejected","locationValid="+rejectedFix.valid
                    +"; nativeAgeMs="+(rejectedWall-rejectedFix.at)
                    +"; rawAgeMs="+(rejectedElapsed-rejectedFix.rawAt)
                    +"; providerAgeMs="+(rejectedFix.provider==null?-1:rejectedElapsed-rejectedFix.provider.fixMs)
                    +"; plantingValid="+observation.planting.valid);
                // Persist bounded numeric/bool diagnostics before rethrow;
                // no exception message, coordinates, account or token.
                String plantingMode="on".equals(observation.planting.mode)?"on":"off".equals(observation.planting.mode)?"off":"observe".equals(observation.planting.mode)?"observe":"unknown";
                timing.exit("require:location="+rejectedFix.valid+",nativeAge="+(rejectedWall-rejectedFix.at)
                    +",rawAge="+(rejectedElapsed-rejectedFix.rawAt)+",providerAge="+(rejectedFix.provider==null?-1:rejectedElapsed-rejectedFix.provider.fixMs)
                    +",plantingBytes="+observation.plantingPacketBytes+",planting="+observation.planting.valid+",plantingAge="+(observation.planting.at<=0?-1:rejectedWall-observation.planting.at)
                    +",mode="+plantingMode+",active="+observation.planting.planting+",owned="+observation.planting.owned);
                throw rejected; // Identical original failure; never retry/adopt.
            }
            observeOwnedStock(observation);
            fix=observation.location;
            // Validate after BOTH reads; progress uses the actual GPS fix timestamp, never STATUS receipt time.
            verifiedAt=SystemClock.elapsedRealtime();
            timing.stage("verify");
            if(!fix.valid||!pid.equals(fix.pid)||!fix.fresh(System.currentTimeMillis(),verifiedAt)
                    ||(dailyRouteWork!=null&&verifiedAt-fix.provider.fixMs>HarvestRouteProgress.SAMPLE_AGE_MS)){
                if(EmulatorDailyConsumptionRefresh.eligible(dailyRouteWork!=null,frameRefreshed,observation,
                        pid,checkpoint.account,plantingRequestedAt,System.currentTimeMillis(),verifiedAt)){
                    frameRefreshed=true; // BEFORE I/O, including a failed read.
                    RouteSession.ReplacementObservation replacement=timing.timed("provider-recheck",routeSession::replaceDailyWalkingObservation);
                sample=replacement.status;
                observation=replacement.observation;
                    // Re-run identity, planting, stock and final freshness on
                    // the NEW complete frame. Never adopt the expired one.
                    continue;
                }
                throw new IllegalStateException("沿路遊戲定位失效："+fix.readiness+"；state="+fix.state
                        +"；nativeAgeMs="+(System.currentTimeMillis()-fix.at)
                        +"；rawAgeMs="+(verifiedAt-fix.rawAt)+"；providerAgeMs="+(fix.provider==null?-1:verifiedAt-fix.provider.fixMs));
            }
            break;
            }
            timing.stage("provider-match");
            double providerMismatchMeters=FarmLocation.distance(fix.provider.latitude,fix.provider.longitude,sample.getDoubleExtra("lat",0),sample.getDoubleExtra("lng",0));
            // Distances and monotonic intervals only: no coordinates/accounts.
            // STATUS has no provider-fix timestamp; receipt age is NOT fix age.
            if(providerMismatchMeters>10){
                String providerMismatchDetail="distanceMeters="+providerMismatchMeters
                    +"; statusReceiptAgeMs="+(verifiedAt-now)
                    +"; providerAgeMs="+(verifiedAt-fix.provider.fixMs)
                    +"; providerVsStatusReceiptMs="+(fix.provider.fixMs-now);
                android.util.Log.i("PccProviderMatch","rejected "+providerMismatchDetail);
                throw new IllegalStateException("GPS供應來源與路線回報不符；"+providerMismatchDetail);
            }
            timing.stage("progress");
            boolean end=dailyRouteWork==null?callbackFixes[0].adopt(progress,fix.provider,verifiedAt):progress.observeProvider(fix.provider,verifiedAt);
            timing.observed(progress.detail(),progress.failure().isEmpty());
            if(!progress.failure().isEmpty()){
                // Short operator wording on the UI line; gap/age/move detail and loop timing go to the report and log.
                timing.exit("progress:"+progress.failure());
                throw new IllegalStateException(HarvestRouteProgress.label(progress.failure())+"（"+progress.failure()+"）");
            }
            int priorPosition=currentPosition;
            currentPosition=emulatorReached(progress.reached());runReport.attempted=currentPosition;
            // Provider checkpoints are not per-flower game arrival or fruit success.
            if(currentPosition!=priorPosition||verifiedAt-lastUi>=2000){
                refreshRouteDispatchDiagnostics();
                refreshRouteHarvestSummary(false);
                status(dailyRouteWork==null?"沿路採果 · 19 km/h · "+currentPosition+"/"+emulatorOriginalTotal:"每日種花 · 19 km/h · "+progress.detail());lastUi=verifiedAt;
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
        runReport.completed=emulatorOriginalTotal;runReport.arrived=0;
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
            runReport.routeDiag=timing.diag(emulatorReached(progress.reached()),emulatorOriginalTotal);
            refreshRouteDispatchDiagnostics();
            runReport.routeDispatchDiag=routeDispatchCounts;
            android.util.Log.i("PikminRoute","summary "+runReport.routeDiag);
        }
        } finally { if(callbackFixes[0]!=null)callbackFixes[0].close(); }
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
        WorkStatus.write(this, GpsWorkLock.FLOWER_FARM, state, currentPosition, currentTotal, 0, 0, 0, detail);
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
        String title="Pikmin "+EmulatorCityPlantingLabels.title(dailyRouteWork!=null);
        String channel=EmulatorCityPlantingLabels.channel(dailyRouteWork!=null,CHANNEL);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) manager.createNotificationChannel(new NotificationChannel(channel, title, NotificationManager.IMPORTANCE_LOW));
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 154,
                new Intent(this, MainActivity.class).putExtra("open_flower_farm", dailyRouteWork==null)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        PendingIntent stop = PendingIntent.getService(this, 155, new Intent(this, FlowerFarmService.class).setAction(STOP), flags);
        return ControlNotifications.grouped(this,new Notification.Builder(this, channel).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title).setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
                .setContentIntent(open).setCategory(Notification.CATEGORY_PROGRESS)
                .setProgress(currentTotal, currentPosition, currentTotal == 0)
                .addAction(android.R.drawable.ic_menu_view, "查看進度", open)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止採果", stop));
    }
    private void collectReport() {
        if(dailyRouteWork!=null){if(runReport!=null)FarmReports.save(this,runReport);return;} // Real lifecycle only, no harvesting.
        if (runReport == null) return;
        FarmReports.collect(this, runReport);
        reportCounts = runReport.counts() + (runReport.incomplete ? "（紀錄不完整）" : "");
    }
    private void refreshRouteDispatchDiagnostics() {
        if(dailyRouteWork!=null)return; // No special harvesting or harvest-result certification in a daily job.
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
        if(dailyRouteWork!=null)return; // No special harvesting or harvest-result certification in a daily job.
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
        try {
            NotificationManager manager = getSystemService(NotificationManager.class);
            PendingIntent open = PendingIntent.getActivity(this, 156,
                    new Intent(this, MainActivity.class).putExtra("open_flower_report", dailyRouteWork==null)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            String title = EmulatorCityPlantingLabels.title(dailyRouteWork!=null)+" · " + WorkProgress.label(runReport.terminal);
            String text = (routeMode?"通過路線節點 ":"完成停靠 ") + runReport.completed + "/" + runReport.total + " · " + reportCounts;
            manager.notify(156, new Notification.Builder(this, EmulatorCityPlantingLabels.channel(dailyRouteWork!=null,CHANNEL)).setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true).setContentIntent(open).setCategory(Notification.CATEGORY_STATUS).build());
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

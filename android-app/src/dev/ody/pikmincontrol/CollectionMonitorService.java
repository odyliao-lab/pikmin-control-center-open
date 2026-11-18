package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Keeps explicit collection choices armed. Native ticks decide each actual claim. */
public final class CollectionMonitorService extends Service {
    private volatile boolean stopped;
    private Thread worker;
    private String settingsPid="";
    private String rebindPid="";
    private final Object signal=new Object();
    private long generation;
    private final MonitorSchedule cadence=new MonitorSchedule();
    private MonitorSchedule.Result result=MonitorSchedule.Result.WAITING;
    private static volatile CollectionMonitorService active;
    private static volatile boolean mainVisible;
    static void visibility(boolean visible) {mainVisible=visible;wake();}
    private static void wake() {
        CollectionMonitorService service=active;
        if(service!=null)synchronized(service.signal){service.generation++;service.signal.notifyAll();}
    }
    private static final String CHANNEL="collection-monitor";
    static boolean desired(Context c,String key) {
        return (key.equals("reward")?"all":"on").equals(AutomationPreferences.value(c,key,"off"));
    }
    static boolean pendingOff(Context c,String key){return c.getSharedPreferences(CHANNEL,0).getBoolean("off-"+key,false);}
    static void markOff(Context c,String key,boolean pending) {
        if(!c.getSharedPreferences(CHANNEL,0).edit().putBoolean("off-"+key,pending).commit())throw new IllegalStateException("無法保存停止同步狀態");
    }
    static boolean wanted(Context c) {
        boolean feeding=false;
        if(c.getSharedPreferences("feeding",0).contains("config")) {
            FeedingConfig f=FeedingGateway.load(c);feeding=f.enabled||f.collect;
        }
        return feeding || desired(c,"nearby") || desired(c,"reward") || desired(c,"nectar")
            || pendingOff(c,"reward") || pendingOff(c,"nectar") || pendingOff(c,"nearby");
    }
    static String waiting(Context c) {
        return c.getSharedPreferences(CHANNEL,0).getString("waiting","等待核心與遊戲資料");
    }
    static String display(Context c,String key,String observation) {
        if(!desired(c,key)&&pendingOff(c,key))return "已選擇關閉 · 等待核心同步停止";
        return CollectionPreference.summary(desired(c,key),waiting(c),observation);
    }
    private void record(String message) {if(!message.equals(waiting(this)))getSharedPreferences(CHANNEL,0).edit().putString("waiting",message).apply();}
    private void recordNearby(String message) {getSharedPreferences(CHANNEL,0).edit().putString("waiting-nearby",message).apply();}
    static String displayNearby(Context c,NearbyState state) {
        boolean enabled="on".equals(AutomationPreferences.value(c,"nearby","armed".equals(state.mode)&&!state.busy?"on":"off"));
        if(!enabled && pendingOff(c,"nearby"))return "已選擇關閉 · 等待安全同步停止";
        String message=waiting(c);
        if(message.isEmpty())message=c.getSharedPreferences(CHANNEL,0).getString("waiting-nearby","");
        return NearbyPreference.summary(enabled,message,state);
    }
    static void sync(Context c) {
        wake();
        Intent i=new Intent(c,CollectionMonitorService.class);
        if(!wanted(c)){c.stopService(i);return;}
        try {if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
        catch(RuntimeException e) {c.getSharedPreferences(CHANNEL,0).edit().putString("waiting","請開啟控制中心以恢復監控").apply();}
    }
    @Override public int onStartCommand(Intent i,int flags,int id) {
        if(!wanted(this)){stopSelf();return START_NOT_STICKY;}
        NotificationManager n=getSystemService(NotificationManager.class);
        n.createNotificationChannel(new NotificationChannel(CHANNEL,"自動領取監控",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,159,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notice=ControlNotifications.grouped(this,new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("日常自動化監控中").setContentText("依已保存的開關等待遊戲資料")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true));
        try {if(Build.VERSION.SDK_INT>=34)startForeground(159,notice,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(159,notice);}
        catch(RuntimeException e){record("請允許背景監控後重試");stopSelf();return START_NOT_STICKY;}
        ControlNotifications.refresh(this);
        active=this;wake();
        if(worker==null){worker=new Thread(this::loop,CHANNEL);worker.start();}
        return START_STICKY;
    }
    private void loop() {
        while(!stopped && wanted(this)) {
            long ticket; synchronized(signal){ticket=generation;}
            result=MonitorSchedule.Result.WAITING;
            try {
                check();
                if(!stopped && !rebindPid.isEmpty()) {
                    String pid=rebindPid;rebindPid="";
                    if(SettingsRestoreJob.restoreForPid(this,pid))settingsPid=pid;
                    else {rebindPid=pid;record("部分設定尚待新遊戲程序核對，持續重試");}
                }
            } catch(Exception e){result=MonitorSchedule.Result.FAILED;record("核心或權限尚未就緒，持續重試");}
            long delay=cadence.next(result,mainVisible,pendingOff(this,"reward")||pendingOff(this,"nectar")||pendingOff(this,"nearby"),ReadOnlyQueryPolicy.enabled(this));
            try {synchronized(signal){if(!stopped&&ticket==generation)signal.wait(delay);}}
            catch(InterruptedException e){break;}
        }
        stopSelf();
    }
    private void check() throws Exception {
        if(UpdatePreparation.blocking(this)&&!UpdatePreparation.restore(this)) {
            result=MonitorSchedule.Result.BUSY;record(UpdatePreparation.status(this));return;
        }
        if(!ReadOnlyQueryPolicy.enabled(this) && !GpsWorkLock.anyHeld(this) && !NearbyWorkState.pending(this) && !WorkStatus.blocked(this)) {
            String pid=CoreUpdater.runtimePid(this,CoreUpdater.pair(this));
            if(!pid.isEmpty() && !pid.equals(settingsPid)) {
                if(!SettingsRestoreJob.restoreForPid(this,pid))rebindPid=pid;
                settingsPid=pid;
            }
        }
        boolean held=false;
        try {
        // Temporary batch/route/installer overrides own the same lease. Do not
        // re-arm a paused control while those transactions are still running.
        if(GpsWorkLock.anyHeld(this)||NearbyWorkState.pending(this)){result=MonitorSchedule.Result.BUSY;record("等待目前工作完成後同步");return;}
        GpsWorkLock.acquire(this,"collection-settings");held=true;
        // Share the settings transaction with choose/stop: a late check cannot re-arm after STOP.
        synchronized(AutomationPreferences.class) {
            if(stopped || !wanted(this))return;
            // Explicit OFF does not require a running game. Never race another work's lease.
            if(pendingOff(this,"nearby") && !desired(this,"nearby")) {
                String reason=NearbyGateway.reconcile(false);
                recordNearby(reason);
                if(reason.isEmpty())markOff(this,"nearby",false);
            }
            if(CoreInstallWizard.preparing(this)||CoreUpdater.running||CoreUpdater.pending(this)||SetupDeployment.pending(this)
                ||UpdateInstaller.blocking(this)||WorkStatus.stoppingAll||WorkStatus.blocked(this)) {record("等待停止或核心安裝完成");return;}
            CorePair pair=CoreUpdater.pair(this);
            String[] controls;
            if(ReadOnlyQueryPolicy.enabled(this)) {
                // Fresh read inside the existing settings transaction; a new PID
                // defers restore until the lease has been released.
                // Before/after PID brackets all reports and controls; never share UI evidence.
                CollectionObservation observation=CollectionObservation.parse(
                    BoundedCommand.root(CollectionObservation.command(),6,32768),pair,System.currentTimeMillis());
                if(observation.pid.isEmpty()){record("等待遊戲載入配對核心");return;}
                if(!observation.pid.equals(settingsPid)) {
                    // Defer restore until this transaction releases its lease.
                    // restoreForPid rechecks the current PID before/after writes.
                    rebindPid=observation.pid;
                    settingsPid=observation.pid;
                    // A feeding restore failure must not block independently proven collection/nearby controls.
                }
                controls=observation.controls;
            } else {
                String runtime=CoreUpdater.runtime(this,pair);
                if(!"遊戲已載入配對核心".equals(runtime)){record("等待遊戲載入配對核心");return;}
                controls=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(
                "cat /data/local/tmp/pikmin-return-mode.txt 2>/dev/null || true",
                "cat /data/adb/modules/pikmin_nectar_rpc/nectar_control_mode.txt 2>/dev/null || true",
                "cat /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt 2>/dev/null || true"),4,4096),3);
            }
            // Verify installed payload only if a write is needed, not on every heartbeat.
            boolean reward=desired(this,"reward"),nectar=desired(this,"nectar");
            String rewardMode=reward?"all":"off",nectarMode=nectar?"auto":"diag";
            boolean writeReward=(reward||pendingOff(this,"reward"))&&CollectionPreference.needsWrite(controls[0],rewardMode);
            boolean writeNectar=(nectar||pendingOff(this,"nectar"))&&(CollectionPreference.needsWrite(controls[1],nectarMode)||CollectionPreference.needsWrite(controls[2],nectarMode));
            if(writeReward||writeNectar) {
                CoreUpdater.requireFiles(this,pair);
                if(stopped||WorkStatus.stoppingAll||UpdatePreparation.blocking(this)||CoreInstallWizard.preparing(this))return;
                if(writeReward)FeedingGateway.root(ReturnRewardModuleGateway.modeCommand(rewardMode));
                if(writeNectar)FeedingGateway.root(NectarModuleGateway.controlCommand(nectar));
            }
            if(pendingOff(this,"reward"))markOff(this,"reward",false);
            if(pendingOff(this,"nectar"))markOff(this,"nectar",false);
            if(desired(this,"nearby")) {
                try {recordNearby(NearbyGateway.reconcile(true));}
                catch(Exception unavailable){recordNearby("派遣設定尚未同步，持續重試");}
            }
            record(""); // Per-feature UI adds native observation / location waiting details.
            result=MonitorSchedule.Result.HEALTHY;
        }
        } finally {if(held)GpsWorkLock.release(this,"collection-settings");}
    }
    @Override public void onDestroy(){stopped=true;if(active==this)active=null;if(worker!=null)worker.interrupt();stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(this);super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}
}

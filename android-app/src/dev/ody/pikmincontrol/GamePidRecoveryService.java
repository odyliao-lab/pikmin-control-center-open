package dev.ody.pikmincontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Opt-in process guard. Never replays a batch, route, feed RPC or GPS command. */
public final class GamePidRecoveryService extends Service {
    private static final String CHANNEL="game-pid-recovery";
    private static final int NOTIFICATION_ID=158;
    private static final String PREFS="game_pid_recovery";
    private static final String GAME="com.nianticlabs.pikmin";
    private volatile boolean stopped;
    private boolean foreground;
    private Thread worker;
    private final GamePidRecoveryPolicy policy=new GamePidRecoveryPolicy();
    private CorePair pair;

    static void sync(Context c) {
        Intent intent=new Intent(c,GamePidRecoveryService.class);
        if(UpdateInstaller.blocking(c)||!ControlSettings.restartGameOnPidLoss(c)) { c.stopService(intent);return; }
        if(Build.VERSION.SDK_INT>=26)c.startForegroundService(intent);else c.startService(intent);
    }
    static String status(Context c) {
        return c.getSharedPreferences(PREFS,MODE_PRIVATE).getString("status","等待首次確認遊戲程序");
    }
    static void forgetBaseline(Context c) {
        c.getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove("bound_pid").remove("window_start")
                .remove("last_launch").remove("launches").putString("status","已關閉").commit();
    }
    private void record(String value) {
        getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString("status",value)
                .putLong("at",System.currentTimeMillis()).apply();
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"遊戲程序守護",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(this,158,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification notification=ControlNotifications.grouped(this,new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("遊戲復原監控中")
                .setContentText("遊戲異常時嘗試復原，不重送舊工作")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true));
        try {
            if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID,notification);
        } catch(RuntimeException error) {record("無法啟動常駐通知 · 程序守護未運作");stopSelf();return;}
        foreground=true;
        ControlNotifications.refresh(this);
        try(InputStream in=getAssets().open("core.properties")) {
            pair=new CorePair(new String(SetupBundle.read(in,4096),StandardCharsets.UTF_8));
        } catch(Exception invalid) {
            record("無法核對配對核心 · 程序守護未運作");stopSelf();return;
        }
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        policy.restore(prefs.getString("bound_pid",""));
        policy.restoreLimits(prefs.getLong("window_start",0),prefs.getLong("last_launch",0),prefs.getInt("launches",0));
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(!foreground || pair==null || !ControlSettings.restartGameOnPidLoss(this)) { stopSelf();return START_NOT_STICKY; }
        if(worker!=null)return START_STICKY;
        worker=new Thread(this::loop,"pikmin-pid-recovery");worker.start();
        return START_STICKY;
    }
    private void loop() {
        while(!stopped && ControlSettings.restartGameOnPidLoss(this)) {
            try { probeAndRecover(); }
            catch(Exception error) { record("程序檢查暫時失敗 · 15 秒後重試"); }
            try { Thread.sleep(15_000); } catch(InterruptedException interrupted) { break; }
        }
        stopSelf();
    }
    private static String reportRead(String file) {
        String path="/data/user/0/"+GAME+"/files/"+file;
        return "if test -e "+path+"; then cat "+path+" 2>/dev/null || echo __PCC_ERROR__; fi";
    }
    private void probeAndRecover() throws Exception {
        // The two PID reads bracket all four native reports; an inconsistent or
        // incomplete shell result never proves absence and cannot restart.
        String pidRead=OwnerGameProcess.watchdogCommand();
        BoundedCommand.Result read=BoundedCommand.root(ProbeSnapshot.command(
                "command -v pidof 2>/dev/null || true",pidRead,
                reportRead("automation_session.tsv"),reportRead("core_runtime.tsv"),
                reportRead("unified_runtime.tsv"),reportRead("core_capabilities.tsv"),
                pidRead),5,4096);
        String[] sampled=ProbeSnapshot.checkedSections(read,7);
        String[] fields={sampled[0],sampled[1],sampled[2],sampled[3],sampled[4],sampled[6]};
        if(fields[0].trim().isEmpty()) {record("無法確認程序查詢工具 · 不重啟遊戲");return;}
        String before=fields[1].trim(),after=fields[5].trim();
        if(before.startsWith("__PCC_") || after.startsWith("__PCC_")) {
            record("程序查詢失敗 · 不重啟遊戲");return;
        }
        if(fields[2].contains("__PCC_ERROR__") || fields[3].contains("__PCC_ERROR__") ||
                fields[4].contains("__PCC_ERROR__")) {
            record("核心回報讀取失敗 · 不重啟遊戲");return;
        }
        if(!before.equals(after) || (!before.isEmpty()&&!before.matches("[1-9][0-9]*"))) {
            record("程序變動中 · 等待下次確認");return;
        }
        boolean healthy=GamePidRecoveryProof.healthy(pair,fields,System.currentTimeMillis());
        boolean terminal=GamePidRecoveryProof.terminal(pair,sampled[5],before,System.currentTimeMillis());
        boolean busy=DispatchBatchService.isRunning() || FlowerFarmService.isRunning() ||
                GpsWorkLock.anyHeld(this) || !residualWorkSafe();
        if(SetupDeployment.pending(this) || CoreUpdater.pending(this) || UpdateInstaller.blocking(this) ||
                CoreInstallWizard.preparing(this) || WorkStatus.stoppingAll) {
            record("核心／安裝工作中 · 暫緩重啟遊戲");return;
        }
        GamePidRecoveryPolicy.Action action=policy.observe(before,healthy,true,busy,terminal,System.currentTimeMillis());
        SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        if(action==GamePidRecoveryPolicy.Action.REBIND) {
            prefs.edit().putString("bound_pid",policy.boundPid()).commit();
            SettingsRestoreJob.schedule(this);
            record("已重新綁定遊戲程序 PID "+policy.boundPid()+" · 排程核對已保存設定");return;
        }
        if(action==GamePidRecoveryPolicy.Action.WAIT_WORK) {
            if(DispatchBatchService.isRunning())DispatchBatchService.stop(this);
            if(FlowerFarmService.isRunning())FlowerFarmService.stop(this);
            record("遊戲程序異常 · 等待 GPS 工作安全停止／收尾");return;
        }
        if(action==GamePidRecoveryPolicy.Action.RATE_LIMIT) {record("重啟次數已達上限 · 稍後自動再檢查");return;}
        if(action==GamePidRecoveryPolicy.Action.COOLDOWN) {record("重啟冷卻中 · 稍後自動再檢查");return;}
        if(action!=GamePidRecoveryPolicy.Action.LAUNCH && action!=GamePidRecoveryPolicy.Action.RESTART) {
            if(terminal){record("目前程序核心／登入捕捉未完成 · 有限次復原觀察中");return;}
            if(before.isEmpty())record(policy.boundPid().isEmpty()?"等待首次確認遊戲程序":"遊戲程序未找到 · 觀察中");
            else if(policy.boundPid().isEmpty() && !healthy)
                record("等待遊戲回到前景，確認配對核心與帳號");
            return;
        }
        prefs.edit().putLong("window_start",policy.windowStart()).putLong("last_launch",policy.lastLaunch())
                .putInt("launches",policy.launches()).commit();
        if(stopped || UpdateInstaller.blocking(this) || !ControlSettings.restartGameOnPidLoss(this))return;
        if(DispatchBatchService.isRunning() || FlowerFarmService.isRunning() ||
                GpsWorkLock.anyHeld(this) || !residualWorkSafe()) {
            record("GPS 工作仍在收尾 · 暫緩重啟遊戲");return;
        }
        Intent launch=getPackageManager().getLaunchIntentForPackage(GAME);
        ComponentName resolved=launch==null?null:launch.getComponent();
        String component=resolved!=null && GAME.equals(resolved.getPackageName())?resolved.flattenToString():"";
        String command=GamePidRecoveryLaunch.command(action,component);
        if(command.isEmpty()){record("找不到可驗證的遊戲啟動入口 · 不重啟");return;}
        BoundedCommand.Result result=BoundedCommand.root(command,8,4096);
        record(GamePidRecoveryLaunch.accepted(result,component)
                ? (action==GamePidRecoveryPolicy.Action.RESTART?"已要求重啟遊戲 · 等待新程序":"已要求開啟遊戲 · 等待新程序")
                : "遊戲啟動命令未確認 · 稍後再檢查");
    }
    private boolean residualWorkSafe() throws Exception {
        // A durable error is not an active worker. Preserve all checkpoints,
        // ledgers and nearby-original state, but do not let them block the only
        // way to restore native acknowledgements. A route still needs proof
        // that its watchdog retired and shut down automation, not just age.
        if(!RouteSession.pending(this))return true;
        BoundedCommand.Result r=BoundedCommand.root("t=$(cat /data/local/tmp/pcc-route-owner 2>/dev/null); "
            +"case \"$t\" in ''|*[!0-9a-f-]*) exit 1;; esac; test ${#t} -eq 36 && "
            +"test \"$(cat /data/local/tmp/pcc-route-owner.done.$t 2>/dev/null)\" = \"$t\" && "
            +"test \"$(cat /data/local/tmp/pikmin-dispatch-mode.txt 2>/dev/null)\" = off && "
            +"test \"$(cat /data/local/tmp/pikmin-planting-mode.txt 2>/dev/null)\" = off && "
            +"test \"$(cat /data/local/tmp/pikmin-flower-farm-mode.txt 2>/dev/null)\" = off",3,1024);
        return r.ok && !r.timedOut && !r.truncated;
    }
    @Override public void onDestroy() {
        stopped=true;if(worker!=null)worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(this);super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) {return null;}
}

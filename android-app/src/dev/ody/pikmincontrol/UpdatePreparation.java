package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import java.io.IOException;

/** Durable APK-only pause. User settings, ledgers, route ownership and game are not erased. */
final class UpdatePreparation {
    static final InstallPreparationPolicy.WriteFence STEPS=new InstallPreparationPolicy.WriteFence();
    private static final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("apk_preparation",0);}
    static boolean blocking(Context c){return !prefs(c).getString("phase","").isEmpty();}
    static boolean running(){return running.get();}
    static String status(Context c){return prefs(c).getString("message","更新準備暫停中");}
    static boolean needsRestore(Context c) {
        if(PhoneUpdateFlow.holdRestore(c)||PhoneUpdateFlow.restoreExhausted(c))return false;
        return blocking(c) && ("restoring".equals(prefs(c).getString("phase",""))
            ||UpdateStore.installed(c)>=prefs(c).getLong("target",Long.MAX_VALUE));
    }
    private static void stage(Context c,String phase,String message)throws IOException {
        if(!prefs(c).edit().putString("phase",phase).putString("message",message).commit())
            throw new IOException("無法保存更新準備狀態；未繼續安裝");
    }
    static void prepare(Context c,long target)throws Exception {
        if(!running.compareAndSet(false,true))throw new IllegalStateException("更新準備仍在執行");
        try {InstallPreparationPolicy.run(new InstallPreparationPolicy.Backend(){
            public void begin()throws Exception {
                synchronized(GpsWorkLock.class){
                    if(UpdateInstaller.pending(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c)||WorkStatus.stoppingAll)
                        throw new IllegalStateException("請先完成已有核心／部署／安裝交易");
                    if(target<=UpdateStore.installed(c))throw new IllegalStateException("更新目標不比目前版本新");
                    STEPS.persistPause(()->{
                        if(!prefs(c).edit().putString("phase","pausing").putLong("target",target)
                            .putString("message","正在暫停自動功能，設定保留").commit())throw new IllegalStateException("暫停狀態未保存");
                    });
                }
                c.stopService(new Intent(c,GamePidRecoveryService.class));
            }
            public void pauseSteps()throws Exception {
                StepSyncJob.cancel(c);c.stopService(new Intent(c,StepService.class));
                if(!STEPS.drain(15_000))throw new IOException("步數寫入尚在返回，原ID及紀錄保留，請重試");
            }
            public void stopWork()throws Exception {
                DispatchBatchService.stop(c);FlowerFarmService.stop(c);
                long end=SystemClock.elapsedRealtime()+30_000;
                while((DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||GpsWorkLock.anyHeld(c))&&SystemClock.elapsedRealtime()<end)
                    Thread.sleep(200);
                requireIdle(c);
            }
            public void pauseControls()throws Exception {
                // Serialize against an already-admitted settings write, then read back OFF.
                synchronized(AutomationPreferences.class){
                    pauseNectar();ReturnRewardModuleGateway.setMode("off");
                    DispatchProbeGateway.stopBatch();DispatchProbeGateway.setFarmArmed(false);
                }
                FeedingGateway.pauseForInstall(c);
                synchronized(PlanterSettings.class){PlanterGateway.setMode("off");}
                // Only our flower worker may stop its planting/route. No blind OFF for manual planting or JoyStick.
            }
            public void verify()throws Exception {
                requireIdle(c);FeedingGateway.requirePausedForInstall();StopChecks.apk();
                if(!STEPS.drain(1))throw new IOException("步數仍在返回");
            }
            public void ready()throws Exception {stage(c,"ready","已暫停並核對，等待Android安裝確認；遊戲與JoyStick保持開啟");}
            public void failed(String stage)throws Exception {UpdatePreparation.stage(c,"blocked",stage+"未完成；設定及未確認操作保留，可重試或取消準備");}
        });}finally{running.set(false);}
    }
    private static void pauseNectar()throws Exception {
        // APK upgrades must also work before a core/game has ever created its control files.
        FeedingGateway.root("if test -f /data/adb/modules/pikmin_nectar_rpc/module.prop; then "
            +"printf 'diag\\n' > /data/adb/modules/pikmin_nectar_rpc/nectar_control_mode.txt && chmod 0644 /data/adb/modules/pikmin_nectar_rpc/nectar_control_mode.txt || exit 1; fi; "
            +"if test -e /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt; then printf 'diag\\n' > /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt && chmod 0644 /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt || exit 1; fi");
    }
    static void requireIdle(Context c) {
        GpsWorkLock.requireInstallIdle(c);
        if(NearbyWorkState.pending(c))throw new IllegalStateException("附近派遣原設定仍待恢復，請先至原工作診斷");
    }
    static void requireReady(Context c)throws Exception {
        if(!"ready".equals(prefs(c).getString("phase","")))throw new IOException("請先完成自動更新準備");
        requireIdle(c);FeedingGateway.requirePausedForInstall();StopChecks.apk();
    }
    /** First setup uses the same control pause and restore as an APK update. */
    static void prepareSetupCore(Context c)throws Exception {
        if(!PhoneUpdateFlow.active(c)||!PhoneUpdateFlow.setup(c))throw new IOException("缺少首次設定同意");
        if(!blocking(c))STEPS.persistPause(()->{
            if(!prefs(c).edit().putString("phase","pausing").putLong("target",UpdateStore.installed(c))
                .putString("message","正在準備核心，原設定保留").commit())throw new IllegalStateException("無法保存設定暫停");
        });
        c.stopService(new Intent(c,GamePidRecoveryService.class));
        StepSyncJob.cancel(c);c.stopService(new Intent(c,StepService.class));
        DispatchBatchService.stop(c);FlowerFarmService.stop(c);
        long until=SystemClock.elapsedRealtime()+30_000;
        while((DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||GpsWorkLock.anyHeld(c))
            &&SystemClock.elapsedRealtime()<until)Thread.sleep(200);
        prepareCore(c);
    }
    /** APK pause remains held across core preparation. Never discards ownership/UNKNOWN records. */
    static void prepareCore(Context c)throws Exception {
        if(!blocking(c)||!PhoneUpdateFlow.active(c))throw new IOException("缺少本次更新暫停紀錄");
        GpsWorkLock.requireCoreRecoveryIdle(c);
        if(NearbyWorkState.pending(c))throw new IOException("附近工作待恢復，未關閉遊戲");
        StepService.pauseForInstall(c);
        if(!STEPS.drain(15_000))throw new IOException("步數仍在返回，未關閉遊戲");
        synchronized(AutomationPreferences.class){
            pauseNectar();ReturnRewardModuleGateway.setMode("off");DispatchProbeGateway.stopBatch();DispatchProbeGateway.setFarmArmed(false);
        }
        FeedingGateway.pauseForInstall(c);
        synchronized(PlanterSettings.class){PlanterGateway.setMode("off");}
        PlantingControlGateway.setEnabled(false);PlantingControlGateway.setFlowerFarmSession(false);
        StopChecks.all();FeedingGateway.requirePausedForInstall();GpsWorkLock.requireCoreRecoveryIdle(c);
    }
    static void installedOrCancelled(Context c)throws IOException {
        if(blocking(c))stage(c,"restoring","等待配對核心與遊戲就緒後恢復原開關；不續跑舊批次");
        SettingsRestoreJob.schedule(c);
    }
    static void cancel(Context c)throws Exception {
        if(running()||UpdateInstaller.preparing)throw new IOException("準備仍在執行，請稍候");
        if(UpdateInstaller.pending(c))UpdateInstaller.cancel(c);
        installedOrCancelled(c);
        restore(c);
    }
    /** Called on worker/job only. Missing/failed native proof keeps the freeze; never starts the game. */
    static boolean restore(Context c) {
        if(PhoneUpdateFlow.holdRestore(c)||PhoneUpdateFlow.restoreExhausted(c))return false;
        if(!blocking(c))return true;
        if(running()||UpdateInstaller.preparing)return false;
        try {
            long target=prefs(c).getLong("target",Long.MAX_VALUE);
            boolean finished=UpdateStore.installed(c)>=target;
            if(!finished&&!"restoring".equals(prefs(c).getString("phase","")))return false;
            if(UpdateInstaller.pending(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c))return false;
            requireIdle(c);
            CorePair pair=CoreUpdater.pair(c);
            if(CoreUpdater.runtimePid(c,pair).isEmpty()) {
                stage(c,"restoring","原設定保留：等待遊戲載入本版配對核心；需要時請完成核心更新");return false;
            }
            if(!prefs(c).edit().remove("phase").putString("message","更新暫停已解除，依原設定重新核對；不重播舊工作").commit())return false;
            StepSyncJob.restore(c);CollectionMonitorService.sync(c);
            if(ControlSettings.restartGameOnPidLoss(c))GamePidRecoveryService.sync(c);
            return true;
        }catch(Exception unavailable){
            String detail=unavailable.getMessage()==null?unavailable.getClass().getSimpleName():unavailable.getMessage();
            if(detail.length()>180)detail=detail.substring(0,180);
            try{stage(c,"restoring","暫停保留，等待恢復："+detail);}catch(Exception ignored){}
            return false;
        }
    }
}

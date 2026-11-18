package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground-driven, durable single-target consent. No background install/reboot or replay. */
final class PhoneUpdateFlow {
    private static final AtomicBoolean running=new AtomicBoolean();
    private static final AtomicBoolean queued=new AtomicBoolean();
    private static final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private static volatile long nextCheck;
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("phone_update_flow",0);}
    static boolean setup(Context c){return "setup".equals(prefs(c).getString("mode",""));}
    static String phase(Context c){return prefs(c).getString("phase","");}
    static boolean active(Context c){String s=phase(c);return !s.isEmpty()&&!s.equals("complete")&&!s.equals("cancelled")&&!s.equals("superseded");}
    static boolean busy(){return running.get();}
    static boolean restoreExhausted(Context c){return active(c)&&"restore".equals(phase(c))&&prefs(c).getInt("restore_waits",0)>=24;}
    static boolean holdRestore(Context c){return active(c)&&!"restore".equals(phase(c));}
    static String manifest(Context c){return active(c)?prefs(c).getString("manifest",""):"";}
    static String status(Context c){return prefs(c).getString("message","");}
    static String action(Context c){return prefs(c).getString("action","");}
    /** Admit at most one round from a visible Activity; Activity destruction cannot interrupt root/session I/O. */
    static void requestAdvance(Context input){
        if(!active(input)||!queued.compareAndSet(false,true))return;
        Context c=input.getApplicationContext();
        worker.execute(()->{try{advance(c);}finally{queued.set(false);}});
    }
    private static void save(SharedPreferences.Editor e)throws IOException {
        if(!e.commit())throw new IOException("無法保存更新進度；未繼續操作");
    }
    private static void stage(Context c,String phase,String action,String message)throws IOException {
        save(prefs(c).edit().putString("phase",phase).putString("action",action).putString("message",message));
    }
    static synchronized void start(Context c,UpdateInfo consent)throws Exception {
        if(active(c)||running.get()||UpdateController.busy()||UpdateInstaller.pending(c)
            ||UpdatePreparation.blocking(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c))
            throw new IOException("已有更新或復原工作，請先完成目前進度");
        String raw=UpdateStore.prefs(c).getString("manifest","");UpdateInfo current=new UpdateInfo(raw);
        if(current.code!=consent.code||!current.sha256.equals(consent.sha256)||!current.newerThan(UpdateStore.installed(c)))
            throw new IOException("更新目標已變動，請重新確認");
        save(prefs(c).edit().clear().putString("manifest",raw).putLong("target",current.code)
            .putString("consent_id",java.util.UUID.randomUUID().toString()).putString("phase","download").putString("message","1/4 下載並驗證更新；尚未暫停功能"));
        nextCheck=0;
    }
    /** One explicit consent for this installed APK/core. Reuses the update transaction. */
    static synchronized void startSetup(Context c)throws Exception {
        if(active(c)||running.get()||UpdateController.busy()||UpdateInstaller.pending(c)
            ||UpdatePreparation.blocking(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c))
            throw new IOException("請先完成目前更新或核心交易，再繼續設定");
        CorePair pair=CoreUpdater.readPair(c,"core.properties"),effective=CoreUpdater.pair(c);
        long installed=UpdateStore.installed(c);
        if(pair.app!=installed||pair.code!=effective.code||!pair.sha.equals(effective.sha))
            throw new IOException("目前使用回退或不同配對，請先完成核心維修");
        save(prefs(c).edit().clear().putString("mode","setup").putString("consent_id",java.util.UUID.randomUUID().toString()).putLong("target",installed)
            .putInt("core_code",pair.code).putString("core_sha",pair.sha)
            .putString("app_sha",UpdateClient.digest(new java.io.File(c.getApplicationInfo().sourceDir)))
            .putString("phase","core").putString("message","2/3 正在檢查安裝與遊戲連線；同核心不重裝"));
        nextCheck=0;
    }
    /** Background observer may only review/restore. It can never install or reboot. */
    static boolean observing(Context c){
        String p=phase(c);return active(c)&&("core".equals(p)||"core-intent".equals(p)||("restore".equals(p)&&prefs(c).getInt("restore_waits",0)<24));
    }
    static String observationKey(Context c){return prefs(c).getString("consent_id","");}
    static void observe(Context c,String key){if(key!=null&&!key.isEmpty())advance(c,true,key);}
    static void retry(Context c)throws Exception {
        if(UpdateController.busy()||!running.compareAndSet(false,true))return;
        try{
        if("error".equals(phase(c))){
            save(prefs(c).edit().putInt("review_attempts",0));
            stage(c,prefs(c).getString("resume","core"),"","重新核對更新進度…");
        }
        if("restore".equals(phase(c)))save(prefs(c).edit().putInt("restore_waits",0));
        nextCheck=0;
        }finally{running.set(false);}
    }
    static void failure(Context c,Exception error) {
        String prior=phase(c);
        try {
            if(!prior.equals("error"))save(prefs(c).edit().putString("resume",prior));
            String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
            if(detail.length()>180)detail=detail.substring(0,180);
            stage(c,"error","retry","更新暫停："+detail+"。原設定與紀錄保留。");
        }catch(Exception ignored){/* Durable prior intent still prevents replay. */}
    }
    /** Cancellation before any installer admission is terminal, never a retryable failure. */
    static void downloadCancelled(Context c)throws IOException {
        if(active(c)&&"download".equals(phase(c))){
            stage(c,"cancelled","","已取消下載；原版本與設定保留");dismissReminder(c);
        }
    }
    static void apkEnded(Context c)throws IOException {
        if(active(c)&&UpdateStore.installed(c)<prefs(c).getLong("target",Long.MAX_VALUE))
            stage(c,"cancelled","","Android 安裝已取消或失敗；原版本與設定保留，正在核對恢復");
    }
    static void apkIntent(Context c)throws IOException {
        if(active(c))stage(c,"apk-intent","","2/4 Android 正在處理安裝");
    }
    static void requireReboot(Context c)throws Exception {
        if(!active(c)||busy())throw new IOException("更新狀態已變動，未重開");
        requirePair(c);
        if(!CoreInstallWizard.action(c).equals("reboot"))throw new IOException("目前不需要重開，請重新核對");
    }
    /** Invoked on a worker only while the app is visible. At most one bounded round per 5 s. */
    static void advance(Context input) {advance(input,false,"");}
    private static void advance(Context input,boolean observationOnly,String expectedKey) {
        Context c=input.getApplicationContext();
        if(!active(c)||android.os.SystemClock.elapsedRealtime()<nextCheck
            ||!running.compareAndSet(false,true))return;
        try {
            if(observationOnly&&(!expectedKey.equals(observationKey(c))||!observing(c)))return;
            if(UpdateController.busy()||UpdateInstaller.preparing)return;
            if(retireSupersededRestore(c))return;
            if("error".equals(phase(c)))return;
            if("restore".equals(phase(c))) {if(prefs(c).getInt("restore_waits",0)<24)finishRestore(c);return;}
            UpdateInfo target=setup(c)?null:new UpdateInfo(manifest(c));long installed=UpdateStore.installed(c);
            long targetCode=setup(c)?prefs(c).getLong("target",-1):target.code;
            boolean session=UpdateInstaller.pending(c);
            String core=installed==targetCode&&!phase(c).equals("apk-intent")&&!phase(c).equals("download")
                ?CoreInstallWizard.action(c):"";
            PhoneUpdatePolicy.Next next=PhoneUpdatePolicy.next(phase(c),installed,targetCode,
                UpdateController.ready(c),c.getPackageManager().canRequestPackageInstalls(),session,core);
            if(observationOnly && next!=PhoneUpdatePolicy.Next.REVIEW && next!=PhoneUpdatePolicy.Next.RESTORE && next!=PhoneUpdatePolicy.Next.WAIT)return;
            switch(next) {
                case DOWNLOAD:
                    stage(c,"download","","1/4 下載並驗證更新；尚未暫停功能");
                    UpdateController.download(c);break;
                case PERMISSION:
                    stage(c,"download","permission","1/4 更新檔已驗證，請允許此來源安裝；返回後自動繼續");break;
                case INSTALL_APK:
                    bindCore(c,target);
                    stage(c,"download","","2/4 正在暫停功能並準備安裝");
                    UpdateInstaller.begin(c);break;
                case WAIT_APK:
                    stage(c,phase(c),"","2/4 "+UpdateInstaller.pendingStatus(c));break;
                case VERIFY_APK:
                    if(!target.sha256.equals(UpdateClient.digest(new java.io.File(c.getApplicationInfo().sourceDir))))
                        throw new IOException("已安裝 APK 與同意的更新檔不同，停止自動接續");
                    requirePair(c);stage(c,"core","","3/4 APK 已更新，正在核對是否需要更新核心");break;
                case INSTALL_CORE:
                    requirePair(c);
                    // Read-back/stop is repeatable; no install intent until preparation succeeds.
                    CoreInstallWizard.preparing(c,true);
                    if(setup(c))UpdatePreparation.prepareSetupCore(c);else UpdatePreparation.prepareCore(c);
                    stage(c,"core-intent","",setup(c)?"2/3 正在安裝配對核心；中斷不重送":"3/4 正在安裝配對核心；中斷不重送");
                    CoreInstallWizard.closeGameAndInstall(c);break;
                case REBOOT:
                    stage(c,phase(c),"reboot",setup(c)?"2/3 安裝已排入，請選擇方便的時間重開手機":"3/4 核心等待重開手機；請選擇方便的時間重開");break;
                case OPEN_GAME:
                    stage(c,phase(c),"open-game","請開啟 Pikmin 進入地圖；系統會自動核對並通知結果");break;
                case REVIEW:
                    requirePair(c);
                    // The existing migration transaction is the sole review authority.
                    try{if(CoreUpdater.pending(c))CoreUpdater.review(c);
                        if(CoreUpdater.runtimePid(c,CoreUpdater.pair(c)).isEmpty())throw new IOException("尚未觀察到配對核心與 GPS 同程序載入");}
                    catch(Exception waiting){
                        int attempts=prefs(c).getInt("review_attempts",0)+1;
                        save(prefs(c).edit().putInt("review_attempts",attempts));
                        if(attempts>=24)throw waiting;
                    }
                    stage(c,phase(c),"open-game","等待遊戲連線；保持 Pikmin 前景，連線確認後會通知");break;
                case RESTORE:
                    requirePair(c);CoreUpdater.requireFiles(c,CoreUpdater.pair(c));
                    if(CoreUpdater.runtimePid(c,CoreUpdater.pair(c)).isEmpty())throw new IOException("配對核心尚未載入");
                    CoreInstallWizard.preparing(c,false);
                    stage(c,"restore","",setup(c)?"3/3 連線已確認，正在交回原設定":"4/4 核心已驗證，正在依原開關恢復；不重播舊批次");
                    finishRestore(c);break;
                case WAIT: break;
                default: throw new IOException("安裝中斷或狀態變動，不能重送；請於進階核心工具核對，或取消尚未完成的 APK 更新");
            }
        }catch(InterruptedException interrupted){
            Thread.currentThread().interrupt();if(!observationOnly&&active(c))failure(c,interrupted);
        }catch(Exception e){if(active(c))failure(c,e);}
        finally{nextCheck=android.os.SystemClock.elapsedRealtime()+5000;running.set(false);}
    }
    private static boolean retireSupersededRestore(Context c)throws Exception {
        long installed=UpdateStore.installed(c),target=prefs(c).getLong("target",0);
        if(!PhoneUpdatePolicy.supersededRestore(phase(c),prefs(c).getString("resume",""),installed,target))return false;
        if(UpdateInstaller.pending(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c))return false;
        CorePair pair=CoreUpdater.readPair(c,"core.properties"),effective=CoreUpdater.pair(c);
        if(pair.app!=installed||effective.code!=pair.code||!effective.sha.equals(pair.sha))return false;
        CoreUpdater.requireFiles(c,pair);
        String pid=CoreUpdater.runtimePid(c,pair);
        if(pid.isEmpty()||!CoreInstallWizard.action(c).equals("ready")||UpdateStore.installed(c)!=installed
            ||UpdateInstaller.pending(c)||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c)
            ||!pid.equals(CoreUpdater.runtimePid(c,pair)))return false;
        // Preserve the old consent/manifest/core fields as history; record only
        // proof of the independently installed successor. No install or reboot.
        save(prefs(c).edit().putLong("successor_app",installed).putInt("successor_core",pair.code)
            .putString("successor_core_sha",pair.sha).putString("phase","superseded").putString("action","")
            .putString("message","舊更新已由目前版本取代；配對核心已核對，原設定由既有流程恢復"));
        if(!UpdatePreparation.restore(c)||!SettingsRestoreJob.restoreForPid(c,pid))SettingsRestoreJob.schedule(c);
        dismissReminder(c);return true;
    }
    private static void bindCore(Context c,UpdateInfo target)throws Exception {
        UpdateController.verify(c,UpdateController.apk(c),target);
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(UpdateController.apk(c));
            java.io.InputStream in=zip.getInputStream(zip.getEntry("assets/core.properties"))) {
            CorePair pair=new CorePair(new String(SetupBundle.read(in,4096),java.nio.charset.StandardCharsets.UTF_8));
            save(prefs(c).edit().putInt("core_code",pair.code).putString("core_sha",pair.sha));
        }
    }
    private static void requirePair(Context c)throws Exception {
        CorePair pair=CoreUpdater.readPair(c,"core.properties");
        if(setup(c)&&!prefs(c).getString("app_sha","").equals(UpdateClient.digest(new java.io.File(c.getApplicationInfo().sourceDir))))
            throw new IOException("設定期間 APK 已更換，請核對原交易；不重送安裝");
        if(UpdateStore.installed(c)!=prefs(c).getLong("target",-1)||pair.code!=prefs(c).getInt("core_code",-1)
            ||!pair.sha.equals(prefs(c).getString("core_sha","")))throw new IOException("APK／核心已不是同意的配對版本");
        CorePair effective=CoreUpdater.pair(c);
        if(effective.code!=pair.code||!effective.sha.equals(pair.sha))throw new IOException("目前是回退核心，請至進階工具完成核對");
    }
    private static void finishRestore(Context c)throws Exception {
        requirePair(c);
        CorePair pair=CoreUpdater.pair(c);CoreUpdater.requireFiles(c,pair);
        String pid=CoreUpdater.runtimePid(c,pair);
        if(pid.isEmpty()){waitRestore(c,"等待遊戲連線；請開啟 Pikmin 並保持前景");return;}
        if(!UpdatePreparation.restore(c)||!SettingsRestoreJob.restoreForPid(c,pid)){
            waitRestore(c,"原設定保留，等待目前工作或設定同步完成");return;
        }
        stage(c,"complete","",setup(c)?"設定完成：遊戲連線已確認，原設定已交回；遊戲功能實測仍需另行確認。":"更新完成：APK及配對核心已驗證，已交回原設定自動恢復；不重播舊批次，各功能效果仍以實際運作為準。");
        dismissReminder(c);
    }
    private static void waitRestore(Context c,String why)throws IOException {
        int attempts=prefs(c).getInt("restore_waits",0)+1;
        save(prefs(c).edit().putInt("restore_waits",attempts));
        stage(c,"restore",attempts>=24?"retry":"open-game",attempts>=24
            ?"恢復仍待核對，已停止自動重查；可重新核對並繼續。原設定與紀錄保留。":why);
    }
    static void cancel(Context c)throws Exception {
        if(UpdateController.busy()||!running.compareAndSet(false,true))throw new IOException("操作仍在進行，請稍候");
        try{
        if((!setup(c)&&UpdateStore.installed(c)>=prefs(c).getLong("target",0))
            ||"core-intent".equals(phase(c))||"core-intent".equals(prefs(c).getString("resume",""))||CoreUpdater.pending(c))
            throw new IOException("APK 已更換，請完成核心核對；不取消已送出的核心交易");
        // Stop automatic continuation first, but preserve its target/evidence.
        if(setup(c)&&CoreInstallWizard.preparing(c))CoreInstallWizard.cancelPreparation(c);
        stage(c,"cancelled","","已取消尚未送出的準備，依原版本核對恢復設定");
        try {
            if(!setup(c))UpdateController.discardPartial(c);
        } finally {
            UpdatePreparation.cancel(c);
        }
        dismissReminder(c);
        }finally{running.set(false);}
    }
    static void remind(Context c){
        if(!active(c))return;
        try{
            android.app.NotificationManager n=c.getSystemService(android.app.NotificationManager.class);
            n.createNotificationChannel(new android.app.NotificationChannel("phone-update","版本更新",android.app.NotificationManager.IMPORTANCE_DEFAULT));
            android.content.Intent i=setup(c)?new android.content.Intent(c,SetupActivity.class):new android.content.Intent(c,MainActivity.class).putExtra("open_updates",true);
            android.app.PendingIntent p=android.app.PendingIntent.getActivity(c,173,i,android.app.PendingIntent.FLAG_UPDATE_CURRENT|android.app.PendingIntent.FLAG_IMMUTABLE);
            n.notify(173,new android.app.Notification.Builder(c,"phone-update").setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(setup(c)?"繼續完成裝置設定":"繼續完成控制中心更新").setContentText("開啟 App 接續核心與設定核對；不會自動重開手機。")
                .setContentIntent(p).setAutoCancel(true).build());
        }catch(RuntimeException denied){/* Progress also remains in the app; no notification permission loop. */}
    }
    private static void dismissReminder(Context c){
        try{c.getSystemService(android.app.NotificationManager.class).cancel(173);}catch(RuntimeException ignored){}
    }
}

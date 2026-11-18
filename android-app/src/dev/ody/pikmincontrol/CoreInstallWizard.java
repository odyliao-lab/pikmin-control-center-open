package dev.ody.pikmincontrol;

import android.content.Context;
import java.io.IOException;

/** UI orchestration only: existing hash-bound migration remains the installation authority. */
final class CoreInstallWizard {
    private static volatile boolean applying;
    static boolean preparing(Context c){return c.getSharedPreferences("core_wizard",0).getBoolean("preparing",false);}
    static void preparing(Context c,boolean value)throws IOException {
        if(!c.getSharedPreferences("core_wizard",0).edit().putBoolean("preparing",value).commit())throw new IOException("無法保存升級準備狀態");
    }
    static void cancelPreparation(Context c)throws IOException {
        synchronized(GpsWorkLock.class){
            if(applying||CoreUpdater.pending(c)||WorkStatus.stoppingAll)throw new IOException("狀態已改變，不能取消已送出的操作");
            preparing(c,false);
        }
    }
    static String action(Context c)throws Exception {
        if(UpdateInstaller.preparing||UpdateInstaller.pending(c)||UpdatePreparation.running())return "busy";
        if(applying||CoreUpdater.running||SetupDeployment.running||WorkStatus.stoppingAll)return "busy";
        if(CoreUpdater.pending(c)) {
            boolean migration=CoreUpdater.prefs(c).getBoolean("migration",false)&&!CoreUpdater.prefs(c).getBoolean("fresh",false);
            String next=CoreWizardPolicy.pending(CoreMigrationFlow.bootChanged(CoreUpdater.root("cat /proc/sys/kernel/random/boot_id"),migration?CoreUpdater.prefs(c).getString("phase_boot",""):"",CoreUpdater.prefs(c).getString("boot","")),
                migration,CoreUpdater.prefs(c).getString("migration_phase",""),
                migration?CoreStoredVersion.read(CoreUpdater.prefs(c).getAll().get("target")):0,CoreUpdater.readPair(c,"core.properties").code);
            String phase=CoreUpdater.prefs(c).getString("migration_phase","");
            if(next.equals("review")&&migration&&(phase.equals("old-core-confirmed")||phase.equals("gps-restore-intent")))return "finish";
            return next.equals("review")&&gamePid().isEmpty()?"open-game":next;
        }
        CorePair pair=CoreUpdater.pair(c);
        String code=CoreUpdater.root("if test -e "+CoreUpdater.BASE+"/module.prop; then sed -n 's/^versionCode=//p' "+CoreUpdater.BASE+"/module.prop; else echo absent; fi");
        if(code.equals("absent"))return "install";
        if(!code.matches("[1-9][0-9]*"))throw new IOException("無法確認現有核心版本");
        if(Integer.parseInt(code)>pair.code)throw new IOException("現有核心較新，請更新APK；不自動降版");
        if(Integer.parseInt(code)<pair.code)return "install";
        CoreUpdater.requireFiles(c,pair);
        return CoreUpdater.runtimePid(c,pair).isEmpty()?(gamePid().isEmpty()?"open-game":"review"):"ready";
    }
    static String gamePid()throws Exception{return CoreUpdater.root("pidof com.nianticlabs.pikmin || true");}
    static void closeGameAndInstall(Context c)throws Exception {
        String next=action(c);
        if(!next.equals("install")&&!next.equals("successor"))throw new IOException("狀態已改變，請重新核對，不重複安裝");
        synchronized(GpsWorkLock.class){
            if(applying||!preparing(c))throw new IOException("升級準備已取消或已有操作執行中");
            applying=true;
        }
        try {
        GpsWorkLock.requireCoreRecoveryIdle(c);
        CoreUpdater.root("am force-stop --user 0 com.nianticlabs.pikmin");
        SetupDeployment.requireGameIdle();
        if(next.equals("successor"))CoreUpdater.installSuccessor(c);else CoreUpdater.install(c);
        }finally{applying=false;}
    }
}

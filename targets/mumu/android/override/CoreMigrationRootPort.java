package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.util.Map;

/** Actual bounded root operations behind the fault-tested migration state machine. */
final class CoreMigrationRootPort implements CoreMigrationFlow.Port {
    final Context c; final SharedPreferences prefs;
    final CorePair target,rollback;final byte[] targetZip,rollbackZip;
    final CoreInstallSnapshot before;final String id,dir;
    CoreMigrationRootPort(Context c,CorePair target,byte[] targetZip,CorePair rollback,byte[] rollbackZip)throws Exception {
        this.c=c.getApplicationContext();prefs=CoreUpdater.prefs(c);
        SetupDeployment.trustedKey(c);CoreMigrationBackup.verifyPayload(targetZip,target);CoreMigrationBackup.verifyPayload(rollbackZip,rollback);
        if(target.app!=UpdateStore.installed(c)||rollback.app!=target.app||!target.unified||rollback.code>=target.code)
            throw new IOException("遷移配對不符");
        if(!prefs.getBoolean("migration",false)||!target.sha.equals(prefs.getString("target_sha",""))||!rollback.sha.equals(prefs.getString("rollback_sha","")))
            throw new IOException("原遷移ZIP與本APK不符，不能繼續操作");
        this.target=target;this.rollback=rollback;this.targetZip=targetZip;this.rollbackZip=rollbackZip;
        id=prefs.getString("migration_id","");dir=CoreMigrationBackup.path(id);
        before=new CoreInstallSnapshot(prefs.getString("before_snapshot",""));
    }
    private static String root(String cmd)throws Exception{return CoreUpdater.root(cmd);}
    private String boot()throws Exception{String value=root("cat /proc/sys/kernel/random/boot_id");CoreMigrationBackup.path(value);return value;}
    public String phase(){return prefs.getString("migration_phase","");}
    public boolean rebooted()throws Exception{return CoreMigrationFlow.bootChanged(boot(),prefs.getString("phase_boot",""),prefs.getString("boot",""));}
    public void persist(String phase)throws Exception {
        if(!phase.matches("gps-disable-intent|install-intent|staged|accepted|rollback-intent|rollback-staged|old-core-confirmed|gps-restore-intent|gps-reboot|rolled-back"))
            throw new IOException("不支援的遷移階段");
        SharedPreferences.Editor edit=prefs.edit().putString("migration_phase",phase).putString("result","核心遷移："+phase)
            .putBoolean("review",true);
        if(phase.endsWith("-intent"))edit.putString("phase_boot",boot());
        if(phase.equals("rolled-back"))edit.putBoolean("rollback_active",true);
        if(phase.equals("accepted"))edit.putBoolean("rollback_active",false);
        CoreUpdater.save(c,edit); // intent first; any later error remains review-required
        root("set -e; test ! -L "+dir+"; test -d "+dir+"; test ! -L "+dir+"/phase.tmp; "
            +"umask 077; printf '%s\\n' '"+phase+"' > "+dir+"/phase.tmp; chmod 0600 "+dir+"/phase.tmp; sync; "
            +"mv "+dir+"/phase.tmp "+dir+"/phase; sync");
        if(phase.equals("accepted")||phase.equals("rolled-back"))CoreUpdater.save(c,prefs.edit().putBoolean("review",false));
    }
    public void verifyBackup()throws Exception {
        CoreUpdater.checkedRoot("核心與GPS備份封存檔校驗",CoreMigrationBackup.verifyArchiveCommand(id,prefs.getString("backup_core_sha",""),prefs.getString("backup_gps_sha","")));
        String controlsHash=CoreMigrationBackup.checkedHash(prefs.getString("backup_controls_sha",""));
        CoreUpdater.checkedRoot("控制檔備份校驗","test ! -L "+dir+"/controls.tar && test -f "+dir+"/controls.tar && "
            +"test \"$(sha256sum "+dir+"/controls.tar | cut -d ' ' -f1)\" = "+controlsHash);
        CoreUpdater.checkedRoot("Root交易ZIP校驗","set -e; test ! -L "+dir+"/native.zip; test ! -L "+dir+"/rollback.zip; "
            +"test \"$(sha256sum "+dir+"/native.zip | cut -d ' ' -f1)\" = "+target.sha+"; "
            +"test \"$(sha256sum "+dir+"/rollback.zip | cut -d ' ' -f1)\" = "+rollback.sha);
    }
    public void requireIdle()throws Exception {
        if(!CoreUpdater.running)throw new IOException("核心安裝互斥未持有");
        FeedingGateway.requirePausedForInstall();
        SetupDeployment.requireGameIdle();
    }
    public void verifyOriginal()throws Exception {
        if(!before.unchanged(new CoreInstallSnapshot(root(CoreInstallSnapshot.command()))))throw new IOException("原核心／GPS已變動");
        root(copyBefore().guard());
    }
    private CopyModeState copyBefore()throws Exception{return new CopyModeState(prefs.getString("copy_before",""));}
    private boolean preserveDisabledCopy()throws Exception{return CoreRollbackCatalog.disableLegacyCopy(rollback.unified,before.gpsState,prefs.getBoolean("copy_user_override",false));}
    private String gpsProof(boolean disabled)throws Exception {
        return LegacyGpsSnapshot.from(before).proof(disabled);
    }
    public void disableGps()throws Exception {
        root(gpsProof(before.gpsState.equals("disabled")));
        if(preserveDisabledCopy()){root(copyBefore().writeOff(id));root(copyBefore().off().guard());}
        if(before.gpsState.equals("enabled"))root(gpsProof(false)+"printf '' > "+CoreInstallSnapshot.GPS+"/disable; chmod 0644 "+CoreInstallSnapshot.GPS+"/disable; sync");
    }
    public void verifyGpsDisabled()throws Exception{root(gpsProof(true));}
    public void requireNoStaged()throws Exception {
        root("test ! -L "+CoreUpdater.STAGED+" && test ! -e "+CoreUpdater.STAGED);
    }
    public void stage(boolean old)throws Exception {
        requireIdle();verifyBackup();requireNoStaged();verifyGpsDisabled();
        if(!old&&preserveDisabledCopy())root(copyBefore().off().guard());
        String nectarRollbackGuard=old?NectarModuleGateway.rollbackProof():"";
        root(CoreModuleProof.knownInstalled(target,targetZip,rollback,rollbackZip)
            +"; test ! -e "+CoreUpdater.STAGED+"; test ! -L "+CoreUpdater.STAGED+"; "
            +nectarRollbackGuard+"magisk --install-module "+dir+(old?"/rollback.zip":"/native.zip"));
    }
    public void verifyStaged(boolean old)throws Exception {
        CorePair p=old?rollback:target;byte[] zip=old?rollbackZip:targetZip;
        Map<String,byte[]> files=SetupBundle.unzip(zip,CoreUpdater.moduleEntries(),12*1024*1024);
        String base=CoreUpdater.STAGED;
        root(CoreModuleProof.paths(base)
            +"test ! -e "+base+"/disable; test ! -L "+base+"/disable; test ! -e "+base+"/remove; test ! -L "+base+"/remove; "
            +"test \"$(sed -n 's/^versionCode=//p' "+base+"/module.prop)\" = "+p.code+"; "
            +"test \"$(sha256sum "+base+"/zygisk/x86_64.so | cut -d ' ' -f1)\" = "+SetupBundle.sha(files.get("zygisk/x86_64.so"))+"; "
            +"test \"$(sha256sum "+base+"/service.sh | cut -d ' ' -f1)\" = "+SetupBundle.sha(files.get("service.sh")));
    }
    public boolean loaded(boolean old)throws Exception {
        CorePair p=old?rollback:target;
        try{CoreUpdater.requireInstalledFiles(p,old?rollbackZip:targetZip);return "遊戲已載入配對核心".equals(CoreUpdater.runtime(c,p));}
        catch(Exception notReady){return false;}
    }
    public void verifyOldFiles()throws Exception{CoreUpdater.requireInstalledFiles(rollback,rollbackZip);}
    public boolean restoreGpsNeedsReboot(){return before.gpsState.equals("enabled");}
    public void restoreGps()throws Exception {
        requireIdle();verifyOldFiles();verifyGpsDisabled();
        if(preserveDisabledCopy()) {
            boolean original=false;try{root(copyBefore().guard());original=true;}catch(Exception changed){/* Require our exact off state below. */}
            if(!original)root(copyBefore().restore(dir+"/controls/gps_copy_mode.txt"));
        }
        if(before.gpsState.equals("enabled"))root(gpsProof(true)+"rm -f "+CoreInstallSnapshot.GPS+"/disable; sync");
    }
    public void verifyRestoredGps()throws Exception{root(gpsProof(before.gpsState.equals("disabled")));if(preserveDisabledCopy())root(copyBefore().guard());}
}

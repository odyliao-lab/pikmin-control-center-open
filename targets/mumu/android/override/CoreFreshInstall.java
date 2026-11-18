package dev.ody.pikmincontrol;

import android.content.Context;
import java.io.*;
import java.util.UUID;

/** First install only. Rollback quarantines our exact module, restoring an absent module baseline. */
final class CoreFreshInstall {
    static boolean rollbackPhase(String state){return CoreFreshFlow.rollbackPhase(state);}
    static final String GPS="/data/adb/modules/zygisk_pikmin_gps_copy";
    private static String root(String cmd)throws Exception{return CoreUpdater.root(cmd);}
    static String coreAbsent() {
        return LegacyGpsSnapshot.parents()+"for p in "+CoreUpdater.BASE+" "+CoreUpdater.STAGED+"; do test ! -e \"$p\"; test ! -L \"$p\"; done; ";
    }
    private static LegacyGpsSnapshot gps(Context c)throws Exception {
        // Earlier fresh transactions admitted only an absent GPS module.
        return new LegacyGpsSnapshot(CoreUpdater.prefs(c).getString("fresh_gps_before","v1\tabsent\t-\t-"));
    }
    private static String gpsOriginal(Context c)throws Exception {LegacyGpsSnapshot g=gps(c);return g.proof(g.state.equals("disabled"));}
    private static String noStaged(Context c)throws Exception {
        return gps(c).proof(true)+"test ! -e "+CoreUpdater.STAGED+"; test ! -L "+CoreUpdater.STAGED+"; ";
    }
    static String gpsArchive(String id,LegacyGpsSnapshot g)throws Exception {
        String d=CoreMigrationBackup.path(id),f=d+"/gps.tar";
        return privateDir(d)+g.proof(g.state.equals("disabled"))+"test ! -e "+f+"; test ! -L "+f+"; "
            +(g.state.equals("absent")?"":"umask 077; tar -cf "+f+" -C /data/adb/modules zygisk_pikmin_gps_copy; chmod 0600 "+f+"; sync; ")
            +g.proof(g.state.equals("disabled"));
    }
    private static void verifyGpsBackup(Context c)throws Exception {
        if(gps(c).state.equals("absent"))return;
        String sha=CoreUpdater.prefs(c).getString("fresh_gps_archive_sha","");
        if(!sha.matches("[a-f0-9]{64}"))throw new IOException("舊GPS備份雜湊缺失，保留狀態待核對");
        String f=dir(c)+"/gps.tar";
        root(privateDir(dir(c))+"test -f "+f+"; test ! -L "+f+"; test \"$(stat -c %a "+f+")\" = 600; "
            +"test \"$(sha256sum "+f+" | cut -d ' ' -f1)\" = "+sha);
    }
    private static void restoreGps(Context c)throws Exception {
        // Never re-enable a second loader until our module is absent and the game is closed.
        root(coreAbsent()+"test -z \"$(pidof com.nianticlabs.pikmin)\"; ");
        try{root(gpsOriginal(c));return;}catch(Exception changed){/* require exact disabled original below */}
        LegacyGpsSnapshot g=gps(c);
        if(!g.state.equals("enabled"))throw new IOException("舊GPS狀態改變，不能自動還原");
        verifyGpsBackup(c);
        root(coreAbsent()+g.proof(true)+"test -z \"$(pidof com.nianticlabs.pikmin)\"; rm -f "+GPS+"/disable; sync; "+g.proof(false));
    }
    private static String boot()throws Exception{String b=root("cat /proc/sys/kernel/random/boot_id");CoreMigrationBackup.path(b);return b;}
    private static String dir(Context c)throws Exception{return CoreMigrationBackup.path(CoreUpdater.prefs(c).getString("fresh_id",""));}
    private static CorePair pair(Context c)throws Exception {
        CorePair p=CoreUpdater.readPair(c,"core.properties");CoreUpdater.payload(c,p);
        if(!p.unified||!p.sha.equals(CoreUpdater.prefs(c).getString("fresh_sha","")))throw new IOException("首次安裝配對已改變，保留紀錄待核對");return p;
    }
    private static String privateDir(String d) {
        return "set -e; test ! -L "+CoreMigrationBackup.ROOT+"; test -d "+d+"; test ! -L "+d+"; test \"$(stat -c %u "+d+")\" = 0; test \"$(stat -c %a "+d+")\" = 700; ";
    }
    private static void phase(Context c,String phase,String message)throws Exception {
        if(!phase.matches("prepared|gps-intent|copy-intent|install-intent|staged|accepted|remove-intent|removed|restored"))throw new IOException("首次安裝階段不符");
        CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putString("fresh_phase",phase).putString("result",message).putBoolean("review",true));
        String d=dir(c);
        root(privateDir(d)+"test ! -L "+d+"/fresh-phase.tmp; umask 077; printf '%s\\n' '"+phase+"' > "+d+"/fresh-phase.tmp; sync; mv "+d+"/fresh-phase.tmp "+d+"/fresh-phase; sync");
        if(phase.equals("accepted")||phase.equals("restored"))CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putBoolean("review",false));
    }
    private static void verifyZip(Context c,CorePair p)throws Exception {
        String d=dir(c);root(privateDir(d)+"test -f "+d+"/native.zip; test ! -L "+d+"/native.zip; test \"$(sha256sum "+d+"/native.zip | cut -d ' ' -f1)\" = "+p.sha);
    }
    private static CopyModeState originalCopy(Context c)throws Exception {
        if(!CoreUpdater.prefs(c).getBoolean("fresh_copy_protocol",false))throw new IOException("首次安裝缺少複製設定備份，保留紀錄待核對");
        return new CopyModeState(CoreUpdater.prefs(c).getString("fresh_copy_before",""));
    }
    private static CopyModeState selectedCopy(Context c)throws Exception {
        return originalCopy(c).selected(CoreUpdater.prefs(c).getBoolean("fresh_copy_enabled",false));
    }
    private static void verifyCopy(Context c)throws Exception {
        if(!CoreUpdater.prefs(c).getBoolean("copy_user_override",false))root(selectedCopy(c).guard());
    }
    private static void restoreCopy(Context c)throws Exception {
        if(CoreUpdater.prefs(c).getBoolean("copy_user_override",false))return;
        CopyModeState original=originalCopy(c);
        // An interrupted restore is idempotent only if the complete original identity is proven.
        try{root(original.guard());return;}catch(Exception notOriginal){/* require exact transaction-written state */}
        root(privateDir(dir(c))+original.restoreFrom(dir(c)+"/controls/gps_copy_mode.txt",selectedCopy(c)));
    }
    static void install(Context c,CorePair p,byte[] zip)throws Exception {
        if(!CoreUpdater.running||!p.unified)throw new IOException("首次安裝缺少互斥或配對");
        CoreMigrationBackup.verifyPayload(zip,p);SetupDeployment.requireGameIdle();root(coreAbsent());
        LegacyGpsSnapshot gps=new LegacyGpsSnapshot(root(LegacyGpsSnapshot.command()));
        CopyModeState copy=new CopyModeState(root(CopyModeState.snapshotCommand()));
        boolean copyEnabled=SetupStore.copy(c);
        String id=UUID.randomUUID().toString(),d=CoreMigrationBackup.path(id);
        File stage=new File(c.getFilesDir(),"core-"+id);
        if(!stage.mkdir())throw new IOException("無法建立首次安裝暫存");
        File file=new File(stage,"native.zip");String source=file.getAbsolutePath();
        if(!source.matches("/data/(user/0|data)/dev\\.ody\\.pikmincontrol/files/core-[a-f0-9-]+/native\\.zip"))throw new IOException("首次安裝路徑不符");
        try(FileOutputStream out=new FileOutputStream(file)){out.write(zip);out.getFD().sync();}
        CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putBoolean("fresh",true).putBoolean("migration",false).putBoolean("rollback_active",false)
            .putBoolean("review",true).putString("fresh_id",id).putString("fresh_sha",p.sha).putString("fresh_phase","prepare-intent")
            .putBoolean("fresh_copy_protocol",true).putString("fresh_copy_before",copy.raw).putBoolean("fresh_copy_enabled",copyEnabled)
            .putString("fresh_gps_before",gps.raw).putString("fresh_gps_archive_sha","")
            .putBoolean("copy_user_override",false).putBoolean("fresh_removed_module",false)
            .putString("boot",boot()).putString("result","首次安裝準備中；中斷後請重開並核對"));
        root(coreAbsent()+gpsOriginal(c)+"test ! -L "+CoreMigrationBackup.ROOT+"; mkdir -p "+CoreMigrationBackup.ROOT+"; chmod 0700 "+CoreMigrationBackup.ROOT+"; "
            +"test ! -e "+d+"; test ! -L "+d+"; umask 077; mkdir "+d+"; cp '"+source+"' "+d+"/native.zip; chmod 0600 "+d+"/native.zip; sync");
        root(privateDir(d)+copy.guard()+"mkdir "+d+"/controls; "+(copy.absent?"":
            "cp "+CopyModeState.FILE+" "+d+"/controls/gps_copy_mode.txt; chmod 0600 "+d+"/controls/gps_copy_mode.txt; "
            +"test \"$(sha256sum "+d+"/controls/gps_copy_mode.txt | cut -d ' ' -f1)\" = "+copy.sha+"; ")+"sync; "+copy.guard());
        root(gpsArchive(id,gps));
        if(!gps.state.equals("absent"))CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putString("fresh_gps_archive_sha",root("sha256sum "+d+"/gps.tar | cut -d ' ' -f1")));
        verifyZip(c,p);verifyGpsBackup(c);phase(c,"prepared","首次安裝、舊GPS及複製設定備份完成");
        CoreFreshFlow.install(new Port(c,p,zip));
    }
    static void review(Context c)throws Exception {
        CorePair p=pair(c);String state=CoreUpdater.prefs(c).getString("fresh_phase","");
        if(!CoreMigrationFlow.bootChanged(boot(),"",CoreUpdater.prefs(c).getString("boot","")))throw new IOException("首次安裝／回復仍需重開機");
        if(state.equals("prepare-intent")||state.equals("prepared")||state.equals("gps-intent")||state.equals("copy-intent")||state.equals("install-intent")) {
            boolean absent=false;try{root(coreAbsent());absent=true;}catch(Exception changed){/* inspect installed target below */}
            if(absent){
                try{root(originalCopy(c).guard()+gpsOriginal(c));}catch(Exception changed){throw new IOException("核心尚未安裝，但GPS或複製設定已調整；請關閉遊戲並按「回復未安裝核心」");}
                CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putBoolean("review",false).putBoolean("fresh",false)
                .putString("result","已重開且仍未安裝模組；未完成首次安裝，可重新開始，保留紀錄"));return;}
        }
        verifyZip(c,p);verifyGpsBackup(c);
        if(state.equals("remove-intent")||state.equals("removed")||state.equals("restored")) {
            try{root(coreAbsent()+gpsOriginal(c));}catch(Exception changed){throw new IOException("首次安裝回復尚未完成；請關閉遊戲並再次按「回復未安裝核心」");}
            if(CoreUpdater.prefs(c).getBoolean("fresh_removed_module",false))verifyRemoved(c,p);
            if(!CoreUpdater.prefs(c).getBoolean("copy_user_override",false))root(originalCopy(c).guard());
            phase(c,"restored","已重開並回復未安裝核心及原複製設定；封存檔與紀錄保留");return;
        }
        if(!state.matches("prepare-intent|prepared|install-intent|staged|accepted"))throw new IOException("首次安裝狀態不明");
        root(noStaged(c));CoreUpdater.requireFiles(c,p);verifyCopy(c);
        if(CoreUpdater.runtimePid(c,p).isEmpty())throw new IOException("請開啟遊戲核對首次核心及GPS載入");
        phase(c,"accepted","首次核心及GPS已同程序載入；遊戲功能仍需實測");
    }
    private static void verifyRemoved(Context c,CorePair p)throws Exception {
        String path=dir(c)+"/removed-core";
        root(privateDir(dir(c))+recoveryProof(path,CoreUpdater.payload(c,p)));
    }
    /** Only our complete payload or exact Magisk metadata-only residue can be quarantined. */
    static String recoveryProof(String path,byte[] zip)throws Exception {
        if(!path.equals(CoreUpdater.BASE)&&!path.matches("/data/adb/pcc-core-backups/[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}/removed-core"))
            throw new IOException("回復來源路徑不符");
        byte[] prop=SetupBundle.unzip(zip,CoreUpdater.moduleEntries(),12*1024*1024).get("module.prop");
        if(prop==null)throw new IOException("配對缺少模組版本檔");
        return "set -e; test -d "+path+"; test ! -L "+path+"; test -f "+path+"/module.prop; test ! -L "+path+"/module.prop; "
            +"test \"$(sha256sum "+path+"/module.prop | cut -d ' ' -f1)\" = "+SetupBundle.sha(prop)+"; "
            +"for f in disable remove; do test ! -e "+path+"/\"$f\"; test ! -L "+path+"/\"$f\"; done; "
            +"if test -e "+path+"/zygisk || test -L "+path+"/zygisk; then "
            +"test -d "+path+"/zygisk; test ! -L "+path+"/zygisk; "
            +"for f in service.sh zygisk/x86_64.so; do test -f "+path+"/\"$f\"; test ! -L "+path+"/\"$f\"; done; "
            +CoreModuleProof.hashes(path,zip)+" || exit 32; else "
            +"for f in "+path+"/* "+path+"/.[!.]* "+path+"/..?*; do test ! -L \"$f\"; test -e \"$f\" || continue; "
            +"case \"${f##*/}\" in module.prop) ;; update) test -f \"$f\"; test ! -s \"$f\";; *) exit 31;; esac; done; fi; ";
    }
    static void rollback(Context c)throws Exception {
        if(!CoreUpdater.running)throw new IOException("缺少回復互斥");
        CorePair p=pair(c);CoreFreshFlow.rollback(new Port(c,p,CoreUpdater.payload(c,p)));
    }
    private static final class Port implements CoreFreshFlow.Port {
        final Context c;final CorePair p;final byte[] zip;
        boolean modulePresent,alreadyRemoved;
        Port(Context c,CorePair p,byte[] zip){this.c=c;this.p=p;this.zip=zip;}
        public String phase(){return CoreUpdater.prefs(c).getString("fresh_phase","");}
        public boolean rebooted()throws Exception{return CoreMigrationFlow.bootChanged(boot(),"",CoreUpdater.prefs(c).getString("boot",""));}
        public void verifyBackup()throws Exception{verifyZip(c,p);verifyGpsBackup(c);}
        public void requireIdle()throws Exception{SetupDeployment.requireGameIdle();}
        public void requireOriginal()throws Exception{root(coreAbsent()+gpsOriginal(c)+originalCopy(c).guard());}
        public void persist(String state)throws Exception {
            String message=state.equals("staged")?"核心已排入；請重開並開啟遊戲核對":
                state.equals("removed")?"核心、舊GPS及複製設定已回復；請再重開核對":
                state.equals("remove-intent")?"正在回復；中斷後請重開核對":
                state.equals("gps-intent")?"暫停舊GPS模組；中斷請核對或回復":
                state.equals("copy-intent")?"正在套用複製座標選項；中斷請核對或回復":"首次安裝需核對；中斷不自動重送";
            CoreFreshInstall.phase(c,state,message);
        }
        public void disableGps()throws Exception {
            LegacyGpsSnapshot g=gps(c);
            if(g.state.equals("enabled"))root(coreAbsent()+g.proof(false)+"printf '' > "+GPS+"/disable; chmod 0644 "+GPS+"/disable; sync; ");
        }
        public void requireDisabled()throws Exception{root(coreAbsent()+gps(c).proof(true));}
        public void applyCopy()throws Exception {
            CopyModeState copy=originalCopy(c);boolean copyEnabled=CoreUpdater.prefs(c).getBoolean("fresh_copy_enabled",false);
            String id=CoreUpdater.prefs(c).getString("fresh_id","");
            root(coreAbsent()+gps(c).proof(true)+copy.writeSelected(id,copyEnabled));
        }
        public void verifyCopy()throws Exception{CoreFreshInstall.verifyCopy(c);}
        public void stage()throws Exception{root(coreAbsent()+gps(c).proof(true)+selectedCopy(c).guard()+"magisk --install-module '"+dir(c)+"/native.zip'");}
        public void verifyStaged()throws Exception{root(CoreModuleProof.paths(CoreUpdater.STAGED)+CoreModuleProof.hashes(CoreUpdater.STAGED,zip));}
        public void prepareRemoval()throws Exception {
        String state=phase();
        try{root(coreAbsent());modulePresent=false;}catch(Exception notAbsent){root(recoveryProof(CoreUpdater.BASE,zip));modulePresent=true;}
        // Before disabling GPS or after quarantine, the original marker is also legitimate.
        // With our module present only a disabled/absent GPS is acceptable.
        if(modulePresent)root(noStaged(c));
        else {try{root(gpsOriginal(c));}catch(Exception notRestored){root(noStaged(c));}}
        String d=dir(c);alreadyRemoved=state.equals("remove-intent")&&CoreUpdater.prefs(c).getBoolean("fresh_removed_module",false)&&!modulePresent;
        if(alreadyRemoved)verifyRemoved(c,p);
        else root(privateDir(d)+"test ! -e "+d+"/removed-core; test ! -L "+d+"/removed-core");
        CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putString("boot",boot()).putBoolean("fresh_removed_module",modulePresent||alreadyRemoved));
        }
        public void restoreCopy()throws Exception{CoreFreshInstall.restoreCopy(c);}
        public void quarantine()throws Exception {
            if(modulePresent)root(noStaged(c)+recoveryProof(CoreUpdater.BASE,zip)
                +"test -z \"$(pidof com.nianticlabs.pikmin)\"; mv "+CoreUpdater.BASE+" "+dir(c)+"/removed-core; sync");
            root(coreAbsent());if(modulePresent||alreadyRemoved)verifyRemoved(c,p);
        }
        public void restoreGps()throws Exception{CoreFreshInstall.restoreGps(c);}
        public void verifyBaseline()throws Exception {
            root(coreAbsent()+gpsOriginal(c));
            if(!CoreUpdater.prefs(c).getBoolean("copy_user_override",false))root(originalCopy(c).guard());
        }
    }
}

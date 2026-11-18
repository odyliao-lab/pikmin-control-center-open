package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.util.UUID;

/** Private, durable backup boundary. Called only with APK-verified target and rollback payloads. */
final class CoreMigrationBackup {
    static final String ROOT="/data/adb/pcc-core-backups";
    static String path(String id)throws IOException {
        if(id==null||!id.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))
            throw new IOException("備份識別無效");
        return ROOT+"/"+id;
    }
    static String checkedHash(String sha)throws IOException {
        if(sha==null||!sha.matches("[a-f0-9]{64}"))throw new IOException("備份校驗欄位無效");return sha;
    }
    private static String root(String command)throws Exception{return CoreUpdater.root(command);}
    private static void write(File file,byte[] bytes)throws Exception {
        try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);out.getFD().sync();}
    }
    static void verifyPayload(byte[] bytes,CorePair pair)throws Exception {
        if(bytes.length!=pair.size||!pair.sha.equals(SetupBundle.sha(bytes)))throw new IOException("備份配對ZIP校驗失敗");
        SetupBundle.inspectModule(bytes,SetupDeployment.CORE,pair.version,""+pair.code);
    }
    // Pure command construction is shared with host tests. Each pathname/hash is fixed or validated.
    static String verifyArchiveCommand(String id,String coreHash,String gpsHash)throws IOException {
        String dir=path(id);checkedHash(coreHash);
        String command="set -e; test ! -L "+ROOT+"; test ! -L "+dir+"; test -d "+dir+"; "
            +"test ! -L "+dir+"/core.tar; test -f "+dir+"/core.tar; "
            +"test \"$(sha256sum "+dir+"/core.tar | cut -d ' ' -f1)\" = "+coreHash+"; ";
        if("absent".equals(gpsHash))return command+"test ! -e "+dir+"/gps.tar; test ! -L "+dir+"/gps.tar";
        checkedHash(gpsHash);
        return command+"test ! -L "+dir+"/gps.tar; test -f "+dir+"/gps.tar; "
            +"test \"$(sha256sum "+dir+"/gps.tar | cut -d ' ' -f1)\" = "+gpsHash;
    }
    /** Shared with isolated Android tests; only fixed code/control allowlists enter rollback evidence. */
    static String archiveCommand(String id,String gpsState)throws IOException {
        String dir=path(id);
        if(!"absent".equals(gpsState)&&!"enabled".equals(gpsState)&&!"disabled".equals(gpsState))throw new IOException("GPS備份狀態不明");
        return "set -e; umask 077; test -d "+dir+"; test ! -L "+ROOT+"; test ! -L "+dir+"; "
            +"for f in core.tar gps.tar controls.tar controls; do test ! -e "+dir+"/\"$f\"; test ! -L "+dir+"/\"$f\"; done; "
            +CoreModuleProof.paths(CoreUpdater.BASE)
            +"set -- module.prop service.sh zygisk/arm64-v8a.so; "
            +"for f in nectar_control_mode.txt return_control_mode.txt return_postcard_policy.txt return_batch_limit.txt; do "
            +"test ! -L "+CoreUpdater.BASE+"/\"$f\"; if test -e "+CoreUpdater.BASE+"/\"$f\"; then test -f "+CoreUpdater.BASE+"/\"$f\"; "
            +"test \"$(wc -c < "+CoreUpdater.BASE+"/\"$f\")\" -le 64; set -- \"$@\" \"$f\"; fi; done; "
            +"tar -cpf "+dir+"/core.tar -C "+CoreUpdater.BASE+" \"$@\"; "
            +(gpsState.equals("absent")?"":"set -- module.prop zygisk/arm64-v8a.so; "
                +(gpsState.equals("disabled")?"set -- \"$@\" disable; ":"")
                +"tar -cpf "+dir+"/gps.tar -C "+CoreInstallSnapshot.GPS+" \"$@\"; ")
            +"mkdir "+dir+"/controls; "
            +"for f in pikmin-return-mode.txt pikmin-return-postcard-policy.txt pikmin-return-batch-limit.txt pikmin-dispatch-mode.txt "
            +"pikmin-dispatch-kinds.txt pikmin-nearby-kinds.txt pikmin-planting-mode.txt pikmin-flower-farm-mode.txt pikmin-planter-mode.txt pikmin-planter-steps.txt; do "
            +"source=/data/local/tmp/\"$f\"; test ! -L \"$source\"; "
            +"if test -e \"$source\"; then test -f \"$source\"; test \"$(wc -c < \"$source\")\" -le 4096; "
            +"cp \"$source\" "+dir+"/controls/\"$f\"; cmp -s \"$source\" "+dir+"/controls/\"$f\"; fi; done; "
            +"source="+CopyModeState.FILE+"; test ! -L \"$source\"; "
            +"if test -e \"$source\"; then test -f \"$source\"; test \"$(wc -c < \"$source\")\" -le 64; "
            +"cp \"$source\" "+dir+"/controls/gps_copy_mode.txt; cmp -s \"$source\" "+dir+"/controls/gps_copy_mode.txt; fi; "
            +"tar -cpf "+dir+"/controls.tar -C "+dir+" controls; sync";
    }
    static String prepare(Context context,CorePair target,byte[] targetZip,CorePair rollback,byte[] rollbackZip)throws Exception {
        Context c=context.getApplicationContext();
        if(!CoreUpdater.running)throw new IOException("備份必須由持有安裝互斥的核心更新流程執行");
        SetupDeployment.trustedKey(c);
        verifyPayload(targetZip,target);verifyPayload(rollbackZip,rollback);
        if(!target.unified||!CorePair.upgrade(rollback.code,target.code)||target.app!=UpdateStore.installed(c)||rollback.app!=target.app)
            throw new IOException("整合／回退配對不符");
        SetupDeployment.requireGameIdle();
        // Installed SO/service must match the supplied, signed-APK rollback package.
        CoreUpdater.requireInstalledFiles(rollback,rollbackZip);
        CoreInstallSnapshot before=new CoreInstallSnapshot(root(CoreInstallSnapshot.command()));
        CopyModeState copy=new CopyModeState(root(CopyModeState.snapshotCommand()));
        if(!before.coreCode.equals(""+rollback.code))throw new IOException("原核心版本已變動");
        String id=UUID.randomUUID().toString(),dir=path(id);
        File stage=new File(c.getFilesDir(),"core-"+id);
        if(!stage.mkdir())throw new IOException("無法建立遷移暫存");
        String source=stage.getAbsolutePath();
        if(!source.matches("/data/(user/0|data)/dev\\.ody\\.pikmincontrol/files/core-[a-f0-9-]+"))throw new IOException("遷移暫存路徑錯誤");
        write(new File(stage,"native.zip"),targetZip);write(new File(stage,"rollback.zip"),rollbackZip);
        String boot=root("cat /proc/sys/kernel/random/boot_id");
        path(boot); // boot ID uses the same strict UUID syntax; never interpolate unchecked output.
        SharedPreferences p=CoreUpdater.prefs(c);
        // Intent is persisted before even creating the root backup. Never automatically replay.
        CoreUpdater.save(c,p.edit().putBoolean("review",true).putBoolean("migration",true).putBoolean("fresh",false)
            .putString("copy_before",copy.raw).putBoolean("copy_user_override",false)
            .putString("migration_id",id).putString("migration_phase","backup-intent")
            .putString("before_snapshot",before.raw).putString("boot",boot).putString("phase_boot",boot)
            .putString("target_sha",target.sha).putString("rollback_sha",rollback.sha)
            .putInt("target",target.code).putString("old",""+rollback.code).putString("old_sha",before.coreSo)
            .putString("result","正在建立私有遷移備份；中斷時保留紀錄，勿重送"));
        root("set -e; umask 077; test ! -L "+ROOT+"; "
            +"if test -e "+ROOT+"; then test -d "+ROOT+"; test \"$(stat -c %u "+ROOT+")\" = 0; else mkdir "+ROOT+"; fi; "
            +"chmod 0700 "+ROOT+"; test ! -e "+dir+"; test ! -L "+dir+"; mkdir "+dir+"; chmod 0700 "+dir+"; "
            +"test ! -L '"+source+"'; test ! -L '"+source+"/native.zip'; test ! -L '"+source+"/rollback.zip'; "
            +"cp '"+source+"/native.zip' "+dir+"/native.zip; cp '"+source+"/rollback.zip' "+dir+"/rollback.zip; "
            +"chmod 0600 "+dir+"/native.zip "+dir+"/rollback.zip; "
            +"test \"$(sha256sum "+dir+"/native.zip | cut -d ' ' -f1)\" = "+target.sha+"; "
            +"test \"$(sha256sum "+dir+"/rollback.zip | cut -d ' ' -f1)\" = "+rollback.sha+"; "
            +archiveCommand(id,before.gpsState));
        String coreHash=checkedHash(root("sha256sum "+dir+"/core.tar | cut -d ' ' -f1"));
        String gpsHash=before.gpsState.equals("absent")?"absent":checkedHash(root("sha256sum "+dir+"/gps.tar | cut -d ' ' -f1"));
        String controlsHash=checkedHash(root("sha256sum "+dir+"/controls.tar | cut -d ' ' -f1"));
        root(verifyArchiveCommand(id,coreHash,gpsHash));
        if(!before.unchanged(new CoreInstallSnapshot(root(CoreInstallSnapshot.command()))))throw new IOException("備份期間模組已變動，不能停用GPS或安裝");
        root(copy.guard());
        // Root-owned recovery evidence survives an app process restart independently of prefs.
        String journal="schema=1\nphase=backed-up\nid="+id+"\nboot="+boot+"\ncopy_before="+copy.raw+"\nbefore="+before.raw
            +"\ntarget_sha="+target.sha+"\nrollback_sha="+rollback.sha+"\ncore_tar_sha="+coreHash+"\ngps_tar_sha="+gpsHash+"\ncontrols_tar_sha="+controlsHash+"\n";
        root("set -e; umask 077; printf '%s' '"+journal+"' > "+dir+"/journal.tmp; chmod 0600 "+dir+"/journal.tmp; sync; "
            +"mv "+dir+"/journal.tmp "+dir+"/journal.properties; sync");
        CoreUpdater.save(c,p.edit().putString("backup_core_sha",coreHash).putString("backup_gps_sha",gpsHash).putString("backup_controls_sha",controlsHash)
            .putString("migration_phase","backed-up").putString("result","核心與GPS私有備份已核對；尚未停用或安裝"));
        return id;
    }
}

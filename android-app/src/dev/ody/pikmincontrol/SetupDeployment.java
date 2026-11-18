package dev.ody.pikmincontrol;

import android.content.*;
import android.content.pm.*;
import android.os.Build;
import android.util.AtomicFile;
import java.io.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;

/** Private offline modules only. No APK sideloading, arbitrary URLs or command catalogue. */
final class SetupDeployment {
    static volatile boolean running;
    static final String CORE="pikmin_nectar_rpc",COPY="zygisk_pikmin_gps_copy";
    static File file(Context c){return new File(c.getFilesDir(),"setup-bundle.zip");}
    static boolean pending(Context c){return running||SetupStore.prefs(c).getBoolean("install_review",false);}
    static PublicKey trustedKey(Context c) throws Exception {
        PackageInfo p=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        android.content.pm.Signature[] signatures=p.signingInfo.getApkContentsSigners();
        if(signatures.length!=1||!"9080c5d8c9a138a3a8eb42ec65755cf2abd64b881a7bf398feabe3547b934a7e".equals(SetupBundle.sha(signatures[0].toByteArray())))throw new SecurityException("控制中心簽章不符");
        return CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(signatures[0].toByteArray())).getPublicKey();
    }
    static SetupBundle verify(Context c,byte[] bytes) throws Exception {
        long floor=SetupStore.prefs(c).getLong("sequence",0);
        SetupBundle b=SetupBundle.verify(bytes,trustedKey(c),System.currentTimeMillis(),floor);
        if(b.sequence==floor&&!SetupBundle.sha(bytes).equals(SetupStore.prefs(c).getString("bundle_sha","")))throw new SecurityException("同序號部署包內容不同");
        return b;
    }
    static SetupBundle load(Context c) throws Exception {try(InputStream in=new FileInputStream(file(c))){return verify(c,SetupBundle.read(in,SetupBundle.MAX));}}
    static void importBundle(Context c,InputStream in) throws Exception {
        synchronized(GpsWorkLock.class){if(pending(c))throw new IllegalStateException("請先核對上次安裝");
            byte[] bytes=SetupBundle.read(in,SetupBundle.MAX);SetupBundle b=verify(c,bytes);
            AtomicFile f=new AtomicFile(file(c));FileOutputStream out=f.startWrite();
            try{out.write(bytes);f.finishWrite(out);}catch(Exception e){f.failWrite(out);throw e;}
            SetupStore.save(SetupStore.prefs(c).edit().putLong("sequence",b.sequence).putString("bundle_sha",SetupBundle.sha(bytes))
                    .putString("result","部署包已校驗：核心"+b.meta.get("native_version")+"／選用GPS Copy r13；尚未安裝"));
        }
    }
    static SetupSnapshot inspect(Context c) throws Exception {
        return new SetupSnapshot(EnvironmentGateway.collect(c),Build.VERSION.SDK_INT,
                Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"),c.getFilesDir().getUsableSpace(),System.currentTimeMillis());
    }
    static String root(String command) throws Exception {
        BoundedCommand.Result r=BoundedCommand.root(command,25,32768);
        if(!r.ok||r.truncated)throw new IOException(r.failureSummary()+"；請核對安裝狀態，不要重複安裝");return r.text.trim();
    }
    static void install(Context supplied) throws Exception {
        Context c=supplied.getApplicationContext();
        // Legacy bundle installation must never create a second GPS loader.
        // Route before taking the legacy installer lock; never fall back to a private dual-module bundle.
        if(CoreUpdater.readPair(c,"core.properties").unified) {
            CoreUpdater.install(c);SetupStore.result(c,CoreUpdater.inspect(c));SetupStore.step(c,4);return;
        }
        synchronized(GpsWorkLock.class){
            if(pending(c)||UpdateInstaller.pending(c))throw new IllegalStateException("已有安裝等待核對");
            GpsWorkLock.requireInstallIdle(c);running=true;
        }
        boolean started=false;
        try {
            SetupBundle b=load(c);SetupSnapshot s=inspect(c);
            if(!s.compatible||s.available<64L*1048576)throw new IOException("環境不相容或剩餘空間不足64MB");
            if(s.nativePending||s.copyPending)throw new IOException("Magisk已有待載入模組，請先完成重開與核對");
            // This first installer only fills absent components. Existing modules stay untouched.
            boolean core="未知".equals(s.get("native"));
            boolean copy=SetupStore.copy(c)&&"未知".equals(s.get("copy"));
            if(!core&&!copy)throw new IOException("沒有缺少的模組；舊版／停用模組請先於Magisk處理，精靈不覆寫");
            requireGameIdle();
            String id=UUID.randomUUID().toString();File dir=new File(c.getFilesDir(),"setup-"+id);
            if(!dir.mkdir())throw new IOException("無法建立部署暫存");
            if(core)write(new File(dir,"native.zip"),b.core);
            if(copy)write(new File(dir,"copy.zip"),b.copy);
            // Intent ledger precedes any root installation. Process death never causes automatic replay.
            SetupStore.save(SetupStore.prefs(c).edit().putBoolean("install_review",true).putString("run",id)
                    .putBoolean("target_core",core).putBoolean("target_copy",copy)
                    .putString("before_boot",root("cat /proc/sys/kernel/random/boot_id"))
                    .putString("result","安裝進行中；中斷時請核對，勿重複安裝"));started=true;
            if(core)installOne(c,new File(dir,"native.zip"),CORE);
            if(copy)installOne(c,new File(dir,"copy.zip"),COPY);
            SetupStore.result(c,"模組已排入，請重開手機、解鎖後回來核對；尚未證明載入");SetupStore.step(c,4);
        } catch(Exception e){if(started)SetupStore.result(c,"安裝需核對："+e.getMessage());throw e;}
        finally {running=false;}
    }
    static void requireGameIdle() throws Exception {
        // No controls are altered. Stop via existing UI first; the user closes the game.
        BoundedCommand.Result idle=BoundedCommand.root("test -z \"$(pidof com.nianticlabs.pikmin)\" || exit 10; "
                +"for f in /data/local/tmp/pikmin-dispatch-mode.txt /data/local/tmp/pikmin-return-mode.txt /data/local/tmp/pikmin-planter-mode.txt /data/local/tmp/pikmin-flower-farm-mode.txt /data/local/tmp/pikmin-planting-mode.txt /data/adb/modules/pikmin_nectar_rpc/nectar_control_mode.txt /data/adb/modules/pikmin_nectar_rpc/return_control_mode.txt /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt /data/user/0/com.nianticlabs.pikmin/files/return_rpc_mode.txt; do "
                +"if test -f \"$f\"; then case \"$(cat \"$f\")\" in off|diag|dry-run) ;; *) exit 11;; esac; fi; done",8,32768);
        if(!idle.ok){String reason=CoreWizardPolicy.idleFailure(idle.exitCode);throw new IOException(reason.isEmpty()?idle.failureSummary():reason);}
    }
    private static void write(File f,byte[] bytes) throws Exception {try(FileOutputStream out=new FileOutputStream(f)){out.write(bytes);out.getFD().sync();}}
    private static void installOne(Context c,File zip,String id) throws Exception {
        // ZIP lives in this app's private UUID directory, never a shared writable /sdcard path.
        SetupBundle b=load(c);requireGameIdle();
        try(InputStream in=new FileInputStream(zip)){
            if(!SetupBundle.sha(SetupBundle.read(in,SetupBundle.MAX)).equals(b.meta.get(CORE.equals(id)?"native_sha":"copy_sha")))throw new SecurityException("安裝前元件校驗不符");
        }
        String path=zip.getAbsolutePath();if(!path.matches("/data/(user/0|data)/dev\\.ody\\.pikmincontrol/files/setup-[a-f0-9-]+/(native|copy)\\.zip"))throw new SecurityException("部署路徑不符");
        root("test ! -e /data/adb/modules/"+id+" && test ! -e /data/adb/modules_update/"+id+" || exit 12; magisk --install-module '"+path+"'");
        SetupStore.result(c,id+" 安裝命令完成，仍需核對重開與載入");
    }
    static void review(Context c) throws Exception {
        synchronized(GpsWorkLock.class){
            if(running)throw new IOException("安裝仍在進行");
            if(!SetupStore.prefs(c).getBoolean("install_review",false))return;
            // Package hash and version are checked without trusting a completed-step flag.
            SetupBundle b;
            try(InputStream in=new FileInputStream(file(c))){byte[] bytes=SetupBundle.read(in,SetupBundle.MAX);
                if(!SetupBundle.sha(bytes).equals(SetupStore.prefs(c).getString("bundle_sha","")))throw new SecurityException("原部署包不符");
                b=SetupBundle.verify(bytes,trustedKey(c),System.currentTimeMillis(),SetupStore.prefs(c).getLong("sequence",0),false);
            }
            boolean rebooted=!SetupStore.prefs(c).getString("before_boot","").equals(root("cat /proc/sys/kernel/random/boot_id"));
            if(!rebooted)throw new IOException("請先重開機，避免與尚未結束的安裝命令競爭");
            int missing=0;
            for(String id:Arrays.asList(CORE,COPY)) {
                boolean core=CORE.equals(id);if(!SetupStore.prefs(c).getBoolean(core?"target_core":"target_copy",false))continue;
                byte[] zip=core?b.core:b.copy;
                Map<String,byte[]> contents=SetupBundle.unzip(zip,new HashSet<>(Arrays.asList("module.prop","service.sh","zygisk/arm64-v8a.so","META-INF/com/google/android/update-binary","META-INF/com/google/android/updater-script","META-INF/","META-INF/com/","META-INF/com/google/","META-INF/com/google/android/","zygisk/")),12*1024*1024);
                String base="/data/adb/modules/"+id;
                String exists=root("if test -e "+base+" || test -e /data/adb/modules_update/"+id+"; then echo present; else echo missing; fi");
                if("missing".equals(exists)){missing++;continue;}
                String expected=SetupBundle.sha(contents.get("zygisk/arm64-v8a.so"));
                root("test ! -e /data/adb/modules_update/"+id+" && test ! -e "+base+"/disable && test ! -e "+base+"/remove && "
                        +"test \"$(sha256sum "+base+"/zygisk/arm64-v8a.so | cut -d ' ' -f1)\" = "+expected+" && "
                        +"test \"$(sed -n 's/^version=//p' "+base+"/module.prop)\" = "+b.meta.get(core?"native_version":"copy_version")
                        +(core?" && test \"$(sed -n 's/^versionCode=//p' "+base+"/module.prop)\" = "+b.meta.get("native_code"):""));
            }
            if(!rebooted)throw new IOException("尚未確認重開機");
            SetupStore.save(SetupStore.prefs(c).edit().putBoolean("install_review",false).putString("result",missing>0
                    ?"已確認重開；仍缺少"+missing+"個模組，可回步驟3補裝，並非全部成功"
                    :"重開後模組檔案已核對；請開啟遊戲驗證實際載入"));
        }
    }
}

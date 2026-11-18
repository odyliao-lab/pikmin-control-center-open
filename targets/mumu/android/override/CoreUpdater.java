package dev.ody.pikmincontrol;
import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** User-confirmed bundled core update; durable intent before Magisk, no automatic reboot/replay. */
final class CoreUpdater {
    static volatile boolean running;
    static final String GAME_NOT_READY="請先開啟遊戲確認配對核心載入";
    static final String BASE="/data/adb/modules/pcc_emulator_bridge_canary", STAGED="/data/adb/modules_update/pcc_emulator_bridge_canary";
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("core_update",0);}
    static boolean pending(Context c){return running||prefs(c).getBoolean("review",false);}
    static CorePair pair(Context c) throws Exception {
        CorePair target=readPair(c,"core.properties");
        return target.unified&&prefs(c).getBoolean("rollback_active",false)&&target.sha.equals(prefs(c).getString("target_sha",""))
            ?recordedRollback(c):target;
    }
    static CorePair readPair(Context c,String asset) throws Exception {
        try(InputStream in=c.getAssets().open(asset)){CorePair p=new CorePair(new String(SetupBundle.read(in,4096),StandardCharsets.UTF_8));
            if(p.app!=UpdateStore.installed(c))throw new IOException("APK與內附核心配對不符");return p;}
    }
    static byte[] payload(Context c,CorePair p) throws Exception {
        return readPayload(c,p,bundledAsset(c,p));
    }
    // Recheck signer and the current APK catalogue even when expected hashes are cached.
    static String bundledAsset(Context c,CorePair p) throws Exception {
        SetupDeployment.trustedKey(c); // only the existing pinned APK signer may supply root code
        CorePair target=readPair(c,"core.properties");
        String asset="pikmin-core.zip";
        if(!samePair(p,target)) {
            if(!target.unified)throw new IOException("核心不是本APK支援的配對");
            asset=null;
            for(Map.Entry<String,CorePair> entry:rollbackPairs(c).entrySet()) {
                if(samePair(p,entry.getValue())) {
                    asset=entry.getKey().replace("core-rollback", "pikmin-core-rollback").replace(".properties", ".zip");
                    break;
                }
            }
            if(asset==null)throw new IOException("核心不是本APK支援的回退配對");
        }
        return asset;
    }
    static byte[] readPayload(Context c,CorePair p,String asset) throws Exception {
        try(InputStream in=c.getAssets().open(asset)){byte[] b=SetupBundle.read(in,SetupBundle.MAX);
            if(b.length!=p.size||!p.sha.equals(SetupBundle.sha(b)))throw new SecurityException("核心ZIP校驗不符");
            SetupBundle.inspectModule(b,SetupDeployment.CORE,p.version,""+p.code);return b;}
    }
    static String root(String cmd) throws Exception {return SetupDeployment.root(cmd);}
    static String checkedRoot(String step,String cmd)throws Exception {
        try{return root(cmd);}catch(Exception e){throw new IOException(step+"："+e.getMessage(),e);}
    }
    static SharedPreferences recoveryDiagnostics(Context c){return c.getSharedPreferences("core_recovery_diagnostics",0);}
    static void recoveryStep(Context c,String step)throws IOException {
        save(c,recoveryDiagnostics(c).edit().putString("step",step).putString("outcome","進行中／若已中斷請重新檢查").putLong("at",System.currentTimeMillis()));
    }
    static String recoveryReport(Context c){
        SharedPreferences p=recoveryDiagnostics(c);String step=p.getString("step","");
        return step.isEmpty()?"":"\n上次接續檢查（"+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(p.getLong("at",0)))+"）\n"+step+"："+p.getString("outcome","");
    }
    static void save(Context c,SharedPreferences.Editor e) throws IOException {if(!e.commit())throw new IOException("無法保存核心更新狀態");}
    static String runtime(Context c,CorePair p) throws Exception {
        return runtimePid(c,p).isEmpty()?"待開啟遊戲確認核心載入":"遊戲已載入配對核心";
    }
    static String runtimePid(Context c,CorePair p) throws Exception {
        String[] s=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command("pidof com.nianticlabs.pikmin || true",
            "cat /data/user/0/com.nianticlabs.pikmin/files/core_runtime.tsv 2>/dev/null || true",
            "cat /data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv 2>/dev/null || true",
            "pidof com.nianticlabs.pikmin || true"),6,32768),4);
        long now=System.currentTimeMillis();
        return p.runtime(s[1],s[0].trim(),s[3].trim(),now)&&p.gpsRuntime(s[2],s[0].trim(),s[3].trim(),now)?s[0].trim():"";
    }
    /** Read-only paired evidence. Missing/error evidence never means installed or loaded. */
    static String environmentEvidence(Context c) {
        String result="\npair_files=no\npair_pid=\n";
        try {
            CorePair p=pair(c);
            result+="pair_version="+p.version+"\npair_code="+p.code+"\npair_unified="+(p.unified?"yes":"no")+"\n";
            if(pending(c))return result;
            requireFiles(c,p);
            result+="pair_files=yes\n";
            String pid=runtimePid(c,p);
            return result+"pair_pid="+pid+"\npair_at="+System.currentTimeMillis()+"\n";
        }catch(Exception unavailable){return result;}
    }
    static String inspect(Context c) throws Exception {
        CorePair p=pair(c);String current=root("sed -n 's/^version=//p' "+BASE+"/module.prop 2>/dev/null; true");
        String result="核心 "+(current.isEmpty()?"未安裝":current)+" · 本APK配對 "+p.version;
        if(pending(c))return result+"\n"+prefs(c).getString("result","待重開機／核對");
        try{requireFiles(c,p);return result+"\n"+runtime(c,p);}catch(Exception e){return result+"\n尚未確認配對 · 請安裝或核對核心";}
    }
    private static final Map<String,String[]> EXPECTED = new LinkedHashMap<>();
    static String expectedKey(String apkIdentity,String asset,CorePair p) {
        return apkIdentity+"\n"+asset+"\n"+p.app+"\n"+p.code+"\n"+p.version+"\n"+p.size+"\n"+p.sha+"\n"+p.unified;
    }
    // Only immutable APK-derived expectations. Never call root or cache live device facts here.
    static synchronized String[] cachedExpected(String key,java.util.concurrent.Callable<String[]> load) throws Exception {
        String[] value=EXPECTED.get(key);
        if(value==null) {
            value=load.call().clone();
            if(value.length!=3||!value[0].matches("[a-f0-9]{64}")||!value[1].matches("[a-f0-9]{64}")||value[2]==null)
                throw new IOException("核心預期雜湊不完整");
            if(EXPECTED.size()>=4)EXPECTED.remove(EXPECTED.keySet().iterator().next());
            EXPECTED.put(key,value);
        }
        return value.clone();
    }
    static void requireFiles(Context c,CorePair p) throws Exception {
        String asset=bundledAsset(c,p);
        android.content.pm.PackageInfo apk=c.getPackageManager().getPackageInfo(c.getPackageName(),0);
        String identity=c.getPackageName()+"\n"+c.getApplicationInfo().sourceDir+"\n"+apk.versionCode+"\n"+apk.lastUpdateTime;
        String[] expected=cachedExpected(expectedKey(identity,asset,p),()->verifiedExpected(p,readPayload(c,p,asset)));
        requireLiveInstalled(p,expected);
    }
    static boolean samePair(CorePair a,CorePair b){return a.app==b.app&&a.code==b.code&&a.unified==b.unified&&a.size==b.size&&a.version.equals(b.version)&&a.sha.equals(b.sha);}
    static Map<String,CorePair> rollbackPairs(Context c)throws Exception {
        Map<String,CorePair> pairs=new LinkedHashMap<>();Set<Integer> codes=new HashSet<>();
        String[] assets=c.getAssets().list("");
        if(assets==null)throw new IOException("無法讀取回退目錄");
        for(String name:assets)if(name.matches("core-rollback(?:-[1-9][0-9]*)?\\.properties")) {
            CorePair p=readPair(c,name);
            if(!name.equals("core-rollback.properties")&&!name.equals("core-rollback-"+p.code+".properties"))
                throw new IOException("回退目錄版本名稱不符");
            if(!codes.add(p.code)) {
                boolean identical=false;
                for(CorePair existing:pairs.values())if(samePair(existing,p)){identical=true;break;}
                if(!identical)throw new IOException("回退目錄重複或過大");
                continue;
            }
            if(pairs.size()>=32)throw new IOException("回退目錄重複或過大");
            pairs.put(name,p);
        }
        CoreRollbackCatalog.validate(pairs.values(),UpdateStore.installed(c),readPair(c,"core.properties").code);
        return pairs;
    }
    static CorePair recordedRollback(Context c)throws Exception {
        String sha=prefs(c).getString("rollback_sha","");
        return CoreRollbackCatalog.recorded(rollbackPairs(c).values(),sha);
    }
    static CorePair installedRollback(Context c,CorePair target)throws Exception {
        String code=root("sed -n 's/^versionCode=//p' "+BASE+"/module.prop");
        CorePair p=CoreRollbackCatalog.installed(rollbackPairs(c).values(),code,target.code);
        requireInstalledFiles(p,payload(c,p));return p;
    }
    /**
     * An APK update can happen while an earlier Magisk migration is awaiting
     * review.  Recovery must keep using that transaction's private ZIPs, not
     * the newer APK assets.  Both payload hashes, module identities, stored
     * codes, and the current pinned APK signer remain mandatory.
     */
    private static CorePair pendingPair(Context c,String file,String shaKey,String codeKey)throws Exception {
        SharedPreferences p=prefs(c);String id=p.getString("migration_id","");
        String dir=CoreMigrationBackup.path(id);
        File zip=new File(c.getFilesDir(),"core-"+id+"/"+file);
        String path=zip.getAbsolutePath();
        if(!path.equals(c.getFilesDir().getAbsolutePath()+"/core-"+id+"/"+file))throw new IOException("遷移ZIP路徑不符");
        byte[] bytes;
        try(InputStream in=new FileInputStream(zip)){bytes=SetupBundle.read(in,SetupBundle.MAX);}
        String sha=p.getString(shaKey,"");
        if(!sha.matches("[a-f0-9]{64}")||!sha.equals(SetupBundle.sha(bytes)))throw new IOException("原遷移ZIP校驗不符");
        Map<String,byte[]> entries=SetupBundle.unzip(bytes,moduleEntries(),12*1024*1024);
        byte[] prop=entries.get("module.prop");if(prop==null)throw new IOException("原遷移模組資料不完整");
        Properties module=new Properties();module.load(new ByteArrayInputStream(prop));
        String version=module.getProperty("version","");String code=module.getProperty("versionCode","");
        String expected=""+CoreStoredVersion.read(p.getAll().get(codeKey));
        if(!"pcc_emulator_bridge_canary".equals(module.getProperty("id"))||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||
            !code.matches("[1-9][0-9]*")||!code.equals(expected))throw new IOException("原遷移模組身分不符");
        return new CorePair("schema=2\nversion="+version+"\ncode="+code+"\napp="+UpdateStore.installed(c)
            +"\nsize="+bytes.length+"\nsha256="+sha+"\n");
    }
    private static byte[] pendingPayload(Context c,String file,CorePair pair)throws Exception {
        String id=prefs(c).getString("migration_id","");CoreMigrationBackup.path(id);
        File zip=new File(c.getFilesDir(),"core-"+id+"/"+file);
        try(InputStream in=new FileInputStream(zip)){byte[] bytes=SetupBundle.read(in,SetupBundle.MAX);
            CoreMigrationBackup.verifyPayload(bytes,pair);return bytes;}
    }
    static CoreMigrationRootPort migrationPort(Context c)throws Exception {
        if(pending(c)&&prefs(c).getBoolean("migration",false)) {
            CorePair target=pendingPair(c,"native.zip","target_sha","target");
            CorePair rollback=pendingPair(c,"rollback.zip","rollback_sha","old");
            if(rollback.code>=target.code)throw new IOException("原遷移核心順位不符");
            return new CoreMigrationRootPort(c,target,pendingPayload(c,"native.zip",target),rollback,pendingPayload(c,"rollback.zip",rollback));
        }
        CorePair target=readPair(c,"core.properties"),rollback=recordedRollback(c);
        return new CoreMigrationRootPort(c,target,payload(c,target),rollback,payload(c,rollback));
    }
    // Private migration/recovery ZIPs always revalidate their actual bytes; no cache.
    static void requireInstalledFiles(CorePair p,byte[] b) throws Exception {
        requireLiveInstalled(p,verifiedExpected(p,b));
    }
    static String[] verifiedExpected(CorePair p,byte[] b) throws Exception {
        if(b.length!=p.size||!SetupBundle.sha(b).equals(p.sha))throw new IOException("核心檔案檢查的ZIP不符配對");
        Map<String,byte[]> files=SetupBundle.unzip(b,moduleEntries(),12*1024*1024);
        String so=SetupBundle.sha(files.get("zygisk/x86_64.so"));
        String service=SetupBundle.sha(files.get("service.sh"));
        return new String[]{so,service,CoreModuleProof.hashes(BASE,b)};
    }
    static void requireLiveInstalled(CorePair p,String[] expected) throws Exception {
        if(p.unified)checkedRoot("舊GPS模組停用檢查","test ! -e /data/adb/modules_update/zygisk_pikmin_gps_copy && "
            +"{ test ! -e /data/adb/modules/zygisk_pikmin_gps_copy || test -f /data/adb/modules/zygisk_pikmin_gps_copy/disable; }");
        checkedRoot("現有核心檔案、版本及雜湊檢查",CoreModuleProof.paths(BASE)+"test ! -e "+STAGED+" && test ! -L "+STAGED+" && test ! -e "+BASE+"/disable && test ! -L "+BASE+"/disable && test ! -e "+BASE+"/remove && test ! -L "+BASE+"/remove && "
            +"test \"$(sed -n 's/^versionCode=//p' "+BASE+"/module.prop)\" = "+p.code+" && "
            +"test \"$(sha256sum "+BASE+"/zygisk/x86_64.so | cut -d ' ' -f1)\" = "+expected[0]+" && "
            +"test \"$(sha256sum "+BASE+"/service.sh | cut -d ' ' -f1)\" = "+expected[1]+" && "+expected[2]);
    }
    static Set<String> moduleEntries(){return new HashSet<>(Arrays.asList("payload-arm64.so","LICENSE.reference","module.prop","customize.sh","service.sh","zygisk/x86_64.so","META-INF/com/google/android/update-binary","META-INF/com/google/android/updater-script","META-INF/","META-INF/com/","META-INF/com/google/","META-INF/com/google/android/","zygisk/"));}
    static void requireReady(Context c) throws Exception {
        if(CoreInstallWizard.preparing(c))throw new IOException("核心升級準備中，保留設定並暫停新操作");
        if(pending(c))throw new IOException("核心更新待核對，請到系統版本更新");
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("核心檢查必須由背景工作執行");
        CorePair p=pair(c);requireFiles(c,p);
        if(!"遊戲已載入配對核心".equals(runtime(c,p)))throw new IOException(GAME_NOT_READY);
    }
    static void install(Context context) throws Exception {
        EmulatorRuntime.hostDeploymentOnly();
        Context c=context.getApplicationContext();
        synchronized(GpsWorkLock.class){
            if(pending(c)||UpdateInstaller.pending(c))throw new IOException("已有安裝待核對");
            GpsWorkLock.requireInstallIdle(c);running=true;
        }
        try{
            CorePair p=readPair(c,"core.properties");byte[] b=payload(c,p);SetupSnapshot env=SetupDeployment.inspect(c);
            if(!env.compatible||env.available<64L*1048576||env.nativePending||env.copyPending)throw new IOException("環境不相容、空間不足或Magisk已有待重開更新");
            FeedingGateway.requirePausedForInstall();
            SetupDeployment.requireGameIdle();
            if(p.unified) {
                String present=root("if test -e "+BASE+" || test -L "+BASE+"; then echo present; else echo absent; fi");
                if(present.equals("absent")){CoreFreshInstall.install(c,p,b);return;}
                CorePair rollback=installedRollback(c,p);byte[] oldZip=payload(c,rollback);
                CoreMigrationBackup.prepare(c,p,b,rollback,oldZip);
                CoreMigrationFlow.install(migrationPort(c));return;
            }
            CoreInstallSnapshot before=new CoreInstallSnapshot(root(CoreInstallSnapshot.command()));
            String old=root("sed -n 's/^versionCode=//p' "+BASE+"/module.prop 2>/dev/null; true");
            if(!old.matches("[0-9]+")||!CorePair.upgrade(Integer.parseInt(old),p.code))throw new IOException("核心不存在或非升級；不覆寫同版／較新版，缺少核心請用新機精靈");
            String oldHash=root("sha256sum "+BASE+"/zygisk/x86_64.so | cut -d ' ' -f1");
            if(!oldHash.matches("[a-f0-9]{64}"))throw new IOException("無法核對舊核心");
            if(!before.coreCode.equals(old)||!before.coreSo.equals(oldHash))throw new IOException("核對期間模組已變動，尚未安裝");
            String id=UUID.randomUUID().toString();File dir=new File(c.getFilesDir(),"core-"+id);
            if(!dir.mkdir())throw new IOException("無法建立核心暫存");File zip=new File(dir,"native.zip");
            try(FileOutputStream out=new FileOutputStream(zip)){out.write(b);out.getFD().sync();}
            String path=dir.getAbsolutePath();if(!path.matches("/data/(user/0|data)/dev\\.ody\\.pikmincontrol/files/core-[a-f0-9-]+"))throw new IOException("核心路徑不符");
            String boot=root("cat /proc/sys/kernel/random/boot_id");
            save(c,prefs(c).edit().putBoolean("review",true).putString("boot",boot).putString("old",old).putString("old_sha",oldHash).putInt("target",p.code)
                .putString("before_snapshot",before.raw)
                .putString("path",path).putString("result","核心安裝需核對；中斷不自動重送"));
            SetupDeployment.requireGameIdle();
            if(!before.unchanged(new CoreInstallSnapshot(root(CoreInstallSnapshot.command()))))throw new IOException("安裝前模組已變動，保留待核對狀態");
            root("test ! -e "+STAGED+" && test ! -e "+BASE+"/disable && test ! -e "+BASE+"/remove && "
                +"test \"$(sed -n 's/^versionCode=//p' "+BASE+"/module.prop)\" = "+old+" && "
                +"test \"$(sha256sum '"+path+"/native.zip' | cut -d ' ' -f1)\" = "+p.sha+" && "
                +"cp -a "+BASE+" '"+path+"/backup' && magisk --install-module '"+path+"/native.zip'");
            save(c,prefs(c).edit().putString("result","核心已排入Magisk · 請手動重開機後核對"));
        }finally{running=false;}
    }
    static void review(Context c) throws Exception {
        synchronized(GpsWorkLock.class){
            if(running)throw new IOException("安裝仍在執行");
            if(pending(c)&&!CoreMigrationFlow.bootChanged(root("cat /proc/sys/kernel/random/boot_id"),"",prefs(c).getString("boot","")))throw new IOException("尚未確認重開機");
            if(prefs(c).getBoolean("fresh",false)){CoreFreshInstall.review(c);return;}
            CorePair p=pair(c);
            if(prefs(c).getBoolean("migration",false)&&pending(c)) {
                CoreMigrationRootPort port=migrationPort(c);
                String phase=port.phase();
                if(phase.equals("backup-intent")||phase.equals("backed-up")||phase.equals("gps-disable-intent")) {
                    boolean untouched=false;try{port.verifyOriginal();untouched=true;}catch(Exception changedOrUnavailable){/* retain evidence */}
                    if(untouched){save(c,prefs(c).edit().putBoolean("review",false).putBoolean("migration",false)
                        .putString("result","已重開且核心／GPS未變，未完成遷移；保留備份，可重新安裝"));return;}
                }
                if(CoreMigrationFlow.isForward(phase)||phase.equals("accepted"))CoreMigrationFlow.accept(port);
                else if(phase.startsWith("rollback-")||phase.equals("gps-restore-intent")||phase.equals("gps-reboot")||phase.equals("rolled-back"))CoreMigrationFlow.reviewRollback(port);
                else if(phase.equals("old-core-confirmed"))throw new IOException("舊核心已載入；請關閉遊戲後按「完成回退並恢復GPS」");
                else throw new IOException("遷移備份或完成紀錄需人工核對，未自動重送／清除："+phase);
                return;
            }
            if(pending(c)) {
                String old=prefs(c).getString("old","");
                String oldHash=prefs(c).getString("old_sha","");
                if(old.matches("[0-9]+")&&oldHash.matches("[a-f0-9]{64}")){
                    String unchanged=root("if test ! -e "+STAGED+" && "
                        +"test \"$(sed -n 's/^versionCode=//p' "+BASE+"/module.prop)\" = "+old+" && "
                        +"test \"$(sha256sum "+BASE+"/zygisk/x86_64.so | cut -d ' ' -f1)\" = "+oldHash+"; then echo unchanged; fi; true");
                    if("unchanged".equals(unchanged)){
                        CoreInstallSnapshot before=new CoreInstallSnapshot(prefs(c).getString("before_snapshot",""));
                        if(!before.unchanged(new CoreInstallSnapshot(root(CoreInstallSnapshot.command()))))
                            throw new IOException("GPS或核心狀態已變動，保留安裝紀錄，不能直接解除");
                        save(c,prefs(c).edit().putBoolean("review",false).putString("result","已重開且核心／GPS檔案與開關未變；更新未成功，可重新安裝"));return;
                    }
                }
            }
            requireFiles(c,p);
            if(!"遊戲已載入配對核心".equals(runtime(c,p)))throw new IOException("模組檔案已配對；請開啟遊戲後再核對載入");
            save(c,prefs(c).edit().putBoolean("review",false).putString("result","核心已重開並確認遊戲載入"));
        }
    }
    static void installSuccessor(Context context)throws Exception {
        EmulatorRuntime.hostDeploymentOnly();
        successor(context,false);
    }
    static void checkSuccessor(Context context)throws Exception {
        successor(context,true);
    }
    private static void successor(Context context,boolean checkOnly)throws Exception {
        final Context c=context.getApplicationContext();
        synchronized(GpsWorkLock.class) {
            if(running||UpdateInstaller.pending(c))throw new IOException("安裝仍在執行");
            if(!prefs(c).getBoolean("review",false)||!prefs(c).getBoolean("migration",false)||prefs(c).getBoolean("fresh",false))
                throw new IOException("沒有可接續的舊核心遷移");
            GpsWorkLock.requireCoreRecoveryIdle(c);running=true;
        }
        try {
            recoveryStep(c,"讀取原交易與私有ZIP");
            final CoreMigrationRootPort previous=migrationPort(c);
            recoveryStep(c,"核對本APK核心及回退目錄");
            final CorePair next=readPair(c,"core.properties");
            final byte[] nextZip=payload(c,next);
            // Require the exact old target in this signed APK's rollback catalogue too.
            final byte[] oldZip=payload(c,previous.target);
            CoreSuccessorFlow.Port successor=new CoreSuccessorFlow.Port() {
                public String phase(){return previous.phase();}
                public int oldTarget(){return previous.target.code;}
                public int newTarget(){return next.code;}
                public boolean rebooted()throws Exception{recoveryStep(c,"核對原安裝後的重開紀錄");return previous.rebooted();}
                public void verify()throws Exception {
                    recoveryStep(c,"確認工作及遊戲已停止");
                    GpsWorkLock.requireCoreRecoveryIdle(c);previous.requireIdle();
                    recoveryStep(c,"核對原交易備份");previous.verifyBackup();
                    recoveryStep(c,"檢查Magisk待重開目錄");previous.requireNoStaged();
                    recoveryStep(c,"核對舊GPS停用狀態");previous.verifyGpsDisabled();
                    recoveryStep(c,"核對現有核心與原目標ZIP");
                    requireInstalledFiles(previous.target,oldZip);
                    recoveryStep(c,"核對Android、遊戲版本與可用空間");
                    SetupSnapshot env=SetupDeployment.inspect(c);
                    if(!env.compatible||env.available<64L*1048576||env.nativePending||env.copyPending)
                        throw new IOException("環境不相容、空間不足或Magisk仍有待重開更新");
                }
                public void archive()throws Exception {
                    recoveryStep(c,"封存原交易紀錄");
                    // Archive every preference with its type, alongside the original immutable ZIPs.
                    File directory=new File(c.getFilesDir(),"core-"+previous.id);
                    File archive=File.createTempFile("superseded-",".xml",directory);
                    Properties saved=new Properties();
                    for(Map.Entry<String,?> entry:prefs(c).getAll().entrySet()) {
                        Object value=entry.getValue();
                        saved.setProperty(entry.getKey(),value.getClass().getName()+":"+value.toString());
                    }
                    saved.setProperty("successor_target_sha",next.sha);
                    saved.setProperty("successor_target_code",""+next.code);
                    try(FileOutputStream out=new FileOutputStream(archive)) {
                        saved.storeToXML(out,"Superseded without runtime acceptance; original root archives retained","UTF-8");
                        out.getFD().sync();
                    }
                    Properties checked=new Properties();
                    try(InputStream in=new FileInputStream(archive)){checked.loadFromXML(in);}
                    if(!saved.equals(checked))throw new IOException("舊交易封存校驗失敗");
                }
                public void prepare()throws Exception {
                    recoveryStep(c,"建立新交易及備份");
                    // prepare atomically records a NEW pending transaction before any root mutation.
                    // No accepted/clear-review intermediate state; original root/private archives remain.
                    CoreMigrationBackup.prepare(c,next,nextZip,previous.target,oldZip);
                }
                public void install()throws Exception {recoveryStep(c,"執行新交易的Magisk安裝");CoreMigrationFlow.install(migrationPort(c));}
            };
            if(checkOnly)CoreSuccessorFlow.check(successor);else CoreSuccessorFlow.run(successor);
            save(c,recoveryDiagnostics(c).edit().putString("outcome",checkOnly?"檢查通過，尚未安裝，可按接續升級":"已排入Magisk，請重開手機核對"));
        }catch(Exception failure){
            String step=recoveryDiagnostics(c).getString("step","接續升級");
            try{save(c,recoveryDiagnostics(c).edit().putString("outcome",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage()));}
            catch(Exception saveFailure){failure.addSuppressed(saveFailure);}
            throw new IOException(step+"："+failure.getMessage(),failure);
        }finally{running=false;}
    }
    static void rollback(Context c,boolean finish)throws Exception {
        EmulatorRuntime.hostDeploymentOnly();
        synchronized(GpsWorkLock.class){
            if(running||UpdateInstaller.pending(c))throw new IOException("安裝仍在執行");
            GpsWorkLock.requireCoreRecoveryIdle(c);running=true;
        }
        try {NectarModuleGateway.requireRollbackSafe();
            if(prefs(c).getBoolean("fresh",false)) {
                if(finish)throw new IOException("首次安裝請使用回復未安裝狀態，再重開核對");
                CoreFreshInstall.rollback(c);return;
            }
            CoreMigrationRootPort port=migrationPort(c);
            if(finish)CoreMigrationFlow.finishRollback(port);else CoreMigrationFlow.rollback(port);
        }finally{running=false;}
    }
}

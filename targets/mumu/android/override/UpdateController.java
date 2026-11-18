package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-level worker survives Activity recreation; downloaded APK stays app-private. */
final class UpdateController {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static volatile boolean checking,downloading,cancel;
    private static volatile String transfer="";
    static boolean busy(){return checking||downloading||UpdateInstaller.preparing;}
    static boolean downloading(){return downloading;}
    static File apk(Context c){return new File(c.getFilesDir(),"update.apk");}
    static String status(Context c) {
        if(UpdateInstaller.preparing)return "正在驗證並準備安裝…";
        if(downloading)return transfer;
        if(checking)return "正在檢查更新…";
        String install=UpdateStore.prefs(c).getString("install_result","");
        if(UpdateInstaller.pending(c))return "安裝確認中 · 暫停新增 GPS 工作";
        String check=UpdateStore.prefs(c).getString("status","尚未檢查更新");
        return check+(install.isEmpty()?"":"\n"+install);
    }
    static synchronized void check(Context input,boolean manual) {
        if(EmulatorRuntime.allowed())return;
        Context c=input.getApplicationContext();long now=System.currentTimeMillis();
        if(busy() || UpdateInstaller.pending(c))return;
        if(!manual&&!UpdateInfo.autoDue(UpdateStore.automatic(c),now,UpdateStore.prefs(c).getLong("last_attempt",0)))return;
        checking=true;UpdateStore.prefs(c).edit().putLong("last_attempt",now).apply();
        worker.execute(()->{
            try {
                String raw=UpdateClient.manifest();UpdateInfo info=new UpdateInfo(raw);
                UpdateInfo previous=UpdateStore.latest(c);
                if(previous!=null && (info.code<previous.code || (info.code==previous.code&&!info.sha256.equals(previous.sha256))))
                    throw new IllegalStateException("版本資料回退或同版檔案被置換，已拒絕");
                String state=info.minSdk>Build.VERSION.SDK_INT?"新版需要較新的 Android"
                        :info.newerThan(UpdateStore.installed(c))?"發現新版 "+info.version:"目前已是最新正式版";
                UpdateStore.prefs(c).edit().putString("manifest",raw).putString("status",state)
                        .putLong("last_success",System.currentTimeMillis()).commit();
            }catch(Exception e){UpdateStore.prefs(c).edit().putString("status","檢查失敗："+e.getMessage()).commit();}
            finally {checking=false;}
        });
    }
    static synchronized void download(Context input) {
        Context c=input.getApplicationContext();UpdateInfo info=UpdateStore.latest(c);
        if(busy() || UpdateInstaller.pending(c))return;
        if(info==null || !info.newerThan(UpdateStore.installed(c)) || info.minSdk>Build.VERSION.SDK_INT)return;
        downloading=true;cancel=false;transfer="下載準備中…";
        worker.execute(()->{
            File part=new File(c.getFilesDir(),"update.apk.part");
            try {
                UpdateClient.download(info,part,new UpdateClient.Observer(){
                    public boolean cancelled(){return cancel;}
                    public void progress(long n,long total){transfer="下載中 · "+(n*100/total)+"%";}
                });
                if(cancel)throw new IllegalStateException("已取消下載");
                verify(c,part,info);
                if(cancel)throw new IllegalStateException("已取消下載");
                if(!part.renameTo(apk(c)))throw new IllegalStateException("無法保存更新檔");
                if(!UpdateStore.prefs(c).edit().putString("download_sha",info.sha256).putString("status","已下載並驗證 · 等待你確認安裝").commit())
                    throw new IllegalStateException("無法保存下載完成狀態，請重試下載");
            }catch(Exception e){part.delete();UpdateStore.prefs(c).edit().putString("status","下載未完成："+e.getMessage()).commit();}
            finally {downloading=false;transfer="";}
        });
    }
    static void cancelDownload(){cancel=true;}
    static boolean ready(Context c){UpdateInfo i=UpdateStore.latest(c);return i!=null && i.newerThan(UpdateStore.installed(c))
            && apk(c).isFile() && i.sha256.equals(UpdateStore.prefs(c).getString("download_sha",""));}
    static void verify(Context c,File file,UpdateInfo info) throws Exception {
        if(file.length()!=info.size || !info.sha256.equals(UpdateClient.digest(file)))throw new IllegalStateException("APK校驗不符");
        PackageInfo archive=c.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo current=c.getPackageManager().getPackageInfo(c.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
        if(archive==null || !UpdateInfo.PACKAGE.equals(archive.packageName) || archive.getLongVersionCode()!=info.code
                || !info.version.equals(archive.versionName) || info.code<=current.getLongVersionCode()
                || archive.applicationInfo==null || archive.applicationInfo.minSdkVersion>Build.VERSION.SDK_INT)
            throw new IllegalStateException("APK套件、版本或Android需求不符");
        if(archive.signingInfo==null || current.signingInfo==null || archive.signingInfo.hasMultipleSigners()
                || current.signingInfo.hasMultipleSigners())throw new IllegalStateException("不支援的APK簽章");
        byte[] a=archive.signingInfo.getApkContentsSigners()[0].toByteArray(),b=current.signingInfo.getApkContentsSigners()[0].toByteArray();
        if(!MessageDigest.isEqual(a,b))throw new IllegalStateException("簽章不同，不能覆蓋目前App");
        try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(file)) {
            java.util.zip.ZipEntry meta=zip.getEntry("assets/core.properties"),core=zip.getEntry("assets/pikmin-core.zip");
            if(meta==null||core==null)throw new IllegalStateException("新版APK缺少配對核心，不允許不完整更新");
            CorePair pair;try(java.io.InputStream in=zip.getInputStream(meta)){pair=new CorePair(new String(SetupBundle.read(in,4096),java.nio.charset.StandardCharsets.UTF_8));}
            if(pair.app!=info.code)throw new IllegalStateException("新版APK與核心配對碼不符");
            try(java.io.InputStream in=zip.getInputStream(core)){byte[] data=SetupBundle.read(in,SetupBundle.MAX);
                if(data.length!=pair.size||!pair.sha.equals(SetupBundle.sha(data)))throw new IllegalStateException("新版核心雜湊不符");
                SetupBundle.inspectModule(data,SetupDeployment.CORE,pair.version,""+pair.code);}
        }
        // PackageInstaller performs the final cryptographic APK signature verification.
    }
}

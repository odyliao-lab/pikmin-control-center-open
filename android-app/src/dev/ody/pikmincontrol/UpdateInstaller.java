package dev.ody.pikmincontrol;

import android.app.PendingIntent;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import java.io.*;

/** Submits a verified, explicitly approved self-update to Android's installer. */
final class UpdateInstaller {
    static volatile boolean preparing;
    static boolean blocking(Context c){return UpdatePreparation.blocking(c)||pending(c);}
    static boolean pending(Context c) {
        int id=UpdateStore.prefs(c).getInt("session",-1);if(id<0)return false;
        if(UpdateStore.installed(c)>=UpdateStore.prefs(c).getLong("install_code",Long.MAX_VALUE)) {
            clear(c,"更新安裝完成");return false;
        }
        try {
            if(c.getPackageManager().getPackageInstaller().getSessionInfo(id)==null) {
                clear(c,"上次安裝已結束，原版本保留");return false;
            }
        }catch(RuntimeException ignored){return true;}
        return true;
    }
    static int session(Context c){return UpdateStore.prefs(c).getInt("session",-1);}
    static String pendingStatus(Context c) {
        return UpdateStore.prefs(c).getBoolean("install_needs_action", false)
                ? "請完成 Android 安裝確認" : "Android 正在處理安裝";
    }
    static boolean requestConfirmation(Context c, int id) {
        synchronized(GpsWorkLock.class) {
            if(session(c)!=id)return false;
            // This flag only describes the system callback. A preference write
            // failure must not prevent the system confirmation from opening.
            UpdateStore.prefs(c).edit().putBoolean("install_needs_action",true).commit();
            return true;
        }
    }
    static void clear(Context c,String message){
        if(!UpdateStore.prefs(c).edit().remove("session").remove("install_code").remove("install_needs_action")
                .putString("install_result",message).commit())
            throw new IllegalStateException("無法保存安裝結果");
        try{PhoneUpdateFlow.apkEnded(c);UpdatePreparation.installedOrCancelled(c);}catch(Exception failure){throw new IllegalStateException(failure);}
    }
    static void cancel(Context c) throws Exception {
        synchronized(GpsWorkLock.class) {
            int id=session(c);if(id<0)return;
            c.getPackageManager().getPackageInstaller().abandonSession(id);
            clear(c,"已取消安裝，原版本保留");
        }
    }
    static void begin(Context c) throws Exception {
        synchronized(UpdateController.class) {
            if(UpdateController.busy())throw new IllegalStateException("正在檢查或下載更新，請稍候");
            preparing=true;
        }
        try { prepare(c); } finally {preparing=false;}
    }
    private static void prepare(Context c) throws Exception {
        if(!c.getPackageManager().canRequestPackageInstalls())throw new IllegalStateException("請先允許此來源安裝App");
        UpdateInfo info=UpdateStore.latest(c);
        if(info==null)throw new IllegalStateException("請先檢查更新");
        UpdateController.verify(c,UpdateController.apk(c),info);
        UpdatePreparation.prepare(c,info.code);
        UpdatePreparation.requireReady(c);
        PackageInstaller installer=c.getPackageManager().getPackageInstaller();int id;
        synchronized(GpsWorkLock.class) {
            if(pending(c))throw new IllegalStateException("Android 已在處理安裝");
            GpsWorkLock.requireInstallIdle(c);
            PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(UpdateInfo.PACKAGE);params.setSize(info.size);
            if(Build.VERSION.SDK_INT>=31)
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            PhoneUpdateFlow.apkIntent(c);
            id=installer.createSession(params);
            if(!UpdateStore.prefs(c).edit().putInt("session",id).putLong("install_code",info.code)
                    .remove("install_needs_action").putString("install_result","Android 正在處理安裝").commit()) {
                installer.abandonSession(id);throw new IOException("無法保存安裝保護狀態");
            }
        }
        try(PackageInstaller.Session session=installer.openSession(id)) {
            try(InputStream in=new FileInputStream(UpdateController.apk(c));OutputStream out=session.openWrite("base.apk",0,info.size)) {
                byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);session.fsync(out);
            }
            session.commit(callback(c,id).getIntentSender());
        }catch(Exception e){
            try{installer.abandonSession(id);clear(c,"安裝未完成："+e.getMessage());}catch(Exception ignored){ }
            throw e;
        }
    }
    // Grant only the explicit, non-exported, session-bound installer callback.
    // Android 15 / target 35 no longer grants creator BAL permission implicitly.
    static PendingIntent callback(Context c,int id) {
        Intent intent=new Intent(c,UpdateInstallActivity.class).setAction("dev.ody.pikmincontrol.INSTALL_RESULT."+id)
                .putExtra("expected_session",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags=PendingIntent.FLAG_UPDATE_CURRENT;
        if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
        Bundle options=null;
        if(Build.VERSION.SDK_INT>=34)options=ActivityOptions.makeBasic()
                .setPendingIntentCreatorBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED).toBundle();
        return PendingIntent.getActivity(c,id,intent,flags,options);
    }

}

package dev.ody.pikmincontrol.hccompat;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.SystemClock;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class HcHook implements IXposedHookLoadPackage {
    private volatile boolean verified;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile long lastLog;
    private XSharedPreferences prefs;
    private final String fitness = "com.nianticproject.ichigo.fitness.FitnessClientHealthConnect";
    private void log(String line) { XposedBridge.log("PCC-HC: " + line); }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam p) throws Throwable {
        if (!CompatPolicy.target(p.packageName, p.processName, Build.VERSION.SDK_INT, Build.MODEL)) return;
        prefs = new XSharedPreferences(CompatPolicy.MODULE, "compat");
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!started.compareAndSet(false, true)) return;
                Context context = (Context)param.args[0];
                Thread check = new Thread(() -> verify(context), "pcc-hc-identity"); check.setDaemon(true); check.start();
            }
        });
        XposedHelpers.findAndHookMethod(fitness, p.classLoader, "isSupported", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || Boolean.TRUE.equals(param.getResult())) return;
                try {
                    prefs.reload();
                    if (!prefs.getBoolean("enabled", false) || !verified) return;
                    Object status = XposedHelpers.callStaticMethod(param.method.getDeclaringClass(), "getStatus");
                    Class<?> statusClass = XposedHelpers.findClass("com.nianticproject.ichigo.proto.HealthConnectStatus", p.classLoader);
                    boolean available = status == XposedHelpers.getStaticObjectField(statusClass, "AVAILABLE");
                    int feature = -1;
                    if (available) {
                        Class<?> shared = XposedHelpers.findClass("com.nianticproject.ichigo.util.SharedContext", p.classLoader);
                        Object context = XposedHelpers.callStaticMethod(shared, "getApplicationContext");
                        Class<?> clientClass = XposedHelpers.findClass("androidx.health.connect.client.HealthConnectClient", p.classLoader);
                        Object client = XposedHelpers.callStaticMethod(clientClass, "getOrCreate", context);
                        Object features = XposedHelpers.callMethod(client, "getFeatures");
                        feature = (Integer)XposedHelpers.callMethod(features, "getFeatureStatus", 1);
                    }
                    boolean supported = CompatPolicy.support(true, verified, available, feature);
                    param.setResult(supported);
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastLog >= 5000) { lastLog = now; log("gate provider=" + available + " backgroundFeature=" + feature + " support=" + supported); }
                } catch (Throwable e) { log("gate-failed " + e.getClass().getSimpleName()); /* keep real false */ }
            }
        });
        // Observe real results; never replace permission or step data.
        XposedHelpers.findAndHookMethod(fitness, p.classLoader, "getPermissionStatus", boolean.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { log("permission " + (Boolean.TRUE.equals(param.args[0]) ? "foreground" : "background") + "=" + (param.hasThrowable() ? "error" : String.valueOf(param.getResult()))); }
        });
        XposedHelpers.findAndHookMethod(fitness, p.classLoader, "queryStepCounts", int.class, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || param.getResult() == null) { log("query error"); return; }
                try {
                    Object result=param.getResult();
                    int count=(Integer)XposedHelpers.callMethod(result,"getStepCountsCount");
                    if(count<0 || count>1000) { log("query-result-over-limit"); return; }
                    long total=0;
                    for(int i=0;i<count;i++) {
                        Object bucket=XposedHelpers.callMethod(result,"getStepCounts",i);
                        total+=((Number)XposedHelpers.callMethod(bucket,"getStepCount")).longValue();
                    }
                    log("query actualBuckets="+count+" actualSteps="+total+" source="+XposedHelpers.callMethod(result,"getFitnessDataSource"));
                }
                catch (Throwable e) { log("query-result-unreadable"); }
            }
        });
        log("hooks-installed; identity pending; disabled unless opted in");
    }
    private void verify(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(CompatPolicy.PACKAGE, 0);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long deadline = SystemClock.elapsedRealtime() + 15000;
            try (FileInputStream in = new FileInputStream(info.applicationInfo.sourceDir)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = in.read(buffer)) != -1) { if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("identity-timeout"); digest.update(buffer,0,n); }
            }
            StringBuilder hash = new StringBuilder(); for (byte b : digest.digest()) hash.append(String.format("%02x", b & 255));
            verified = CompatPolicy.identity(info.versionName, info.getLongVersionCode(), hash.toString());
            log("identity=" + verified + " game=" + info.versionName);
        } catch (Throwable e) { verified = false; log("identity-failed " + e.getClass().getSimpleName()); }
    }
}

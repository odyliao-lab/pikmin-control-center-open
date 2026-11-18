package dev.ody.pikmincontrol;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;

final class StepUnsupportedHealth implements StepHealthPort {
    public boolean available() { return false; }
    public boolean canRead() { return false; }
    public boolean canWrite() { return false; }
    public boolean canReadInBackground() { return false; }
    public WriteResult write(StepPlan plan, long timeoutMs) { return WriteResult.fail("unsupported", "不支援"); }
    public LookupResult findByClientRecordId(String clientRecordId, long rangeStartMs, long rangeEndMs, long timeoutMs) {
        return LookupResult.fail("unsupported");
    }
    public AggregateResult aggregate(long rangeStartMs, long rangeEndMs, boolean thisAppOnly, long timeoutMs) {
        return AggregateResult.unknown("unsupported");
    }
    public PageResult readThisApp(long rangeStartMs, long rangeEndMs, long pageToken, int pageSize, long timeoutMs) {
        return new PageResult(false, false, null, -1L, "unsupported");
    }
}

final class StepAndroidClock implements StepClock {
    public long wallMillis() { return System.currentTimeMillis(); }
    public long elapsedMillis() { return SystemClock.elapsedRealtime(); }
}

final class StepHealthGateway {
    static StepHealthPort create(Context context) {
        if (Build.VERSION.SDK_INT < 33) return new StepUnsupportedHealth();
        StepHealthPort port=Build.VERSION.SDK_INT == 33?new StepHealthGatewayApi33(context):new StepHealthGatewayApi34(context);
        return new InstallAwareSteps(context.getApplicationContext(),port);
    }

    static StepEngine engine(Context context) {
        StepStore store = new StepAndroidStore(context);
        return new StepEngine(new StepAndroidClock(), new StepSystemZone(), new StepRandom(), store,
                create(context), new StepWorkLock(store), true);
    }
}

/** Fence every writer, including manual/catch-up; HC I/O stays outside the admission monitor. */
final class InstallAwareSteps implements StepHealthPort {
    private final Context c;private final StepHealthPort inner;
    InstallAwareSteps(Context c,StepHealthPort inner){this.c=c;this.inner=inner;}
    public boolean available(){return inner.available();}
    public boolean canRead(){return inner.canRead();}
    public boolean canWrite(){return inner.canWrite();}
    public boolean canReadInBackground(){return inner.canReadInBackground();}
    public WriteResult write(StepPlan plan,long timeout){
        if(!UpdatePreparation.STEPS.enter(()->UpdateInstaller.blocking(c)||CoreInstallWizard.preparing(c)))
            return WriteResult.fail("install-paused","更新準備暫停，尚未送出；原開關保留");
        try{return inner.write(plan,timeout);}finally{UpdatePreparation.STEPS.leave();}
    }
    public LookupResult findByClientRecordId(String id,long start,long end,long timeout){return inner.findByClientRecordId(id,start,end,timeout);}
    public AggregateResult aggregate(long start,long end,boolean app,long timeout){return inner.aggregate(start,end,app,timeout);}
    public PageResult readThisApp(long start,long end,long token,int size,long timeout){return inner.readThisApp(start,end,token,size,timeout);}
}

final class StepPermissions {
    static final String READ_STEPS = "android.permission.health.READ_STEPS";
    static final String WRITE_STEPS = "android.permission.health.WRITE_STEPS";
    static final String READ_BACKGROUND = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND";
    static final String MANAGE_ACTION = "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS";

    static boolean notifications(Context context) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean healthRead(Context context) {
        if (Build.VERSION.SDK_INT == 33) return StepHealthGatewayApi33.permission(context,READ_STEPS);
        if (Build.VERSION.SDK_INT < 34) return false;
        return context.checkSelfPermission(READ_STEPS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean healthWrite(Context context) {
        if (Build.VERSION.SDK_INT == 33) return StepHealthGatewayApi33.permission(context,WRITE_STEPS);
        if (Build.VERSION.SDK_INT < 34) return false;
        return context.checkSelfPermission(WRITE_STEPS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean healthBackground(Context context) {
        if (Build.VERSION.SDK_INT == 33) return StepHealthGatewayApi33.backgroundAvailable(context) && StepHealthGatewayApi33.permission(context,READ_BACKGROUND);
        if (Build.VERSION.SDK_INT < 34) return false;
        return context.checkSelfPermission(READ_BACKGROUND) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean batteryUnrestricted(Context context) {
        android.os.PowerManager power = context.getSystemService(android.os.PowerManager.class);
        return power == null || power.isIgnoringBatteryOptimizations(context.getPackageName());
    }

    static String[] requested() {
        return new String[] { READ_STEPS, WRITE_STEPS, READ_BACKGROUND };
    }

    static android.content.Intent manageHealthIntent(String packageName) {
        if (Build.VERSION.SDK_INT == 33) return new android.content.Intent("androidx.health.ACTION_HEALTH_CONNECT_SETTINGS").setPackage("com.google.android.apps.healthdata");
        return new android.content.Intent(MANAGE_ACTION).putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, packageName);
    }
    static void open(android.app.Activity activity) {
        if (Build.VERSION.SDK_INT == 33) { StepHealthGatewayApi33.requestPermissions(activity); return; }
        if (Build.VERSION.SDK_INT < 33) return;
        try { activity.startActivity(manageHealthIntent(activity.getPackageName())); }
        catch (RuntimeException error) { activity.requestPermissions(requested(),162); }
    }
}

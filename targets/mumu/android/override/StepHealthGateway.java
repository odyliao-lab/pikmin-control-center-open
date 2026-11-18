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
        if (Build.VERSION.SDK_INT < StepHealthConnectProbe.MIN_SDK) return new StepUnsupportedHealth();
        return new StepHealthGatewayApi34(context);
    }

    static StepEngine engine(Context context) {
        StepStore store = new StepAndroidStore(context);
        return new StepEngine(new StepAndroidClock(), new StepSystemZone(), new StepRandom(), store,
                create(context), new StepWorkLock(store), true);
    }
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
        if (Build.VERSION.SDK_INT < 34) return false;
        return context.checkSelfPermission(READ_STEPS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean healthWrite(Context context) {
        if (Build.VERSION.SDK_INT < 34) return false;
        return context.checkSelfPermission(WRITE_STEPS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static boolean healthBackground(Context context) {
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
        return new android.content.Intent(MANAGE_ACTION).putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, packageName);
    }
}

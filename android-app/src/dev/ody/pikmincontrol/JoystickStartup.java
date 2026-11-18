package dev.ody.pikmincontrol;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One bounded foreground boot/manual round. No global thermal writes, TELEPORT or STOP. */
final class JoystickStartup {
    static final String PACKAGE = JoystickAdapter.PACKAGE;
    static final String PREFS = "pikmin_joystick_startup";
    private static final String SERVICE = PACKAGE + "/com.theappninjas.fakegpsjoystick.service.OverlayService";
    private static String pendingToken;
    private static Session active;

    static final class Session {
        final String token;
        final boolean manual;
        final long deadline = SystemClock.elapsedRealtime() + 120_000;
        volatile boolean cancelled;
        volatile boolean finished, destroyed;
        Session(String token, boolean manual) { this.token = token; this.manual = manual; }
    }
    private static final class Retryable extends Exception {
        Retryable(String message) { super(message); }
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    private static void prepare(Context c) {
        c.getSystemService(JobScheduler.class).cancel(JoystickStartupJob.JOB_ID);
        SharedPreferences p = prefs(c);
        int boot = Settings.Global.getInt(c.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        if (p.getInt("engine", 0) != 2 || (boot >= 0 && p.getInt("boot", -2) != boot)) {
            p.edit().putInt("engine", 2).putInt("boot", boot).putInt("attempts", 0)
                    .putInt("request_failures", 0).putInt("launches", 0).putString("state", "")
                    .remove("token").putLong("at", 0).commit();
        }
    }

    static synchronized boolean busy() { return pendingToken != null || active != null; }

    private static boolean coreWaiting(Context c) {
        return UpdatePreparation.blocking(c) || CoreInstallWizard.preparing(c) || CoreUpdater.pending(c) || CoreUpdater.running;
    }

    private static void migrateCoreWait(Context c) {
        SharedPreferences p = prefs(c);
        if (JoystickStartupPolicy.legacyCoreWait(p.getString("state", ""), p.getInt("attempts", 0), p.getString("message", "")))
            record(c, "deferred-core", "等待核心升級核對完成後補啟動 GPS");
        else if (JoystickStartupPolicy.legacyWorkWait(p.getString("state", ""), p.getInt("attempts", 0), p.getString("message", "")))
            record(c, "deferred-work", "等待既有 GPS 工作完成；開啟控制中心可補檢");
    }

    /** Called after core acceptance or settings-lease release; never revives a cancelled round. */
    static synchronized void afterCoreReady(Context c) {
        if (!ControlSettings.startJoystickOnBoot(c) || busy()) return;
        prepare(c);
        migrateCoreWait(c);
        if ("deferred-core".equals(prefs(c).getString("state", "")) && !coreWaiting(c) && !GpsWorkLock.observedAnyHeld(c)) recoverOnOpen(c);
    }

    static synchronized void recoverOnOpen(Context c) {
        if (!ControlSettings.startJoystickOnBoot(c)) return;
        prepare(c);
        migrateCoreWait(c);
        SharedPreferences p = prefs(c);
        if (JoystickStartupPolicy.recover(true, busy(), p.getString("state", ""), p.getInt("attempts", 0),
                System.currentTimeMillis() - p.getLong("at", 0))) request(c, false, "app-open");
    }

    static synchronized void request(Context c, boolean manual, String source) {
        if (!manual && !ControlSettings.startJoystickOnBoot(c)) return;
        prepare(c);
        if (busy()) return;
        SharedPreferences p = prefs(c);
        String state = p.getString("state", "");
        if (!manual && (p.getInt("attempts", 0) >= 3 || p.getInt("request_failures", 0) >= 3
                || "ready".equals(state) || "failed".equals(state) || "cancelled".equals(state))) return;
        if (!c.getSystemService(UserManager.class).isUserUnlocked()) {
            record(c, "deferred", "等待首次解鎖後啟動 GPS"); return;
        }
        if (manual) p.edit().putInt("attempts", 0).putInt("request_failures", 0).putInt("launches", 0).commit();
        if (coreWaiting(c)) {
            p.edit().putBoolean("manual", manual).putString("source", source).commit();
            record(c, "deferred-core", "等待核心升級核對完成後補啟動 GPS"); return;
        }
        pendingToken = UUID.randomUUID().toString();
        p.edit().putString("token", pendingToken).putBoolean("manual", manual).putString("source", source).commit();
        record(c, "queued", "準備前景啟動檢查（" + source + "）");
        try {
            c.startForegroundService(new Intent(c, JoystickStartupService.class).putExtra("token", pendingToken));
        } catch (RuntimeException error) {
            pendingToken = null;
            int failures = p.getInt("request_failures", 0) + 1;
            p.edit().putInt("request_failures", failures).remove("token").commit();
            record(c, failures >= 3 ? "failed" : "deferred", "系統拒絕前景啟動：" + error.getClass().getSimpleName()
                    + "；請開啟控制中心補檢（不改用受限背景排程）");
        }
    }

    static synchronized Session begin(Context c, String token) {
        if (token == null || active != null || !token.equals(prefs(c).getString("token", ""))) return null;
        pendingToken = null;
        active = new Session(token, prefs(c).getBoolean("manual", false));
        return active;
    }

    static synchronized void cancelAutomatic(Context c) {
        c.getSystemService(JobScheduler.class).cancel(JoystickStartupJob.JOB_ID);
        if (prefs(c).getBoolean("manual", false)) return;
        cancel(c);
    }

    static synchronized void cancel(Context c) {
        pendingToken = null;
        prefs(c).edit().remove("token").commit();
        if (active != null) active.cancelled = true;
        record(c, "cancelled", "啟動檢查已停止；不停止既有 GPS");
        c.stopService(new Intent(c, JoystickStartupService.class));
    }

    static synchronized void creationFailed(Context c, RuntimeException error) {
        pendingToken = null;
        prefs(c).edit().remove("token").commit();
        record(c, "failed", "無法建立啟動通知／服務：" + error.getClass().getSimpleName());
    }

    static synchronized void progress(Context c, Session s, String state, String message) throws InterruptedException {
        if (active != s || s.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        record(c, state, message);
    }

    static synchronized void complete(Context c, Session s, String state, String message) {
        if (active != s) return;
        if (!s.cancelled) record(c, state, message);
        prefs(c).edit().remove("token").commit();
        s.finished = true;
        if (s.destroyed) active = null;
    }

    static synchronized void destroyed(Context c, Session s) {
        if (active != s) return;
        s.destroyed = true;
        if (!s.finished && !s.cancelled) {
            s.cancelled = true;
            record(c, "interrupted", "前景啟動檢查中斷；開啟控制中心可補檢");
        }
        // Hold the owner until root/lock cleanup completes; no duplicate worker during destruction.
        if (s.finished) { active = null; afterCoreReady(c); }
    }

    static void record(Context c, String state, String message) {
        prefs(c).edit().putLong("at", System.currentTimeMillis()).putString("state", state).putString("message", message).commit();
        android.util.Log.i("PikminJoystickStartup", state + ": " + message);
    }

    static String status(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("at", 0);
        if (at == 0) return "尚未檢查 GPS 服務";
        String message = p.getString("message", ""), state = p.getString("state", "");
        if (!busy() && ("checking".equals(state) || "starting".equals(state) || "queued".equals(state) || "retry".equals(state))
                && System.currentTimeMillis() - at > 120_000) message = "啟動檢查已中斷；開啟控制中心可補檢，或手動重試";
        return "上次檢查 " + new SimpleDateFormat("MM/dd HH:mm:ss", Locale.TAIWAN).format(new Date(at))
                + "\n" + message + "\n本輪檢查 " + p.getInt("attempts", 0) + "/3（短時前景服務）";
    }

    static void run(Context c, Session s) {
        boolean acquired = false;
        String state = "failed", message = "啟動檢查未完成";
        try {
            // Boot also restores collection/settings. Wait inside this same bounded
            // foreground round; do not spend a provider attempt or clear their owner.
            long lockDeadline = Math.min(s.deadline, SystemClock.elapsedRealtime() + 60_000);
            while (!acquired) {
                if (s.cancelled || Thread.currentThread().isInterrupted()
                        || (!s.manual && !ControlSettings.startJoystickOnBoot(c))) throw new InterruptedException();
                try {
                    GpsWorkLock.acquire(c, GpsWorkLock.JOYSTICK_STARTUP);
                    acquired = true;
                } catch (GpsWorkLock.Busy error) {
                    if (SystemClock.elapsedRealtime() >= lockDeadline) {
                        state = "deferred-work";
                        message = error.getMessage() + "；稍後開啟控制中心可補檢";
                        return;
                    }
                    progress(c, s, "waiting-work", error.getMessage() + "；等待完成後啟動 GPS");
                    Thread.sleep(500);
                }
            }
            while (prefs(c).getInt("attempts", 0) < 3) {
                int attempt = prefs(c).getInt("attempts", 0) + 1;
                prefs(c).edit().putInt("attempts", attempt).commit();
                try {
                    checkAllowed(c, s);
                    progress(c, s, "checking", "正在檢查 GPS（第 " + attempt + " 次）");
                    message = checkProvider(c, s);
                    state = "ready";
                    return;
                } catch (Retryable error) {
                    if (!JoystickStartupPolicy.retry(true, attempt, s.manual || ControlSettings.startJoystickOnBoot(c))) throw error;
                    progress(c, s, "retry", error.getMessage() + "；稍後重查");
                    long until = Math.min(s.deadline, SystemClock.elapsedRealtime() + attempt * 10_000L);
                    while (SystemClock.elapsedRealtime() < until) {
                        if (s.cancelled || (!s.manual && !ControlSettings.startJoystickOnBoot(c))) throw new InterruptedException();
                        GpsWorkLock.touch(c, GpsWorkLock.JOYSTICK_STARTUP);
                        Thread.sleep(500);
                    }
                }
            }
            message = "已達三次檢查上限；請檢查設定後手動重試";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); state = "cancelled"; message = "檢查中止；不停止既有 GPS";
        } catch (GpsWorkLock.CoreNotReady error) {
            state = "deferred-core"; message = "等待核心升級核對完成後補啟動 GPS";
        } catch (Exception error) {
            message = (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()) + "；本輪已結束";
        } finally {
            if (acquired) GpsWorkLock.release(c, GpsWorkLock.JOYSTICK_STARTUP);
            complete(c, s, state, message);
        }
    }

    private static String checkProvider(Context c, Session s) throws Exception {
        PackageInfo info = c.getPackageManager().getPackageInfo(PACKAGE, 0);
        String identity = info.applicationInfo.uid + "/" + PACKAGE;
        long deadline = Math.min(s.deadline, SystemClock.elapsedRealtime() + 35_000), previous = -1;
        boolean requested = false;
        while (SystemClock.elapsedRealtime() < deadline) {
            checkAllowed(c, s);
            GpsWorkLock.touch(c, GpsWorkLock.JOYSTICK_STARTUP);
            JoystickReadiness sample = JoystickReadiness.parse(root(s, "dumpsys location"), identity);
            long now = SystemClock.elapsedRealtime();
            if (!sample.known) throw new IllegalStateException("無法判讀 GPS provider，不啟動服務");
            if (sample.mock && !sample.own) throw new IllegalStateException("其他或未知工具正在控制 GPS，不接管");
            if (sample.fresh(now)) {
                if (previous >= 0 && sample.fixMs > previous) return requested
                        ? "GPS 服務已自動啟動，定位持續更新" : "GPS 原已運作，定位持續更新（未重啟）";
                previous = sample.fixMs;
            } else {
                previous = -1;
                if (!sample.own && !requested) {
                    if (!JoystickAdapter.supported(info.versionName)) throw new IllegalStateException("JoyStick " + info.versionName + " 尚未驗證，請手動啟動");
                    String saved = "/data/user/0/" + PACKAGE + "/shared_prefs/" + PACKAGE + "_preferences.xml";
                    String savedCheck = root(s, "if grep -q 'name=\"last_latitude\"' " + saved
                            + " && grep -q 'name=\"last_longitude\"' " + saved + "; then echo saved; else echo missing; fi");
                    if (!"saved".equals(savedCheck.trim())) throw new IllegalStateException("JoyStick 尚未保存位置，請先手動設定並啟動一次");
                    checkAllowed(c, s);
                    progress(c, s, "starting", "啟動 GPS 服務，沿用 JoyStick 保存的位置");
                    prefs(c).edit().putInt("launches", prefs(c).getInt("launches", 0) + 1).commit();
                    String result = root(s, "am start-foreground-service --user 0 -n " + SERVICE);
                    if (result.contains("Error") || result.contains("Exception")) throw new IllegalStateException("JoyStick 拒絕啟動，請檢查定位與懸浮權限");
                    requested = true;
                }
            }
            Thread.sleep(1500);
        }
        throw new Retryable("GPS 尚未持續更新，不強制重啟既有服務");
    }

    private static void checkAllowed(Context c, Session s) throws Exception {
        if (s.cancelled || Thread.currentThread().isInterrupted() || (!s.manual && !ControlSettings.startJoystickOnBoot(c))) throw new InterruptedException();
        if (SystemClock.elapsedRealtime() >= s.deadline) throw new IllegalStateException("啟動檢查已達120秒上限");
        if (!c.getSystemService(UserManager.class).isUserUnlocked()) throw new Retryable("等待首次解鎖");
        // Phone GPS startup does not impose an additional thermal veto.
        // Android retains its own thermal management and service restrictions.
    }

    private static String root(Session s, String command) throws Exception {
        if (s.cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        long remaining = s.deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IllegalStateException("啟動檢查已達120秒上限");
        Process process = new ProcessBuilder("su", "-mm", "-c", command).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] bytes = new byte[4096]; int count;
                while ((count = stream.read(bytes)) >= 0) if (output.size() + count <= 1024 * 1024) output.write(bytes, 0, count);
            } catch (Exception ignored) { }
        }, "joystick-root-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(Math.min(8000, remaining), TimeUnit.MILLISECONDS)) throw new Retryable("Root 指令逾時");
            reader.join(1000);
            if (reader.isAlive()) throw new Retryable("Root 輸出未完成");
            if (process.exitValue() != 0) throw new IllegalStateException("Root 指令失敗，請確認授權");
            return output.toString("UTF-8");
        } finally {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (Exception ignored) { }
        }
    }
}

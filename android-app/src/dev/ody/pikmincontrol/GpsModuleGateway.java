package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** Compatible copy controls and external JoyStick; module lifecycle belongs to CoreUpdater. */
final class GpsModuleGateway {
    private static final String FILES = "/data/user/0/com.nianticlabs.pikmin/files";
    private static final String MODE = FILES + "/gps_copy_mode.txt";
    private static final String STATUS = FILES + "/gps_copy_status.tsv";
    private static final String GAME_GPS = FILES + "/nectar_system_gps.tsv";
    private static final String MODULE = "/data/adb/modules/zygisk_pikmin_gps_copy";
    private static final String MODULE_PROP = MODULE + "/module.prop";
    private static final String DISABLE = MODULE + "/disable";
    private static final String JOYSTICK = JoystickAdapter.PACKAGE;

    private GpsModuleGateway() {}

    /** Read-only two-sample check; never launches or takes over another GPS app. */
    static String readiness(Context context) throws Exception {
        int uid = context.getPackageManager().getApplicationInfo(JOYSTICK, 0).uid;
        String identity = uid + "/" + JOYSTICK;
        JoystickReadiness first = JoystickReadiness.parse(root("dumpsys location"), identity);
        Thread.sleep(1100);
        JoystickReadiness second = JoystickReadiness.parse(root("dumpsys location"), identity);
        return JoystickReadiness.label(first, second, android.os.SystemClock.elapsedRealtime());
    }

    static Snapshot read(Context context) throws Exception {
        String raw = root("printf '%s\\n' '---MODULE---'; test -f " + MODULE_PROP + " && cat " + MODULE_PROP + " || true; test -f " + DISABLE + " && echo disabled; "
                + "printf '%s\\n' '---MODE---'; cat " + MODE + " 2>/dev/null || true; "
                + "printf '%s\\n' '---STATUS---'; cat " + STATUS + " 2>/dev/null || true;");
        boolean installed = false, moduleEnabled = false;
        String version = "未安裝", mode = "on", status = "等待模組啟動", coordinates = "", section = "";
        for (String line : raw.split("\\r?\\n")) {
            if (line.startsWith("---") && line.endsWith("---")) { section = line; continue; }
            if ("---MODULE---".equals(section)) {
                if (line.startsWith("version=")) { version = line.substring(8).trim(); installed = moduleEnabled = true; }
                if ("disabled".equals(line.trim())) moduleEnabled = false;
            } else if ("---MODE---".equals(section) && !line.trim().isEmpty()) mode = line.trim();
            else if ("---STATUS---".equals(section) && !line.trim().isEmpty()) {
                String[] f = line.split("\\t", -1); status = f.length >= 2 ? f[1] : line;
                if (f.length >= 4 && !"0.0000000".equals(f[2])) coordinates = f[2] + "," + f[3];
            }
        }
        CorePair pair=CoreUpdater.pair(context);
        if(pair.unified) {
            installed=false;moduleEnabled=false;version="整合核心 "+pair.version;
            status="整合核心待安裝／核對";
            if(!CoreUpdater.pending(context))try {
                CoreUpdater.requireFiles(context,pair);installed=true;
                moduleEnabled=!CoreUpdater.runtimePid(context,pair).isEmpty();
                status=moduleEnabled?("on".equals(mode)?"整合GPS已載入":"整合GPS已載入 · 複製已關閉"):"整合GPS待開啟遊戲核對";
            }catch(Exception unavailable){/* Keep evidence fail-closed; do not enable legacy GPS. */}
        }
        // The launcher-visible app name, not the (unrelated-looking) package
        // id -- the installed flight engine is a rebranded "Fake GPS
        // Joystick" APK whose package name gives no hint of that.
        boolean joystickInstalled; String joystickLabel;
        try {
            android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(JOYSTICK, 0);
            joystickLabel = String.valueOf(context.getPackageManager().getApplicationLabel(info));
            joystickInstalled = true;
        } catch (Exception notInstalled) { joystickLabel = ""; joystickInstalled = false; }
        return new Snapshot(installed, moduleEnabled, "on".equals(mode), version, status, coordinates, joystickInstalled, joystickLabel);
    }

    /** Compatible with the existing on/off control file. */
    static void setAutoCopy(Context c,boolean enabled) throws Exception {
        synchronized(GpsWorkLock.class) {
            if(CoreUpdater.pending(c)||SetupDeployment.pending(c)||UpdateInstaller.blocking(c))throw new IllegalStateException("核心安裝／回退待核對，暫不變更複製設定");
            if(CoreUpdater.prefs(c).getBoolean("migration",false)||CoreUpdater.prefs(c).getBoolean("fresh",false))
                CoreUpdater.save(c,CoreUpdater.prefs(c).edit().putBoolean("copy_user_override",true));
            farmRead("set -e; test ! -L "+MODE+"; printf '" + (enabled ? "on" : "off") + "\\n' > " + MODE + "; chmod 0644 " + MODE);
        }
    }

    static void flyTo(Context context, String coordinates) {
        String[] f = coordinates.split(",", -1); if (f.length != 2) throw new IllegalArgumentException("GPS 座標格式不正確");
        float lat = Float.parseFloat(f[0]), lng = Float.parseFloat(f[1]);
        if (lat < -90 || lat > 90 || lng < -180 || lng > 180) throw new IllegalArgumentException("GPS 座標超出範圍");
        Intent intent = new Intent("theappninjas.gpsjoystick.TELEPORT").setPackage(JOYSTICK).putExtra("lat", lat).putExtra("lng", lng);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }

    static void stopFlight(Context context) {
        Intent intent = new Intent("theappninjas.gpsjoystick.STOP").setPackage(JOYSTICK);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent); else context.startService(intent);
    }

    /** Reads Android's current mock GPS provider, not the requested destination. */
    static Point currentMockLocation() throws Exception {
        return parseMockLocation(root(mockReadCommand()));
    }
    private static String mockReadCommand() {
        return "dumpsys location | sed -n '/gps provider \\[mock\\]:/,/fused provider:/p' | grep -m1 'last location=Location' || true";
    }

    /** PID sandwiches the native heartbeat; no legacy nectar_system_gps source. */
    static FarmLocation farmLocation(Context context) throws Exception {
        String identity = context.getPackageManager().getApplicationInfo(JOYSTICK, 0).uid + "/" + JOYSTICK;
        String[] parts = ProbeSnapshot.sections(farmRead(ProbeSnapshot.command(
                OwnerGameProcess.command(), "dumpsys location",
                "cat " + FILES + "/nearby_gate_status.tsv 2>/dev/null || true",
                OwnerGameProcess.command())), 4);
        return new FarmLocation(parts[2], parts[0], parts[3], System.currentTimeMillis(),
                android.os.SystemClock.elapsedRealtime(), JoystickReadiness.parse(parts[1], identity));
    }
    // Shared bounded read for flower location and actual native planting state.
    static RouteObservation routeObservation(Context context,String pid)throws Exception {
        String identity=context.getPackageManager().getApplicationInfo(JOYSTICK,0).uid+"/"+JOYSTICK;
        String[] p=ProbeSnapshot.sections(farmRead(ProbeSnapshot.command(
                OwnerGameProcess.command(), "dumpsys location",
                "cat "+FILES+"/nearby_gate_status.tsv 2>/dev/null || true",
                "cat "+FILES+"/planting_control_status.tsv 2>/dev/null || true",
                "cat "+FILES+"/automation_session.tsv 2>/dev/null || true",
                OwnerGameProcess.command())),6);
        return new RouteObservation(p,identity,pid,System.currentTimeMillis(),android.os.SystemClock.elapsedRealtime());
    }
    static String farmRead(String command) throws Exception {
        Process process = new ProcessBuilder("su", "-mm", "-c", command).redirectErrorStream(true).start();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n=stream.read(buffer))>=0) if (out.size()+n<=1024*1024) out.write(buffer,0,n);
            } catch (Exception ignored) { }
        }, "farm-location-read");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) throw new IllegalStateException("花田定位讀取逾時");
            reader.join(500);
            if (reader.isAlive() || process.exitValue()!=0) throw new IllegalStateException("花田定位讀取未完成");
            return out.toString("UTF-8");
        } finally {
            process.destroyForcibly();
            try { process.getInputStream().close(); } catch (Exception ignored) { }
        }
    }
    private static Point parseMockLocation(String raw) {
        int start = raw.indexOf("Location[gps ");
        if (start < 0) throw new IllegalStateException("未讀到 GPS JoyStick 的實際位置");
        int comma = raw.indexOf(',', start), end = raw.indexOf(' ', comma);
        if (comma < 0 || end < 0) throw new IllegalStateException("GPS 位置格式不正確");
        try { return new Point(Double.parseDouble(raw.substring(start + 13, comma)), Double.parseDouble(raw.substring(comma + 1, end))); }
        catch (NumberFormatException e) { throw new IllegalStateException("GPS 位置格式不正確"); }
    }

    /**
     * Starts the installed flight engine without sending it a destination or a
     * STOP command.  This is deliberately separate from {@link #flyTo}: boot
     * setup must never alter the current mock location.
     */
    static boolean launchJoyStick(Context context) {
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(JOYSTICK);
        if (launch == null) return false;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        context.startActivity(launch);
        return true;
    }

    /**
     * Legacy system-provider mirror, retained only for historical read benchmarks.
     * This is NOT processed game position and must not authorize flower arrival.
     */
    static Point currentGameLocation() throws Exception {
        return parseGameLocation(root(gameReadCommand()));
    }
    private static String gameReadCommand() {
        return "test -s " + GAME_GPS + " && stat -c '%Y' " + GAME_GPS + " && cat " + GAME_GPS + " || true";
    }
    private static Point parseGameLocation(String raw) {
        raw = raw.trim();
        String[] lines = raw.split("\\r?\\n");
        if (lines.length < 2) throw new IllegalStateException("遊戲端 GPS 尚未更新");
        long modified;
        try { modified = Long.parseLong(lines[0].trim()); }
        catch (NumberFormatException e) { throw new IllegalStateException("遊戲端 GPS 時間格式不正確"); }
        long age = System.currentTimeMillis() / 1000L - modified;
        if (age < 0 || age > 15L)
            throw new IllegalStateException("遊戲端 GPS 已過期");
        String[] fields = lines[1].trim().split("\\t");
        if (fields.length < 2) throw new IllegalStateException("遊戲端 GPS 格式不正確");
        try { return new Point(Double.parseDouble(fields[0]), Double.parseDouble(fields[1])); }
        catch (NumberFormatException e) { throw new IllegalStateException("遊戲端 GPS 格式不正確"); }
    }

    /** One root process per arrival poll; neither source is cached across polls. */
    static ArrivalSnapshot arrivalSnapshot(String taskId) throws Exception {
        String[] parts = ProbeSnapshot.sections(root(ProbeSnapshot.command(mockReadCommand(),
                taskId == null ? gameReadCommand() : DispatchProbeGateway.candidateReadCommand())), 2);
        Point provider = parseMockLocation(parts[0]);
        return taskId == null
                ? new ArrivalSnapshot(provider, parseGameLocation(parts[1]), null)
                : new ArrivalSnapshot(provider, null, DispatchProbeGateway.parseGameSide(parts[1], taskId));
    }
    static final class ArrivalSnapshot {
        final Point provider, game;
        final DispatchProbeGateway.GameSide candidate;
        ArrivalSnapshot(Point provider, Point game, DispatchProbeGateway.GameSide candidate) {
            this.provider = provider; this.game = game; this.candidate = candidate;
        }
    }

    static double distanceMetres(double lat1, double lng1, double lat2, double lng2) {
        double a = Math.sin(Math.toRadians(lat2 - lat1) / 2); a *= a;
        double b = Math.sin(Math.toRadians(lng2 - lng1) / 2); b *= b;
        return 6371000.0 * 2.0 * Math.asin(Math.min(1.0, Math.sqrt(a + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * b)));
    }

    private static String root(String command) throws Exception {
        return BoundedCommand.checkedRoot(command,6,1048576);
    }
    static final class Snapshot {
        final boolean installed, moduleEnabled, autoCopy, joystickInstalled; final String version, status, coordinates, joystickLabel;
        Snapshot(boolean installed, boolean moduleEnabled, boolean autoCopy, String version, String status, String coordinates, boolean joystickInstalled, String joystickLabel) {
            this.installed = installed; this.moduleEnabled = moduleEnabled; this.autoCopy = autoCopy; this.version = version; this.status = status;
            this.coordinates = coordinates; this.joystickInstalled = joystickInstalled; this.joystickLabel = joystickLabel;
        }
    }
    static final class Point { final double latitude, longitude; Point(double latitude, double longitude) { this.latitude = latitude; this.longitude = longitude; } }
}

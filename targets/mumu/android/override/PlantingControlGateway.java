package dev.ody.pikmincontrol;

/** Root bridge for the v152 native planting controller. */
final class PlantingControlGateway {
    private static final String CONTROL = "/data/local/tmp/pikmin-planting-mode.txt";
    private static final String FLOWER_FARM = "/data/local/tmp/pikmin-flower-farm-mode.txt";
    private static final String STATUS = "/data/user/0/com.nianticlabs.pikmin/files/planting_control_status.tsv";
    private PlantingControlGateway() { }

    static void setEnabled(boolean enabled) throws Exception {
        root("printf '" + (enabled ? "on" : "off") + "\\n' > " + CONTROL + " && chmod 0644 " + CONTROL);
    }

    /** Passive acknowledgement resets a previous owned Stop attempt, never starts planting. */
    static void prepareRecovery() throws Exception {
        root("printf 'observe\\n' > " + CONTROL + " && chmod 0644 " + CONTROL);
    }

    /** Enables native flower-farm-only UI handling; this never changes GPS or game speed limits. */
    static void setFlowerFarmSession(boolean enabled) throws Exception {
        root("printf '" + (enabled ? "on" : "off") + "\\n' > " + FLOWER_FARM + " && chmod 0644 " + FLOWER_FARM);
    }

    static String status() throws Exception {
        String raw = root("cat " + STATUS + " 2>/dev/null || true").trim();
        String[] f = raw.split("\\t", -1);
        if (f.length < 5) return "等待 Pikmin 地圖載入原生種花控制。";
        if ("1".equals(f[2])) return "原生種花中（" + f[4] + "）";
        return "未種花（" + f[4] + "）";
    }

    static PlantingState snapshot(String expectedPid) throws Exception {
        String[] parts = ProbeSnapshot.sections(GpsModuleGateway.farmRead(ProbeSnapshot.command(
                "pidof com.nianticlabs.pikmin || true", "cat " + STATUS + " 2>/dev/null || true",
                "pidof com.nianticlabs.pikmin || true")), 3);
        return new PlantingState(parts[1], parts[0], parts[2], expectedPid, System.currentTimeMillis());
    }

    private static String root(String command) throws Exception {
        return GpsModuleGateway.farmRead(command);
    }
}

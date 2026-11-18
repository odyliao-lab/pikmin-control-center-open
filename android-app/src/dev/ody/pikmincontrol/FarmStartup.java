package dev.ody.pikmincontrol;

/** Read-only preflight policy. No GPS or planting commands until READY. */
final class FarmStartup {
    static final long WAIT_MS = 60_000L;
    private final long deadline;
    private String observedPid="";
    private long observedAt;
    FarmStartup(long elapsed) { deadline = elapsed + WAIT_MS; }
    boolean waiting(long elapsed, boolean stopping) { return !stopping && elapsed < deadline; }
    /** Two distinct fresh owner frames; a failure or a PID change resets confirmation. */
    boolean confirmed(FarmLocation fix, PlantingState planting, long wall, long elapsed) {
        if(!reason(fix,planting,wall,elapsed).isEmpty()) { observedPid="";observedAt=0;return false; }
        boolean ok=fix.pid.equals(observedPid)&&fix.at>observedAt&&fix.at-observedAt<=3000;
        if(!fix.pid.equals(observedPid)||fix.at!=observedAt){observedPid=fix.pid;observedAt=fix.at;}
        return ok;
    }
    static String reason(FarmLocation fix, PlantingState planting, long wall, long elapsed) {
        if (fix == null) return "等待原生定位回報";
        if (!fix.valid) return fix.readiness;
        if (!fix.fresh(wall, elapsed)) return "等待遊戲定位心跳更新";
        if (planting == null || !planting.valid || wall < planting.at || wall-planting.at > 2000)
            return "等待地圖種花狀態更新";
        return "";
    }
    static boolean plantingBusy(PlantingState planting) {
        return planting != null && planting.valid && (planting.planting || planting.owned || !"off".equals(planting.mode));
    }
}

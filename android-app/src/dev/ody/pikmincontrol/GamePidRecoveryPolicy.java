package dev.ody.pikmincontrol;

/** Pure decision model. A new healthy PID is rebound, not restarted again. */
final class GamePidRecoveryPolicy {
    enum Action { NONE, REBIND, LAUNCH, RESTART, WAIT_WORK, RATE_LIMIT, COOLDOWN }
    private static final long MISSING_GRACE_MS = 30_000;
    private static final long NEW_PID_GRACE_MS = 45_000;
    private static final long RESTART_WINDOW_MS = 600_000;
    private static final long RESTART_GAP_MS = 90_000;
    private String boundPid = "";
    private String suspect = "";
    private long suspectSince, windowStart, lastLaunch;
    private int launches;

    String boundPid() { return boundPid; }
    void restore(String pid) { if (pid != null && pid.matches("[1-9][0-9]*")) boundPid = pid; }
    void restoreLimits(long window,long last,int count) {
        if(window>0 && last>=window && count>=0 && count<=2) {
            windowStart=window;lastLaunch=last;launches=count;
        }
    }
    long windowStart() {return windowStart;}
    long lastLaunch() {return lastLaunch;}
    int launches() {return launches;}
    Action observe(String pid, boolean healthy, boolean reliable, boolean workBusy, long now) {
        return observe(pid,healthy,reliable,workBusy,false,now);
    }
    Action observe(String pid, boolean healthy, boolean reliable, boolean workBusy, boolean terminalCoreFailure, long now) {
        if (!reliable || now <= 0) return Action.NONE;
        if (pid == null || !pid.matches("[1-9][0-9]*")) pid = "";
        if (boundPid.isEmpty()) {
            if (!pid.isEmpty() && healthy) { boundPid = pid; suspect = ""; return Action.REBIND; }
            // A fresh paired terminal diagnostic proves a game is already running.
            // It is not permission to launch an absent, never-observed game.
            if (pid.isEmpty() || !terminalCoreFailure) return Action.NONE;
        }
        if (boundPid.equals(pid) && !terminalCoreFailure) { suspect = ""; suspectSince = 0; return Action.NONE; }
        if (!pid.isEmpty() && healthy) {
            boundPid = pid; suspect = ""; suspectSince = 0; return Action.REBIND;
        }
        if (suspectSince == 0 || now < suspectSince || !pid.equals(suspect)) { suspect = pid; suspectSince = now; return Action.NONE; }
        if (now - suspectSince < (pid.isEmpty() ? MISSING_GRACE_MS : NEW_PID_GRACE_MS)) return Action.NONE;
        if (workBusy) return Action.WAIT_WORK;
        if (now - windowStart >= RESTART_WINDOW_MS || now < windowStart) { windowStart = now; launches = 0; }
        if (lastLaunch > 0 && now - lastLaunch < RESTART_GAP_MS) return Action.COOLDOWN;
        if (launches >= 2) return Action.RATE_LIMIT;
        launches++;lastLaunch = now;suspectSince = now;
        return pid.isEmpty() ? Action.LAUNCH : Action.RESTART;
    }
}

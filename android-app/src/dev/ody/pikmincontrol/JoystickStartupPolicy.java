package dev.ody.pikmincontrol;

/** Pure, bounded recovery policy. Waiting in Android's queue is not an attempt. */
final class JoystickStartupPolicy {
    static final int MAX_ATTEMPTS = 3;
    static final long RECOVERY_DELAY_MS = 90_000;

    static boolean thermalAllows(int status) { return status >= 0 && status < 3; }

    static boolean retry(boolean transientFailure, int attempts, boolean allowed) {
        return transientFailure && allowed && attempts > 0 && attempts < MAX_ATTEMPTS;
    }

    static boolean recover(boolean enabled, boolean pending, String state, int attempts, long age) {
        if (!enabled || pending || attempts >= MAX_ATTEMPTS) return false;
        if ("ready".equals(state) || "failed".equals(state) || "cancelled".equals(state)) return false;
        return state.isEmpty() || "deferred-core".equals(state) || "deferred-work".equals(state) || age >= RECOVERY_DELAY_MS;
    }

    // Migrate only known pre-attempt live-owner failures from the old boot race.
    static boolean legacyWorkWait(String state, int attempts, String message) {
        if (!"failed".equals(state) || attempts != 0) return false;
        for (String owner : new String[]{"設定同步", "領取設定同步", "附近派遣設定", "批次探險", "花田採果", "GPS 啟動檢查"})
            if (("GPS 控制正由「" + owner + "」執行中持有；本輪已結束").equals(message)) return true;
        return false;
    }

    // Only the known pre-attempt core gate from the previous engine is recoverable.
    // Do not revive explicit cancellation, exhausted retries, or arbitrary root errors.
    static boolean legacyCoreWait(String state, int attempts, String message) {
        return "failed".equals(state) && attempts == 0 &&
                ("核心升級準備中，暫停新GPS工作；本輪已結束".equals(message)
                || "核心更新待重開／核對；本輪已結束".equals(message));
    }
}

package dev.ody.pikmincontrol;

/** A saved ON is intent, not evidence of native readiness or a dispatch. */
final class NearbyPreference {
    static String captureMode(String actual,String choice) {
        if(!"off".equals(actual)&&!"armed".equals(actual))return actual;
        return "on".equals(choice)?"armed":"off".equals(choice)?"off":actual;
    }
    static String waiting(boolean enabled, NearbyState state) {
        if (state.busy) return "等待批次／花田結束";
        if (!"off".equals(state.mode) && !"armed".equals(state.mode)) return "派遣控制狀態待確認";
        if (enabled && !state.safeEngine) return "等待本次遊戲程序的安全能力";
        if (!state.legacy.matches("all|seed|fruit|gift")) return "舊種類控制尚待確認";
        return "";
    }
    static boolean needsWrite(boolean enabled, NearbyState state) {
        return waiting(enabled,state).isEmpty() && (!state.mode.equals(enabled?"armed":"off") || !"644".equals(state.controlPermissions));
    }
    static String summary(boolean enabled, String waiting, NearbyState state) {
        if (!enabled) return "已關閉";
        if (waiting!=null && !waiting.isEmpty()) return "已開啟 · "+waiting;
        if (!"armed".equals(state.mode)) return "已開啟 · 等待同步派遣設定";
        String observation=state.compactSummary();
        return observation.startsWith("已開啟")?observation:"已開啟 · "+observation;
    }
}

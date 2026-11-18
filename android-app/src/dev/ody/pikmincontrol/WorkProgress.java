package dev.ody.pikmincontrol;

/** Lifecycle is independent of an individual item's phase (including failed). */
final class WorkProgress {
    static boolean active(String state) {
        return "running".equals(state) || "stopping".equals(state);
    }
    static String terminal(boolean stopped, String error, String cleanupError) {
        if (!cleanupError.isEmpty()) return "stop-incomplete";
        if (!error.isEmpty()) return "error";
        return stopped ? "stopped" : "completed";
    }
    static String label(String state) {
        switch (state) {
            case "recovered": return "檢查並重置完成；未開始採果";
            case "running": return "執行中";
            case "stopping": return "停止中，等待清理確認";
            case "stopped": return "已停止新增操作，GPS 工作權已釋放";
            case "completed": return "工作已結束，GPS 工作權已釋放";
            case "error": return "工作因錯誤結束；請查看下方原因";
            case "stop-incomplete": return "停止未完成；請到總覽重試「停止全部」";
            default: return "尚未執行";
        }
    }
}

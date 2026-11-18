package dev.ody.pikmincontrol;

/** Presentation only. Services and GpsWorkLock still authorize every operation. */
final class FarmUi {
    final boolean recovery, canStart, canRecover, canEdit, showEditor, showNotice, warning;
    final String title, message, detail;

    FarmUi(String state, boolean live, boolean held, boolean busy, boolean recoveryAvailable,
            String blocker, boolean hasPoints, boolean editingRecovery) {
        recovery = !live && (FarmRecovery.suggested(state) || held);
        canEdit = !live && !held && blocker.isEmpty() && (!busy || recoveryAvailable);
        canStart = hasPoints && !live && !held && !busy && !recovery && blocker.isEmpty();
        canRecover = hasPoints && recovery && recoveryAvailable && !live && !held && blocker.isEmpty();
        showEditor = !live && (!recovery || editingRecovery || !hasPoints);
        showNotice = live || recovery || busy || !blocker.isEmpty();
        warning = !blocker.isEmpty() || (!live && recovery && !"stopped".equals(state)) || (live && !held);
        if (live) {
            title = "stopping".equals(state) ? "安全停止中" : "採果進行中";
            message = !held ? "GPS 工作權待確認" : "stopping".equals(state) ? "等待停止確認" : "請切回遊戲";
            detail = "stopping".equals(state) ? "尚未確認完成，請勿開始其他工作。" : "即時進度請看懸浮視窗或通知。";
        } else if (!blocker.isEmpty()) {
            title = "暫不可啟動";
            message = blocker;
            detail = "原座標與執行紀錄仍會保留。";
        } else if (recovery) {
            title = "需要確認";
            message = held ? "GPS 工作權尚未釋放" : "stopped".equals(state) ? "上次工作已停止" : "上次工作未完成";
            detail = held ? "請先確認停止，再嘗試復原。" : "先復原狀態，再從第 1 點開始。";
        } else {
            title = "花田採果";
            message = busy ? "工作狀態待確認" : "準備開始";
            detail = "首點到達後種花，所選輪數結束再停止。";
        }
    }
}

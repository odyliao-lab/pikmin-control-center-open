package dev.ody.pikmincontrol;

/** Presentation only: never used to authorize a game operation. */
final class UiSummary {
    private UiSummary() { }

    static String work(String state, boolean live, boolean held) {
        if ("stop-incomplete".equals(state)) return "停止未完成 · 到總覽重試停止全部";
        if ("stopping".equals(state) && live) return "停止中 · 等待確認";
        if (live) return held ? "執行中 · GPS 使用中" : "執行中 · GPS 工作權待確認";
        if (WorkProgress.active(state)) return "工作中斷 · 停止狀態待確認";
        if (held) return "GPS 工作權未釋放 · 請檢查";
        if ("error".equals(state)) return "上次執行失敗 · 查看紀錄";
        if ("completed".equals(state)) return "上次已結束 · GPS 已釋放";
        if ("stopped".equals(state)) return "上次已停止 · GPS 已釋放";
        if ("recovered".equals(state)) return "檢查並重置完成 · 未開始採果";
        return "尚未執行";
    }

    static boolean workWarning(String state, boolean live, boolean held) {
        return "stop-incomplete".equals(state) || "error".equals(state)
                || (!live && (WorkProgress.active(state) || held)) || (live && !held);
    }

    static String nearby(NearbyState s) {
        if (s.busy) return "批次／花田接管中";
        if (!s.safeEngine) return "安全能力待確認";
        if (s.mask == 0) return "未選種類 · 不派遣";
        if (!"armed".equals(s.mode)) return "已關閉";
        if (!s.fresh || s.observed != s.mask || !s.guard.supported) return "已開啟 · 等待遊戲";
        if (!"all".equals(s.legacy)) return "已開啟 · 舊種類限制";
        return "ready".equals(s.guard.state) ? "已開啟 · 定位就緒" : "已開啟 · 等待定位";
    }

    static String planter(PlanterState s) {
        String mode = "auto".equals(s.mode) ? "拔苗＋補種" : "pluck".equals(s.mode) ? "僅拔苗" : "已關閉";
        if (s.anomaly) return s.status + (s.fresh ? "" : " · 上次回報");
        if (!s.fresh) return mode + " · 待更新";
        return mode + ("off".equals(s.mode) ? "" : " · " + s.status);
    }

    static String nectar(NectarModuleGateway.Snapshot s) {
        if (!s.modulePresent) return "未偵測到精華模組";
        if (!s.automatic()) return "已關閉";
        // The legacy snapshot has no PID/freshness proof: never label it live/ready.
        if (s.status.length < 10) return "已開啟 · 等待遊戲";
        if (!"1".equals(s.status[8])) return "已開啟 · 上次連線待確認";
        if (!"1".equals(s.status[4])) return "已開啟 · 上次定位待確認";
        if (s.status[9].startsWith("SUCCESS")) return "已開啟 · 上次檢查正常";
        return "已開啟 · 上次結果待確認";
    }

    static String planterDetail(PlanterState s) {
        if (!s.fresh) return "槽位資料待更新";
        if (s.slots.isEmpty()) return "等待槽位回報";
        int used = 0, ready = 0;
        for (PlanterState.Slot slot : s.slots) {
            if (!slot.empty) used++;
            if (!slot.empty && slot.ready) ready++;
        }
        return used + " / " + s.slots.size() + " 槽使用中" + (ready > 0 ? " · 可拔 " + ready : "");
    }

    static String startup(String report) {
        if (!report.contains("\n")) return report;
        if (report.contains("高溫") || report.contains("溫控")) return "啟動受溫控限制 · 查看詳情";
        if (report.contains("無法") || report.contains("失敗") || report.contains("拒絕") || report.contains("中斷"))
            return "啟動檢查異常 · 查看詳情";
        return "上次啟動檢查 · 查看詳情";
    }

    static String reward(boolean compatible, boolean enabled, String report) {
        if (!compatible) return "版本不相容 · 控制已鎖定";
        if (report.contains("安全停止") || report.contains("無法辨識") || report.contains("暫停"))
            return "上次領取待確認 · 查看紀錄";
        return enabled ? "已開啟 · 在遊戲中自動收取" : "已關閉";
    }
}

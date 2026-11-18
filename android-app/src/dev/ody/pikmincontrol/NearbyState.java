package dev.ody.pikmincontrol;

final class NearbyState {
    final String mode, legacy;
    final int mask, observed;
    final boolean fresh, busy, safeEngine;
    final NearbyGuard guard;
    final String controlPermissions;

    NearbyState(String mode, String legacy, String config, String ack, String pid, long now) {
        this(mode, legacy, config, ack, pid, now, "");
    }
    NearbyState(String mode, String legacy, String config, String ack, String pid, long now, String guardRaw) {
        this(mode, legacy, config, ack, pid, now, guardRaw, false);
    }
    NearbyState(String mode, String legacy, String config, String ack, String pid, long now, String guardRaw, boolean safeEngine) {
        this(mode,legacy,config,ack,pid,now,guardRaw,safeEngine,"644");
    }
    NearbyState(String mode, String legacy, String config, String ack, String pid, long now, String guardRaw, boolean safeEngine,String permissions) {
        controlPermissions=permissions.trim();
        this.safeEngine = safeEngine;
        guard = new NearbyGuard(guardRaw, pid, now);
        this.mode = mode.trim();
        this.legacy = legacy.trim();
        mask = NearbyKinds.parse(config);
        String[] row = ack.trim().split("\\t", -1);
        long observedMask = row.length == 6 ? number(row[3]) : -1;
        observed = observedMask >= 0 && observedMask <= 7 ? (int) observedMask : -1;
        long at = row.length == 6 ? number(row[1]) : -1;
        fresh = row.length == 6 && "v1".equals(row[0]) && !pid.trim().isEmpty()
                && pid.trim().equals(row[2]) && at > 0 && now >= at && now - at <= 90_000
                && observed >= 0 && observed <= 7 && this.mode.equals(row[4])
                && (!"armed".equals(this.mode) || this.legacy.equals(row[5]));
        busy = "batch".equals(this.mode) || "farm".equals(this.legacy);
    }
    /** Presentation only; dispatch permission continues to use the original guards. */
    String compactSummary() {
        if (busy) return "批次／花田接管中";
        if (!safeEngine) return "等待原生安全能力確認 · 請開啟遊戲";
        if (mask == 0) return "未勾選種類 · 不派遣";
        if (!"armed".equals(mode)) return "已關閉 · 可先開啟等待遊戲就緒";
        if (!fresh || observed != mask || !guard.supported) return "等待遊戲就緒 · 切回遊戲後接續";
        if (!"all".equals(legacy)) return "已開啟 · 另受舊種類限制（見詳情）";
        if (!"ready".equals(guard.state)) return "等待遊戲定位穩定 · 暫不派遣";
        return "定位就緒 · 派遣前重驗隊伍";
    }
    String summary() {
        if (busy) return "批次／花田接管中；此處勾選保留給一般附近派遣。";
        boolean enabled = "armed".equals(mode);
        String text = enabled ? "附近自動派遣已開啟" : "附近自動派遣已關閉";
        if (mask == 0) text += "；未勾選種類，不新增派遣";
        if (!safeEngine) return text + "\n需要 native 1.4.23 的不限距離能力；舊引擎不會被當成新規則。";
        if (!fresh || observed != mask || !guard.supported)
            return text + (enabled ? "（等待遊戲就緒）" : "；可先開啟並等待遊戲就緒")
                    + "\n設定已儲存；請切回 Pikmin，等待原生回報與定位恢復。\n實際派遣仍須定位穩定、隊員可用，且原生往返 ≤ 5 分鐘。";
        if (!"all".equals(legacy)) text += "\n另受舊控制檔種類限制：" + legacy;
        return text + "\n設定已由原生引擎讀取；不代表已有皮克敏出發。\n" + guard.summary();
    }
    boolean canToggle(boolean held) { return !held && !busy; }
    private static long number(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return -1; }
    }
}

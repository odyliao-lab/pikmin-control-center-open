package dev.ody.pikmincontrol;

/** Separate heartbeat keeps the existing six-column kinds acknowledgement compatible. */
final class NearbyGuard {
    final boolean supported;
    final boolean timeOnly;
    final String state;
    NearbyGuard(String raw, String pid, long now) {
        String[] row = raw.trim().split("\\t", -1);
        state = row.length == 12 ? row[3] : "";
        long at = row.length == 12 ? number(row[1]) : -1;
        timeOnly = row.length == 12 && "v2".equals(row[0]);
        supported = row.length == 12 && ("v1".equals(row[0]) || timeOnly) && !pid.trim().isEmpty() && pid.trim().equals(row[2])
                && at > 0 && now >= at && now-at <= 10000 && "300000".equals(row[11])
                && ("ready".equals(state) || "waiting-game-location".equals(state) || "stale-game-location".equals(state)
                    || "game-location-catching-up".equals(state) || "location-settling".equals(state)
                    || "location-gap".equals(state) || "location-moving".equals(state));
    }
    String summary() {
        if (!supported) return "等待原生定位保護更新；實際派遣仍須通過 5 分鐘與定位檢查。";
        String detail;
        switch (state) {
            case "ready": detail = "遊戲定位已穩定；派遣前仍會重驗隊伍"; break;
            case "game-location-catching-up": detail = "遊戲位置追趕中，暫不派遣"; break;
            case "location-settling": case "location-moving": detail = "等待遊戲位置穩定，暫不派遣"; break;
            default: detail = "等待新鮮遊戲定位，暫不派遣";
        }
        return (timeOnly ? "原生往返 ≤ 5 分鐘 · 不限距離\n" : "舊引擎仍有 200 公尺限制\n") + detail;
    }
    private static long number(String value) { try { return Long.parseLong(value); } catch (Exception e) { return -1; } }
}

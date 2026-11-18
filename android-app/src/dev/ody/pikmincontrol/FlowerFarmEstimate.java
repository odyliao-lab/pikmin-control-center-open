package dev.ody.pikmincontrol;

/** Read-only preflight comparison. Never predicts location latency or harvest success. */
final class FlowerFarmEstimate {
    static final long RESULT_SETTLE_MS = 5_000L;
    static final String BASIS = "逐點為固定等待下限；沿路為純行走時間。";
    static final String LIMITS = "逐點另加每點定位確認、指令與資料讀取時間；沿路按輸入順序以 19 km/h 單趟計算。"
            + "兩者另加準備、首尾定位與收尾時間；逾時、資源不足或中止可能改變耗時。"
            + "時間不代表採果數量，也不保證水果已返程或領取。";
    final String point, route;

    FlowerFarmEstimate(String raw, int rounds, int dwellSeconds) {
        if ((rounds != 1 && rounds != 2) || (dwellSeconds != 5 && dwellSeconds != 10))
            throw new IllegalArgumentException("Unsupported flower farm settings");
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            point = "逐點：輸入座標後預估";
            route = "沿路：輸入路線後預估";
            return;
        }
        if (text.length() > HarvestRoute.MAX_CHARS) {
            point = "逐點：輸入過長，無法預估";
            route = "沿路：輸入過長，無法預估";
            return;
        }
        if (text.startsWith("<")) {
            // The point-mode runner accepts coordinate rows, not a GPX document.
            point = "逐點：請改用每行緯度,經度（GPX 僅適用沿路）";
        } else {
            FlowerFarmDraft draft = new FlowerFarmDraft(text);
            long visits = (long) draft.points.size() * rounds;
            point = visits == 0 ? "逐點：沒有有效座標，無法預估"
                    : "逐點：至少 " + duration(visits * (dwellSeconds + RESULT_SETTLE_MS / 1000))
                    + "（" + draft.points.size() + " 點 × " + rounds + " 輪；停留 " + dwellSeconds
                    + "＋結果等待 5 秒／點）"
                    + (draft.duplicates > 0 ? "；合併 " + draft.duplicates + " 個重複點" : "")
                    + (draft.skipped > 0 ? "；略過 " + draft.skipped + " 行無效座標" : "");
        }
        String walking;
        try {
            HarvestRoute parsed = text.startsWith("<") ? HarvestRoute.gpx(text) : HarvestRoute.coordinates(text);
            walking = "沿路：約 " + duration(parsed.estimatedSeconds()) + "（" + parsed.points.size()
                    + " 節點／" + Math.round(parsed.meters) + " 公尺；19 km/h 單趟）";
        } catch (Exception invalid) {
            // A filtered point draft must never turn an invalid ordered route into an estimate.
            walking = "沿路：無法預估，請檢查路線格式、節點數及長度";
        }
        route = walking;
    }

    String comparison() { return point + "\n" + route + "\n" + BASIS; }
    String confirmation() { return comparison() + "\n" + LIMITS; }

    static String duration(long seconds) {
        long hours = seconds / 3600, minutes = seconds % 3600 / 60, remainder = seconds % 60;
        return (hours > 0 ? hours + " 小時 " : "")
                + (minutes > 0 ? minutes + " 分 " : "") + remainder + " 秒";
    }
}

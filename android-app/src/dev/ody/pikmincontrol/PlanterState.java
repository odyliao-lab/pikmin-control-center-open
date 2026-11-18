package dev.ody.pikmincontrol;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Strict, Android-independent reader for the native planter audit protocol. */
final class PlanterState {
    final String mode, status, summary, history, eventCode;
    final boolean fresh, supportsSteps, anomaly;
    final int observedSteps;
    final java.util.List<Slot> slots = new java.util.ArrayList<>();
    static final class Slot {
        final int index;
        final long steps, target;
        final boolean empty, ready;
        Slot(String[] fields) {
            index = (int) number(fields[1]);
            empty = fields[4].isEmpty();
            steps = Math.max(0, number(fields[5]));
            target = number(fields[6]);
            ready = "ready".equals(fields[7]);
        }
    }

    PlanterState(String mode, String status, String inventory, String history, String pid, long now) {
        this.mode = "auto".equals(mode) || "pluck".equals(mode) ? mode : "off";
        String[] state = status.trim().split("\\t", -1);
        eventCode = state.length >= 5 && "v1".equals(state[0]) ? state[3] : "";
        anomaly = observationIssue(eventCode);
        String[] rows = inventory.trim().split("\\n");
        String[] header = rows[0].split("\\t", -1);
        int seeds = 0;
        for (String row : rows) if (row.startsWith("seed\t")) seeds++;
        int observed = -1;
        for (String row : rows) {
            String[] f = row.split("\\t", -1);
            if (f.length == 2 && "steps".equals(f[0]) && number(f[1]) >= 0 && number(f[1]) <= 63)
                observed = (int) number(f[1]);
        }
        this.observedSteps = observed;
        this.supportsSteps = observed >= 0;
        String[] end = rows[rows.length - 1].split("\\t", -1);
        boolean complete = end.length == 2 && "end".equals(end[0]) && number(end[1]) == seeds;
        this.fresh = state.length >= 5 && "v1".equals(state[0]) && header.length == 5 &&
                "v1".equals(header[0]) && !pid.isEmpty() && pid.equals(state[2]) && pid.equals(header[2]) &&
                recent(state[1], now) && recent(header[1], now) && complete &&
                !"metadata-unavailable".equals(state[3]) && !"inventory-incomplete".equals(state[3])
                && !"session-unavailable".equals(state[3]);
        this.status = state.length >= 5 ? (this.mode.equals(state[4]) ? event(state[3])
                : "設定已儲存，等待遊戲套用") : "等待遊戲產生育苗狀態";
        StringBuilder shown = new StringBuilder(this.status);
        if (!fresh) shown.append("\n等待新的完整育苗資料，收到後自動觀察。");
        if (header.length == 5 && "v1".equals(header[0]))
            shown.append("\n皮克敏空間：").append(header[3]).append(" / ")
                    .append(number(header[4]) > 0 ? header[4] : "未知");
        for (String row : rows) {
            String[] f = row.split("\\t", -1);
            if (f.length < 9 || !"slot".equals(f[0])) continue;
            if ("0".equals(f[2]) && number(f[1]) >= 0) slots.add(new Slot(f));
            shown.append("\n槽位 ").append(number(f[1]) + 1)
                    .append("0".equals(f[2]) ? "（永久）" : "（額外，不自動操作）").append("：");
            if (f[4].isEmpty()) shown.append("空");
            else shown.append(seedLabel(f[8])).append(" ").append(f[5]).append(" / ").append(f[6])
                    .append(" 步 · ").append("ready".equals(f[7]) ? "可拔苗" : "成長中");
        }
        this.summary = shown.toString();
        String[] logs = history.trim().split("\\n");
        StringBuilder events = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN);
        int kept = 0;
        for (int i = logs.length - 1; i >= 0 && kept < 30; --i) {
            String[] f = logs[i].split("\\t", -1);
            long at = number(f[0]);
            if (f.length < 4 || at <= 0 || at < now - 86_400_000L) continue;
            if (events.length() > 0) events.append('\n');
            events.append(time.format(new Date(at))).append(" · ").append(event(f[1]))
                    .append(" · 槽位 ").append(number(f[3]) + 1);
            kept++;
        }
        this.history = events.length() == 0 ? "尚無育苗操作紀錄。" : events.toString();
    }

    private static boolean recent(String value, long now) {
        long age = now - number(value);
        return number(value) > 0 && age >= -5000 && age <= 60000;
    }
    static long number(String value) { try { return Long.parseLong(value); } catch (Exception e) { return -1; } }
    static String seedLabel(String value) {
        return ReturnRewardModuleGateway.seedLabel(value);
    }
    /** Used only by retained historical recovery parsers, never by continuous admission. */
    boolean legacyReviewRequired() { return eventCode.endsWith("-review-required"); }

    static boolean observationIssue(String value) {
        switch (value) {
            case "": case "off": case "waiting-growth-or-seed": case "no-seed-types-selected":
            case "pluck-sent": case "plant-sent": case "server-success":
            case "pluck-confirmed": case "plant-confirmed": case "manual-recovered":
            case "waiting-rpc": case "waiting-inventory":
            case "waiting-rpc-no-retry": case "waiting-inventory-no-retry": return false;
            default: return true;
        }
    }
    static String event(String value) {
        switch (value) {
            case "restart-pending-review-required":
            case "rpc-failed-review-required":
            case "server-rejected-review-required": return "舊操作結果待確認，等待自動觀察";
            case "manual-recovery-checking": return "上次核對尚待確認，等待自動觀察";
            case "manual-recovered": return "上次育苗核對已完成";
            case "observing-current-state": return "異常已記錄，依最新有效庫存自動觀察";
            case "rpc-timeout-unknown": return "回覆逾時已記錄，結果未確認；等待新資料後自動觀察";
            case "rpc-state-unknown":
            case "response-root-unknown":
            case "unexpected-response-unknown":
            case "missing-new-pikmin-unknown":
            case "clock-reset-unknown":
            case "rpc-failed-unknown": return "請求失敗已記錄，結果未確認；等待新資料後自動觀察";
            case "server-rejected-unknown": return "伺服器拒絕已記錄，等待新資料後自動觀察";
            case "rpc-start-unknown":
            case "task-root-unknown": return "請求啟動異常已記錄，結果未確認；等待新資料後自動觀察";
            case "inventory-timeout-unknown": return "庫存確認逾時已記錄，結果未確認；等待新資料後自動觀察";
            case "session-changed-unknown": return "帳號狀態變更已記錄，等待新資料後自動觀察";
            case "unknown-record-retained": return "未確認操作原件已保存，等待自動觀察";
            case "confirmed-record-retained": return "已確認操作的原件已保存";
            case "archive-retry": return "舊操作原件保留，紀錄保存異常已記錄，持續自動觀察";
            case "audit-write-unknown": return "育苗紀錄未能保存，已記錄異常並持續自動觀察";
            case "audit-write-retry": return "育苗紀錄異常已記錄，持續自動觀察並重試保存";
            case "request-build-retry": return "育苗請求準備異常已記錄，稍後自動重試";
            case "off": return "自動育苗已關閉";
            case "waiting-growth-or-seed": return "等待成熟花苗／可補種花苗";
            case "no-seed-types-selected": return "未勾選補種種類，只拔成熟花苗";
            case "pluck-sent": return "已送出拔苗請求";
            case "plant-sent": return "已送出種植請求";
            case "server-success": return "伺服器成功，等待庫存確認";
            case "pluck-confirmed": return "✓ 拔苗完成，新皮克敏已入庫";
            case "plant-confirmed": return "✓ 補種完成";
            case "storage-full-or-unknown": return "等待皮克敏空間足夠且容量已確認";
            case "waiting-rpc":
            case "waiting-rpc-no-retry": return "等待伺服器回覆，不重複送出";
            case "waiting-inventory":
            case "waiting-inventory-no-retry": return "等待庫存確認，不重複送出";
            case "metadata-unavailable": return "育苗介面不相容，等待有效資料";
            case "session-unavailable": return "等待目前帳號與遊戲資料就緒";
            case "inventory-incomplete": return "庫存尚未完整，等待新資料，暫不操作";
            default: return "育苗狀態待確認，等待新資料並自動觀察";
        }
    }
}

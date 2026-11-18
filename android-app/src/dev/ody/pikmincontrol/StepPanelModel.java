package dev.ody.pikmincontrol;

/** Presentation helpers. Opening the page alone never starts a writer or grants consent. */
final class StepPanelModel {
    static final String GATE = "預設關閉 · 使用者確認並授權後才會寫入";
    static String total(Long value) { return value == null ? "尚未讀取" : Long.toString(value); }
    static String hourText(int minute) {
        return String.format(java.util.Locale.ROOT, "%02d", minute / 60);
    }
    static int hour(String text) {
        if (text == null || !text.trim().matches("[0-9]{1,2}"))
            throw new IllegalArgumentException("時間請輸入 0–23");
        int value = Integer.parseInt(text.trim());
        if (value < 0 || value > 23) throw new IllegalArgumentException("時間須為 0–23");
        return value;
    }
    static StepSettings settings(String target, String start, String end, String rate, String seconds) {
        try {
            long interval = Long.parseLong(seconds.trim());
            if (interval < 60 || interval > 180) throw new IllegalArgumentException("背景間隔須為 60–180 秒");
            return StepSettings.parse(Integer.parseInt(target.trim()), hour(start) * 60, hour(end) * 60,
                    Integer.parseInt(rate.trim()), "custom", interval * 1000L);
        } catch (NumberFormatException e) { throw new IllegalArgumentException("請輸入有效整數"); }
    }
    static String summary(StepSettings s) {
        if (s == null) return "設定未能讀取，請勿覆寫";
        return "目標 " + s.target + " 步 · " + s.ratePerMinute + " 步／分\n"
                + (s.allDay() ? "全天" : hourText(s.windowStartMin) + "–" + hourText(s.windowEndMin) + " 時"
                + (s.overnight() ? "（跨日）" : "")) + " · 每 " + s.intervalMs / 1000 + " 秒";
    }
    static String result(String code) {
        if ("ok".equals(code) || "confirmed".equals(code)) return "已確認";
        if ("uncertain".equals(code)) return "待核對";
        if ("cancel".equals(code)) return "已取消";
        if ("failed".equals(code)) return "未成功";
        return "待核對（" + code + "）";
    }
}

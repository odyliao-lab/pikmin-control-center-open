package dev.ody.pikmincontrol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the current provider section, never historical location logs. */
final class JoystickReadiness {
    private static final Pattern SECTION = Pattern.compile("(?ms)^    gps provider(.*?)(?=^    \\S|\\z)");
    private static final Pattern LOCATION = Pattern.compile("(?m)^\\s*last mock location=Location\\[gps ([^ ,]+),([^ ]+).*? et=([^ ]+).*?\\bmock\\b");
    private static final Pattern PART = Pattern.compile("(\\d+)(ms|d|h|m|s)");
    final boolean known, mock, own;
    final long fixMs;
    final double latitude, longitude;
    JoystickReadiness(boolean known, boolean mock, boolean own, long fixMs) {
        this(known, mock, own, fixMs, Double.NaN, Double.NaN);
    }
    private JoystickReadiness(boolean known, boolean mock, boolean own, long fixMs, double lat, double lng) {
        this.known = known; this.mock = mock; this.own = own; this.fixMs = fixMs;
        latitude = lat; longitude = lng;
    }
    boolean fresh(long now) { return own && fixMs >= 0 && now >= fixMs && now - fixMs <= 5000; }
    static String label(JoystickReadiness first, JoystickReadiness second, long now) {
        if (!second.known) return "無法確認目前 GPS 提供者";
        if (!second.mock) return "目前不是模擬定位";
        if (!second.own) return "由其他模擬定位工具提供；目前飛行介面不支援它";
        if (!second.fresh(now)) return "GPS JoyStick 尚無新鮮定位";
        return first.fresh(now) && second.fixMs > first.fixMs
                ? "GPS JoyStick 定位更新中（本次檢查）" : "已有定位；尚未觀察到持續更新";
    }
    static JoystickReadiness parse(String dump, String identity) {
        Matcher section = SECTION.matcher(dump);
        if (!section.find()) return new JoystickReadiness(false, false, false, -1);
        String body = section.group(1);
        boolean mock = body.startsWith(" [mock]:");
        boolean own = mock && Pattern.compile("(?m)^\\s*identity=" + Pattern.quote(identity) + "\\s*$").matcher(body).find();
        long fix = -1; double latitude = Double.NaN, longitude = Double.NaN;
        Matcher location = LOCATION.matcher(body);
        if (own && body.contains("allowed=true") && location.find()) {
            try {
                double lat = Double.parseDouble(location.group(1)), lng = Double.parseDouble(location.group(2));
                if (Double.isFinite(lat) && Double.isFinite(lng) && Math.abs(lat) <= 90 && Math.abs(lng) <= 180) {
                    fix = elapsed(location.group(3));
                    latitude = lat; longitude = lng;
                }
            } catch (RuntimeException ignored) { }
        }
        return new JoystickReadiness(true, mock, own, fix, latitude, longitude);
    }
    static long elapsed(String text) {
        if ("0".equals(text)) return 0;
        if (!text.startsWith("+")) return -1;
        Matcher m = PART.matcher(text.substring(1));
        int end = 0; long value = 0;
        try {
            while (m.find()) {
                if (m.start() != end) return -1;
                long scale = "d".equals(m.group(2)) ? 86400000L : "h".equals(m.group(2)) ? 3600000L
                        : "m".equals(m.group(2)) ? 60000L : "s".equals(m.group(2)) ? 1000L : 1L;
                value = Math.addExact(value, Math.multiplyExact(Long.parseLong(m.group(1)), scale));
                end = m.end();
            }
            return end == text.length() - 1 && end > 0 ? value : -1;
        } catch (ArithmeticException | NumberFormatException error) { return -1; }
    }
}

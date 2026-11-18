package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** Read-only input preview. Never replaces the user's draft or changes a running route. */
final class FlowerFarmDraft {
    final List<double[]> points = new ArrayList<>();
    final String normalized, issues;
    final int skipped, duplicates;

    FlowerFarmDraft(String raw) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        StringBuilder clean = new StringBuilder(), errors = new StringBuilder();
        int invalid = 0, repeated = 0;
        String[] lines = raw.split("\\r\\n|\\r|\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] fields = line.split("[,\\s]+", -1);
            double lat = Double.NaN, lng = Double.NaN;
            if (fields.length == 2) {
                try { lat = Double.parseDouble(fields[0]); lng = Double.parseDouble(fields[1]); }
                catch (NumberFormatException ignored) { }
            }
            String reason = fields.length != 2 ? "請使用緯度,經度"
                    : !Double.isFinite(lat) || !Double.isFinite(lng) ? "不是有效數字"
                    : lat < -90 || lat > 90 || lng < -180 || lng > 180 ? "超出經緯度範圍" : "";
            if (!reason.isEmpty()) {
                invalid++;
                errors.append("第 ").append(i + 1).append(" 行：").append(reason).append('\n');
                continue;
            }
            String key = String.format(Locale.US, "%.7f,%.7f", lat, lng);
            if (!seen.add(key)) {
                repeated++;
                errors.append("第 ").append(i + 1).append(" 行：重複座標，執行時合併\n");
                continue;
            }
            points.add(new double[]{lat, lng});
            if (clean.length() > 0) clean.append('\n');
            clean.append(String.format(Locale.US, "%.7f, %.7f", lat, lng));
        }
        normalized = clean.toString(); issues = errors.toString().trim();
        skipped = invalid; duplicates = repeated;
    }
    String summary() {
        return "有效 " + points.size() + " 點 · 無效 " + skipped + " 行 · 重複 " + duplicates + " 點";
    }
}

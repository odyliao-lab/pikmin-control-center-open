package dev.ody.pikmincontrol;

import java.util.LinkedHashSet;
import java.util.Set;

/** APK-owned, route-window-bound summary for the operator overlay. */
final class RouteHarvestSummary {
    private static final int MAX_IDS = 10_000;
    private final long startedAt;
    private final Set<String> observed = new LinkedHashSet<>();
    private final Set<String> started = new LinkedHashSet<>();

    RouteHarvestSummary(long startedAt) {
        if (startedAt <= 0) throw new IllegalArgumentException("route summary start");
        this.startedAt = startedAt;
    }

    void ingest(String raw, long now) {
        if (raw == null || raw.length() > 262_144 || now < startedAt)
            throw new IllegalArgumentException("route summary framing");
        for (String row : raw.replace("\r", "").split("\n")) {
            if (row.isEmpty() || "MISSING".equals(row)) continue;
            String[] f = row.split("\t", -1);
            if (f.length != 6) continue;
            long at = number(f[0]);
            if (at < startedAt || at > now || !"fruit".equals(f[2]) || !validId(f[3])) continue;
            if ("nearby-selection-settling".equals(f[1])) add(observed, f[3]);
            else if ("start-requested".equals(f[1]) && observed.contains(f[3])) add(started, f[3]);
        }
    }

    int fruits() { return observed.size(); }
    int dispatched() { return started.size(); }
    int remaining() { return Math.max(0, fruits() - dispatched()); }

    String label(boolean finished) {
        if (fruits() == 0) return finished ? "水果 0　已派 0　略過 0" : "等待水果出現";
        return "水果 " + fruits() + "　已派 " + dispatched() + "　"
                + (finished ? "略過 " : "待派 ") + remaining();
    }

    private void add(Set<String> values, String id) {
        if (!values.contains(id) && values.size() >= MAX_IDS)
            throw new IllegalStateException("route summary id limit");
        values.add(id);
    }

    private static boolean validId(String value) {
        return value != null && !value.isEmpty() && value.length() <= 256
                && value.matches("[A-Za-z0-9_-]+");
    }

    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) return -1;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return -1; }
    }
}

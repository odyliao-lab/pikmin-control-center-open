package dev.ody.pikmincontrol;

final class StepSettings {
    static final int MIN_TARGET = 1;
    static final int MAX_TARGET = 1_000_000;
    static final int DEFAULT_TARGET = 50_000;
    static final int MIN_RATE = 1;
    static final int MAX_RATE = 100_000;
    static final int DEFAULT_RATE = 130;
    static final int SLOW_RATE = 90;
    static final int FAST_RATE = 200;
    static final int RUN_RATE = 350;
    static final int DEFAULT_WINDOW_START = 8 * 60;
    static final int DEFAULT_WINDOW_END = 22 * 60;
    static final long DEFAULT_INTERVAL_MS = 60_000L;
    static final long MIN_INTERVAL_MS = 5_000L;
    static final long MAX_RECORD_MS = 180_000L;
    static final long MANUAL_FIRST_MS = 60_000L;
    static final long MANUAL_MIN_GAP_MS = 2_000L;
    static final double JITTER = 0.05d;

    final int target;
    final int windowStartMin;
    final int windowEndMin;
    final int ratePerMinute;
    final String ratePreset;
    final long intervalMs;

    StepSettings(int target, int windowStartMin, int windowEndMin, int ratePerMinute, String ratePreset, long intervalMs) {
        this.target = target;
        this.windowStartMin = windowStartMin;
        this.windowEndMin = windowEndMin;
        this.ratePerMinute = ratePerMinute;
        this.ratePreset = ratePreset == null ? "normal" : ratePreset;
        this.intervalMs = intervalMs;
    }

    static StepSettings defaults() {
        return new StepSettings(DEFAULT_TARGET, DEFAULT_WINDOW_START, DEFAULT_WINDOW_END, DEFAULT_RATE, "normal", DEFAULT_INTERVAL_MS);
    }

    static StepSettings parse(int target, int windowStartMin, int windowEndMin, int ratePerMinute, String preset, long intervalMs) {
        if (target < MIN_TARGET || target > MAX_TARGET) throw new IllegalArgumentException("目標須為 1–1000000");
        if (windowStartMin < 0 || windowStartMin > 1439 || windowEndMin < 0 || windowEndMin > 1439)
            throw new IllegalArgumentException("活躍時段無效");
        String ratePreset = normalizePreset(preset);
        int rate = ratePerMinute;
        if ("slow".equals(ratePreset)) rate = SLOW_RATE;
        else if ("normal".equals(ratePreset)) rate = DEFAULT_RATE;
        else if ("fast".equals(ratePreset)) rate = FAST_RATE;
        else if ("run".equals(ratePreset)) rate = RUN_RATE;
        if (rate < MIN_RATE || rate > MAX_RATE) throw new IllegalArgumentException("速率須為 1–100000 步/分");
        if ("slow".equals(ratePreset) && (rate < 70 || rate > 110)) throw new IllegalArgumentException("慢走速率須為 70–110");
        if ("normal".equals(ratePreset) && (rate < 110 || rate > 150)) throw new IllegalArgumentException("一般速率須為 110–150");
        if ("fast".equals(ratePreset) && (rate < 170 || rate > 210)) throw new IllegalArgumentException("快走速率須為 170–210");
        if ("run".equals(ratePreset) && (rate < 330 || rate > 370)) throw new IllegalArgumentException("奔跑速率須為 330–370");
        long interval = intervalMs <= 0 ? DEFAULT_INTERVAL_MS : intervalMs;
        if (interval < MIN_INTERVAL_MS) throw new IllegalArgumentException("間隔最短 5 秒");
        if (interval > MAX_RECORD_MS) interval = MAX_RECORD_MS;
        return new StepSettings(target, windowStartMin, windowEndMin, rate, ratePreset, interval);
    }

    static String normalizePreset(String preset) {
        if ("slow".equals(preset) || "fast".equals(preset) || "run".equals(preset) || "custom".equals(preset)) return preset;
        return "normal";
    }

    boolean allDay() { return windowStartMin == windowEndMin; }

    boolean overnight() { return windowStartMin > windowEndMin; }

    boolean inWindow(int minuteOfDay) {
        if (allDay()) return true;
        if (windowStartMin < windowEndMin) return minuteOfDay >= windowStartMin && minuteOfDay < windowEndMin;
        return minuteOfDay >= windowStartMin || minuteOfDay < windowEndMin;
    }

    boolean extremePreview() {
        return "run".equals(ratePreset) || ("custom".equals(ratePreset) && (ratePerMinute < 70 || ratePerMinute > 210));
    }

    long countForDuration(long durationMs, StepRng rng) {
        if (durationMs <= 0L) return 0L;
        double minutes = durationMs / 60000.0d;
        double jitter = 1.0d + ((rng.nextUnit() * 2.0d) - 1.0d) * JITTER;
        long count = Math.round(ratePerMinute * minutes * jitter);
        return count < 1L ? 1L : count;
    }
}

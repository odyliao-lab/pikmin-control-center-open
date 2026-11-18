package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.List;

final class StepCatchUpRequest {
    static final int MAX_STEPS = 1_000_000;
    static final int MAX_MINUTES = 720;
    static final int MIN_RATE = 1;
    static final int MAX_RATE = 350;

    final String mode;
    final long steps;
    final int ratePerMinute;
    final int year;
    final int month;
    final int day;
    final int startHour;
    final int startMinute;
    final int endHour;
    final int endMinute;
    final Integer startOffsetSeconds;
    final Integer endOffsetSeconds;
    final int clockEpoch;

    StepCatchUpRequest(String mode, long steps, int ratePerMinute, int year, int month, int day,
            int startHour, int startMinute, int endHour, int endMinute,
            Integer startOffsetSeconds, Integer endOffsetSeconds) {
        this(mode, steps, ratePerMinute, year, month, day, startHour, startMinute, endHour, endMinute,
                startOffsetSeconds, endOffsetSeconds, 0);
    }

    StepCatchUpRequest(String mode, long steps, int ratePerMinute, int year, int month, int day,
            int startHour, int startMinute, int endHour, int endMinute,
            Integer startOffsetSeconds, Integer endOffsetSeconds, int clockEpoch) {
        this.mode = mode == null ? "window" : mode;
        this.steps = steps;
        this.ratePerMinute = ratePerMinute;
        this.year = year;
        this.month = month;
        this.day = day;
        this.startHour = startHour;
        this.startMinute = startMinute;
        this.endHour = endHour;
        this.endMinute = endMinute;
        this.startOffsetSeconds = startOffsetSeconds;
        this.endOffsetSeconds = endOffsetSeconds;
        this.clockEpoch = clockEpoch;
    }

    boolean sameBinding(StepCatchUpRequest other) {
        if (other == null) return false;
        return mode.equals(other.mode) && steps == other.steps && ratePerMinute == other.ratePerMinute
                && year == other.year && month == other.month && day == other.day
                && startHour == other.startHour && startMinute == other.startMinute
                && endHour == other.endHour && endMinute == other.endMinute
                && sameOffset(startOffsetSeconds, other.startOffsetSeconds)
                && sameOffset(endOffsetSeconds, other.endOffsetSeconds)
                && clockEpoch == other.clockEpoch;
    }

    private static boolean sameOffset(Integer a, Integer b) {
        if (a == null) return b == null;
        return a.equals(b);
    }
}

final class StepCatchUpPlanner {
    static final class Result {
        final List<StepPlan> plans;
        final String summary;
        final String warning;
        final long elapsedMinutes;
        Result(List<StepPlan> plans, String summary, String warning, long elapsedMinutes) {
            this.plans = plans;
            this.summary = summary;
            this.warning = warning;
            this.elapsedMinutes = elapsedMinutes;
        }
    }

    static Result plan(StepCatchUpRequest request, StepState state, StepClock clock, StepZone zone, StepRng rng) {
        if (request.ratePerMinute < StepCatchUpRequest.MIN_RATE || request.ratePerMinute > StepCatchUpRequest.MAX_RATE)
            throw new IllegalArgumentException("補走速率須為 1–350 步/分");
        if (request.steps < 1L || request.steps > StepCatchUpRequest.MAX_STEPS)
            throw new IllegalArgumentException("補走總量須為 1–1000000");
        long now = clock.wallMillis();
        int epoch = zone.clockEpoch(now);
        if (request.clockEpoch != 0 && request.clockEpoch != epoch)
            throw new IllegalStateException("時鐘／時區已變更，請重新預覽。");
        String today = zone.dayKey(now);
        String requestedDay = String.format("%04d-%02d-%02d", Integer.valueOf(request.year), Integer.valueOf(request.month), Integer.valueOf(request.day));
        if (!today.equals(requestedDay)) throw new IllegalArgumentException("只能補走今日已完成的分鐘，不能跨日。");
        if ("recent".equals(request.mode)) return planRecent(request, state, now, zone, rng);
        long start;
        long end;
        if ("total".equals(request.mode)) {
            start = zone.dayStartMillis(now);
            end = (now / 60_000L) * 60_000L;
            if (end > now) end = now;
        } else {
            start = localEpoch(request, zone, true);
            end = localEpoch(request, zone, false);
        }
        if (end <= start) throw new IllegalArgumentException("補走終點必須晚於起點。");
        if (end > now) throw new IllegalArgumentException("不能寫入未來。");
        if (start < zone.dayStartMillis(now) || end > zone.nextMidnightMillis(now))
            throw new IllegalArgumentException("補走不能跨當地午夜。");
        long elapsedMs = end - start;
        long elapsedMinutes = elapsedMs / 60000L;
        if (elapsedMinutes < 1L) throw new IllegalArgumentException("可用分鐘不足。");
        if (elapsedMinutes > StepCatchUpRequest.MAX_MINUTES)
            throw new IllegalArgumentException("最多 720 個實際經過分鐘。");
        long neededMinutes = (long) Math.ceil(request.steps / (double) request.ratePerMinute);
        if (neededMinutes > elapsedMinutes)
            throw new IllegalArgumentException("分鐘不足，不縮時段也不暗中加速。");
        if (state.ledger.overlaps(start, end)) throw new IllegalStateException("與本 App 已有區間重疊。");
        List<StepPlan> plans = split(request, start, end, zone, rng);
        long total = 0L;
        for (int i = 0; i < plans.size(); i++) {
            StepPlan plan = plans.get(i);
            if (plan.count < 1L || plan.endMs <= plan.startMs)
                throw new IllegalStateException("計畫必須 count>0 且 end>start。");
            if (plan.endMs - plan.startMs > StepSettings.MAX_RECORD_MS)
                throw new IllegalStateException("單筆最長 180 秒。");
            total += plan.count;
        }
        if (plans.isEmpty()) throw new IllegalStateException("沒有可寫入的正數段。");
        if (total != request.steps) throw new IllegalStateException("整數餘數分配後總量不符。");
        String warning = "補走是人工／程式步數。Health Connect 寫入、聚合可見、下游同步、遊戲採計仍分開。";
        return new Result(plans, "預覽 " + plans.size() + " 筆、共 " + request.steps + " 步、" + elapsedMinutes + " 分鐘。", warning, elapsedMinutes);
    }

    private static Result planRecent(StepCatchUpRequest request, StepState state, long now, StepZone zone, StepRng rng) {
        long end = (now / 60_000L) * 60_000L;
        long dayStart = zone.dayStartMillis(now);
        long earliest = Math.max(dayStart, end - StepCatchUpRequest.MAX_MINUTES * 60_000L);
        List<long[]> newest = new ArrayList<long[]>();
        long capacity = 0L;
        long cursor = end;
        while (cursor - 60_000L >= earliest && capacity < request.steps) {
            long start = cursor - 60_000L;
            if (!state.ledger.overlaps(start, cursor)) {
                newest.add(new long[] { start, cursor, 0L });
                capacity += request.ratePerMinute;
            }
            cursor = start;
        }
        if (newest.isEmpty()) throw new IllegalArgumentException("今天沒有可補走的完整分鐘。");
        if (capacity < request.steps)
            throw new IllegalArgumentException("今天可用時段不足；依目前速度最多可補 " + capacity + " 步。");

        long remaining = request.steps;
        for (int i = 0; i < newest.size() && remaining > 0L; i++) {
            long count = Math.min((long) request.ratePerMinute, remaining);
            newest.get(i)[2] = count;
            remaining -= count;
        }
        List<long[]> chronological = new ArrayList<long[]>();
        for (int i = newest.size() - 1; i >= 0; i--) {
            long[] minute = newest.get(i);
            if (minute[2] > 0L) chronological.add(minute);
        }
        List<StepPlan> plans = mergeRecent(chronological, zone, rng);
        if (plans.isEmpty() || remaining != 0L) throw new IllegalStateException("自動回推未能完整分配步數。");
        long oldest = chronological.get(0)[0];
        long elapsedMinutes = (end - oldest) / 60_000L;
        String warning = "補走是人工／程式步數。Health Connect 寫入、聚合可見、下游同步、遊戲採計仍分開。";
        return new Result(plans, "預覽 " + plans.size() + " 筆、共 " + request.steps
                + " 步、往前回推約 " + elapsedMinutes + " 分鐘。", warning, elapsedMinutes);
    }

    private static List<StepPlan> mergeRecent(List<long[]> minutes, StepZone zone, StepRng rng) {
        List<StepPlan> plans = new ArrayList<StepPlan>();
        long start = 0L, end = 0L, count = 0L;
        for (int i = 0; i < minutes.size(); i++) {
            long[] minute = minutes.get(i);
            boolean contiguous = count > 0L && end == minute[0]
                    && minute[1] - start <= StepSettings.MAX_RECORD_MS;
            if (!contiguous && count > 0L) {
                plans.add(newCatchUpPlan(start, end, count, zone, rng));
                count = 0L;
            }
            if (count == 0L) start = minute[0];
            end = minute[1];
            count += minute[2];
        }
        if (count > 0L) plans.add(newCatchUpPlan(start, end, count, zone, rng));
        return plans;
    }

    private static StepPlan newCatchUpPlan(long start, long end, long count, StepZone zone, StepRng rng) {
        String id = rng.newId();
        return new StepPlan(id, StepPlan.CATCH_UP, StepPlan.PREPARED, count, start, end,
                zone.offsetSecondsAt(start), zone.offsetSecondsAt(end), "pcc-step-" + id, 0L, "", "");
    }

    private static long localEpoch(StepCatchUpRequest request, StepZone zone, boolean start) {
        int hour = start ? request.startHour : request.endHour;
        int minute = start ? request.startMinute : request.endMinute;
        if (!zone.localTimeExists(request.year, request.month, request.day, hour, minute))
            throw new IllegalArgumentException("此時段在夏令時間不存在。");
        List<Integer> offsets = zone.offsetsAtLocal(request.year, request.month, request.day, hour, minute);
        Integer chosen = start ? request.startOffsetSeconds : request.endOffsetSeconds;
        int offset;
        if (offsets.size() == 1) offset = offsets.get(0).intValue();
        else if (chosen == null) throw new IllegalArgumentException("重複當地時間需明確 offset。");
        else if (!offsets.contains(chosen)) throw new IllegalArgumentException("offset 與當地重複時間不符。");
        else offset = chosen.intValue();
        return zone.toEpochMillis(request.year, request.month, request.day, hour, minute, offset);
    }

    private static List<StepPlan> split(StepCatchUpRequest request, long start, long end, StepZone zone, StepRng rng) {
        List<long[]> spans = new ArrayList<long[]>();
        long cursor = start;
        while (cursor < end) {
            long next = Math.min(end, cursor + StepSettings.MAX_RECORD_MS);
            if (next <= cursor) break;
            spans.add(new long[] { cursor, next });
            cursor = next;
        }
        long totalMs = end - start;
        long[] counts = new long[spans.size()];
        long assigned = 0L;
        for (int i = 0; i < spans.size(); i++) {
            long spanMs = spans.get(i)[1] - spans.get(i)[0];
            if (i == spans.size() - 1) counts[i] = request.steps - assigned;
            else {
                long share = Math.round(request.steps * (spanMs / (double) totalMs));
                if (share < 0L) share = 0L;
                counts[i] = share;
                assigned += share;
            }
        }
        if (counts[spans.size() - 1] < 0L) {
            long deficit = -counts[spans.size() - 1];
            counts[spans.size() - 1] = 0L;
            for (int i = 0; i < spans.size() - 1 && deficit > 0L; i++) {
                long take = Math.min(counts[i], deficit);
                counts[i] -= take;
                deficit -= take;
            }
            if (deficit > 0L) throw new IllegalStateException("無法在不產生負步數下分配。");
        }
        List<StepPlan> plans = new ArrayList<StepPlan>();
        for (int i = 0; i < spans.size(); i++) {
            long count = counts[i];
            if (count < 1L) continue;
            long spanStart = spans.get(i)[0];
            long spanEnd = spans.get(i)[1];
            if (spanEnd <= spanStart) continue;
            String id = rng.newId();
            plans.add(new StepPlan(id, StepPlan.CATCH_UP, StepPlan.PREPARED, count, spanStart, spanEnd,
                    zone.offsetSecondsAt(spanStart), zone.offsetSecondsAt(spanEnd), "pcc-step-" + id, 0L, "", ""));
        }
        return plans;
    }

    static int[] localYmd(StepZone zone, long epochMs) {
        // Used by UI; keep planner free of Android.
        String key = zone.dayKey(epochMs);
        String[] p = key.split("-");
        return new int[] { Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]) };
    }

    static int[] localHm(StepZone zone, long epochMs) {
        int minute = zone.minuteOfDay(epochMs);
        return new int[] { minute / 60, minute % 60 };
    }
}

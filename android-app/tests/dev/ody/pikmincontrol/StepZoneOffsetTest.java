package dev.ody.pikmincontrol;

import java.util.TimeZone;

public final class StepZoneOffsetTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        dynamicZoneFollowsSystemDefault();
        dstStartAndEndOffsetsDiffer();
        adapterPassesBothOffsets();
        previewRejectsEpochChange();
        rollbackRejectedOrReplanned();
        System.out.println("PASS StepZoneOffsetTest " + checks + " checks; R9 zone/offset");
    }

    static void dynamicZoneFollowsSystemDefault() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
            StepSystemZone zone = new StepSystemZone();
            String first = zone.zoneId();
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            String second = zone.zoneId();
            check("Asia/Taipei".equals(first), "first zone Taipei was " + first);
            check(!first.equals(second), "same engine zone object follows system default");
            check("UTC".equals(second) || "Etc/UTC".equals(second) || "GMT".equals(second) || second.contains("UTC"),
                    "second is UTC-like: " + second);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    static void dstStartAndEndOffsetsDiffer() {
        StepSystemZone zone = new StepSystemZone("America/New_York");
        long start = zone.toEpochMillis(2024, 11, 3, 1, 30, -4 * 3600);
        long end = zone.toEpochMillis(2024, 11, 3, 1, 30, -5 * 3600);
        check(zone.offsetSecondsAt(start) != zone.offsetSecondsAt(end), "DST overlap offsets differ");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 11, 3, 12, 0, -5 * 3600), 1);
        MemoryStepStore store = new MemoryStepStore();
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        StepView view = StepTestConfirm.previewCatchUp(engine, new StepCatchUpRequest("window", 180, 10, 2024, 11, 3, 1, 0, 3, 0,
                Integer.valueOf(-4 * 3600), Integer.valueOf(-5 * 3600)));
        check(!view.previewPlans.isEmpty(), "dst preview");
        StepPlan first = view.previewPlans.get(0);
        StepPlan last = view.previewPlans.get(view.previewPlans.size() - 1);
        check(first.startOffsetSeconds == -4 * 3600, "window start offset");
        check(last.endOffsetSeconds == -5 * 3600, "window end offset");
        long crossStart = zone.toEpochMillis(2024, 11, 3, 1, 59, -4 * 3600);
        long crossEnd = crossStart + 180_000L;
        check(zone.offsetSecondsAt(crossStart) != zone.offsetSecondsAt(crossEnd), "3min span crosses DST");
        int[] across = StepHealthConnectProbe.writeZoneOffsets(new StepPlan("x", StepPlan.CATCH_UP, StepPlan.PREPARED, 1,
                crossStart, crossEnd, zone.offsetSecondsAt(crossStart), zone.offsetSecondsAt(crossEnd), "cx", 0L, "", ""));
        check(across[0] != across[1], "crossing plan keeps both offsets");
    }

    static void adapterPassesBothOffsets() {
        StepPlan plan = new StepPlan("id", StepPlan.CATCH_UP, StepPlan.PREPARED, 10, 1L, 2L,
                -4 * 3600, -5 * 3600, "c", 0L, "", "");
        int[] forwarded = StepHealthConnectProbe.writeZoneOffsets(plan);
        check(forwarded[0] == -4 * 3600, "start offset forwarded");
        check(forwarded[1] == -5 * 3600, "end offset forwarded");
        check(forwarded[0] != forwarded[1], "adapter does not collapse offsets");
        java.time.ZoneOffset[] offsets = StepHealthConnectProbe.recordOffsets(forwarded[0], forwarded[1]);
        check(offsets[0].getTotalSeconds() == forwarded[0], "builder start offset");
        check(offsets[1].getTotalSeconds() == forwarded[1], "builder end offset");
    }

    static void previewRejectsEpochChange() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
            StepSystemZone zone = new StepSystemZone();
            MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 1);
            MemoryStepStore store = new MemoryStepStore();
            StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, new FakeStepHealth(), new StepWorkLock(store));
            engine.disclose();
            int epoch = zone.clockEpoch(clock.wall);
            StepCatchUpRequest request = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null, epoch);
            check(!StepTestConfirm.previewCatchUp(engine, request).previewPlans.isEmpty(), "preview in Taipei");
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            StepView changed = StepTestConfirm.confirmCatchUp(engine, request);
            check(StepView.REJECTED.equals(changed.phase), "epoch change rejected");
            check(changed.detail.contains("時區") || changed.detail.contains("預覽"), "asks re-preview: " + changed.detail);
        } finally {
            TimeZone.setDefault(original);
        }
    }

    static void rollbackRejectedOrReplanned() {
        StepSystemZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 1);
        MemoryStepStore store = new MemoryStepStore();
        StepEngine engine = new StepEngine(clock, zone, new FixedStepRng(0.5d), store, new FakeStepHealth(), new StepWorkLock(store));
        engine.disclose();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 10, 10, 2024, 1, 1, 10, 0, 11, 0, null, null);
        check(!StepTestConfirm.previewCatchUp(engine, request).previewPlans.isEmpty(), "preview");
        clock.wall = zone.toEpochMillis(2024, 1, 1, 10, 30, 8 * 3600);
        StepView rolled = StepTestConfirm.confirmCatchUp(engine, request);
        check(StepView.REJECTED.equals(rolled.phase), "clock rollback makes window future");
    }
}

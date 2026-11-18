package dev.ody.pikmincontrol;

public final class StepUiCopyTest {
    public static void main(String[] args) {
        if (!"未知".equals(StepView.totalText(null))) throw new AssertionError("unknown");
        if ("0".equals(StepView.totalText(null))) throw new AssertionError("must not coerce null to 0");
        if (!"0".equals(StepView.totalText(Long.valueOf(0L)))) throw new AssertionError("real zero remains 0");
        if (!StepView.outcomeNote().contains("四件不同的事")) throw new AssertionError("four outcomes");
        if (StepView.phaseLabel(StepView.WRITING).equals(StepView.phaseLabel(StepView.TARGET_REACHED)))
            throw new AssertionError("distinct phases");
        if (StepView.outcomeNote().contains("遊戲已加步數")) throw new AssertionError("must not claim game counted");
        if (!"slow".equals(StepSettings.normalizePreset("slow"))) throw new AssertionError("slow preset");
        if (StepSettings.parse(8000, 480, 1320, 1, "fast", 60_000L).ratePerMinute != StepSettings.FAST_RATE)
            throw new AssertionError("fast preset rate");
        if (StepSettings.defaults().target != 50000 || StepSettings.FAST_RATE != 200 || StepSettings.RUN_RATE != 350)
            throw new AssertionError("requested defaults and presets");
        System.out.println("PASS StepUiCopyTest unknown totals and four-outcome copy");
    }
}

package dev.ody.pikmincontrol;

public final class WorkProgressTest {
    private static int checks;
    private static void check(boolean b) { if (!b) throw new AssertionError("check " + checks); checks++; }
    public static void main(String[] args) {
        check(WorkProgress.active("running")); check(WorkProgress.active("stopping"));
        for (String s : new String[]{"failed", "error", "stopped", "completed", "stop-incomplete", ""}) check(!WorkProgress.active(s));
        check("completed".equals(WorkProgress.terminal(false, "", "")));
        check("stopped".equals(WorkProgress.terminal(true, "", "")));
        check("error".equals(WorkProgress.terminal(false, "RPC failure", "")));
        check("stop-incomplete".equals(WorkProgress.terminal(true, "", "readback failed")));
        check("stop-incomplete".equals(WorkProgress.terminal(false, "RPC failure", "readback failed")));
        StringBuilder errors = new StringBuilder();
        StopChecks.attempt(errors, "first", () -> { throw new IllegalStateException(); });
        StopChecks.attempt(errors, "second", () -> { throw new IllegalArgumentException(); });
        StopChecks.attempt(errors, "ok", () -> { });
        check(errors.toString().equals("first：IllegalStateException；second：IllegalArgumentException"));
        JoystickReadiness a = new JoystickReadiness(true, true, true, 1000);
        check(JoystickReadiness.label(a, new JoystickReadiness(true, true, true, 2000), 2500).contains("更新中"));
        check(!JoystickReadiness.label(a, a, 2500).contains("更新中"));
        check(JoystickReadiness.label(a, a, 10000).contains("尚無新鮮"));
        check(JoystickReadiness.label(a, new JoystickReadiness(true, true, false, 2000), 2500).contains("其他"));
        check(JoystickReadiness.label(a, new JoystickReadiness(false, false, false, -1), 2500).contains("無法確認"));
        check(JoystickReadiness.label(a, new JoystickReadiness(true, false, false, -1), 2500).contains("不是模擬"));
        check(NectarModuleGateway.controlCommand(false).contains("/nectar_control_mode.txt.tmp"));
        check(NectarModuleGateway.controlCommand(false).contains("printf 'diag\\n'"));
        check(NectarModuleGateway.controlCommand(true).contains("printf 'auto\\n'"));
        System.out.println("PASS: WorkProgress / stop errors / GPS readiness / nectar control " + checks);
    }
}

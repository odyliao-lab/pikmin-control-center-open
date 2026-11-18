package dev.ody.pikmincontrol;

public final class JoystickReadinessTest {
    private static final String OWNER = "12345/example.joystick";
    private static final String GOOD = "    gps provider [mock]:\n      allowed=true\n      identity=" + OWNER
            + "\n      last mock location=Location[gps 25.0,121.0 et=+1h2m3s4ms mock]\n    fused provider:\n";
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        JoystickReadiness sample = JoystickReadiness.parse(GOOD, OWNER);
        check(sample.known && sample.own && sample.mock && sample.fixMs == 3723004);
        check(sample.fresh(3725004)); check(!sample.fresh(3730000)); check(!sample.fresh(3723003));
        check(!JoystickReadiness.parse(GOOD, "999/another.app").own);
        check(!JoystickReadiness.parse(GOOD.replace("allowed=true", "allowed=false"), OWNER).fresh(3725004));
        check(!JoystickReadiness.parse(GOOD.replace("25.0", "NaN"), OWNER).fresh(3725004));
        check(!JoystickReadiness.parse(GOOD.replace("121.0", "181.0"), OWNER).fresh(3725004));
        check(!JoystickReadiness.parse(GOOD.replace(" [mock]", ""), OWNER).mock);
        check(!JoystickReadiness.parse("    gps provider:\n    Historical:\n" + GOOD.replace("    gps provider [mock]:", "old gps provider [mock]:"), OWNER).own);
        check(!JoystickReadiness.parse("", OWNER).fresh(3725004));
        check(!JoystickReadiness.parse("Permission denied", OWNER).known);
        check(JoystickReadiness.elapsed("+1d2h3m4s5ms") == 93784005);
        check(JoystickReadiness.elapsed("+123ms") == 123);
        check(JoystickReadiness.elapsed("+1garbage2s") == -1);
        check(JoystickReadiness.elapsed("+99999999999999999999999d") == -1);
        check(JoystickReadiness.elapsed("-1s") == -1);
        System.out.println("PASS: provider ownership, fresh monotonic fix, range, history isolation and malformed timestamps");
    }
}

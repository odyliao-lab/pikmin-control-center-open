package dev.ody.pikmincontrol;

public final class PlanterStepsTest {
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        check(PlanterSteps.parse(null) == 14);
        check(PlanterSteps.parse("1000 3000 5000\n") == 14);
        check(PlanterSteps.parse("") == 0);
        check(PlanterSteps.parse("none") == 0);
        check(PlanterSteps.parse("none 1000") == 0);
        check(PlanterSteps.parse("1000 invalid") == 0);
        check(PlanterSteps.parse("100\t10000 other") == 49);
        check(PlanterSteps.parse("1000 1000") == 2);
        check("1000 3000 5000".equals(PlanterSteps.encode(14)));
        check("none".equals(PlanterSteps.encode(0)));
        for (int i = 0; i < 64; i++) check(PlanterSteps.parse(PlanterSteps.encode(i)) == i);
        try { PlanterSteps.encode(64); throw new AssertionError(); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: planter defaults, invalid data, all 64 mask round-trips");
    }
}

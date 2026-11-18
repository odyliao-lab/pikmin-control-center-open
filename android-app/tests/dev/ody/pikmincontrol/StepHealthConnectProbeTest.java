package dev.ody.pikmincontrol;

/** Compile-and-constant checks only. Does not construct Android health records at runtime. */
public final class StepHealthConnectProbeTest {
    public static void main(String[] args) {
        if (StepHealthConnectProbe.MIN_SDK != 34) throw new AssertionError("API floor");
        if (!"android.permission.health.READ_STEPS".equals(StepHealthConnectProbe.READ_STEPS))
            throw new AssertionError("READ_STEPS");
        if (!"android.permission.health.WRITE_STEPS".equals(StepHealthConnectProbe.WRITE_STEPS))
            throw new AssertionError("WRITE_STEPS");
        if (!"android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND".equals(StepHealthConnectProbe.READ_BACKGROUND))
            throw new AssertionError("READ_BACKGROUND");
        if (!"android.health.connect.action.MANAGE_HEALTH_PERMISSIONS".equals(StepHealthConnectProbe.MANAGE_ACTION))
            throw new AssertionError("manage action");
        String[] requested = StepHealthConnectProbe.requestedPermissions();
        if (requested.length != 3) throw new AssertionError("permission set size");
        System.out.println("PASS StepHealthConnectProbe constants; no Health Connect write");
    }
}

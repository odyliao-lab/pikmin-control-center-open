package dev.ody.pikmincontrol;

/** Run manually via app_process while the game is open and planter mode is off. */
public final class PlanterStepsDeviceTest {
    public static void main(String[] args) throws Exception {
        PlanterState before = PlanterGateway.read();
        if (!before.fresh || !before.supportsSteps || !"off".equals(before.mode))
            throw new IllegalStateException("Requires live filter-capable game, planter off");
        try {
            if (PlanterGateway.steps() != PlanterSteps.DEFAULT || before.observedSteps != PlanterSteps.DEFAULT)
                throw new AssertionError("Default is not 1000/3000/5000");
            verify(0);
            verify(49); // 100 / 10000 / other, opposite of the default choices.
            verify(PlanterSteps.DEFAULT);
            System.out.println("PASS: real Gateway save/read, native none/custom/default acknowledgement");
        } finally {
            PlanterGateway.setSteps(PlanterSteps.DEFAULT);
        }
    }

    private static void verify(int mask) throws Exception {
        PlanterGateway.setSteps(mask);
        if (PlanterGateway.steps() != mask) throw new AssertionError("Control file round-trip failed");
        for (int attempt = 0; attempt < 12; attempt++) {
            PlanterState state = PlanterGateway.read();
            if (!"off".equals(state.mode)) throw new AssertionError("Planter must remain off during test");
            if (state.fresh && state.observedSteps == mask) {
                System.out.println("native acknowledged mask=" + mask);
                return;
            }
            Thread.sleep(1000);
        }
        throw new AssertionError("Native did not apply mask=" + mask);
    }
}

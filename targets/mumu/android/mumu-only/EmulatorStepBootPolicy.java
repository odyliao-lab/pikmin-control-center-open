package dev.ody.pikmincontrol;

/** MuMu flavor only. Never bypass same-boot backoff, pending reconciliation or a writer lock. */
final class EmulatorStepBootPolicy {
    static boolean mayRebaseStopped(StepState state, String currentBoot) {
        return state != null && !state.desiredRunning && state.ledger.pending() == null
                && state.lockId.length() == 0 && state.writerOwner.length() == 0
                && state.bootId.length() > 0 && currentBoot != null && currentBoot.length() > 0
                && !"boot".equals(currentBoot) && !"boot".equals(state.bootId)
                && !currentBoot.equals(state.bootId);
    }
}

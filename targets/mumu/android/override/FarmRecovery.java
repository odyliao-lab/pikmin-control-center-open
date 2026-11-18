package dev.ody.pikmincontrol;

/** Recovery never emits planting-on or GPS commands. Consent is per launch. */
final class FarmRecovery {
    static final long WAIT_MS = 60_000L;
    static boolean suggested(String state) {
        return "error".equals(state) || "stopped".equals(state)
                || "stop-incomplete".equals(state) || WorkProgress.active(state);
    }
    static final class Gate {
        private final long deadline;
        private long requestedAt, firstIdle, previous;
        private boolean offRequested;
        Gate(long elapsed, long wall) { deadline = elapsed + WAIT_MS; requestedAt = wall; }
        boolean waiting(long elapsed, boolean stopping) { return !stopping && elapsed < deadline; }
        boolean observed(PlantingState s) {
            return fresh(s) && "observe".equals(s.mode) && !"controller-unavailable".equals(s.action);
        }
        void offRequested(long wall) { offRequested = true; requestedAt = wall; firstIdle = previous = 0; }
        boolean ready(PlantingState s) {
            if (!offRequested || !fresh(s) || FarmStartup.plantingBusy(s)
                    || !("off".equals(s.action) || "stopped".equals(s.action))) {
                firstIdle = previous = 0; return false;
            }
            if (firstIdle == 0 || s.at < previous || s.at - previous > 2500) firstIdle = s.at;
            previous = s.at;
            return s.at - firstIdle >= 3000;
        }
        private boolean fresh(PlantingState s) { return s != null && s.valid && s.at > requestedAt; }
    }
}

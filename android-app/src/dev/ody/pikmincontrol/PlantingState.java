package dev.ody.pikmincontrol;

/** Actual planting bit, never the last action label (which can outlive planting). */
final class PlantingState {
    final long at;
    final boolean valid, planting, owned;
    final String mode, action;

    PlantingState(String raw, String beforePid, String afterPid, String expectedPid, long now) {
        long timestamp = 0;
        boolean ok = false, active = false, owner = false;
        String request = "", result = "";
        try {
            String[] f = raw.trim().split("\t", -1);
            String pid = beforePid.trim();
            timestamp = Long.parseLong(f[0]);
            request = f[1];
            active = "1".equals(f[2]);
            owner = "1".equals(f[3]);
            result = f[4];
            ok = f.length == 6 && pid.matches("[1-9][0-9]*")
                    && pid.equals(afterPid.trim()) && pid.equals(expectedPid)
                    && timestamp > 0 && timestamp <= now && now - timestamp <= 2000
                    && ("on".equals(request) || "off".equals(request) || "observe".equals(request))
                    && ("0".equals(f[2]) || active) && ("0".equals(f[3]) || owner);
        } catch (RuntimeException ignored) { }
        at = timestamp; valid = ok; planting = active; owned = owner; mode = request; action = result;
    }

    boolean activeSince(long requestAt) {
        return valid && at > requestAt && "on".equals(mode) && planting && owned;
    }

    static final class StartGate {
        private long first;
        boolean observe(PlantingState state, long requestAt) {
            if (!state.activeSince(requestAt)) { first = 0; return false; }
            if (first == 0 || state.at < first || state.at - first > 3000) {
                first = state.at; return false;
            }
            return state.at - first >= 1000;
        }
    }
}

package dev.ody.pikmincontrol;

public final class PlantingStateTest {
    static final long NOW = 1788704000000L;
    static int checks;
    static void check(boolean value) { ++checks; if (!value) throw new AssertionError("check " + checks); }
    static PlantingState state(long at, String mode, String active, String owned) {
        return new PlantingState(at + "\t" + mode + "\t" + active + "\t" + owned + "\tactive-owned\t-",
                "1829", "1829", "1829", NOW);
    }
    public static void main(String[] args) {
        long request = NOW - 1900;
        check(state(NOW, "on", "1", "1").activeSince(request));
        check(!state(NOW, "on", "0", "1").activeSince(request)); // real failure: stale action says active-owned
        check(!state(NOW, "on", "1", "0").activeSince(request)); // external/manual session
        check(!state(NOW, "off", "1", "1").activeSince(request));
        check(!state(NOW, "observe", "1", "1").activeSince(request));
        check(!state(request, "on", "1", "1").activeSince(request));
        check(!state(NOW - 2001, "on", "1", "1").valid);
        check(!state(NOW + 1, "on", "1", "1").valid);
        check(!state(NOW, "on", "yes", "1").valid);
        check(!state(NOW, "on", "1", "yes").valid);
        check(!state(NOW, "unknown", "1", "1").valid);
        String raw = NOW + "\ton\t1\t1\tactive-owned\t-";
        check(!new PlantingState(raw, "1829", "1830", "1829", NOW).valid);
        check(!new PlantingState(raw, "1830", "1830", "1829", NOW).valid);
        check(!new PlantingState(raw, "1829 1830", "1829 1830", "1829 1830", NOW).valid);
        check(!new PlantingState(raw + "\textra", "1829", "1829", "1829", NOW).valid);
        check(!new PlantingState("", "1829", "1829", "1829", NOW).valid);
        check(!new PlantingState(null, "1829", "1829", "1829", NOW).valid);
        PlantingState.StartGate gate = new PlantingState.StartGate();
        check(!gate.observe(state(NOW - 1500, "on", "1", "1"), request));
        check(!gate.observe(state(NOW - 1500, "on", "1", "1"), request));
        check(!gate.observe(state(NOW - 1000, "on", "1", "1"), request));
        check(gate.observe(state(NOW - 500, "on", "1", "1"), request));
        check(!gate.observe(state(NOW - 400, "on", "0", "1"), request));
        check(!gate.observe(state(NOW - 300, "on", "1", "1"), request));
        check(!gate.observe(state(NOW, "on", "1", "1"), request));
        System.out.println("PASS: " + checks + " planting-state checks");
    }
}

package dev.ody.pikmincontrol;

public final class BatchTargetCandidateTest {
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    private static String row(long time, String id, double distance, long duration, boolean canStart) {
        return time + "\tseed\t" + id + "\t0\t0\t25.0\t121.0\tpending-travel-estimate\t"
                + distance + "\t" + duration + "\t" + (canStart ? 1 : 0) + "\t3\n";
    }

    public static void main(String[] args) {
        String command = DispatchProbeGateway.candidateReadCommand();
        check(command.contains("dispatch_target_candidate.tsv") && command.contains("dispatch_candidates.tsv"),
                "both target and complete projection are read");
        String oldCore = row(1000, "wanted", 50.0, 10000, false);
        DispatchProbeGateway.GameSide onlyFull = DispatchProbeGateway.parseGameSide(oldCore, "wanted");
        check(onlyFull != null && onlyFull.observedMs == 1000, "old core retains complete-list fallback");
        String fast = row(2000, "wanted", 0.3, 200, true);
        DispatchProbeGateway.GameSide newest = DispatchProbeGateway.parseGameSide(fast + oldCore, "wanted");
        check(newest != null && newest.observedMs == 2000 && newest.canStart && newest.metres == 0.3,
                "new target observation wins");
        newest = DispatchProbeGateway.parseGameSide(oldCore + fast, "wanted");
        check(newest != null && newest.observedMs == 2000, "row order does not matter");
        newest = DispatchProbeGateway.parseGameSide(fast + row(3000, "wanted", 0.4, 300, true), "wanted");
        check(newest != null && newest.observedMs == 3000, "stale target cannot hide newer complete scan");
        check(DispatchProbeGateway.parseGameSide(row(3000, "other", 0, 1, true), "wanted") == null,
                "a different target is never reused");
        check(DispatchProbeGateway.parseGameSide(row(2000, "wanted", 0.3, 200, true)
                + row(2000, "wanted", 100, 10000, false), "wanted") == null,
                "same-timestamp conflict fails closed");
        check(DispatchProbeGateway.parseGameSide(row(0, "wanted", 0, 1, true), "wanted") == null,
                "invalid timestamp cannot authorise arrival");
        System.out.println("PASS BatchTargetCandidateTest");
    }
}

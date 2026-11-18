package dev.ody.pikmincontrol;

public final class BatchArrivalProofTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        BatchArrivalProof p = new BatchArrivalProof();
        // Libra's 17-item run: the first good sample was followed by a
        // transiently missing candidate, then a distinct scan five seconds later.
        check(p.accept(20_000, 1_000_000) == 1, "first fresh scan");
        check(p.missing(22_000) == 1, "one absent candidate preserves first scan");
        check(p.accept(23_000, 1_000_000) == 1, "same TSV timestamp cannot authorise");
        check(p.accept(25_000, 1_005_000) == 2, "second distinct scan authorises");

        p.reset();
        check(p.accept(40_000, 1_020_000) == 1, "new stop needs first scan");
        check(p.missing(55_000) == 1, "boundary still retained");
        check(p.accept(55_001, 1_025_000) == 1, "expired first scan cannot authorise");
        check(p.accept(60_000, 1_030_000) == 2, "new pair after expiry");

        p.reset();
        check(p.accept(70_000, 1_040_000) == 1, "first scan before GPS drift");
        p.reset(); // Service resets on provider drift, old/post-settle game data and read failure.
        check(p.accept(75_000, 1_045_000) == 1, "drifted stop cannot reuse first scan");
        check(p.accept(80_000, 1_045_000) == 1, "duplicate never authorises");
        check(p.accept(81_000, 0) == 0, "invalid timestamp clears first scan");
        check(p.accept(82_000, 1_050_000) == 1, "first scan after invalid timestamp");
        check(p.accept(81_000, 1_055_000) == 1, "regressed elapsed clock clears proof");
        check(DispatchProbeGateway.detailLabel("awaiting_native_scan_candidate_missing_1_of_2").contains("保留首次確認"),
                "transient missing scan remains readable in the overlay");
        check(DispatchProbeGateway.detailLabel("awaiting_native_scan_scan_stale_0_of_2").contains("等待重新確認"),
                "expired scan does not imply arrival proof");
        System.out.println("PASS BatchArrivalProof: " + checks + " distinct-scan, missing, expiry and reset checks");
    }
}

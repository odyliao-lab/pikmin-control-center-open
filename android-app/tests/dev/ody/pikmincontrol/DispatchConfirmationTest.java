package dev.ody.pikmincontrol;

public final class DispatchConfirmationTest {
    private static void check(boolean expected, String row, String task, long since) {
        if (DispatchConfirmation.rewardCollectedSince(row, task, since) != expected)
            throw new AssertionError("unexpected result: " + row);
    }
    public static void main(String[] args) {
        String ok = "200\tbatch-confirmed\titem\t34\t2\t0\tkeep\tall\texpedition:seed:30";
        check(true, ok, "item", 200);
        check(true, "garbage\n" + ok + "\r\n", "item", 100);
        check(true, ok.replace("batch-confirmed", "one-confirmed"), "item", 100);
        check(false, ok, "other", 100);
        check(false, ok, "ite", 100);
        check(false, ok, "item", 201);
        check(false, ok.replace("batch-confirmed", "batch-dispatched"), "item", 100);
        check(false, ok.replace("34\t2\t0", "34\t0\t0"), "item", 100);
        check(false, ok.replace("34\t2\t0", "34\t2\t1"), "item", 100);
        check(false, ok.replace("200", "invalid"), "item", 100);
        check(false, "200\tbatch-confirmed\titem", "item", 100);
        check(false, null, "item", 100);
        check(false, ok, "", 100);
        check(false, ok, "item", 0);
        check(true, ok.replace("expedition:seed:30", ""), "item", 100);
        System.out.println("PASS: 15 reward confirmation cases");
        String rejected = "90\tgps-requested\tgift\titem\t0\t0\n200\tgift-pikmin-unavailable\tgift\titem\t0\t0\n";
        if (new DispatchConfirmation.Evidence(rejected.substring(rejected.indexOf('\n')+1),"","item",100).safelyRejected())
            throw new AssertionError("truncated window");
        if (!new DispatchConfirmation.Evidence(rejected,"","item",100).safelyRejected()) throw new AssertionError("rejected");
        for (String event : new String[]{"start-requested","start-rpc-faulted","inventory-confirmed","inventory-confirmed-absent"}) {
            String mixed = rejected + "201\t"+event+"\tgift\titem\t0\t0\n";
            if (new DispatchConfirmation.Evidence(mixed,"","item",100).safelyRejected()) throw new AssertionError(event);
        }
        if (new DispatchConfirmation.Evidence(rejected,ok,"item",100).safelyRejected()) throw new AssertionError("collected");
        if (new DispatchConfirmation.Evidence(rejected,"","item",201).safelyRejected()) throw new AssertionError("stale");
    }
}

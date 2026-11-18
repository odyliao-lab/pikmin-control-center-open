package dev.ody.pikmincontrol;

public final class PlanterStateTest {
    private static final long NOW = 1_788_658_000_000L;
    private static final String STATUS = "v1\t" + NOW + "\t123\toff\toff\t\n";
    private static final String INVENTORY = "v1\t" + NOW + "\t123\t100\t300\n"
            + "slot\t0\t0\t-1\tseed1\t1000\t1000\tready\tseed:24:pikmin:2:category:46:asset:0\n"
            + "slot\t1\t0\t-1\tseed2\t500\t3000\tgrowing\tseed:26:pikmin:4:category:1:asset:0\n"
            + "seed\tseed1\t10\t1000\t0\t0\tseed:24\n"
            + "seed\tseed2\t10\t3000\t0\t0\tseed:26\nend\t2\n";
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        PlanterState s = new PlanterState("off", STATUS, INVENTORY, "", "123", NOW);
        check(s.fresh); check(s.summary.contains("1000 / 1000 步 · 可拔苗"));
        check(s.slots.size() == 2);
        check(s.slots.get(0).ready && s.slots.get(0).steps == 1000 && s.slots.get(0).target == 1000);
        check(!s.slots.get(1).ready && s.slots.get(1).steps == 500 && s.slots.get(1).target == 3000);
        check(new PlanterState("off", STATUS, INVENTORY.replace("slot\t1\t0", "slot\t1\t1"), "", "123", NOW).slots.size() == 1);
        check(new PlanterState("off", STATUS, INVENTORY.replace("seed2\t500", "\t500"), "", "123", NOW).slots.get(1).empty);
        check(!s.supportsSteps);
        check(new PlanterState("auto", STATUS, INVENTORY, "", "123", NOW).status.contains("等待遊戲套用"));
        check(new PlanterState("auto", STATUS, INVENTORY.replace("end\t2", "steps\t14\nend\t2"), "", "123", NOW).supportsSteps);
        check(s.summary.contains("500 / 3000 步 · 成長中")); check(s.summary.contains("藍色"));
        check(!new PlanterState("off", STATUS, INVENTORY, "", "124", NOW).fresh);
        check(!new PlanterState("off", STATUS, INVENTORY, "", "123", NOW + 61000).fresh);
        check(!new PlanterState("off", STATUS, INVENTORY.replace("end\t2", "end\t3"), "", "123", NOW).fresh);
        check(!new PlanterState("off", STATUS, "", "", "123", NOW).fresh);
        check(!new PlanterState("off", "", INVENTORY, "", "123", NOW).fresh);
        String history = (NOW - 1000) + "\tpluck-sent\tseed1\t0\n" + NOW + "\tpluck-confirmed\tseed1\t0\n";
        String log = new PlanterState("pluck", STATUS, INVENTORY, history, "123", NOW).history;
        check(log.indexOf("新皮克敏已入庫") < log.indexOf("已送出拔苗請求"));
        check("off".equals(new PlanterState("invalid", STATUS, INVENTORY, "", "123", NOW).mode));
        System.out.println("PASS: 19 planter state and presentation cases");
    }
}

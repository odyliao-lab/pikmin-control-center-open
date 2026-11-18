package dev.ody.pikmincontrol;

public final class GardenPresentationTest {
    private static final long NOW = 1000000;
    private static NearbyState state(String mode, String legacy, int mask, String status, boolean safe) {
        String ack = "v1\t" + NOW + "\t123\t" + mask + "\t" + mode + "\t" + legacy;
        String guard = "v1\t" + NOW + "\t123\t" + status + "\t0\t0\t0\t0\t0\t0\t0\t300000";
        return new NearbyState(mode, legacy, NearbyKinds.encode(mask), ack, "123", NOW, guard, safe);
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        check(state("batch", "all", 3, "ready", true).compactSummary().contains("接管中"));
        check(state("armed", "farm", 3, "ready", true).compactSummary().contains("接管中"));
        check(state("armed", "all", 3, "ready", false).compactSummary().contains("安全能力"));
        check(state("armed", "all", 0, "ready", true).compactSummary().contains("不派遣"));
        check(state("off", "all", 3, "ready", true).compactSummary().contains("已關閉"));
        check(state("armed", "all", 3, "unknown", true).compactSummary().contains("等待遊戲就緒"));
        check(state("armed", "fruit", 3, "ready", true).compactSummary().contains("舊種類限制"));
        check(state("armed", "all", 3, "location-moving", true).compactSummary().contains("暫不派遣"));
        check(state("armed", "all", 3, "ready", true).compactSummary().contains("重驗隊伍"));
        check(state("armed", "all", 3, "ready", true).summary().contains("不代表已有皮克敏出發"));
        System.out.println("PASS: 10 garden presentation safety states");
    }
}

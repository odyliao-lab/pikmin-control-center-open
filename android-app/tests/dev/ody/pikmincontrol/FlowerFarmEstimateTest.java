package dev.ody.pikmincontrol;

public final class FlowerFarmEstimateTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        // Synthetic equatorial route: ~222.4 m, including a deliberate return to the start.
        String raw = "0,0\n0,0.001\n0,0";
        FlowerFarmEstimate one = new FlowerFarmEstimate(raw, 1, 5);
        check(one.point.contains("至少 20 秒"));
        check(one.point.contains("2 點 × 1 輪"));
        check(one.point.contains("合併 1 個重複點"));
        check(one.route.contains("約 43 秒"));
        check(one.route.contains("3 節點／222 公尺"));
        FlowerFarmEstimate two = new FlowerFarmEstimate(raw, 2, 10);
        check(two.point.contains("至少 1 分 0 秒"));
        check(two.route.equals(one.route)); // Walking is always one ordered pass.
        check(one.confirmation().contains("下限"));
        check(one.confirmation().contains("不保證水果已返程或領取"));
        check(new FlowerFarmEstimate("", 1, 5).comparison().contains("輸入路線後預估"));
        check(new FlowerFarmEstimate(null, 1, 5).comparison().contains("輸入座標後預估"));
        check(new FlowerFarmEstimate("0,0", 1, 5).point.contains("至少 10 秒"));
        check(new FlowerFarmEstimate("0,0", 1, 5).route.contains("無法預估"));
        FlowerFarmEstimate filtered = new FlowerFarmEstimate("0,0\nbad\n0,0.001", 1, 5);
        check(filtered.point.contains("至少 20 秒"));
        check(filtered.point.contains("略過 1 行"));
        check(filtered.route.contains("無法預估")); // No silently filtered walking route.
        String gpx = "<gpx><trk><trkseg><trkpt lat='0' lon='0'/><trkpt lat='0' lon='0.001'/>"
                + "</trkseg></trk></gpx>";
        FlowerFarmEstimate xml = new FlowerFarmEstimate(gpx, 1, 5);
        check(xml.point.contains("GPX 僅適用沿路"));
        check(xml.route.contains("約 22 秒"));
        check(new FlowerFarmEstimate("0,0\n10,10", 1, 5).route.contains("無法預估"));
        check(new FlowerFarmEstimate("NaN,0", 1, 5).point.contains("無法預估"));
        check(new FlowerFarmEstimate(new String(new char[HarvestRoute.MAX_CHARS + 1]).replace('\0', 'x'), 1, 5)
                .route.contains("輸入過長"));
        check(FlowerFarmEstimate.duration(3661).equals("1 小時 1 分 1 秒"));
        check(FlowerFarmEstimate.duration(0).equals("0 秒"));
        System.out.println("PASS FlowerFarmEstimate: " + checks + " preflight bounds/ordered route/partial input/GPX checks; NOT device timing");
    }
}

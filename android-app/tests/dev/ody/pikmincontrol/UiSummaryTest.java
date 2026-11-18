package dev.ody.pikmincontrol;

public final class UiSummaryTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    private static PlanterState planter(String event, boolean fresh) {
        return new PlanterState("auto", "v1\t1000000\t123\t" + event + "\tauto",
                "v1\t1000000\t123\t10\t100\nsteps\t14\nend\t0", "", fresh ? "123" : "321", 1000000);
    }
    public static void main(String[] args) {
        check(UiSummary.work("running", true, true).equals("執行中 · GPS 使用中"), "running lifecycle");
        check(!UiSummary.workWarning("running", true, true), "healthy owner");
        check(UiSummary.work("running", false, false).contains("中斷"), "dead worker is not active");
        check(UiSummary.workWarning("running", false, false), "dead worker warning");
        check(UiSummary.work("stopping", true, true).contains("等待確認"), "stopping not completed");
        check(UiSummary.work("stopping", false, false).contains("中斷"), "interrupted stopping");
        check(UiSummary.work("stop-incomplete", false, false).contains("重試停止全部"), "recovery direction");
        check(UiSummary.workWarning("stop-incomplete", false, false), "cleanup warning");
        check(UiSummary.work("completed", false, true).contains("未釋放"), "held lock beats completed");
        check(UiSummary.workWarning("completed", false, true), "stale owner warning");
        check(UiSummary.work("completed", false, false).contains("上次已結束"), "terminal is historical");
        check(UiSummary.work("stopped", false, false).contains("上次已停止"), "stopped not completed");
        check(UiSummary.work("error", false, false).contains("失敗"), "terminal error visible");
        check(UiSummary.workWarning("error", false, false), "terminal error warning");
        check(UiSummary.work("", false, false).equals("尚未執行"), "no fabricated run");
        check(UiSummary.work("running", true, false).contains("待確認"), "no unproven GPS ownership");
        check(UiSummary.workWarning("running", true, false), "missing lock warning");
        check(UiSummary.planter(planter("storage-full-or-unknown", true)).contains("空間足夠"), "capacity wait visible");
        check(UiSummary.planter(planter("metadata-unavailable", false)).contains("不相容"), "incompatible visible");
        check(UiSummary.planter(planter("inventory-incomplete", false)).contains("暫不操作"), "incomplete inventory visible");
        check(UiSummary.planter(planter("waiting-growth-or-seed", false)).contains("待更新"), "stale slots not live");
        check(UiSummary.planter(planter("storage-full-or-unknown", false)).contains("上次回報"), "stale warning qualified");
        check(UiSummary.planter(planter("waiting-growth-or-seed", true)).contains("拔苗＋補種"), "mode preserved");
        check(UiSummary.planterDetail(planter("waiting-growth-or-seed", false)).equals("槽位資料待更新"), "stale inventory does not show live counts");
        check(UiSummary.planterDetail(planter("waiting-growth-or-seed", true)).equals("等待槽位回報"), "missing slots not zero success");
        PlanterState slots = new PlanterState("auto", "v1\t1000000\t123\twaiting-growth-or-seed\tauto",
                "v1\t1000000\t123\t10\t100\nslot\t0\t0\t\tseed1\t1000\t1000\tready\t\nslot\t1\t0\t\t\t0\t0\tempty\t\nend\t0", "", "123", 1000000);
        check(UiSummary.planterDetail(slots).equals("1 / 2 槽使用中 · 可拔 1"), "only occupied mature permanent slots counted");
        check(!UiSummary.planter(planter("restart-pending-review-required", false)).contains("restart-pending"), "technical event stays in details");
        check(UiSummary.planter(planter("restart-pending-review-required", false)).contains("自動觀察"), "legacy observation wait remains visible");
        check(UiSummary.nectar(new NectarModuleGateway.Snapshot("auto", new String[0], false)).contains("未偵測"), "missing nectar");
        check(UiSummary.nectar(new NectarModuleGateway.Snapshot("diag", new String[0], true)).equals("已關閉"), "nectar off");
        check(UiSummary.nectar(new NectarModuleGateway.Snapshot("auto", new String[0], true)).contains("等待遊戲"), "nectar waiting");
        String[] status = {"", "", "1", "", "1", "", "", "", "1", "SUCCESS:NONE"};
        check(UiSummary.nectar(new NectarModuleGateway.Snapshot("auto", status, true)).contains("上次"), "no freshness proof from legacy snapshot");
        check(UiSummary.reward(false, true, "").contains("已鎖定"), "reward incompatibility visible");
        check(UiSummary.reward(true, true, "安全停止：20 秒內未確認庫存更新。").contains("待確認"), "reward timeout visible");
        check(UiSummary.reward(true, true, "原生狀態格式無法辨識。").contains("待確認"), "unknown reward status visible");
        check(UiSummary.reward(true, false, "").equals("已關閉"), "reward off");
        check(UiSummary.startup("上次檢查\n啟動檢查已中斷\n本輪檢查 1/3").contains("異常"), "startup interrupted visible");
        check(UiSummary.startup("上次檢查\n高溫暫停\n本輪檢查 1/3").contains("溫控"), "thermal warning visible");
        check(UiSummary.startup("尚未檢查 GPS 服務").equals("尚未檢查 GPS 服務"), "no startup proof invented");
        System.out.println("PASS: " + checks + " compact UI presentation checks");
    }
}

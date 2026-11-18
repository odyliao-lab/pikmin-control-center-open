package dev.ody.pikmincontrol;

/** Real presentation/parser and wiring regression; no game or Android execution. */
public final class PlanterContinuousTest {
    private static int checks;
    private static final long NOW=1_000_000L;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static PlanterState state(String event,String pid,long now,String mode){
        return new PlanterState(mode,"v1\t"+NOW+"\t42\t"+event+"\tauto\t\n",
            "v1\t"+NOW+"\t42\t10\t100\nslot\t0\t0\t-1\t\t0\t0\tempty\t\nend\t0\n","",pid,now);
    }
    public static void main(String[] args)throws Exception{
        String[] issues={"observing-current-state","rpc-timeout-unknown","rpc-failed-unknown",
            "server-rejected-unknown","rpc-start-unknown","task-root-unknown","inventory-timeout-unknown",
            "session-changed-unknown","rpc-state-unknown","response-root-unknown","unexpected-response-unknown",
            "missing-new-pikmin-unknown","clock-reset-unknown","unknown-record-retained","archive-retry","audit-write-unknown","audit-write-retry","request-build-retry",
            "restart-pending-review-required","rpc-failed-review-required","server-rejected-review-required"};
        for(String event:issues){
            PlanterState s=state(event,"42",NOW,"auto");
            check(s.fresh&&s.anomaly,"current anomaly "+event);
            check(s.status.contains("等待")||s.status.contains("自動"),"continuous wait "+event);
            for(String forbidden:new String[]{"手動","安全暫停","解除","✓","補種完成","拔苗完成"})
                check(!s.status.contains(forbidden),"unknown is not completion/manual unlock: "+event);
            check(UiSummary.planter(s).contains(s.status),"anomaly stays visible "+event);
        }
        for(String event:new String[]{"waiting-rpc","waiting-inventory"}){
            PlanterState s=state(event,"42",NOW,"auto");
            check(s.fresh&&!s.anomaly&&s.status.contains("不重複送出"),"pending is not replay/success");
        }
        for(String event:new String[]{"metadata-unavailable","inventory-incomplete","session-unavailable"})
            check(!state(event,"42",NOW,"auto").fresh,"invalid source cannot become current: "+event);
        check(!state("observing-current-state","41",NOW,"auto").fresh,"old PID never current");
        check(!state("observing-current-state","42",NOW+60_001,"auto").fresh,"expired report never current");
        check(!state("observing-current-state","42",NOW-5_001,"auto").fresh,"future report never current");
        check(UiSummary.planter(state("rpc-timeout-unknown","41",NOW,"auto")).contains("上次回報"),"historical anomaly qualified");
        check(state("observing-current-state","42",NOW,"off").status.contains("等待遊戲套用"),"saved OFF differs from observed auto");
        check(state("storage-full-or-unknown","42",NOW,"auto").status.contains("等待皮克敏空間"),"capacity remains a wait");
        check(state("new-private-value","42",NOW,"auto").status.equals("育苗狀態待確認，等待新資料並自動觀察"),"unknown code does not leak raw detail");
        check(PlanterState.event("pluck-confirmed").startsWith("✓")&&PlanterState.event("plant-confirmed").startsWith("✓"),"only confirmed results show success");
        String history=NOW+"\trpc-timeout-unknown\tseed\t0\n"+(NOW+1)+"\tplant-confirmed\tseed\t0\n";
        String log=new PlanterState("auto","", "",history,"42",NOW+1).history;
        check(log.contains("結果未確認")&&log.contains("✓ 補種完成"),"history retains unknown alongside distinct later success");
        String ui=FarmResetSourceTest.source("MainActivity"),settings=FarmResetSourceTest.source("PlanterSettings");
        for(String forbidden:new String[]{"PlanterManualRecoveryGateway","PlanterReportRepairGateway","PlanterRecoveryUi",
            "confirmPlanterRecovery","confirmPlanterRetirement","runPlanterReportRepair","手動恢復育苗","封存舊育苗紀錄"})
            check(!ui.contains(forbidden),"no retired UI entry: "+forbidden);
        for(String forbidden:new String[]{"PlanterGateway.recover(","PlanterGateway.recovery(","requestRecovery(","recovery_at",
            "if(reportRepairPaused)return","if(manualRecoveryBusy)return"})check(!settings.contains(forbidden),"no manual recovery dependency: "+forbidden);
        check(settings.indexOf("PlanterGateway.setMode(wanted)")<settings.indexOf("PlanterGateway.read()"),"desired control survives unavailable status");
        for(String gate:new String[]{"WorkStatus.stoppingAll","WorkStatus.blocked(c)","CoreUpdater.requireFiles(c,CoreUpdater.pair(c))",
            "CoreInstallWizard.preparing(c)","SetupDeployment.pending(c)","UpdateInstaller.blocking(c)"})check(settings.contains(gate),"real admission retained "+gate);
        check(ui.contains("if(afterStopped==null)PlanterSettings.choose(this,\"off\")"),"stop-all preserves user OFF");
        check(ui.contains("永久槽 · 保留加星苗"),"slot/star promise retained");
        check(AlertPolicy.message("nursery").contains("自動觀察")&&!AlertPolicy.message("nursery").contains("手動"),"alert history does not require unlocking");
        String alerts=FarmResetSourceTest.source("AutomationAlerts");
        check(alerts.indexOf("if(\"nursery\".equals(owner)||!enabled(c)||!newAlert)return;")<alerts.indexOf("manager.notify("),"nursery is history only");
        System.out.println("PASS PlanterContinuous: "+checks+" parser/presentation/source checks; NOT device acceptance");
    }
}

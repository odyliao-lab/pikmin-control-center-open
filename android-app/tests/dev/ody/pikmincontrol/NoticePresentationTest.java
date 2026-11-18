package dev.ody.pikmincontrol;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class NoticePresentationTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("notice check "+checks);}
    static String source(String name)throws Exception{return new String(Files.readAllBytes(Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent().resolve("src/dev/ody/pikmincontrol/"+name+".java")),StandardCharsets.UTF_8);}
    static StepView view(String phase,Long total){return new StepView(phase,"private raw diagnostic",total,null,0,50000,true,true,false,false,"",null,null,"");}
    public static void main(String[] args)throws Exception{
        List<NoticePresentation.Row> rows=Arrays.asList(new NoticePresentation.Row(159,"日常自動化","監控中"),
            new NoticePresentation.Row(161,"步數寫入中","保存值"),new NoticePresentation.Row(154,"沿路採果 8 / 18","等待水果"),
            new NoticePresentation.Row(156,"完成報告","不是運行中的工作"),new NoticePresentation.Row(183,"權限提醒","需處理"));
        List<NoticePresentation.Row> ordered=NoticePresentation.ordered(rows);
        check(ordered.size()==3);check(ordered.get(0).id==154);check(ordered.get(1).id==161);check(ordered.get(2).id==159);
        check(!NoticePresentation.headline(rows).contains("完成報告"));check(NoticePresentation.headline(rows).startsWith("沿路採果 8 / 18"));
        check(NoticePresentation.ordered(Collections.emptyList()).isEmpty());
        for(int id:new int[]{152,153,154,158,159,161})check(NoticePresentation.ongoing(id));
        for(int id:new int[]{156,160,180,181,182,183})check(!NoticePresentation.ongoing(id));
        check(NoticePresentation.stepTitle(StepView.RESTING).equals(NoticePresentation.stepTitle(StepView.WRITING)));
        check(!NoticePresentation.stepText(view(StepView.RESTING,10L)).contains("private raw"));
        check(NoticePresentation.stepText(view(StepView.WRITING,null)).contains("未知"));
        check(NoticePresentation.stepText(view(StepView.RESULT_PENDING,10L)).contains("不重複寫入"));
        for(String phase:new String[]{StepView.WAITING_PERMISSION,StepView.STORE_ERROR,StepView.UNSUPPORTED})
            check(!NoticePresentation.stepAlert(phase).isEmpty());
        for(String phase:new String[]{StepView.RESTING,StepView.WAITING_WINDOW,StepView.RESULT_PENDING,StepView.STOPPED})
            check(NoticePresentation.stepAlert(phase).isEmpty());
        check(!NoticePresentation.stepHealthy(StepView.STOPPED));check(NoticePresentation.stepHealthy(StepView.WRITING));
        check(!NoticePresentation.newAlert("nursery","nursery"));check(NoticePresentation.newAlert("","nursery"));
        check(NoticePresentation.newAlert("location","review"));
        for(String state:new String[]{"stopping","stop-incomplete","error","unknown"})check(!NoticePresentation.clearAlert(state));
        for(String state:new String[]{"running","completed","stopped"})check(NoticePresentation.clearAlert(state));
        String helper=source("ControlNotifications");
        check(helper.contains("getActiveNotifications()"));check(helper.contains("rows.isEmpty()"));check(helper.contains("m.cancel(SUMMARY)"));
        check(helper.contains("Notification.FLAG_FOREGROUND_SERVICE"));
        check(helper.contains("setPublicVersion")&&helper.contains("VISIBILITY_PRIVATE"));
        check(!helper.contains("BoundedCommand")&&!helper.contains("root(")&&!helper.contains("stopService"));
        check(helper.contains("if(!settling)")&&helper.contains("},1000)")&&helper.contains("(app),200)"));
        check(helper.contains("actions.size()<2"));check(helper.contains("setGroupSummary(true)"));
        for(String service:new String[]{"StepService","FlowerFarmService","DispatchBatchService","GamePidRecoveryService","CollectionMonitorService","JoystickStartupService"}){
            String s=source(service);check(s.contains("ControlNotifications.grouped(this,"));check(s.contains("ControlNotifications.refresh("));check(s.contains("stopForeground(STOP_FOREGROUND_REMOVE)"));
        }
        String alerts=source("AutomationAlerts");check(alerts.contains("!enabled(c)||!newAlert"));check(alerts.contains("remove(\"active.\"+owner)"));
        check(!alerts.contains("ControlNotifications.grouped")); // attention never buried in silent ongoing group
        check(source("StepService").contains("停止步數"));check(source("FlowerFarmService").contains("停止採果"));check(source("DispatchBatchService").contains("停止批次"));
        System.out.println("PASS NoticePresentation: "+checks+" grouping/copy/privacy/resolution/source checks; NOT device notification/FGS acceptance");
    }
}

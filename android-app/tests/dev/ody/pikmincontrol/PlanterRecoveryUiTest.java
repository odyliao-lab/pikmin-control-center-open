package dev.ody.pikmincontrol;

public final class PlanterRecoveryUiTest {
    private static int checks;
    private static final long NOW=100000;
    private static void check(boolean value){if(!value)throw new AssertionError("check "+checks);checks++;}
    private static PlanterState state(long at,String pid){
        return new PlanterState("auto","v1\t"+at+"\t42\trestart-pending-review-required\tauto\t\n",
            "v1\t"+at+"\t42\t100\t300\nend\t0\n","",pid,NOW);
    }
    private static PlanterRecovery recovery(String raw,String pid){return new PlanterRecovery(raw,pid,NOW);}
    private static String report(long at,String pid,String result){return "v1\t"+at+"\t"+pid+"\tnone\t"+result;}
    private static void unavailable(String raw,String pid,String reason,String message){
        PlanterRecovery r=recovery(raw,pid);check(!r.supported);check(r.unavailable.equals(reason));
        check(r.message().contains(message));check(!r.message().contains("1.4.24"));
        PlanterRecoveryUi ui=new PlanterRecoveryUi(state(NOW,pid),r,true,false);
        check(ui.visible&&ui.enabled&&!ui.canRequest&&ui.label.equals("前往遊戲更新育苗"));
    }
    private static void rejects(String[] rows){
        try {new PlanterGateway.Snapshot(rows,NOW);throw new AssertionError("accepted invalid snapshot");}
        catch(IllegalStateException expected){checks++;}
    }
    public static void main(String[] args){
        String ready=report(NOW,"42","ready");
        unavailable("","42","missing","尚未收到");
        unavailable(ready,"","pid-unavailable","未取得遊戲程序");
        unavailable(ready,"42 43","pid-ambiguous","多個遊戲程序");
        unavailable(report(NOW,"41","ready"),"42","pid-changed","舊程序");
        unavailable(report(39999,"42","ready"),"42","stale","已過期");
        unavailable(report(NOW+1,"42","ready"),"42","clock","時間");
        unavailable(ready.replace("v1\t","v2\t"),"42","schema","格式不相容");
        unavailable("v1\tbad\t42\tnone\tready","42","malformed","不完整");
        unavailable("v1\t100000\t42","42","malformed","不完整");
        check(recovery(report(40000,"42","ready"),"42").supported);
        PlanterRecovery r=recovery(ready,"42");
        PlanterRecoveryUi fresh=new PlanterRecoveryUi(state(NOW,"42"),r,true,false);
        check(fresh.visible&&fresh.enabled&&fresh.canRequest&&fresh.label.equals("手動恢復育苗"));
        PlanterRecoveryUi staleInventory=new PlanterRecoveryUi(state(39999,"42"),r,true,false);
        check(staleInventory.enabled&&!staleInventory.canRequest&&staleInventory.message.contains("資料尚未完整更新"));
        PlanterRecoveryUi busy=new PlanterRecoveryUi(state(NOW,"42"),recovery(report(NOW,"42","checking-inventory"),"42"),true,false);
        check(busy.visible&&!busy.enabled&&!busy.canRequest);
        check(!new PlanterRecoveryUi(state(NOW,"42"),r,true,true).enabled);
        PlanterRecoveryUi completed=new PlanterRecoveryUi(state(NOW,"42"),recovery(report(NOW,"42","recovered-pluck-target-retired"),"42"),true,false);
        check(completed.message.contains("另有待確認"));
        for(String result:new String[]{"invalid-request","incompatible","future-status"})
            check(!recovery(report(NOW,"42",result),"42").message().contains("1.4.24"));
        String[] rows={"42\n","auto\n","v1\t100000\t42\trestart-pending-review-required\tauto\n",
            "v1\t100000\t42\t100\t300\nend\t0\n","",ready+"\n","42\n"};
        PlanterGateway.Snapshot snapshot=new PlanterGateway.Snapshot(rows,NOW);
        check(snapshot.state.fresh&&snapshot.recovery.supported);
        String[] changed=rows.clone();changed[6]="43\n";rejects(changed);
        for(int i=1;i<=5;i++){changed=rows.clone();changed[i]="__PCC_PLANTER_READ_ERROR__\n";rejects(changed);}
        changed=rows.clone();changed[3]=new String(new char[262145]).replace('\0','x');rejects(changed);
        changed=rows.clone();changed[0]=changed[6]="42 43\n";
        snapshot=new PlanterGateway.Snapshot(changed,NOW);check(!snapshot.state.fresh&&!snapshot.recovery.supported);
        changed=rows.clone();changed[0]=changed[6]="";
        snapshot=new PlanterGateway.Snapshot(changed,NOW);check(!snapshot.state.fresh&&!snapshot.recovery.supported);
        changed=rows.clone();changed[3]=changed[5]="";
        snapshot=new PlanterGateway.Snapshot(changed,NOW);check(!snapshot.state.fresh&&!snapshot.recovery.supported);
        rejects(new String[0]);rejects(null);
        String command=PlanterGateway.snapshotCommand();
        check(command.indexOf("pidof")<command.indexOf("planter_status.tsv"));
        check(command.lastIndexOf("pidof")>command.indexOf("planter_recovery.tsv"));
        check(!command.contains("rm ")&&!command.contains("mv ")&&!command.contains(" > "));
        System.out.println("PASS PlanterRecoveryUi: "+checks+" reason/navigation/PID/read-failure checks; NOT device acceptance");
    }
}

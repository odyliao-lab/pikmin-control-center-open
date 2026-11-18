package dev.ody.pikmincontrol;

import android.content.Context;
import java.util.ArrayList;
import java.util.List;

/** Actual production PlanterSettings/State/Steps with fake I/O only. Never executes root. */
public final class PlanterContinuousSettingsHarness {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void reset(){
        PlanterGateway.calls.clear();PlanterGateway.readFailure=false;PlanterGateway.writeFailure=false;
        PlanterGateway.fresh=false;PlanterGateway.event="observing-current-state";
        CoreUpdater.running=false;CoreUpdater.pending=false;CoreUpdater.failFiles=false;CoreUpdater.changeAfterCheck=false;
        CoreInstallWizard.busy=false;SetupDeployment.busy=false;UpdateInstaller.busy=false;WorkStatus.stoppingAll=false;WorkStatus.busy=false;
        PlanterSettings.reportRepairPaused=false;PlanterSettings.manualRecoveryBusy=false;
    }
    public static void main(String[] args)throws Exception{
        reset();Context c=new Context();PlanterSettings.choose(c,"auto");PlanterSettings.chooseSteps(c,14);
        PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.toString().equals("[mode:auto, steps:14, read]"),"desired and steps reach core before unavailable state");
        check(PlanterSettings.message(c).contains("等待新資料"),"missing report waits without changing desire");
        check(PlanterSettings.desired(c).equals("auto")&&PlanterSettings.steps(c,0)==14,"saved desire preserved");
        reset();PlanterGateway.readFailure=true;PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.toString().equals("[mode:auto, steps:14, read]"),"read failure cannot suppress saved control");
        check(PlanterSettings.message(c).contains("自動重試")&&PlanterSettings.desired(c).equals("auto"),"read failure remains automatic");
        PlanterGateway.readFailure=false;PlanterGateway.fresh=true;PlanterSettings.reconcile(c);
        check(PlanterSettings.message(c).contains("自動觀察"),"next reconcile progresses without manual command");
        reset();PlanterGateway.writeFailure=true;PlanterSettings.reconcile(c);
        check(!PlanterGateway.calls.contains("read")&&PlanterSettings.desired(c).equals("auto"),"failed write is not shown successful");
        PlanterGateway.writeFailure=false;PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.contains("read"),"transient control failure retries on next reconcile");
        reset();PlanterSettings.reportRepairPaused=true;PlanterSettings.manualRecoveryBusy=true;PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.contains("mode:auto"),"retired manual flags cannot permanently pause enabled desire");
        PlanterSettings.choose(c,"off");PlanterGateway.calls.clear();PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.toString().equals("[mode:off]"),"OFF works without reading inventory or legacy flags");
        check(PlanterSettings.message(c).equals("自動育苗已關閉"),"OFF acknowledged only after write");
        PlanterSettings.choose(c,"auto");
        for(int gate=0;gate<7;gate++){
            reset();switch(gate){case 0:WorkStatus.stoppingAll=true;break;case 1:WorkStatus.busy=true;break;
                case 2:CoreUpdater.running=true;break;case 3:CoreInstallWizard.busy=true;break;
                case 4:SetupDeployment.busy=true;break;case 5:UpdateInstaller.busy=true;break;case 6:CoreUpdater.failFiles=true;break;}
            PlanterSettings.reconcile(c);check(PlanterGateway.calls.isEmpty(),"real STOP/install/core gate "+gate);
            check(PlanterSettings.desired(c).equals("auto"),"gate preserves desire "+gate);
            reset();PlanterSettings.reconcile(c);check(PlanterGateway.calls.contains("mode:auto"),"gate release rechecks automatically "+gate);
        }
        reset();CoreUpdater.changeAfterCheck=true;PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.isEmpty(),"late installation change checked under admission monitor");
        reset();PlanterGateway.fresh=true;PlanterGateway.event="rpc-timeout-unknown";PlanterSettings.choose(c,"pluck");PlanterSettings.reconcile(c);
        check(PlanterGateway.calls.get(0).equals("mode:pluck")&&PlanterSettings.desired(c).equals("pluck"),"only-pluck choice preserved after unknown");
        check(PlanterSettings.message(c).contains("結果未確認")&&!PlanterSettings.message(c).contains("✓"),"unknown never reported successful");
        System.out.println("PASS PlanterContinuousSettings: "+checks+" actual-reconciler/fake-I/O checks; NOT Android/root acceptance");
    }
}
final class PlanterGateway {
    static final List<String> calls=new ArrayList<>();static boolean readFailure,writeFailure,fresh;static String event;
    static void setMode(String mode){calls.add("mode:"+mode);if(writeFailure)throw new IllegalStateException("fixture write failure");}
    static void setSteps(int mask){calls.add("steps:"+mask);}
    static PlanterState read(){calls.add("read");if(readFailure)throw new IllegalStateException("fixture read failure");
        String mode=calls.get(0).substring(5);
        return new PlanterState(mode,"v1\t1000\t42\t"+event+"\t"+mode,"v1\t1000\t42\t10\t100\nend\t0\n","",fresh?"42":"",1000);}
    // Deliberately no recover/requestRecovery/recovery method: compilation prevents their reintroduction.
}
final class CoreUpdater {
    static boolean running,pending,failFiles,changeAfterCheck;
    static boolean pending(Context c){return pending;}
    static void review(Context c){}
    static Object pair(Context c){return new Object();}
    static void requireFiles(Context c,Object p){if(failFiles)throw new IllegalStateException("fixture core mismatch");if(changeAfterCheck)pending=true;}
}
final class CoreInstallWizard {static boolean busy;static boolean preparing(Context c){return busy;}}
final class SetupDeployment {static boolean busy;static boolean pending(Context c){return busy;}}
final class UpdateInstaller {static boolean busy;static boolean blocking(Context c){return busy;}}
final class WorkStatus {static boolean stoppingAll,busy;static boolean blocked(Context c){return busy;}}
final class GpsWorkLock {}
final class ReturnRewardModuleGateway {static String seedLabel(String value){return value;}}

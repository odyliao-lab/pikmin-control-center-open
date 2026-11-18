package dev.ody.pikmincontrol;
public final class PlanterContinuousUiTest {
 public static void main(String[] args)throws Exception {
  int checks=0;
  for(String event:new String[]{"rpc-timeout-unknown","inventory-timeout-unknown","metadata-unavailable","observing-current-state","storage-full-or-unknown","restart-pending-review-required"}) {
   PlanterState s=new PlanterState("auto","v1\t100000\t42\t"+event+"\tauto\n","v1\t100000\t42\t1\t100\nend\t0\n","","42",100000);
   PlanterRecoveryUi ui=new PlanterRecoveryUi(s,new PlanterRecovery("","42",100000),true,false);
   if(ui.visible||ui.enabled||ui.canRequest||s.status.contains("安全暫停"))throw new AssertionError(event);checks++;
  }
  String main=FarmResetSourceTest.source("MainActivity"),runtime=FarmResetSourceTest.source("EmulatorSavedDailyRuntime"),settings=FarmResetSourceTest.source("PlanterSettings");
  if(main.contains("手動恢復育苗")||main.contains("PlanterGateway.recover()")||runtime.contains("PlanterGateway.recover()")||settings.contains("PlanterGateway.recover()"))throw new AssertionError("Legacy recovery entry retained");
  if(settings.indexOf("PlanterGateway.setMode(")>settings.indexOf("PlanterGateway.read()"))throw new AssertionError("stale read blocks saved mode");
  if(!settings.contains("EmulatorDailySupportWindow.planter")||!settings.contains("EmulatorSavedDailyRuntime.managed"))throw new AssertionError("MuMu activity window or owner bypass lost");
  System.out.println("PASS continuous MuMu UI/settings: "+(checks+3)+" HOST checks; not gameplay");
 }
}

package dev.ody.pikmincontrol;
public final class CollectionPreferenceTest {
    public static void main(String[] args) throws Exception {
        if(!CollectionPreference.summary(true,"等待遊戲","").equals("已開啟 · 等待遊戲"))throw new AssertionError("waiting retains ON");
        if(!CollectionPreference.summary(false,"等待遊戲","").equals("已關閉"))throw new AssertionError("explicit OFF");
        if(CollectionPreference.needsWrite("all\n","all"))throw new AssertionError("no heartbeat rewrite");
        if(!CollectionPreference.needsWrite("off","all"))throw new AssertionError("restore armed choice");
        java.nio.file.Path src=java.nio.file.Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent().resolve("src/dev/ody/pikmincontrol");
        String service=new String(java.nio.file.Files.readAllBytes(src.resolve("CollectionMonitorService.java")),java.nio.charset.StandardCharsets.UTF_8);
        String prefs=new String(java.nio.file.Files.readAllBytes(src.resolve("AutomationPreferences.java")),java.nio.charset.StandardCharsets.UTF_8);
        String main=new String(java.nio.file.Files.readAllBytes(src.resolve("MainActivity.java")),java.nio.charset.StandardCharsets.UTF_8);
        if(!service.contains("signal.wait(delay)")||!service.contains("cadence.next(")||!service.contains("synchronized(AutomationPreferences.class)")||!service.contains("GpsWorkLock.acquire(this,\"collection-settings\")")||!service.contains("if(writeReward||writeNectar)"))throw new AssertionError("bounded serialized monitor wiring");
        if(!prefs.contains("if(!enabled)FeedingGateway.root")||!prefs.contains("CollectionMonitorService.sync(c)"))throw new AssertionError("ON choice must not require ready core");
        String nectar=main.substring(main.indexOf("private void toggleNectar("),main.indexOf("private void toggleReturn("));
        if(nectar.contains("requireReady"))throw new AssertionError("cannot refuse desired ON on missing runtime");
        if(service.contains(".setArmed(")||service.contains("stopChoices(")||service.contains("putString(\"nectar\",\"off\")"))throw new AssertionError("monitor cannot turn off saved choices or bypass guarded nearby reconcile");
        if(!service.contains("NearbyGateway.reconcile(true)")||!service.contains("pendingOff(this,\"nearby\")"))throw new AssertionError("nearby participates in guarded reconciliation and STOP");
        System.out.println("PASS CollectionPreference: desired/waiting + monitor ownership/source contracts; NOT Android background acceptance");
    }
}

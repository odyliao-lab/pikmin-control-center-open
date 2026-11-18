package dev.ody.pikmincontrol;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

public final class NearbyPreferenceTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("nearby check "+checks);}
    static NearbyState state(String mode,String legacy,boolean safe,String perm) {
        return new NearbyState(mode,legacy,"fruit seed","","123",100000,"",safe,perm);
    }
    public static void main(String[] args)throws Exception {
        NearbyState unavailable=state("off","all",false,"644"),off=state("off","all",true,"644"),on=state("armed","all",true,"644");
        check(unavailable.canToggle(false));check(!unavailable.canToggle(true));
        check(!NearbyPreference.needsWrite(true,unavailable));
        check(NearbyPreference.summary(true,"等待核心",unavailable).startsWith("已開啟"));
        check(NearbyPreference.needsWrite(true,off));check(!NearbyPreference.needsWrite(true,on));
        check(NearbyPreference.needsWrite(false,on));check(!NearbyPreference.needsWrite(false,off));
        check(NearbyPreference.needsWrite(true,state("armed","all",true,"600")));
        for(String mode:new String[]{"batch","unknown","__PCC_CONTROL_ERROR__"}) {
            check(!NearbyPreference.needsWrite(true,state(mode,"all",true,"644")));
            check(!NearbyPreference.needsWrite(false,state(mode,"all",false,"644")));
        }
        check(!NearbyPreference.needsWrite(true,state("armed","farm",true,"644")));
        check(!NearbyPreference.needsWrite(false,state("armed","farm",true,"644")));
        for(String legacy:new String[]{"seed","fruit","gift"})check(NearbyPreference.needsWrite(true,state("off",legacy,true,"644")));
        check(NearbyPreference.summary(false,"等待核心",on).equals("已關閉"));
        check(NearbyPreference.summary(true,"",off).contains("等待同步"));
        check(NearbyPreference.summary(true,"",on).contains("已開啟"));
        check(NearbyPreference.captureMode("off","on").equals("armed"));
        check(NearbyPreference.captureMode("armed","off").equals("off"));
        check(NearbyPreference.captureMode("off","").equals("off"));
        check(NearbyPreference.captureMode("armed","").equals("armed"));
        check(NearbyPreference.captureMode("batch","on").equals("batch"));
        check(NearbyPreference.captureMode("__PCC_CONTROL_ERROR__","on").equals("__PCC_CONTROL_ERROR__"));
        String ui=FarmResetSourceTest.source("MainActivity"),monitor=FarmResetSourceTest.source("CollectionMonitorService");
        String toggle=ui.substring(ui.indexOf("private void toggleArmed("),ui.indexOf("private void toggleNearbyKinds("));
        check(toggle.contains("chooseNearby(this,enabled)"));check(!toggle.contains("requireReady")&&!toggle.contains("safeEngine"));
        check(monitor.contains("pendingOff(c,\"nearby\")")&&monitor.contains("recordNearby(NearbyGateway.reconcile(true))"));
        check(monitor.contains("synchronized(AutomationPreferences.class)")&&monitor.contains("NearbyWorkState.pending(this)"));
        check(monitor.contains("rebindPid=pid;record(")&&monitor.contains("settingsPid=observation.pid;"));
        String prefs=FarmResetSourceTest.source("AutomationPreferences");
        check(prefs.contains("choose(c,\"nearby\",enabled?\"on\":\"off\""));
        check(!toggle.contains("setArmed("));
        check(FarmResetSourceTest.source("NearbyWorkState").contains("NearbyPreference.captureMode(actual,"));
        realShell();
        System.out.println("PASS NearbyPreference: "+checks+" intent/waiting/STOP/ownership/permissions checks; NOT device acceptance");
    }
    static void realShell()throws Exception {
        String sh=ReadinessGatewayTest.findSh();
        if(sh==null){if("true".equalsIgnoreCase(System.getenv("CI")))throw new AssertionError("shell required");System.out.println("SKIP nearby control shell fixture");return;}
        Path dir=Files.createTempDirectory("pcc-nearby-");
        try {
            Files.write(dir.resolve("pikmin-dispatch-mode.txt"),"off\n".getBytes(StandardCharsets.UTF_8));
            Files.write(dir.resolve("pikmin-dispatch-kinds.txt"),"all\n".getBytes(StandardCharsets.UTF_8));
            check(run(sh,dir,true).ok);check(read(dir,"pikmin-dispatch-mode.txt").equals("armed\n"));
            Files.write(dir.resolve("pikmin-dispatch-target.txt"),"task\n".getBytes(StandardCharsets.UTF_8));
            check(!run(sh,dir,false).ok);check(read(dir,"pikmin-dispatch-mode.txt").equals("armed\n"));
            Files.delete(dir.resolve("pikmin-dispatch-target.txt"));
            Files.write(dir.resolve("pikmin-dispatch-ready.tsv"),"task\n".getBytes(StandardCharsets.UTF_8));
            check(!run(sh,dir,false).ok);Files.delete(dir.resolve("pikmin-dispatch-ready.tsv"));
            Files.write(dir.resolve("pikmin-dispatch-kinds.txt"),"farm\n".getBytes(StandardCharsets.UTF_8));
            check(!run(sh,dir,true).ok);
            Files.write(dir.resolve("pikmin-dispatch-kinds.txt"),"all\n".getBytes(StandardCharsets.UTF_8));
            check(run(sh,dir,false).ok);check(read(dir,"pikmin-dispatch-mode.txt").equals("off\n"));
            Files.write(dir.resolve("pikmin-dispatch-kinds.txt"),"fruit\n".getBytes(StandardCharsets.UTF_8));
            check(run(sh,dir,true).ok);check(read(dir,"pikmin-dispatch-kinds.txt").equals("fruit\n"));
            Files.write(dir.resolve("pikmin-dispatch-mode.txt"),"batch\n".getBytes(StandardCharsets.UTF_8));
            check(!run(sh,dir,true).ok);check(read(dir,"pikmin-dispatch-mode.txt").equals("batch\n"));
            Files.delete(dir.resolve("pikmin-dispatch-mode.txt"));
            check(run(sh,dir,false).ok);check(read(dir,"pikmin-dispatch-mode.txt").equals("off\n"));
            System.out.println("PASS real shell nearby control: guards for target/ready/farm/batch, arm and explicit OFF");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
    }
    static String read(Path dir,String file)throws Exception{return new String(Files.readAllBytes(dir.resolve(file)),StandardCharsets.UTF_8);}
    static BoundedCommand.Result run(String sh,Path dir,boolean on)throws Exception {
        String command=NearbyGateway.controlCommand(on).replace("/data/local/tmp",dir.toAbsolutePath().toString().replace('\\','/'));
        Path script=dir.resolve("control.sh");Files.write(script,command.getBytes(StandardCharsets.UTF_8));
        ProcessBuilder p=new ProcessBuilder(sh,script.toString().replace('\\','/'));p.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        return BoundedCommand.run(p,5000,4096);
    }
}

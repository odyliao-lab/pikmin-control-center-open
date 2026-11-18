package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public final class ReadOnlyQueryIntegrationTest {
    private static void check(boolean value){if(!value)throw new AssertionError();}
    private static String source(String file)throws Exception {
        Path src=Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent().resolve("src/dev/ody/pikmincontrol");
        return new String(Files.readAllBytes(src.resolve(file)),StandardCharsets.UTF_8);
    }
    public static void main(String[] args)throws Exception {
        ReturnRewardModuleGateway.Display r=ReturnRewardModuleGateway.display(new String[]{"all\n","157.0\tcompatible\tx\tx", "discard\n","", "old trace\n", "1\tbatch-confirmed\tx\t2\t0\tkeep"});
        check(r.mode.equals("all")&&r.compatible&&r.discard&&r.history.equals("old trace"));
        check(r.status.equals(ReturnRewardModuleGateway.formatStatus("1\tbatch-confirmed\tx\t2\t0\tkeep")));
        check(ReturnRewardModuleGateway.display(new String[]{"off","", "keep","bad history", "trace",""}).history.equals("尚無含獎勵內容的已確認收取紀錄。"));
        NectarModuleGateway.Display n=NectarModuleGateway.display(new String[]{"---MODULE---\npresent\n---MODE---\nauto\n---STATUS---\n1\tx\t0\tx\t1\tx\tx\tx\t1\tSUCCESS:NONE\n",""});
        check(n.snapshot.modulePresent&&n.snapshot.automatic()&&n.history.isEmpty());
        for(String command:new String[]{ReturnRewardModuleGateway.displayCommand(),NectarModuleGateway.displayCommand()})
            check(!command.contains(" > ")&&!command.contains("chmod")&&!command.contains(" mv ")&&!command.contains("am "));
        String monitor=source("CollectionMonitorService.java");
        check(monitor.contains("signal.wait(delay)")&&monitor.contains("cadence.next(")&&monitor.contains("CoreUpdater.requireFiles(this,pair)")&&monitor.contains("SettingsRestoreJob.restoreForPid(this,pid)"));
        check(monitor.contains("if(ReadOnlyQueryPolicy.enabled(this))")&&!monitor.contains("DISPLAY.read"));
        check(!source("CoreUpdater.java").contains("ReadOnlyQueryPolicy"));
        check(!source("GamePidRecoveryService.java").contains("ReadOnlyQueryPolicy"));
        check(!source("FlowerFarmService.java").contains("ReadOnlyQueryPolicy"));
        check(!source("DispatchBatchService.java").contains("ReadOnlyQueryPolicy"));
        check(source("MainActivity.java").contains("唯讀查詢：統計與回退"));
        check(source("DiagnosticsExporter.java").contains("RootQueryStats.summary()"));
        RootQueryStats.completed(10000000,true);RootQueryStats.completed(20000000,false);
        String stats=RootQueryStats.summary();check(stats.contains("root：2 次")&&stats.contains("未完成 1 次")&&stats.contains("平均 15 ms"));
        check(stats.contains("p50 10 ms")&&stats.contains("p95 20 ms"));
        for(int i=0;i<200;i++)RootQueryStats.completed(30000000,true);
        check(RootQueryStats.summary().contains("最近 128 次 p50 30 ms · p95 30 ms"));
        realShell();
        System.out.println("PASS ReadOnlyQueryIntegration: UI-only bounded reads, formatter parity, fresh guard exclusions, rollback and private counters");
    }
    private static void realShell()throws Exception {
        String sh=ReadinessGatewayTest.findSh();
        if(sh==null) {
            if("true".equalsIgnoreCase(System.getenv("CI")))throw new AssertionError("POSIX shell required in CI");
            System.out.println("SKIP readonly query shell fixture: POSIX shell unavailable");return;
        }
        Path dir=Files.createTempDirectory("pcc readonly ~");
        String base="."; // Relative fixture paths; the host path is passed only as ProcessBuilder cwd.
        try {
            Files.createDirectories(dir.resolve("owner"));
            for(String[] process:new String[][]{{"123","10276"},{"456","1010276"}}){
                String u=process[1];
                ReadinessGatewayTest.write(dir.resolve("proc/"+process[0]+"/status"),"Uid:\t"+u+"\t"+u+"\t"+u+"\t"+u+"\n");
                ReadinessGatewayTest.write(dir.resolve("proc/"+process[0]+"/cmdline"),"com.nianticlabs.pikmin\u0000");
            }
            ReadinessGatewayTest.write(dir.resolve("mod/module.prop"),"id=pikmin_nectar_rpc\n");
            ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-return-mode.txt"),"all\n");
            ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-return-postcard-policy.txt"),"discard\n");
            ReadinessGatewayTest.write(dir.resolve("mod/nectar_control_mode.txt"),"auto\n");
            ReadinessGatewayTest.write(dir.resolve("game/nectar_rpc_mode.txt"),"auto\n");
            ReadinessGatewayTest.write(dir.resolve("game/compatibility_status.tsv"),"157.0\tcompatible\tx\tx\n");
            ReadinessGatewayTest.write(dir.resolve("game/core_runtime.tsv"),"v1\t100000\t123\t1.5.31\t99\t157.0\n");
            ReadinessGatewayTest.write(dir.resolve("game/unified_runtime.tsv"),"v1\t100000\t123\t1.5.31\t99\t1\n");
            ReadinessGatewayTest.write(dir.resolve("mod/return_rpc_trace.tsv"),"fallback\n");
            CorePair pair=new CorePair("schema=2\nversion=1.5.31\ncode=99\napp=131\nsize=100\nsha256="+new String(new char[64]).replace('\0','a')+"\n");
            check(CollectionObservation.parse(shell(sh,dir,base,CollectionObservation.command(),"pidof(){ echo '456 123'; }"),pair,100001).pid.equals("123"));
            ReturnRewardModuleGateway.Display display=ReturnRewardModuleGateway.display(ProbeSnapshot.checkedSections(
                shell(sh,dir,base,ReturnRewardModuleGateway.displayCommand(),""),6));
            check(display.compatible&&display.mode.equals("all")&&display.discard&&display.history.equals("fallback"));
            NectarModuleGateway.Display nectar=NectarModuleGateway.display(ProbeSnapshot.checkedSections(
                shell(sh,dir,base,NectarModuleGateway.displayCommand(),""),2));
            check(nectar.snapshot.modulePresent&&nectar.snapshot.automatic()&&nectar.history.isEmpty());
            try {
                CollectionObservation.parse(shell(sh,dir,base,CollectionObservation.command(),"pidof(){ echo 123; }; cat(){ return 1; }"),pair,100001);
                throw new AssertionError("control read failure accepted");
            } catch(IllegalStateException expected){}
            System.out.println("PASS readonly query shell fixture: collection/return/nectar aggregates and failed control read via "+sh+"; host fixture, not Android");
        } finally {ReadinessGatewayTest.deleteTree(dir);}
    }
    private static BoundedCommand.Result shell(String sh,Path dir,String base,String command,String prelude)throws Exception {
        command=command.replace("/data/adb/modules/pikmin_nectar_rpc",base+"/mod")
            .replace("/data/user/0/com.nianticlabs.pikmin/files",base+"/game").replace("/data/local/tmp",base+"/tmp")
            .replace("/data/user/0/com.nianticlabs.pikmin",base+"/owner").replace("/proc/",base+"/proc/");
        String ownerStat="stat() { if test \"$2\" = '%u'; then echo 10276; else command stat \"$@\"; fi; }; ";
        Path script=dir.resolve("query.sh");Files.write(script,(ownerStat+prelude+"\n"+command).getBytes(StandardCharsets.UTF_8));
        ProcessBuilder pb=new ProcessBuilder(sh,script.getFileName().toString()).directory(dir.toFile());
        pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        return BoundedCommand.run(pb,5000,262144);
    }
}

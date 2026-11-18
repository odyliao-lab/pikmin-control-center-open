package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Actual shell execution; no root, no process control or production data. */
public final class OwnerGameProcessTest {
    static int checks;
    static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
    static void write(Path p,String value)throws Exception{Files.createDirectories(p.getParent());Files.write(p,value.getBytes(StandardCharsets.UTF_8));}
    static String run(String sh,Map<String,String> env,Path dir,String prelude,boolean absence)throws Exception{
        // Host temp roots may contain spaces, non-ASCII names or Windows 8.3 '~'.
        // They are ProcessBuilder's cwd, never unquoted production-shell path input.
        String command=OwnerGameProcess.command("owner","proc",absence);
        Path script=dir.resolve("run.sh");
        write(script,"stat() { if test \"$2\" = '%u'; then echo 10276; else command stat \"$@\"; fi; }; "+prelude+"\n"+command);
        ProcessBuilder pb=new ProcessBuilder(sh,script.getFileName().toString()).directory(dir.toFile());pb.environment().putAll(env);
        BoundedCommand.Result r=BoundedCommand.run(pb,5000,8192);
        check(r.ok&&!r.truncated&&!r.timedOut,"bounded shell completion");return r.text.trim();
    }
    static void expect(String sh,Map<String,String> env,Path d,String prelude,String wanted)throws Exception{
        String got=run(sh,env,d,prelude,false);check(wanted.equals(got),"result: expected "+wanted+" got "+got);
    }
    public static void main(String[] args)throws Exception{
        String sh=ReadinessGatewayTest.findSh();
        if(sh==null){if(ReadinessGatewayTest.CI)throw new AssertionError("real owner PID shell required");System.out.println("SKIP owner PID shell: unavailable");return;}
        Map<String,String> env=ReadinessGatewayTest.toolEnv(sh);Path dir=Files.createTempDirectory("pcc owner ~");
        try {
            check(dir.getFileName().toString().contains(" ")&&dir.getFileName().toString().contains("~"),"host path counterexample exercised");
            Files.createDirectories(dir.resolve("owner"));
            for(String[] row:new String[][]{{"11","10276"},{"22","1010276"},{"33","10276"}}){
                write(dir.resolve("proc/"+row[0]+"/status"),"Name:\tpikmin\nUid:\t"+row[1]+"\t"+row[1]+"\t"+row[1]+"\t"+row[1]+"\n");
                write(dir.resolve("proc/"+row[0]+"/cmdline"),OwnerGameProcess.GAME+"\u0000");
            }
            expect(sh,env,dir,"pidof() { echo '22 11'; };","11");
            expect(sh,env,dir,"pidof() { echo '11 22'; };","11");
            expect(sh,env,dir,"pidof() { echo 11; };","11");
            expect(sh,env,dir,"pidof() { echo 22; };",OwnerGameProcess.OTHER);
            expect(sh,env,dir,"pidof() { echo '11 33 22'; };",OwnerGameProcess.AMBIGUOUS);
            expect(sh,env,dir,"pidof() { return 1; };",OwnerGameProcess.ABSENT);
            expect(sh,env,dir,"pidof() { return 2; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { return 0; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; return 1; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo '11 11'; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo invalid; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 0; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 4194305; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 99; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; }; stat() { echo 10276; return 1; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; }; stat() { echo invalid; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; }; stat() { echo 0; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; }; sed() { return 1; };",OwnerGameProcess.ERROR);
            expect(sh,env,dir,"pidof() { echo 11; }; tr() { return 1; };",OwnerGameProcess.ERROR);
            write(dir.resolve("proc/11/cmdline"),OwnerGameProcess.GAME+":backgroundplanting\u0000");
            expect(sh,env,dir,"pidof() { echo 11; };",OwnerGameProcess.ERROR);
            write(dir.resolve("proc/11/cmdline"),OwnerGameProcess.GAME+"\u0000");
            write(dir.resolve("proc/11/status"),"Uid:\t10276\t10276\n");
            expect(sh,env,dir,"pidof() { echo 11; };",OwnerGameProcess.ERROR);
            check(run(sh,env,dir,"pidof() { return 1; };",true).isEmpty(),"opt-in watchdog absence only");
            check(run(sh,env,dir,"pidof() { echo 22; };",true).isEmpty(),"other user cannot block main watchdog");
            check(OwnerGameProcess.ERROR.equals(run(sh,env,dir,"pidof() { return 2; };",true)),"unknown is never absence");
            check(OwnerGameProcess.reason("11 22").contains("不唯一"),"legacy multi-PID classified");
            check(OwnerGameProcess.reason(OwnerGameProcess.OTHER).contains("其他使用者"),"foreign-space reason");
            String cmd=OwnerGameProcess.command();
            for(String unsafe:new String[]{"bad path","bad;path"}){
                boolean denied=false;try{OwnerGameProcess.command(unsafe,"proc",false);}catch(IllegalArgumentException expected){denied=true;}
                check(denied,"production path guard unchanged");
            }
            for(String forbidden:new String[]{"kill ","am ","chmod ","rm ","mv ","/proc/[","| head","| tr"," > /data"})check(!cmd.contains(forbidden),"no control/unbounded scan: "+forbidden);
            for(String file:new String[]{"GpsModuleGateway","SessionIdentity","PlantingControlGateway","FeedingGateway","PlanterGateway","NearbyGateway","CoreUpdater","DispatchBatchService","DispatchProbeGateway","ResourceGuard","CollectionObservation","BatchRecoveryGateway"})
                check(FarmResetSourceTest.source(file).contains("OwnerGameProcess.command()"),"owner PID wired: "+file);
            check(FarmResetSourceTest.source("CoreFreshInstall").contains("$(pidof com.nianticlabs.pikmin)"),"global install exclusion retained");
            System.out.println("PASS OwnerGameProcess: "+checks+" actual-shell/source checks; primary UID only, not dual-game support or device acceptance");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
    }
}

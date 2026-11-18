package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.util.Arrays;

/** Executes the complete production archive shell; all paths and process actions are synthetic. */
public final class PlanterArchiveShellTest {
    static final long NOW=2000000;
    static final String TARGET="/data/user/0/com.nianticlabs.pikmin/files/planter_pending.tsv.manual-retired-11111111-1111-1111-1111-111111111111";
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static void write(Path dir,String name,String data)throws Exception{ReadinessGatewayTest.write(dir.resolve(name),data);}
    static String[] rows(){return PlanterRetirementTest.rows(NOW);}
    static void seed(Path d)throws Exception {
        String[] r=rows();String[] caps=new String[23];Arrays.fill(caps,"0");
        caps[0]="v1";caps[1]=""+NOW;caps[2]="42";caps[14]="2";caps[15]="ready";caps[16]="1990000";caps[22]="end";
        write(d,"game/core_capabilities.tsv",String.join("\t",caps)+"\n");
        write(d,"game/planter_status.tsv",r[3]);write(d,"game/planter_inventory.tsv",r[4]);
        write(d,"game/planter_pending.tsv",r[6]);write(d,"game/planter_recovery.tsv",r[5]);
        write(d,"game/automation_session.tsv",r[8]);write(d,"tmp/pikmin-planter-recover.txt",r[7]);
        write(d,"boot",r[2]+"\n");write(d,"absentpid","");
    }
    static BoundedCommand.Result run(Path d,String sh,String afterStop,boolean oldHeader)throws Exception {
        PlanterRetirement.Proof p=new PlanterRetirement.Proof(rows(),NOW);
        String cmd=PlanterRetirementGateway.archiveCommand(p,new String[]{"42",p.boot,"100","2","1990000"},TARGET)
            .replace(p.guard(),"test \"$(cat boot)\" = "+p.boot+"; ")
            .replace(OwnerGameProcess.watchdogCommand(),"cat absentpid")
            .replace("/data/user/0/com.nianticlabs.pikmin/files/","game/").replace("/data/local/tmp/","tmp/")
            .replace("/proc/sys/kernel/random/boot_id","boot");
        if(oldHeader)cmd=cmd.replace("END{exit !ok}' game/planter_inventory.tsv", "END{exit !(NR==1 && ok)}' game/planter_inventory.tsv");
        cmd="date(){ echo "+NOW+"; }; sync(){ :; }; am(){ printf stop > stopped; "+afterStop+"; }; "+cmd;
        write(d,"run.sh",cmd);
        ProcessBuilder pb=new ProcessBuilder(sh,"run.sh").directory(d.toFile());pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        return BoundedCommand.run(pb,5000,4096);
    }
    interface Mutation {void apply(Path d)throws Exception;}
    static void scenario(String sh,String why,Mutation change,String afterStop,boolean oldHeader,boolean allowed,boolean stopped)throws Exception {
        Path d=Files.createTempDirectory("pcc-full-archive-");
        try {
            seed(d);change.apply(d);
            Path archived=d.resolve("game/"+TARGET.substring(TARGET.lastIndexOf('/')+1));
            byte[] existing=Files.exists(archived)?Files.readAllBytes(archived):null;
            BoundedCommand.Result result=run(d,sh,afterStop,oldHeader);
            check(result.ok==allowed,why+" exit/output "+result.text);
            check(Files.exists(d.resolve("stopped"))==stopped,why+" stop boundary");
            check(Files.exists(archived)==(allowed||existing!=null),why+" archive boundary");
            if(existing!=null)check(Arrays.equals(existing,Files.readAllBytes(archived)),why+" prior archive bytes retained");
            if(allowed) {
                check(new String(Files.readAllBytes(archived),java.nio.charset.StandardCharsets.UTF_8).equals(rows()[6]),why+" original bytes preserved");
                check(result.text.equals("GAME_STOPPEDRETIRED_UNKNOWN"),why+" exact completion");
            }else check(Files.exists(d.resolve("game/planter_pending.tsv")),why+" pending retained");
        } finally {ReadinessGatewayTest.deleteTree(d);}
    }
    public static void main(String[] args)throws Exception {
        String sh=ReadinessGatewayTest.findSh();if(sh==null){ReadinessGatewayTest.skip("full archive requires POSIX shell");return;}
        scenario(sh,"old single-row END rejects valid multirow inventory",d->{},":",true,false,false);
        scenario(sh,"valid full production command",d->{},":",false,true,true);
        scenario(sh,"progress changes before proof boundary",d->write(d,"game/planter_inventory.tsv",rows()[4].replace("new-seed\t1\t1000","new-seed\t99\t1000")),":",false,true,true);
        scenario(sh,"progress changes during stop",d->{},"sed 's/new-seed\t1\t1000/new-seed\t99\t1000/' game/planter_inventory.tsv > next; mv next game/planter_inventory.tsv",false,true,true);
        scenario(sh,"changed slot identity",d->write(d,"game/planter_inventory.tsv",rows()[4].replace("new-seed","other-seed")),":",false,false,false);
        scenario(sh,"stale inventory",d->write(d,"game/planter_inventory.tsv",rows()[4].replace("v1\t2000000","v1\t1900000")),":",false,false,false);
        scenario(sh,"changed pending before stop",d->write(d,"game/planter_pending.tsv","1001\tplant\told-seed\t1\n"),":",false,false,false);
        scenario(sh,"changed pending during stop",d->{},"printf 'changed' > game/planter_pending.tsv",false,false,true);
        scenario(sh,"stale ACK",d->write(d,"game/planter_recovery.tsv",rows()[5].replace("2000000","1900000")),":",false,false,false);
        scenario(sh,"changed session",d->write(d,"game/automation_session.tsv",rows()[8].replace(PlanterRetirementTest.repeat('a',64),PlanterRetirementTest.repeat('b',64))),":",false,false,false);
        scenario(sh,"occupied archive never replaced",d->write(d,"game/"+TARGET.substring(TARGET.lastIndexOf('/')+1),"existing"),":",false,false,false);
        check(PlanterRetirementGateway.archiveFailureStage("GAME_STOPPED\nPCC_RETIRE_FAILED:post-stop\n").equals("post-stop"),"bounded failure code parsed");
        check(PlanterRetirementGateway.archiveFailureStage("\nPCC_RETIRE_FAILED:private-data\n").isEmpty(),"unrecognized detail not audited");
        String gateway=FarmResetSourceTest.source("PlanterRetirementGateway");
        check(gateway.contains("archived.text.startsWith(\"GAME_STOPPED\")")&&gateway.contains("try { launch(); }"),"post-stop failure reopens only the authorized game without repeating archive");
        System.out.println("PASS full production archive: "+checks+" HOST checks, synthetic files/process only; not Android recovery acceptance");
    }
}

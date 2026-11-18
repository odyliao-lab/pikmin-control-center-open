package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/** Real shell fixture touches only synthetic files; not device recovery acceptance. */
public final class PlanterRecoveryRequestTest {
    static int checks;static final long NOW=2000000,LOGIN=1000000,EPOCH=3;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static PlanterRetirement.Proof proof()throws Exception{return new PlanterRetirement.Proof(PlanterRetirementTest.rows(NOW),NOW);}
    interface Action{void run()throws Exception;}
    static void rejects(Action action)throws Exception{
        try{action.run();throw new AssertionError("unsafe request accepted");}
        catch(IllegalArgumentException|IllegalStateException expected){checks++;}
    }
    public static void main(String[] args)throws Exception{
        PlanterRetirement.Proof basis=proof();
        String first=PlanterRecoveryRequest.command(basis,"fresh-1",NOW,EPOCH,LOGIN);
        String second=PlanterRecoveryRequest.command(basis,"fresh-2",NOW,EPOCH,LOGIN);
        check(!first.equals(second),"new diagnostic nonce for every explicit renewal");
        check(first.contains("v1\tfresh-1\t42\t2000000\t1000\tplant\told-seed\t1\n"),"request carries original pending fields only");
        check(first.split(java.util.regex.Pattern.quote(basis.sha),-1).length==4,"original SHA checked before write, before publish, after publish");
        check(first.contains("now-$2<=12000")&&first.contains("-le 90000"),"freshness deadlines retained");
        check(FarmResetSourceTest.source("PlanterGateway").contains(
            "BoundedCommand.root(PlanterRecoveryRequest.command(expected,nonce,now,epoch,login),6,4096)"),
            "Android bound request retains its 6-second root budget independently of the HOST fixture");
        check(first.contains("$15==3")&&first.contains("$17==1000000"),"epoch/login bound");
        for(String forbidden:new String[]{"am force-stop","planter_pending.tsv' '","planter-mode","sendBroadcast","START","STOP"})
            check(!first.contains(forbidden),"no business replay or pending mutation: "+forbidden);
        rejects(()->PlanterRecoveryRequest.command(basis,"bad';id",NOW,EPOCH,LOGIN));
        rejects(()->PlanterRecoveryRequest.command(basis,"fresh",0,EPOCH,LOGIN));
        rejects(()->PlanterRecoveryRequest.command(basis,"fresh",NOW,0,LOGIN));
        rejects(()->PlanterRecoveryRequest.command(basis,"fresh",NOW,EPOCH,NOW+1));
        check(PlanterRecoveryRequest.accepted(new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED,true,false,false,0)),"exact complete marker");
        for(BoundedCommand.Result r:new BoundedCommand.Result[]{null,
                new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED,false,false,false,1),
                new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED,true,true,false,0),
                new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED,true,false,true,0),
                new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED,true,false,false),
                new BoundedCommand.Result(PlanterRecoveryRequest.ACCEPTED+"\nextra",true,false,false,0)})
            check(!PlanterRecoveryRequest.accepted(r),"partial, failed or ambiguous result rejected");
        realShell();
        System.out.println("PASS PlanterRecoveryRequest: "+checks+" HOST checks; no Android pending mutation or gameplay acceptance");
    }
    static void seed(Path dir)throws Exception{
        String[] r=PlanterRetirementTest.rows(NOW);Files.createDirectories(dir.resolve("owner"));
        ReadinessGatewayTest.write(dir.resolve("proc/42/status"),"Uid:\t10276\t10276\t10276\t10276\n");
        ReadinessGatewayTest.write(dir.resolve("proc/42/cmdline"),"com.nianticlabs.pikmin\u0000");
        String[] stat=new String[22];Arrays.fill(stat,"0");stat[21]="100";
        ReadinessGatewayTest.write(dir.resolve("proc/42/stat"),String.join(" ",stat));
        ReadinessGatewayTest.write(dir.resolve("proc/sys/kernel/random/boot_id"),r[2]+"\n");
        ReadinessGatewayTest.write(dir.resolve("game/automation_session.tsv"),r[8]);
        ReadinessGatewayTest.write(dir.resolve("game/planter_pending.tsv"),r[6]);
        String[] caps=new String[23];Arrays.fill(caps,"0");caps[0]="v1";caps[1]=""+NOW;caps[2]="42";
        caps[14]=""+EPOCH;caps[15]="ready";caps[16]=""+LOGIN;caps[22]="end";
        ReadinessGatewayTest.write(dir.resolve("game/core_capabilities.tsv"),String.join("\t",caps)+"\n");
        ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"old diagnostic request\n");
        Files.deleteIfExists(dir.resolve("race.phase"));
    }
    static BoundedCommand.Result shell(String sh,Path dir,String extra)throws Exception{
        String command=PlanterRecoveryRequest.command(proof(),"fixture-new",NOW,EPOCH,LOGIN)
            .replace("/data/user/0/com.nianticlabs.pikmin/files/","game/")
            .replace("/data/user/0/com.nianticlabs.pikmin","owner").replace("/data/local/tmp/","tmp/").replace("/proc/","proc/");
        String pre="pidof(){ echo 42; }; stat(){ if test \"$2\" = '%u'; then echo 10276; else command stat \"$@\"; fi; }; "
            +"date(){ echo 2000000; }; dumpsys(){ echo 'versionName=157.0'; }; ";
        ReadinessGatewayTest.write(dir.resolve("run.sh"),pre+extra+"\n"+command);
        ProcessBuilder pb=new ProcessBuilder(sh,"run.sh").directory(dir.toFile());pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        // HOST semantic fixture, like ReadinessGatewayTest: Windows/MSYS process startup
        // under concurrent build load can exceed 6 s. This is not the Android root budget;
        // the source assertion above and the real shell 12 s / 90 s guards remain unchanged.
        return BoundedCommand.run(pb,20_000,4096);
    }
    static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    static void rejectedShell(BoundedCommand.Result result,String why){
        check(!result.timedOut&&!result.truncated&&result.exitCode!=null&&result.exitCode==1,
            why+" did not finish its guard rejection: "+result.failureSummary()+"; output="+result.text);
        check(!PlanterRecoveryRequest.accepted(result),why);
    }
    static void realShell()throws Exception{
        String sh=ReadinessGatewayTest.findSh();if(sh==null){ReadinessGatewayTest.skip("planter recovery request needs POSIX shell");return;}
        System.out.println("Planter recovery request shell: "+sh);
        Path dir=Files.createTempDirectory("pcc-recovery-request-");
        try{
            seed(dir);BoundedCommand.Result result=shell(sh,dir,"");
            check(PlanterRecoveryRequest.accepted(result),"valid request shell accepted: "+result.failureSummary()+" "+result.text);
            check(read(dir.resolve("game/planter_pending.tsv")).equals(proof().ledger),"original pending unchanged");
            check(read(dir.resolve("tmp/pikmin-planter-recover.txt")).equals(PlanterRecovery.request("fixture-new","42",NOW,proof().ledger)),"atomic diagnostic request content");
            String[] mutations={
                "printf '1000\\tplant\\tother-seed\\t1\\n' > game/planter_pending.tsv; ",
                "sed 's/2000000/1987999/' game/automation_session.tsv > changed; mv changed game/automation_session.tsv; ",
                "sed 's/aaaaaaaa/bbbbbbbb/' game/automation_session.tsv > changed; mv changed game/automation_session.tsv; ",
                "sed 's/ready/failed/' game/core_capabilities.tsv > changed; mv changed game/core_capabilities.tsv; ",
                "sed 's/1000000/1000001/' game/core_capabilities.tsv > changed; mv changed game/core_capabilities.tsv; ",
                "dumpsys(){ echo 'versionName=155.0'; }; ",
                "dumpsys(){ echo 'versionName=157.0'; return 1; }; ",
                "pidof(){ echo 43; }; ",
                "date(){ echo 2090001; }; "
            };
            for(String mutation:mutations){
                seed(dir);rejectedShell(shell(sh,dir,mutation),"changed identity/state rejected: "+mutation);
                check(read(dir.resolve("tmp/pikmin-planter-recover.txt")).equals("old diagnostic request\n"),"pre-write failure preserves prior request");
            }
            // The original can change during publication: no success marker even though
            // the read-only request was published. Caller must not retry or retarget it.
            seed(dir);result=shell(sh,dir,"mv(){ printf entered > race.phase; command mv \"$@\"; printf '1000\\tplant\\tchanged-after\\t1\\n' > game/planter_pending.tsv; printf mutated > race.phase; }; ");
            rejectedShell(result,"post-publish ledger race fails closed (phase="+(Files.exists(dir.resolve("race.phase"))?read(dir.resolve("race.phase")):"before-hook")+")");
            check(Files.exists(dir.resolve("race.phase"))&&read(dir.resolve("race.phase")).equals("mutated"),"race hook not completed: "+result.failureSummary()+"; output="+result.text);
            check(read(dir.resolve("game/planter_pending.tsv")).contains("changed-after"),"race original is not overwritten: "+result.failureSummary());
            seed(dir);result=shell(sh,dir,"mv(){ printf entered > race.phase; command mv \"$@\"; sed 's/ready/failed/' game/core_capabilities.tsv > changed; command mv changed game/core_capabilities.tsv; printf mutated > race.phase; }; ");
            rejectedShell(result,"post-publish session race fails closed (phase="+(Files.exists(dir.resolve("race.phase"))?read(dir.resolve("race.phase")):"before-hook")+")");
            check(Files.exists(dir.resolve("race.phase"))&&read(dir.resolve("race.phase")).equals("mutated"),"session race hook not completed: "+result.failureSummary()+"; output="+result.text);
            System.out.println("PASS real recovery request shell: 12 cases; synthetic files only");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
    }
}

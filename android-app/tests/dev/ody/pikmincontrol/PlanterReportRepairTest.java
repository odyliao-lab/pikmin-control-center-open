package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class PlanterReportRepairTest {
    static int checks;static final long NOW=1800000000000L;
    static final String BOOT="11111111-1111-1111-1111-111111111111",ACCOUNT=repeat('a',64);
    static String golden;static CorePair pair;
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static String repeat(char c,int n){char[] a=new char[n];Arrays.fill(a,c);return new String(a);}
    static String[] rows(long now,long at,String pid)throws Exception{
        String[] cap=golden.trim().split("\t",-1);cap[1]=""+now;cap[2]=pid;
        return new String[]{pid,BOOT,"100","auto\n","v1\t"+at+"\t"+pid+"\twaiting-growth-or-seed\tauto\t\n",
            "v1\t"+at+"\t"+pid+"\t826\t1350\nsteps\t14\nslot\t0\t0\t0\tseed1\t919\t1000\tgrowing\tseed:1\nslot\t1\t0\t0\tseed2\t0\t5000\tgrowing\tseed:2\nseed\tseed1\t100\t1000\t0\t0\tseed:1\t1\nseed\tseed2\t100\t5000\t0\t0\tseed:2\t1\nend\t2\n",
            "v1\t"+at+"\t"+pid+"\told-request\texpired-or-game-restarted\n",PlanterReportRepair.MISSING,
            "v1\t"+now+"\t"+pid+"\t1.5.23\t91\t157.0\n",String.join("\t",cap)+"\n",
            "v1\t"+now+"\t"+pid+"\t"+ACCOUNT+"\tready\tbusy\tend\n","100",BOOT,pid};
    }
    static String[] framed(String[] r){String[] f=new String[15];f[0]=r[9];System.arraycopy(r,0,f,1,14);return f;}
    static PlanterReportRepair.Sample sample(String[] r,long now){return new PlanterReportRepair.Sample(r.length==14?framed(r):r,pair,now);}
    interface Change{void run(String[] r);}
    static void rejected(Change c)throws Exception{
        String[] r=rows(NOW,NOW,"42");c.run(r);try{sample(r,NOW);throw new AssertionError("bad read accepted");}catch(IllegalStateException ok){checks++;}
    }
    static final class Port implements PlanterReportRepair.Port {
        long elapsed;int reads,restarts,opens,pauses,resumes;String fail="",mode="healthy",pid="42",afterPause="";
        List<String> events=new ArrayList<>();String[] special;
        void event(String s)throws Exception{events.add(s);if(s.equals(fail))throw new java.io.IOException(s);}
        public long elapsed(){return elapsed;}
        public void guard()throws Exception{event("guard");}
        public void openGame()throws Exception{event("open");opens++;}
        public void waitForUpdate()throws Exception{event("wait");elapsed+=4000;}
        public void audit(String code)throws Exception{event("audit");}
        public void pause()throws Exception{event("pause");pauses++;if(!afterPause.isEmpty())mode=afterPause;}
        public void resume()throws Exception{resumes++;event("resume");}
        public void restart(PlanterReportRepair.Sample s)throws Exception{event("restart");restarts++;pid="43";mode="healthy";}
        public PlanterReportRepair.Sample read()throws Exception{
            event("read");reads++;long now=NOW+elapsed;
            String[] r=rows(now,mode.equals("frozen")?NOW-120000:now,pid);
            if(mode.equals("wrong-pid")){r[4]=r[4].replace("\t42\t","\t27477\t");r[5]=r[5].replace("\t42\t","\t27477\t");}
            if(mode.equals("changed-pid")&&reads>1)r=rows(now,now,"44");
            if(mode.equals("changed-epoch")&&reads>1)r[9]=r[9].replace("\t1\t1\tready\t","\t2\t2\tready\t");
            if(mode.equals("slow-read"))elapsed+=26000;
            if(mode.equals("constant"))r=rows(now,NOW,"42");
            if(mode.equals("pending"))r[7]="1000\tpluck\tseed\t0\n";
            if(mode.equals("changed-account"))r[10]=r[10].replace(ACCOUNT,repeat('b',64));
            if(special!=null)r=special.clone();
            return sample(r,now);
        }
    }
    static void fails(Port p,boolean restart,PlanterReportRepair.Sample consent)throws Exception{
        try{if(restart)PlanterReportRepair.restart(p,consent);else PlanterReportRepair.refresh(p);throw new AssertionError("unsafe flow accepted");}
        catch(IllegalStateException|java.io.IOException ok){checks++;}
    }
    public static void main(String[] args)throws Exception{
        golden=ReadinessCapabilitiesTest.golden("healthy");pair=new CorePair("schema=2\nversion=1.5.23\ncode=91\napp=156\nsize=1\nsha256="+repeat('a',64)+"\n");
        PlanterReportRepair.Sample good=sample(rows(NOW,NOW,"42"),NOW);
        check(good.healthy&&good.kind==PlanterReportRepair.Kind.CURRENT,"old expired ack is not a current pause");
        check(!good.canRestart(),"healthy sample cannot restart");
        String[] r=rows(NOW,NOW-120000,"42");PlanterReportRepair.Sample stale=sample(r,NOW);
        check(!stale.healthy&&stale.canRestart(),"known current loader, stale report, no ledger");
        r[4]=r[4].replace("\t42\t","\t27477\t");r[5]=r[5].replace("\t42\t","\t27477\t");
        check(sample(r,NOW).kind==PlanterReportRepair.Kind.OLD_PROCESS,"attachment old-PID shape");
        for(String ledger:new String[]{"","1000\tpluck\tseed\t0\n","garbage"}){r=rows(NOW,NOW-120000,"42");r[7]=ledger;check(!sample(r,NOW).canRestart(),"any existing ledger blocks restart");}
        for(String status:new String[]{"plant-sent","pluck-sent","waiting-rpc-no-retry","waiting-inventory-no-retry","restart-pending-review-required","rpc-start-uncertain-review-required"}){
            r=rows(NOW,NOW-120000,"42");r[4]=r[4].replace("waiting-growth-or-seed",status);check(!sample(r,NOW).canRestart(),status);
        }
        r=rows(NOW,NOW,"42");r[4]=r[4].replace("waiting-growth-or-seed","restart-pending-review-required");
        check(sample(r,NOW).kind==PlanterReportRepair.Kind.PAUSED,"real pause uses existing native recovery");
        PlanterReportRepair.Sample paused=sample(r,NOW);
        check(paused.healthy&&paused.paused()&&PlanterReportRepair.readyForManualRecovery(paused),
            "manual recovery adopts one fresh paused snapshot without waiting for timestamps to advance twice");
        check(!PlanterReportRepair.readyForManualRecovery(good),"healthy but unpaused state is not manually retired");
        r=rows(NOW,NOW-120000,"42");r[4]=r[4].replace("waiting-growth-or-seed","restart-pending-review-required");
        check(!PlanterReportRepair.readyForManualRecovery(sample(r,NOW)),"stale paused state still cannot authorize recovery");
        r=rows(NOW,NOW,"42");r[3]="off";r[4]=r[4].replace("\tauto\t","\toff\t");
        check(sample(r,NOW).kind==PlanterReportRepair.Kind.OFF,"desired off not secretly enabled");
        r=rows(NOW,NOW+1,"42");check(!sample(r,NOW).canRestart(),"future data cannot authorize restart");
        r=rows(NOW,NOW-120000,"42");r[9]=PlanterReportRepair.MISSING;
        check(!sample(r,NOW).canRestart()&&sample(r,NOW).kind==PlanterReportRepair.Kind.CORE_UNKNOWN,"missing core is unknown");
        r=rows(NOW,NOW-120000,"42");r[9]=r[9].replace("\tready\t1799999940000","\tlogged_out\t1799999940000");
        check(!sample(r,NOW).canRestart(),"logout not restartable");
        r=rows(NOW,NOW,"42");r[9]=r[9].replace("\tfff\t0\t","\tfff\t1\t");
        check(!sample(r,NOW).healthy&&sample(r,NOW).kind==PlanterReportRepair.Kind.SOURCE_WAIT,"fresh TSV cannot override stale manager after login");
        r=rows(NOW,NOW,"42");r[4]=r[4].replace("waiting-growth-or-seed","storage-full-or-unknown");
        check(sample(r,NOW).message().contains("等待皮克敏空間"),"capacity limit is not hidden by report health");
        r=rows(NOW,NOW-120000,"42");r[10]=r[10].replace("1800000000000","1799999939999");
        check(!sample(r,NOW).canRestart(),"identity before current login cannot authorize restart");
        for(int i=0;i<14;i++){final int n=i;rejected(a->a[n]=PlanterReportRepair.ERROR);}
        rejected(a->a[13]="43");rejected(a->a[12]=BOOT.replace('1','2'));rejected(a->a[11]="101");
        rejected(a->a[7]=repeat('x',513));rejected(a->a[5]=repeat('x',262145));
        String[] mixed=framed(rows(NOW,NOW,"42"));mixed[10]=mixed[10].replace("\t1\t1\tready\t","\t2\t2\tready\t");
        boolean crossRejected=false;try{sample(mixed,NOW);}catch(IllegalStateException e){crossRejected=true;}
        check(crossRejected,"same PID logout/login between capability bookends rejected");
        Port live=new Port();PlanterReportRepair.Result result=PlanterReportRepair.refresh(live);
        check(result.advancing&&!result.offerRestart()&&live.opens==1&&live.reads==3,"two advancing observations");
        Port frozen=new Port();frozen.mode="frozen";result=PlanterReportRepair.refresh(frozen);
        check(!result.advancing&&result.offerRestart()&&frozen.reads==6&&frozen.restarts==0,"bounded wait and separate consent");
        Port oldPid=new Port();oldPid.mode="wrong-pid";result=PlanterReportRepair.refresh(oldPid);
        check(!result.advancing&&result.offerRestart()&&result.sample.kind==PlanterReportRepair.Kind.OLD_PROCESS,"attachment-style old process can be manually repaired");
        Port same=new Port();same.mode="constant";result=PlanterReportRepair.refresh(same);
        check(!result.advancing&&!result.offerRestart(),"unchanged fresh files are not a running producer");
        for(String mode:new String[]{"changed-pid","changed-epoch"}){Port change=new Port();change.mode=mode;fails(change,false,null);check(change.restarts==0,"no cross-session restart");}
        Port slow=new Port();slow.mode="slow-read";result=PlanterReportRepair.refresh(slow);
        check(!result.advancing&&slow.reads==1&&slow.opens==0,"no new observation round after spent read budget");
        Port restarted=new Port();restarted.mode="frozen";result=PlanterReportRepair.restart(restarted,stale);
        check(restarted.restarts==1&&restarted.pauses==1&&restarted.resumes==1&&result.advancing,"restart verified by new reports");
        check(restarted.events.indexOf("audit")<restarted.events.indexOf("restart"),"persist intent before command");
        for(String fail:new String[]{"pause","audit","restart"}){
            Port p=new Port();p.mode="frozen";p.fail=fail;fails(p,true,stale);
            check(p.resumes==1,"pause always released: "+fail);check(p.restarts<=1,"no retry: "+fail);
        }
        Port changed=new Port();changed.mode="healthy";fails(changed,true,stale);check(changed.restarts==0,"repaired since consent -> no restart");
        Port healDuringPause=new Port();healDuringPause.mode="frozen";healDuringPause.afterPause="healthy";
        check(PlanterReportRepair.restart(healDuringPause,stale).advancing&&healDuringPause.restarts==0&&healDuringPause.resumes==1,"pause race heals: do not restart");
        Port pendingDuringPause=new Port();pendingDuringPause.mode="frozen";pendingDuringPause.afterPause="pending";
        check(PlanterReportRepair.restart(pendingDuringPause,stale).sample.kind==PlanterReportRepair.Kind.PAUSED
            &&pendingDuringPause.restarts==0&&pendingDuringPause.resumes==1,"new pending follows existing recovery; no force-stop");
        Port accountDuringPause=new Port();accountDuringPause.mode="frozen";accountDuringPause.afterPause="changed-account";
        fails(accountDuringPause,true,stale);check(accountDuringPause.restarts==0&&accountDuringPause.resumes==1,"post-pause account veto and restored fence");
        Port pending=new Port();pending.special=rows(NOW,NOW-120000,"42");pending.special[7]="1000\tpluck\tseed\t0\n";
        fails(pending,true,stale);check(pending.restarts==0&&pending.pauses==0,"new pending vetoes consent");
        for(String fail:new String[]{"guard","read","open","wait","audit"}){Port p=new Port();p.fail=fail;fails(p,false,null);check(p.restarts==0,"refresh failure never restarts");}
        sourceContracts();realShell();
        System.out.println("PASS PlanterReportRepair: "+checks+" HOST checks; no phone restart or mutation acceptance");
    }
    static void sourceContracts()throws Exception{
        String g=FarmResetSourceTest.source("PlanterReportRepairGateway"),m=FarmResetSourceTest.source("MainActivity");
        String manual=FarmResetSourceTest.source("PlanterManualRecoveryGateway");
        check(g.contains("am force-stop --user 0 ")&&!g.contains("am force-stop com."),"owner user only");
        for(String forbidden:new String[]{".delete(","\"rm ","\"mv ","PlanterGateway.recover(","JoystickAdapter.","sendBroadcast(","choose(c,"})check(!g.contains(forbidden),"no business replay/cleanup: "+forbidden);
        for(String guard:new String[]{"WorkStatus.stoppingAll","CoreUpdater.pending(c)","RouteSession.pending(c)","UpdateInstaller.blocking(c)","DispatchBatchService.isRunning()","FlowerFarmService.isRunning()","CoreUpdater.requireFiles(c,pair)"})check(g.contains(guard),guard);
        check(g.contains("test ! -L "+"\"+FILES+\"planter_pending.tsv")&&g.contains("$15=="),"ledger and epoch in final root command");
        check(g.contains("PlanterSettings.reportRepairPaused=true")&&g.contains("PlanterSettings.reportRepairPaused=false"),"temporary native OFF fence");
        check(!FarmResetSourceTest.source("PlanterSettings").contains("if(reportRepairPaused)return;"),"retired manual flag cannot suspend continuous reconciliation");
        check(!m.contains("confirmPlanterRecovery")&&!m.contains("runPlanterReportRepair"),"continuous UI has no manual restart/recovery entry");
        check(g.contains("--ez open_return true"),"returns to nursery dispatch tab, not expedition batch tab");
        check(!m.contains("PlanterManualRecoveryGateway")&&!m.contains("confirmPlanterRetirement"),"historical ledger helpers have no UI entry");
        check(manual.contains("PlanterReportRepair.readyForManualRecovery(latest)")
            &&!manual.contains("PlanterReportRepairGateway.run(c,null)"),
            "manual recovery no longer requires two advancing foreground reports");
        String command=PlanterReportRepairGateway.readCommand();
        for(String forbidden:new String[]{"am ","chmod ","mv ","rm "," > "})check(!command.contains(forbidden),"diagnostic read only");
        check(DiagnosticsExporter.planterFilesCommand().contains("--- %s ---")&&DiagnosticsExporter.planterFilesCommand().contains("planter_pending.tsv.recovered"),"named archival file evidence");
    }
    static void realShell()throws Exception{
        String sh=ReadinessGatewayTest.findSh();
        if(sh==null){if(ReadinessGatewayTest.CI)throw new AssertionError("planter report shell required");System.out.println("SKIP planter report shell: unavailable");return;}
        Path dir=Files.createTempDirectory("pcc planter ~");
        try{
            String[] r=rows(NOW,NOW,"42");Files.createDirectories(dir.resolve("owner"));
            ReadinessGatewayTest.write(dir.resolve("proc/42/status"),"Uid:\t10276\t10276\t10276\t10276\n");
            ReadinessGatewayTest.write(dir.resolve("proc/42/cmdline"),"com.nianticlabs.pikmin\u0000");
            String[] stat=new String[22];Arrays.fill(stat,"0");stat[21]="100";
            ReadinessGatewayTest.write(dir.resolve("proc/42/stat"),String.join(" ",stat));
            ReadinessGatewayTest.write(dir.resolve("proc/sys/kernel/random/boot_id"),BOOT+"\n");
            for(String[] f:new String[][]{{"tmp/pikmin-planter-mode.txt",r[3]},{"game/planter_status.tsv",r[4]},
                {"game/planter_inventory.tsv",r[5]},{"game/planter_recovery.tsv",r[6]},
                {"game/core_runtime.tsv",r[8]},{"game/core_capabilities.tsv",r[9]},{"game/automation_session.tsv",r[10]}})
                ReadinessGatewayTest.write(dir.resolve(f[0]),f[1]);
            check(shell(sh,dir,"").healthy,"actual shell healthy and missing pending");
            ReadinessGatewayTest.write(dir.resolve("game/planter_pending.tsv"),"");
            check(!shell(sh,dir,"").canRestart(),"actual empty pending is present");Files.delete(dir.resolve("game/planter_pending.tsv"));
            boolean rejected=false;try{shell(sh,dir,"head(){ return 1; }; ");}catch(IllegalStateException e){rejected=true;}check(rejected,"head failure is not missing");
            Files.delete(dir.resolve("game/planter_inventory.tsv"));Files.createDirectory(dir.resolve("game/planter_inventory.tsv"));
            rejected=false;try{shell(sh,dir,"");}catch(IllegalStateException e){rejected=true;}check(rejected,"directory is not inventory");
            System.out.println("PASS real planter report shell (4 cases) via "+sh+"; fixture files only");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
    }
    static PlanterReportRepair.Sample shell(String sh,Path dir,String extra)throws Exception{
        String cmd=PlanterReportRepairGateway.readCommand().replace("/data/user/0/com.nianticlabs.pikmin/files/","game/")
            .replace("/data/user/0/com.nianticlabs.pikmin","owner").replace("/data/local/tmp/","tmp/").replace("/proc/","proc/");
        String pre="pidof(){ echo 42; }; stat(){ if test \"$2\" = '%u'; then echo 10276; else command stat \"$@\"; fi; }; ";
        ReadinessGatewayTest.write(dir.resolve("run.sh"),pre+extra+"\n"+cmd);
        ProcessBuilder pb=new ProcessBuilder(sh,"run.sh").directory(dir.toFile());pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        return sample(ProbeSnapshot.checkedSections(BoundedCommand.run(pb,5000,300000),15),NOW);
    }
}

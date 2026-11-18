package dev.ody.pikmincontrol;

import android.content.*;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Phone-only user-confirmed recovery. The native core and its normal recovery stay unchanged. */
final class PlanterRetirementGateway {
    private static final String GAME="com.nianticlabs.pikmin",FILES="/data/user/0/"+GAME+"/files/",OWNER="planter-retirement";
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private static String root(String command)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,6,524288);
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0)throw new IOException("育苗封存步驟未確認；不自動重送");return r.text;
    }
    static PlanterRetirement.Proof preview(Context c)throws Exception{
        return new PlanterRetirement.Proof(read(c),System.currentTimeMillis());
    }
    static PlanterRetirement.Basis basis(Context c)throws Exception{
        return new PlanterRetirement.Basis(read(c),System.currentTimeMillis());
    }
    private static String[] read(Context c)throws Exception{
        if(!"dev.ody.pikmincontrol".equals(c.getPackageName()))throw new IllegalStateException("只限手機控制中心");
        CoreUpdater.requireFiles(c,CoreUpdater.pair(c));
        String[] parts=ProbeSnapshot.sections(root(ProbeSnapshot.command(
            OwnerGameProcess.command(),"p=$("+OwnerGameProcess.command()+"); case \"$p\" in ''|*[!0-9]*) exit 1;; esac; awk '{print $22}' /proc/$p/stat","cat /proc/sys/kernel/random/boot_id",
            "cat "+FILES+"planter_status.tsv","cat "+FILES+"planter_inventory.tsv","cat "+FILES+"planter_recovery.tsv",
            "cat "+FILES+"planter_pending.tsv","cat /data/local/tmp/pikmin-planter-recover.txt","cat "+FILES+"automation_session.tsv",
            "dumpsys package "+GAME+" | sed -n 's/^[[:space:]]*versionName=//p'",OwnerGameProcess.command())),11);
        return parts;
    }
    private static void guard(Context c){
        if(CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)||SetupDeployment.pending(c)||UpdateInstaller.blocking(c)
            ||WorkStatus.stoppingAll||WorkStatus.blocked(c)||DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||RouteSession.pending(c))throw new IllegalStateException("先完成其他工作或安裝，再恢復育苗");
    }
    static void retire(Context context,PlanterRetirement.Proof consent,String session,java.util.function.BooleanSupplier active)throws Exception{
        Context c=context.getApplicationContext();if(!busy.compareAndSet(false,true))throw new IllegalStateException("育苗封存核對中");boolean held=false,fenced=false;
        final String id=UUID.randomUUID().toString();final String target=FILES+"planter_pending.tsv.manual-retired-"+id;
        String[] identity=session.split(":",-1);
        if(identity.length!=5||!identity[0].equals(consent.pid)||!identity[1].equals(consent.boot)||!identity[2].equals(consent.ticks)
            ||!identity[3].matches("[1-9][0-9]*")||!identity[4].matches("[1-9][0-9]*")){
            busy.set(false);throw new IOException("恢復帳號狀態未確認");
        }
        final String[] stage={"acquire-lease"};
        try {
            guard(c);GpsWorkLock.acquire(c,OWNER);held=true;
            synchronized(PlanterSettings.class){
                if(PlanterSettings.manualRecoveryBusy||PlanterSettings.reportRepairPaused)throw new IllegalStateException("其他育苗恢復仍在執行");
                PlanterSettings.manualRecoveryBusy=true;fenced=true;
            }
            PlanterRetirement.run(new PlanterRetirement.Port(){
                public void guard()throws Exception{
                    if(!active.getAsBoolean())throw new IOException("確認畫面已關閉，未繼續封存");
                    PlanterRetirementGateway.guard(c);if(!GpsWorkLock.heldBy(c,OWNER))throw new IOException("恢復工作權已變更");
                    GpsWorkLock.touch(c,OWNER);PlanterManualRecoveryGateway.requireSession(c,session,consent.account);
                }
                public void saveIntent(PlanterRetirement.Proof p)throws Exception{
                    stage[0]="save-intent";PlanterReportRepairGateway.audit(c,"retirement:"+stage[0]);
                    save(c,id,"intent\t"+p.boot+"\t"+p.pid+"\t"+p.ticks+"\t"+p.account+"\t"+p.sampled+"\t"+p.inventorySha+"\t"+p.sha+"\tresult-unknown\n"+p.ledger);
                }
                public void retire(PlanterRetirement.Proof p)throws Exception{
                    guard();
                    stage[0]="archive-unknown";
                    // No writer remains after force-stop. Recheck original bytes after exit;
                    // never unlink, replace an archive, confirm old success or replay a plant.
                    String cmd=archiveCommand(p,identity,target);
                    BoundedCommand.Result archived=BoundedCommand.root(cmd,25,4096);
                    if(archived==null||!archived.ok||archived.timedOut||archived.truncated||!"GAME_STOPPEDRETIRED_UNKNOWN".equals(archived.text)) {
                        String failed=archiveFailureStage(archived==null?"":archived.text);
                        if(!failed.isEmpty())stage[0]="archive-"+failed;
                        // The explicit recovery already authorized launching this game. A failed
                        // post-stop proof must not strand it closed or retry the archive/RPC.
                        if(archived!=null&&!archived.timedOut&&!archived.truncated&&archived.exitCode!=null
                                &&archived.text.startsWith("GAME_STOPPED")) {
                            stage[0]="archive-post-stop";
                            try { launch(); } catch(Exception reopen) {
                                throw new IOException("封存未確認且遊戲未重新開啟；保留原紀錄，請手動開啟遊戲",reopen);
                            }
                        }
                        throw new IOException("封存未確認，保留原件／交易；請重新讀取目前狀態，不會重送舊操作");
                    }
                    save(c,id+"-archived","archived\t"+p.sha+"\tresult-unknown\n"+target+"\n");
                }
                public void launch()throws Exception{
                    Intent i=c.getPackageManager().getLaunchIntentForPackage(GAME);if(i==null||i.getComponent()==null||!GAME.equals(i.getComponent().getPackageName()))throw new IOException("找不到遊戲入口，請手動開啟遊戲");
                    String component=i.getComponent().flattenToString();
                    String command=GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,component);
                    if(command.isEmpty())throw new IOException("遊戲入口未確認");
                    BoundedCommand.Result result=BoundedCommand.root(command,8,4096);
                    if(!GamePidRecoveryLaunch.accepted(result,component))throw new IOException("遊戲啟動未確認，請手動開啟；不會重送舊補種");
                }
                public void complete()throws Exception{stage[0]="launch-requested";save(c,id+"-launched","launch-requested\tresult-unknown\n");}
            },consent);
        }catch(Exception e){PlanterReportRepairGateway.audit(c,"retirement:failed:"+stage[0]);throw e;
        }finally{
            if(fenced)synchronized(PlanterSettings.class){PlanterSettings.manualRecoveryBusy=false;}
            if(held)GpsWorkLock.release(c,OWNER);busy.set(false);CollectionMonitorService.sync(c);
        }
        try{
            PlanterReportRepair.Result observed=PlanterReportRepairGateway.run(c,null);
            if(!observed.advancing||!observed.sample.healthy||observed.sample.paused())throw new IOException(observed.message);
            PlanterReportRepairGateway.audit(c,"retirement:reports-restored");
        }catch(Exception e){PlanterReportRepairGateway.audit(c,"retirement:failed:postcheck");throw new IOException("舊紀錄已封存為結果不明，但新育苗尚未確認恢復："+e.getMessage(),e);}
    }
    /** Exact production shell is exercised against synthetic files by HOST regressions. */
    static String archiveFailureStage(String output) {
        for(String name:new String[]{"identity","session","status","inventory","pending","ack","stop","post-stop","move"})
            if(output.contains("\nPCC_RETIRE_FAILED:"+name+"\n"))return name;
        return "";
    }
    static String archiveCommand(PlanterRetirement.Proof p,String[] identity,String target) {
        if(identity==null||identity.length!=5||!identity[3].matches("[1-9][0-9]*")
                ||!identity[4].matches("[1-9][0-9]*")||target==null
                ||!target.matches("/data/user/0/com\\.nianticlabs\\.pikmin/files/planter_pending\\.tsv\\.manual-retired-[a-f0-9-]{36}"))
            throw new IllegalArgumentException("Invalid retirement binding");
        return "set -eu; pcc_retire_stage=identity; trap 'pcc_retire_rc=$?; if test $pcc_retire_rc -ne 0; then printf \"\\nPCC_RETIRE_FAILED:%s\\n\" \"$pcc_retire_stage\"; fi' 0; "+p.guard()+"test ! -e '"+target+"'; test ! -L '"+target+"'; "
                        +"now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NF==23 && $1==\"v1\" && $3=="+p.pid
                        +" && $15=="+identity[3]+" && $16==\"ready\" && $17=="+identity[4]
                        +" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' "+FILES+"core_capabilities.tsv; "
                        +"pcc_retire_stage=status; now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NR==1 && NF>=5 && NF<=6 && $1==\"v1\" && $3=="+p.pid
                        +" && ($4==\"restart-pending-review-required\" || $4==\"rpc-failed-review-required\" || $4==\"server-rejected-review-required\")"
                        +" && $2~/^[0-9]+$/ && now>=$2 && now-$2<=12000 {ok=1} END{exit !(NR==1 && ok)}' "+FILES+"planter_status.tsv; "
                        +"pcc_retire_stage=inventory; now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NR==1 && NF==5 && $1==\"v1\" && $3=="+p.pid
                        +" && $2~/^[0-9]+$/ && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' "+FILES+"planter_inventory.tsv; "
                        +"pcc_retire_stage=session; now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NF==7 && $3=="+p.pid+" && $4==\""+p.account+"\" && $5==\"ready\" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' "+FILES+"automation_session.tsv; "
                        +"pcc_retire_stage=inventory; test \"$(awk -F '\\t' 'BEGIN{OFS=\"\\t\"} NR==1{$2=\"0\"} $1==\"slot\"{$6=\"0\"} {print}' '"+FILES+"planter_inventory.tsv'|sha256sum|cut -d' ' -f1)\" = "+p.inventorySha+"; "
                        +"pcc_retire_stage=pending; test ! -L '"+FILES+"planter_pending.tsv'; test \"$(sha256sum '"+FILES+"planter_pending.tsv'|cut -d' ' -f1)\" = "+p.sha+"; "
                        +"pcc_retire_stage=ack; "+PlanterRetirementBoundary.guard(p)
                        +"pcc_retire_stage=stop; am force-stop --user 0 "+GAME+" >/dev/null 2>&1; printf GAME_STOPPED; pcc_retire_stage=post-stop; p=$("+OwnerGameProcess.watchdogCommand()+"); test -z \"$p\"; "
                        +"test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+p.boot+"; test \"$(sha256sum '"+FILES+"planter_pending.tsv'|cut -d' ' -f1)\" = "+p.sha+"; "
                        +"test \"$(awk -F '\\t' 'BEGIN{OFS=\"\\t\"} NR==1{$2=\"0\"} $1==\"slot\"{$6=\"0\"} {print}' '"+FILES+"planter_inventory.tsv'|sha256sum|cut -d' ' -f1)\" = "+p.inventorySha+"; "
                        +"pcc_retire_stage=move; test ! -e '"+target+"'; test ! -L '"+target+"'; mv '"+FILES+"planter_pending.tsv' '"+target+"'; "
                        +"test \"$(sha256sum '"+target+"'|cut -d' ' -f1)\" = "+p.sha+"; sync; printf RETIRED_UNKNOWN";
    }
    private static void save(Context c,String id,String body)throws Exception{
        AtomicFile file=new AtomicFile(new File(c.getFilesDir(),"planter-retirement-"+id+".tsv"));
        String wire=body+"sha256\t"+PlanterRetirement.hash(body)+"\n";FileOutputStream out=null;
        try{out=file.startWrite();out.write(wire.getBytes(StandardCharsets.UTF_8));file.finishWrite(out);out=null;}finally{if(out!=null)file.failWrite(out);}
        if(!java.util.Arrays.equals(file.readFully(),wire.getBytes(StandardCharsets.UTF_8)))throw new IOException("封存交易未完整保存");
    }
}

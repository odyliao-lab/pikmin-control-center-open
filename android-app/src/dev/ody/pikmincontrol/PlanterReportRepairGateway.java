package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.io.IOException;

/** Manual, phone-only foreground refresh and separately confirmed owner-process restart. */
final class PlanterReportRepairGateway {
    private static final String FILES="/data/user/0/com.nianticlabs.pikmin/files/",OWNER="planter-report-repair";
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences("planter_report_repair",0);}
    static String last(Context c){return prefs(c).getString("message","");}
    private static String readFile(String path,int limit){
        return "if test -L "+path+"; then echo "+PlanterReportRepair.ERROR+"; elif test -e "+path
            +"; then test -f "+path+" && test -r "+path+" && head -c "+(limit+1)+" "+path
            +" 2>/dev/null || echo "+PlanterReportRepair.ERROR+"; else echo "+PlanterReportRepair.MISSING+"; fi";
    }
    private static String ticks(){return "p=$("+OwnerGameProcess.command()+"); case \"$p\" in "+OwnerGameProcess.ABSENT+"|"+OwnerGameProcess.OTHER+") echo "+PlanterReportRepair.MISSING
        +";; ''|*[!0-9]*) echo "+PlanterReportRepair.ERROR
        +";; *) awk '{print $22}' /proc/$p/stat || echo "+PlanterReportRepair.ERROR+";; esac";}
    static String readCommand(){return ProbeSnapshot.command(readFile(FILES+"core_capabilities.tsv",8192),OwnerGameProcess.command(),
        "cat /proc/sys/kernel/random/boot_id",ticks(),readFile("/data/local/tmp/pikmin-planter-mode.txt",128),
        readFile(FILES+"planter_status.tsv",2048),readFile(FILES+"planter_inventory.tsv",262144),
        readFile(FILES+"planter_recovery.tsv",2048),readFile(FILES+"planter_pending.tsv",512),
        readFile(FILES+"core_runtime.tsv",2048),readFile(FILES+"core_capabilities.tsv",8192),
        readFile(FILES+"automation_session.tsv",2048),ticks(),"cat /proc/sys/kernel/random/boot_id",OwnerGameProcess.command());}
    private static BoundedCommand.Result root(String command,int seconds,int bytes)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,seconds,bytes);
        if(r==null||!r.ok||r.timedOut||r.truncated)throw new IOException("育苗診斷命令未確認；未清除紀錄或自動重試重啟");return r;
    }
    private static void guard(Context c){
        if(CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)||SetupDeployment.pending(c)
            ||UpdateInstaller.blocking(c)||WorkStatus.stoppingAll||WorkStatus.blocked(c)
            ||DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||RouteSession.pending(c))
            throw new IllegalStateException("請先安全結束批次／採果或安裝工作，再診斷育苗");
    }
    private static String component(Context c)throws Exception{
        Intent i=c.getPackageManager().getLaunchIntentForPackage(OwnerGameProcess.GAME);
        String name=i==null||i.getComponent()==null?"":i.getComponent().flattenToString();
        if(GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,name).isEmpty())throw new IOException("找不到主空間遊戲入口");
        return name;
    }
    static PlanterReportRepair.Result run(Context context,PlanterReportRepair.Sample consent)throws Exception{
        Context c=context.getApplicationContext();
        if(!"dev.ody.pikmincontrol".equals(c.getPackageName()))throw new IllegalStateException("此恢復入口只適用手機版");
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("育苗診斷仍在執行");
        boolean held=false;
        try{
            synchronized(GpsWorkLock.class){guard(c);GpsWorkLock.acquire(c,OWNER);held=true;}
            CorePair pair=CoreUpdater.pair(c);String launch=component(c);
            PlanterReportRepair.Port port=new PlanterReportRepair.Port(){
                public void guard(){PlanterReportRepairGateway.guard(c);
                    if(!GpsWorkLock.heldBy(c,OWNER))throw new IllegalStateException("診斷工作權已改變；未繼續");GpsWorkLock.touch(c,OWNER);}
                public long elapsed(){return SystemClock.elapsedRealtime();}
                public PlanterReportRepair.Sample read()throws Exception{
                    return new PlanterReportRepair.Sample(ProbeSnapshot.checkedSections(root(readCommand(),4,300000),15),pair,System.currentTimeMillis());}
                public void openGame()throws Exception{
                    BoundedCommand.Result r=root("am start --user 0 -n '"+launch+"'",8,4096);
                    if(!GamePidRecoveryLaunch.accepted(r,launch))throw new IOException("切回遊戲未確認，請手動回地圖後重試診斷");}
                public void waitForUpdate()throws Exception{Thread.sleep(4000);}
                public void audit(String code)throws Exception{
                    PlanterReportRepairGateway.audit(c,code);}
                public void pause()throws Exception{
                    synchronized(PlanterSettings.class){
                        PlanterSettings.reportRepairPaused=true;PlanterGateway.setMode("off");
                    }
                    // Give a previously admitted tick a chance to publish its durable ledger.
                    // This is risk reduction, NOT an idle-RPC certificate; any late ledger survives restart.
                    Thread.sleep(6000);
                }
                public void resume()throws Exception{
                    synchronized(PlanterSettings.class){PlanterSettings.reportRepairPaused=false;PlanterSettings.reconcile(c);}
                }
                public void restart(PlanterReportRepair.Sample s)throws Exception{
                    CoreUpdater.requireFiles(c,pair);guard();
                    // Revalidate process incarnation, epoch and ledger absence inside the same shell,
                    // immediately before force-stop. Native RPC results and ledgers are untouched.
                    String cmd="set -eu; test \"$("+OwnerGameProcess.command()+")\" = "+s.pid+"; "
                        +"test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+s.boot+"; "
                        +"test \"$(awk '{print $22}' /proc/"+s.pid+"/stat)\" = "+s.ticks+"; "
                        +"test \"$(cat /data/local/tmp/pikmin-planter-mode.txt)\" = off; "
                        +"test ! -e "+FILES+"planter_pending.tsv; test ! -L "+FILES+"planter_pending.tsv; "
                        +"now=$(date +%s%3N); awk -F '\\t' -v now=\"$now\" 'NF==23 && $1==\"v1\" && $3=="+s.pid
                        +" && $15=="+s.caps.sessionEpoch+" && $16==\"ready\" && $17=="+s.caps.lastLoginMs
                        +" && now>=$2 && now-$2<=12000 {ok=1} END{exit !ok}' "+FILES+"core_capabilities.tsv; "
                        +"awk -F '\\t' 'NF==7 && $3=="+s.pid+" && $4==\""+s.account+"\" && $5==\"ready\" {ok=1} END{exit !ok}' "+FILES+"automation_session.tsv; "
                        +"am force-stop --user 0 "+OwnerGameProcess.GAME+"; p=$("+OwnerGameProcess.watchdogCommand()+"); test -z \"$p\"; "
                        +"am start --user 0 -n '"+launch+"'";
                    if(!GamePidRecoveryLaunch.accepted(root(cmd,8,4096),launch))throw new IOException("重啟命令未確認；不重複執行，請查看遊戲後再診斷");
                }
            };
            PlanterReportRepair.Result result=consent==null?PlanterReportRepair.refresh(port):PlanterReportRepair.restart(port,consent);
            if(!prefs(c).edit().putString("message",result.message).commit())throw new IOException("診斷結果未保存");return result;
        }catch(Exception e){prefs(c).edit().putString("message","診斷未完成；紀錄與設定保留，請重新診斷").commit();throw e;}
        finally{if(held)GpsWorkLock.release(c,OWNER);busy.set(false);}
    }
    static void returnToApp(Context c){
        try{String command=BatchRecoveryReturn.command(c.getPackageName(),c.getApplicationInfo().uid)
                .replace("--ez open_dispatch true","--ez open_return true");
            BoundedCommand.Result r=BoundedCommand.root(command,8,4096);
            if(!BatchRecoveryReturn.accepted(r,c.getPackageName()))android.util.Log.w("PlanterReportRepair","return-unconfirmed");
        }catch(Exception e){android.util.Log.w("PlanterReportRepair","return-unconfirmed");}
    }
    static String history(Context c){return prefs(c).getString("history","");}
    // Only bounded, fixed stage codes: no raw exception text, identities or coordinates.
    static synchronized void audit(Context c,String code)throws IOException{
        if(code==null||code.length()>80||!code.matches("[A-Za-z0-9_:;-]+"))throw new IOException("診斷代碼無效");
        String row=System.currentTimeMillis()+"\t"+code+"\n",old=prefs(c).getString("history","");
        String[] lines=(row+old).split("\n");StringBuilder out=new StringBuilder();
        for(int n=0;n<Math.min(24,lines.length);n++)out.append(lines[n]).append('\n');
        if(!prefs(c).edit().putString("history",out.toString()).commit())throw new IOException("診斷紀錄未保存；不繼續恢復");
    }
}

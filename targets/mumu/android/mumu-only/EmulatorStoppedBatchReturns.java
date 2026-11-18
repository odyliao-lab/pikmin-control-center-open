package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.UUID;

/** Explicit, bounded return-only work after a stopped manual batch. Uses the
 * existing monitor and native return API; never starts Daily, GPS or dispatch. */
final class EmulatorStoppedBatchReturns {
    static final long LIMIT_MS=600_000L;
    private static final String PREFS="stopped_batch_returns_v1";
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,0);}
    static boolean pending(Context c){return prefs(c).getBoolean("pending",false);}
    static String summary(Context c){SharedPreferences p=prefs(c);return p.getString("status","");}
    static boolean allowed(boolean stopped,boolean drained,boolean batchLive,boolean gpsHeld,boolean blocked,boolean maintenance){
        return stopped&&drained&&!batchLive&&!gpsHeld&&!blocked&&!maintenance;
    }
    static boolean live(long now,long created,long deadline,boolean stop,boolean desired,boolean same){
        return now>=created&&created>0&&deadline==created+LIMIT_MS&&now<deadline&&!stop&&desired&&same;
    }
    private static boolean maintenance(Context c){return WorkStatus.stoppingAll||SetupDeployment.pending(c)||CoreUpdater.pending(c)||UpdateInstaller.pending(c)||CoreInstallWizard.preparing(c);}
    private static EmulatorSavedDailyStore stopped(Context c)throws Exception{
        EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
        if(s==null||!allowed(s.current()&&!s.enabled()&&s.confirmedStop()&&"STOPPED".equals(s.get("run.lifecycle","")),s.drained(),
                DispatchBatchService.isRunning()||FlowerFarmService.isRunning(),GpsWorkLock.anyHeld(c),WorkStatus.blocked(c)||NearbyWorkState.pending(c),maintenance(c)))
            throw new IllegalStateException("請先讓每日與批次真正停止、原未決對帳完成；不會啟動排程");
        return s;
    }
    static void request(Context c)throws Exception{
        synchronized(GpsWorkLock.class){synchronized(AutomationPreferences.class){
            if(!EmulatorDailySupportWindow.inWindow(System.currentTimeMillis()))throw new IllegalStateException("僅07–23活動時間開新領回窗口");
            if(pending(c))throw new IllegalStateException("原領回窗口仍在核對；不可延長或重開");
            EmulatorSavedDailyStore s=stopped(c);
            EmulatorSavedDailyRuntime.Identity i=new EmulatorSavedDailyRuntime(c,s).game();
            if(!"all".equals(AutomationPreferences.value(c,"reward","off")))throw new IllegalStateException("請先保存自動領取意願ON");
            String mode=ReturnRewardModuleGateway.mode();if(!mode.equals("off"))throw new IllegalStateException("原領取控制必須已停止");
            requireInactive(s,i);
            long now=System.currentTimeMillis();String id=UUID.randomUUID().toString();
            String original="PCCSTOPPEDRETURNS/1\t"+id+"\t"+s.run+"\t"+i.key()+"\t"+now+"\t"+(now+LIMIT_MS)+"\toff\tkeep\tend";
            if(!prefs(c).edit().putString("request."+id,original).putString("id",id).putString("run",s.run).putString("owner",i.key())
                    .putLong("created",now).putLong("deadline",now+LIMIT_MS).putBoolean("stop",false).putBoolean("pending",true)
                    .putString("status","領回窗口已保存；等待同程序核對，最多10分鐘，不啟動排程").commit())throw new IllegalStateException("領回請求未保存");
        }}
        CollectionMonitorService.sync(c);
    }
    static void stop(Context c){
        synchronized(GpsWorkLock.class){synchronized(AutomationPreferences.class){
            if(pending(c)&&!prefs(c).edit().putBoolean("stop",true).commit())throw new IllegalStateException("停止請求未保存");
        }}
        CollectionMonitorService.sync(c);
    }
    private static void requireInactive(EmulatorSavedDailyStore s,EmulatorSavedDailyRuntime.Identity i)throws Exception{
        String f=EmulatorSavedDailyRuntime.FILES;
        String result=EmulatorSavedDailyRuntime.root("set -eu; "+s.guard(false)+i.sessionGuard()+
            "g=$(stat -c %u "+f+"); for p in automation_session.tsv core_capabilities.tsv emulator_planting_observation.tsv emulator_independent_planting_transaction.tsv.safety; do p="+f+"$p; test -f $p; test ! -L $p; test \"$(stat -c %u:%g:%a:%h $p)\" = $g:$g:644:1; test \"$(stat -c %s $p)\" -le 32768; done; "+
            "now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==6 && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}' "+f+"emulator_planting_observation.tsv; "+
            "awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==8 && $3==p && $4==\"ready\" && $5==\"clear\" && $6==\"idle\" && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}' "+f+"emulator_independent_planting_transaction.tsv.safety; "+
            "for n in dispatch planter planting flower-farm; do test ! -L /data/local/tmp/pikmin-$n-mode.txt; test \"$(cat /data/local/tmp/pikmin-$n-mode.txt)\" = off; done; "+
            "for n in feeding_pending.tsv planter_pending.tsv emulator-command; do test ! -e "+f+"$n; test ! -L "+f+"$n; done; "+
            "awk -F '\\t' 'NR==1 && $1==\"v3\" && $3==0 && $7==0 && $9==0 {ok=1} END {exit !ok}' /data/local/tmp/pikmin-feeding-config.tsv; "+
            "test \"$(cat /data/local/tmp/pikmin-return-postcard-policy.txt)\" = keep; echo READY_RETURN_ONLY").trim();
        if(!result.equals("READY_RETURN_ONLY"))throw new IllegalStateException("領回工作尚未就緒；原未決保留");
    }
    /** Called by the original monitor. Even a failed readiness check first
     * restricts return to OFF; no stale owner can retain a new ON write. */
    static boolean tick(Context c)throws Exception{
        if(!pending(c))return false;
        synchronized(GpsWorkLock.class){synchronized(AutomationPreferences.class){
            if(!pending(c))return false;
            SharedPreferences p=prefs(c);
            try {
                EmulatorSavedDailyStore s=stopped(c);
                EmulatorSavedDailyRuntime.Identity i=new EmulatorSavedDailyRuntime(c,s).game();
                long now=System.currentTimeMillis();
                boolean same=s.run.equals(p.getString("run",""))&&i.key().equals(p.getString("owner",""));
                if(!live(now,p.getLong("created",0),p.getLong("deadline",0),p.getBoolean("stop",false),
                        "all".equals(AutomationPreferences.value(c,"reward","off"))&&EmulatorDailySupportWindow.inWindow(now),same))return finish(c,"領回窗口已停止；收據另行核對，未決不補成功");
                requireInactive(s,i);
                // Full original owner/session and exact STOP are rechecked in
                // the SAME root command immediately before this sole ON effect.
                EmulatorSavedDailyRuntime.root("set -eu; "+s.guard(false)+i.sessionGuard()+
                    "test \"$(date +%s%3N)\" -lt "+p.getLong("deadline",0)+"; "+
                    "test ! -L /data/local/tmp/pikmin-return-mode.txt; "+ReturnRewardModuleGateway.modeCommand("all"));
                p.edit().putString("status","批次領回中；每日／GPS／HC未啟動，到期停止，成果依原生收據").apply();
                return true;
            }catch(Exception refused){finish(c,"領回已安全停止；身份或原工作待核對，原收據／未決保持");return true;}
        }}
    }
    private static boolean finish(Context c,String status)throws Exception{
        EmulatorSavedDailyRuntime.root("set -eu; test ! -L /data/local/tmp/pikmin-return-mode.txt; "+ReturnRewardModuleGateway.modeCommand("off")+
            "; test \"$(cat /data/local/tmp/pikmin-return-mode.txt)\" = off");
        SharedPreferences p=prefs(c);long now=System.currentTimeMillis();
        if(!p.edit().putString("result."+p.getString("id",""),"OFF_CONFIRMED\t"+now+"\t"+status)
                .putBoolean("pending",false).putString("status",status).putLong("ended",now).commit())throw new IllegalStateException("領回停止回讀未保存");
        return true;
    }
}

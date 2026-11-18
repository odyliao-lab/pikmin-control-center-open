package dev.ody.pikmincontrol;

import android.content.Intent;
import android.content.Context;
import android.os.SystemClock;

final class PlanterManualRecoveryGateway {
    private static final String OWNER="planter-manual-recovery";
    private static final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    static PlanterRecoveryFlow.Prepared run(Context context)throws Exception {return prepare(context,null);}
    private static PlanterRecoveryFlow.Prepared prepare(Context context,PlanterRecoveryFlow.Prepared consent)throws Exception {
        if(!busy.compareAndSet(false,true))throw new IllegalStateException("育苗恢復仍在執行");
        try{return recover(context,consent);}finally{busy.set(false);}
    }
    static boolean confirm(Context c,PlanterRecoveryFlow.Prepared consent,java.util.function.BooleanSupplier active)throws Exception {
        return PlanterRecoveryFlow.confirm(new PlanterRecoveryFlow.Port(){
            public void guard(){if(!active.getAsBoolean())throw new IllegalStateException("確認畫面已關閉，未繼續封存");PlanterManualRecoveryGateway.guard(c);}
            public PlanterRecoveryFlow.Prepared prepare(PlanterRecoveryFlow.Prepared previous)throws Exception{return PlanterManualRecoveryGateway.prepare(c,previous);}
            public void retire(PlanterRetirement.Proof proof,String session)throws Exception{PlanterRetirementGateway.retire(c,proof,session,active);}
        },consent);
    }
    static PlanterReportRepair.Sample current(Context c)throws Exception{
        return new PlanterReportRepair.Sample(
            ProbeSnapshot.checkedSections(BoundedCommand.root(PlanterReportRepairGateway.readCommand(),4,300000),15),
            CoreUpdater.pair(c),System.currentTimeMillis());
    }
    static void requireSession(Context c,String session,String account)throws Exception{
        PlanterReportRepair.Sample sample=current(c);
        if(!sample.healthy||!session.equals(sample.session())||!account.equals(sample.account))
            throw new IllegalStateException("帳號、程序或育苗回報已變更，未繼續恢復");
    }
    private static PlanterReportRepair.Sample foregroundCurrent(Context c)throws Exception{
        Intent launch=c.getPackageManager().getLaunchIntentForPackage(OwnerGameProcess.GAME);
        String component=launch==null||launch.getComponent()==null?"":launch.getComponent().flattenToString();
        String command=GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,component);
        if(command.isEmpty())throw new IllegalStateException("找不到主空間遊戲入口");
        BoundedCommand.Result opened=BoundedCommand.root(command,8,4096);
        if(!GamePidRecoveryLaunch.accepted(opened,component))throw new IllegalStateException("切回遊戲未確認；未送出育苗恢復核對");
        long start=SystemClock.elapsedRealtime();String process="",session="";
        PlanterReportRepair.Sample latest=null;
        while(SystemClock.elapsedRealtime()-start<PlanterReportRepair.BUDGET_MS){
            guard(c);latest=current(c);
            if(latest.processKnown){
                if(!process.isEmpty()&&!process.equals(latest.process()))throw new IllegalStateException("更新期間遊戲程序已變更，請重新核對育苗");
                process=latest.process();
            }
            if(latest.sessionKnown){
                if(!session.isEmpty()&&!session.equals(latest.session()))throw new IllegalStateException("更新期間帳號狀態已變更，請重新核對育苗");
                session=latest.session();
            }
            if(PlanterReportRepair.readyForManualRecovery(latest))return latest;
            if(SystemClock.elapsedRealtime()-start>=PlanterReportRepair.BUDGET_MS)break;
            Thread.sleep(2000);
        }
        if(latest==null)throw new IllegalStateException("尚未取得目前育苗狀態");
        if(latest.healthy&&!latest.paused())throw new IllegalStateException("目前育苗沒有待核對的安全暫停；未重設狀態");
        throw new IllegalStateException(latest.message());
    }
    private static PlanterRecoveryFlow.Prepared recover(Context context,PlanterRecoveryFlow.Prepared consent)throws Exception {
        Context c=context.getApplicationContext();
        String stage="acquire-lease";
        boolean held=false,fenced=false;
        try{
            synchronized(GpsWorkLock.class){guard(c);GpsWorkLock.acquire(c,OWNER);held=true;}
            synchronized(PlanterSettings.class){
                if(PlanterSettings.manualRecoveryBusy||PlanterSettings.reportRepairPaused)throw new IllegalStateException("育苗恢復仍在執行");
                PlanterSettings.manualRecoveryBusy=true;
                fenced=true;
            }
            stage="foreground-refresh";
            PlanterReportRepairGateway.audit(c,"manual:"+stage);
            // Use a fresh current snapshot after foregrounding. Requiring two advancing
            // report timestamps deadlocks when the complete inventory is stable.
            PlanterReportRepair.Sample fresh=foregroundCurrent(c);
            // Consent has no wall-clock lifetime. Current evidence does: re-read the same
            // ledger and baseline without using the now-expired previous ACK/request.
            stage="consent-recheck";
            final PlanterRetirement.Basis expected=consent==null?null:PlanterRetirementGateway.basis(c);
            if(consent!=null)PlanterRecoveryFlow.requireSame(consent,expected,fresh.session());
            stage="native-check";
            PlanterManualRecovery.Port port=new PlanterManualRecovery.Port(){
                public void guard()throws Exception{
                    PlanterManualRecoveryGateway.guard(c);
                    if(!GpsWorkLock.heldBy(c,OWNER))throw new IllegalStateException("育苗恢復工作權已改變");
                    GpsWorkLock.touch(c,OWNER);
                    requireSession(c,fresh.session(),fresh.account);
                }
                public PlanterRecovery submit()throws Exception{
                    CoreUpdater.requireFiles(c,CoreUpdater.pair(c));
                    return expected==null?PlanterGateway.requestRecovery():PlanterGateway.requestRecovery(expected,fresh.caps.sessionEpoch,fresh.caps.lastLoginMs);
                }
                public PlanterRecovery read()throws Exception{return PlanterGateway.recovery();}
                public void waitForUpdate()throws Exception{Thread.sleep(2000);}
                public long elapsed(){return SystemClock.elapsedRealtime();}
            };
            PlanterRecovery ack=PlanterManualRecovery.run(port);
            stage="capture-proof";
            port.guard();
            // Capture while the game is still foreground and our lease/fence is held.
            PlanterRetirement.Proof proof="result-unknown".equals(ack.result)?PlanterRetirementGateway.preview(c):null;
            port.guard();
            PlanterRecoveryFlow.Prepared result=new PlanterRecoveryFlow.Prepared(ack,proof,fresh.session());
            if(consent!=null&&proof!=null)PlanterRecoveryFlow.requireSame(consent,proof,result.session);
            PlanterReportRepairGateway.audit(c,proof==null?"manual:native-result":"manual:proof-ready");
            return result;
        }catch(Exception e){
            PlanterReportRepairGateway.audit(c,"manual:failed:"+stage);throw e;
        }finally{
            if(fenced)synchronized(PlanterSettings.class){PlanterSettings.manualRecoveryBusy=false;}
            if(held)GpsWorkLock.release(c,OWNER);
        }
    }
    private static void guard(Context c){
        if(CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)||SetupDeployment.pending(c)
            ||UpdateInstaller.blocking(c)||WorkStatus.stoppingAll||WorkStatus.blocked(c)
            ||DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||RouteSession.pending(c))
            throw new IllegalStateException("核心安裝或停止全部尚未完成，暫不送出育苗恢復核對");
    }
}

package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.concurrent.*;

/** The production MuMu saved-route scheduler. Explicit opt-in, sticky same-run restore. */
public final class EmulatorSavedDailyService extends Service {
    static final String START="dev.ody.pikmincontrol.mumu.SAVED_DAILY_START",STOP="dev.ody.pikmincontrol.mumu.SAVED_DAILY_STOP";
    static final String DRAIN="dev.ody.pikmincontrol.mumu.SAVED_DAILY_DRAIN";
    private static final Object[] LANE_LOCKS={new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),new Object(),new Object()};
    private EmulatorSavedDailyStore store;private EmulatorSavedDailyRuntime runtime;private EmulatorSavedDailyUi ui;
    private final java.util.List<ScheduledExecutorService> workers=new java.util.ArrayList<>();
    private volatile boolean stopping;
    private static volatile EmulatorSavedDailyService live;
    static void start(Context c,EmulatorSavedRoutePlan plan)throws Exception{
        start(c,plan,false);
    }
    static void start(Context c,EmulatorSavedRoutePlan plan,boolean daytime)throws Exception{
        if(EmulatorStoppedBatchReturns.pending(c))throw new IllegalStateException("請先停止批次領回並核對收據，再開始每日排程");
        EmulatorRuntime.require();EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
        if(live!=null&&live.stopping)throw new IllegalStateException("Previous saved schedule is still stopping; no replacement");
        if(s==null||s.replaceable())s=EmulatorSavedDailyStore.create(c,plan,daytime?EmulatorSavedRouteRotation.daytimePreserving(s==null?"":s.get("rotation.plan",""),System.currentTimeMillis()).encode():"");
        else if(!s.enabled())throw new IllegalStateException("Original saved run is still draining; no replacement");
        else if(s.plan.city!=plan.city)throw new IllegalStateException("Stop current city before choosing another");
        c.startForegroundService(new Intent(c,EmulatorSavedDailyService.class).setAction(START).putExtra("run",s.run));
    }
    static void stop(Context c)throws Exception{
        EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);if(s!=null){s.stop();
            if(EmulatorSavedDailyStore.STOPPED.equals(s.lifecycle()))return;
            c.startForegroundService(new Intent(c,EmulatorSavedDailyService.class).setAction(DRAIN).putExtra("run",s.run));}
    }
    static void startOvernight(Context c)throws Exception{
        if(EmulatorStoppedBatchReturns.pending(c))throw new IllegalStateException("請先停止批次領回並核對收據");
        EmulatorRuntime.require();EmulatorSavedDailyStore prior=EmulatorSavedDailyStore.open(c);
        if(prior==null||live!=null||!prior.replaceable()||!prior.confirmedStop()||!prior.drained())
            throw new IllegalStateException("Stop and drain the current schedule first; no in-place calendar rewrite");
        String original=prior.get("rotation.plan","");
        String rotation=EmulatorSavedRouteRotation.overnightPreserving(original,System.currentTimeMillis()).encode();
        EmulatorSavedRoutePlan plan=prior.plan;
        // create() also checks the original pending/owner/maintenance gates and publishes last.
        EmulatorSavedDailyStore next=EmulatorSavedDailyStore.create(c,plan,rotation);
        c.startForegroundService(new Intent(c,EmulatorSavedDailyService.class).setAction(START).putExtra("run",next.run));
    }
    static void restore(Context c){
        try{
            EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
            if(s!=null&&!EmulatorSavedDailyStore.STOPPED.equals(s.lifecycle()))
                c.startForegroundService(new Intent(c,EmulatorSavedDailyService.class).setAction(s.enabled()?START:DRAIN).putExtra("run",s.run));
        }catch(Exception e){android.util.Log.w("PccSavedDaily","Saved restore pending; original run and intents retained");}
    }
    /** Explicit UI request resumes only the saved enabled run. It never clears
     * a hold/intent, runs a second recovery worker or manufactures a new run. */
    static synchronized String requestRecovery(Context c)throws Exception{
        EmulatorRuntime.require();EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
        if(s==null)throw new IllegalStateException("No saved run");
        boolean maintenance=WorkStatus.stoppingAll||SetupDeployment.pending(c)||CoreUpdater.pending(c)||UpdateInstaller.pending(c)||CoreInstallWizard.preparing(c);
        boolean competing=DispatchBatchService.isRunning()||FlowerFarmService.isRunning();
        long now=System.currentTimeMillis(),previous=s.journal.getLong("recovery.ui-at",0);
        if(!recoveryRequestAllowed(s.enabled(),maintenance,competing,live!=null&&live.stopping,now,previous))
            throw new IllegalStateException("Stopped, busy or recovery request cooldown; originals retained");
        if(live!=null)return "每日服務已在運行；沿用既有有限恢復節拍，不重啟、不清未決。";
        if(!s.journal.edit().putLong("recovery.ui-at",now).commit())throw new IllegalStateException("Recovery request not durable");
        if(!s.enabled()||WorkStatus.stoppingAll)throw new IllegalStateException("Saved run stopped during request");
        c.startForegroundService(new Intent(c,EmulatorSavedDailyService.class).setAction(START).putExtra("run",s.run));
        return "已要求恢復原每日服務；尚未確認遊戲動作。休息、同程序核心及各項未決守門仍適用。";
    }
    static boolean recoveryRequestAllowed(boolean enabled,boolean maintenance,boolean competing,boolean stopping,long now,long previous){
        return enabled&&!maintenance&&!competing&&!stopping&&now>0&&previous>=0&&now>=previous&&(previous==0||now-previous>=60000);
    }
    @Override public void onCreate(){super.onCreate();EmulatorRuntime.require();}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        // Even an idempotent/stale STOP may have been delivered through startForegroundService.
        // Acknowledge that Android obligation before any terminal/maintenance return.
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("saved-daily","每日自動排程",NotificationManager.IMPORTANCE_LOW));
        startForeground(369,notification("正在核對排程狀態"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        try{
            EmulatorSavedDailyStore current=EmulatorSavedDailyStore.open(this);if(current==null){stopSelf();return START_NOT_STICKY;}
            // Explicit maintenance owns this stopped run until its UNKNOWN
            // archival finishes. Do not restart an observer across a crash or
            // package replacement; never clear the original pending here.
            if(!current.get("plant.maintenance","").isEmpty()||!current.get("plant.archive-maintenance","").isEmpty()){if(runtime!=null)runtime.close();stopSelf();return START_NOT_STICKY;}
            if(intent!=null){
                String requested=intent.getStringExtra("run");
                if(requested!=null&&!current.run.equals(requested)){if(runtime==null)stopSelf(id);return runtime==null?START_NOT_STICKY:START_STICKY;}
                if(STOP.equals(intent.getAction())){
                    if(!current.run.equals(requested))throw new IllegalStateException("Exact STOP run required");
                    current.stop();
                }
            }
            if(EmulatorSavedDailyStore.STOPPED.equals(current.lifecycle())){stopSelf();return START_NOT_STICKY;}
            if(runtime!=null&&!runtime.store.run.equals(current.run))throw new IllegalStateException("Live saved runtime cannot adopt a new run");
            store=current;
            if(current.needsDrain()){
                if(!stopping&&runtime!=null)runtime.resetClosure();
                stopping=true;current.beginDrain();
            }
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("saved-daily","每日自動排程",NotificationManager.IMPORTANCE_LOW));
            {startForeground(369,notification("正在恢復預存路線模式"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);ControlNotifications.refresh(this);}
            if(runtime!=null)return START_STICKY;
            runtime=new EmulatorSavedDailyRuntime(this,current);ui=new EmulatorSavedDailyUi(runtime);live=this;
            lane(0,"saved-gps",5,()->runtime.gpsTick(),e->runtime.gpsText="GPS待確認；其它工作繼續");
            lane(1,"saved-plant",10,()->runtime.plantingTick(),e->runtime.plantText="種花等待核心／庫存；GPS繼續");
            lane(2,"saved-helpers",30,()->runtime.helpersTick(),e->runtime.helperText="背景功能等待核心");
            lane(3,"saved-steps",60,()->runtime.stepsTick(),e->runtime.stepsText="步數歸屬或停止結果待確認");
            lane(4,"saved-batch",15,()->runtime.batchTick(),e->runtime.batchText="批次待核對；不重送");
            lane(5,"saved-ui",5,()->ui.tick(),e->android.util.Log.w("PccSavedDaily","Known UI recovery waiting; other lanes unaffected"));
            lane(7,"saved-health",30,()->runtime.healthTick(),e->runtime.recoveryText="安全復原待核對；未清除原件");
            lane(8,"saved-capacity",60,()->runtime.capacityTick(),e->{});
            lane(9,"saved-retention",60,()->runtime.retentionTick(),e->{});
            lane(6,"saved-status",10,()->{
                runtime.closingStatusTick();publishStatus();
                ControlNotifications.post(this,369,notification((store.needsDrain()?"停止收尾中；":"")+runtime.gpsText+"；"+runtime.plantText+"；"+runtime.helperText+"；"+runtime.stepsText+"；"+runtime.batchText));
                if(store.needsDrain()&&store.completeStop(runtime.drainReady())){
                    try{runtime.close();}finally{stopSelf();}
                }
            },e->{});
            return START_STICKY;
        }catch(Exception failure){android.util.Log.e("PccSavedDaily","Saved scheduler start failed; config/unknown retained");stopSelf();return START_NOT_STICKY;}
    }
    interface Work{void run()throws Exception;}
    static boolean isRunning(){return live!=null;}
    static void refreshRetiredGps(EmulatorSavedDailyStore saved)throws Exception{
        EmulatorSavedDailyService owner=live;if(owner==null)return;
        if(owner.store==null||!owner.store.run.equals(saved.run)||owner.runtime==null)throw new IllegalStateException("Original daily runtime required");
        owner.runtime.refreshRetiredGps();
    }
    static void pauseForPlantingMaintenance(EmulatorSavedDailyStore saved,String digest)throws Exception {
        if(!saved.current()||!saved.confirmedStop()||saved.enabled()||!saved.needsDrain()||
            !digest.matches("[a-f0-9]{64}")||!(digest.equals(saved.get("plant.maintenance",""))||digest.equals(saved.get("plant.archive-maintenance","")))||
            !EmulatorDeadPlantingRecovery.maintenanceQueue(saved.journal.getAll())||!saved.get("rotation.settling","").isEmpty())
            throw new IllegalStateException("Exact stopped maintenance request required");
        EmulatorSavedDailyService owner=live;if(owner==null)return;
        if(owner.store==null||!owner.store.run.equals(saved.run)||owner.runtime==null||
            !owner.runtime.gpsClosed||!owner.runtime.helpersOff||!owner.runtime.stepsClosed||!owner.runtime.batchClosed)
            throw new IllegalStateException("Other daily lanes have not drained");
        owner.runtime.close();for(ScheduledExecutorService worker:owner.workers)worker.shutdownNow();owner.stopSelf();
    }
    static String status(Context c,EmulatorSavedDailyStore s)throws Exception{
        if(s==null)return "尚未建立每日排程";
        long at=s.journal.getLong("status.at",0),age=System.currentTimeMillis()-at;
        String head=!s.enabled()?"已停止；不會自動復活":isRunning()?"每日服務運行中":"已保存啟用，但每日服務未確認運行";
        String text=head+" · "+s.plan.savedName()+"\n"+(at==0?"尚無本輪觀察":age<0||age>30000?"以下為舊狀態（非即時證據）":"最近觀察 "+age/1000+" 秒前");
        text+="\n"+s.journal.getString("status.text","各工作尚待首次觀察");
        String rotation=s.get("rotation.plan","");
        if(!rotation.isEmpty()){
            EmulatorSavedRouteRotation plan=EmulatorSavedRouteRotation.decode(rotation);
            EmulatorSavedRouteRotation.Slot slot=plan.at(System.currentTimeMillis());
            text+="\n每天兩線 · "+plan.firstDay+"起 · "+(plan.daytime?"07:00／15:00，23:00停止 · 台北＋首爾／首爾＋東京／東京＋台北":"06:00／16:45 · 舊輪替");
            text+="\n目前路線 "+s.get("gps.route-name",s.plan.savedName())+(slot==null?" · 尚未開始／收工休息":" · 本時段 "+slot.city.savedName);
            if(!s.get("rotation.settling","").isEmpty())text+="\n換線後等待遊戲定位穩定；種花與背景工作暫停新增";
        }
        long capacityAt=s.journal.getLong("capacity.at",0),capacityAge=System.currentTimeMillis()-capacityAt;
        text+="\n"+(capacityAt<=0||capacityAge<0||capacityAge>90000?"容量未確認（尚無近期觀察）":
            "容量觀察 "+capacityAge/1000+" 秒前：\n"+s.journal.getString("capacity.summary","容量未確認"));
        String pre=s.get("closing.preflight","");if(!pre.isEmpty())text+="\n收工預檢 "+s.get("closing.preflight.day","")+"："+pre;
        String retention=s.get("retention.summary","");if(!retention.isEmpty())text+="\n截圖整理 "+s.get("retention.attempt","")+"："+retention;
        String summary=s.get("closing.summary","");if(!summary.isEmpty())text+="\n上次收工 "+s.get("closing.summary.day","")+"："+summary;
        for(String lane:new String[]{"saved-gps","saved-plant","saved-helpers","saved-steps","saved-batch","saved-health"}){
            long ok=s.journal.getLong("health."+lane+".ok",0),failed=s.journal.getLong("health."+lane+".failed",0);
            if(failed>=ok&&failed>0)text+="\n"+lane+"："+s.journal.getString("health."+lane+".error","待確認")+"（"+Math.max(0,(System.currentTimeMillis()-failed)/1000)+"秒前）";
        }
        return text+"\n觀察節拍不等於遊戲動作成功；無候選不視為故障。";
    }
    private void publishStatus()throws Exception{
        String text=runtime.recoveryText+"\n"+runtime.gpsText+"\n"+runtime.plantText+"\n"+runtime.helperText+"\n"+runtime.stepText+"\n"+runtime.batchText;
        if(runtime.closing()){
            long now=System.currentTimeMillis(),at=Long.parseLong(store.get("closing.wait-at",Long.toString(now)));
            long left=EmulatorSavedDailyRuntime.remainingMinutes(now,runtime.closingDeadline());
            text+="\n收工狀態持續 "+Math.max(0,(now-at)/1000)+" 秒；本次窗口剩餘約 "+left+" 分鐘；未知不重送";
        }
        if(!store.journal.edit().putLong("status.at",System.currentTimeMillis()).putString("status.text",text).commit())throw new IllegalStateException("Status commit unconfirmed");
    }
    private void lane(int index,String name,int seconds,Work work,java.util.function.Consumer<Exception> failure){
        ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,name);t.setPriority(Thread.MIN_PRIORITY);return t;});workers.add(executor);
        final EmulatorSavedDailyRuntime owner=runtime;
        executor.scheduleWithFixedDelay(()->{
            synchronized(LANE_LOCKS[index]){
                if(owner.isClosed())return;
                try{work.run();store.journal.edit().putLong("health."+name+".ok",System.currentTimeMillis()).apply();}catch(Exception e){if(!owner.isClosed())failure.accept(e);android.util.Log.w("PccSavedDaily",name+" pending: "+e.getClass().getSimpleName()+("saved-gps".equals(name)?" · "+e.getMessage():""));}
            }
        },0,seconds,TimeUnit.SECONDS);
    }
    private Notification notification(String text){
        Intent stop=new Intent(this,EmulatorSavedDailyService.class).setAction(STOP);if(store!=null)stop.putExtra("run",store.run);
        Notification.Builder n=new Notification.Builder(this,"saved-daily").setSmallIcon(android.R.drawable.ic_menu_mylocation).setContentTitle("每日排程 · 預存路線").setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this,369,new Intent(this,EmulatorSavedDailyActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(new Notification.Action.Builder(null,"停止排程",PendingIntent.getService(this,369,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT)).build());return ControlNotifications.grouped(this,n);
    }
    @Override public void onDestroy(){
        if(live==this)live=null;
        try{if(runtime!=null)runtime.close();}
        finally{
            try{if(ui!=null)ui.close();}
            finally{for(ScheduledExecutorService w:workers)w.shutdownNow();super.onDestroy();}
        }
    }
    @Override public IBinder onBind(Intent intent){return null;}
}

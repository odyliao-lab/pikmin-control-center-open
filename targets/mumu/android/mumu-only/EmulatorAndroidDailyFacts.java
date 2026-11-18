package dev.ody.pikmincontrol;

import android.content.Context;
import android.os.SystemClock;
import java.time.Instant;

/** Read-only GPS/resource facts. No scheduler or effects are enabled here.
 * Step/HC acceptance is independent and is never granted or consumed here. */
final class EmulatorAndroidDailyFacts implements EmulatorAndroidDailyTick.FactsSource {
    interface Acceptance { void require(EmulatorDailyRunner.Binding binding)throws Exception; }
    interface Access {
        void identity()throws Exception;
        // Legacy diagnostic seam only; GPS facts never invoke step reads.
        default EmulatorGameDailySteps.Observation identityRecheckCounter()throws Exception{identity();return null;}
        EmulatorDailyResources resources()throws Exception;
        EmulatorGameDailySteps.Observation counter()throws Exception;
        FarmLocation location()throws Exception;
        Work work(EmulatorDailyRunner.State retained)throws Exception;
        boolean configured()throws Exception;
        default void support()throws Exception {} // original HOST/legacy seams have no helper effects
        default boolean overnight(){return false;}
        long wall(); long elapsed();
    }
    static final class Work {
        final boolean route,batch,external;
        Work(boolean route,boolean batch,boolean external){this.route=route;this.batch=batch;this.external=external;}
        boolean same(Work next){return next!=null&&route==next.route&&batch==next.batch&&external==next.external;}
    }
    private final EmulatorDailyRunner.Binding held;
    private final Access access;
    private String reason="not-read";
    private enum ReadStage { IDENTITY, CONFIGURATION, SUPPORT, RESOURCES, IDENTITY_RECHECK, LOCATION, EVALUATION }
    EmulatorAndroidDailyFacts(Context context,EmulatorDailyRunner.Binding held,HarvestRoute route,Acceptance acceptance) {
        this(held,new AndroidAccess(context,held,route),acceptance);
    }
    EmulatorAndroidDailyFacts(Context context,EmulatorDailyConfiguration config,Acceptance acceptance){
        this(config.binding,new AndroidAccess(context,config),acceptance);
    }
    EmulatorAndroidDailyFacts(EmulatorDailyRunner.Binding held,Access access,Acceptance acceptance) {
        if(held==null||access==null)throw new IllegalArgumentException("Independent binding and actual reads required");
        // Retain the legacy constructor/interface for the independent HC UI.
        // Do not invoke, catch-and-ignore, migrate or certify that provider.
        this.held=held;this.access=access;
    }
    String reason(){return reason;}
    public EmulatorDailyRunner.Facts read(EmulatorDailyRunner.State retained)throws Exception {
        if(retained!=null&&!held.same(retained.binding)){
            reason="binding-changed-no-adoption";
            return new EmulatorDailyRunner.Facts(held,0,false,false,false,true,false);
        }
        Work before=access.work(retained);
        if(before==null)throw new IllegalStateException("Known work observation required");
        boolean ready=false,configured=false;long epoch=0;
        EmulatorNurseryBudget.Snapshot nursery=null;
        ReadStage stage=ReadStage.IDENTITY;
        try {
            access.identity();
            stage=ReadStage.CONFIGURATION;
            configured=access.configured();
            // GPS readiness does not depend on a step certificate, its PID,
            // ledger, distance correlation or automatic writer being enabled.
            // Existing HC evidence remains owned by its separate observer/UI.
            stage=ReadStage.SUPPORT;
            access.support(); // owned effective settings only, under original daily lock and after identity/acceptance
            stage=ReadStage.RESOURCES;
            EmulatorDailyResources resource=access.resources();
            stage=ReadStage.IDENTITY_RECHECK;
            access.identity();
            // Step/HC/nursery operation is independent of GPS. No counter read,
            // counter-to-distance comparison or nursery ledger gates this job.
            // Android location() independently sandwiches the actual PID and
            // checks the native heartbeat plus JoyStick provider. Never cache
            // its ready state across another slow evidence/identity read.
            stage=ReadStage.LOCATION;FarmLocation fix=access.location();
            stage=ReadStage.EVALUATION;
            long now=access.wall(),elapsed=access.elapsed();
            if(!configured)reason="private-route-changed";
            else if(resource==null||!held.same(resource.binding)||!resource.fresh(now))reason="resources-unavailable";
            else if(fix==null||!fix.valid||!Integer.toString(held.pid).equals(fix.pid)||fix.epoch<=0||!fix.fresh(now,elapsed))reason="location-unavailable";
            else {
                epoch=fix.epoch;
                java.time.ZonedDateTime time=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
                int minute=time.getHour()*60+time.getMinute();
                // Capacity/no-stock blocks NEW daytime routes, not fruit/gift
                // collection after 16:30 nor confirmation of accepted work.
                reason=EmulatorDailyPlan.routeWindow(now,retained==null?access.overnight():retained.journal.overnight)?resource.blocker(true,true,false):"";
                ready=reason.isEmpty();
                if(ready)reason="fresh-verified-facts";
            }
        }catch(Exception unavailable){reason="fresh-read-unavailable-"+stage.name();} // no raw exception/private data
        Work after=access.work(retained);
        if(after==null)throw new IllegalStateException("Fresh exact work observation required");
        if(!before.same(after)){ready=false;nursery=null;reason="work-changed-during-read";}
        return new EmulatorDailyRunner.Facts(held,epoch,ready,after.route,after.batch,after.external,configured,nursery);
    }
    private static final class AndroidAccess implements Access {
        public boolean overnight(){return true;}
        final Context context;final EmulatorDailyRunner.Binding held;final HarvestRoute route;final EmulatorDailyConfiguration configuration;
        AndroidAccess(Context c,EmulatorDailyRunner.Binding b,HarvestRoute r){
            if(c==null||b==null||r==null||!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))throw new IllegalArgumentException("MuMu private route context required");
            context=c;held=b;route=r;configuration=null;
        }
        AndroidAccess(Context c,EmulatorDailyConfiguration config){
            if(c==null||config==null||!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))throw new IllegalArgumentException("MuMu immutable itinerary required");
            context=c;held=config.binding;route=config.route;configuration=config;
        }
        public void identity()throws Exception{EmulatorDailyRouteWork.requireIdentity(context,held);}
        public EmulatorGameDailySteps.Observation identityRecheckCounter()throws Exception{
            return EmulatorDailyRouteWork.requireIdentityCounter(context,held,null);
        }
        public void support()throws Exception{EmulatorDailySupportWindow.refresh(context);}
        public EmulatorDailyResources resources()throws Exception{return EmulatorDailyResources.read(context,held);}
        public EmulatorGameDailySteps.Observation counter()throws Exception{return EmulatorRunnerCounterGateway.read(context,held,CoreUpdater.pair(context));}
        public FarmLocation location()throws Exception{return GpsModuleGateway.farmLocation(context);}
        public boolean configured()throws Exception{
            java.util.List<FlowerFarmService.Point> points=new java.util.ArrayList<>();
            for(HarvestRoute.Point p:route.points)points.add(new FlowerFarmService.Point(p.lat,p.lng));
            boolean primary=held.kmh==HarvestRoute.SPEED_KMH&&held.routeSha.equals(EmulatorDailyRouteWork.routeSha(points));
            return primary&&(configuration==null||configuration.encode().equals(EmulatorAndroidDailyConfigured.read(context).encode()));
        }
        public Work work(EmulatorDailyRunner.State retained){
            String key=retained==null?"":retained.journal.pendingKey;
            EmulatorDailyRunner.Binding actual=configuration!=null&&retained!=null&&retained.journal.pending==EmulatorDailyPlan.Pending.ROUTE?configuration.routeBinding(key):held;
            boolean ownsRoute=retained!=null&&retained.journal.pending==EmulatorDailyPlan.Pending.ROUTE&&FlowerFarmService.ownsDailyRoute(actual,key);
            boolean ownsBatch=retained!=null&&retained.journal.pending==EmulatorDailyPlan.Pending.BATCH&&DispatchBatchService.ownsDailyBatch(held,key);
            boolean external=FlowerFarmService.isRunning()&&!ownsRoute||DispatchBatchService.isRunning()&&!ownsBatch
                ||GpsWorkLock.anyHeld(context)&&!(ownsRoute&&GpsWorkLock.heldBy(context,GpsWorkLock.FLOWER_FARM)||ownsBatch&&GpsWorkLock.heldBy(context,GpsWorkLock.BATCH))
                ||RouteSession.pending(context)&&!ownsRoute||WorkStatus.stoppingAll||WorkStatus.blocked(context)
                ||SetupDeployment.pending(context)||UpdateInstaller.pending(context)||CoreUpdater.pending(context);
            return new Work(ownsRoute,ownsBatch,external);
        }
        public long wall(){return System.currentTimeMillis();}
        public long elapsed(){return SystemClock.elapsedRealtime();}
    }
}

package dev.ody.pikmincontrol;

import android.content.Context;
import java.time.LocalDate;

/** Future MuMu runner transport. Route effects use the existing owned service;
 * batch requires its own exact-key adapter, never generic start/stop flags.
 * This class does not establish readiness, enable scheduling or adopt work. */
final class EmulatorAndroidDailyPort implements EmulatorDailyRunner.Port {
    interface BatchPort {
        void start(EmulatorDailyRunner.Binding held,String key,long locationEpoch,long deadline)throws Exception;
        void closeAdmissions(EmulatorDailyRunner.Binding held,String key)throws Exception;
        EmulatorDailyRunner.Observation observe(EmulatorDailyRunner.Binding held,String key)throws Exception;
    }
    private final Context context;
    private final HarvestRoute route;
    private final EmulatorDailyConfiguration configuration;
    private final BatchPort batch;
    EmulatorAndroidDailyPort(Context context,HarvestRoute route) {
        this(context,route,new EmulatorAndroidDailyBatchPort(context));
    }
    EmulatorAndroidDailyPort(Context context,HarvestRoute route,BatchPort batch) {
        this(context,route,null,batch);
    }
    EmulatorAndroidDailyPort(Context context,EmulatorDailyConfiguration config){
        this(context,config==null?null:config.route,config,new EmulatorAndroidDailyBatchPort(context));
    }
    private EmulatorAndroidDailyPort(Context context,HarvestRoute route,EmulatorDailyConfiguration config,BatchPort batch) {
        if(context==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName())||route==null||batch==null)
            throw new IllegalArgumentException("Complete MuMu route and exact batch adapter required");
        this.context=context;this.route=route;this.configuration=config;this.batch=batch;
    }
    public void startRoute(EmulatorDailyRunner.Binding held,String key,long epoch)throws Exception {
        requireKey(held,key,false);requireRoute(held);
        EmulatorDailyRunner.Binding actual=routeBinding(held,key);
        FlowerFarmService.startDailyRoute(context,configuration==null?route:configuration.routeFor(key),actual,key,epoch);
    }
    public void stopOwnedRoute(EmulatorDailyRunner.Binding held,String key)throws Exception {
        requireKey(held,key,false);
        FlowerFarmService.stopDailyRoute(context,routeBinding(held,key),key);
    }
    public void finalBatch(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline)throws Exception {
        requireKey(held,key,true);requireRoute(held);
        long expected=EmulatorDailyPlan.deadline(key);
        if(epoch<=0||deadline!=expected)throw new IllegalArgumentException("Exact day admission deadline required");
        batch.start(held,key,epoch,deadline);
    }
    public void closeBatchAdmissions(EmulatorDailyRunner.Binding held,String key)throws Exception {
        requireKey(held,key,true);batch.closeAdmissions(held,key);
    }
    public EmulatorDailyRunner.Observation observe(EmulatorDailyRunner.Binding held,String key)throws Exception {
        boolean isBatch=key!=null&&key.contains(":batch-");requireKey(held,key,isBatch);
        EmulatorDailyRunner.Binding actual=isBatch?held:routeBinding(held,key);
        EmulatorDailyRunner.Observation result=isBatch?batch.observe(held,key):FlowerFarmService.observeDailyRoute(context,actual,key);
        if(result==null||!actual.same(result.binding)||!key.equals(result.key))throw new IllegalStateException("Exact work observation required");
        return new EmulatorDailyRunner.Observation(held,key,result.evidence);
    }
    private EmulatorDailyRunner.Binding routeBinding(EmulatorDailyRunner.Binding held,String key){
        if(configuration==null)return held;
        if(!held.same(configuration.binding))throw new IllegalArgumentException("Exact immutable itinerary binding required");
        return configuration.routeBinding(key);
    }
    private void requireRoute(EmulatorDailyRunner.Binding held) {
        java.util.List<FlowerFarmService.Point> points=new java.util.ArrayList<>();
        for(HarvestRoute.Point p:route.points)points.add(new FlowerFarmService.Point(p.lat,p.lng));
        if(!held.routeSha.equals(EmulatorDailyRouteWork.routeSha(points))||held.kmh!=HarvestRoute.SPEED_KMH)
            throw new IllegalArgumentException("Held private route changed");
    }
    static void requireKey(EmulatorDailyRunner.Binding held,String key,boolean batch) {
        if(held==null||key==null||!key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:"+(EmulatorDailyPlan.overnightKey(key)?"n22:":batch&&EmulatorDailyPlan.twoHourEveningKey(key)?"e23h2:":batch&&EmulatorDailyPlan.eveningBatchKey(key)?"e23:":"")+(batch?"batch-[1-3]":EmulatorDailyPlan.overnightKey(key)?"route-"+EmulatorDailyPlan.NIGHT_ROUTE_NUMBER:"route-([0-9]|1[0-2])")))
            throw new IllegalArgumentException("Exact daily work key required");
        if(!LocalDate.parse(key.substring(0,10)).toString().equals(key.substring(0,10)))throw new IllegalArgumentException("Canonical work day required");
    }
}

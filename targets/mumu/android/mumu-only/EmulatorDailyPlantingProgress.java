package dev.ody.pikmincontrol;

/** Ordered along-route distance for daily planting, NOT flower/fruit witnesses.
 * Latest actual owned GPS fixes must remain <=2s old. An emulator-only 8s gap,
 * 7m/s+2m jump bound and real endpoint checks remain. Projection is local and
 * forward-bounded, not a global nearest point search or interpolated fix.
 * Passed geometry milestones are not claims of observing every waypoint.
 */
final class EmulatorDailyPlantingProgress {
    // Different from sample age: a fresh fix may arrive after a slow IPC/root
    // observation round. Device313 rejected a fresh 1636ms-old fix solely for
    // a 4115ms interval (8.01m movement). Keep a finite observation bound while
    // retaining actual fix time, physics/local projection and endpoint proof.
    // Ordinary phone/manual harvesting retains its original 4s bound.
    static final long MAX_OBSERVATION_GAP_MS=8000;
    private final HarvestRoute route;
    private final double[] arc;
    private HarvestRoute.Point previous;
    private long previousAt=-1,lastAge=-1,maxGap=-1;
    private double along;
    private int reached,tolerated;
    private String failure="",detail="";
    EmulatorDailyPlantingProgress(HarvestRoute route){
        if(route==null||route.points.size()<2)throw new IllegalArgumentException("Daily route required");
        this.route=route;arc=new double[route.points.size()];
        for(int i=1;i<arc.length;i++)arc[i]=arc[i-1]+HarvestRoute.distance(route.points.get(i-1),route.points.get(i));
        if(!(arc[arc.length-1]>0))throw new IllegalArgumentException("Moving daily route required");
    }
    boolean observeProvider(JoystickReadiness fix,long now){
        if(fix==null||!fix.known||!fix.mock||!fix.own||!fix.fresh(now)||now-fix.fixMs>HarvestRouteProgress.SAMPLE_AGE_MS)
            return fail("stale-provider");
        lastAge=now-fix.fixMs;
        return observe(fix.latitude,fix.longitude,fix.fixMs);
    }
    boolean observe(double lat,double lng,long at){
        if(!failure.isEmpty())return false;
        HarvestRoute.Point point;
        try{point=new HarvestRoute.Point(lat,lng);}catch(IllegalArgumentException invalid){return fail("invalid-location");}
        if(at<0)return fail("invalid-clock");
        if(previousAt<0){
            if(HarvestRoute.distance(route.points.get(0),point)>HarvestRouteProgress.REACH_M)return fail("start-not-reached");
            previous=point;previousAt=at;reached=1;detail="daily start; no harvesting witnesses";return false;
        }
        long dt=at-previousAt;double moved=HarvestRoute.distance(previous,point);
        detail="daily distanceMeters="+Math.round(along)+", dtMs="+dt+", ageMs="+lastAge+", movedMeters="+Math.round(moved*100)/100.0;
        if(dt<0)return fail("clock-regression");
        if(dt>MAX_OBSERVATION_GAP_MS)return fail("observation-gap");
        if(dt==0){if(moved>0.5)return fail("conflicting-observation");return finished();}
        double budget=HarvestRouteProgress.MAX_SPEED_MPS*dt/1000.0+HarvestRouteProgress.JUMP_TOLERANCE_M;
        if(moved>budget)return fail("unexpected-jump");
        double bestDistance=Double.POSITIVE_INFINITY,bestAlong=-1;
        // Only arcs physically reachable since the LAST adopted real fix are
        // considered. At intersections, equal-distance ties choose the earlier
        // arc, never a later loop/end coordinate. No skipped-sample fabrication.
        for(int i=Math.min(arc.length-2,Math.max(0,reached-1));i<arc.length-1&&arc[i]<=along+budget;i++){
            double length=arc[i+1]-arc[i];if(length<=0.01)continue;
            HarvestRoute.Point a=route.points.get(i),b=route.points.get(i+1);
            double cos=Math.cos(Math.toRadians((a.lat+b.lat)/2));
            double x=(b.lng-a.lng)*cos,y=b.lat-a.lat;
            double px=(point.lng-a.lng)*cos,py=point.lat-a.lat;
            double t=Math.max(0,Math.min(1,(px*x+py*y)/(x*x+y*y)));
            double projected=arc[i]+t*length;
            if(projected<along-2||projected>along+budget)continue;
            HarvestRoute.Point foot=new HarvestRoute.Point(a.lat+t*(b.lat-a.lat),a.lng+t*(b.lng-a.lng));
            double distance=HarvestRoute.distance(foot,point);
            if(distance<bestDistance-0.01||(Math.abs(distance-bestDistance)<=0.01&&(bestAlong<0||projected<bestAlong))){
                bestDistance=distance;bestAlong=projected;
            }
        }
        if(bestAlong<0||bestDistance>10)return fail("off-daily-route");
        along=Math.max(along,bestAlong);
        previous=point;previousAt=at;maxGap=Math.max(maxGap,dt);
        if(dt>HarvestRouteProgress.SAMPLE_AGE_MS)tolerated++;
        while(reached<arc.length&&arc[reached]<=along+0.01)reached++;
        if(arc[arc.length-1]-along<=HarvestRouteProgress.REACH_M&&
            HarvestRoute.distance(point,route.points.get(arc.length-1))<=HarvestRouteProgress.REACH_M)reached=arc.length;
        detail="daily distanceMeters="+Math.round(along)+"/"+Math.round(arc[arc.length-1])+", dtMs="+dt+", ageMs="+lastAge+", movedMeters="+Math.round(moved*100)/100.0;
        return finished();
    }
    boolean fresh(long now){return previousAt>=0&&now>=previousAt&&now-previousAt<=HarvestRouteProgress.SAMPLE_AGE_MS&&failure.isEmpty();}
    boolean finished(){return reached==arc.length&&failure.isEmpty();}
    int reached(){return reached;}
    String failure(){return failure;}
    String detail(){return detail;}
    long maxGapMs(){return maxGap;}
    int toleratedGaps(){return tolerated;}
    private boolean fail(String code){failure=code;return false;}
}

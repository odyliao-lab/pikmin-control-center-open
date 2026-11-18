package dev.ody.pikmincontrol;

/**
 * Ordered provider progress only. Never authorizes planting, dispatch, or game arrival.
 *
 * Two different times are kept apart (route harvest observation spec §4.1):
 * <ul>
 * <li><b>sample age</b> = now − provider fix time. The newest fix must be at most
 *     {@link #SAMPLE_AGE_MS} old; an old fix is never accepted just to bridge a gap.</li>
 * <li><b>observation interval</b> = fix time of this sample − fix time of the last
 *     adopted (different) sample. It may exceed the sample age limit when a STATUS or
 *     root read made the loop miss one provider update; up to {@link #MAX_GAP_MS} it
 *     is tolerated as long as the newest fix is fresh, the coordinate is legal and the
 *     displacement respects the speed bound. Beyond it the route stops safely.</li>
 * </ul>
 * A tolerated interval never back-fills the nodes that might have been passed in
 * between: only nodes actually observed within reach, in order, count as reached.
 */
final class HarvestRouteProgress {
    /** Newest provider fix must be at most this old (unchanged freshness rule). */
    static final long SAMPLE_AGE_MS = 2000;
    /** First-version candidate: largest tolerated fix-to-fix interval. Not an unbounded relaxation; device-validation pending. */
    static final long MAX_GAP_MS = 4000;
    /** Provider speed bound in metres per second plus a fixed tolerance; unchanged. */
    static final double MAX_SPEED_MPS = 7.0, JUMP_TOLERANCE_M = 2.0;
    static final double REACH_M = 6.0;

    private final HarvestRoute route;
    private HarvestRoute.Point previous;
    private long previousAt = -1;
    private int next;
    private String failure = "";
    private String detail = "";
    private long lastGapMs = -1, maxGapMs = -1, lastAgeMs = -1;
    private int toleratedGaps;
    HarvestRouteProgress(HarvestRoute route) { this.route=route; }
    boolean observeProvider(JoystickReadiness fix,long elapsed) {
        // Detail always describes THIS sample; an invalid provider never carries the previous sample's numbers.
        if(fix==null||!fix.fresh(elapsed)){detail="ageMs="+(fix==null||fix.fixMs<0?"unknown":Long.toString(elapsed-fix.fixMs))+", dtMs=unknown, movedMeters=unknown";lastAgeMs=-1;return fail("stale-provider");}
        long age=elapsed-fix.fixMs;
        lastAgeMs=age;
        if(age>SAMPLE_AGE_MS){detail="ageMs="+age+", dtMs=unknown, movedMeters=unknown";return fail("stale-provider");}
        return observe(fix.latitude,fix.longitude,fix.fixMs);
    }
    boolean observe(double lat,double lng,long elapsed) {
        if (!failure.isEmpty()) return false;
        final HarvestRoute.Point point;
        try { point=new HarvestRoute.Point(lat,lng); }
        catch (IllegalArgumentException e) { return fail("invalid-location"); }
        if (elapsed<0) return fail("invalid-clock");
        if (previousAt>=0) {
            long dt=elapsed-previousAt;
            double moved=HarvestRoute.distance(previous,point);
            // Formed before any verdict so every rejection (gap, regression, jump) carries this sample's dt/age/moved.
            detail="dtMs="+dt+age()+", movedMeters="+Math.round(moved*100)/100.0;
            if (dt<0) return fail("clock-regression");
            if (dt>MAX_GAP_MS) return fail("observation-gap");
            if (dt==0) {
                if (moved>0.5) return fail("conflicting-observation");
                return finished(); // A repeated observation never advances a route.
            }
            if (moved>MAX_SPEED_MPS*dt/1000.0+JUMP_TOLERANCE_M) return fail("unexpected-jump");
            lastGapMs=dt; if(dt>maxGapMs)maxGapMs=dt;
            if (dt>SAMPLE_AGE_MS) toleratedGaps++;
        } else {
            detail="first"+age()+", startDistanceMeters="+Math.round(HarvestRoute.distance(route.points.get(0),point)*100)/100.0;
            if (HarvestRoute.distance(route.points.get(0),point)>REACH_M) return fail("start-not-reached");
        }
        previous=point; previousAt=elapsed;
        // No global nearest-point lookup: crossing the final coordinate early,
        // an out-and-back or a repeated waypoint must not finish a trip early.
        // A tolerated gap does not back-fill nodes passed in between: a node that
        // was never observed within reach stays unreached (the trip may time out).
        while(next<route.points.size() && HarvestRoute.distance(route.points.get(next),point)<=REACH_M) {
            // Close nodes must not all be consumed at the starting coordinate.
            // Exact duplicate nodes remain legitimate no-distance steps.
            if(next>0) {
                HarvestRoute.Point from=route.points.get(next-1);
                double leg=HarvestRoute.distance(from,route.points.get(next));
                if(leg>0.01 && HarvestRoute.distance(from,point)<leg*0.8) break;
            }
            next++;
        }
        return finished();
    }
    private String age() { return lastAgeMs>=0 ? ", ageMs="+lastAgeMs : ""; }
    boolean fresh(long elapsed) { return previousAt>=0 && elapsed>=previousAt && elapsed-previousAt<=SAMPLE_AGE_MS && failure.isEmpty(); }
    boolean finished() { return next==route.points.size() && failure.isEmpty(); }
    int reached() { return next; }
    String failure() { return failure; }
    String detail() { return detail; }
    /** Last adopted fix-to-fix interval in ms, -1 before the second adopted sample. */
    long lastGapMs() { return lastGapMs; }
    /** Largest adopted fix-to-fix interval so far, -1 before the second adopted sample. */
    long maxGapMs() { return maxGapMs; }
    /** Adopted samples whose interval exceeded the sample-age limit but stayed within the tolerated gap. */
    int toleratedGaps() { return toleratedGaps; }
    /** Short operator wording per failure code (spec §4.4); details stay in {@link #detail()}. Says "正在停止", not "已安全停止": the outer cleanup confirms the stop later (review round 1, R3). */
    static String label(String failure) {
        switch (failure) {
            case "stale-provider": return "最新定位過期，正在停止";
            case "observation-gap": return "定位觀測中斷，正在停止";
            case "unexpected-jump": return "定位跳點，正在停止";
            case "clock-regression": case "conflicting-observation": case "invalid-clock": return "定位時序異常，正在停止";
            case "invalid-location": return "定位座標無效，正在停止";
            case "start-not-reached": return "未到達路線起點，正在停止";
            default: return "路線進度異常，正在停止";
        }
    }
    private boolean fail(String reason) { failure=reason; return false; }
}

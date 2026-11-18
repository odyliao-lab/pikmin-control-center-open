package dev.ody.pikmincontrol;

/** Waiting is never arrival authority. During pre-START arrival, an aged
 * observation resets the consecutive-fix gate and waits inside the ORIGINAL
 * deadline. No fly/START/WALK is replayed. Changed identity/provider and an
 * active planting round's OFF/unowned/changed confirmation still fails
 * immediately. An otherwise positive aged post-START frame may only wait;
 * it never authorizes arrival or WALK. */
final class EmulatorDailyArrivalGuard {
    private FarmLocation.Arrival gate=new FarmLocation.Arrival();
    private final long deadline;
    EmulatorDailyArrivalGuard(long deadline){if(deadline<=0)throw new IllegalArgumentException("Arrival deadline required");this.deadline=deadline;}
    boolean observe(RouteObservation observation,String pid,String account,long requested,boolean plantingRequired,
            double lat,double lng,long wall,long elapsed,long flightWall,long flightElapsed){
        if(observation==null||account==null||account.isEmpty()||!account.equals(observation.account)||
           !pid.equals(observation.location.pid))throw new IllegalStateException("Daily arrival identity changed");
        FarmLocation fix=observation.location;
        if(fix.provider==null||!fix.provider.known||!fix.provider.mock||!fix.provider.own)
            throw new IllegalStateException("Daily arrival provider ownership lost");
        if(fix.at>wall||fix.rawAt>elapsed||fix.provider.fixMs>elapsed)
            throw new IllegalStateException("Daily arrival clock regression");
        if(!plantingRequired&&(observation.planting.planting||observation.planting.owned))
            throw new IllegalStateException("Unexpected planting before daily START");
        if(plantingRequired&&!EmulatorDailyWalkBaseline.ready(observation,pid,account,requested,wall,elapsed)){
            // Reuse the existing strict post-START/pre-WALK classifier. It
            // rejects OFF/unowned/pre-request/malformed/foreign frames, and
            // returns false only for an otherwise positive aged snapshot.
            // Keep this arrival's original deadline and distinct-fix gate.
            gate=new FarmLocation.Arrival();return false;
        }
        if(elapsed>=deadline||!fix.valid||!fix.fresh(wall,elapsed)||!observation.planting.valid){
            gate=new FarmLocation.Arrival();return false;
        }
        observation.require(pid,account,requested,plantingRequired); // unchanged full arrival gates
        return gate.observe(fix,lat,lng,wall,elapsed,flightWall,flightElapsed);
    }
    static String diagnostics(RouteObservation observation,long wall,long elapsed,long cost){
        FarmLocation f=observation.location;
        return "readMs="+cost+", heartbeatAgeMs="+(wall-f.at)+", rawFixAgeMs="+(elapsed-f.rawAt)+
            ", providerAgeMs="+(f.provider==null?-1:elapsed-f.provider.fixMs)+", plantingAgeMs="+(wall-observation.planting.at);
    }
}

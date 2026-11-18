package dev.ody.pikmincontrol;

/** Pure scheduling policy. The native boundary must recheck all facts before any RPC. */
final class FeedingPolicy {
    static final class Facts {
        boolean complete,accountReady,inSquad,idle,canFeed,canCollect,priorityWork,pending;
        long nectar=-1,petals=-1,capacity=-1,reservedNectar,reservedPetals,cost=-1,yield=-1;
        // Raw FlowerStateFlowerCount diagnostic value, not a verified feed counter.
        int heartMilli=-1,flowerStateCount=-1;
    }
    static String decide(FeedingConfig c,String kind,Facts f){
        if(!c.enabled)return "off";
        if(f.pending)return "pending-review";
        if(!f.complete||!f.accountReady)return "waiting-data";
        if(f.priorityWork)return "waiting-other-work";
        if(!f.inSquad||!f.idle)return "waiting-squad";
        if(f.nectar<0||f.petals<0||f.capacity<=0||f.petals>f.capacity||f.reservedNectar<0||f.reservedPetals<0
                ||f.heartMilli<0||f.heartMilli>8000||f.flowerStateCount<0)return "waiting-data";
        long limit=Math.min(f.capacity,c.ceiling(kind));
        if(f.canCollect){
            if(!c.collect)return "waiting-collect";
            if(f.yield<0||f.reservedPetals>limit||f.yield>limit-f.reservedPetals||f.petals>limit-f.reservedPetals-f.yield)return "petal-limit";
            return "collect";
        }
        if(!c.rules.containsKey(kind))return "not-allowed";
        if(f.heartMilli>=c.hearts*1000)return "friendship-reached";
        // The native game restriction gate decides whether another action is
        // allowed. FlowerStateFlowerCount is retained only for diagnostics.
        if(!f.canFeed)return "waiting-recovery";
        if(f.cost<=0||f.yield<0)return "waiting-data";
        if(f.reservedNectar>f.nectar||f.cost>f.nectar-f.reservedNectar||f.nectar-f.reservedNectar-f.cost<c.reserve(kind))return "nectar-reserve";
        if(f.petals>=limit||f.reservedPetals>limit-f.petals||f.yield>limit-f.petals-f.reservedPetals)return "petal-limit";
        return "feed";
    }
}

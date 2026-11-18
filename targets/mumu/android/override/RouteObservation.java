package dev.ody.pikmincontrol;

/** One bounded read, same PID and existing strict parsers; never a gameplay authority. */
final class RouteObservation {
    final FarmLocation location;
    final PlantingState planting;
    final String account;
    final String ownedCapabilities,ownedJournal,ownedPetals;
    final int plantingPacketBytes;
    final String snapshotBefore,snapshotAfter,nativeRaw,plantingRaw;
    RouteObservation(String[] p,String providerIdentity,String pid,long wall,long elapsed) {
        if(p.length!=9)throw new IllegalArgumentException("route snapshot sections");
        snapshotBefore=p[0];snapshotAfter=p[8];nativeRaw=p[2];plantingRaw=p[3];
        ownedCapabilities=p[5];ownedJournal=p[6];ownedPetals=p[7];
        plantingPacketBytes=p[3].getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        p=new String[]{p[0],p[1],p[2],p[3],p[4],p[8]};
        location=new FarmLocation(p[2],p[0],p[5],wall,elapsed,JoystickReadiness.parse(p[1],providerIdentity));
        planting=new PlantingState(p[3],p[0],p[5],pid,wall);
        account=SessionIdentity.parse(p[4],p[0].trim(),p[5].trim(),wall);
    }
    void require(String pid,String expectedAccount,long plantingSince) {
        require(pid,expectedAccount,plantingSince,true);
    }
    void require(String pid,String expectedAccount,long plantingSince,boolean plantingRequired) {
        if(expectedAccount.isEmpty()||!expectedAccount.equals(account))throw new IllegalStateException("帳號識別失效或已變更，停止工作");
        if(!location.valid||!pid.equals(location.pid))throw new IllegalStateException("沿路遊戲定位失效："+location.readiness);
        if(!planting.valid||(plantingRequired&&!planting.activeSince(plantingSince)))throw new IllegalStateException("遊戲種花已停止或回報失效，已中止花田工作");
    }
}

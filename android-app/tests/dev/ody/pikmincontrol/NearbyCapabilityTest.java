package dev.ody.pikmincontrol;

public final class NearbyCapabilityTest {
    static int checks;
    static final long WALL=1788706000000L, BOOT=10000000L;
    static String row(long at, long fix) { return "v2\t"+at+"\t1829\tready\t1\t3\t40\t28\t40\t28\t"+fix+"\t300000"; }
    static String stat(long ticks) {
        StringBuilder s=new StringBuilder("1829 (process with spaces) S");
        for(int field=4;field<22;field++) s.append(" 0");
        return s.append(" ").append(ticks).append(" 0").toString();
    }
    static boolean verify(String raw,String pid,String proc,String hz,String after) {
        return NearbyCapability.verified(raw,pid,proc,hz,after,WALL,BOOT);
    }
    static void check(boolean value) { checks++; if(!value) throw new AssertionError("check "+checks); }
    public static void main(String[] args) {
        String stale=row(WALL-150000,BOOT-150000), proc=stat(200000);
        check(verify(stale,"1829",proc,"100","1829"));
        check(!verify(stale.replace("v2\t","v1\t"),"1829",proc,"100","1829"));
        check(!new NearbyGuard(stale,"1829",WALL).supported); // stale is NEVER readiness
        check(!verify(stale,"1829",proc,"100","1830"));
        check(!verify(stale,"1830",proc,"100","1830"));
        check(!verify(stale,"1829 1830",proc,"100","1829 1830"));
        check(!verify(stale,"1829",proc.replace("1829 (","1830 ("),"100","1829"));
        check(!verify(stale,"1829",stat(999999),"100","1829")); // same PID newer process
        check(!verify(row(WALL-86400000,BOOT-150000),"1829",proc,"100","1829")); // previous boot
        check(!verify(row(WALL+1,BOOT-1),"1829",proc,"100","1829"));
        check(!verify(row(WALL-1,BOOT+1),"1829",proc,"100","1829"));
        check(!verify(row(WALL-1,100),"1829",proc,"100","1829"));
        check(!verify(stale.replace("300000","120000"),"1829",proc,"100","1829"));
        check(!verify(stale.replace("ready","unknown"),"1829",proc,"100","1829"));
        check(!verify(stale,"1829",proc,"0","1829"));
        check(!verify(stale,"1829",proc,"-1","1829"));
        check(!verify(stale,"1829",stat(Long.MAX_VALUE),"100","1829"));
        check(!verify(stale,"1829","broken","100","1829"));
        check(!verify("","1829",proc,"100","1829"));
        check(!verify(null,"1829",proc,"100","1829"));
        NearbyState off=new NearbyState("off","all","fruit seed","","1829",WALL,stale,true);
        check(!off.fresh && !off.guard.supported && off.canToggle(false));
        check(!off.canToggle(true));
        check(off.summary().contains("可先開啟"));
        NearbyState armed=new NearbyState("armed","all","fruit seed","","1829",WALL,stale,true);
        check(armed.canToggle(false) && armed.summary().contains("等待遊戲就緒"));
        NearbyState missing=new NearbyState("off","all","fruit seed","","",WALL,"",false);
        check(missing.canToggle(false)); // Desired ON can be saved before runtime is ready.
        check(new NearbyState("armed","all","fruit seed","","",WALL,"",false).canToggle(false)); // can turn off
        check(!new NearbyState("batch","all","fruit seed","","1829",WALL,stale,true).canToggle(false));
        check(!new NearbyState("armed","farm","fruit seed","","1829",WALL,stale,true).canToggle(false));
        String cap="v1\t"+(WALL-150000)+"\t1829\t"+(BOOT-150000)+"\tnearby-v2-time-only\t300000";
        String noFix=row(WALL-150000,0).replace("ready","stale-game-location");
        check(!verify(noFix,"1829",proc,"100","1829")); // actual old-core defect: fix time is not engine time
        check(NearbyCapability.verifiedEngine(cap,noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!new NearbyGuard(noFix,"1829",WALL).supported); // stale location never becomes readiness
        check(NearbyCapability.verifiedEngine(cap,noFix.replace("stale-game-location","waiting-game-location"),"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap,noFix,"1829",stat(999999),"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap,noFix,"1829",proc,"100","1830",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace("1829","1830"),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace("300000","120000"),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace("nearby-v2-time-only","unknown"),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace("v1\t","v2\t"),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap+"\textra",noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace(""+(BOOT-150000),""+(BOOT+1)),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace(""+(BOOT-150000),"0"),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace(""+(WALL-150000),""+(WALL+1)),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap.replace(""+(WALL-150000),""+(WALL-86400000)),noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap,noFix,"1829",proc,"0","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine(cap,noFix,"1829",stat(Long.MAX_VALUE),"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine("malformed",stale,"1829",proc,"100","1829",WALL,BOOT)); // no downgrade on corrupt new report
        check(NearbyCapability.verifiedEngine("",stale,"1829",proc,"100","1829",WALL,BOOT));
        check(!NearbyCapability.verifiedEngine("",noFix,"1829",proc,"100","1829",WALL,BOOT));
        check(new NearbyState("armed","all","fruit seed","","1829",WALL,noFix,true).compactSummary().contains("等待遊戲就緒"));
        check(new NearbyState("armed","all","fruit seed","","1829",WALL,row(WALL-1,0).replace("ready","waiting-game-location"),true).guard.state.equals("waiting-game-location"));
        System.out.println("PASS: "+checks+" nearby background capability and waiting checks");
    }
}

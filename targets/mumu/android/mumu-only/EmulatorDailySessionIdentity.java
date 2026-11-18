package dev.ody.pikmincontrol;

/** Passive boot/PID/account/core-session identity. No steps or GPS/HC effects. */
final class EmulatorDailySessionIdentity {
    static String command() {
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        String session=EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048);
        String caps=EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768);
        return ProbeSnapshot.command(identity,session,caps,caps,session,identity);
    }
    static void require(android.content.Context context,EmulatorDailyRunner.Binding held,CorePair pair)throws Exception {
        String pid=Integer.toString(held.pid);
        if(!pid.equals(CoreUpdater.runtimePid(context,pair)))bad();
        decode(EmulatorSavedDailyRead.identity(),held,pair,System.currentTimeMillis());
        if(!pid.equals(CoreUpdater.runtimePid(context,pair)))bad();
    }
    static void decode(String raw,EmulatorDailyRunner.Binding held,CorePair pair,long now) {
        if(held==null||pair==null||!pair.unified||!held.coreSha.equals(pair.sha)||now<=0||
           raw==null||raw.length()>80000||raw.indexOf('\r')>=0)bad();
        String[] p=EmulatorOwnedPetalGateway.sections(raw,6);
        String pid=Integer.toString(held.pid),identity=pid+"\n"+held.boot+"\n";
        if(!identity.equals(p[0])||!identity.equals(p[5]))bad();
        for(int i:new int[]{1,4})if(!held.account.equals(SessionIdentity.parse(p[i],pid,pid,now)))bad();
        for(int i:new int[]{2,3}) {
            CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(p[i]),pid,pair.version,pair.code,now);
            if(!caps.ok()||!caps.identityReady()||!caps.domainReady()||caps.sessionEpoch!=held.sessionEpoch)bad();
        }
    }
    private static void bad(){throw new IllegalStateException("Fresh independently held boot/PID/account/core session required");}
}

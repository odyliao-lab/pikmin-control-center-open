package dev.ody.pikmincontrol;

/** Existing bootstrap check UI only; never creates a runner Binding or grants ready. */
final class EmulatorGameCounterDiagnostic {
    static String check(android.content.Context context,String heldPid,CorePair pair) {
        try {
            EmulatorRuntime.require();
            if(!"0.10.22".equals(pair.version) || pair.code!=900126 || heldPid==null ||
                    !heldPid.matches("[1-9][0-9]{0,8}"))throw new IllegalArgumentException("Paired counter core required");
            String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
            String root="/data/user/0/com.nianticlabs.pikmin/files/";
            String[] p=EmulatorOwnedPetalGateway.sections(GpsModuleGateway.farmRead(ProbeSnapshot.command(
                identity,"cat "+root+"automation_session.tsv","cat "+root+"core_capabilities.tsv",
                EmulatorGameDailyStepsGateway.command(),identity)),5);
            long now=System.currentTimeMillis();
            String[] id=p[0].split("\n",-1);
            if(id.length!=3 || !id[0].equals(heldPid) || !id[2].isEmpty() ||
                    !id[1].matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") || !p[0].equals(p[4]))
                throw new IllegalArgumentException("Game changed during counter read");
            String account=SessionIdentity.parse(p[1],heldPid,heldPid,now);
            CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(p[2]),heldPid,pair.version,pair.code,now);
            if(account.isEmpty() || !caps.ok() || !caps.identityReady() || !caps.domainReady() || caps.sessionEpoch<=0)
                throw new IllegalArgumentException("Fresh account/session required");
            EmulatorGameDailySteps.Counter counter=EmulatorGameDailySteps.counter(p[3],Integer.parseInt(heldPid),caps.sessionEpoch,account,now);
            if(!heldPid.equals(CoreUpdater.runtimePid(context,pair)))throw new IllegalArgumentException("Core changed after counter read");
            // No account digest, coordinates, raw report or control intent in UI/log.
            String summary="遊戲今日步數："+counter.today+" · 遊戲日期 "+counter.day;
            android.util.Log.i("PccGameDailySteps",summary+" · captured="+counter.captured);
            return "\n"+summary+"\n唯讀採計；不代表每日排程或育苗額度已驗收。";
        }catch(Exception unknown){return "\n遊戲今日步數：未知（需要同程序、新鮮且已確認的遊戲回報）。";}
    }
}

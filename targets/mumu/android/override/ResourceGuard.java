package dev.ody.pikmincontrol;
import android.content.Context;

final class ResourceGuard {
    static boolean enabled(Context c){return c.getSharedPreferences("resource_guard",0).getBoolean("strict",false);}
    static void enabled(Context c,boolean value){c.getSharedPreferences("resource_guard",0).edit().putBoolean("strict",value).commit();}
    static ResourceState read() throws Exception {
        String[] p=ProbeSnapshot.sections(GpsModuleGateway.farmRead(ProbeSnapshot.command(
                "pidof com.nianticlabs.pikmin || true",
                "cat /data/user/0/com.nianticlabs.pikmin/files/automation_resources.tsv 2>/dev/null || true",
                "pidof com.nianticlabs.pikmin || true")),3);
        return ResourceState.parse(p[1],p[0].trim(),p[2].trim(),System.currentTimeMillis());
    }
    static void check(Context c,boolean seeds,boolean planting) throws Exception {
        if(!enabled(c)||(!seeds&&!planting))return;
        String reason;
        try {reason=read().blocker(seeds,planting);}catch(Exception unknown){reason="資源資料待確認";}
        if(!reason.isEmpty()) {
            AutomationAlerts.record(c,planting?GpsWorkLock.FLOWER_FARM:GpsWorkLock.BATCH,"resource");
            throw new IllegalStateException("資源保護暫停："+reason+"；請整理資源或確認原生資料後再開始");
        }
    }
}

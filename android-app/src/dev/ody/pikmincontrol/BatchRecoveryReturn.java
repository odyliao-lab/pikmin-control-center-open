package dev.ody.pikmincontrol;

/** MIUI may silently deny an app-origin return even with overlay permission. */
final class BatchRecoveryReturn {
    static String command(String pkg,int uid) {
        if(uid<10000 || uid>=100000 || !("dev.ody.pikmincontrol".equals(pkg)||"dev.ody.pikmincontrol.mumu".equals(pkg)))
            throw new IllegalArgumentException("只允許主空間控制中心返回自己的畫面");
        return "am start -W --user 0 -n "+pkg+"/dev.ody.pikmincontrol.MainActivity --activity-clear-top --activity-single-top --ez open_dispatch true";
    }
    static boolean accepted(BoundedCommand.Result r,String pkg) {
        if(r==null || !r.ok || r.timedOut || r.truncated || !("dev.ody.pikmincontrol".equals(pkg)||"dev.ody.pikmincontrol.mumu".equals(pkg)))return false;
        java.util.List<String> lines=java.util.Arrays.asList(r.text.replace("\r","").split("\n"));
        return lines.contains("Status: ok") && (lines.contains("Activity: "+pkg+"/dev.ody.pikmincontrol.MainActivity")
            || ("dev.ody.pikmincontrol".equals(pkg)&&lines.contains("Activity: "+pkg+"/.MainActivity")));
    }
}

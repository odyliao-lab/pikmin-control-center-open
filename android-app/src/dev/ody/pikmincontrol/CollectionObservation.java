package dev.ody.pikmincontrol;

/** Fresh transaction read, not a cache and not atomic with native writers. */
final class CollectionObservation {
    final String pid;
    final String[] controls;
    private CollectionObservation(String pid,String[] controls) { this.pid=pid; this.controls=controls; }
    static String command() {
        return ProbeSnapshot.command(OwnerGameProcess.command(),
            "cat /data/user/0/com.nianticlabs.pikmin/files/core_runtime.tsv 2>/dev/null || true",
            "cat /data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv 2>/dev/null || true",
            control("/data/local/tmp/pikmin-return-mode.txt"),
            control("/data/adb/modules/pikmin_nectar_rpc/nectar_control_mode.txt"),
            control("/data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt"),
            OwnerGameProcess.command());
    }
    private static String control(String path) {
        return "if test -e " + path + "; then cat " + path + " 2>/dev/null || echo __PCC_CONTROL_ERROR__; fi";
    }
    static CollectionObservation parse(BoundedCommand.Result read,CorePair pair,long now) {
        String[] s=ProbeSnapshot.checkedSections(read,7);
        for(int i=3;i<=5;i++)if(s[i].contains("__PCC_CONTROL_ERROR__"))
            throw new IllegalStateException("控制檔讀取未完成");
        String before=s[0].trim(),after=s[6].trim();
        String pid=pair.runtime(s[1],before,after,now)&&pair.gpsRuntime(s[2],before,after,now)?before:"";
        return new CollectionObservation(pid,new String[]{s[3],s[4],s[5]});
    }
}

package dev.ody.pikmincontrol;
import android.content.Context;

final class HcCompatGateway {
    static final String FILE="/data/local/tmp/pikmin-hc-compat.txt";
    private static final String REPORT="/data/user/0/com.nianticlabs.pikmin/files/hc_compat_status.tsv";
    static String readControl(){return "if test -e "+FILE+" || test -L "+FILE+"; then test ! -L "+FILE+" && test -f "+FILE+" && head -c 16 "+FILE+"; else printf off; fi";}
    static synchronized HcCompatState read(int sdk)throws Exception {
        if(!HcCompatPolicy.allowedSdk(sdk))return new HcCompatState(sdk,"off","","","",0);
        String[] rows=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(OwnerGameProcess.command(),readControl(),
                "if test -e "+REPORT+"; then test ! -L "+REPORT+" && test -f "+REPORT+" && head -c 1024 "+REPORT+"; fi",OwnerGameProcess.command()),5,4096),4);
        return new HcCompatState(sdk,rows[1].trim(),rows[2],rows[0].trim(),rows[3].trim(),System.currentTimeMillis());
    }
    static synchronized void set(Context context,boolean enabled,int sdk)throws Exception {
        if(enabled&&!HcCompatPolicy.allowedSdk(sdk))throw new IllegalStateException("Android 14 以上不需要 HC 相容處理");
        if(UpdateInstaller.pending(context)||WorkStatus.stoppingAll)throw new IllegalStateException("正在更新或停止工作，請稍後再試");
        if(enabled){
            if(!BoundedCommand.checkedRoot("pm list packages --user 0 dev.ody.pikmincontrol.hccompat",5,1024).trim().isEmpty())
                throw new IllegalStateException("請先備份並移除獨立 HC 相容套件，再重開遊戲");
            CorePair pair=CoreUpdater.pair(context);CoreUpdater.requireFiles(context,pair);
            // HC is a Java feature: paused Unity inventory/GPS callbacks are not its readiness authority.
            String[] loader=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(OwnerGameProcess.command(),
                    "cat /data/user/0/com.nianticlabs.pikmin/files/unified_runtime.tsv 2>/dev/null || true",OwnerGameProcess.command()),5,2048),3);
            if(!HcCompatState.loaderMatches(loader[1],loader[0].trim(),loader[2].trim(),pair.version,pair.code,System.currentTimeMillis())||!read(sdk).fresh)
                throw new IllegalStateException("請先安裝配對核心並開啟遊戲，核對 HC 核心回報");
        }
        String mode=enabled?"on":"off";
        String command="set -eu; umask 077; "+(enabled?"hc_legacy=$(pm list packages --user 0 dev.ody.pikmincontrol.hccompat); test -z \"$hc_legacy\" || { printf '請先備份並移除獨立 HC 相容套件，再重開遊戲'; exit 1; }; ":"")
                +"test ! -L "+FILE+"; if test -e "+FILE+"; then test -f "+FILE+"; fi; hc_tmp=$(mktemp /data/local/tmp/pcc-hc.XXXXXX); trap 'rm -f -- \"$hc_tmp\"' EXIT; printf '%s\\n' "+mode+" > \"$hc_tmp\"; chown 0:0 \"$hc_tmp\"; chmod 0644 \"$hc_tmp\"; mv -fT \"$hc_tmp\" "+FILE+"; "+readControl();
        if(!mode.equals(BoundedCommand.checkedRoot(command,6,1024).trim()))throw new IllegalStateException("HC 相容設定未確認保存");
    }
}

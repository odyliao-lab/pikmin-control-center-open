package dev.ody.pikmincontrol;

/** No gameplay commands, inventory writes, module installation or process restarts. */
final class NectarStockGateway {
    private static final String CONTROL="/data/local/tmp/pikmin-nectar-stock-preview.txt";
    private static final String REPORT="/data/user/0/com.nianticlabs.pikmin/files/nectar_stock_preview_status.tsv";
    static SeedPreviewState read() throws Exception {
        String[] rows=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(
            OwnerGameProcess.command(),
            "if test -e "+CONTROL+"; then test ! -L "+CONTROL+" && test -f "+CONTROL+" && head -c 32 "+CONTROL+"; else echo off; fi",
            "if test -e "+REPORT+"; then test ! -L "+REPORT+" && test -f "+REPORT+" && head -c 1024 "+REPORT+"; fi",
            OwnerGameProcess.command()),4,4096),4);
        if(!rows[1].trim().equals("on")&&!rows[1].trim().equals("off"))throw new IllegalStateException("精華花瓣庫存開關無法核對");
        return new SeedPreviewState(rows[1],rows[2],rows[0].trim(),rows[3].trim(),System.currentTimeMillis());
    }
    static String setCommand(boolean enabled){
        String mode=enabled?"on":"off";
        return "set -eu; test ! -L "+CONTROL+"; umask 077; "
            +"pcc_seed_tmp=$(mktemp /data/local/tmp/pcc-nectar-stock.XXXXXX); "
            +"trap 'rm -f -- \"$pcc_seed_tmp\"' EXIT; "
            +"printf '%s\\n' '"+mode+"' > \"$pcc_seed_tmp\"; chown 0:0 \"$pcc_seed_tmp\"; chmod 0644 \"$pcc_seed_tmp\"; "
            +"mv -fT \"$pcc_seed_tmp\" "+CONTROL+"; test \"$(cat "+CONTROL+")\" = '"+mode+"'; echo __PCC_NECTAR_STOCK_SAVED__";
    }
    static void set(boolean enabled)throws Exception {
        String reply=BoundedCommand.checkedRoot(setCommand(enabled),4,1024).trim();
        if(!reply.equals("__PCC_NECTAR_STOCK_SAVED__"))throw new IllegalStateException("精華花瓣庫存開關未確認保存");
    }
}

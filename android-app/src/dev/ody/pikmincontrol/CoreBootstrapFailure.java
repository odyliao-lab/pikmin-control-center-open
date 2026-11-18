package dev.ody.pikmincontrol;

/** A bounded, read-only explanation at startup timeout, never a retry/RPC command. */
final class CoreBootstrapFailure {
    static String explain(CoreCapabilities k) {
        if(k==null||!k.ok())return "";
        switch(k.stage) {
            case "lib_wait": return "核心仍在低頻等待遊戲程式庫，尚未可用；請稍後重試";
            case "lib_timeout": return "核心載入重試已逾時；可開啟程序守護有限次復原，或重開遊戲";
            case "symbols_missing": return "核心無法解析遊戲程式庫；可開啟程序守護復原，持續發生請匯出診斷";
            case "incompatible": return "遊戲版本與核心不相容；請更新配對核心，不要反覆重啟";
            default: return "";
        }
    }
    static String read(android.content.Context c) {
        try {
            CorePair pair=CoreUpdater.pair(c);
            String[] s=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(
                OwnerGameProcess.command(),
                "cat /data/user/0/com.nianticlabs.pikmin/files/core_capabilities.tsv 2>/dev/null || true",
                OwnerGameProcess.command()),3,8192),3);
            if(!s[0].trim().equals(s[2].trim()))return "";
            return explain(CoreCapabilities.parse(ReadinessSample.Read.ok(s[1]),s[0].trim(),pair.version,pair.code,System.currentTimeMillis()));
        } catch(Exception unreadable){return "";}
    }
}

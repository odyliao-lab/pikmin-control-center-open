package dev.ody.pikmincontrol;

final class PlanterGateway {
    private static final String CONTROL = "/data/local/tmp/pikmin-planter-mode.txt";
    private static final String STEPS = "/data/local/tmp/pikmin-planter-steps.txt";
    private static final String FILES = "/data/user/0/com.nianticlabs.pikmin/files/";

    static PlanterState read() throws Exception {
        return snapshot().state;
    }

    static final class Snapshot {
        final PlanterState state;
        final PlanterRecovery recovery;
        Snapshot(String[] rows,long now){
            if(rows==null||rows.length!=7)throw new IllegalStateException("育苗回報未完整讀取");
            for(String row:rows)if(row.contains("__PCC_PLANTER_READ_ERROR__")
                    ||row.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>262144)
                throw new IllegalStateException("育苗回報無法讀取或過大，未當成缺少核心");
            String pid=rows[0].trim();
            if(!pid.equals(rows[6].trim()))throw new IllegalStateException("核對期間遊戲程序已變更，請更新育苗狀態");
            state=new PlanterState(rows[1].trim(),rows[2],rows[3],rows[4],pid,now);
            recovery=new PlanterRecovery(rows[5],pid,now);
        }
    }

    private static String file(String path){
        return "if test -e "+path+"; then test -f "+path+" && test -r "+path
            +" && head -c 262145 "+path+" 2>/dev/null || echo __PCC_PLANTER_READ_ERROR__; fi";
    }

    static String snapshotCommand() {
        return ProbeSnapshot.command(OwnerGameProcess.command(),file(CONTROL),
            file(FILES+"planter_status.tsv"),file(FILES+"planter_inventory.tsv"),
            "if test -e "+FILES+"planter_history.tsv; then tail -n 120 "+FILES
                +"planter_history.tsv 2>/dev/null || echo __PCC_PLANTER_READ_ERROR__; fi",
            file(FILES+"planter_recovery.tsv"),OwnerGameProcess.command());
    }

    /** One bounded shell with PID bookends, not an atomic native inventory snapshot. */
    static Snapshot snapshot() throws Exception {
        return new Snapshot(ProbeSnapshot.checkedSections(BoundedCommand.root(snapshotCommand(),4,524288),7),System.currentTimeMillis());
    }

    static void setMode(String mode) throws Exception {
        if (!"off".equals(mode) && !"pluck".equals(mode) && !"auto".equals(mode))
            throw new IllegalArgumentException("Unsupported planter mode");
        // Desired mode may be saved before the first native sample. The paired core
        // and its per-action inventory/capacity gates remain mandatory at the caller.
        root("printf '" + mode + "\\n' > " + CONTROL + ".tmp && chmod 0644 " + CONTROL +
                ".tmp && mv " + CONTROL + ".tmp " + CONTROL);
    }

    static int steps() throws Exception {
        return PlanterSteps.parse(root("if [ -e " + STEPS + " ]; then cat " + STEPS
                + "; else printf '1000 3000 5000'; fi").trim());
    }

    static PlanterRecovery recovery() throws Exception {
        String[] rows=ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(
            OwnerGameProcess.command(),file(FILES+"planter_recovery.tsv"),
            OwnerGameProcess.command()),3,32768),3);
        if(!rows[0].trim().equals(rows[2].trim())||rows[1].contains("__PCC_PLANTER_READ_ERROR__"))
            throw new IllegalStateException("育苗恢復回報讀取失敗或遊戲程序已更換");
        return new PlanterRecovery(rows[1],rows[0].trim(),System.currentTimeMillis());
    }

    static void recover() throws Exception {
        requestRecovery();
    }

    static PlanterRecovery requestRecovery() throws Exception {
        Snapshot observed=snapshot();
        if (!observed.state.fresh) throw new IllegalStateException("請先開啟遊戲，更新完整育苗狀態");
        PlanterRecovery capability = observed.recovery;
        if (!capability.supported) throw new IllegalStateException(capability.message());
        if ("checking-inventory".equals(capability.result)) throw new IllegalStateException("恢復核對中，請留在遊戲等待");
        String pid=root(OwnerGameProcess.command()).trim();
        if(!pid.equals(capability.pid))throw new IllegalStateException("遊戲程序已更換，請更新育苗狀態後重試");
        String ledger=root("if [ -e " + FILES + "planter_pending.tsv ]; then cat " + FILES + "planter_pending.tsv; else echo MISSING; fi").trim();
        String nonce=java.util.UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        String value=PlanterRecovery.request(nonce,pid,now,ledger);
        String path="/data/local/tmp/pikmin-planter-recover.txt";
        // Validated tokens only. Never delete the game's pending ledger or change mode here.
        root("printf '%s' '"+value+"' > "+path+".tmp && chmod 0644 "+path+".tmp && mv "+path+".tmp "+path);
        return new PlanterRecovery("v1\t"+now+"\t"+pid+"\t"+nonce+"\tchecking-inventory",pid,now);
    }

    /** Refresh only the originally confirmed pending record; never retarget a new one. */
    static PlanterRecovery requestRecovery(PlanterRetirement.Basis expected,long epoch,long login) throws Exception {
        PlanterRecovery capability=recovery();
        if(!capability.supported||!expected.pid.equals(capability.pid))
            throw new IllegalStateException("育苗核對來源已變更，未重送請求");
        if("checking-inventory".equals(capability.result))
            throw new IllegalStateException("恢復核對中，請留在遊戲等待");
        String nonce=java.util.UUID.randomUUID().toString();long now=System.currentTimeMillis();
        BoundedCommand.Result result=BoundedCommand.root(PlanterRecoveryRequest.command(expected,nonce,now,epoch,login),6,4096);
        if(!PlanterRecoveryRequest.accepted(result))
            throw new IllegalStateException("原育苗紀錄或遊戲狀態已變更；核對請求未確認，不自動重送");
        return new PlanterRecovery("v1\t"+now+"\t"+expected.pid+"\t"+nonce+"\tchecking-inventory",expected.pid,now);
    }

    static void setSteps(int mask) throws Exception {
        String value = PlanterSteps.encode(mask);
        root("printf '" + value + "\\n' > " + STEPS + ".tmp && chmod 0644 " + STEPS
                + ".tmp && mv " + STEPS + ".tmp " + STEPS);
    }

    private static String root(String command) throws Exception {
        BoundedCommand.Result result=BoundedCommand.root(command,3,262144);
        if(!result.ok||result.truncated)throw new IllegalStateException("育苗資料讀取失敗、逾時或過大，請稍後重試");
        return result.text;
    }
}

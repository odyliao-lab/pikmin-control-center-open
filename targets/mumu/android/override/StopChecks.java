package dev.ody.pikmincontrol;

import java.util.concurrent.TimeUnit;

/** Read-back is control acknowledgement, NOT cancellation of native requests already sent. */
final class StopChecks {
    interface Action { void run() throws Exception; }
    static void attempt(StringBuilder errors, String label, Action action) {
        try { action.run(); }
        catch (Exception error) {
            if (errors.length() > 0) errors.append("；");
            errors.append(label).append("：").append(error.getClass().getSimpleName());
            String detail=error.getMessage();
            if(detail!=null&&!detail.trim().isEmpty()) {
                detail=detail.replace('\n',' ').replace('\r',' ').trim();
                if(detail.length()>120)detail=detail.substring(0,120)+"…";
                errors.append("（").append(detail).append("）");
            }
        }
    }
    static void dispatch() throws Exception {
        verify("test \"$(cat /data/local/tmp/pikmin-dispatch-mode.txt)\" = off"
                + " && test ! -e /data/local/tmp/pikmin-dispatch-target.txt"
                + " && test ! -e /data/local/tmp/pikmin-dispatch-ready.tsv");
    }
    static void farm() throws Exception {
        verify("test \"$(cat /data/local/tmp/pikmin-dispatch-mode.txt)\" = off"
                + " && test \"$(cat /data/local/tmp/pikmin-dispatch-kinds.txt)\" = all"
                + " && test \"$(cat /data/local/tmp/pikmin-planting-mode.txt)\" = off"
                + " && test \"$(cat /data/local/tmp/pikmin-flower-farm-mode.txt)\" = off");
    }
    static void all() throws Exception {
        dispatch(); farm();
        verify("test \"$(cat /data/user/0/com.nianticlabs.pikmin/files/nectar_rpc_mode.txt)\" = diag"
                + " && test \"$(cat /data/adb/modules/pcc_emulator_bridge_canary/nectar_control_mode.txt)\" = diag"
                + " && test \"$(cat /data/local/tmp/pikmin-return-mode.txt)\" = off"
                + " && test \"$(cat /data/local/tmp/pikmin-planter-mode.txt)\" = off");
    }
    private static void verify(String command) throws Exception {
        Process p = new ProcessBuilder("su", "-mm", "-c", command).redirectErrorStream(true).start();
        try {
            if (!p.waitFor(8, TimeUnit.SECONDS)) throw new IllegalStateException("停止確認逾時");
            if (p.exitValue() != 0) throw new IllegalStateException("停止控制未確認");
        } finally { p.destroy(); }
    }
}

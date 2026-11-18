package dev.ody.pikmincontrol;

/** Exposes the exact production shell to the isolated filesystem regression runner. */
public final class NectarFirstInstallStopTest {
    public static void main(String[] args) {
        if (args.length == 1) {
            if ("stop".equals(args[0])) System.out.print(NectarModuleGateway.controlCommand(false));
            else if ("check".equals(args[0])) System.out.print(NectarModuleGateway.stoppedCommand());
            else if ("start".equals(args[0])) System.out.print(NectarModuleGateway.controlCommand(true));
            else throw new AssertionError("unknown operation");
            return;
        }
        StringBuilder errors = new StringBuilder();
        StopChecks.attempt(errors, "精華", () -> { throw new java.io.IOException("root denied"); });
        StopChecks.attempt(errors, "停止回讀", () -> { throw new IllegalStateException("停止控制未確認"); });
        if (!errors.toString().contains("root denied") || !errors.toString().contains("停止控制未確認"))
            throw new AssertionError("Stop failures must remain visible");
        System.out.println("PASS nectar stop error propagation; generated commands verified by shell runner");
    }
}

package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Wiring contracts only; fault injection and Android service life remain device acceptance. */
public final class ProcessRecoveryWiringTest {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        Path src=Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent().resolve("src/dev/ody/pikmincontrol");
        String guard=read(src,"GamePidRecoveryService"),restore=read(src,"SettingsRestoreJob"),monitor=read(src,"CollectionMonitorService");
        check(guard.contains("reportRead(\"core_capabilities.tsv\")") && guard.contains("checkedSections(read,7)"),"PID sandwich includes capabilities");
        check(guard.contains("!residualWorkSafe()") && guard.contains("pcc-route-owner.done.$t"),"residual work requires watchdog retirement proof");
        check(!guard.contains("NearbyWorkState.discard") && !guard.contains("WorkStatus.recovered"),"guard must not erase unresolved work");
        check(restore.contains("pid.equals(CoreUpdater.runtimePid(c,pair))") && restore.contains("feeding && choices"),"restore acknowledged only after same paired PID and all writes");
        check(monitor.contains("SettingsRestoreJob.restoreForPid") && monitor.contains("signal.wait(delay)") && monitor.contains("cadence.next("),"bounded adaptive cadence");
        check(monitor.contains("if(SettingsRestoreJob.restoreForPid(this,pid))settingsPid=pid;") && monitor.contains("if(!stopped && !rebindPid.isEmpty())"),"failed settings restore is retried; destroyed service cannot restore");
        String feeding=read(src,"FeedingGateway"),updater=read(src,"CoreUpdater");
        check(updater.contains("FeedingGateway.requirePausedForInstall()")&&!updater.contains("if(FeedingGateway.load(c).enabled)"),"install checks actual pause, not preserved intent");
        check(feeding.contains("FeedingConfig.parse(root(\"cat \"+CONTROL))")&&feeding.contains("actual.enabled||actual.collect"),"strict full native config must acknowledge both switches off");
        check(read(src,"CoreMigrationRootPort").contains("FeedingGateway.requirePausedForInstall()"),"migration rechecks the same actual pause, not saved intent");
        System.out.println("PASS ProcessRecoveryWiring: preserved uncertain ledgers, retired watchdog gate, acknowledged settings; not Android fault acceptance");
    }
    static String read(Path src,String name)throws Exception{return new String(Files.readAllBytes(src.resolve(name+".java")),StandardCharsets.UTF_8);}
}

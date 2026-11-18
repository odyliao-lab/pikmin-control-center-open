package dev.ody.pikmincontrol;

public final class JoystickStartupPolicyTest {
    private static int checks;
    private static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) throws Exception {
        check(JoystickStartupPolicy.thermalAllows(0));
        check(JoystickStartupPolicy.thermalAllows(1));
        check(JoystickStartupPolicy.thermalAllows(2));
        check(!JoystickStartupPolicy.thermalAllows(3));
        check(!JoystickStartupPolicy.thermalAllows(4));
        check(!JoystickStartupPolicy.thermalAllows(6));
        check(!JoystickStartupPolicy.thermalAllows(-1));
        check(JoystickStartupPolicy.retry(true, 1, true));
        check(JoystickStartupPolicy.retry(true, 2, true));
        check(!JoystickStartupPolicy.retry(true, 3, true));
        check(!JoystickStartupPolicy.retry(true, 4, true));
        check(!JoystickStartupPolicy.retry(true, 0, true));
        check(!JoystickStartupPolicy.retry(false, 1, true));
        check(!JoystickStartupPolicy.retry(true, 1, false));
        check(JoystickStartupPolicy.recover(true, false, "", 0, 0));
        check(!JoystickStartupPolicy.recover(false, false, "", 0, 0));
        check(!JoystickStartupPolicy.recover(true, true, "queued", 0, 999999));
        check(!JoystickStartupPolicy.recover(true, false, "checking", 1, 89999));
        check(!JoystickStartupPolicy.recover(true, false, "checking", 1, -1));
        check(JoystickStartupPolicy.recover(true, false, "checking", 1, 90000));
        check(JoystickStartupPolicy.recover(true, false, "starting", 2, 90000));
        check(JoystickStartupPolicy.recover(true, false, "queued", 0, 90000));
        check(JoystickStartupPolicy.recover(true, false, "retry", 2, 90000));
        check(!JoystickStartupPolicy.recover(true, false, "retry", 3, 90000));
        check(!JoystickStartupPolicy.recover(true, false, "ready", 1, 999999));
        check(!JoystickStartupPolicy.recover(true, false, "failed", 1, 999999));
        check(!JoystickStartupPolicy.recover(true, false, "cancelled", 1, 999999));
        check(JoystickStartupPolicy.recover(true, false, "deferred-core", 0, 0));
        check(!JoystickStartupPolicy.recover(false, false, "deferred-core", 0, 999999));
        check(!JoystickStartupPolicy.recover(true, true, "deferred-core", 0, 999999));
        check(!JoystickStartupPolicy.recover(true, false, "deferred-core", 3, 999999));
        check(JoystickStartupPolicy.legacyCoreWait("failed", 0, "核心升級準備中，暫停新GPS工作；本輪已結束"));
        check(JoystickStartupPolicy.legacyCoreWait("failed", 0, "核心更新待重開／核對；本輪已結束"));
        check(!JoystickStartupPolicy.legacyCoreWait("failed", 1, "核心升級準備中，暫停新GPS工作；本輪已結束"));
        check(!JoystickStartupPolicy.legacyCoreWait("cancelled", 0, "核心升級準備中，暫停新GPS工作；本輪已結束"));
        check(!JoystickStartupPolicy.legacyCoreWait("failed", 0, "Root 指令失敗，請確認授權；本輪已結束"));
        check(JoystickStartupPolicy.legacyWorkWait("failed", 0, "GPS 控制正由「領取設定同步」執行中持有；本輪已結束"));
        check(!JoystickStartupPolicy.legacyWorkWait("failed", 1, "GPS 控制正由「領取設定同步」執行中持有；本輪已結束"));
        check(!JoystickStartupPolicy.legacyWorkWait("cancelled", 0, "GPS 控制正由「領取設定同步」執行中持有；本輪已結束"));
        check(!JoystickStartupPolicy.legacyWorkWait("failed", 0, "GPS 控制正由「未知工作」執行中持有；本輪已結束"));
        check(!JoystickStartupPolicy.legacyWorkWait("failed", 0, "其他或未知工具正在控制 GPS，不接管；本輪已結束"));
        check(JoystickStartupPolicy.recover(true, false, "deferred-work", 0, 0));
        check(!JoystickStartupPolicy.recover(true, false, "deferred-work", 3, 999999));
        check(JoystickStartupPolicy.recover(true, false, "deferred-work", 0, 90000));
        java.nio.file.Path src=java.nio.file.Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent().resolve("src/dev/ody/pikmincontrol");
        String startup=new String(java.nio.file.Files.readAllBytes(src.resolve("JoystickStartup.java")),java.nio.charset.StandardCharsets.UTF_8);
        String main=new String(java.nio.file.Files.readAllBytes(src.resolve("MainActivity.java")),java.nio.charset.StandardCharsets.UTF_8);
        String restore=new String(java.nio.file.Files.readAllBytes(src.resolve("SettingsRestoreJob.java")),java.nio.charset.StandardCharsets.UTF_8);
        check(startup.indexOf("if (coreWaiting(c)) {")<startup.indexOf("pendingToken = UUID.randomUUID()"));
        check(startup.contains("catch (GpsWorkLock.CoreNotReady error)"));
        check(startup.contains("catch (GpsWorkLock.Busy error)"));
        check(startup.indexOf("while (!acquired)") < startup.indexOf("int attempt ="));
        check(startup.contains("SystemClock.elapsedRealtime() + 60_000"));
        check(startup.indexOf("int attempt =") < startup.indexOf("checkAllowed(c, s)"));
        check(startup.contains("!coreWaiting(c) && !GpsWorkLock.observedAnyHeld(c)"));
        check(main.contains("CoreInstallWizard.preparing(this,false);JoystickStartup.afterCoreReady(this)"));
        check(main.contains("CoreInstallWizard.preparing(this,false);\n                    JoystickStartup.afterCoreReady(this)") || main.contains("CoreInstallWizard.preparing(this,false);\r\n                    JoystickStartup.afterCoreReady(this)"));
        check(restore.indexOf("GpsWorkLock.release")<restore.indexOf("JoystickStartup.afterCoreReady"));
        // Existing own provider is observed, not stopped, relaunched, or teleported.
        check(startup.contains("if (!sample.own && !requested)"));
        check(!startup.contains("am force-stop") && !startup.contains(".teleport("));
        System.out.println("PASS: " + checks + " bounded retry/recovery checks");
    }
}

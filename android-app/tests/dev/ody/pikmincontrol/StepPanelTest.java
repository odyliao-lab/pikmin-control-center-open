package dev.ody.pikmincontrol;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;

/** Offline presentation/contracts only; not Android rendering or HC acceptance. */
public final class StepPanelTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    private static void invalid(String target, String start, String end, String rate, String interval) {
        try { StepPanelModel.settings(target, start, end, rate, interval); throw new AssertionError("accepted invalid input"); }
        catch (IllegalArgumentException expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(StepPanelModel.total(null).equals("尚未讀取"), "unknown is not zero");
        check(StepPanelModel.total(0L).equals("0"), "known zero");
        check(StepPanelModel.hour("8") == 8, "single hour");
        check(StepPanelModel.hourText(485).equals("08"), "hour padded without minutes");
        StepSettings s = StepPanelModel.settings("9000", "22", "08", "200", "60");
        check(s.overnight() && s.target == 9000 && s.intervalMs == 60000, "saved form values");
        check(StepPanelModel.summary(s).contains("跨日"), "overnight copy");
        check(StepPanelModel.summary(StepPanelModel.settings("1", "00", "00", "1", "60")).contains("全天"), "all day");
        check(StepPanelModel.settings("1000000", "23", "00", "100000", "180").extremePreview(), "extreme warning retained");
        check(StepPanelModel.summary(null).contains("勿覆寫"), "unknown configuration protected");
        for (String bad : new String[]{"24", "-1", "8:00", "1.5", "hello", "000"})
            invalid("8000", bad, "22", "130", "60");
        for (String bad : new String[]{"0", "-2", "1000001", "abc", "9223372036854775807"})
            invalid(bad, "08", "22", "130", "60");
        for (String bad : new String[]{"0", "4", "59", "181", "9223372036854775807", "1.5"})
            invalid("8000", "08", "22", "130", bad);
        invalid("8000", "08", "22", "0", "60");
        check(StepSettings.defaults().target == 50000, "new daily target default");
        check(StepSettings.FAST_RATE == 200 && StepSettings.RUN_RATE == 350, "three requested rates");
        check(StepPanelModel.GATE.contains("預設關閉") && StepPanelModel.GATE.contains("授權"), "explicit opt-in gate");
        check(StepPanelModel.result("new-code").startsWith("待核對"), "unknown result not success");
        for (int i = 0; i <= 4; i++) check(MainNavigation.validPage(i), "existing page identity preserved");
        check(MainNavigation.validPage(6) && !MainNavigation.validPage(5) && !MainNavigation.validPage(-1), "step distinct from flower scroll slot");
        int[] old = {1,2,3,4,5,6};
        int[] restored = MainNavigation.restoreScrolls(old);
        check(restored.length == 7 && restored[5] == 6 && restored[6] == 0, "old saved state expanded not lost");
        restored[0] = 100;
        check(old[0] == 1, "defensive copy");
        check(MainNavigation.scrollKey(3,1) == 5 && MainNavigation.scrollKey(6,0) == 6, "no scroll collision");
        check(MainNavigation.restoreScrolls(new int[]{1}).length == 7, "invalid old state ignored");
        Path app = Paths.get(System.getProperty("pcc.fixtures")).toAbsolutePath().getParent().getParent();
        String panel = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/StepPanel.java")), StandardCharsets.UTF_8);
        String activity = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/StepActivity.java")), StandardCharsets.UTF_8);
        for (String forbidden : new String[]{"engine.tick(", "engine.confirmManual(", "engine.confirmCatchUp(", "BoundedCommand", "GpsWorkLock"})
            check(!panel.contains(forbidden), "main page must not perform unsafe path " + forbidden);
        check(panel.contains("engine.configure(next)") && panel.contains("worker.execute("), "settings save connected off UI thread");
        check(panel.contains("if (closed) return;") && panel.contains("worker.shutdown()"), "detached callbacks guarded");
        check(panel.contains("engine.disclose()") && panel.contains("engine.start()") && panel.contains("engine.stop()"), "explicit disclosure and start/stop wired");
        check(panel.contains("StepService.start(") && panel.contains("StepService.signalStop("), "foreground minute writer wired");
        check(panel.contains("StepSyncJob.scheduleRecovery(") && panel.contains("StepSyncJob.cancel("), "recovery scheduler wired");
        check(panel.contains("healthBackground(activity)") && panel.contains("StepPanelModel.GATE"), "background permission and gate shown");
        check(panel.contains("背景執行設定") && panel.contains("ACTION_APPLICATION_DETAILS_SETTINGS"), "background explanation and settings route");
        check(panel.contains("AppButtonStyle.Kind.PRIMARY") && panel.contains("AppButtonStyle.Kind.DANGER")
                && panel.contains("AppButtonStyle.Kind.LINK"), "main steps page uses shared semantic button hierarchy");
        check(activity.contains("AppButtonStyle.Kind.PRIMARY") && activity.contains("AppButtonStyle.Kind.DANGER"),
                "advanced steps page uses shared semantic button hierarchy");
        check(!panel.contains("setBackgroundTintList") && !activity.contains("setBackgroundTintList"),
                "steps pages do not fall back to platform tinted rectangles");
        check(panel.contains("一般") && panel.contains("快走") && panel.contains("奔跑") && !panel.contains("\"慢走\""), "three visible rate choices only");
        for (String forbidden : new String[]{"補走開始 HH:MM", "補走結束 HH:MM", "offset 秒", "補走速率 1–1000", "預覽指定時段補走", "預覽今日至今補走"})
            check(!activity.contains(forbidden), "advanced UI hides technical input " + forbidden);
        check(activity.contains("現在補寫步數（1–350）") && activity.contains("最近一分鐘"), "manual UI is recent and bounded");
        check(activity.contains("一般 130") && activity.contains("快走 200") && activity.contains("奔跑 350"), "catch-up has three explicit rates");
        check(activity.contains("new StepCatchUpRequest(\"recent\"") && activity.contains("補到今日目標"), "catch-up auto backtracks from total");
        String main = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/MainActivity.java")), StandardCharsets.UTF_8);
        String buttons = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/AppButtonStyle.java")), StandardCharsets.UTF_8);
        check(main.contains("tab(\"步數\", STEPS, \"nav_steps\")"), "independent main tab");
        check(main.contains("stepPanel = new StepPanel(this, page)"), "embedded not a detached activity shortcut");
        check(main.contains("MainNavigation.restoreScrolls(positions)"), "saved state migration used");
        check(main.contains("AppButtonStyle.custom(this") && buttons.contains("enum Kind { PRIMARY, SECONDARY, DANGER, LINK }"),
                "steps and existing pages share one button implementation");
        check(buttons.contains("setCornerRadius(radius)") && buttons.contains("setElevation(0)")
                && buttons.contains("enabled ? 1f : 0.42f"), "shared rounded flat disabled-state contract");
        String manifest = new String(Files.readAllBytes(app.resolve("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains(".StepService") && manifest.contains("foregroundServiceType=\"specialUse\"")
                && manifest.contains("PROPERTY_SPECIAL_USE_FGS_SUBTYPE"), "explicit minute writer foreground service exposed");
        check(!manifest.contains("FOREGROUND_SERVICE_HEALTH") && !manifest.contains("foregroundServiceType=\"health\""), "no unrelated health sensor FGS permission");
        check(manifest.contains(".StepSyncJob") && manifest.contains("READ_HEALTH_DATA_IN_BACKGROUND"), "job and HC permissions declared");
        check(manifest.contains("VIEW_PERMISSION_USAGE") && manifest.contains("HEALTH_PERMISSIONS"), "permission rationale route declared");
        System.out.println("PASS StepPanel: " + checks + " checks (offline wiring; not device UI or HC writes)");
    }
}

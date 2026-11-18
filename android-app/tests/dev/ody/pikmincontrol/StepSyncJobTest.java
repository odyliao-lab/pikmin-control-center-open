package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/** Offline source contract. Android JobScheduler behavior remains a device test. */
public final class StepSyncJobTest {
    private static int checks;
    private static void check(boolean ok, String why) { checks++; if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Path app = Paths.get(System.getProperty("pcc.fixtures")).toAbsolutePath().getParent().getParent();
        String job = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/StepSyncJob.java")), StandardCharsets.UTF_8);
        String service = new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/StepService.java")), StandardCharsets.UTF_8);
        check(job.contains("JOB_A = 31610") && job.contains("JOB_B = 31611"), "alternating bounded jobs");
        check(job.contains("MIN_DELAY_MS = 60_000L"), "no sub-minute background loop");
        check(job.contains("RECOVERY_DELAY_MS = 5 * 60_000L"), "job is recovery cadence, not primary minute timer");
        check(job.contains("setPersisted(true)"), "survives reboot by system scheduling");
        check(job.contains("engine.continueAuthorizedRun(epoch)") && job.contains("engine.tickBound(epoch)"), "epoch-bound recovery tick");
        check(job.contains("engine.epochCurrent(epoch)") && job.contains("after.desiredRunning"), "stale or stopped work cannot re-arm");
        check(job.contains("!StepService.isServing(epoch)"), "active foreground service suppresses recovery write");
        check(job.contains("scheduler.cancel(JOB_A)") && job.contains("scheduler.cancel(JOB_B)"), "stop cancels both slots");
        check(!job.contains("startForeground") && !job.contains("while ("), "recovery job itself stays bounded");
        check(service.contains("engine.nextTickDelayMs()") && service.contains("Thread.sleep(wait)")
                && !service.contains("Thread.sleep(250L)"), "foreground writer sleeps to persisted deadline without polling");
        String manifest = new String(Files.readAllBytes(app.resolve("AndroidManifest.xml")), StandardCharsets.UTF_8);
        check(manifest.contains(".StepSyncJob") && manifest.contains("android.permission.BIND_JOB_SERVICE"), "job service protected");
        check(manifest.contains(".StepService") && manifest.contains("foregroundServiceType=\"specialUse\""), "minute writer is declared special-use foreground service");
        check(!manifest.contains("FOREGROUND_SERVICE_HEALTH") && !manifest.contains("foregroundServiceType=\"health\""), "no unrelated health sensor FGS gate");
        System.out.println("PASS StepSyncJob: " + checks + " offline scheduling contracts; not Android execution");
    }
}

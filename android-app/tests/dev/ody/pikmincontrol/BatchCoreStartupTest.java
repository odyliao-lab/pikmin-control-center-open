package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Source boundary: a paused Unity process must refresh its paired heartbeat before GPS work. */
public final class BatchCoreStartupTest {
    public static void main(String[] args) throws Exception {
        String service = new String(Files.readAllBytes(Paths.get(
                "android-app/src/dev/ody/pikmincontrol/DispatchBatchService.java")), StandardCharsets.UTF_8);
        int run = service.indexOf("private void runBatch()");
        int gate = service.indexOf("awaitPairedCoreAfterSwitch();", run);
        int capture = service.indexOf("NearbyWorkState.capture(this, GpsWorkLock.BATCH)", run);
        int movement = service.indexOf("GpsModuleGateway", run);
        check(run >= 0 && gate > run && capture > gate && movement > gate,
                "paired-core wait must precede all GPS and native controls");
        String wait = service.substring(service.indexOf("private void awaitPairedCoreAfterSwitch()"));
        check(wait.contains("CoreUpdater.requireReady(this)")
                && wait.contains("CoreUpdater.GAME_NOT_READY.equals(notReady.getMessage())")
                && wait.contains("CoreUpdater.runtimePid(this, pair)")
                && wait.contains("SystemClock.elapsedRealtime() + 30_000L")
                && wait.contains("!stopping")
                && wait.contains("未移動 GPS 或派遣"),
                "only stale heartbeat may wait; bounded same-PID proof or fail closed");
        int flight = service.indexOf("private void flyToAndReturnToGame(");
        int teleport = service.indexOf("GpsModuleGateway.flyTo(this,", flight);
        int focus = service.indexOf("RouteSession.returnToGameIfJoystick()", flight);
        int arrival = service.indexOf("private boolean waitForArrival(", flight);
        check(flight > run && teleport > flight && focus > teleport && focus < arrival
                && service.substring(arrival).contains("lastFocusCheck")
                && service.substring(arrival).contains("RouteSession.returnToGameIfJoystick()")
                && !service.substring(flight, arrival).contains("stopFlight("),
                "teleport must preserve JoyStick service and return game focus before native arrival proof");
        String route = new String(Files.readAllBytes(Paths.get(
                "android-app/src/dev/ody/pikmincontrol/RouteSession.java")), StandardCharsets.UTF_8);
        int conditional = route.indexOf("static void returnToGameIfJoystick()");
        check(conditional >= 0 && route.substring(conditional, route.indexOf("String serviceIdentity()", conditional))
                .contains("case \\\"$top\\\" in *"),
                "focus restoration must remain conditional on JoyStick foreground");
        System.out.println("PASS BatchCoreStartup: bounded same-PID wait before GPS; no weakened pair gate");
    }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }
}

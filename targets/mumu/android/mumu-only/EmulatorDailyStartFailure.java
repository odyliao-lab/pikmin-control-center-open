package dev.ody.pikmincontrol;

/** Fixed, non-private failure labels only; never grants admission or retry. */
final class EmulatorDailyStartFailure {
    static String label(Exception error, boolean foregroundComplete) {
        if (!foregroundComplete) return "foreground-preparation";
        if (error == null) return "unknown";
        if ("android.app.ForegroundServiceStartNotAllowedException".equals(error.getClass().getName()))
            return "android-background-service";
        if (error instanceof SecurityException) return "android-permission";
        String message = error.getMessage();
        if ("Visible notification required".equals(message)) return "notification-permission";
        if ("Fresh same-PID location required".equals(message)) return "initial-location-ready";
        if ("Complete fresh same-account/session daily resources required; missing is unknown".equals(message))
            return "resource-observation";
        for (String stage : new String[]{"ticket-window", "route-binding", "location-ready", "location-epoch", "final-window"})
            if (("Daily route start rejected: " + stage + "; preserve uncertain claim").equals(message)) return stage;
        for (String stage : new String[]{"seed-capacity-reserve", "pikmin-capacity-reserve", "no-ordinary-petals-above-reserve"})
            if (("Daily resource gate: " + stage).equals(message)) return stage;
        return sourceStage(error);
    }
    /** Only fixed known product frame pairs; never render exception text,
     * file names, line numbers, causes, or any private field. A stage is not
     * proof of the rejection cause or permission to repeat a work request. */
    private static String sourceStage(Exception error) {
        String[][] stages={
            {"CoreUpdater","requireFiles","core-files"},
            {"CoreUpdater","requireReady","core-readiness"},
            {"CoreUpdater","runtimePid","core-runtime"},
            {"EmulatorRunnerCounterGateway","capture","counter-observation"},
            {"EmulatorRunnerCounterGateway","decodeCaptured","counter-decoding"},
            {"EmulatorGameDailySteps","read","counter-packet"},
            {"EmulatorDailyResources","read","resource-read"},
            {"EmulatorDailyResources","decode","resource-decoding"},
            {"GpsModuleGateway","farmLocation","location-observation"},
            {"GpsWorkLock","acquire","gps-work-ownership"},
            {"EmulatorDailyRouteWork","requireIdentity","route-identity"},
            {"EmulatorDailyRouteWork","requireStart","route-start-check"},
            {"EmulatorDailyRouteWork","development","development-ticket"},
            {"FlowerFarmService","start","service-start"}
        };
        for(StackTraceElement frame:error.getStackTrace()) {
            for(String[] stage:stages)
                if(("dev.ody.pikmincontrol."+stage[0]).equals(frame.getClassName()) &&
                        stage[1].equals(frame.getMethodName()))return stage[2];
        }
        return "unclassified-start-gate";
    }
}

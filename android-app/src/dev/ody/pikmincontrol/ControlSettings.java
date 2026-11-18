package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

final class ControlSettings {
    private static final String PREFS = "pikmin_control";
    private static final String RETURN_REWARD_ENABLED = "return_reward_enabled";
    private static final String RECEIVE_POSTCARDS = "receive_postcards";
    private static final String TIMEOUT_MINUTES = "timeout_minutes";
    private static final String SESSION_CLAIMS = "session_claims";
    private static final String APPEARANCE = "appearance";
    private static final String DISPATCH_OVERLAY_ENABLED = "dispatch_overlay_enabled";
    private static final String DISPATCH_OVERLAY_X = "dispatch_overlay_x";
    private static final String DISPATCH_OVERLAY_Y = "dispatch_overlay_y";
    private static final String FLOWER_FARM_POINTS = "flower_farm_points";
    private static final String FLOWER_FARM_DWELL = "flower_farm_dwell";
    private static final String START_JOYSTICK_ON_BOOT = "start_joystick_on_boot";
    private static final String RESTART_GAME_ON_PID_LOSS = "restart_game_on_pid_loss";
    static final String APPEARANCE_SYSTEM = "system";
    static final String APPEARANCE_LIGHT = "light";
    static final String APPEARANCE_DARK = "dark";

    private ControlSettings() {}

    static int normalizeFarmRounds(int rounds) { return rounds == 2 ? 2 : 1; }
    static boolean routeHarvest(Context c) { return preferences(c).getBoolean("route_harvest", true); }
    static void setRouteHarvest(Context c,boolean route) { preferences(c).edit().putBoolean("route_harvest",route).commit(); }
    static int flowerFarmRounds(Context c) { return normalizeFarmRounds(preferences(c).getInt("flower_farm_rounds", 1)); }
    static void setFlowerFarmRounds(Context c, int rounds) { preferences(c).edit().putInt("flower_farm_rounds", normalizeFarmRounds(rounds)).commit(); }

    static boolean returnRewardEnabled(Context context) {
        return preferences(context).getBoolean(RETURN_REWARD_ENABLED, false);
    }

    static void setReturnRewardEnabled(Context context, boolean value) {
        preferences(context).edit().putBoolean(RETURN_REWARD_ENABLED, value).apply();
    }

    static boolean receivePostcards(Context context) {
        return preferences(context).getBoolean(RECEIVE_POSTCARDS, true);
    }

    static void setReceivePostcards(Context context, boolean value) {
        preferences(context).edit().putBoolean(RECEIVE_POSTCARDS, value).apply();
    }

    static int timeoutMinutes(Context context) {
        return preferences(context).getInt(TIMEOUT_MINUTES, 5);
    }

    static void setTimeoutMinutes(Context context, int value) {
        preferences(context).edit().putInt(TIMEOUT_MINUTES, Math.max(1, Math.min(30, value))).apply();
    }

    static int sessionClaims(Context context) {
        return preferences(context).getInt(SESSION_CLAIMS, 0);
    }

    static void resetSessionClaims(Context context) {
        preferences(context).edit().putInt(SESSION_CLAIMS, 0).apply();
    }

    static int incrementSessionClaims(Context context) {
        int value = sessionClaims(context) + 1;
        preferences(context).edit().putInt(SESSION_CLAIMS, value).apply();
        return value;
    }

    static String appearance(Context context) {
        String value = preferences(context).getString(APPEARANCE, APPEARANCE_SYSTEM);
        if (APPEARANCE_LIGHT.equals(value) || APPEARANCE_DARK.equals(value)) return value;
        return APPEARANCE_SYSTEM;
    }

    static void setAppearance(Context context, String value) {
        if (!APPEARANCE_LIGHT.equals(value) && !APPEARANCE_DARK.equals(value))
            value = APPEARANCE_SYSTEM;
        preferences(context).edit().putString(APPEARANCE, value).apply();
    }

    static Context applyAppearance(Context context) {
        String value = appearance(context);
        if (APPEARANCE_SYSTEM.equals(value)) return context;
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        int nightMode = APPEARANCE_DARK.equals(value)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | nightMode;
        return context.createConfigurationContext(configuration);
    }

    static boolean dispatchOverlayEnabled(Context context) {
        return preferences(context).getBoolean(DISPATCH_OVERLAY_ENABLED, false);
    }

    static void setDispatchOverlayEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(DISPATCH_OVERLAY_ENABLED, enabled).apply();
    }

    static int dispatchOverlayX(Context context, int fallback) {
        return preferences(context).getInt(DISPATCH_OVERLAY_X, fallback);
    }

    static int dispatchOverlayY(Context context, int fallback) {
        return preferences(context).getInt(DISPATCH_OVERLAY_Y, fallback);
    }

    static void setDispatchOverlayPosition(Context context, int x, int y) {
        preferences(context).edit().putInt(DISPATCH_OVERLAY_X, x).putInt(DISPATCH_OVERLAY_Y, y).apply();
    }

    static String flowerFarmPoints(Context context) {
        return preferences(context).getString(FLOWER_FARM_POINTS, "");
    }

    static int flowerFarmDwell(Context context) {
        return preferences(context).getInt(FLOWER_FARM_DWELL, 10) == 5 ? 5 : 10;
    }

    static void setFlowerFarm(Context context, String points, int dwell) {
        preferences(context).edit().putString(FLOWER_FARM_POINTS, points == null ? "" : points)
                .putInt(FLOWER_FARM_DWELL, dwell == 5 ? 5 : 10).apply();
    }

    static BatchUiSelection batchUi(Context context) {
        SharedPreferences p = preferences(context);
        return new BatchUiSelection(p.getInt("batch_ui_kinds", 7), p.getInt("batch_ui_range", 0),
                p.getBoolean("batch_ui_nearest", false), p.getInt("batch_ui_test", 0));
    }

    static void setBatchUi(Context context, BatchUiSelection selection) {
        preferences(context).edit().putInt("batch_ui_kinds", selection.kinds)
                .putInt("batch_ui_range", selection.range).putBoolean("batch_ui_nearest", selection.nearest)
                .putInt("batch_ui_test", selection.test).apply();
    }

    static int expeditionTab(Context context) {
        return preferences(context).getInt("expedition_ui_tab", 0) == 1 ? 1 : 0;
    }

    static void setExpeditionTab(Context context, int value) {
        preferences(context).edit().putInt("expedition_ui_tab", value == 1 ? 1 : 0).apply();
    }

    /** Opt-in: launching a third-party app at boot should never be implicit. */
    static boolean startJoystickOnBoot(Context context) {
        return preferences(context).getBoolean(START_JOYSTICK_ON_BOOT, false);
    }

    static void setStartJoystickOnBoot(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(START_JOYSTICK_ON_BOOT, enabled).apply();
    }

    static boolean restartGameOnPidLoss(Context context) {
        return preferences(context).getBoolean(RESTART_GAME_ON_PID_LOSS, false);
    }
    static boolean setRestartGameOnPidLoss(Context context, boolean enabled) {
        return preferences(context).edit().putBoolean(RESTART_GAME_ON_PID_LOSS, enabled).commit();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

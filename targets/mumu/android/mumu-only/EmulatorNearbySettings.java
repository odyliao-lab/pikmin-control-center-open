package dev.ody.pikmincontrol;

import android.content.Context;

/** A saved choice does not assert that the paused Unity map is ready. */
final class EmulatorNearbySettings {
    static void choose(Context c, boolean enabled) throws Exception {
        EmulatorRuntime.require();
        // Existing monitor requires fresh paired runtime before restoring ON.
        // Never arm directly using a stale map observation from this screen.
        AutomationPreferences.choose(c, "nearby", enabled ? "on" : "off", () -> {
            if (!enabled) DispatchProbeGateway.setArmed(false);
        });
    }
    static String summary(Context c, NearbyState state) {
        if ("on".equals(AutomationPreferences.value(c, "nearby", "off"))
                && !"armed".equals(state.mode))
            return "已開啟 · 等待遊戲回到前景並核對核心";
        return state.compactSummary();
    }
    private EmulatorNearbySettings() { }
}

package dev.ody.pikmincontrol;

/** Provider observations prove cleanup, never just successful command submission. */
final class RouteCleanupProof {
    static final String SERVICE = "com.thejhijuqoqy.bdujvrwdcajvzin/com.theappninjas.fakegpsjoystick.service.OverlayService";
    static boolean ready(boolean running, boolean active, int waypoints) {
        // Paused waypoints may remain loaded. Do not restart GPS just to erase them.
        return running && !active && waypoints >= 0;
    }
    static String serviceIdentity(String dump) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("ServiceRecord\\{([^}]+)\\}").matcher(dump);
        while(m.find())if(m.group(1).contains(SERVICE))return m.group(1);
        return "";
    }
    static boolean speed(double expected, double observed) {
        return Double.isFinite(expected) && expected > 0 && Double.isFinite(observed)
                && Math.abs(expected-observed) <= 0.05;
    }
    static boolean response(String action) {
        return "theappninjas.gpsjoystick.STATUS_RESPONSE".equals(action);
    }
}

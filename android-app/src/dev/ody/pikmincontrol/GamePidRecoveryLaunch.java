package dev.ody.pikmincontrol;

/** Exact launcher component only; Android may return exit 0 for an unresolvable implicit Intent. */
final class GamePidRecoveryLaunch {
    private GamePidRecoveryLaunch() {}
    static String command(GamePidRecoveryPolicy.Action action,String component) {
        if(action!=GamePidRecoveryPolicy.Action.LAUNCH && action!=GamePidRecoveryPolicy.Action.RESTART)
            return "";
        if(component==null || !component.matches("com\\.nianticlabs\\.pikmin/[A-Za-z0-9_.$]+"))return "";
        return (action==GamePidRecoveryPolicy.Action.RESTART?
                "am force-stop com.nianticlabs.pikmin && ":"")+"am start -n '"+component+"'";
    }
    static boolean accepted(BoundedCommand.Result result,String component) {
        return result!=null && result.ok && !result.timedOut && !result.truncated &&
                component!=null && result.text!=null &&
                result.text.contains("Starting: Intent {") &&
                result.text.contains("cmp="+component) &&
                !result.text.contains("Error:") && !result.text.contains("Exception:");
    }
}

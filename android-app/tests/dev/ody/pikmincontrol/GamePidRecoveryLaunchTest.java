package dev.ody.pikmincontrol;

public final class GamePidRecoveryLaunchTest {
    static void check(boolean value) {if(!value)throw new AssertionError();}
    public static void main(String[] args) {
        String component="com.nianticlabs.pikmin/com.nianticproject.ichigo.IchigoUnityPlayerActivity";
        check(GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,component)
                .equals("am start -n '"+component+"'"));
        check(GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.RESTART,component)
                .equals("am force-stop com.nianticlabs.pikmin && am start -n '"+component+"'"));
        check(GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,
                "com.nianticlabs.pikmin/' ; reboot").isEmpty());
        check(GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.NONE,component).isEmpty());
        String good="Starting: Intent { cmp="+component+" }\n";
        check(GamePidRecoveryLaunch.accepted(new BoundedCommand.Result(good,true,false,false,0),component));
        String old="Starting: Intent { act=android.intent.action.MAIN pkg=com.nianticlabs.pikmin }\n"+
                "Error: Activity not started, unable to resolve Intent { pkg=com.nianticlabs.pikmin }\n";
        check(!GamePidRecoveryLaunch.accepted(new BoundedCommand.Result(old,true,false,false,0),component));
        check(!GamePidRecoveryLaunch.accepted(new BoundedCommand.Result(good+"Error: late failure",true,false,false,0),component));
        check(!GamePidRecoveryLaunch.accepted(new BoundedCommand.Result(good,false,true,false,124),component));
        System.out.println("PASS GamePidRecoveryLaunch: explicit component and exit-zero resolver failure rejected");
    }
}

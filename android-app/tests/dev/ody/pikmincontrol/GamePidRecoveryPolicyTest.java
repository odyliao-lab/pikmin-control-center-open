package dev.ody.pikmincontrol;
public final class GamePidRecoveryPolicyTest {
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
    public static void main(String[] args) {
        GamePidRecoveryPolicy p = new GamePidRecoveryPolicy();
        check(p.observe("",false,true,false,1000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("10",true,true,false,2000) == GamePidRecoveryPolicy.Action.REBIND);
        check(p.observe("",false,false,false,40000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("",false,true,false,41000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("",false,true,true,72000) == GamePidRecoveryPolicy.Action.WAIT_WORK);
        check(p.observe("",false,true,false,73000) == GamePidRecoveryPolicy.Action.LAUNCH);
        check(p.observe("11",true,true,false,74000) == GamePidRecoveryPolicy.Action.REBIND);
        check(p.observe("12",false,true,false,76000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("12",true,true,false,77000) == GamePidRecoveryPolicy.Action.REBIND);
        check(p.observe("13",false,true,false,78000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("13",false,true,false,124000) == GamePidRecoveryPolicy.Action.COOLDOWN);
        check(p.observe("13",false,true,false,165000) == GamePidRecoveryPolicy.Action.RESTART);
        check(p.observe("13",false,true,false,211000) == GamePidRecoveryPolicy.Action.COOLDOWN);
        check(p.observe("",false,true,false,220000) == GamePidRecoveryPolicy.Action.NONE);
        check(p.observe("",false,true,false,251000) == GamePidRecoveryPolicy.Action.COOLDOWN);
        check(p.observe("",false,true,false,260000) == GamePidRecoveryPolicy.Action.RATE_LIMIT);
        GamePidRecoveryPolicy restored = new GamePidRecoveryPolicy();restored.restore("42");
        check(restored.observe("42",false,true,false,1000) == GamePidRecoveryPolicy.Action.NONE);
        check(restored.observe("bad",false,true,false,2000) == GamePidRecoveryPolicy.Action.NONE);
        check(restored.observe("",false,true,false,33000) == GamePidRecoveryPolicy.Action.LAUNCH);
        GamePidRecoveryPolicy failed=new GamePidRecoveryPolicy();failed.restore("42");
        check(failed.observe("42",false,true,false,true,1000)==GamePidRecoveryPolicy.Action.NONE);
        check(failed.observe("42",false,true,true,true,47000)==GamePidRecoveryPolicy.Action.WAIT_WORK);
        check(failed.observe("42",false,true,false,true,48000)==GamePidRecoveryPolicy.Action.RESTART);
        check(failed.observe("43",true,true,false,false,49000)==GamePidRecoveryPolicy.Action.REBIND);
        GamePidRecoveryPolicy firstFailed=new GamePidRecoveryPolicy();
        check(firstFailed.observe("55",false,true,false,true,1000)==GamePidRecoveryPolicy.Action.NONE);
        check(firstFailed.observe("55",false,true,false,true,47000)==GamePidRecoveryPolicy.Action.RESTART);
        GamePidRecoveryPolicy pending=new GamePidRecoveryPolicy();pending.restore("55");
        check(pending.observe("55",false,true,false,false,1000)==GamePidRecoveryPolicy.Action.NONE);
        check(pending.observe("55",false,true,false,false,990000)==GamePidRecoveryPolicy.Action.NONE);
        System.out.println("PASS GamePidRecoveryPolicy: healthy rebind, absent grace, busy gate, bounded restart and no blind launch");
    }
}

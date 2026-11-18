package dev.ody.pikmincontrol;

public final class GamePidRecoveryProofTest {
    static void check(boolean ok) {if(!ok)throw new AssertionError();}
    public static void main(String[] args) throws Exception {
        CorePair pair=new CorePair("schema=2\nversion=1.5.26\ncode=94\napp=120\nsize=123\nsha256="+
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        long now=1_000_000;
        String[] fields={"pidof","42","v1\t999999\t42\t"+
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\tready\tidle\tend",
                "v1\t999999\t42\t1.5.26\t94\t157.0",
                "v1\t999999\t42\t1.5.26\t94\t1","42"};
        check(GamePidRecoveryProof.healthy(pair,fields,now));
        fields[4]="";check(!GamePidRecoveryProof.healthy(pair,fields,now));
        fields[4]="v1\t999999\t42\t1.5.25\t93\t1";check(!GamePidRecoveryProof.healthy(pair,fields,now));
        fields[4]="v1\t999999\t42\t1.5.26\t94\t1";
        fields[3]="v1\t999999\t43\t1.5.26\t94\t157.0";check(!GamePidRecoveryProof.healthy(pair,fields,now));
        fields[3]="v1\t999999\t42\t1.5.26\t94\t157.0";
        fields[5]="43";check(!GamePidRecoveryProof.healthy(pair,fields,now));
        fields[5]="42";check(!GamePidRecoveryProof.healthy(pair,fields,1_020_000));
        CorePair capPair=new CorePair("schema=2\nversion=1.5.23\ncode=91\napp=100\nsize=123\nsha256="+
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\n");
        String pid=ReadinessCapabilitiesTest.PID;long at=ReadinessCapabilitiesTest.NOW;
        check(GamePidRecoveryProof.terminal(capPair,ReadinessCapabilitiesTest.golden("lib_timeout"),pid,at));
        check(GamePidRecoveryProof.terminal(capPair,ReadinessCapabilitiesTest.golden("symbols_missing"),pid,at));
        for(String name:new String[]{"healthy","boot_lib_wait","domain_pending","no_map_yet","incompatible","logged_out"})
            check(!GamePidRecoveryProof.terminal(capPair,ReadinessCapabilitiesTest.golden(name),pid,at));
        check(!GamePidRecoveryProof.terminal(capPair,ReadinessCapabilitiesTest.golden("lib_timeout"),"123456",at));
        check(!GamePidRecoveryProof.terminal(capPair,ReadinessCapabilitiesTest.golden("lib_timeout"),pid,at+13000));
        check(!GamePidRecoveryProof.terminal(pair,ReadinessCapabilitiesTest.golden("lib_timeout"),pid,at));
        check(!GamePidRecoveryProof.terminal(capPair,"__PCC_ERROR__",pid,at));
        String[] late=ReadinessCapabilitiesTest.golden("healthy").trim().split("\t",-1);
        late[13]="0";late[15]="unknown";late[16]="0";
        check(GamePidRecoveryProof.terminal(capPair,String.join("\t",late)+"\n",pid,at));
        late[17]="0";check(!GamePidRecoveryProof.terminal(capPair,String.join("\t",late)+"\n",pid,at));
        late[15]="logged_out";check(!GamePidRecoveryProof.terminal(capPair,String.join("\t",late)+"\n",pid,at));
        System.out.println("PASS GamePidRecoveryProof: same PID, pair and fresh native reports required");
    }
}

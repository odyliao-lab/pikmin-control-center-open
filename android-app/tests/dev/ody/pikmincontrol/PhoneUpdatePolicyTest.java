package dev.ody.pikmincontrol;

import static dev.ody.pikmincontrol.PhoneUpdatePolicy.Next.*;

public final class PhoneUpdatePolicyTest {
    private static int count;
    private static void check(PhoneUpdatePolicy.Next expected,String phase,long installed,boolean ready,
                              boolean permission,boolean session,String core) {
        PhoneUpdatePolicy.Next got=PhoneUpdatePolicy.next(phase,installed,172,ready,permission,session,core);
        if(got!=expected)throw new AssertionError(phase+"/"+installed+"/"+core+": "+got+" != "+expected);
        count++;
    }
    public static void main(String[] args) {
        check(DOWNLOAD,"download",171,false,false,false,"");
        check(PERMISSION,"download",171,true,false,false,"");
        check(INSTALL_APK,"download",171,true,true,false,"");
        check(WAIT_APK,"download",171,true,true,true,"");
        check(WAIT_APK,"apk-intent",171,true,true,true,"");
        // Process killed before/after session creation: an intent is not permission to replay.
        check(BLOCK,"apk-intent",171,true,true,false,"");
        check(BLOCK,"apk-intent",171,false,false,false,"");
        check(VERIFY_APK,"apk-intent",172,false,false,false,"");
        check(VERIFY_APK,"apk-intent",172,true,true,true,"");
        check(VERIFY_APK,"download",172,false,false,false,"");
        for(String phase:new String[]{"download","apk-intent","core","core-intent"})
            check(BLOCK,phase,173,true,true,false,"ready"); // external newer install is not consent
        for(String action:new String[]{"install","successor"}) {
            check(INSTALL_CORE,"core",172,false,true,false,action);
            check(BLOCK,"core-intent",172,false,true,false,action);
        }
        for(String phase:new String[]{"core","core-intent"}) {
            check(REBOOT,phase,172,false,true,false,"reboot");
            check(OPEN_GAME,phase,172,false,true,false,"open-game");
            check(REVIEW,phase,172,false,true,false,"review");
            check(RESTORE,phase,172,false,true,false,"ready");
            check(WAIT,phase,172,false,true,false,"busy");
            check(BLOCK,phase,171,true,true,false,"ready");
            check(BLOCK,phase,172,false,true,false,"finish"); // rollback needs separate consent
            check(BLOCK,phase,172,false,true,false,"error");
        }
        for(String phase:new String[]{"","error","cancelled","complete","unknown"})
            check(BLOCK,phase,172,true,true,false,"ready");
        if(PhoneUpdatePolicy.next("core",0,0,true,true,false,"ready")!=BLOCK)throw new AssertionError("missing target");
        for(String phase:new String[]{"download","apk-intent","core","core-intent","restore","error","complete","unknown"})
            for(String resume:new String[]{"","core","restore"})
            for(long installed:new long[]{171,172,173}) {
                boolean expected=installed>172&&(phase.equals("restore")||(phase.equals("error")&&resume.equals("restore")));
                if(PhoneUpdatePolicy.supersededRestore(phase,resume,installed,172)!=expected)throw new AssertionError("superseded restore gate");
                count++;
            }
        if(PhoneUpdatePolicy.supersededRestore("restore","",173,0))throw new AssertionError("missing obsolete target");
        // End-to-end decision traces survive process recreation because only durable phase matters.
        check(INSTALL_APK,"download",171,true,true,false,"");
        check(WAIT_APK,"apk-intent",171,true,true,true,"");
        check(VERIFY_APK,"apk-intent",172,false,true,false,"");
        check(INSTALL_CORE,"core",172,false,true,false,"install");
        check(REBOOT,"core-intent",172,false,true,false,"reboot");
        check(OPEN_GAME,"core-intent",172,false,true,false,"open-game");
        check(REVIEW,"core-intent",172,false,true,false,"review");
        check(RESTORE,"core-intent",172,false,true,false,"ready");
        System.out.println("PASS PhoneUpdatePolicy: "+count+" interruption, consent, installer, reboot and no-replay decisions");
    }
}

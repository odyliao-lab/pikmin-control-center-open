package dev.ody.pikmincontrol;
public final class BatchRecoveryReturnTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("recovery return "+checks);}
    static BoundedCommand.Result result(String text,boolean ok,boolean timeout,boolean clipped){return new BoundedCommand.Result(text,ok,timeout,clipped);}
    public static void main(String[] args)throws Exception {
        for(String pkg:new String[]{"dev.ody.pikmincontrol","dev.ody.pikmincontrol.mumu"}) {
            String cmd=BatchRecoveryReturn.command(pkg,10292);
            check(cmd.startsWith("am start -W --user 0 -n "+pkg+"/dev.ody.pikmincontrol.MainActivity "));
            check(cmd.endsWith("--ez open_dispatch true"));
            String good="Starting: Intent\nStatus: ok\nActivity: "+pkg+"/dev.ody.pikmincontrol.MainActivity\nComplete\n";
            check(BatchRecoveryReturn.accepted(result(good,true,false,false),pkg));
            check(BatchRecoveryReturn.accepted(result(good.replace("\n","\r\n"),true,false,false),pkg));
            check(!BatchRecoveryReturn.accepted(result(good,false,false,false),pkg));
            check(!BatchRecoveryReturn.accepted(result(good,true,true,false),pkg));
            check(!BatchRecoveryReturn.accepted(result(good,true,false,true),pkg));
            check(!BatchRecoveryReturn.accepted(result(good.replace("Status: ok","Status: timeout"),true,false,false),pkg));
            check(!BatchRecoveryReturn.accepted(result(good.replace("MainActivity","OtherActivity"),true,false,false),pkg));
            check(!BatchRecoveryReturn.accepted(result("Starting: Intent\n",true,false,false),pkg));
            check(!BatchRecoveryReturn.accepted(null,pkg));
        }
        check(BatchRecoveryReturn.accepted(result("Status: ok\nActivity: dev.ody.pikmincontrol/.MainActivity\n",true,false,false),"dev.ody.pikmincontrol"));
        for(String pkg:new String[]{"other.pkg","dev.ody.pikmincontrol;reboot","dev.ody.pikmincontrol/foo","",null}) {
            boolean failed=false;try{BatchRecoveryReturn.command(pkg,10292);}catch(IllegalArgumentException expected){failed=true;}check(failed);
        }
        for(int uid:new int[]{0,2000,9999,100000,1010292,-1}) {
            boolean failed=false;try{BatchRecoveryReturn.command("dev.ody.pikmincontrol",uid);}catch(IllegalArgumentException expected){failed=true;}check(failed);
        }
        String gateway=FarmResetSourceTest.source("BatchRecoveryGateway"),ui=FarmResetSourceTest.source("MainActivity");
        check(gateway.contains("BatchRecoveryReturn.command(c.getPackageName(),c.getApplicationInfo().uid),4,4096"));
        check(ui.contains("重新核對批次收據")&&ui.contains("WorkStatus.hasBatchReconciliation(this)"));
        check(!FarmResetSourceTest.source("BatchRecoveryReturn").contains("appops"));
        System.out.println("PASS BatchRecoveryReturn: "+checks+" command/ACK/source checks; NOT MIUI navigation acceptance");
    }
}

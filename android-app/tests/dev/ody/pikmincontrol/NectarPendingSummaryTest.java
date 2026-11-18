package dev.ody.pikmincontrol;
public final class NectarPendingSummaryTest {
    private static String summary(String result){
        return new NectarModuleGateway.Snapshot("auto",
            new String[]{"1","auto","0","1","1","0","0","0","1",result},true).summary();
    }
    private static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        check(summary("PENDING").contains("不重送"));
        check(summary("PENDING:59000:100000:abcdef012345:live").contains("59 秒"));
        String recovered=summary("PENDING_UNCONFIRMED:60000:100000:abcdef012345:restart");
        check(recovered.contains("待核對")&&recovered.contains("前次程序")&&recovered.contains("abcdef012345"));
        check(!recovered.contains("上次收取成功"));
        check(summary("PENDING_UNCONFIRMED:unknown:unknown:unknown:storage").contains("紀錄無法確認"));
        check(summary("PENDING_UNCONFIRMED:9999999999999999999:9999999999999999999:bad:restart").contains("待核對"));
        check(summary("PENDING:-1").contains("等待原請求"));
        check(summary("SUCCESS:NONE").contains("檢查正常"));
        check(summary("SUCCESS:ANY").contains("收取成功"));
    }
}

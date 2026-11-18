package dev.ody.pikmincontrol;
public final class BatchRetryTest {
    public static void main(String[] args) {
        BatchRetry r=new BatchRetry();
        if(!r.defer("a","gift-owner-busy",1000) || r.defer("a","gift-owner-busy",2000)
                || r.remaining("a",2000)!=29000 || r.remaining("a",40000)!=0
                || !r.defer("b","no-available-team",2000))throw new AssertionError("bounded busy retry");
        for(String reason:new String[]{"confirmation_timeout","game_refresh_timeout","arrival_timeout","rpc-faulted",""})
            if(r.defer(reason,reason,0))throw new AssertionError("unsafe retry "+reason);
        System.out.println("PASS BatchRetry: one cooldown revisit only for confirmed preflight busy");
    }
}

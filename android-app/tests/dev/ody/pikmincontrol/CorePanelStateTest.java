package dev.ody.pikmincontrol;

public final class CorePanelStateTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        CorePanelState ready=new CorePanelState("ready",false,false,false,"accepted");
        check(ready.primary.isEmpty());check(!ready.cancel);check(ready.rollback);check(!ready.finish);
        check(new CorePanelState("ready",true,false,false,"accepted").primary.contains("結束準備"));
        check(new CorePanelState("successor",false,true,false,"staged").primary.equals("繼續更新核心"));
        check(new CorePanelState("open-game",false,true,false,"staged").primary.contains("開啟"));
        check(new CorePanelState("review",false,true,false,"staged").primary.equals("核對載入結果"));
        check(new CorePanelState("error",false,true,false,"staged").primary.equals("重新檢查"));
        for(String action:new String[]{"busy","reboot","error"})for(String phase:new String[]{"staged","accepted","rollback-staged","install-intent"})
            for(boolean fresh:new boolean[]{false,true})check(!new CorePanelState(action,false,true,fresh,phase).rollback);
        for(String action:new String[]{"install","successor","reboot","open-game","review","finish","ready","busy","error"})
            for(boolean pending:new boolean[]{false,true})for(boolean preparing:new boolean[]{false,true}){
                CorePanelState s=new CorePanelState(action,preparing,pending,false,"staged");
                check(s.cancel==(preparing&&!pending&&!action.equals("busy")));
                check(s.finish==action.equals("finish"));
                if(action.equals("busy"))check(s.primary.isEmpty());
            }
        for(String phase:new String[]{"","backed-up","old-core-confirmed","gps-reboot","rolled-back","unknown"})
            check(!new CorePanelState("review",false,true,false,phase).rollback);
        check(new CorePanelState("finish",false,true,false,"old-core-confirmed").finish);
        check(!new CorePanelState("finish",false,true,false,"old-core-confirmed").rollback);
        for(String phase:new String[]{"prepared","gps-intent","copy-intent","install-intent","staged","accepted","remove-intent"})
            check(new CorePanelState("review",false,true,true,phase).rollback);
        for(String phase:new String[]{"prepare-intent","removed","restored",""})check(!new CorePanelState("review",false,true,true,phase).rollback);
        System.out.println("PASS CorePanelState: "+checks+" state/action visibility checks; NOT Android visual validation");
    }
}

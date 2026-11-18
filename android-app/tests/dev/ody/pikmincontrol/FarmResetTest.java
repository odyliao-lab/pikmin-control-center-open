package dev.ody.pikmincontrol;
import java.util.*;
public final class FarmResetTest {
    private static int checks;
    private static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    public static void main(String[] args) {
        for(String state:new String[]{"error","stopped","completed","recovered",""}) {
            check(!FarmRecovery.required(state,true,false));
            check(FarmRecovery.required(state,false,false));
            check(FarmRecovery.required(state,true,true));
            FarmUi clean=new FarmUi(state,false,false,false,true,"",true,false,false);
            check(clean.canStart&&!clean.recovery&&!clean.canReset);
        }
        for(String state:new String[]{"running","stopping","stop-incomplete"})check(FarmRecovery.required(state,true,false));
        check(!FarmRecovery.required("completed",null,false));
        for(String state:new String[]{"error","stopped","running","stopping","stop-incomplete"})check(FarmRecovery.required(state,null,false));
        // Saved cleanup proof survives recreation; old failure remains historical.
        Properties stored=new Properties();stored.setProperty("state","error");stored.setProperty("clean","true");
        Properties reloaded=new Properties();reloaded.putAll(stored);
        check(!FarmRecovery.required(reloaded.getProperty("state"),Boolean.valueOf(reloaded.getProperty("clean")),false));
        check(reloaded.getProperty("state").equals("error"));
        for(boolean pending:new boolean[]{true,false})check(FarmRecovery.required("completed",true,pending)==pending);
        FarmUi noPoints=new FarmUi("error",false,false,false,true,"",false,false,true);
        check(noPoints.canReset&&!noPoints.canRecover&&!noPoints.canStart);
        for(String blocker:new String[]{"批次工作中","安裝中","停止全部中"}) {
            FarmUi blocked=new FarmUi("error",false,false,true,false,blocker,false,false,true);
            check(!blocked.canReset&&!blocked.canRecover&&!blocked.canStart);
        }
        check(!new FarmUi("completed",false,true,true,true,"",true,false,false).canReset);
        check(!new FarmUi("running",true,true,true,true,"",true,false,true).canReset);
        FarmRecovery.persist(()->true);
        boolean refused=false;try{FarmRecovery.persist(()->false);}catch(IllegalStateException expected){refused=true;}
        check(refused);
        check(WorkProgress.label("recovered").contains("未開始採果"));
        check(UiSummary.work("recovered",false,false).contains("未開始採果"));
        check(!UiSummary.workWarning("recovered",false,false));
        System.out.println("PASS FarmReset: "+checks+" cleanup/history/persistence/UI checks; NOT Android or gameplay validation");
    }
}

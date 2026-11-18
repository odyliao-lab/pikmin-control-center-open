package dev.ody.pikmincontrol;

/** Presentation only. Every operation must recheck the real transaction before mutation. */
final class CorePanelState {
    final String action, title, primary;
    final boolean cancel, rollback, finish;
    CorePanelState(String action,boolean preparing,boolean pending,boolean fresh,String phase){
        this.action=action;
        switch(action){
            case "install":title="尚未安裝配對核心";primary="安裝／更新核心";break;
            case "successor":title="舊核心待接續更新";primary="繼續更新核心";break;
            case "reboot":title="核心待重開核對";primary="重新啟動手機";break;
            case "open-game":title="等待遊戲啟動以核對核心";primary="開啟Pikmin並核對";break;
            case "review":title="核心載入待核對";primary="核對載入結果";break;
            case "finish":title="舊核心已確認，待恢復GPS";primary="完成回退並恢復GPS";break;
            case "ready":title="核心已就緒";primary=preparing?"完成核對並結束準備":"";break;
            case "busy":title="正在處理，請稍候";primary="";break;
            default:title="核心狀態尚未確認";primary="重新檢查";
        }
        boolean available=!action.equals("busy")&&!action.equals("reboot")&&!action.equals("error");
        cancel=preparing&&!pending&&!action.equals("busy");
        finish=action.equals("finish");
        rollback=available&&!finish&&(fresh?CoreFreshFlow.rollbackPhase(phase):
            phase.equals("gps-disable-intent")||phase.equals("install-intent")||phase.equals("staged")||phase.equals("accepted")||phase.equals("rollback-intent")||phase.equals("rollback-staged"));
    }
}

package dev.ody.pikmincontrol;

/** One desired value for an entire display group; reports remain independently verified. */
final class DisplayOptionsState {
    final boolean desired;
    final String message;
    DisplayOptionsState(String control,String[] reports,String before,String after,long now) {
        if(!"on".equals(control.trim())&&!"off".equals(control.trim()))
            throw new IllegalStateException("顯示設定尚未確認");
        if(reports.length<1||reports.length>3)throw new IllegalArgumentException("Display group reports required");
        desired="on".equals(control.trim());
        boolean fresh=true,compatible=true,applied=true,restoring=false;
        for(String report:reports) {
            SeedPreviewState state=new SeedPreviewState(control,report,before,after,now);
            fresh&=state.fresh;compatible&=state.compatible;applied&=state.applied;
            restoring|=state.applied;
        }
        message=!desired
                ? !fresh?"已關閉，等待遊戲確認":restoring?"已關閉，正在還原原顯示":"已關閉，顯示遊戲原資訊"
                : !fresh?"已開啟，等待遊戲回報":!compatible?"已開啟，目前遊戲或核心尚未就緒"
                : !applied?"已開啟，等待套用":"已開啟，回到遊戲對應頁面查看；資料不足保留未知";
    }
}

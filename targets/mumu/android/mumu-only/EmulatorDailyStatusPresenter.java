package dev.ody.pikmincontrol;

import java.time.Instant;
import java.time.LocalDate;

/** Display only. Attempted slots and final claims are not route completion.
 * No root/API/store writes, timer, acceptance or effect authority. */
final class EmulatorDailyStatusPresenter {
    static final String UNAVAILABLE="每日摘要：資料未確認，請稍後重新核對；不會啟動或重送工作。";
    static String render(EmulatorDailyRunner.State state,EmulatorDailyRunner.Binding held,long now){
        if(held==null||now<=0)return UNAVAILABLE;
        if(state==null)return "每日摘要：尚無已保存的每日工作紀錄。";
        try{
            // Validate the same canonical state, never infer a result from a
            // running flag, consumed slot, UI preference or old binding.
            EmulatorDailyRunner.State s=EmulatorDailyRunner.State.decode(state.encode());
            if(!held.same(s.binding)||s.journal.lastEpoch>now)return UNAVAILABLE;
            LocalDate today=EmulatorDailyPlan.workDay(now,s.journal.overnight);
            if(s.journal.day.isAfter(today))return UNAVAILABLE;
            String day=s.journal.day.toString();
            String pending=s.journal.pending==EmulatorDailyPlan.Pending.ROUTE?"路線待確認":
                    s.journal.pending==EmulatorDailyPlan.Pending.BATCH?"批次待確認":"無待確認工作";
            if(s.journal.uncertain)pending+="（送出／結果未知，不重送）";
            if(s.journal.failed)pending+="；需要檢查";
            if(s.stopAttempted)pending+="；已要求路線停止，尚非閉合證明";
            if(s.batchCloseAttempted)pending+="；已要求停止新派遣，既有領回仍待確認";
            if(!s.journal.failed&&s.journal.pending==EmulatorDailyPlan.Pending.NONE&&!s.journal.finalComplete&&s.journal.finalAttempts>=EmulatorDailyPlan.MAX_RESCANS)
                pending+="；本日補掃上限已達，待辦保留，隔日重新評估（不是全部領回）";
            return "每日摘要 · "+day+(s.journal.day.equals(today)?"":"（歷史，不是今日完成）")+
                    "\n路線已嘗試 "+s.journal.routeSlots.size()+(s.journal.overnight?"（連續循環，安全上限128）":" / 13")+"；不代表已完成路段。"+
                    "\n收工批次已嘗試 "+s.journal.finalAttempts+" / "+EmulatorDailyPlan.MAX_RESCANS+
                    "；全部領回確認："+(s.journal.finalComplete?"是":"尚未")+"。\n"+pending+"。";
        }catch(Exception invalid){return UNAVAILABLE;} // no raw/private error disclosure
    }
}

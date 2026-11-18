package dev.ody.pikmincontrol;

/** Navigation is allowed without fresh proof; a recovery request is not. */
final class PlanterRecoveryUi {
    final boolean visible, enabled, canRequest;
    final String label, message;
    PlanterRecoveryUi(PlanterState state,PlanterRecovery recovery,boolean active,boolean submitting){
        boolean checking=recovery.supported&&"checking-inventory".equals(recovery.result);
        boolean paused=state.legacyReviewRequired();
        visible=paused||checking||(active&&(!state.fresh||!recovery.supported));
        canRequest=state.fresh&&recovery.supported&&!checking;
        enabled=!submitting&&!checking;
        label=canRequest?"手動恢復育苗":"前往遊戲更新育苗";
        message=!state.fresh&&recovery.supported
            ?"育苗資料尚未完整更新，請回遊戲停留約10秒再回來；不會清除待確認操作"
            :paused&&recovery.result.startsWith("recovered-")?"目前另有待確認操作，可再次核對"
            :(recovery.result.startsWith("recovered-")?"上次核對：":"")+recovery.message();
    }
}

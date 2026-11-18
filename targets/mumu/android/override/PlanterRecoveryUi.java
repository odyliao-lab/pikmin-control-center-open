package dev.ody.pikmincontrol;

/** Compatibility shape only: continuous nursery has no manual recovery entry. */
final class PlanterRecoveryUi {
    final boolean visible, enabled, canRequest;
    final String label, message;
    PlanterRecoveryUi(PlanterState state,PlanterRecovery recovery,boolean active,boolean submitting){
        visible=false;canRequest=false;enabled=false;
        label="育苗自動觀察";
        message=active?"異常保留紀錄，等待有效庫存後自動接續":"育苗已關閉";
    }
}

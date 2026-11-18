package dev.ody.pikmincontrol;

public final class FarmUiTest {
    private static int checks;
    private static void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        checks++;
    }
    public static void main(String[] args) {
        FarmUi idle = ui("", false, false, false, true, "", true, false);
        check(idle.canStart && idle.canEdit && idle.showEditor, "idle editor and start available");
        check(!idle.recovery && !idle.canRecover && !idle.showNotice, "no redundant recovery or idle banner");
        check(!ui("", false, false, false, true, "", false, false).canStart, "empty draft cannot start");
        FarmUi run = ui("running", true, true, true, false, "", true, false);
        check(!run.canStart && !run.canRecover && !run.canEdit && !run.showEditor, "live route locks editing and both starts");
        check(run.message.equals("請切回遊戲") && run.detail.contains("通知"), "overlay and notification direction");
        check(!run.warning && run.showNotice, "running notice is not error");
        FarmUi stopping = ui("stopping", true, true, true, false, "", true, false);
        check(stopping.message.contains("等待停止確認") && !stopping.canStart, "stopping is not completed");
        FarmUi missingOwner = ui("running", true, false, true, false, "", true, false);
        check(missingOwner.warning && missingOwner.message.contains("待確認"), "live worker does not prove ownership");
        for (String state : new String[]{"error", "stopped", "stop-incomplete", "running", "stopping"}) {
            FarmUi recovery = ui(state, false, false, "stop-incomplete".equals(state), true, "", true, false);
            check(recovery.recovery && recovery.canRecover && !recovery.canStart, "consent required: " + state);
            check(!recovery.showEditor && recovery.canEdit, "saved summary with editable recovery draft: " + state);
            check(ui(state, false, false, false, true, "", true, true).showEditor, "recovery editor can open: " + state);
            check(ui(state, false, false, false, true, "", false, false).showEditor, "empty recovery opens editor: " + state);
        }
        check(ui("stopped", false, false, false, true, "", true, false).message.equals("上次工作已停止"), "user stop is not failure");
        check(!ui("completed", false, false, false, true, "", true, false).recovery, "normal completion does not require recovery");
        FarmUi held = ui("completed", false, true, true, false, "", true, false);
        check(held.recovery && held.warning && !held.canRecover && held.message.contains("未釋放"), "held owner cannot be hidden by completed label");
        for (String reason : new String[]{"裝置部署待核對", "更新安裝待確認", "停止全部中", "批次探險使用 GPS 中", "GPS 啟動檢查中"}) {
            FarmUi blocked = ui("error", false, false, true, false, reason, true, false);
            check(!blocked.canStart && !blocked.canRecover && !blocked.canEdit, "other owner blocks actions: " + reason);
            check(blocked.message.equals(reason) && blocked.warning, "actual blocker visible: " + reason);
        }
        FarmUi contradictory = ui("", false, false, false, true, "部署待核對", true, false);
        check(!contradictory.canStart && !contradictory.canEdit, "blocker wins over stale busy flag");
        check(!ui("error", false, false, false, true, "停止全部中", true, false).canRecover, "blocker wins over stale recovery flag");
        // Every presentation combination must retain the basic locks. No Android or native writes here.
        for (String state : new String[]{"", "completed", "error", "stopped", "stop-incomplete", "running", "stopping"})
            for (int bits = 0; bits < 128; bits++) {
                boolean live=(bits&1)!=0, heldLock=(bits&2)!=0, busy=(bits&4)!=0, available=(bits&8)!=0,
                        points=(bits&16)!=0, edit=(bits&32)!=0;
                String reason=(bits&64)!=0?"其他工作阻擋":"";
                FarmUi s=ui(state,live,heldLock,busy,available,reason,points,edit);
                check(!s.canStart || (points && !live && !heldLock && !busy && !s.recovery && reason.isEmpty()), "start invariant");
                check(!s.canRecover || (points && s.recovery && available && !live && !heldLock && reason.isEmpty()), "recovery invariant");
                check(!live || (!s.showEditor && !s.canEdit), "running editor invariant");
            }
        System.out.println("PASS: " + checks + " garden farm presentation checks (not service/device validation)");
    }
    private static FarmUi ui(String s, boolean l, boolean h, boolean b, boolean r, String reason, boolean points, boolean edit) {
        return new FarmUi(s,l,h,b,r,reason,points,edit);
    }
}

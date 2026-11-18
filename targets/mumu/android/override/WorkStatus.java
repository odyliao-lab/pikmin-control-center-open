package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** App-private, durable lifecycle. Existing native TSV/control protocols stay unchanged. */
final class WorkStatus {
    static final String PREFS = "pikmin_work_status";
    static volatile boolean stoppingAll;
    static synchronized void write(Context c, String owner, String state, int position, int total,
            int successes, int failures, int skipped, String detail) {
        prefs(c).edit().putString(owner + ".state", state).putInt(owner + ".position", position)
                .putInt(owner + ".total", total).putInt(owner + ".success", successes)
                .putInt(owner + ".failure", failures).putInt(owner + ".skipped", skipped)
                .putString(owner + ".detail", detail).putLong(owner + ".time", System.currentTimeMillis()).commit();
        AutomationAlerts.work(c,owner,state,detail,failures,skipped);
    }
    static String state(Context c, String owner) {
        if(GpsWorkLock.BATCH.equals(owner)&&batchStorageFailed)return "stop-incomplete"; return prefs(c).getString(owner + ".state", ""); }
    static boolean blocked(Context c) {
        return !prefs(c).getString("stop_all_error", "").isEmpty()
                || (WorkProgress.active(state(c,GpsWorkLock.BATCH)) && !DispatchBatchService.isRunning())
                || "stop-incomplete".equals(state(c, GpsWorkLock.BATCH))
                || "stop-incomplete".equals(state(c, GpsWorkLock.FLOWER_FARM));
    }
    static void allResult(Context c, String error) { prefs(c).edit().putString("stop_all_error", error).commit(); }
    static String allError(Context c) { return prefs(c).getString("stop_all_error", ""); }
    static String compact(Context c, String owner, boolean live) {
        String state = state(c, owner);
        if (!live && GpsWorkLock.FLOWER_FARM.equals(owner) && "stop-incomplete".equals(state))
            return "停止／復原未確認 · 到花田頁復原並重新開始";
        boolean held = GpsWorkLock.observedHeldBy(c, owner);
        if (!live && !held && !UiSummary.workWarning(state, false, false)) {
            String other = GpsWorkLock.BATCH.equals(owner) ? GpsWorkLock.FLOWER_FARM : GpsWorkLock.BATCH;
            if (GpsWorkLock.observedHeldBy(c, other)) return (GpsWorkLock.BATCH.equals(other) ? "批次探險" : "花田採果") + "使用 GPS · 此頁暫不可啟動";
            if (GpsWorkLock.observedHeldBy(c, GpsWorkLock.JOYSTICK_STARTUP)) return "GPS 啟動檢查中 · 請稍候";
        }
        return UiSummary.work(state, live, held);
    }
    static String lastCounts(Context c, String owner, boolean live) {
        SharedPreferences p = prefs(c);
        int total = p.getInt(owner + ".total", 0);
        String state = state(c, owner);
        // Running counters belong to overlay/notification, not the setup page.
        if (live || WorkProgress.active(state) || total <= 0) return compact(c, owner, live);
        String counts = "上次 " + p.getInt(owner + ".position", 0) + " / " + total
                + (GpsWorkLock.BATCH.equals(owner) ? " · 成功 " + p.getInt(owner + ".success", 0) : "")
                + ("completed".equals(state) ? " · 已結束" : "stopped".equals(state) ? " · 已停止" : "");
        return UiSummary.workWarning(state, false, GpsWorkLock.observedHeldBy(c, owner))
                ? compact(c, owner, false) : counts;
    }
    static String summary(Context c, String owner, boolean live) {
        SharedPreferences p = prefs(c);
        String state = state(c, owner);
        if (state.isEmpty()) return "";
        String label = WorkProgress.active(state) && !live
                ? "服務目前未執行；上次工作中斷，停止狀態尚未確認" : WorkProgress.label(state);
        if (GpsWorkLock.FLOWER_FARM.equals(owner) && "stop-incomplete".equals(state))
            label = "停止／復原未確認；可按「復原並重新開始」重試";
        int total = p.getInt(owner + ".total", 0);
        String counts = total == 0 ? "" : "\n" + p.getInt(owner + ".position", 0) + " / " + total;
        if (GpsWorkLock.BATCH.equals(owner) && total > 0) counts += "（成功 " + p.getInt(owner + ".success", 0)
                + " · 失敗 " + p.getInt(owner + ".failure", 0) + " · 略過 " + p.getInt(owner + ".skipped", 0) + "）";
        String detail = p.getString(owner + ".detail", "");
        if(GpsWorkLock.BATCH.equals(owner)){
            String previous=p.getString("batch.recovery_original_detail","");
            if(!previous.isEmpty()&&!previous.equals(detail))detail+="\n上次中斷原因："+previous;
        }
        return label + counts + (detail.isEmpty() ? "" : "\n" + detail);
    }
    static synchronized void recovered(Context c, String owner) {
        prefs(c).edit().putString(owner + ".state", "stopped")
                .putString(owner + ".detail", "停止控制檔已回讀確認；已送出請求仍可能完成")
                .putLong(owner + ".time", System.currentTimeMillis()).commit();
    }
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
    private static volatile boolean batchStorageFailed;
    static synchronized void batchRecovery(Context c,String state,String detail) {
        batchStorageFailed=true;
        SharedPreferences p=prefs(c);
        SharedPreferences.Editor edit=p.edit().putString("batch.state",state).putString("batch.detail",detail)
                .putLong("batch.time",System.currentTimeMillis());
        if("stopping".equals(state) && !p.contains("batch.recovery_original_detail")) {
            String previous=p.getString("batch.detail","");
            edit.putString("batch.recovery_original_detail",previous.length()>2048?previous.substring(0,2048):previous);
        }
        if(!edit.commit())
            throw new IllegalStateException("批次恢復狀態未保存，保留暫停，請重試診斷");
        batchStorageFailed=false;
    }
}

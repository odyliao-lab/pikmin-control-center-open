package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * One durable owner for GPS JoyStick commands issued by Control Center.
 *
 * Both foreground services run in this app process, but the owner is also
 * recorded in preferences so a recreated activity can explain the block. A
 * stale owner is reclaimed after two minutes; active services refresh it on
 * every visible progress update and never wait that long without updating.
 */
final class GpsWorkLock {
    static final String BATCH = "batch";
    static final String FLOWER_FARM = "flower-farm";
    static final String JOYSTICK_STARTUP = "joystick-startup";
    private static final String PREFS = "pikmin_gps_work_lock";
    private static final String OWNER = "owner";
    private static final String TOUCHED_MS = "touched_ms";
    private static final long STALE_MS = 120_000L;

    private GpsWorkLock() { }

    static final class CoreNotReady extends IllegalStateException {
        CoreNotReady(String message) { super(message); }
    }

    /** A live owner is temporary contention, never permission to steal its lease. */
    static final class Busy extends IllegalStateException {
        Busy(String message) { super(message); }
    }

    static synchronized void acquire(Context context, String owner) {
        acquire(context, owner, false);
    }

    static synchronized void acquireFarmRecovery(Context context) {
        acquire(context, FLOWER_FARM, true);
    }
    static void acquireBatchRecovery(Context c) {
        // Availability reads NearbyWorkState. Do not hold the GPS class monitor while
        // taking its monitor: normal cleanup takes NearbyWorkState -> GPS in that order.
        // acquire() rechecks the durable owner/live services after this advisory read.
        if(!batchRecoveryAvailable(c))throw new IllegalStateException("其他工作、安裝或停止仍待處理；請先安全停止，工作權過期前不強制清除");
        synchronized(GpsWorkLock.class){acquire(c,BATCH,true);}
    }
    static boolean batchRecoveryAvailable(Context c) {
        return !CoreInstallWizard.preparing(c) && !CoreUpdater.running && !CoreUpdater.pending(c)
            && !SetupDeployment.pending(c) && !UpdateInstaller.pending(c) && !WorkStatus.stoppingAll
            && WorkStatus.allError(c).isEmpty() && !RouteSession.pending(c)
            && !"stop-incomplete".equals(WorkStatus.state(c,FLOWER_FARM))
            && !WorkProgress.active(WorkStatus.state(c,FLOWER_FARM))
            && !NearbyWorkState.ownedBy(c,FLOWER_FARM)
            && !DispatchBatchService.isRunning() && !FlowerFarmService.isRunning() && !observedAnyHeld(c);
    }

    static boolean farmRecoveryAvailable(Context c) {
        return !SetupDeployment.pending(c) && !UpdateInstaller.pending(c) && !WorkStatus.stoppingAll
                && WorkStatus.allError(c).isEmpty()
                && !"stop-incomplete".equals(WorkStatus.state(c, BATCH))
                && !WorkProgress.active(WorkStatus.state(c, BATCH))
                && !DispatchBatchService.isRunning() && !FlowerFarmService.isRunning()
                && !observedHeldBy(c, BATCH) && !observedHeldBy(c, JOYSTICK_STARTUP) && !observedHeldBy(c, FLOWER_FARM);
    }

    private static void acquire(Context context, String owner, boolean recovery) {
        if(!recovery && UpdatePreparation.blocking(context))throw new IllegalStateException("更新準備暫停中，請完成或取消更新；原開關保留");
        if(CoreInstallWizard.preparing(context))throw new CoreNotReady("核心升級準備中，暫停新GPS工作");
        if(RouteSession.pending(context)&&!FLOWER_FARM.equals(owner)&&!JOYSTICK_STARTUP.equals(owner))
            throw new IllegalStateException("前次沿路工作待清理，請到花田復原後再啟動其他GPS工作");
        if(CoreUpdater.pending(context))throw new CoreNotReady("核心更新待重開／核對");
        if (SetupDeployment.pending(context)) throw new IllegalStateException("裝置部署尚待完成或核對，請到裝置設定繼續");
        if (UpdateInstaller.pending(context)) throw new IllegalStateException("更新安裝確認中，請先完成或取消安裝");
        boolean otherStop = !WorkStatus.allError(context).isEmpty()
                || "stop-incomplete".equals(WorkStatus.state(context, BATCH))
                || WorkProgress.active(WorkStatus.state(context, BATCH));
        if(recovery && BATCH.equals(owner))otherStop=!WorkStatus.allError(context).isEmpty()
                || "stop-incomplete".equals(WorkStatus.state(context,FLOWER_FARM))
                || WorkProgress.active(WorkStatus.state(context,FLOWER_FARM));
        if (WorkStatus.stoppingAll || (recovery ? otherStop : WorkStatus.blocked(context)))
            throw new IllegalStateException("停止尚未確認，請到總覽重試停止全部");
        if (recovery && (DispatchBatchService.isRunning() || FlowerFarmService.isRunning()))
            throw new IllegalStateException("工作仍在執行，不能復原");
        SharedPreferences prefs = prefs(context);
        String held = prefs.getString(OWNER, "");
        long age = System.currentTimeMillis() - prefs.getLong(TOUCHED_MS, 0L);
        if (!held.isEmpty() && !owner.equals(held) && age <= STALE_MS)
            throw new Busy("GPS 控制正由「" + label(held) + "」執行中持有");
        prefs.edit().putString(OWNER, owner).putLong(TOUCHED_MS, System.currentTimeMillis()).commit();
    }

    static synchronized void touch(Context context, String owner) {
        SharedPreferences prefs = prefs(context);
        if (owner.equals(prefs.getString(OWNER, "")))
            prefs.edit().putLong(TOUCHED_MS, System.currentTimeMillis()).apply();
    }

    static synchronized void release(Context context, String owner) {
        SharedPreferences prefs = prefs(context);
        if (owner.equals(prefs.getString(OWNER, "")))
            prefs.edit().remove(OWNER).remove(TOUCHED_MS).commit();
    }
    static synchronized void releaseFarmChecked(Context context) {
        SharedPreferences p=prefs(context);
        String held=p.getString(OWNER,"");
        if(!held.isEmpty() && !FLOWER_FARM.equals(held))throw new IllegalStateException("GPS工作權已變更，不清除其他工作的狀態");
        FarmRecovery.persist(()->p.edit().remove(OWNER).remove(TOUCHED_MS).commit());
        if(!p.getString(OWNER,"").isEmpty())throw new IllegalStateException("GPS工作權釋放未確認");
    }
    static synchronized void releaseBatchChecked(Context c) {
        SharedPreferences p=prefs(c);
        if(!BATCH.equals(p.getString(OWNER,"")))throw new IllegalStateException("工作權已變更，不清除其他工作");
        if(!p.edit().remove(OWNER).remove(TOUCHED_MS).commit())throw new IllegalStateException("批次工作權釋放未保存");
    }

    /** Advisory display/STOP signalling only. Never grants command or installation authority. */
    static boolean observedHeldBy(Context context, String owner) {
        java.util.Map<String,?> values=prefs(context).getAll();
        String held=values.containsKey(OWNER)?(String)values.get(OWNER):"";
        long touched=values.containsKey(TOUCHED_MS)?(Long)values.get(TOUCHED_MS):0L;
        return owner.equals(held)&&System.currentTimeMillis()-touched<=STALE_MS;
    }
    static boolean observedAnyHeld(Context context) {
        java.util.Map<String,?> values=prefs(context).getAll();
        String held=values.containsKey(OWNER)?(String)values.get(OWNER):"";
        long touched=values.containsKey(TOUCHED_MS)?(Long)values.get(TOUCHED_MS):0L;
        long age=System.currentTimeMillis()-touched;
        return !held.isEmpty()&&age>=0&&age<=STALE_MS;
    }
    static synchronized boolean heldBy(Context context, String owner) {
        SharedPreferences prefs = prefs(context);
        String held = prefs.getString(OWNER, "");
        long age = System.currentTimeMillis() - prefs.getLong(TOUCHED_MS, 0L);
        return owner.equals(held) && age <= STALE_MS;
    }

    static synchronized boolean anyHeld(Context context) {
        SharedPreferences prefs = prefs(context);
        String held = prefs.getString(OWNER, "");
        long age = System.currentTimeMillis() - prefs.getLong(TOUCHED_MS, 0L);
        return !held.isEmpty() && age >= 0 && age <= STALE_MS;
    }

    static String label(String owner) {
        if ("settings-restore".equals(owner)) return "設定同步";
        if ("collection-settings".equals(owner)) return "領取設定同步";
        if ("nearby-settings".equals(owner)) return "附近派遣設定";
        return JOYSTICK_STARTUP.equals(owner) ? "GPS 啟動檢查" : FLOWER_FARM.equals(owner) ? "花田採果" : BATCH.equals(owner) ? "批次探險" : "未知工作";
    }

    /** Called under this class monitor before persisting the PackageInstaller session. */
    static synchronized void requireInstallIdle(Context c) {
        requireInstallIdle(c,false);
    }
    static synchronized void requireCoreRecoveryIdle(Context c) {
        requireInstallIdle(c,true);
    }
    private static void requireInstallIdle(Context c,boolean coreRecovery) {
        if(RouteSession.pending(c))throw new IllegalStateException("沿路工作待收尾，請先復原確認");
        if(!coreRecovery&&CoreUpdater.pending(c))throw new IllegalStateException("核心更新待重開／核對");
        if (SetupDeployment.pending(c)) throw new IllegalStateException("裝置部署尚待完成或核對");
        String owner=prefs(c).getString(OWNER, "");
        long age=System.currentTimeMillis()-prefs(c).getLong(TOUCHED_MS,0);
        if(WorkStatus.stoppingAll || WorkStatus.blocked(c) || DispatchBatchService.isRunning() || FlowerFarmService.isRunning()
                || WorkProgress.active(WorkStatus.state(c,BATCH)) || WorkProgress.active(WorkStatus.state(c,FLOWER_FARM))
                || (!owner.isEmpty() && age<=STALE_MS))
            throw new IllegalStateException("工作執行或停止確認中，暫不可安裝更新");
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

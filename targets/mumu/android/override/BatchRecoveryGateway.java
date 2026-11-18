package dev.ody.pikmincontrol;

import android.content.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scoped recovery under the same GPS/settings exclusion as batch and daily automation. */
final class BatchRecoveryGateway {
    static final class PendingReview extends IllegalStateException {
        final String id,checksum;
        PendingReview(WorkCheckpoint cp){super("派遣結果待人工核對；不重送，也不算成功");id=cp.id;checksum=cp.properties().getProperty("checksum");}
    }
    static PendingReview review(Throwable error) {
        for(int i=0;error!=null&&i<8;i++,error=error.getCause())if(error instanceof PendingReview)return (PendingReview)error;
        return null;
    }
    private static final AtomicBoolean checking=new AtomicBoolean();
    static boolean checking(){return checking.get();}
    static boolean needed(Context c) {
        return BatchRecovery.needed(WorkStatus.state(c,GpsWorkLock.BATCH),NearbyWorkState.ownedBy(c,GpsWorkLock.BATCH));
    }
    static String checkAndReset(Context c) throws Exception {
        return checkAndReset(c,null,null);
    }
    static String checkAndReset(Context c,String reviewedId,String reviewedChecksum) throws Exception {
        if(!checking.compareAndSet(false,true))throw new IllegalStateException("批次診斷仍在進行");
        boolean held=false;
        try {
            EmulatorSavedDailyStore saved=EmulatorSavedDailyStore.open(c);
            EmulatorSavedDailyRuntime.requireStoppedBatchReview(c,saved);
            GpsWorkLock.acquireBatchRecovery(c);held=true;
            // choose/STOP and the monitor cannot rearm between readback and durable outcome.
            synchronized(AutomationPreferences.class) {
                return BatchRecovery.run(new BatchRecovery.Backend() {
                    public void guard() {
                        if(DispatchBatchService.isRunning() || FlowerFarmService.isRunning()
                                || !GpsWorkLock.heldBy(c,GpsWorkLock.BATCH) || WorkStatus.stoppingAll)
                            throw new IllegalStateException("工作狀態已改變，請先安全停止");
                        try{EmulatorSavedDailyRuntime.requireStoppedBatchReview(c,saved);}
                        catch(Exception changed){throw new IllegalStateException("每日原件或目前遊戲狀態待確認；保留未決",changed);}
                    }
                    public void begin(){WorkStatus.batchRecovery(c,"stopping","正在診斷批次控制；不開始派遣");}
                    public void stop()throws Exception{guard();DispatchProbeGateway.stopBatch();}
                    public void verify()throws Exception{StopChecks.dispatch();}
                    public void requireResolved()throws Exception {
                        // Missing is not a broken file: corrupt/truncated checkpoints fail closed.
                        if(!CheckpointStore.present(c,GpsWorkLock.BATCH))return;
                        WorkCheckpoint cp=CheckpointStore.read(c,GpsWorkLock.BATCH);
                        if(!cp.uncertain)return;
                        if(CheckpointStore.archivedReviewed(c,cp))return;
                        if(!cp.id.equals(reviewedId)||!cp.properties().getProperty("checksum").equals(reviewedChecksum))throw new PendingReview(cp);
                        // The current producer reports busy conservatively, never an idle certificate.
                        // This is explicit archival consent, NOT evidence the old RPC was cancelled.
                        if(!BatchRecovery.reviewed(cp,reviewedId,reviewedChecksum,SessionIdentity.read()))
                            throw new IllegalStateException("目前帳號或原生資料未確認，不能封存恢復");
                        CheckpointStore.archiveUncertain(c,cp); // Original stays uncertain: old resume remains forbidden.
                    }
                    public void restoreNearby()throws Exception{NearbyWorkState.restoreDeferred(c,GpsWorkLock.BATCH);}
                    public void release(){GpsWorkLock.releaseBatchChecked(c);}
                    public void complete(){WorkStatus.batchRecovery(c,"stopped","診斷與停止回讀完成；原附近派遣設定交還背景監控。未確認紀錄不算成功、不接續重送；請重新掃描");}
                    public void failed(String detail){WorkStatus.batchRecovery(c,"stop-incomplete",detail);}
                });
            }
        } finally {
            if(held)GpsWorkLock.release(c,GpsWorkLock.BATCH);
            checking.set(false);
            CollectionMonitorService.sync(c);
        }
    }
}

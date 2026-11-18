package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Scoped recovery under the same GPS/settings exclusion as batch and daily automation. */
final class BatchRecoveryGateway {
    static final class PendingReview extends IllegalStateException {
        final String id,checksum;
        PendingReview(WorkCheckpoint cp){this(cp,"");}
        PendingReview(WorkCheckpoint cp,String detail){super("派遣結果待人工核對；不重送，也不算成功"+detail);id=cp.id;checksum=cp.properties().getProperty("checksum");}
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
        final boolean[] switched={false};
        try {
            GpsWorkLock.acquireBatchRecovery(c);held=true;
            // choose/STOP and the monitor cannot rearm between readback and durable outcome.
            synchronized(AutomationPreferences.class) {
                String result=BatchRecovery.run(new BatchRecovery.Backend() {
                    public void guard() {
                        if(DispatchBatchService.isRunning() || FlowerFarmService.isRunning()
                                || !GpsWorkLock.heldBy(c,GpsWorkLock.BATCH) || WorkStatus.stoppingAll)
                            throw new IllegalStateException("工作狀態已改變，請先安全停止");
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
                        freshIdentity(c,cp,switched);
                        CoreUpdater.requireReady(c);
                        guard();
                        if(BatchReconciliationStore.present(c,cp)) {
                            BatchReconciliationStore.get(c,cp);
                            if(!cp.account.equals(SessionIdentity.read()))throw new IllegalStateException("帳號回報已過期或變更，未重用對帳收據");
                            WorkStatus.batchReconciled(c,cp);return;
                        }
                        String readFailure="";
                        String[] reports=null;
                        try {
                            reports=ProbeSnapshot.checkedSections(BoundedCommand.root("set -e; "+ProbeSnapshot.command(
                                history("/data/local/tmp/pikmin-dispatch-batch-status-history.tsv"),
                                history("/data/user/0/com.nianticlabs.pikmin/files/return_reward_history.tsv")),6,600_000),2);
                        } catch(Exception read) {readFailure="；遲到領回對帳未完成："+read.getMessage();}
                        long now=System.currentTimeMillis();
                        if(reports!=null && BatchLateReceipt.find(cp,reports[0],reports[1],now)!=null) {
                            guard();
                            if(!cp.account.equals(SessionIdentity.read()))throw new IllegalStateException("帳號回報已過期或變更，未完成對帳，請重新診斷");
                            BatchReconciliationStore.save(c,cp,reports[0],reports[1],now);
                            WorkStatus.batchReconciled(c,cp);return;
                        }
                        if(!cp.id.equals(reviewedId)||!cp.properties().getProperty("checksum").equals(reviewedChecksum))throw new PendingReview(cp,readFailure);
                        // The current producer reports busy conservatively, never an idle certificate.
                        // This is explicit archival consent, NOT evidence the old RPC was cancelled.
                        if(!BatchRecovery.reviewed(cp,reviewedId,reviewedChecksum,SessionIdentity.read()))
                            throw new IllegalStateException("目前帳號或原生資料未確認，不能封存恢復");
                        CheckpointStore.archiveUncertain(c,cp); // Original stays uncertain: old resume remains forbidden.
                    }
                    public void restoreNearby()throws Exception{NearbyWorkState.restoreDeferred(c,GpsWorkLock.BATCH);}
                    public void release(){GpsWorkLock.releaseBatchChecked(c);}
                    public void complete(){WorkStatus.batchRecovery(c,"stopped","診斷與停止回讀完成；原附近派遣設定交還背景監控。未確認紀錄不算成功、不接續重送；請重新掃描"+WorkStatus.batchReconciliationSummary(c));}
                    public void failed(String detail){WorkStatus.batchRecovery(c,"stop-incomplete",detail);}
                });
                return result+WorkStatus.batchReconciliationSummary(c);
            }
        } finally {
            if(held)GpsWorkLock.release(c,GpsWorkLock.BATCH);
            checking.set(false);
            CollectionMonitorService.sync(c);
            if(switched[0])returnToController(c);
        }
    }
    private static void returnToController(Context c) {
        try {
            BoundedCommand.Result returned=BoundedCommand.root(BatchRecoveryReturn.command(c.getPackageName(),c.getApplicationInfo().uid),4,4096);
            if(!BatchRecoveryReturn.accepted(returned,c.getPackageName()))throw new IllegalStateException("控制中心返回畫面未確認");
        } catch(Exception unavailable) {
            android.util.Log.w("PikminBatchRecovery","Recovery result retained; UI return unconfirmed",unavailable);
            // Navigation failure must not replace a PendingReview or erase its outcome.
            try {c.startActivity(new Intent(c,MainActivity.class).putExtra(MainActivity.EXTRA_OPEN_DISPATCH,true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));}
            catch(RuntimeException failed) {android.util.Log.w("PikminBatchRecovery","Open Control Center from its notification",failed);}
        }
    }
    private static String history(String path) {
        // Retain bounded recent rows, not a fixed total-file size limit. The pure
        // checker must prove that this slice still covers the checkpoint window.
        return "test -f "+path+" && test ! -L "+path+" && tail -n 800 "+path;
    }
    private static void freshIdentity(Context c,WorkCheckpoint cp,boolean[] switched)throws Exception {
        BatchRecoveryRefresh.require(new BatchRecoveryRefresh.Backend() {
            public BatchRecoveryRefresh.Sample read()throws Exception {
                String[] p=ProbeSnapshot.checkedSections(BoundedCommand.root("set -e; "+ProbeSnapshot.command(
                    OwnerGameProcess.command(),
                    "cat /data/user/0/com.nianticlabs.pikmin/files/automation_session.tsv",
                    OwnerGameProcess.command()),3,8192),3);
                return new BatchRecoveryRefresh.Sample(p[1],p[0].trim(),p[2].trim(),System.currentTimeMillis());
            }
            public long elapsed(){return SystemClock.elapsedRealtime();}
            public void openGame() {
                Intent launch=c.getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                if(launch==null)throw new IllegalStateException("找不到遊戲入口，無法更新帳號回報");
                c.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                switched[0]=true;
            }
            public void waitForUpdate()throws Exception {Thread.sleep(500);}
            public void guard()throws Exception {
                if(WorkStatus.stoppingAll || DispatchBatchService.isRunning() || FlowerFarmService.isRunning()
                        || !GpsWorkLock.heldBy(c,GpsWorkLock.BATCH))throw new IllegalStateException("工作狀態已改變，停止更新回報");
                WorkCheckpoint current=CheckpointStore.read(c,GpsWorkLock.BATCH);
                if(!current.properties().getProperty("checksum").equals(cp.properties().getProperty("checksum")))
                    throw new IllegalStateException("原批次已改變，請重新診斷");
                GpsWorkLock.touch(c,GpsWorkLock.BATCH);
            }
        },cp.account);
    }
}

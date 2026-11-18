package dev.ody.pikmincontrol;

/** Stop/diagnose only: never starts GPS, dispatches, or erases an uncertain checkpoint. */
final class BatchRecovery {
    interface Backend {
        void guard() throws Exception;
        void begin() throws Exception;
        void stop() throws Exception;
        void verify() throws Exception;
        void requireResolved() throws Exception;
        void restoreNearby() throws Exception;
        void release() throws Exception;
        void complete() throws Exception;
        void failed(String detail) throws Exception;
    }
    static boolean needed(String state,boolean pending) {
        return pending || WorkProgress.active(state) || "error".equals(state) || "stop-incomplete".equals(state);
    }
    static boolean reviewed(WorkCheckpoint cp,String id,String checksum,String identity) {
        return cp!=null && "batch".equals(cp.owner) && cp.uncertain && cp.id.equals(id)
            && cp.properties().getProperty("checksum").equals(checksum)
            && !cp.account.isEmpty() && cp.account.equals(identity);
    }
    static String run(Backend b) throws Exception {
        b.guard(); // A rejected attempt must not alter the other work's state or controls.
        String stage="保存診斷狀態";
        try {
            b.begin();
            stage="關閉批次控制"; b.stop();
            stage="停止回讀"; b.verify();
            stage="派遣結果核對"; b.requireResolved();
            stage="恢復附近派遣"; b.restoreNearby();
            stage="保存恢復結果"; b.complete();
            stage="釋放工作權"; b.release();
            return "批次控制已恢復 · 請重新掃描，不接續重送舊批次；附近派遣依原設定恢復。";
        } catch(Exception e) {
            String detail=stage+"未完成："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            // A failed read/store/restore must remain blocked, not be disguised as recovery.
            try {b.failed(detail);}catch(Exception save){e.addSuppressed(save);}
            try {b.stop();b.verify();}catch(Exception stop){e.addSuppressed(stop);}
            throw new IllegalStateException(detail,e);
        }
    }
}

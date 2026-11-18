package dev.ody.pikmincontrol;

import java.io.IOException;

/** Durable transitions; interrupted mutations are reviewed, never implicitly replayed. */
final class CoreMigrationFlow {
    interface Port {
        String phase();
        boolean rebooted() throws Exception;
        void persist(String phase) throws Exception;
        void verifyBackup() throws Exception;
        void requireIdle() throws Exception;
        void verifyOriginal() throws Exception;
        void disableGps() throws Exception;
        void verifyGpsDisabled() throws Exception;
        void stage(boolean rollback) throws Exception;
        void verifyStaged(boolean rollback) throws Exception;
        boolean loaded(boolean rollback) throws Exception;
        void requireNoStaged() throws Exception;
        boolean restoreGpsNeedsReboot() throws Exception;
        void restoreGps() throws Exception;
        void verifyRestoredGps() throws Exception;
        void verifyOldFiles() throws Exception;
    }
    private static void require(boolean ok,String message)throws IOException{if(!ok)throw new IOException(message);}
    static boolean bootChanged(String current,String phaseBoot,String backupBoot)throws IOException {
        // A missing phase epoch may use the initial backup epoch, but malformed data is not a reboot.
        String previous=phaseBoot==null||phaseBoot.isEmpty()?backupBoot:phaseBoot;
        String uuid="[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}";
        require(current!=null&&current.matches(uuid)&&previous!=null&&previous.matches(uuid),"重開機識別不完整，保留遷移紀錄待核對");
        return !current.equals(previous);
    }
    static void install(Port p)throws Exception {
        require("backed-up".equals(p.phase()),"遷移已開始或狀態不明，請核對或回退，不重送安裝");
        require(!p.rebooted(),"備份後已重開，需重新核對，不能直接安裝");
        p.verifyBackup();p.requireIdle();p.verifyOriginal();
        p.persist("gps-disable-intent");p.disableGps();p.verifyGpsDisabled();
        p.requireIdle();p.persist("install-intent");p.stage(false);p.verifyStaged(false);p.persist("staged");
    }
    static void accept(Port p)throws Exception {
        require(isForward(p.phase())||"accepted".equals(p.phase()),"不是可核對的遷移狀態");
        require(p.rebooted(),"請先重開機");p.requireNoStaged();p.verifyGpsDisabled();
        require(p.loaded(false),"尚未確認新核心及GPS同程序載入");p.persist("accepted");
    }
    static boolean isForward(String phase){return "gps-disable-intent".equals(phase)||"install-intent".equals(phase)||"staged".equals(phase);}
    static void rollback(Port p)throws Exception {
        require(isForward(p.phase())||"accepted".equals(p.phase())||"rollback-intent".equals(p.phase())||"rollback-staged".equals(p.phase()),"尚未變更模組或已進入GPS恢復，請核對目前狀態");
        require(p.rebooted(),"請先重開，避免與尚未結束的安裝程序競爭");
        p.verifyBackup();p.requireIdle();p.requireNoStaged();p.verifyGpsDisabled();
        p.persist("rollback-intent");p.stage(true);p.verifyStaged(true);p.persist("rollback-staged");
    }
    static void reviewRollback(Port p)throws Exception {
        String phase=p.phase();
        require("rollback-intent".equals(phase)||"rollback-staged".equals(phase)||"gps-restore-intent".equals(phase)||"gps-reboot".equals(phase)||"rolled-back".equals(phase),"沒有可核對的回退");
        require(p.rebooted(),"回退仍需重開機");p.verifyBackup();p.requireNoStaged();
        require(p.loaded(true),"尚未確認舊核心載入，不能恢復舊GPS");
        if("gps-reboot".equals(phase)||"gps-restore-intent".equals(phase)||"rolled-back".equals(phase)) {
            // A crash after intent may or may not have restored the marker. Never guess/replay.
            p.verifyRestoredGps();p.persist("rolled-back");return;
        }
        p.persist("old-core-confirmed");
    }
    // Explicit user action after closing the game; do not require a live PID and closed game at once.
    static void finishRollback(Port p)throws Exception {
        boolean retry="gps-restore-intent".equals(p.phase());
        require("old-core-confirmed".equals(p.phase())||retry,"請先開啟遊戲核對舊核心，再關閉遊戲完成回退");
        if(retry)require(p.rebooted(),"恢復中斷後請先重開，再確認重試");
        p.verifyBackup();p.requireNoStaged();p.verifyOldFiles();
        boolean needsReboot=p.restoreGpsNeedsReboot();
        p.requireIdle();p.verifyGpsDisabled();p.persist("gps-restore-intent");p.restoreGps();p.verifyRestoredGps();
        p.persist(needsReboot?"gps-reboot":"rolled-back");
    }
}

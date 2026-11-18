package dev.ody.pikmincontrol;

import java.io.IOException;

/** First-install transaction ordering, shared by the Android port and interruption tests. */
final class CoreFreshFlow {
    interface Port {
        String phase();
        boolean rebooted() throws Exception;
        void verifyBackup() throws Exception;
        void requireIdle() throws Exception;
        void requireOriginal() throws Exception;
        void persist(String phase) throws Exception;
        void disableGps() throws Exception;
        void requireDisabled() throws Exception;
        void applyCopy() throws Exception;
        void verifyCopy() throws Exception;
        void stage() throws Exception;
        void verifyStaged() throws Exception;
        void prepareRemoval() throws Exception;
        void restoreCopy() throws Exception;
        void quarantine() throws Exception;
        void restoreGps() throws Exception;
        void verifyBaseline() throws Exception;
    }
    static boolean rollbackPhase(String s){return s!=null&&s.matches("prepared|gps-intent|copy-intent|install-intent|staged|accepted|remove-intent");}
    static void install(Port p)throws Exception {
        if(!"prepared".equals(p.phase())||p.rebooted())throw new IOException("首次安裝已中斷或已重開，請核對或回復，不重送安裝");
        p.verifyBackup();p.requireIdle();p.requireOriginal();
        p.persist("gps-intent");p.disableGps();p.requireDisabled();
        p.persist("copy-intent");p.applyCopy();p.verifyCopy();
        p.requireIdle();p.requireDisabled();p.persist("install-intent");
        p.stage();p.verifyStaged();p.persist("staged");
    }
    static void rollback(Port p)throws Exception {
        if(!rollbackPhase(p.phase()))throw new IOException("請先核對首次安裝狀態");
        if(!p.rebooted())throw new IOException("請先重開，避免與未結束的安裝競爭");
        p.verifyBackup();p.requireIdle();p.prepareRemoval();p.persist("remove-intent");
        p.restoreCopy();p.quarantine();p.restoreGps();p.verifyBaseline();p.persist("removed");
    }
}

package dev.ody.pikmincontrol;
import java.io.IOException;

/** Explicit successor installation: old runtime acceptance is not required, file proof is. */
final class CoreSuccessorFlow {
    interface Port {
        String phase();
        int oldTarget();
        int newTarget();
        boolean rebooted()throws Exception;
        void verify()throws Exception;
        void archive()throws Exception;
        void prepare()throws Exception;
        void install()throws Exception;
    }
    static void check(Port p)throws Exception {
        if(!"staged".equals(p.phase())||!CorePair.upgrade(p.oldTarget(),p.newTarget()))
            throw new IOException("只可從已排入且版本較舊的核心接續升級；請先核對目前狀態");
        if(!p.rebooted())throw new IOException("原核心安裝後尚未重開，請先重開手機");
        p.verify();
    }
    static void run(Port p)throws Exception {
        check(p);p.archive();p.verify();p.prepare();p.install();
    }
}

package dev.ody.pikmincontrol;

/** Temporary ownership for a consistent scan, not dispatch admission. The
 * original restore record is retained if cleanup fails; never release ownership
 * first or replace a rejected snapshot with an empty inventory. */
final class EmulatorDailyBatchScanScope {
    interface Access {
        void acquire()throws Exception;
        void capture()throws Exception;
        void pause()throws Exception;
        void settle()throws Exception;
        void restore()throws Exception;
        void release()throws Exception;
    }
    interface Read<T> { T run()throws Exception; }
    static <T> T read(Access access,Read<T> read)throws Exception {
        if(access==null||read==null)throw new IllegalArgumentException("Exact scoped reader required");
        access.acquire(); // A refused acquisition must not restore another owner's work.
        Exception primary=null;
        try {
            access.capture(); // Durable original state before the first control write.
            access.pause();
            access.settle();
            return read.run(); // Original whole-frame validation, unchanged freshness.
        }catch(Exception failure){primary=failure;throw failure;}
        finally{
            try{access.restore();access.release();}
            catch(Exception cleanup){if(primary!=null)primary.addSuppressed(cleanup);else throw cleanup;}
        }
    }
    private EmulatorDailyBatchScanScope(){}
}

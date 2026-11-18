package dev.ody.pikmincontrol;

/** Ordered transaction: a failed stage never reaches the installer or clears old work. */
final class InstallPreparationPolicy {
    interface Backend {
        void begin() throws Exception;
        void pauseSteps() throws Exception;
        void stopWork() throws Exception;
        void pauseControls() throws Exception;
        void verify() throws Exception;
        void ready() throws Exception;
        void failed(String stage) throws Exception;
    }
    static void run(Backend b) throws Exception {
        b.begin();
        String stage="暫停步數";
        try {
            b.pauseSteps();stage="等待批次與花田收尾";b.stopWork();
            stage="暫停自動控制";b.pauseControls();stage="核對停止結果";b.verify();
            stage="保存安裝準備";b.ready();
        } catch(Exception failure) {
            String detail=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            detail=detail.replace('\n',' ').replace('\r',' ');if(detail.length()>180)detail=detail.substring(0,180);
            try {b.failed(stage+"："+detail);}catch(Exception save){failure.addSuppressed(save);}
            throw new IllegalStateException(stage+"未完成："+failure.getMessage(),failure);
        }
    }
    /** Never hold this monitor during HC I/O. The persisted pause wins new admissions. */
    static final class WriteFence {
        private int active;
        synchronized boolean enter(java.util.function.BooleanSupplier blocked) {
            if(blocked.getAsBoolean())return false;
            active++;return true;
        }
        synchronized void leave(){active--;notifyAll();}
        synchronized void persistPause(Runnable save){save.run();}
        synchronized boolean drain(long millis)throws InterruptedException {
            long end=System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
            while(active>0){long left=end-System.nanoTime();if(left<=0)return false;
                java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,left);}
            return true;
        }
    }
}

package dev.ody.pikmincontrol;

/** Settings-only cadence; never authorizes gameplay or caches a core proof. */
final class MonitorSchedule {
    enum Result { HEALTHY, WAITING, BUSY, FAILED }
    private int misses;
    long next(Result result, boolean visible, boolean pendingStop, boolean optimized) {
        if (!optimized) { misses=0; return 15000; }
        if (result==Result.HEALTHY || result==Result.BUSY) misses=0;
        else misses=Math.min(3,misses+1);
        if (visible || pendingStop || result==Result.BUSY) return 15000;
        if (result==Result.HEALTHY) return 45000;
        return misses==1?15000:misses==2?30000:60000;
    }
}

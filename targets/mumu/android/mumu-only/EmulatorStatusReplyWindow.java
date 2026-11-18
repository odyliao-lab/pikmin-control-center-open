package dev.ody.pikmincontrol;
/** Pure monotonic two-second window. No response or provider authority. */
final class EmulatorStatusReplyWindow {
    static final long LIMIT_MS=2000;
    private long started=-1;
    boolean pending(){return started>=0;}
    void begin(long now){
        if(pending()||now<0||now>Long.MAX_VALUE-LIMIT_MS)
            throw new IllegalStateException("Invalid or pending STATUS window");
        started=now;
    }
    long remaining(long now){
        if(!pending()||now<started||now-started>=LIMIT_MS)
            throw new IllegalStateException("GPS狀態回報逾時（2秒）");
        return LIMIT_MS-(now-started);
    }
    void clear(){started=-1;}
}
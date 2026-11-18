package dev.ody.pikmincontrol;

/** One passive re-read for one already acknowledged STATUS; never an effect,
 * a new reply window, or permission to accept a mismatched/expired fix. */
final class EmulatorDailyProviderRecheck {
    static final long MAX_REPLY_AGE_MS=2000;
    private long acknowledgedAt;
    private boolean observed,consumed;
    void reset(){acknowledgedAt=0;observed=false;consumed=false;}
    void acknowledge(long at){
        if(at<=0||acknowledgedAt!=0)throw new IllegalStateException("provider-recheck-ack");
        acknowledgedAt=at;
    }
    void observed(){
        if(acknowledgedAt<=0||observed||consumed)throw new IllegalStateException("provider-recheck-observation");
        observed=true;
    }
    void consume(long at){
        boolean allowed=observed&&!consumed;consumed=true;
        if(!allowed)throw new IllegalStateException("provider-recheck-already-consumed");
        requireFresh(at);
    }
    void requireFresh(long at){
        if(acknowledgedAt<=0||at<acknowledgedAt||at-acknowledgedAt>MAX_REPLY_AGE_MS)
            throw new IllegalStateException("provider-recheck-reply-expired");
    }
}

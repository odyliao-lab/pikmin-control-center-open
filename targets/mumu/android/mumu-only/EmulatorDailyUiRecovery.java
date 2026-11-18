package dev.ody.pikmincontrol;

/** UI-only reducer. A cleared dialog NEVER means START/dispatch/claim succeeded. */
final class EmulatorDailyUiRecovery {
    enum Status { CLEAR, IDLE_UNKNOWN, WAITING, TRANSITION, STOPPED, LIMITED }
    static final class Frame {
        final EmulatorDailyUiMatcher.Scene scene;final byte[] raw;final long clock,monotonic;
        Frame(EmulatorDailyUiMatcher.Scene scene,byte[] raw,long clock,long monotonic){if(scene==null||raw==null)throw new IllegalArgumentException();this.scene=scene;this.raw=raw;this.clock=clock;this.monotonic=monotonic;}
    }
    interface Port {
        boolean active()throws Exception;
        Frame capture()throws Exception;
        long monotonic();
        boolean navigationAllowed()throws Exception;
        void tap(Frame held)throws Exception; // rechecks exact identity/ticket/clock immediately before input
    }
    interface Store {
        String pending()throws Exception;
        default EmulatorDailyUiMatcher.Scene pendingScene(String id)throws Exception{return EmulatorDailyUiMatcher.Scene.UNKNOWN;}
        int count()throws Exception;
        String begin(Frame frame)throws Exception; // durable BEFORE input, original immutable
        void confirmed(String id,Frame after,boolean map)throws Exception;
        void diagnostic(Frame frame)throws Exception;
    }
    private final Port port;private final Store store;
    private int episode;private long lastAction=-10000;
    EmulatorDailyUiRecovery(Port port,Store store){if(port==null||store==null)throw new IllegalArgumentException();this.port=port;this.store=store;}
    Status tick()throws Exception {
        if(!port.active())return Status.STOPPED;
        Frame before=port.capture();if(!fresh(before))return Status.WAITING;
        String pending=store.pending();
        if(before.scene==EmulatorDailyUiMatcher.Scene.MAP){
            if(pending!=null)store.confirmed(pending,before,true);
            episode=0;return Status.CLEAR;
        }
        // A previous unconfirmed action is NEVER resent, even after process death.
        if(pending!=null){
            // Animation may outlast the immediate post-input frame. A fresh,
            // different known scene confirms only this UI transition, never a
            // game effect. The old input is not resent; navigation needs its
            // own new intent on a later tick. Same/unknown scenes stay pending.
            EmulatorDailyUiMatcher.Scene original=store.pendingScene(pending);
            if(transition(original,before.scene)){
                store.confirmed(pending,before,false);return Status.TRANSITION;
            }
            store.diagnostic(before);return Status.WAITING;
        }
        if(!before.scene.clickable()){store.diagnostic(before);return Status.IDLE_UNKNOWN;}
        if((before.scene.navigation()||before.scene.reward())&&!port.navigationAllowed())return Status.WAITING;
        if(episode>=8||store.count()>=128)return Status.LIMITED;
        if(port.monotonic()-lastAction<1800)return Status.WAITING;
        if(!port.active()||!fresh(before))return Status.STOPPED;
        String id=store.begin(before); // sync failure => no input, pending retained
        episode++;lastAction=port.monotonic();
        try{port.tap(before);}catch(Exception unknown){
            try{store.diagnostic(before);}catch(Exception evidence){unknown.addSuppressed(evidence);}throw unknown;
        } // any error leaves pending, no retry
        store.diagnostic(before); // large private image writes are outside the pre-input 3s window
        if(!port.active())return Status.STOPPED;
        Frame after=port.capture();
        if(!fresh(after)){return Status.WAITING;}
        if(after.scene==EmulatorDailyUiMatcher.Scene.MAP){store.confirmed(id,after,true);episode=0;return Status.CLEAR;}
        if(transition(before.scene,after.scene)){store.confirmed(id,after,false);return Status.TRANSITION;}
        store.diagnostic(after);return Status.WAITING;
    }
    private boolean fresh(Frame f){long age=port.monotonic()-f.monotonic;return f.clock>0&&age>=0&&age<=3000;}
    // A new process may have reached another copy of the same reward dialog.
    // Only a clear main scene permits an owner handoff. Old inputs stay UNKNOWN;
    // this observation never confirms their game effects or authorizes a replay.
    static boolean ownerChangeAllowed(boolean sameAccount,boolean sameCore,boolean oldOwnerEnded,EmulatorDailyUiMatcher.Scene scene){
        return sameAccount&&sameCore&&oldOwnerEnded&&(scene==EmulatorDailyUiMatcher.Scene.MAP||scene==EmulatorDailyUiMatcher.Scene.HOME||scene==EmulatorDailyUiMatcher.Scene.PROFILE);
    }
    private static boolean transition(EmulatorDailyUiMatcher.Scene original,EmulatorDailyUiMatcher.Scene next){
        // A network/speed/another reward dialog is not clearance of a submitted claim.
        // Retain its original intent through errors/restarts; never reconfirm it blindly.
        if(original==EmulatorDailyUiMatcher.Scene.REWARD_CONFIRM)return next==EmulatorDailyUiMatcher.Scene.HOME||next==EmulatorDailyUiMatcher.Scene.PROFILE;
        return original.clickable()&&next.clickable()&&next!=original;
    }
}

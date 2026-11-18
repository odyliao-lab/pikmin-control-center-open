package dev.ody.pikmincontrol;

/** Lifecycle part of the MuMu handoff. Restored is deliberately NOT drained.
 * Caller must hold the product GPS fence for the entire run/observation.
 * Journal writes must be durable and read back before each external effect. */
final class EmulatorGpsHandoff {
    enum Phase { PREPARED, STOP_INTENT, OLD_EXITED, START_INTENT, RESTORED }
    static final class Binding {
        final String boot,service,operation;
        final int gamePid,oldPid;
        final long oldStartTicks;
        Binding(String boot,int gamePid,int oldPid,long ticks,String service,String operation) {
            if(boot==null||!boot.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")||
               gamePid<=0||oldPid<=0||ticks<=0||service==null||!service.matches("[0-9a-f]+")||
               operation==null||!operation.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                throw new IllegalArgumentException("Independent original lifecycle binding required");
            this.boot=boot;this.gamePid=gamePid;this.oldPid=oldPid;oldStartTicks=ticks;
            this.service=service;this.operation=operation;
        }
        boolean same(Binding b){return b!=null&&boot.equals(b.boot)&&gamePid==b.gamePid&&oldPid==b.oldPid&&
            oldStartTicks==b.oldStartTicks&&service.equals(b.service)&&operation.equals(b.operation);}
    }
    static final class Entry {
        final Binding binding;final Phase phase;
        Entry(Binding binding,Phase phase){if(binding==null||phase==null)throw new IllegalArgumentException();this.binding=binding;this.phase=phase;}
    }
    interface Port {
        Entry read() throws Exception;
        void persist(Entry entry) throws Exception;
        // Checks live held fence, unchanged game/boot and absence of competing
        // jobs. For PREPARED also requires original service/PID/startticks.
        boolean allowed(Binding binding,Phase phase) throws Exception;
        void stopOriginal(Binding binding) throws Exception;
        // Unknown/timeout is false, never permission to replay stop.
        boolean originalExited(Binding binding) throws Exception;
        void startSavedPosition(Binding binding) throws Exception;
        // New foreground service/PID, same-position continuing fixes, unchanged
        // game/boot. Never accepts just am's exit or a STATUS reply.
        boolean restored(Binding binding) throws Exception;
    }
    private static Entry save(Port port,Binding binding,Phase phase)throws Exception{
        Entry value=new Entry(binding,phase);port.persist(value);
        Entry read=port.read();
        if(read==null||!binding.same(read.binding)||read.phase!=phase)
            throw new IllegalStateException("Lifecycle intent readback differs; no effect");
        return read;
    }
    static Phase run(Port port,Binding binding,boolean authorized)throws Exception{
        if(!authorized)throw new SecurityException("MuMu lifecycle approval required");
        Entry entry=port.read();
        if(entry==null)entry=save(port,binding,Phase.PREPARED);
        if(!binding.same(entry.binding))throw new IllegalStateException("Original lifecycle retained; another binding refused");
        if(!port.allowed(binding,entry.phase))throw new IllegalStateException("GPS lifecycle exclusion/identity unknown");
        if(entry.phase==Phase.PREPARED){
            entry=save(port,binding,Phase.STOP_INTENT);
            if(!port.allowed(binding,Phase.PREPARED))throw new IllegalStateException("Stop preflight changed; intent retained");
            // Intent is retained even when the command throws or returns no reply.
            port.stopOriginal(binding);
        }
        if(entry.phase==Phase.STOP_INTENT){
            if(!port.originalExited(binding))return Phase.STOP_INTENT;
            entry=save(port,binding,Phase.OLD_EXITED);
        }
        if(entry.phase==Phase.OLD_EXITED){
            if(!port.allowed(binding,Phase.OLD_EXITED)||!port.originalExited(binding))
                throw new IllegalStateException("Original exit/exclusion changed; no start");
            entry=save(port,binding,Phase.START_INTENT);
            if(!port.allowed(binding,Phase.OLD_EXITED)||!port.originalExited(binding))
                throw new IllegalStateException("Start preflight changed; intent retained");
            port.startSavedPosition(binding);
        }
        if(entry.phase==Phase.START_INTENT){
            if(!port.restored(binding))return Phase.START_INTENT;
            entry=save(port,binding,Phase.RESTORED);
        }
        if(entry.phase==Phase.RESTORED && !port.restored(binding))
            throw new IllegalStateException("Restored service no longer matches; no lifecycle replay");
        return entry.phase;
    }
    private EmulatorGpsHandoff(){}
}

package dev.ody.pikmincontrol;

/** Started MuMu transaction closure, not GPS cleanup or route success.
 * The original fresh route ownership must pass first; the terminal port MUST
 * be the strict complete native closed-round consumer. No legacy stop-bit
 * fallback, status synthesis or command is provided by this class. */
final class EmulatorDailyStopGate {
    interface Ownership {void require()throws Exception;}
    interface Terminal<T> {T read()throws Exception;}
    static <T> T started(Ownership owner,Terminal<T> terminal)throws Exception {
        if(owner==null||terminal==null)throw new IllegalArgumentException("Complete owned terminal ports required");
        owner.require();
        T proof=terminal.read();
        if(proof==null)throw new IllegalStateException("Complete closed native round required");
        return proof;
    }
}

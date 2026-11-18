package dev.ody.pikmincontrol;

/** MuMu read-only STATUS snapshot ordering, inside the existing admitted GPS
 * actor. This never admits an effect, acknowledges a reply or accepts a fix.
 * Full frame freshness/identity/10m checks remain at the original consumer. */
final class EmulatorDailyStatusFrameOrder {
    interface Read<T> { T get() throws Exception; }
    interface Status { void request() throws Exception; }
    static <T> T capture(Read<T> frame,Status status)throws Exception {
        if(frame==null||status==null)throw new IllegalArgumentException("Complete STATUS capture ports required");
        T held=frame.get();
        if(held==null)throw new IllegalStateException("Complete STATUS frame required");
        status.request();
        // A failed/unknown request never publishes this pre-request frame.
        // The caller still withholds its permit until original actor exit and
        // a valid response; this return is NOT acknowledgement/readiness.
        return held;
    }
}

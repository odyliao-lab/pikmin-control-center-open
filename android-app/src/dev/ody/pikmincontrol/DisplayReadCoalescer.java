package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** UI observations ONLY. Shares in-flight reads, never caches a completed result.
 * No global root queue: different pages, STOP, GPS leases and safety reads run independently.
 */
final class DisplayReadCoalescer {
    enum Key { RETURN, NECTAR }
    interface Read { BoundedCommand.Result run() throws Exception; }
    private static final class Flight {
        final long generation;
        final CountDownLatch done = new CountDownLatch(1);
        BoundedCommand.Result result; Exception error;
        Flight(long generation) { this.generation = generation; }
    }
    private final EnumMap<Key,Flight> flights = new EnumMap<>(Key.class);
    private long generation;
    private boolean enabled = true;
    synchronized void invalidate() { generation++; flights.clear(); }
    synchronized void enabled(boolean value) { enabled=value; invalidate(); }
    BoundedCommand.Result read(Key key, Read read) throws Exception {
        Flight flight; boolean owner; boolean bypass;
        synchronized(this) {
            bypass=!enabled;
            flight=flights.get(key); owner=flight==null;
            if (!bypass && owner) { flight=new Flight(generation); flights.put(key,flight); }
        }
        if (bypass) return read.run();
        if (owner) {
            try { flight.result=read.run(); }
            catch(Exception e) { flight.error=e; }
            finally {
                synchronized(this) { if(flights.get(key)==flight)flights.remove(key); }
                flight.done.countDown();
            }
        } else {
            RootQueryStats.shared();
            if (!flight.done.await(8500,TimeUnit.MILLISECONDS)) throw new IOException("畫面查詢等待逾時");
        }
        synchronized(this) {
            if(flight.generation!=generation)throw new IOException("畫面或設定已變更，舊查詢已丟棄");
        }
        if(flight.error!=null)throw flight.error;
        return flight.result; // checkedSections still rejects errors, truncation and bad framing.
    }
}

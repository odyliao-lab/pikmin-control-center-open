package dev.ody.pikmincontrol;

import java.util.concurrent.*;

/** Sequential minute-scale driver, NOT an opt-in/ready authority.
 * Each invocation opens the original durable tick afresh. An exception is not
 * retried here; the next interval observes its retained journal. Stop prevents
 * future invocations without interrupting an already admitted tick/effect.
 * Android foreground lifetime, persistent opt-in and recovery remain separate.
 */
final class EmulatorDailyLoop implements AutoCloseable {
    interface Tick { EmulatorAndroidDailyRuntime.Report run() throws Exception; }
    enum Phase { NEW, RUNNING, DRAINING, STOPPED }
    enum Outcome { NONE, REPORTED, TICK_FAILED, FATAL }
    static final class Snapshot {
        final Phase phase;
        final boolean inFlight;
        final long attempted,completed;
        final Outcome outcome;
        final EmulatorAndroidDailyRuntime.Report report;
        Snapshot(Phase phase,boolean inFlight,long attempted,long completed,Outcome outcome,
                EmulatorAndroidDailyRuntime.Report report){
            this.phase=phase;this.inFlight=inFlight;this.attempted=attempted;
            this.completed=completed;this.outcome=outcome;this.report=report;
        }
    }
    private final Tick tick;
    private final long intervalMs;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> scheduled;
    private Phase phase=Phase.NEW;
    private boolean inFlight;
    private long attempted,completed;
    private Outcome outcome=Outcome.NONE;
    private EmulatorAndroidDailyRuntime.Report report;

    EmulatorDailyLoop(Tick tick,long intervalMs){
        if(tick==null||intervalMs<1||intervalMs>60000)throw new IllegalArgumentException("Bounded tick interval required");
        this.tick=tick;this.intervalMs=intervalMs;
        executor=Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"mumu-daily-tick");t.setDaemon(true);return t;
        });
    }
    synchronized void start(){
        if(phase==Phase.RUNNING)return; // repeated start does not add a timer
        if(phase!=Phase.NEW)throw new IllegalStateException("Stopped loop cannot restart or overlap draining work");
        phase=Phase.RUNNING;
        try {scheduled=executor.scheduleWithFixedDelay(this::invoke,0,intervalMs,TimeUnit.MILLISECONDS);}
        catch(RuntimeException failure){phase=Phase.STOPPED;executor.shutdown();throw failure;}
    }
    private void invoke(){
        synchronized(this){
            if(phase!=Phase.RUNNING)return;
            if(inFlight)throw new IllegalStateException("Sequential executor invariant");
            inFlight=true;attempted++;
        }
        EmulatorAndroidDailyRuntime.Report next=null;
        Outcome nextOutcome=Outcome.FATAL;
        try {
            next=tick.run();
            if(next==null)throw new IllegalStateException("Complete tick report required");
            nextOutcome=Outcome.REPORTED;
        }catch(Exception retained){
            // Do not retain/disclose raw messages (commands/coordinates may be
            // private), clear the old success and wait a FULL delay before the
            // next fresh tick. No local retry, cleanup or invented success.
            nextOutcome=Outcome.TICK_FAILED;
        }finally{
            synchronized(this){
                completed++;inFlight=false;outcome=nextOutcome;report=next;
                if(nextOutcome==Outcome.FATAL){
                    phase=Phase.STOPPED;
                    if(scheduled!=null)scheduled.cancel(false);
                    executor.shutdown();
                }else if(phase==Phase.DRAINING)phase=Phase.STOPPED;
                notifyAll();
            }
        }
    }
    synchronized Snapshot snapshot(){return new Snapshot(phase,inFlight,attempted,completed,outcome,report);}
    @Override public synchronized void close(){
        if(phase==Phase.STOPPED||phase==Phase.DRAINING)return;
        phase=inFlight?Phase.DRAINING:Phase.STOPPED;
        if(scheduled!=null)scheduled.cancel(false); // never interrupt accepted work
        executor.shutdown(); // does not await/block the Android main thread
        notifyAll();
    }
    boolean awaitStopped(long timeoutMs)throws InterruptedException{
        if(timeoutMs<0||timeoutMs>60000)throw new IllegalArgumentException("Bounded drain wait required");
        return executor.awaitTermination(timeoutMs,TimeUnit.MILLISECONDS);
    }
}

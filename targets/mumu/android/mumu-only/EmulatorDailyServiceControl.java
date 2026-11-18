package dev.ody.pikmincontrol;

import java.util.HashSet;
import java.util.Set;

/** Actual service's in-process event correlation, not an opt-in authority.
 * Persisted ticket/STOP and fresh gates remain mandatory outside this reducer.
 * OS deliveries/notifications are separate from these HOST-tested decisions.
 */
final class EmulatorDailyServiceControl {
    static final class Stop {
        final boolean closeActive,finish;
        Stop(boolean closeActive,boolean finish){this.closeActive=closeActive;this.finish=finish;}
    }
    private long generation;
    private boolean destroyed,starting;
    private EmulatorDailySchedule.Ticket requested,active;
    private final Set<String> stopped=new HashSet<>();

    synchronized long request(EmulatorDailySchedule.Ticket requested){
        if(destroyed)throw new IllegalStateException("Destroyed service cannot request work");
        this.requested=requested;starting=true;return ++generation;
    }
    synchronized boolean current(long mine){return !destroyed&&starting&&generation==mine;}
    synchronized boolean sameGeneration(long mine){return !destroyed&&generation==mine;}
    synchronized boolean admit(long mine,EmulatorDailySchedule.Ticket saved){
        if(!current(mine))return false;
        starting=false;
        boolean valid=saved!=null&&(requested==null||same(requested,saved))&&!stopped.contains(saved.encode());
        requested=null;
        if(valid)active=saved;
        return valid;
    }
    synchronized boolean failed(long mine){
        if(current(mine)){starting=false;requested=null;}
        return idle();
    }
    synchronized boolean launchFailed(long mine){
        if(sameGeneration(mine)){active=null;starting=false;requested=null;generation++;}
        return idle();
    }
    /** Called after publishing or rejecting the exact STOP request. A failure
     * is blocked locally too; it is never promoted to durable stopped success. */
    synchronized Stop stop(EmulatorDailySchedule.Ticket ticket){
        if(ticket==null)throw new IllegalArgumentException("Exact stop ticket required");
        if(destroyed)return new Stop(false,true);
        stopped.add(ticket.encode());
        boolean close=same(active,ticket);
        if(close)active=null;
        if(starting&&same(requested,ticket)){
            // Cancel only THIS pending request. A newer/different request's
            // generation is retained even if the old active timer is stopped.
            starting=false;requested=null;generation++;
        }
        // A null-intent sticky restore has no identity yet; leave it pending.
        // admit() later checks the stopped set against the actual saved ticket.
        return new Stop(close,idle());
    }
    synchronized boolean loopFinished(EmulatorDailySchedule.Ticket ticket){
        if(same(active,ticket))active=null;
        return idle(); // old timer's status must not finish a queued new restore
    }
    synchronized boolean idle(){return destroyed||(!starting&&active==null);}
    synchronized boolean destroyed(){return destroyed;}
    synchronized void destroy(){destroyed=true;starting=false;requested=null;active=null;generation++;}
    private static boolean same(EmulatorDailySchedule.Ticket a,EmulatorDailySchedule.Ticket b){return a!=null&&b!=null&&a.encode().equals(b.encode());}
}

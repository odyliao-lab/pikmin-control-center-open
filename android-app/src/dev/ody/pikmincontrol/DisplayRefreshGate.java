package dev.ody.pikmincontrol;

import java.util.EnumMap;

/** Reserve before enqueueing on Activity's single worker, not after root starts. */
final class DisplayRefreshGate {
    static final class Ticket {
        final DisplayReadCoalescer.Key key; final long generation;
        Ticket(DisplayReadCoalescer.Key key,long generation) {this.key=key;this.generation=generation;}
    }
    private final EnumMap<DisplayReadCoalescer.Key,Ticket> queued=new EnumMap<>(DisplayReadCoalescer.Key.class);
    private long generation;
    synchronized Ticket begin(DisplayReadCoalescer.Key key,boolean optimized) {
        if(optimized&&queued.containsKey(key)){RootQueryStats.skipped();return null;}
        Ticket ticket=new Ticket(key,generation);
        if(optimized)queued.put(key,ticket);
        return ticket;
    }
    synchronized boolean current(Ticket ticket) {return ticket!=null&&ticket.generation==generation;}
    synchronized void finish(Ticket ticket) {if(queued.get(ticket.key)==ticket)queued.remove(ticket.key);}
    synchronized void invalidate() {generation++;queued.clear();}
}

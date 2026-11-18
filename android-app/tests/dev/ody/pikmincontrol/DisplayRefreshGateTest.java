package dev.ody.pikmincontrol;

public final class DisplayRefreshGateTest {
    private static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args) {
        DisplayRefreshGate gate=new DisplayRefreshGate();
        DisplayRefreshGate.Ticket first=gate.begin(DisplayReadCoalescer.Key.RETURN,true);
        check(first!=null&&gate.current(first));
        for(int i=0;i<100;i++)check(gate.begin(DisplayReadCoalescer.Key.RETURN,true)==null);
        DisplayRefreshGate.Ticket other=gate.begin(DisplayReadCoalescer.Key.NECTAR,true);check(other!=null);
        gate.finish(first);check(gate.current(first)); // Its posted UI result is still eligible.
        DisplayRefreshGate.Ticket second=gate.begin(DisplayReadCoalescer.Key.RETURN,true);check(second!=null);
        gate.invalidate();check(!gate.current(first)&&!gate.current(second)&&!gate.current(other));
        DisplayRefreshGate.Ticket fresh=gate.begin(DisplayReadCoalescer.Key.RETURN,true);check(fresh!=null);
        gate.finish(second);check(gate.begin(DisplayReadCoalescer.Key.RETURN,true)==null); // Late old finally cannot clear new reservation.
        gate.finish(fresh);check(gate.begin(DisplayReadCoalescer.Key.RETURN,true)!=null);
        check(gate.begin(DisplayReadCoalescer.Key.RETURN,false)!=null&&gate.begin(DisplayReadCoalescer.Key.RETURN,false)!=null);
        System.out.println("PASS DisplayRefreshGate: 100 duplicate queued refreshes suppressed, keys independent, lifecycle rejection, late finish and bypass; simulated only");
    }
}

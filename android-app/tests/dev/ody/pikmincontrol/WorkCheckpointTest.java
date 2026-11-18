package dev.ody.pikmincontrol;
import java.util.*;
public final class WorkCheckpointTest {
    static final String ACCOUNT=String.join("",Collections.nCopies(64,"a"));
    static WorkCheckpoint farm(){return new WorkCheckpoint("12345678-1234-1234-1234-123456789abc","flower-farm",ACCOUNT,1000,2,5,Arrays.asList("25\t120","26\t121"));}
    public static void main(String[] args) {
        WorkCheckpoint c=farm();c.done.set(0);c.done.set(2);
        WorkCheckpoint r=WorkCheckpoint.restore(c.properties());
        if(r.remaining()!=2 || !r.done.get(2) || r.done.get(1) || !r.reason(ACCOUNT,2000).isEmpty())throw new AssertionError("round ledger");
        if(r.reason("",2000).isEmpty() || r.reason(ACCOUNT.replace('a','b'),2000).isEmpty() || r.reason(ACCOUNT,999).isEmpty()
                || r.reason(ACCOUNT,86_402_000).isEmpty())throw new AssertionError("identity/expiry");
        r.uncertain=true;if(WorkCheckpoint.restore(r.properties()).reason(ACCOUNT,2000).isEmpty())throw new AssertionError("pending replay");
        Properties p=c.properties();p.setProperty("done","0,1,2,3");
        try {WorkCheckpoint.restore(p);throw new AssertionError("corruption");}catch(IllegalArgumentException expected){}
        WorkCheckpoint b=new WorkCheckpoint(c.id,"batch",ACCOUNT,1000,1,5,Arrays.asList("task-a\tfruit\t0\t25\t120\t-1","task-b\tseed\t0\t25\t120\t-1"));
        b.finishTask("task-a");b.finishTask("task-a");if(b.remaining()!=1||!b.done.get(0))throw new AssertionError("task dedupe");
        String identity="v1\t1000\t123\t"+ACCOUNT+"\tready\tidle\tend";
        if(!SessionIdentity.parse(identity,"123","123",2000).equals(ACCOUNT)
                || !SessionIdentity.parse(identity,"123","124",2000).isEmpty()
                || !SessionIdentity.parse(identity,"123","123",14000).isEmpty()
                || !SessionIdentity.parse(identity.replace("ready","logged-out"),"123","123",2000).isEmpty())throw new AssertionError("identity protocol");
        if(!SessionIdentity.parse(identity.replace("idle","busy"),"123","123",2000,true).isEmpty()
                || !SessionIdentity.parse(identity.replace("idle","busy"),"123","123",2000).equals(ACCOUNT))throw new AssertionError("resume requires idle but active session remains identifiable");
        System.out.println("PASS WorkCheckpoint: round completion, task dedupe, checksum, unknown/account/expiry/pending gates");
    }
}

package dev.ody.pikmincontrol;
import java.io.IOException;
import java.util.*;

public final class CoreSuccessorFlowTest {
    static class Fake implements CoreSuccessorFlow.Port {
        String phase="staged";int old=83,next=84,step,fail=-1;boolean reboot=true,pending=true;
        List<String> calls=new ArrayList<>();
        public String phase(){return phase;}
        public int oldTarget(){return old;}
        public int newTarget(){return next;}
        public boolean rebooted(){return reboot;}
        void event(String name)throws Exception {
            if(!pending)throw new AssertionError("Lost protection");
            calls.add(name);if(step++==fail)throw new IOException(name);
        }
        public void verify()throws Exception{event("verify");}
        public void archive()throws Exception{event("archive");}
        public void prepare()throws Exception{event("prepare");phase="backed-up";old=next;}
        public void install()throws Exception{event("install");phase="staged";reboot=false;}
    }
    static void reject(Fake p)throws Exception {
        try{CoreSuccessorFlow.run(p);throw new AssertionError("Accepted invalid successor");}catch(IOException expected){}
        if(!p.pending)throw new AssertionError("Cleared pending");
    }
    public static void main(String[] args)throws Exception {
        Fake check=new Fake();CoreSuccessorFlow.check(check);
        if(!check.calls.equals(Arrays.asList("verify"))||!check.phase.equals("staged")||check.old!=83||!check.pending)
            throw new AssertionError("Check must not archive, prepare, install or accept");
        Fake ok=new Fake();CoreSuccessorFlow.run(ok);
        if(!ok.calls.equals(Arrays.asList("verify","archive","verify","prepare","install")))throw new AssertionError(ok.calls);
        reject(ok); // No replay before reboot, nor same-version successor afterwards.
        ok.reboot=true;reject(ok);
        for(String phase:Arrays.asList("","accepted","install-intent","rollback-staged","backed-up")) {
            Fake p=new Fake();p.phase=phase;reject(p);if(!p.calls.isEmpty())throw new AssertionError("Mutation before phase gate");
        }
        for(int code:new int[]{0,82,83}){Fake p=new Fake();p.next=code;reject(p);}
        Fake noBoot=new Fake();noBoot.reboot=false;reject(noBoot);
        for(int boundary=0;boundary<5;boundary++) {
            Fake p=new Fake();p.fail=boundary;reject(p);
            if(p.calls.size()!=boundary+1)throw new AssertionError("Continued after fault");
        }
        System.out.println("PASS CoreSuccessorFlow: ordered proof/archive/recheck/prepare/install, gates, each fault, no replay or acceptance");
    }
}

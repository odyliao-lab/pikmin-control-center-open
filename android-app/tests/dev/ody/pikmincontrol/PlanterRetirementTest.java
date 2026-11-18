package dev.ody.pikmincontrol;

public final class PlanterRetirementTest {
    static int checks;static final long NOW=2000000;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("retirement check "+checks);}
    static String[] rows(long time){return new String[]{"42","100","11111111-1111-1111-1111-111111111111",
        "v1\t"+time+"\t42\trestart-pending-review-required\tauto\t\n",
        "v1\t"+time+"\t42\t100\t300\nsteps\t7\nslot\t0\t0\t0\t\t0\t0\tempty\t\nslot\t1\t0\t0\tnew-seed\t1\t1000\tgrowing\tseed:1\nseed\tnew-seed\t1000\t1000\t0\t0\tseed:1\t1\nend\t1\n",
        "v1\t"+time+"\t42\trequest-1\tresult-unknown\n","1000\tplant\told-seed\t1\n",
        "v1\trequest-1\t42\t1999000\t1000\tplant\told-seed\t1\n",
        "v1\t"+time+"\t42\t"+repeat('a',64)+"\tready\tidle\tend\n","157.0","42"};}
    static String repeat(char c,int n){StringBuilder s=new StringBuilder();while(n-->0)s.append(c);return s.toString();}
    static String[] manualConflict(long time){
        String[] r=rows(time);
        r[4]=r[4].replace("end\t1", "seed\told-seed\t0\t1000\t0\t0\tseed:1\t1\nend\t2");
        return r;
    }
    interface Change{void run(String[] r);}
    static void reject(Change f)throws Exception{String[] r=rows(NOW);f.run(r);try{new PlanterRetirement.Proof(r,NOW);throw new AssertionError("unsafe proof accepted");}catch(IllegalStateException|NumberFormatException e){checks++;}}
    static final class Port implements PlanterRetirement.Port {
        final StringBuilder events=new StringBuilder();String fail="";
        void event(String n)throws Exception{events.append(n).append(',');if(n.equals(fail))throw new java.io.IOException(n);}
        public void guard()throws Exception{event("guard");}
        public void saveIntent(PlanterRetirement.Proof p)throws Exception{event("save");}
        public void retire(PlanterRetirement.Proof p)throws Exception{event("retire");}
        public void launch()throws Exception{event("launch");}
        public void complete()throws Exception{event("complete");}
    }
    public static void main(String[] args)throws Exception{
        PlanterRetirement.Proof p=new PlanterRetirement.Proof(rows(NOW),NOW);check(p.ledger.endsWith("\n"));check(p.sha.equals(PlanterRetirement.hash(p.ledger)));
        check(p.requestNonce.equals("request-1"));
        check(p.guard().contains("/proc/42/stat"));check(p.guard().contains(p.boot));
        check(p.inventorySha.equals(new PlanterRetirement.Proof(rows(NOW+5000),NOW+5000).inventorySha));
        // A confirmation dialog may outlive the old ACK/request. Fresh current data
        // can revalidate consent, but only a newly matched request may authorize retirement.
        String[] expired=rows(NOW);expired[5]=expired[5].replace("2000000","1900000");
        expired[7]=expired[7].replace("1999000","1900000");
        PlanterRetirement.Basis current=new PlanterRetirement.Basis(expired,NOW);
        check(p.same(current));check(current.same(p));
        try{new PlanterRetirement.Proof(expired,NOW);throw new AssertionError("expired ACK accepted");}catch(IllegalStateException e){checks++;}
        expired[5]=rows(NOW)[5]; // A rewritten old ACK timestamp still cannot renew the request.
        try{new PlanterRetirement.Proof(expired,NOW);throw new AssertionError("expired request accepted");}catch(IllegalStateException e){checks++;}
        expired[7]="v1\trequest-2\t42\t2000000\t1000\tplant\told-seed\t1\n";
        try{new PlanterRetirement.Proof(expired,NOW);throw new AssertionError("old nonce accepted");}catch(IllegalStateException e){checks++;}
        expired[5]=expired[5].replace("request-1","request-2");
        PlanterRetirement.Proof renewed=new PlanterRetirement.Proof(expired,NOW);
        check(renewed.requestNonce.equals("request-2"));check(p.same(renewed));
        String[] different=rows(NOW);different[6]=different[6].replace("old-seed","other-seed");
        check(!p.same(new PlanterRetirement.Basis(different,NOW)));
        different=rows(NOW);different[6]=different[6].replace("\tplant\t","  plant  ");
        check(!p.same(new PlanterRetirement.Basis(different,NOW))); // Original bytes, not just parsed fields.
        for(int identity:new int[]{1,2,8}){
            different=rows(NOW);
            different[identity]=identity==1?"101":identity==2?"22222222-2222-2222-2222-222222222222":different[8].replace(repeat('a',64),repeat('b',64));
            check(!p.same(new PlanterRetirement.Basis(different,NOW)));
        }
        String[] staleBasis=rows(NOW);staleBasis[4]=staleBasis[4].replace("2000000","1987999");
        try{new PlanterRetirement.Basis(staleBasis,NOW);throw new AssertionError("stale inventory basis accepted");}catch(IllegalStateException e){checks++;}
        staleBasis=rows(NOW);staleBasis[8]=staleBasis[8].replace("2000000","1987999");
        try{new PlanterRetirement.Basis(staleBasis,NOW);throw new AssertionError("stale account basis accepted");}catch(IllegalStateException e){checks++;}
        // Reproduce the report: old plant still unplanted, intended slot now holds another seed.
        PlanterRetirement.Proof conflict=new PlanterRetirement.Proof(manualConflict(NOW),NOW);
        check(conflict.baseline.equals("unplanted:new-seed"));
        String[] ready=manualConflict(NOW);ready[4]=ready[4].replace("1000\tgrowing", "1000\tready");
        check(new PlanterRetirement.Proof(ready,NOW).baseline.equals(conflict.baseline));
        for(String event:new String[]{"rpc-failed-review-required","server-rejected-review-required"}){
            String[] r=manualConflict(NOW);r[3]=r[3].replace("restart-pending-review-required",event);
            check(new PlanterRetirement.Proof(r,NOW).baseline.equals(conflict.baseline));
        }
        String[] planted=manualConflict(NOW);planted[4]=planted[4].replace("seed\told-seed\t0", "seed\told-seed\t123");
        try{new PlanterRetirement.Proof(planted,NOW);throw new AssertionError();}catch(IllegalStateException e){checks++;}
        String[] reference=manualConflict(NOW);reference[4]=reference[4].replace("0\t0\tnew-seed","0\t0\told-seed");
        try{new PlanterRetirement.Proof(reference,NOW);throw new AssertionError();}catch(IllegalStateException e){checks++;}
        check(p.same(conflict)); // Explicit consent remains bound to the same operation, not stale inventory.
        check(!p.sameDisposition(conflict));
        reject(r->{r[3]=r[3].replace("2000000","1987999");r[4]=r[4].replace("2000000","1987999");});
        reject(r->r[10]="43");reject(r->r[0]="42 43");reject(r->r[1]="bad");reject(r->r[2]=";bad");
        reject(r->r[3]=r[3].replace("restart-pending-review-required","waiting-rpc-no-retry"));
        reject(r->r[3]=r[3].replace("2000000","1000000"));reject(r->r[4]=r[4].replace("end\t1","end\t2"));
        reject(r->r[4]=r[4].replace("seed\tnew-seed","seed\told-seed"));
        reject(r->r[4]=r[4].replace("0\t0\tnew-seed","0\t0\told-seed"));
        reject(r->r[4]=r[4].replace("slot\t1\t0","slot\t1\t1"));
        reject(r->r[4]=r[4].replace("slot\t0\t0\t0\t\t0\t0\tempty\t\n",""));
        reject(r->r[4]=r[4].replace("end\t1","seed\tnew-seed\t0\t1000\t0\t0\tseed:1\t1\nend\t2"));
        reject(r->r[5]=r[5].replace("result-unknown","rpc-in-flight"));reject(r->r[5]=r[5].replace("result-unknown","checking-inventory"));
        reject(r->r[6]=r[6].replace("plant","pluck"));reject(r->r[6]="1999999\tplant\told-seed\t1\n");
        reject(r->r[7]=r[7].replace("request-1","request-2"));reject(r->r[7]=r[7].replace("old-seed","other"));
        reject(r->{r[5]=r[5].replace("request-1","bad'nonce");r[7]=r[7].replace("request-1","bad'nonce");});
        reject(r->{r[5]=r[5].replace("request-1",repeat('n',65));r[7]=r[7].replace("request-1",repeat('n',65));});
        reject(r->r[7]=r[7].replace("1999000","1900000"));reject(r->r[8]="");reject(r->r[9]="154.0");
        Port ok=new Port();PlanterRetirement.run(ok,p);check(ok.events.toString().equals("guard,save,guard,retire,launch,complete,"));
        Port manual=new Port();PlanterRetirement.run(manual,conflict);check(manual.events.toString().equals("guard,save,guard,retire,launch,complete,"));
        for(String fail:new String[]{"guard","save","retire","launch","complete"}){
            Port f=new Port();f.fail=fail;try{PlanterRetirement.run(f,p);throw new AssertionError("failure swallowed");}catch(java.io.IOException e){checks++;}
            if(!fail.equals("complete"))check(!f.events.toString().contains("complete,"));
            if(fail.equals("save"))check(!f.events.toString().contains("retire,"));
        }
        String ui=FarmResetSourceTest.source("MainActivity"),gateway=FarmResetSourceTest.source("PlanterRetirementGateway");
        check(!ui.contains("封存舊育苗紀錄並恢復？")&&!ui.contains("confirmPlanterRetirement"));
        check(!ui.contains("resumePlanterAfterReview"));
        check(gateway.contains("GpsWorkLock.acquire(c,OWNER)"));check(gateway.contains("AtomicFile"));
        check(gateway.contains("mv '")&&!gateway.contains("\"rm ")&&!gateway.contains(".delete("));
        check(gateway.contains("manual-retired-")&&gateway.contains("RETIRED_UNKNOWN"));
        check(!gateway.contains("Thread.sleep(11000)"));
        check(gateway.contains("GamePidRecoveryLaunch.command(GamePidRecoveryPolicy.Action.LAUNCH,component)"));
        check(gateway.contains("GamePidRecoveryLaunch.accepted(result,component)"));
        System.out.println("PASS "+checks+" manual planter retirement HOST checks; not Android restart/archive validation");
    }
}

package dev.ody.pikmincontrol;
public final class PlanterRecoveryTest {
    static int n;
    static void check(boolean b){if(!b)throw new AssertionError("case "+n);n++;}
    interface Attempt{void run();}
    static void denied(Attempt a){boolean b=false;try{a.run();}catch(IllegalArgumentException|IllegalStateException e){b=true;}check(b);}
    public static void main(String[] args){
        check(new PlanterRecovery("v1\t1000\t42\tnonce\tready","42",61000).supported);
        check(!new PlanterRecovery("v1\t1000\t42\tnonce\tready","42",61001).supported);
        check(!new PlanterRecovery("v1\t1000\t42\tnonce\tready","43",1001).supported);
        check(!new PlanterRecovery("v2\t1000\t42\tnonce\tready","42",1001).supported);
        check(!new PlanterRecovery("v1\t1000\t42\tnonce\tready","42",999).supported);
        check(!new PlanterRecovery("","42",1001).supported);
        check(PlanterRecovery.request("test-1","42",2000,"1000\tpluck\tseed-1\t0").equals("v1\ttest-1\t42\t2000\t1000\tpluck\tseed-1\t0\n"));
        check(PlanterRecovery.request("test-1","42",2000,"MISSING").endsWith("0\tnone\tnone\t-1\n"));
        denied(()->PlanterRecovery.request("bad'","42",2000,"MISSING"));
        denied(()->PlanterRecovery.request("ok","42;id",2000,"MISSING"));
        denied(()->PlanterRecovery.request("ok","42",2000,""));
        denied(()->PlanterRecovery.request("ok","42",2000,"1 pluck seed 8"));
        denied(()->PlanterRecovery.request("ok","42",2000,"1 drop seed 0"));
        denied(()->PlanterRecovery.request("ok","42",2000,"1 pluck seed;id 0"));
        denied(()->PlanterRecovery.request("ok","42",2000,"1 pluck seed 0 extra"));
        check(new PlanterRecovery("v1\t1000\t42\tnonce\tresult-unknown","42",1001).message().contains("保留暫停"));
        check(new PlanterRecovery("v1\t1000\t42\tnonce\trecovered-plant-state-confirmed","42",1001).message().contains("保留目前開關"));
        check(PlanterState.event("restart-pending-review-required").contains("自動觀察"));
        System.out.println("PASS PlanterRecovery: "+n+" checks");
    }
}

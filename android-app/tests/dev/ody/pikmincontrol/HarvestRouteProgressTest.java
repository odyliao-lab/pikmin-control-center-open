package dev.ody.pikmincontrol;
public final class HarvestRouteProgressTest {
    static int checks;
    static void check(boolean value) { checks++;if(!value)throw new AssertionError("check "+checks); }
    static HarvestRouteProgress path() { return new HarvestRouteProgress(HarvestRoute.coordinates("0,0\n0,0.0002\n0,0")); }
    public static void main(String[] args) {
        HarvestRouteProgress p=path();
        check(!p.observe(0,0,100)); check(p.reached()==1); check(!p.finished());
        check(!p.observe(0,0,100)); check(p.reached()==1);
        for(int i=1;i<=5;i++)p.observe(0,i*0.00004,100+i*1000);
        check(p.reached()==2); check(!p.finished());
        for(int i=4;i>=0;i--)p.observe(0,i*0.00004,6100+(4-i)*1000);
        check(p.finished()); check(p.fresh(11000)); check(!p.fresh(13000));
        p=path();check(!p.observe(0,0.0002,100));check(p.failure().equals("start-not-reached"));
        check(!p.observe(0,0,200)); // Failure is latched; no blind automatic reset.
        p=path();p.observe(0,0,100);check(!p.observe(0,0.0002,200));check(p.failure().equals("unexpected-jump"));
        p=path();p.observe(0,0,100);p.observe(0,0,4101);check(p.failure().equals("observation-gap"));
        p=path();p.observe(0,0,100);p.observe(0,0,2201);check(p.failure().isEmpty()); // 2.1 s interval tolerated (spec 4.1)
        p=path();p.observe(0,0,100);p.observe(0,0,99);check(p.failure().equals("clock-regression"));
        p=path();p.observe(0,0,100);p.observe(0,0.0001,100);check(p.failure().equals("conflicting-observation"));
        p=path();p.observe(Double.NaN,0,100);check(p.failure().equals("invalid-location"));
        p=new HarvestRouteProgress(HarvestRoute.coordinates("0,0\n0,0\n0,0.0002"));
        p.observe(0,0,100);check(p.reached()==2);check(!p.finished());
        p=new HarvestRouteProgress(HarvestRoute.coordinates("0,0\n0,0.00002"));
        check(!p.observe(0,0,100));check(p.reached()==1);check(p.observe(0,0.00002,1100));
        // Multiple STATUS polls between one-second fixes must not compress travel time.
        p=path();
        JoystickReadiness first=FarmLocationTest.provider(1000,0,0);
        p.observeProvider(first,1000);p.observeProvider(first,1500);p.observeProvider(first,1900);
        p.observeProvider(FarmLocationTest.provider(2000,0,0.0000475),2050);
        check(p.failure().isEmpty()); // 5.28m/1s, not 5.28m/150ms.
        check(p.reached()==1);
        p.observeProvider(FarmLocationTest.provider(3000,0,0.00015),3100);
        check(p.failure().equals("unexpected-jump")); // Real fast jump still blocked.
        p=path();p.observeProvider(first,3001);check(p.failure().equals("stale-provider"));
        p=path();p.observeProvider(first,999);check(p.failure().equals("stale-provider"));
        System.out.println("PASS HarvestRouteProgress: "+checks+" ordered/closed/repeated route and stale/jump checks; NOT game arrival (gap tolerance cases in HarvestRouteObservationTest)");
    }
}

package dev.ody.pikmincontrol;

public final class FarmLocationTest {
    static final long WALL=1788696777000L, BOOT=1095000;
    static int checks;
    static void check(boolean ok) { ++checks; if(!ok) throw new AssertionError("check "+checks); }
    static JoystickReadiness provider(long t, double lat, double lng) {
        return JoystickReadiness.parse("    gps provider [mock]:\n      allowed=true\n      identity=123/example\n"
                +"      last mock location=Location[gps "+lat+","+lng+" et=+"+t+"ms mock]\n    fused provider:\n", "123/example");
    }
    static String row(int tick, String state, long epoch, double lat, double lng) {
        return "v1\t"+(WALL+tick*1000)+"\t10976\t"+state+"\t"+epoch+"\t3\t37.7042274\t109.4021149\t"
                +lat+"\t"+lng+"\t"+(BOOT+tick*1000)+"\t300000";
    }
    static FarmLocation fix(int t, String state, long epoch, double lat, double lng) {
        return new FarmLocation(row(t,state,epoch,lat,lng),"10976","10976",WALL+t*1000,BOOT+t*1000,
                provider(BOOT+t*1000,37.7042274,109.4021149));
    }
    static boolean at(FarmLocation f,int t) { return f.atTarget(37.7042274,109.4021149,WALL+t*1000,BOOT+t*1000,WALL,BOOT); }
    public static void main(String[] args) {
        FarmLocation.Arrival gate=new FarmLocation.Arrival();
        double[][] hops={{26.3568325,94.166069},{29.1936827,97.9750805},{32.0305309,101.7840919},{34.8673792,105.5931034}};
        for(int i=1;i<=4;i++) {
            FarmLocation f=fix(i,"game-location-catching-up",i,hops[i-1][0],hops[i-1][1]);
            check(f.valid); check(!at(f,i)); check(!gate.observe(f,37.7042274,109.4021149,WALL+i*1000,BOOT+i*1000,WALL,BOOT));
        }
        FarmLocation settling=fix(5,"location-settling",4,37.7042274,109.4021149); check(!at(settling,5));
        FarmLocation a=fix(8,"ready",4,37.7042274,109.4021149), b=fix(9,"ready",4,37.7042274,109.4021149);
        check(at(a,8)); check(!gate.observe(a,a.lat,a.lng,WALL+8000,BOOT+8000,WALL,BOOT));
        check(!gate.observe(a,a.lat,a.lng,WALL+9000,BOOT+9000,WALL,BOOT)); // duplicate frame
        check(gate.observe(b,b.lat,b.lng,WALL+9000,BOOT+9000,WALL,BOOT));
        check(!at(a,11)); // heartbeat >2s old
        check(!a.atTarget(a.lat,a.lng,WALL+8000,BOOT+14000,WALL,BOOT)); // raw/provider >5s
        check(!a.atTarget(a.lat,a.lng,WALL+7999,BOOT+8000,WALL,BOOT)); // future wall
        check(!a.atTarget(a.lat,a.lng,WALL+8000,BOOT+7999,WALL,BOOT)); // future boot
        // During a root read GPS can advance past the earlier STATUS timestamp.
        // Using the pre-read clock spuriously rejects a valid, newer snapshot.
        FarmLocation moving=fix(9,"location-moving",4,37.7042274,109.4021149);
        check(moving.valid);
        check(!moving.fresh(WALL+9100,BOOT+8900));
        check(moving.fresh(WALL+9100,BOOT+9100));
        check(!moving.fresh(WALL+12000,BOOT+12000)); // stale still rejected
        check(!a.atTarget(a.lat,a.lng,WALL+8000,BOOT+8000,WALL+8000,BOOT+8000)); // pre-teleport cached ready
        check(!a.atTarget(35,139,WALL+8000,BOOT+8000,WALL,BOOT));
        check(!a.atTarget(Double.NaN,139,WALL+8000,BOOT+8000,WALL,BOOT));
        check(!at(fix(8,"ready",4,37.7043274,109.4021149),8)); // >5m
        String good=row(8,"ready",4,37.7042274,109.4021149);
        check(at(new FarmLocation(good.replace("v1","v2"),"10976","10976",WALL+8000,BOOT+8000,a.provider),8));
        for(String bad:new String[]{"", "24.0\t121.0", good.replace("v1","v3"), good.replace("300000","120000"),
                good.replace("\tready\t","\tunknown\t"),good.replace("\t4\t3\t","\t-1\t3\t"),
                good.replace("\t4\t3\t","\t4\t0\t"),good.replace("37.7042274","NaN"),good+"\textra"}) {
            FarmLocation f=new FarmLocation(bad,"10976","10976",WALL+8000,BOOT+8000,provider(BOOT+8000,a.lat,a.lng)); check(!at(f,8));
        }
        check(!new FarmLocation(good,"1","1",WALL+8000,BOOT+8000,a.provider).valid);
        check(!new FarmLocation(good,"10976","2",WALL+8000,BOOT+8000,a.provider).valid);
        check(!new FarmLocation(good,"10976 2","10976 2",WALL+8000,BOOT+8000,a.provider).valid);
        check(!at(new FarmLocation(good,"10976","10976",WALL+8000,BOOT+8000,provider(BOOT+8000,24,121)),8));
        check(!at(new FarmLocation(good,"10976","10976",WALL+8000,BOOT+8000,new JoystickReadiness(true,true,false,BOOT+8000)),8));
        FarmLocation changed=fix(10,"ready",5,a.lat,a.lng);
        check(!gate.observe(changed,a.lat,a.lng,WALL+10000,BOOT+10000,WALL,BOOT)); // new epoch needs another sample
        FarmLocation gap=fix(14,"ready",5,a.lat,a.lng);
        check(!gate.observe(gap,a.lat,a.lng,WALL+14000,BOOT+14000,WALL,BOOT));
        FarmLocation repeatedRaw=new FarmLocation(row(15,"ready",5,a.lat,a.lng).replace(Long.toString(BOOT+15000),Long.toString(BOOT+14000)),
                "10976","10976",WALL+15000,BOOT+15000,provider(BOOT+15000,a.lat,a.lng));
        check(!gate.observe(repeatedRaw,a.lat,a.lng,WALL+15000,BOOT+15000,WALL,BOOT));
        System.out.println("PASS FarmLocation: "+checks+" checks (real staged-jump shape, freshness, PID, provider, epoch, duplicate samples)");
    }
}

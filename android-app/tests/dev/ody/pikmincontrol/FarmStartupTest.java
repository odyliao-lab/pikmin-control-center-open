package dev.ody.pikmincontrol;

public final class FarmStartupTest {
    static int checks;
    static void check(boolean ok) { if (!ok) throw new AssertionError("check " + checks); ++checks; }
    public static void main(String[] args) {
        long wall=FarmLocationTest.WALL+8000, boot=FarmLocationTest.BOOT+8000;
        FarmStartup gate = new FarmStartup(boot);
        check(gate.waiting(boot,false));
        check(gate.waiting(boot+59999,false));
        check(!gate.waiting(boot+60000,false));
        check(!gate.waiting(boot,true));
        FarmLocation good=FarmLocationTest.fix(8,"ready",4,37.7042274,109.4021149);
        PlantingState off=new PlantingState(wall+"\toff\t0\t0\tidle\t0","10976","10976","10976",wall);
        check(off.valid);
        check(FarmStartup.reason(null,off,wall,boot).length()>0);
        check(FarmStartup.reason(good,null,wall,boot).length()>0);
        check(FarmStartup.reason(good,off,wall+2001,boot+2001).length()>0);
        check(FarmStartup.reason(good,off,wall,boot).isEmpty());
        check(!FarmStartup.plantingBusy(off));
        PlantingState busy=new PlantingState(wall+"\toff\t1\t0\tmanual\t0","10976","10976","10976",wall);
        check(FarmStartup.plantingBusy(busy));
        PlantingState changed=new PlantingState(wall+"\toff\t0\t0\tidle\t0","2","2","10976",wall);
        check(FarmStartup.reason(good,changed,wall,boot).length()>0);
        String raw=FarmLocationTest.row(8,"ready",4,37.7042274,109.4021149);
        FarmLocation missing=new FarmLocation(raw,"","",wall,boot,good.provider);
        check(!missing.valid && missing.readiness.contains("啟動"));
        FarmLocation stale=new FarmLocation(raw,"10976","10976",wall+2500,boot+2500,good.provider);
        check(!stale.valid && stale.readiness.contains("心跳"));
        FarmLocation old=new FarmLocation(raw.replace("300000","120000"),"10976","10976",wall,boot,good.provider);
        check(!old.valid && old.readiness.contains("1.4.22"));
        FarmLocation other=new FarmLocation(raw,"10976","10976",wall,boot,new JoystickReadiness(true,true,false,boot));
        check(!other.valid && other.readiness.contains("接管"));
        check(FarmStartup.reason(good,off,wall,boot).isEmpty()); // fresh recovery after rejected frames
        FarmStartup stable=new FarmStartup(boot);
        check(!stable.confirmed(good,off,wall,boot));
        check(!stable.confirmed(good,off,wall,boot)); // duplicate is not a second frame
        FarmLocation next=FarmLocationTest.fix(9,"ready",4,good.lat,good.lng);
        check(stable.confirmed(next,off,wall+1000,boot+1000));
        check(!stable.confirmed(null,off,wall+1000,boot+1000));
        check(!stable.confirmed(next,off,wall+1000,boot+1000)); // reset after failure
        FarmLocation multi=new FarmLocation(raw,"11 22","11 22",wall,boot,good.provider);
        check(!multi.valid&&multi.readiness.contains("不唯一"));
        FarmLocation foreign=new FarmLocation(raw,OwnerGameProcess.OTHER,OwnerGameProcess.OTHER,wall,boot,good.provider);
        check(!foreign.valid&&foreign.readiness.contains("其他使用者"));
        System.out.println("PASS FarmStartup: "+checks+" checks");
    }
}

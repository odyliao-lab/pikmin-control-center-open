package dev.ody.pikmincontrol;

public final class NearbyWorkStateTest {
    static final class Fake implements NearbyWorkState.Backend {
        String owner="", mode="armed", kinds="all";
        boolean armed, stop, held=true, failSave, failWrite, failClear, stopDuringWrite;
        int writes;
        public String owner(){return owner;} public boolean armed(){return armed;}
        public String readMode(){return mode;} public String readKinds(){return kinds;}
        public void save(String o,boolean a){if(failSave)throw new IllegalStateException(); owner=o;armed=a;}
        public void clear(){if(failClear)throw new IllegalStateException();owner="";}
        public boolean stopping(){return stop;} public boolean held(String o){return held;}
        public void write(boolean a){writes++; if(failWrite && a)throw new IllegalStateException();mode=a?"armed":"off";if(stopDuringWrite)stop=true;}
    }
    interface Action {void run() throws Exception;}
    static void check(boolean v){if(!v)throw new AssertionError();}
    static void fails(Action a) throws Exception {try{a.run();}catch(IllegalStateException expected){return;}throw new AssertionError("expected failure");}
    public static void main(String[] args) throws Exception {
        check(ControlSettings.normalizeFarmRounds(2)==2);
        for(int rounds:new int[]{-1,0,1,3,100})check(ControlSettings.normalizeFarmRounds(rounds)==1);
        for(String work:new String[]{"batch","flower-farm"})for(boolean original:new boolean[]{false,true}) {
            Fake f=new Fake();f.mode=original?"armed":"off";
            NearbyWorkState.capture(f,work);check(f.writes==0 && f.armed==original);
            f.mode="armed";f.kinds="farm"; // farm must not overwrite the original off snapshot
            NearbyWorkState.capture(f,work);check(f.armed==original);
            f.mode="off";f.kinds="all"; // successful service cleanup precedes restoration
            NearbyWorkState.restore(f,work);check(f.mode.equals(original?"armed":"off") && f.owner.isEmpty());
        }
        Fake f=new Fake();NearbyWorkState.capture(f,"batch");f.mode="off";f.stop=true;
        NearbyWorkState.restore(f,"batch");check(f.writes==0 && f.owner.isEmpty());
        Fake race=new Fake();NearbyWorkState.capture(race,"batch");race.stopDuringWrite=true;
        NearbyWorkState.restore(race,"batch");check(race.mode.equals("off") && race.writes==2);
        Fake lost=new Fake();NearbyWorkState.capture(lost,"batch");lost.held=false;
        fails(()->NearbyWorkState.restore(lost,"batch"));check(lost.writes==0 && !lost.owner.isEmpty());
        fails(()->NearbyWorkState.capture(lost,"flower-farm"));
        Fake failed=new Fake();NearbyWorkState.capture(failed,"batch");failed.failWrite=true;
        fails(()->NearbyWorkState.restore(failed,"batch"));check(failed.mode.equals("off") && !failed.owner.isEmpty());
        Fake storage=new Fake();NearbyWorkState.capture(storage,"batch");storage.failClear=true;
        fails(()->NearbyWorkState.restore(storage,"batch"));check(storage.mode.equals("off") && !storage.owner.isEmpty());
        Fake save=new Fake();save.failSave=true;fails(()->NearbyWorkState.capture(save,"batch"));check(save.writes==0);
        Fake stale=new Fake();stale.kinds="farm";fails(()->NearbyWorkState.capture(stale,"batch"));check(stale.owner.isEmpty());
        stale.kinds="all";stale.mode="probe";fails(()->NearbyWorkState.capture(stale,"batch"));
        Fake legacy=new Fake();legacy.kinds="farm";
        NearbyWorkState.capture(legacy,"flower-farm",true);check(!legacy.armed);
        System.out.println("PASS NearbyWorkState: original on/off, recovery, cancellation, ownership and failures");
    }
}

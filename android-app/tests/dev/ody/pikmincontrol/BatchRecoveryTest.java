package dev.ody.pikmincontrol;
import java.util.*;

public final class BatchRecoveryTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("batch check "+checks);}
    static final class Fake implements BatchRecovery.Backend {
        final List<String> events=new ArrayList<>();String fail="",state="error";boolean originalOn=true,armed=false;
        boolean desired;
        void step(String n)throws Exception{events.add(n);if(fail.equals(n))throw new Exception(n);}
        public void guard()throws Exception{step("guard");}
        public void begin()throws Exception{step("begin");state="stopping";}
        public void stop()throws Exception{step("stop");armed=false;}
        public void verify()throws Exception{step("verify");}
        public void requireResolved()throws Exception{step("resolved");}
        public void restoreNearby()throws Exception{step("restore");desired=originalOn;}
        public void release()throws Exception{step("release");}
        public void complete()throws Exception{step("complete");state="stopped";}
        public void failed(String detail)throws Exception{step("failed");state="stop-incomplete";}
    }
    public static void main(String[] args)throws Exception {
        for(boolean on:new boolean[]{false,true}) {
            Fake f=new Fake();f.originalOn=on;check(BatchRecovery.run(f).contains("重新掃描"));
            check(f.events.equals(Arrays.asList("guard","begin","stop","verify","resolved","restore","complete","release")));
            check(f.state.equals("stopped")&&f.desired==on&&!f.armed);
        }
        for(String fail:new String[]{"guard","begin","stop","verify","resolved","restore","complete","release"}) {
            Fake f=new Fake();f.fail=fail;boolean rejected=false;
            try{BatchRecovery.run(f);}catch(Exception expected){rejected=true;}
            check(rejected);
            if(fail.equals("guard")){check(f.events.size()==1);check(f.state.equals("error"));}
            else {check(f.state.equals("stop-incomplete"));check(f.events.contains("failed"));}
            if(Arrays.asList("begin","stop","verify","resolved").contains(fail))check(!f.events.contains("restore"));
        }
        for(String s:new String[]{"running","stopping","error","stop-incomplete"})check(BatchRecovery.needed(s,false));
        for(String s:new String[]{"stopped","completed",""}){check(!BatchRecovery.needed(s,false));check(BatchRecovery.needed(s,true));}
        String account=String.join("",Collections.nCopies(64,"a"));
        WorkCheckpoint cp=new WorkCheckpoint(UUID.randomUUID().toString(),"batch",account,100000,1,5,
            Arrays.asList("task\tfruit\t123456\t1.0\t2.0\t3.0"));cp.uncertain=true;
        String hash=cp.properties().getProperty("checksum");
        check(BatchRecovery.reviewed(cp,cp.id,hash,account));
        check(cp.uncertain&&cp.done.isEmpty()); // Manual archive consent never turns an unknown result into success.
        check(!cp.reason(account,100001).isEmpty()); // Old resume still forbidden.
        check(!BatchRecovery.reviewed(cp,"other",hash,account));
        check(!BatchRecovery.reviewed(cp,cp.id,"wrong",account));
        check(!BatchRecovery.reviewed(cp,cp.id,hash,""));
        check(!BatchRecovery.reviewed(cp,cp.id,hash,String.join("",Collections.nCopies(64,"b"))));
        cp.done.set(0);check(!BatchRecovery.reviewed(cp,cp.id,hash,account));
        String gateway=FarmResetSourceTest.source("BatchRecoveryGateway"),ui=FarmResetSourceTest.source("MainActivity"),store=FarmResetSourceTest.source("CheckpointStore");
        check(gateway.contains("acquireBatchRecovery(c)")&&gateway.contains("StopChecks.dispatch()"));
        check(gateway.contains("synchronized(AutomationPreferences.class)")&&gateway.contains("SessionIdentity.read()"));
        check(!gateway.contains("SessionIdentity.read(true)")); // Current native has no idle certificate.
        for(String bad:new String[]{"flyTo(","markArrivalReady(","beginBatch(","uncertain=false","CheckpointStore.save(",".delete("})check(!gateway.contains(bad));
        check(ui.contains("我已核對，封存並恢復")&&ui.contains("review.id,review.checksum"));
        check(store.contains("checkpoint-archives")&&store.contains("archive.openRead()")&&store.contains("saved.uncertain"));
        check(gateway.contains("guard();DispatchProbeGateway.stopBatch()"));
        check(!FarmResetSourceTest.source("GpsWorkLock").contains("static synchronized void acquireBatchRecovery"));
        check(FarmResetSourceTest.source("WorkStatus").contains("WorkProgress.active(state(c,GpsWorkLock.BATCH)) && !DispatchBatchService.isRunning()"));
        check(gateway.contains("NearbyWorkState.restoreDeferred(c,GpsWorkLock.BATCH)"));
        check(ui.contains("pending=cp.remaining()>0&&!cp.uncertain;"));
        for(boolean original:new boolean[]{true,false}) {
            NearbyWorkStateTest.Fake f=new NearbyWorkStateTest.Fake();f.armed=original;f.owner="batch";
            boolean[] chosen={false};NearbyWorkState.restoreDeferred(f,"batch",v -> chosen[0]=v);
            check(f.mode.equals("off")&&f.owner.isEmpty()&&chosen[0]==original);
        }
        NearbyWorkStateTest.Fake refused=new NearbyWorkStateTest.Fake();refused.owner="batch";
        boolean failed=false;try{NearbyWorkState.restoreDeferred(refused,"batch",v->{throw new IllegalStateException("save");});}catch(IllegalStateException expected){failed=true;}
        check(failed&&refused.mode.equals("off")&&refused.owner.equals("batch"));
        System.out.println("PASS BatchRecovery: "+checks+" ordered stop/readback/fault/consent/no-replay checks; NOT Android transaction acceptance");
    }
}

package dev.ody.pikmincontrol;
import java.util.*;

public final class BatchRecoveryRefreshTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("recovery refresh "+checks);}
    static final String ACCOUNT=String.join("",Collections.nCopies(64,"a"));
    static BatchRecoveryRefresh.Sample sample(long at,long now,String pid,String before,String after,String account,String status,String busy) {
        return new BatchRecoveryRefresh.Sample("v1\t"+at+"\t"+pid+"\t"+account+"\t"+status+"\t"+busy+"\tend",before,after,now);
    }
    static final class Fake implements BatchRecoveryRefresh.Backend {
        long elapsed;int opened,reads,waits,guards;boolean failOpen,failRead,failGuard;
        List<BatchRecoveryRefresh.Sample> samples=new ArrayList<>();
        Fake(BatchRecoveryRefresh.Sample... values){samples.addAll(Arrays.asList(values));}
        public BatchRecoveryRefresh.Sample read()throws Exception {if(failRead)throw new Exception("read");return samples.get(Math.min(reads++,samples.size()-1));}
        public long elapsed(){return elapsed;}
        public void openGame()throws Exception {opened++;if(failOpen)throw new Exception("open");}
        public void waitForUpdate(){waits++;elapsed+=500;}
        public void guard()throws Exception {guards++;if(failGuard)throw new Exception("guard");}
    }
    static void rejected(Fake f){boolean rejected=false;try{BatchRecoveryRefresh.require(f,ACCOUNT);}catch(Exception expected){rejected=true;}check(rejected);}
    public static void main(String[] args)throws Exception {
        BatchRecoveryRefresh.Sample fresh=sample(100000,100000,"22","22","22",ACCOUNT,"ready","busy");
        BatchRecoveryRefresh.Sample stale=sample(50000,100000,"22","22","22",ACCOUNT,"ready","busy");
        Fake direct=new Fake(fresh);check(BatchRecoveryRefresh.require(direct,ACCOUNT).equals(ACCOUNT));check(direct.opened==0&&direct.waits==0);
        Fake refresh=new Fake(stale,stale,fresh);check(BatchRecoveryRefresh.require(refresh,ACCOUNT).equals(ACCOUNT));check(refresh.opened==1&&refresh.waits==2&&refresh.guards==3);
        for(long age:new long[]{0,1,11999,12000}) {
            Fake f=new Fake(sample(100000-age,100000,"22","22","22",ACCOUNT,"ready","busy"));
            check(BatchRecoveryRefresh.require(f,ACCOUNT).equals(ACCOUNT));check(f.opened==0);
        }
        Fake timeout=new Fake(stale);rejected(timeout);check(timeout.opened==1&&timeout.elapsed==20000&&timeout.waits==40);
        for(String busy:new String[]{"idle","busy"})check(BatchRecoveryRefresh.require(new Fake(sample(100000,100001,"22","22","22",ACCOUNT,"ready",busy)),ACCOUNT).equals(ACCOUNT));
        Fake other=new Fake(sample(100000,100000,"22","22","22",String.join("",Collections.nCopies(64,"b")),"ready","busy"));rejected(other);check(other.opened==0);
        for(String[] p:new String[][]{{"",""},{"22 23","22 23"},{"22","23"},{"0","0"}}) {
            Fake f=new Fake(sample(100000,100000,"22",p[0],p[1],ACCOUNT,"ready","busy"));rejected(f);check(f.opened==0);
        }
        Fake changed=new Fake(stale,sample(100000,100001,"23","23","23",ACCOUNT,"ready","busy"));rejected(changed);check(changed.opened==1);
        Fake notReady=new Fake(sample(100000,100001,"22","22","22",ACCOUNT,"unknown","busy"),fresh);
        check(BatchRecoveryRefresh.require(notReady,ACCOUNT).equals(ACCOUNT));check(notReady.opened==1);
        Fake future=new Fake(sample(100001,100000,"22","22","22",ACCOUNT,"ready","busy"),fresh);check(BatchRecoveryRefresh.require(future,ACCOUNT).equals(ACCOUNT));check(future.opened==1);
        for(String fault:new String[]{"open","read","guard"}) {
            Fake f=new Fake(stale);f.failOpen=fault.equals("open");f.failRead=fault.equals("read");f.failGuard=fault.equals("guard");rejected(f);
        }
        for(String account:new String[]{"","bad",null}) {
            Fake f=new Fake(fresh);boolean rejected=false;try{BatchRecoveryRefresh.require(f,account);}catch(Exception expected){rejected=true;}check(rejected);check(f.opened==0&&f.reads==0);
        }
        String gateway=FarmResetSourceTest.source("BatchRecoveryGateway");
        check(gateway.contains("FLAG_ACTIVITY_REORDER_TO_FRONT")&&gateway.contains("EXTRA_OPEN_DISPATCH"));
        check(gateway.contains("GpsWorkLock.touch(c,GpsWorkLock.BATCH)")&&gateway.contains("Thread.sleep(500)"));
        for(String bad:new String[]{"force-stop","reboot","input tap","setTime(","dumpsys window"})check(!gateway.contains(bad));
        System.out.println("PASS BatchRecoveryRefresh: "+checks+" bounded fake-clock/source checks; NOT Android navigation acceptance");
    }
}

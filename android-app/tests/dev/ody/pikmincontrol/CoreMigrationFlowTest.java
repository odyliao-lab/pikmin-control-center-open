package dev.ody.pikmincontrol;

public final class CoreMigrationFlowTest {
    static int n;
    static void check(boolean v){if(!v)throw new AssertionError("flow "+n);n++;}
    static class Fake implements CoreMigrationFlow.Port {
        String state="backed-up",fail="";boolean reboot,disabled,staged,oldLoaded,newLoaded,restored;int installs,rollbacks;
        void hit(String at)throws Exception{if(at.equals(fail))throw new Exception("injected "+at);}
        public String phase(){return state;}
        public boolean rebooted(){return reboot;}
        public void persist(String s)throws Exception{hit("before-"+s);state=s;if(s.endsWith("-intent"))reboot=false;hit("after-"+s);}
        public void verifyBackup()throws Exception{hit("backup");}
        public void requireIdle()throws Exception{hit("idle");}
        public void verifyOriginal()throws Exception{hit("original");}
        public void disableGps()throws Exception{hit("disable");disabled=true;hit("disabled");}
        public void verifyGpsDisabled()throws Exception{hit("gps-proof");if(!disabled)throw new Exception("GPS enabled");}
        public void stage(boolean old)throws Exception{hit("stage");if(old)rollbacks++;else installs++;staged=true;hit("staged-effect");}
        public void verifyStaged(boolean old)throws Exception{hit("stage-proof");if(!staged)throw new Exception("no stage");}
        public boolean loaded(boolean old){return old?oldLoaded:newLoaded;}
        public void requireNoStaged()throws Exception{if(staged)throw new Exception("pending stage");}
        public boolean restoreGpsNeedsReboot(){return true;}
        public void restoreGps()throws Exception{hit("restore");disabled=false;restored=true;hit("restored-effect");}
        public void verifyRestoredGps()throws Exception{if(!restored||disabled)throw new Exception("not restored");}
        public void verifyOldFiles()throws Exception{if(!oldLoaded)throw new Exception("not old");}
    }
    interface Call{void run()throws Exception;}
    static void reject(Call c){boolean bad=false;try{c.run();}catch(Exception e){bad=true;}check(bad);}
    public static void main(String[] args)throws Exception {
        String first="11111111-1111-1111-1111-111111111111",second="22222222-2222-2222-2222-222222222222";
        check(!CoreMigrationFlow.bootChanged(first,first,first));
        check(CoreMigrationFlow.bootChanged(second,first,first));
        check(!CoreMigrationFlow.bootChanged(second,second,first)); // later rollback intent needs another reboot
        check(!CoreMigrationFlow.bootChanged(second,second,second)); // new transaction resets both epochs
        check(!CoreMigrationFlow.bootChanged(first,"",first));
        check(CoreMigrationFlow.bootChanged(second,null,first));
        for(String invalid:new String[]{null,"","bad",first+"\n",first+";id"}) {
            reject(()->CoreMigrationFlow.bootChanged(invalid,first,first));
            reject(()->CoreMigrationFlow.bootChanged(first,"",invalid));
        }
        reject(()->CoreMigrationFlow.bootChanged(first,"corrupt",first));
        Fake good=new Fake();CoreMigrationFlow.install(good);check(good.state.equals("staged")&&good.installs==1);
        reject(()->CoreMigrationFlow.install(good));reject(()->CoreMigrationFlow.accept(good));
        good.reboot=true;good.staged=false;reject(()->CoreMigrationFlow.accept(good));
        good.newLoaded=true;CoreMigrationFlow.accept(good);check(good.state.equals("accepted"));
        for(String fault:new String[]{"backup","idle","original","before-gps-disable-intent","after-gps-disable-intent","disable","disabled","gps-proof","before-install-intent","after-install-intent","stage","staged-effect","stage-proof","before-staged","after-staged"}) {
            Fake f=new Fake();f.fail=fault;reject(()->CoreMigrationFlow.install(f));
            check(f.installs<=1);
            if(!f.state.equals("backed-up")){f.fail="";int sent=f.installs;reject(()->CoreMigrationFlow.install(f));check(sent==f.installs);}
        }
        Fake r=new Fake();CoreMigrationFlow.install(r);r.reboot=true;r.staged=false;
        CoreMigrationFlow.rollback(r);check(r.rollbacks==1&&r.disabled);reject(()->CoreMigrationFlow.rollback(r));
        r.reboot=true;r.staged=false;reject(()->CoreMigrationFlow.reviewRollback(r));
        r.oldLoaded=true;CoreMigrationFlow.reviewRollback(r);check(r.state.equals("old-core-confirmed")&&r.disabled);
        CoreMigrationFlow.finishRollback(r);check(r.state.equals("gps-reboot")&&!r.disabled);
        reject(()->CoreMigrationFlow.reviewRollback(r));r.reboot=true;CoreMigrationFlow.reviewRollback(r);check(r.state.equals("rolled-back"));
        Fake interrupted=new Fake();CoreMigrationFlow.install(interrupted);interrupted.reboot=true;interrupted.staged=false;
        CoreMigrationFlow.rollback(interrupted);interrupted.reboot=true;interrupted.staged=false;interrupted.oldLoaded=true;
        CoreMigrationFlow.reviewRollback(interrupted);interrupted.fail="restore";
        reject(()->CoreMigrationFlow.finishRollback(interrupted));check(interrupted.state.equals("gps-restore-intent"));
        interrupted.fail="";interrupted.reboot=true;reject(()->CoreMigrationFlow.reviewRollback(interrupted));check(interrupted.disabled);
        CoreMigrationFlow.finishRollback(interrupted);check(interrupted.restored&&interrupted.state.equals("gps-reboot"));
        Fake retry=new Fake();CoreMigrationFlow.install(retry);retry.reboot=true;retry.staged=false;retry.fail="stage";
        reject(()->CoreMigrationFlow.rollback(retry));check(retry.state.equals("rollback-intent")&&retry.rollbacks==0);
        retry.fail="";reject(()->CoreMigrationFlow.rollback(retry));retry.reboot=true;
        CoreMigrationFlow.rollback(retry);check(retry.rollbacks==1);
        System.out.println("PASS CoreMigrationFlow: "+n+" durable transition/fault checks; NOT Magisk or reboot execution");
    }
}

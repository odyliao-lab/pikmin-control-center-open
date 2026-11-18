package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class InstallPreparationTest {
    static int checks;
    static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
    static final class Fake implements InstallPreparationPolicy.Backend {
        String calls="",failure="";boolean paused,ready;
        void at(String name)throws Exception{calls+=name+",";if(name.equals(failure))throw new Exception("fixture");}
        public void begin()throws Exception{at("begin");paused=true;}
        public void pauseSteps()throws Exception{at("steps");}
        public void stopWork()throws Exception{at("work");}
        public void pauseControls()throws Exception{at("controls");}
        public void verify()throws Exception{at("verify");}
        public void ready()throws Exception{at("ready");ready=true;}
        public void failed(String stage){calls+="failed,";}
    }
    static String source(String name)throws Exception{return new String(Files.readAllBytes(Paths.get("android-app/src/dev/ody/pikmincontrol/"+name+".java")),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        Fake good=new Fake();InstallPreparationPolicy.run(good);
        check(good.calls.equals("begin,steps,work,controls,verify,ready,"));check(good.ready);
        for(String fail:new String[]{"begin","steps","work","controls","verify","ready"}){
            Fake b=new Fake();b.failure=fail;boolean denied=false;
            try{InstallPreparationPolicy.run(b);}catch(Exception e){denied=true;}
            check(denied);check(!b.ready);check(b.calls.endsWith(fail.equals("begin")?"begin,":"failed,"));
            check(b.paused!=fail.equals("begin"));
        }
        InstallPreparationPolicy.WriteFence fence=new InstallPreparationPolicy.WriteFence();
        AtomicBoolean blocked=new AtomicBoolean();AtomicBoolean outside=new AtomicBoolean();
        CountDownLatch admitted=new CountDownLatch(1),release=new CountDownLatch(1);
        Thread write=new Thread(()->{check(fence.enter(blocked::get));admitted.countDown();
            try{release.await();}catch(Exception e){throw new AssertionError(e);}finally{fence.leave();}});
        write.start();check(admitted.await(2,TimeUnit.SECONDS));
        fence.persistPause(()->blocked.set(true));check(!fence.enter(blocked::get));check(!fence.drain(1));
        Thread reader=new Thread(()->{fence.persistPause(()->outside.set(true));});reader.start();reader.join(2000);
        check(outside.get());release.countDown();write.join(2000);check(!write.isAlive());check(fence.drain(10));
        blocked.set(false);check(fence.enter(blocked::get));fence.leave();check(fence.drain(1));
        String p=source("UpdatePreparation"),i=source("UpdateInstaller"),m=source("MainActivity");
        check(!p.contains("am force-stop")&&!p.contains("reboot")&&!p.contains("NearbyWorkState.discard"));
        check(!p.contains("WorkStatus.recovered")&&!p.contains("engine(c).stop()"));
        check(p.contains("getLong(\"target\"")&&p.contains("CoreUpdater.runtimePid(c,pair).isEmpty()"));
        check(p.contains("requireIdle(c);FeedingGateway.requirePausedForInstall();StopChecks.apk()"));
        check(!i.contains("requireGameIdle")&&!i.contains("FeedingGateway.load(c).enabled"));
        check(i.indexOf("UpdateController.verify(c,UpdateController.apk(c),info)")<i.indexOf("UpdatePreparation.prepare(c,info.code)"));
        check(i.indexOf("UpdatePreparation.requireReady(c)")<i.indexOf("installer.createSession(params)"));
        check(i.contains("USER_ACTION_NOT_REQUIRED")&&i.contains("abandonSession(id)"));
        check(!m.contains("工作中 · 結束後可安裝"));
        check(m.contains("if(afterStopped!=null){StepService.pauseForInstall(this);return;}"));
        for(String n:new String[]{"AutomationPreferences","CollectionMonitorService","FeedingGateway","FeedingPanel","GamePidRecoveryService","PlanterSettings","ReadinessGateway","SettingsProfiles"})
            check(source(n).contains("UpdateInstaller.blocking("));
        for(String n:new String[]{"StepService","StepSyncJob"})check(source(n).contains("UpdateInstaller.blocking("));
        check(source("StepHealthGateway").contains("UpdatePreparation.STEPS.enter("));
        check(source("StepHealthGateway").contains("finally{UpdatePreparation.STEPS.leave();}"));
        check(source("GpsWorkLock").contains("if(!recovery && UpdatePreparation.blocking(context))"));
        check(source("SettingsRestoreJob").contains("if(!UpdatePreparation.restore(c))return"));
        check(source("SettingsRestoreJob").contains("setMinimumLatency(15_000)"));
        check(source("SettingsRestoreJob").contains("UpdatePreparation.needsRestore(this)"));
        check(source("CollectionMonitorService").contains("UpdatePreparation.blocking(this)&&!UpdatePreparation.restore(this)"));
        System.out.println("PASS InstallPreparation: "+checks+" ordered failure/fence/source checks; NOT Android installer or live-work acceptance");
    }
}

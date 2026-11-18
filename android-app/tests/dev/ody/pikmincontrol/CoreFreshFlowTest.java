package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.*;

/** Executes the production orchestration; root/Magisk and durable storage are a fault model. */
public final class CoreFreshFlowTest {
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("fresh transaction "+n);n++;}
    interface Action{void run()throws Exception;}
    static void rejects(Action a)throws Exception{try{a.run();}catch(IOException expected){n++;return;}throw new AssertionError("expected failure");}
    static final class Disk {
        String phase="prepared";boolean rebooted,gps,originalGps,copy,staged,module,quarantine,removedFlag;
        int installs;boolean backup=true,idle=true,override;
        Disk(boolean gps){this.gps=this.originalGps=gps;}
        void reboot(){module=staged||module;staged=false;rebooted=true;}
    }
    static final class P implements CoreFreshFlow.Port {
        final Disk d;final List<String> calls=new ArrayList<>();int index,fail=-1;boolean after;
        P(Disk d){this.d=d;}
        void operation(String name,Action body)throws Exception {
            calls.add(name);int i=index++;
            if(i==fail&&!after)throw new IOException("before "+name);
            body.run();
            if(i==fail&&after)throw new IOException("after "+name);
        }
        void require(boolean v)throws IOException{if(!v)throw new IOException("model guard");}
        public String phase(){return d.phase;}
        public boolean rebooted(){return d.rebooted;}
        public void verifyBackup()throws Exception{operation("backup",()->require(d.backup));}
        public void requireIdle()throws Exception{operation("idle",()->require(d.idle));}
        public void requireOriginal()throws Exception{operation("original",()->require(!d.module&&!d.staged&&d.gps==d.originalGps&&!d.copy));}
        public void persist(String s)throws Exception{operation("persist:"+s,()->d.phase=s);}
        public void disableGps()throws Exception{operation("disable",()->{require(d.phase.equals("gps-intent"));d.gps=false;});}
        public void requireDisabled()throws Exception{operation("disabled",()->require(!d.gps&&!d.module&&!d.staged));}
        public void applyCopy()throws Exception{operation("copy",()->{require(d.phase.equals("copy-intent"));d.copy=true;});}
        public void verifyCopy()throws Exception{operation("copy-proof",()->require(d.copy));}
        public void stage()throws Exception{operation("stage",()->{require(d.phase.equals("install-intent")&&!d.gps&&!d.staged&&!d.module);d.installs++;d.staged=true;});}
        public void verifyStaged()throws Exception{operation("staged-proof",()->require(d.staged));}
        public void prepareRemoval()throws Exception{operation("prepare-removal",()->{
            require(!d.staged&&(!d.module||!d.gps));
            if(d.quarantine)require(d.phase.equals("remove-intent")&&d.removedFlag&&!d.module);
            d.removedFlag=d.module||d.quarantine;d.rebooted=false;
        });}
        public void restoreCopy()throws Exception{operation("restore-copy",()->{require(d.phase.equals("remove-intent"));if(!d.override)d.copy=false;});}
        public void quarantine()throws Exception{operation("quarantine",()->{require(d.phase.equals("remove-intent")&&!d.staged);if(d.module){d.module=false;d.quarantine=true;}require(!d.module);});}
        public void restoreGps()throws Exception{operation("restore-gps",()->{require(!d.module&&!d.staged);d.gps=d.originalGps;});}
        public void verifyBaseline()throws Exception{operation("baseline",()->require(!d.module&&!d.staged&&d.gps==d.originalGps&&(d.override||!d.copy)));}
    }
    static void baseline(Disk d){check(!d.module&&!d.staged&&d.gps==d.originalGps&&(d.override||!d.copy));}
    public static void main(String[] args)throws Exception {
        P normal=new P(new Disk(true));CoreFreshFlow.install(normal);int forward=normal.calls.size();
        check(normal.d.installs==1&&normal.d.phase.equals("staged")&&!normal.d.gps);
        normal.d.reboot();P back=new P(normal.d);CoreFreshFlow.rollback(back);int reverse=back.calls.size();baseline(normal.d);
        // New port objects emulate process restart; only Disk state survives.
        for(boolean gps:new boolean[]{false,true})for(boolean after:new boolean[]{false,true}) {
            for(int i=0;i<forward;i++) {
                Disk d=new Disk(gps);P p=new P(d);p.fail=i;p.after=after;
                rejects(()->CoreFreshFlow.install(p));
                if(!d.phase.equals("prepared")){int sent=d.installs;rejects(()->CoreFreshFlow.install(new P(d)));check(d.installs==sent);}
                d.reboot();CoreFreshFlow.rollback(new P(d));baseline(d);check(d.installs<=1);
            }
            for(int i=0;i<reverse;i++) {
                Disk d=new Disk(gps);CoreFreshFlow.install(new P(d));d.reboot();P p=new P(d);p.fail=i;p.after=after;
                rejects(()->CoreFreshFlow.rollback(p));d.reboot();
                if(d.phase.equals("removed"))baseline(d);else {CoreFreshFlow.rollback(new P(d));baseline(d);}
                check(d.installs==1);
            }
        }
        for(String phase:new String[]{"prepare-intent","restored","removed","unknown"}) {
            Disk d=new Disk(true);d.phase=phase;d.rebooted=true;P p=new P(d);rejects(()->CoreFreshFlow.rollback(p));check(p.calls.isEmpty());
        }
        Disk bad=new Disk(true);bad.backup=false;P p=new P(bad);rejects(()->CoreFreshFlow.install(p));check(bad.gps&&!bad.copy&&bad.installs==0);
        Disk busy=new Disk(true);busy.idle=false;rejects(()->CoreFreshFlow.install(new P(busy)));check(busy.gps&&!busy.copy);
        Disk reboot=new Disk(true);reboot.rebooted=true;P r=new P(reboot);rejects(()->CoreFreshFlow.install(r));check(r.calls.isEmpty());
        Disk override=new Disk(true);CoreFreshFlow.install(new P(override));override.override=true;override.reboot();CoreFreshFlow.rollback(new P(override));check(override.copy);baseline(override);
        System.out.println("PASS CoreFreshFlow: "+n+" orchestration/interruption checks ("+forward+" forward, "+reverse+" rollback boundaries); root/Magisk/storage are models, NOT device transaction acceptance");
    }
}

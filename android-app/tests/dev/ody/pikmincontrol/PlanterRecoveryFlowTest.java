package dev.ody.pikmincontrol;

/** Virtual-clock HOST regression: UI waiting is not native retirement authority. */
public final class PlanterRecoveryFlowTest {
    static int checks;
    static final long START=2000000;
    static final String SESSION="42:boot:100:epoch-1:login-1";
    static void check(boolean condition){checks++;if(!condition)throw new AssertionError("recovery flow check "+checks);}
    interface Attempt {void run()throws Exception;}
    static void reject(Attempt attempt)throws Exception{
        try{attempt.run();throw new AssertionError("unsafe recovery flow accepted");}
        catch(IllegalStateException expected){checks++;}
    }
    static String account(char c){StringBuilder out=new StringBuilder();for(int i=0;i<64;i++)out.append(c);return out.toString();}
    static String[] rows(long time,String nonce){return new String[]{"42","100","11111111-1111-1111-1111-111111111111",
        "v1\t"+time+"\t42\trestart-pending-review-required\tauto\t\n",
        "v1\t"+time+"\t42\t100\t300\nsteps\t7\nslot\t0\t0\t0\t\t0\t0\tempty\t\nslot\t1\t0\t0\tnew-seed\t1\t1000\tgrowing\tseed:1\nseed\tnew-seed\t1000\t1000\t0\t0\tseed:1\t1\nend\t1\n",
        "v1\t"+time+"\t42\t"+nonce+"\tresult-unknown\n","1000\tplant\told-seed\t1\n",
        "v1\t"+nonce+"\t42\t"+time+"\t1000\tplant\told-seed\t1\n",
        "v1\t"+time+"\t42\t"+account('a')+"\tready\tidle\tend\n","157.0","42"};}
    static PlanterRecovery ack(long time,String nonce,String result){return new PlanterRecovery("v1\t"+time+"\t42\t"+nonce+"\t"+result,"42",time);}
    static PlanterRecoveryFlow.Prepared prepared(long time,String nonce)throws Exception{
        return new PlanterRecoveryFlow.Prepared(ack(time,nonce,"result-unknown"),new PlanterRetirement.Proof(rows(time,nonce),time),SESSION);
    }
    interface Change {void apply(String[] value);}
    static final class Clock {long now=START;void advance(long millis){now+=millis;}}
    static final class Port implements PlanterRecoveryFlow.Port {
        final Clock clock;
        final StringBuilder events=new StringBuilder();
        int guards,prepares,retires,requests;
        int failGuard;
        boolean failPrepare,nullPrepared,oldNonce;
        String result="result-unknown",session=SESSION;
        Change change;
        PlanterRecoveryFlow.Prepared original,renewed;
        PlanterRetirement.Proof retired;
        Port(Clock clock){this.clock=clock;}
        public void guard(){events.append("guard,");guards++;if(guards==failGuard)throw new IllegalStateException("stop-or-busy");}
        public PlanterRecoveryFlow.Prepared prepare(PlanterRecoveryFlow.Prepared consent)throws Exception{
            events.append("prepare,");prepares++;original=consent;
            if(failPrepare)throw new IllegalStateException("refresh-failed");
            if(nullPrepared)return null;
            clock.advance(8000); // Fresh foreground reports, not an extension of an old report's TTL.
            String nonce=oldNonce?consent.ack.nonce:"renewed-request";
            String[] sample=rows(clock.now,nonce);if(change!=null)change.apply(sample);
            PlanterRecoveryFlow.requireSame(consent,new PlanterRetirement.Basis(sample,clock.now),session);
            requests++; // Represents a scoped read-only check; never a plant/pluck request.
            renewed="result-unknown".equals(result)
                ?new PlanterRecoveryFlow.Prepared(ack(clock.now,nonce,result),new PlanterRetirement.Proof(sample,clock.now),session)
                :new PlanterRecoveryFlow.Prepared(ack(clock.now,nonce,result),null,session);
            return renewed;
        }
        public void retire(PlanterRetirement.Proof proof,String currentSession){
            events.append("retire,");retires++;retired=proof;
            check(proof==renewed.proof&&currentSession.equals(renewed.session));
            check(clock.now-proof.sampled<=12000&&!proof.requestNonce.equals(original.ack.nonce));
        }
    }
    static void identityChange(Change change,String session)throws Exception{
        Clock clock=new Clock();PlanterRecoveryFlow.Prepared consent=prepared(clock.now,"original-request");
        clock.advance(100000);Port changed=new Port(clock);changed.change=change;changed.session=session;
        reject(()->PlanterRecoveryFlow.confirm(changed,consent));
        check(changed.prepares==1&&changed.requests==0&&changed.retires==0);
    }
    public static void main(String[] args)throws Exception {
        Clock clock=new Clock();
        PlanterRecoveryFlow.Prepared consent=prepared(clock.now,"original-request");
        check(consent.proof.requestNonce.equals(consent.ack.nonce));
        // Capture while game reports are fresh, then display the already captured consent.
        clock.advance(12001); // Worker queue / return-to-App delay used to expire the preview.
        check(consent.proof.sampled==START);
        reject(()->new PlanterRetirement.Proof(rows(START,"original-request"),clock.now));
        clock.advance(90001); // The second confirmation can remain open beyond request TTL.
        String[] expired=rows(clock.now,"original-request");
        expired[7]=rows(START,"original-request")[7];
        // Even with a rewritten ACK heartbeat, the old request stays expired.
        reject(()->new PlanterRetirement.Proof(expired,clock.now));
        PlanterRecoveryFlow.requireSame(consent,new PlanterRetirement.Basis(expired,clock.now),SESSION);checks++;
        Port ok=new Port(clock);check(PlanterRecoveryFlow.confirm(ok,consent));
        check(ok.events.toString().equals("guard,prepare,guard,retire,"));
        check(ok.prepares==1&&ok.requests==1&&ok.retires==1);
        check(ok.retired!=consent.proof&&ok.retired.sampled>consent.proof.sampled);

        // The explicit choice remains bound to the same pending operation and owner;
        // the freshly read inventory is authoritative after unrelated manual changes.
        Port updatedInventory=new Port(new Clock());
        updatedInventory.change=r->{r[4]=r[4].replace("new-seed","replacement-seed");};
        check(PlanterRecoveryFlow.confirm(updatedInventory,consent));
        check(updatedInventory.retired.baseline.equals("absent:replacement-seed"));
        Port oldSeedReturned=new Port(new Clock());
        oldSeedReturned.change=r->{r[4]=r[4].replace("end\t1","seed\told-seed\t0\t1000\t0\t0\tseed:1\t1\nend\t2");};
        check(PlanterRecoveryFlow.confirm(oldSeedReturned,consent));
        check(oldSeedReturned.retired.baseline.equals("unplanted:new-seed"));

        // Reconfirmation cannot retarget another process incarnation, account, or original ledger.
        identityChange(r->{r[1]="101";},SESSION);
        identityChange(r->{r[2]="22222222-2222-2222-2222-222222222222";},SESSION);
        identityChange(r->{r[8]=r[8].replace(account('a'),account('b'));},SESSION);
        identityChange(r->{r[6]=r[6].replace("old-seed","another-seed");r[7]=r[7].replace("old-seed","another-seed");},SESSION);
        identityChange(r->{r[6]=r[6].replace("\tplant\t","  plant  ");},SESSION);
        identityChange(r->{r[0]="43";r[10]="43";for(int i:new int[]{3,4,5,7,8})r[i]=r[i].replace("\t42\t","\t43\t");},SESSION);
        identityChange(r->{},SESSION.replace("epoch-1","epoch-2"));
        identityChange(r->{},SESSION.replace("login-1","login-2"));

        Port targetOccupied=new Port(new Clock());
        targetOccupied.change=r->{r[4]=r[4].replace("slot\t0\t0\t0\t\t", "slot\t0\t0\t0\told-seed\t")
            .replace("end\t1", "seed\told-seed\t0\t1000\t0\t0\tseed:1\t1\nend\t2");};
        reject(()->PlanterRecoveryFlow.confirm(targetOccupied,consent));
        check(targetOccupied.retires==0);

        // Prepared binds the ACK actually awaited, not merely a self-consistent unrelated proof.
        PlanterRetirement.Proof proof=new PlanterRetirement.Proof(rows(START,"original-request"),START);
        reject(()->new PlanterRecoveryFlow.Prepared(ack(START,"other-request","result-unknown"),proof,SESSION));
        reject(()->new PlanterRecoveryFlow.Prepared(new PlanterRecovery("v1\t"+START+"\t43\toriginal-request\tresult-unknown","43",START),proof,SESSION));
        reject(()->new PlanterRecoveryFlow.Prepared(ack(START,"original-request","result-unknown"),null,SESSION));
        reject(()->new PlanterRecoveryFlow.Prepared(ack(START,"original-request","result-unknown"),proof," "));
        reject(()->new PlanterRecoveryFlow.Prepared(ack(START,"original-request","result-unknown"),proof,null));
        reject(()->new PlanterRecoveryFlow.Prepared(ack(START,"original-request","not-paused"),proof,SESSION));
        reject(()->new PlanterRecoveryFlow.Prepared(null,proof,SESSION));
        reject(()->new PlanterRecoveryFlow.Prepared(new PlanterRecovery("","42",START),null,SESSION));
        Port old=new Port(new Clock());old.oldNonce=true;
        reject(()->PlanterRecoveryFlow.confirm(old,consent));check(old.requests==1&&old.retires==0);

        for(String terminal:new String[]{"recovered-plant-state-confirmed","recovered-pluck-target-retired","recovered-no-request-created","not-paused","rpc-in-flight","ledger-changed","expired-or-game-restarted"}){
            Port resolved=new Port(new Clock());resolved.result=terminal;
            check(!PlanterRecoveryFlow.confirm(resolved,consent));
            check(resolved.prepares==1&&resolved.retires==0&&resolved.guards==1);
        }
        for(int fail:new int[]{1,2}){
            Port stopped=new Port(new Clock());stopped.failGuard=fail;
            reject(()->PlanterRecoveryFlow.confirm(stopped,consent));
            check(stopped.retires==0&&stopped.prepares==(fail==1?0:1));
        }
        Port failed=new Port(new Clock());failed.failPrepare=true;
        reject(()->PlanterRecoveryFlow.confirm(failed,consent));check(failed.retires==0&&failed.requests==0);
        Port missing=new Port(new Clock());missing.nullPrepared=true;
        reject(()->PlanterRecoveryFlow.confirm(missing,consent));check(missing.retires==0);
        Port cancelled=new Port(new Clock()); // User cancels: no confirm invocation, no refresh or side effects.
        check(cancelled.events.length()==0&&cancelled.requests==0&&cancelled.retires==0);
        reject(()->PlanterRecoveryFlow.requireSame(null,proof,SESSION));
        reject(()->PlanterRecoveryFlow.requireSame(consent,null,SESSION));
        reject(()->PlanterRecoveryFlow.requireSame(consent,proof,null));
        Port invalid=new Port(new Clock());reject(()->PlanterRecoveryFlow.confirm(invalid,null));check(invalid.prepares==0&&invalid.retires==0);
        PlanterRecoveryFlow.Prepared notUnknown=new PlanterRecoveryFlow.Prepared(ack(START,"other","not-paused"),null,SESSION);
        Port noConsent=new Port(new Clock());reject(()->PlanterRecoveryFlow.confirm(noConsent,notUnknown));check(noConsent.prepares==0&&noConsent.retires==0);
        System.out.println("PASS "+checks+" recovery timing/consent HOST checks; NOT Android recovery acceptance");
    }
}

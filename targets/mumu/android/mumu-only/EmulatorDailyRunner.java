package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

/** Persist-before-effect daily coordinator. No Android/shell/game adapter or scheduler is enabled. */
final class EmulatorDailyRunner {
    interface Store { State read() throws Exception; void save(State state) throws Exception; }
    interface Port {
        void startRoute(Binding binding,String key,long locationEpoch) throws Exception;
        // Every per-task admission, including rescan, must enforce this absolute
        // deadline even if the scheduler dies. Closing admission is NOT aborting
        // an accepted task or declaring its claims complete.
        void finalBatch(Binding binding,String key,long locationEpoch,long admissionDeadline) throws Exception;
        void closeBatchAdmissions(Binding binding,String key) throws Exception;
        void stopOwnedRoute(Binding binding,String key) throws Exception;
        Observation observe(Binding binding,String key) throws Exception;
    }
    enum Evidence { UNKNOWN, ADMITTED, COMPLETE, COMPLETE_ALL_CLAIMS, CLOSED_SKIPPED }
    static final class Binding {
        final String device,boot,account,coreSha,apkSha,routeSha;
        final int pid;
        final long sessionEpoch;
        final double kmh;
        Binding(String device,String boot,int pid,String account,long sessionEpoch,String coreSha,String apkSha,String routeSha,double kmh) {
            if(!"127.0.0.1:16448".equals(device) || boot==null || !boot.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}") ||
                    pid<=0 || sessionEpoch<=0 || !sha(account) || !sha(coreSha) || !sha(apkSha) || !sha(routeSha))throw new IllegalArgumentException("Independent MuMu identity required");
            this.device=device;this.boot=boot;this.pid=pid;this.account=account;this.sessionEpoch=sessionEpoch;
            this.coreSha=coreSha;this.apkSha=apkSha;this.routeSha=routeSha;this.kmh=EmulatorDailyPlan.plantingSpeed(kmh);
        }
        String encode(){return device+"\t"+boot+"\t"+pid+"\t"+account+"\t"+sessionEpoch+"\t"+coreSha+"\t"+apkSha+"\t"+routeSha+"\t"+kmh;}
        static Binding decode(String wire) {
            String[] f=wire.split("\t",-1);if(f.length!=9)throw new IllegalArgumentException("Binding schema");
            Binding b=new Binding(f[0],f[1],Integer.parseInt(f[2]),f[3],Long.parseLong(f[4]),f[5],f[6],f[7],Double.parseDouble(f[8]));
            if(!wire.equals(b.encode()))throw new IllegalArgumentException("Noncanonical binding");return b;
        }
        boolean same(Binding other){return other!=null && encode().equals(other.encode());}
    }
    static final class State {
        final Binding binding;
        final EmulatorDailyPlan.Journal journal;
        boolean stopAttempted;
        boolean batchCloseAttempted;
        long pendingLocationEpoch,lastLocationEpoch;
        EmulatorNurseryBudget.Ledger nursery;
        State(Binding b,EmulatorDailyPlan.Journal j){if(b==null||j==null)throw new IllegalArgumentException("State required");binding=b;journal=j;}
        String encode() {
            EmulatorDailyPlan.Journal.decode(journal.encode());
            if(stopAttempted && journal.pending!=EmulatorDailyPlan.Pending.ROUTE)throw new IllegalArgumentException("Stop without owned route intent");
            if(batchCloseAttempted && journal.pending!=EmulatorDailyPlan.Pending.BATCH)throw new IllegalArgumentException("Close without owned batch intent");
            if(lastLocationEpoch<0 || pendingLocationEpoch<0 || pendingLocationEpoch>lastLocationEpoch ||
                    (journal.pending==EmulatorDailyPlan.Pending.NONE ? pendingLocationEpoch!=0 : pendingLocationEpoch==0))throw new IllegalArgumentException("Location intent required");
            if(nursery!=null && nursery.day.isAfter(journal.overnight?journal.day.plusDays(1):journal.day))throw new IllegalArgumentException("Future nursery day");
            String body=(batchCloseAttempted?"v3":nursery==null?"v1":"v2")+"\t"+base64(binding.encode())+"\t"+base64(journal.encode())+"\t"+stopAttempted+"\t"+pendingLocationEpoch+"\t"+lastLocationEpoch+
                    (batchCloseAttempted?"\t"+(nursery==null?"-":base64(nursery.encode()))+"\ttrue":nursery==null?"":"\t"+base64(nursery.encode()))+"\tend\n";
            return body+"sha256\t"+digest(body)+"\n";
        }
        static State decode(String wire) {
            if(wire==null || wire.length()>4096 || wire.indexOf('\r')>=0)throw new IllegalArgumentException("State framing");
            String[] rows=wire.split("\n",-1);
            if(rows.length!=3 || !rows[2].isEmpty() || !rows[1].equals("sha256\t"+digest(rows[0]+"\n")))throw new IllegalArgumentException("State checksum");
            String[] f=rows[0].split("\t",-1);
            boolean v2=f.length==8 && f[0].equals("v2");
            boolean v3=f.length==9 && f[0].equals("v3") && f[7].equals("true");
            if(!(v3 || v2 || (f.length==7 && f[0].equals("v1"))) || !f[f.length-1].equals("end") || !(f[3].equals("true")||f[3].equals("false")))throw new IllegalArgumentException("State schema");
            State s=new State(Binding.decode(unbase64(f[1])),EmulatorDailyPlan.Journal.decode(unbase64(f[2])));s.stopAttempted=Boolean.parseBoolean(f[3]);
            s.pendingLocationEpoch=Long.parseLong(f[4]);s.lastLocationEpoch=Long.parseLong(f[5]);
            if(v2 || (v3 && !f[6].equals("-")))s.nursery=EmulatorNurseryBudget.Ledger.decode(unbase64(f[6]));
            s.batchCloseAttempted=v3;
            if(!wire.equals(s.encode()))throw new IllegalArgumentException("Noncanonical state");return s;
        }
    }
    static final class Facts {
        final Binding binding;
        // ready is supplied by a future trusted fresh manual/core/account/location/resource
        // adapter AND controlled-round/HC acceptance. It is NOT a preference or authorization API.
        final boolean ready,ownedRouteBusy,ownedBatchBusy,externalBusy,routeConfigured;
        final long locationEpoch;
        final EmulatorNurseryBudget.Snapshot nurserySnapshot;
        Facts(Binding b,long locationEpoch,boolean ready,boolean route,boolean batch,boolean external,boolean configured){
            this(b,locationEpoch,ready,route,batch,external,configured,null);
        }
        Facts(Binding b,long locationEpoch,boolean ready,boolean route,boolean batch,boolean external,boolean configured,EmulatorNurseryBudget.Snapshot nurserySnapshot){
            if(b==null || locationEpoch<0 || (ready && locationEpoch==0))throw new IllegalArgumentException("Fresh location required for readiness");binding=b;this.ready=ready;this.locationEpoch=locationEpoch;
            ownedRouteBusy=route;ownedBatchBusy=batch;externalBusy=external;routeConfigured=configured;
            this.nurserySnapshot=nurserySnapshot;
        }
    }
    static final class Observation {
        final Binding binding;final String key;final Evidence evidence;
        Observation(Binding b,String key,Evidence e){if(b==null||key==null||e==null)throw new IllegalArgumentException("Observation required");binding=b;this.key=key;evidence=e;}
    }
    static final class Result {
        final EmulatorDailyPlan.Action action;final String key,reason;
        final EmulatorNurseryBudget.Advice nursery;
        Result(EmulatorDailyPlan.Action a,String k,String r,EmulatorNurseryBudget.Advice n){action=a;key=k;reason=r;nursery=n;}
    }
    private final Store store;private final Port port;
    private final boolean overnight;
    private EmulatorNurseryBudget.Advice nurseryAdvice=EmulatorNurseryBudget.unknown();
    EmulatorDailyRunner(Store store,Port port){this(store,port,false);}
    EmulatorDailyRunner(Store store,Port port,boolean overnight){if(store==null||port==null)throw new IllegalArgumentException("Complete ports required");this.store=store;this.port=port;this.overnight=overnight;}
    /** Caller must hold one cross-process Store lock for the entire tick, including effects. */
    Result tick(long epoch,Facts facts) throws Exception {
        nurseryAdvice=EmulatorNurseryBudget.unknown();
        if(epoch<0 || facts==null)throw new IllegalArgumentException("Clock/facts required");
        State read=store.read();State s=read==null ? new State(facts.binding,new EmulatorDailyPlan.Journal(EmulatorDailyPlan.workDay(epoch,overnight),overnight)) : State.decode(read.encode());
        if(s.journal.overnight!=overnight)return result(EmulatorDailyPlan.Action.REVIEW,s.journal.pendingKey,"schedule-policy-changed-no-adoption");
        if(!s.binding.same(facts.binding))return result(EmulatorDailyPlan.Action.REVIEW,s.journal.pendingKey,"binding-changed-no-adoption");
        if(s.journal.failed)return result(EmulatorDailyPlan.Action.REVIEW,s.journal.pendingKey,"operator-review-required");
        if(facts.ownedRouteBusy && facts.ownedBatchBusy)return review(s,"conflicting-owned-work");
        if((facts.ownedRouteBusy && s.journal.pending!=EmulatorDailyPlan.Pending.ROUTE) ||
                (facts.ownedBatchBusy && s.journal.pending!=EmulatorDailyPlan.Pending.BATCH))return review(s,"unreserved-work-never-adopted");
        // Session epoch is stable identity. Location gate epoch may increase after a
        // legitimate gap/jump/reset; capture a fresh epoch per new intent, not per day.
        // Unknown/regressed location prevents NEW work, never replays a pending one.
        boolean ready=facts.ready && facts.locationEpoch>0 && facts.locationEpoch>=s.lastLocationEpoch;
        EmulatorDailyPlan.Decision d=EmulatorDailyPlan.next(s.journal,epoch,ready,facts.ownedRouteBusy,facts.ownedBatchBusy,facts.routeConfigured);
        if(d.action==EmulatorDailyPlan.Action.REVIEW)return review(s,d.reason);
        if(EmulatorNurseryBudget.usable(facts.nurserySnapshot,s.binding,Instant.ofEpochMilli(epoch).atZone(EmulatorDailyPlan.ZONE).toLocalDate(),epoch,ready)) {
            s.nursery=EmulatorNurseryBudget.update(s.nursery,facts.nurserySnapshot);
            nurseryAdvice=EmulatorNurseryBudget.advice(s.nursery,true);
            // Persist/readback high-water before returning advice or issuing effects.
            // This advice never enables/blocks GPS, total steps, plucking or claims.
            commit(s);
        }
        if(s.stopAttempted || s.batchCloseAttempted)return observe(s);
        java.time.ZonedDateTime local=Instant.ofEpochMilli(epoch).atZone(EmulatorDailyPlan.ZONE);
        int minute=local.getHour()*60+local.getMinute();
        if(facts.ownedBatchBusy && !EmulatorDailyPlan.active(epoch,s.journal.overnight)) {
            // Exact binding/key plus the adapter's fresh owned-batch proof are
            // required. Never adopt or close an external batch; resource/GPS
            // readiness loss must not reopen admission on our existing work.
            s.batchCloseAttempted=true;commit(s);
            try{port.closeBatchAdmissions(s.binding,s.journal.pendingKey);}
            catch(Exception unknown){return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"batch-close-reply-unknown-observe-only");}
            return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"batch-admission-close-sent-not-task-completion");
        }
        if(d.action==EmulatorDailyPlan.Action.CONFIRM_CURRENT)return observe(s);
        if(d.action==EmulatorDailyPlan.Action.STOP_ROUTE) {
            if(s.journal.pending!=EmulatorDailyPlan.Pending.ROUTE)return review(s,"stop-needs-exact-owned-intent");
            s.stopAttempted=true;commit(s);
            try{port.stopOwnedRoute(s.binding,s.journal.pendingKey);}catch(Exception unknown){return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"stop-reply-unknown-observe-only");}
            return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"stop-sent-not-completion");
        }
        if(d.action==EmulatorDailyPlan.Action.START_ROUTE || d.action==EmulatorDailyPlan.Action.FINAL_BATCH) {
            if(facts.externalBusy){commit(s);return result(EmulatorDailyPlan.Action.WAIT,"","external-work-preserved");}
            EmulatorDailyPlan.reserve(s.journal,d);s.pendingLocationEpoch=facts.locationEpoch;s.lastLocationEpoch=facts.locationEpoch;commit(s);
            // Adapter must freshly recheck this epoch immediately before effect;
            // a mismatch refuses the effect and leaves the durable intent uncertain.
            long deadline=EmulatorDailyPlan.deadline(s.journal.day,s.journal.overnight);
            try{if(d.action==EmulatorDailyPlan.Action.START_ROUTE)port.startRoute(s.binding,d.key,s.pendingLocationEpoch);else port.finalBatch(s.binding,d.key,s.pendingLocationEpoch,deadline);}
            catch(Exception unknown){return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,d.key,"send-reply-unknown-observe-only");}
            return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,d.key,"sent-not-observed-admission-or-completion");
        }
        commit(s);return result(d.action,d.key,d.reason);
    }
    private Result observe(State s) throws Exception {
        if(s.journal.pending==EmulatorDailyPlan.Pending.NONE)return review(s,"confirmation-without-intent");
        Observation o;
        try{o=port.observe(s.binding,s.journal.pendingKey);}catch(Exception unknown){commit(s);return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"observation-unavailable");}
        if(o==null || o.evidence==Evidence.UNKNOWN){commit(s);return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,s.journal.pendingKey,"confirmation-pending-no-replay");}
        if(!s.binding.same(o.binding) || !s.journal.pendingKey.equals(o.key))return review(s,"receipt-binding-or-key-differs");
        if(o.evidence==Evidence.ADMITTED){EmulatorDailyPlan.acknowledged(s.journal,o.key);commit(s);return result(EmulatorDailyPlan.Action.CONFIRM_CURRENT,o.key,"admitted-not-complete");}
        if(o.evidence==Evidence.CLOSED_SKIPPED&&s.journal.pending!=EmulatorDailyPlan.Pending.ROUTE)return review(s,"skipped-receipt-is-not-batch-claim-completion");
        EmulatorDailyPlan.confirmed(s.journal,o.key,o.evidence==Evidence.COMPLETE_ALL_CLAIMS);s.stopAttempted=false;s.batchCloseAttempted=false;s.pendingLocationEpoch=0;commit(s);
        return result(EmulatorDailyPlan.Action.WAIT,o.key,o.evidence==Evidence.CLOSED_SKIPPED?"owned-route-safely-closed-incomplete-no-replay":"owned-completion-confirmed");
    }
    private Result review(State s,String reason)throws Exception{nurseryAdvice=EmulatorNurseryBudget.unknown();s.journal.failed=true;commit(s);return result(EmulatorDailyPlan.Action.REVIEW,s.journal.pendingKey,reason);}
    private void commit(State s)throws Exception {
        String expected=s.encode();store.save(State.decode(expected));State actual=store.read();
        if(actual==null || !expected.equals(actual.encode()))throw new IllegalStateException("Intent durable readback differs; no effect authorized");
    }
    private Result result(EmulatorDailyPlan.Action a,String k,String r){return new Result(a,k,r,nurseryAdvice);}
    private static boolean sha(String s){return s!=null && s.matches("[0-9a-f]{64}");}
    static String base64(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
    static String unbase64(String s){byte[] b=Base64.getDecoder().decode(s);String text=new String(b,StandardCharsets.UTF_8);if(!base64(text).equals(s))throw new IllegalArgumentException("Noncanonical UTF8/base64");return text;}
    static String digest(String s){try{byte[] b=MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte x:b)out.append(String.format(java.util.Locale.ROOT,"%02x",x&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}

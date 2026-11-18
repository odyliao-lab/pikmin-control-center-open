package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Actual Android adapter for a CLOSED original route round -> new PREPARE
 * lease. Does NOT stop/start planting, select petals, resume provider movement,
 * or enable daily. Caller must retain next for cleanup even if open fails.
 * Not yet invoked by FlowerFarmService; supplied to the MuMu successor only. */
final class EmulatorPetalRouteHandoff {
    private static final String FILES="/data/user/0/com.nianticlabs.pikmin/files/";
    final RouteSession next;
    private final Context context;
    private final RouteSession prior;
    private final long requested;
    private final SharedPreferences journal;
    private final String key;
    private String wire;
    private EmulatorClosedPlantingRound boundary;
    private final EmulatorRouteRemainder remainder;
    EmulatorPetalRouteHandoff(Context context,RouteSession prior,long requested) {
        this(context,prior,requested,null);
    }
    EmulatorPetalRouteHandoff(Context context,RouteSession prior,long requested,EmulatorRouteRemainder remainder) {
        if(context==null||prior==null||requested<=0||!context.getPackageName().equals("dev.ody.pikmincontrol.mumu"))
            throw new IllegalArgumentException("MuMu route binding required");
        this.context=context;this.prior=prior;this.requested=requested;
        this.remainder=remainder==null?null:EmulatorRouteRemainder.decode(remainder.encode());
        next=new RouteSession(context,prior.pid,prior.account);
        journal=context.getSharedPreferences("emulator_petal_handoff",0);key="round_"+prior.token;
        // Never recreate unknown close/open effects after process death.
        if(journal.contains(key))throw new IllegalStateException("Handoff journal retained; observe/reconcile, never replay");
    }
    private void requireWork()throws Exception {
        if(!GpsWorkLock.heldBy(context,GpsWorkLock.FLOWER_FARM)||WorkStatus.stoppingAll||
           CoreUpdater.pending(context)||SetupDeployment.pending(context)||UpdateInstaller.pending(context))
            throw new IllegalStateException("Exclusive live farm work required");
        CoreUpdater.requireReady(context);
        if(!FlowerFarmService.isRunning()||!prior.pid.equals(CoreUpdater.runtimePid(context,CoreUpdater.pair(context)))||!prior.account.equals(SessionIdentity.read()))
            throw new IllegalStateException("Handoff process/account changed");
        EmulatorRouteGuard.requireNoReservation();
    }
    private EmulatorClosedPlantingRound closed()throws Exception {
        requireWork();
        String raw=GpsModuleGateway.farmRead(ProbeSnapshot.command(
            "pidof com.nianticlabs.pikmin","cat /proc/sys/kernel/random/boot_id",
            "head -c 2049 "+FILES+"core_capabilities.tsv",
            "head -c 2049 "+FILES+"emulator_planting_transaction.tsv",
            "head -c 2049 "+FILES+"planting_control_status.tsv",
            "cat /proc/sys/kernel/random/boot_id","pidof com.nianticlabs.pikmin"));
        return EmulatorClosedPlantingRound.read(EmulatorOwnedPetalGateway.sections(raw,7),prior.pid,prior.account,prior.token,requested,System.currentTimeMillis());
    }
    void requireClosed()throws Exception {closed();}
    private void persist(String phase,EmulatorClosedPlantingRound proof)throws Exception {
        String body="v2\t"+phase+"\t"+prior.pid+"\t"+proof.boot+"\t"+proof.epoch+"\t"+prior.account+"\t"+
            prior.token+"\t"+next.token+"\t"+requested+"\t"+proof.started+"\t"+
            (remainder==null?"-":java.util.Base64.getEncoder().encodeToString(remainder.encode().getBytes(StandardCharsets.UTF_8)))+"\tend\n";
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        String value=body+"sha256\t"+hex+"\n";
        if(!journal.getString(key,"").equals(wire==null?"":wire)||!journal.edit().putString(key,value).commit()||!value.equals(journal.getString(key,"")))
            throw new IllegalStateException("Durable handoff readback failed; preserve uncertain effect");
        wire=value;
    }
    void closePrior()throws Exception {
        if(wire!=null||!prior.owned||next.owned||prior.token.equals(next.token))throw new IllegalStateException("Once owned handoff required");
        prior.requireSameService();
        RouteObservation observation=GpsModuleGateway.routeObservation(context,prior.pid);
        observation.require(prior.pid,prior.account,requested,false);
        if(observation.planting.planting||observation.planting.owned)throw new IllegalStateException("Cannot hand off active/unresolved planting");
        EmulatorClosedPlantingRound proof=closed();
        if(!prior.stopped())throw new IllegalStateException("Old route stop unconfirmed"); // exact token + fresh native status
        persist("close-intent",proof); // unknown close cannot be repeated
        closed(); // live pre-effect check, never authority from saved journal
        prior.close(); // original pause/watchdog-exit/provider restoration/GPS fence path
        EmulatorClosedPlantingRound after=closed();
        if(!after.boot.equals(proof.boot)||after.epoch!=proof.epoch||after.started!=proof.started||prior.owned||RouteSession.pending(context))
            throw new IllegalStateException("Closed boundary changed; do not open next");
        persist("closed",after);
        boundary=after;
    }
    void openNext()throws Exception {
        if(wire==null||!wire.startsWith("v2\tclosed\t")||prior.owned||next.owned||RouteSession.pending(context))
            throw new IllegalStateException("Exact durable closed boundary required");
        EmulatorClosedPlantingRound proof=closed();
        if(boundary==null||!proof.boot.equals(boundary.boot)||proof.epoch!=boundary.epoch||proof.started!=boundary.started)
            throw new IllegalStateException("Closed identity changed before next lease");
        persist("open-intent",proof); // caller holds next BEFORE this effect
        requireWork();
        next.open(false); // original gates + fresh UUID; no WALK or planting ON
        if(!next.owned||!next.account.equals(prior.account)||!next.pid.equals(prior.pid))throw new IllegalStateException("New prepare lease unconfirmed");
        persist("prepared",proof);
    }
}

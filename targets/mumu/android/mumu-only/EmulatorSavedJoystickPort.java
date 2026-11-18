package dev.ody.pikmincontrol;

import android.content.*;
import android.os.Looper;
import java.io.IOException;
import java.util.concurrent.*;

/** Actual Android transport for the independent saved-route scheduler.
 * Caller owns the durable run/intent journal and saved-city-loop GPS work owner.
 * It must not clear an uncertain intent or use this transport to adopt an existing route.
 * STATUS is advisory provider state, NOT proof of the saved name, movement or game effects. */
final class EmulatorSavedJoystickPort implements AutoCloseable {
    private static volatile String stage="idle";
    static String diagnostic(){return stage;}
    static final String OWNER="saved-city-loop";
    interface IntentJournal {
        /** Must fsync/commit the intent before returning. Refuse an unresolved predecessor. */
        void before(EmulatorSavedJoystickProtocol.Operation operation)throws Exception;
        /** Only records transport return, not route/game completion. */
        void returned(EmulatorSavedJoystickProtocol.Operation operation)throws Exception;
        /** Only a caught local refusal BEFORE entering send may use this.
         * A transport exception must retain UNKNOWN, never invoke this hook. */
        default void notSent(EmulatorSavedJoystickProtocol.Operation operation)throws Exception{throw new IOException("No durable not-sent journal");}
    }
    private final Context app;
    private final EmulatorSavedRoutePlan plan;
    private final String guard;
    private final BlockingQueue<Intent> replies=new ArrayBlockingQueue<>(4);
    private volatile boolean closed;
    private final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        if("theappninjas.gpsjoystick.STATUS_RESPONSE".equals(intent.getAction()))replies.offer(new Intent(intent));
    }};
    EmulatorSavedJoystickPort(Context context,EmulatorSavedRoutePlan plan,String boot,int providerPid,long providerTicks)throws Exception{
        EmulatorRuntime.require();
        if(context==null||plan==null||boot==null||!boot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||providerPid<=0||providerTicks<=0)
            throw new IllegalArgumentException("Current provider identity required");
        app=context.getApplicationContext();this.plan=plan;
        if(app==null||!"dev.ody.pikmincontrol.mumu".equals(app.getPackageName()))throw new SecurityException("MuMu only");
        // Existing overlay only: absent service needs a separate explicit startup,
        // never an implicit launch/TELEPORT or a game-readiness requirement here.
        String pkg=EmulatorSavedJoystickProtocol.PACKAGE;
        guard="set -eu; test \"$(getprop ro.build.version.sdk)\" = 35; "+
            "test \"$(getprop ro.product.cpu.abi)\" = x86_64; test \"$(getprop ro.dalvik.vm.native.bridge)\" = libnb.so; "+
            "test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+boot+"; "+
            "test \"$(pidof "+pkg+":overlay)\" = "+providerPid+"; "+
            "test \"$(awk '{print $22}' /proc/"+providerPid+"/stat)\" = "+providerTicks+"; "+
            "pcc_apk=$(pm path "+pkg+"); case \"$pcc_apk\" in package:/data/app/*/base.apk) ;; *) exit 1;; esac; "+
            "pcc_apk=${pcc_apk#package:}; test -f \"$pcc_apk\"; test ! -L \"$pcc_apk\"; "+
            "test \"$(sha256sum \"$pcc_apk\" | cut -d' ' -f1)\" = "+EmulatorSavedJoystickProtocol.APK_SHA+"; ";
        requireOwner();
        app.registerReceiver(receiver,new IntentFilter("theappninjas.gpsjoystick.STATUS_RESPONSE"),Context.RECEIVER_EXPORTED);
    }
    private void requireOwner()throws IOException{
        if(closed||Looper.myLooper()==Looper.getMainLooper()||!GpsWorkLock.heldBy(app,OWNER)||
           DispatchBatchService.isRunning()||FlowerFarmService.isRunning())throw new IOException("Saved GPS owner unavailable; no command");
    }
    private void send(EmulatorSavedJoystickProtocol.Operation operation)throws Exception{
        send(operation,plan);
    }
    private void send(EmulatorSavedJoystickProtocol.Operation operation,EmulatorSavedRoutePlan selected)throws Exception{
        stage="send-owner";
        requireOwner();
        if(WorkStatus.stoppingAll&&operation!=EmulatorSavedJoystickProtocol.Operation.STATUS&&operation!=EmulatorSavedJoystickProtocol.Operation.ROUTE_PAUSE)throw new IOException("Global stop; no new GPS work");
        // Same root flock inode as existing batch/watchdog callers. No game PID,
        // planting, HC, coordinate freshness or old route journal in this command.
        String command=guard+EmulatorSavedJoystickProtocol.command(operation,selected)+"; "+guard+"printf 'SAVED_GPS_SENT\\n'";
        BoundedCommand.Result r=BoundedCommand.root(EmulatorGpsShellFence.wrap(command),5,8192);
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0||!r.text.trim().endsWith("SAVED_GPS_SENT")){
            stage=r==null?"send-null":r.timedOut?"send-timeout":r.truncated?"send-clipped":r.exitCode!=null&&r.exitCode==75?"send-fence":r.exitCode!=null&&r.exitCode!=0?"send-exit":"send-output";
            throw new IOException("Saved GPS command unconfirmed; do not replay");
        }
    }
    EmulatorSavedJoystickProtocol.Status observe()throws Exception{
        synchronized(GpsWorkLock.class){
            requireOwner();replies.clear();send(EmulatorSavedJoystickProtocol.Operation.STATUS);
            stage="status-wait";Intent reply=replies.poll(3,TimeUnit.SECONDS);
            if(reply==null||reply.getExtras()==null)throw new IOException("Saved GPS status unavailable; leave other lanes running");
            stage="status-fields";String[] flags={"is_running","is_route_active","is_route_paused"};
            for(String key:flags)if(!(reply.getExtras().get(key) instanceof Boolean))throw new IOException("Missing GPS status flag");
            for(String key:new String[]{"route_total","route_remaining"})if(!(reply.getExtras().get(key) instanceof Integer))throw new IOException("Missing GPS count");
            Object speed=reply.getExtras().get("speed");Object mode=reply.getExtras().get("route_mode");
            if(!(speed instanceof Float||speed instanceof Double)||!(mode instanceof String))throw new IOException("Missing GPS speed/mode");
            stage="status-owner";requireOwner();
            return new EmulatorSavedJoystickProtocol.Status(reply.getBooleanExtra(flags[0],false),reply.getBooleanExtra(flags[1],false),
                reply.getBooleanExtra(flags[2],false),reply.getIntExtra("route_total",-1),reply.getIntExtra("route_remaining",-1),((Number)speed).doubleValue(),(String)mode);
        }
    }
    /** One caller-journalled effect. No automatic retry, restart, stop or petal coupling. */
    void effect(EmulatorSavedJoystickProtocol.Operation operation,IntentJournal journal)throws Exception{
        if(operation==null||operation==EmulatorSavedJoystickProtocol.Operation.STATUS||operation==EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH||journal==null)throw new IllegalArgumentException("Effect journal required");
        synchronized(GpsWorkLock.class){
            requireOwner();
            EmulatorSavedJoystickProtocol.Status before=observe();
            stage="effect-precondition";if(!EmulatorSavedJoystickProtocol.allows(operation,before))
                throw new IOException("Saved loop precondition differs; no adoption or mode rewrite");
            journal.before(operation);requireOwner();send(operation);journal.returned(operation);
        }
    }
    /** Only the dated rotation caller can replace its own proven paused route.
     * The durable guard is rechecked immediately before journal and transport. */
    void switchPaused(EmulatorSavedRoutePlan selected,IntentJournal journal,RotationGuard authority)throws Exception{
        if(selected==null||journal==null||authority==null)throw new IllegalArgumentException("Explicit rotation authority required");
        synchronized(GpsWorkLock.class){
            requireOwner();
            EmulatorSavedJoystickProtocol.Status before=observe();
            if(!EmulatorSavedJoystickProtocol.allows(EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH,before))throw new IOException("Original route is not safely paused");
            authority.check();journal.before(EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH);
            try{requireOwner();authority.check();}
            catch(Exception refused){journal.notSent(EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH);throw refused;}
            send(EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH,selected);
            journal.returned(EmulatorSavedJoystickProtocol.Operation.ROUTE_SWITCH);
        }
    }
    interface RotationGuard {void check()throws Exception;}
    public synchronized void close(){if(!closed){closed=true;app.unregisterReceiver(receiver);}}
}

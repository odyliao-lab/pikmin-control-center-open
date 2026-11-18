package dev.ody.pikmincontrol;

import android.content.Context;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Explicit one-shot MuMu experiment. Does NOT enable a formal scheduler,
 * manufacture facts, resume stored intent, write steps or use harvesting. */
final class EmulatorAndroidDevelopmentBatch {
    interface Listener {void changed(String text);}
    private static final String PREFS="development_batch_v1",LAST="last-explicit-run";
    private static final long CLEANUP_OBSERVE_MS=120000;
    private static volatile boolean active,stopRequested;
    private static volatile long stopAt;
    private static volatile String activeFirst="",message="尚未開始受控批次。";
    static boolean active(){return active;}
    static String message(){return message;}
    static String activeKey(){return activeFirst;}
    static synchronized void stop(String firstKey){
        if(!active||firstKey==null||firstKey.isEmpty()||!firstKey.equals(activeFirst))throw new IllegalStateException("Exact current development run required");
        if(!stopRequested){stopAt=System.currentTimeMillis();stopRequested=true;} // never prolong cleanup/admission
    }
    static void run(Context input,Listener listener)throws Exception{
        Context app=app(input);
        synchronized(EmulatorAndroidDevelopmentBatch.class){
            if(active)throw new IllegalStateException("Development batch already active");active=true;stopRequested=false;stopAt=0;activeFirst="";
        }
        String stage="configuration";
        try(EmulatorAndroidDailyStore daily=EmulatorAndroidDailyStore.open(app)){
            EmulatorDailyConfiguration config=EmulatorAndroidDailyConfigured.read(app);String configSha=EmulatorDailyRunner.digest(config.encode());
            EmulatorDailyRunner.State state=daily.read();String before=state==null?null:state.encode();
            if(state!=null&&(!config.binding.same(state.binding)||state.journal.failed||state.journal.pending!=EmulatorDailyPlan.Pending.NONE))throw new IllegalStateException("Previous formal intent preserved");
            stage="original-fresh-facts";EmulatorDailyRunner.Facts fresh=preflight(app,config,configSha,daily,before,state);
            stage="previous-run-observation";requirePreviousClosed(app,config);
            Path files=EmulatorAndroidDailyStore.files(app);
            long now=System.currentTimeMillis();String day=java.time.Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE).toLocalDate().toString();
            EmulatorDevelopmentBatch.Window first=EmulatorDevelopmentBatch.create(day,UUID.randomUUID().toString().replace("-",""),now);
            EmulatorDevelopmentBatchRun run=new EmulatorDevelopmentBatchRun(config.binding,configSha,first);
            stage="explicit-intent-publication";
            run.persist(files,EmulatorAndroidDailyStore::syncDirectory,EmulatorAndroidDevelopmentBatch::privateMode);
            if(!run.encode().equals(EmulatorDevelopmentBatchRun.read(files,first.key).encode()))throw new IllegalStateException("Explicit run readback differs");
            String pointer=EmulatorDevelopmentBatchRun.preference(run);
            if(!app.getSharedPreferences(PREFS,0).edit().putString(LAST,pointer).commit()||!pointer.equals(app.getSharedPreferences(PREFS,0).getAll().get(LAST)))throw new IllegalStateException("Explicit request pointer unconfirmed");
            activeFirst=first.key;
            EmulatorAndroidDailyBatchPort port=new EmulatorAndroidDailyBatchPort(app);
            EmulatorDevelopmentBatch.Coordinator coordinator=new EmulatorDevelopmentBatch.Coordinator(config.binding,first);
            List<EmulatorDailyBatchOutcome> completed=new ArrayList<>();String key=first.key;
            for(int sweep=1;sweep<=EmulatorDevelopmentBatch.MAX_SWEEPS;sweep++){
                if(stopRequested||!first.open(System.currentTimeMillis())){publish(listener,"已停止接收新任務；沒有重送未開始批次。");return;}
                if(sweep>1){stage="original-fresh-facts";fresh=preflight(app,config,configSha,daily,before,state);}
                stage="sweep-reservation";
                run.reserve(files,key,fresh.locationEpoch,System.currentTimeMillis(),EmulatorAndroidDailyStore::syncDirectory,EmulatorAndroidDevelopmentBatch::privateMode);
                if(stopRequested||!first.open(System.currentTimeMillis())){
                    publish(listener,"已停止接收新任務；本次未開始的預約保留待核對，沒有啟動或重送批次。");return;
                }
                publish(listener,"正在執行三類收工批次 "+sweep+" / 3；新任務總上限15分鐘，逐ID核對領回。");
                stage="original-batch-start";
                try{port.startDevelopment(config.binding,first.sweep(sweep),fresh.locationEpoch);}
                catch(Exception unknown){publish(listener,"開始回覆未確認；只觀察本次不可重送的原工作。");}
                boolean closeAttempted=false;EmulatorDailyRunner.Observation observed=null;
                while(true){
                    now=System.currentTimeMillis();long until=first.deadline+CLEANUP_OBSERVE_MS;
                    if(stopRequested)until=Math.min(until,stopAt+CLEANUP_OBSERVE_MS);
                    if(now>until)throw new IllegalStateException("Bounded confirmation unavailable; preserve every intent");
                    if((stopRequested||!first.open(now))&&!closeAttempted&&DispatchBatchService.ownsDailyBatch(config.binding,key)){
                        closeAttempted=true;stage="close-new-admissions";
                        try{port.closeAdmissions(config.binding,key);}catch(Exception unknown){ /* one close attempt only; original work remains observable */ }
                    }
                    stage="independent-batch-observation";
                    try{observed=port.observe(config.binding,key);}catch(Exception unavailable){observed=null;}
                    if(observed!=null&&(observed.evidence==EmulatorDailyRunner.Evidence.COMPLETE||observed.evidence==EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS))break;
                    Thread.sleep(1000); // bounded passive reads only; no start/claim replay
                }
                if(!config.binding.same(observed.binding)||!key.equals(observed.key))throw new IllegalStateException("Observation changed; never adopt");
                stage="full-outcome-readback";completed.clear();
                for(int n=1;n<=sweep;n++){
                    EmulatorDailyBatchOutcome outcome=EmulatorDailyBatchOutcome.readExact(files,config.binding,first.sweep(n).key,n<sweep,EmulatorAndroidDailyStore::syncDirectory);
                    if(outcome==null||outcome.needsReview())throw new IllegalStateException("Exact closed outcome required");completed.add(outcome);
                }
                EmulatorDailyBatchScan.Snapshot scan=EmulatorDailyBatchScan.read(app,config.binding);
                EmulatorDailyBatchDay rollup=new EmulatorDailyBatchDay(config.binding,key,completed,scan);
                if(!rollup.clean||rollup.unresolved!=0)throw new IllegalStateException("Claims still unknown");
                publish(listener,rollup.summary()+"；原停止控制及定位工作權已獨立核對。");
                if(stopRequested)return;
                String next=coordinator.next(completed,scan,System.currentTimeMillis());
                if(next.isEmpty())return;key=next; // finite same-window sweep only, never reconstruct a pending one
            }
        }catch(Exception failure){
            publish(listener,"受控批次未完成確認（階段："+stage+"）；原請求與待辦保留，不自動重送或宣稱領回。");throw failure;
        }finally{synchronized(EmulatorAndroidDevelopmentBatch.class){active=false;activeFirst="";}}
    }
    private static EmulatorDailyRunner.Facts preflight(Context app,EmulatorDailyConfiguration config,String sha,EmulatorAndroidDailyStore daily,String before,EmulatorDailyRunner.State state)throws Exception{
        if(!StepPermissions.notifications(app)||EmulatorAndroidDailySchedule.enabled(app)!=null)throw new IllegalStateException("Visible test notification and formal scheduler OFF required");
        EmulatorDailyRouteWork.foregroundExistingGame(app,config.binding);
        EmulatorAndroidDailyFacts source=new EmulatorAndroidDailyFacts(app,config,new EmulatorAndroidDailyAcceptance(app));
        EmulatorDailyRunner.Facts fresh=source.read(state==null?null:EmulatorDailyRunner.State.decode(before));
        EmulatorDailyRunner.State after=daily.read();
        if(!Objects.equals(before,after==null?null:after.encode())||!fresh.ready||fresh.externalBusy||fresh.ownedRouteBusy||fresh.ownedBatchBusy||!fresh.routeConfigured||
                !sha.equals(EmulatorDailyRunner.digest(EmulatorAndroidDailyConfigured.read(app).encode()))){
            android.util.Log.w("PccDevelopmentBatch","Original fresh batch preflight refused: "+source.reason());
            throw new IllegalStateException("Original fresh accepted idle facts required: "+source.reason());
        }
        return fresh;
    }
    private static void requirePreviousClosed(Context app,EmulatorDailyConfiguration config)throws Exception{
        Object last=app.getSharedPreferences(PREFS,0).getAll().get(LAST);if(last==null)return;
        if(!(last instanceof String))throw new IllegalStateException("Previous request unknown");
        EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.decodePreference((String)last);Path files=EmulatorAndroidDailyStore.files(app);
        if(!run.binding.same(config.binding)||!run.configSha.equals(EmulatorDailyRunner.digest(config.encode()))||!run.encode().equals(EmulatorDevelopmentBatchRun.read(files,run.window.key).encode()))throw new IllegalStateException("Previous binding/request needs separate review");
        String key=run.latestReservation(files);if(key.isEmpty())throw new IllegalStateException("Previous explicit intent needs independent review");
        EmulatorDailyRunner.Observation observation=EmulatorDailyBatchOutcome.completed(app,run.binding,key);
        if(observation==null||!run.binding.same(observation.binding)||!key.equals(observation.key)||
                !(observation.evidence==EmulatorDailyRunner.Evidence.COMPLETE||observation.evidence==EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS))throw new IllegalStateException("Previous task intent stays uncertain; no new run");
    }
    private static Context app(Context input){EmulatorRuntime.require();if(input==null||!"dev.ody.pikmincontrol.mumu".equals(input.getPackageName()))throw new IllegalArgumentException("Isolated explicit MuMu context required");Context app=input.getApplicationContext();if(app==null||!"dev.ody.pikmincontrol.mumu".equals(app.getPackageName()))throw new IllegalArgumentException("MuMu application required");return app;}
    private static void privateMode(Path file)throws Exception{Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));}
    private static void publish(Listener listener,String value){message=value;if(listener!=null)listener.changed(value);}
}

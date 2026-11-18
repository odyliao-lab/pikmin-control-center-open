package dev.ody.pikmincontrol;

import android.content.*;
import android.os.SystemClock;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Saved-name GPS, native ordinary planting and helpers are separate lanes. */
final class EmulatorSavedDailyRuntime implements AutoCloseable {
    static final String GAME="com.nianticlabs.pikmin",FILES="/data/user/0/"+GAME+"/files/";
    final Context app;final EmulatorSavedDailyStore store;
    volatile String gpsText="等待GPS",plantText="等待種花",helperText="等待背景功能",batchText="";
    volatile boolean helpersOff,plantClosed,gpsClosed,stepsClosed,batchClosed;
    volatile String recoveryText="等待核心健康確認",stepText="等待自動步數";
    private volatile boolean recoveryHold;
    private volatile boolean plantContinuationStorageFailed;
    private static volatile boolean gpsRetirementStorageFailed;
    private int unhealthySamples,healthySamples;
    private String healthyBinding="";
    private String preparedQuietKey="",preparedQuietFirst="";
    private long preparedQuietElapsed,preparedQuietObserved;
    private volatile EmulatorSavedJoystickPort gps;private boolean gpsOwned;private volatile boolean closed;
    volatile String stepsText="等待步數";
    private String boot;private long overlayTicks;private int overlayPid;
    private Identity lastGame;
    private EmulatorSavedRouteRotation.Slot rotationSlot(){
        String raw=store.get("rotation.plan","");
        return raw.isEmpty()?null:EmulatorSavedRouteRotation.decode(raw).at(System.currentTimeMillis());
    }
    private boolean rotationDraining(){
        EmulatorSavedRouteRotation.Slot slot=rotationSlot();
        return !store.get("rotation.settling","").isEmpty()||slot!=null&&!slot.key().equals(store.get("rotation.slot",""));
    }
    static boolean rotationArrivalNeedsPause(boolean should,boolean owned,boolean settling,boolean active){
        return should&&owned&&settling&&active;
    }
    private EmulatorSavedRoutePlan currentRoute(){
        String name=store.get("gps.route-name",store.plan.savedName());
        for(EmulatorSavedRoutePlan.City city:EmulatorSavedRoutePlan.City.values())if(city.savedName.equals(name))return new EmulatorSavedRoutePlan(city);
        throw new IllegalStateException("Recorded route unknown; no adoption");
    }
    static void scheduleRotation(Context c,LocalDate firstDay)throws Exception{
        synchronized(GpsWorkLock.class){
            EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
            if(s==null||!s.enabled()||!firstDay.equals(LocalDate.now(EmulatorDailyPlan.ZONE).plusDays(1))||
                    !s.get("rotation.plan","").isEmpty()||!s.get("gps.pending","").isEmpty()||!s.get("gps-bootstrap.pending","").isEmpty()||
                    !s.get("batch.pending","").isEmpty()||!s.get("batch-close.pending","").isEmpty()||DispatchBatchService.isRunning()||FlowerFarmService.isRunning())
                throw new IllegalStateException("Rotation requires enabled current schedule, clear GPS/batch and explicit next day");
            s.put("rotation.plan",new EmulatorSavedRouteRotation(firstDay).encode());
        }
    }
    EmulatorSavedDailyRuntime(Context c,EmulatorSavedDailyStore store){this.app=c.getApplicationContext();this.store=store;recoveryHold="1".equals(store.get("recovery.hold","0"));}
    static boolean managed(Context c){try{EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);return s!=null&&s.enabled();}catch(Exception e){return false;}}
    // There must be exactly one recovery owner. The legacy process watcher
    // does not understand saved-daily planting intents, so it must never kill
    // a process while a saved-daily run (including STOP/history) exists.
    static boolean ownsRecovery(Context c){try{return EmulatorSavedDailyStore.open(c)!=null;}catch(Exception malformed){return true;}}
    static boolean stoppedBatchReviewAllowed(boolean enabled,boolean gpsPending,boolean plantPending,boolean serviceLive,boolean sameSession,boolean sameCheckpoint){
        return !enabled&&!gpsPending&&!plantPending&&!serviceLive&&sameSession&&sameCheckpoint;
    }
    /** Permission for the existing explicit UNKNOWN archival dialog only.
     * Never writes daily originals, creates a claim, replays or calls it success.
     * APK replacement is irrelevant to archival; original PID/epoch/core/account
     * and exact persisted service/checkpoint correlation remain mandatory. */
    static void requireStoppedBatchReview(Context c,EmulatorSavedDailyStore saved)throws Exception{
        if(saved==null)return;
        if(saved.enabled())throw new IllegalStateException("請先停止每日排程，原件保留");
        if(saved.get("batch.pending","").isEmpty())return;
        if(!saved.current())throw new IllegalStateException("每日原件已改變");
        EmulatorDailyRunner.Binding held=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(saved.get("batch.binding","")));
        String key=saved.get("batch.key","");
        if(!saved.get("intent."+saved.get("batch.pending",""),"").equals("THREE_KINDS "+key))throw new IllegalStateException("原批次意圖不符");
        EmulatorDailyBatchOutcome original=EmulatorDailyBatchOutcome.readExact(EmulatorAndroidDailyStore.files(c),held,key,false,EmulatorAndroidDailyStore::syncDirectory);
        WorkCheckpoint cp=CheckpointStore.read(c,GpsWorkLock.BATCH);
        Identity live=new EmulatorSavedDailyRuntime(c,saved).game();
        boolean same=held.pid==live.pid&&held.boot.equals(live.boot)&&held.sessionEpoch==live.epoch&&held.account.equals(live.account)&&held.coreSha.equals(live.pair.sha);
        boolean checkpoint=original!=null&&original.checkpoint.equals(cp.id)&&held.account.equals(cp.account)&&cp.owner.equals(GpsWorkLock.BATCH);
        if(!stoppedBatchReviewAllowed(saved.enabled(),!saved.get("gps.pending","").isEmpty(),!saved.get("plant.pending","").isEmpty(),DispatchBatchService.isRunning(),same,checkpoint))
            throw new IllegalStateException("每日批次原件待核對；不清除、不重送");
    }
    private static volatile boolean batchRetirementStorageFailed;
    /** Explicit maintenance: preserve UNKNOWN, never infer an old success or
     * adopt its service. Native target exclusions are durable before releasing
     * any journal lane. The original checkpoint/outcome/key/binding remain. */
    private static java.nio.file.Path canonicalSavedRoot(Context c)throws Exception{
        java.nio.file.Path p=EmulatorAndroidDailyStore.files(c).resolve("saved-daily");
        if(java.nio.file.Files.isSymbolicLink(p)||!java.nio.file.Files.isDirectory(p,java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new java.io.IOException("Private saved directory required");
        return p;
    }
    static boolean gpsRetirementAllowed(boolean stopped,boolean current,boolean onlyGpsPending,boolean otherWork,boolean maintenance,boolean sameOwner){
        return stopped&&current&&onlyGpsPending&&!otherWork&&!maintenance&&sameOwner;
    }
    static String gpsRetirementLane(String intent,String route,String bootstrap){
        if(!EmulatorDeadPlantingRecovery.uuid(intent))throw new IllegalArgumentException("Exact GPS intent required");
        if(intent.equals(route)&&bootstrap.isEmpty())return "gps";
        if(intent.equals(bootstrap)&&route.isEmpty())return "gps-bootstrap";
        throw new IllegalArgumentException("Only one original GPS lane may retire");
    }
    private static void requireGpsRetirementJournal(Context c,EmulatorSavedDailyStore s,String intent)throws Exception{
        String lane=gpsRetirementLane(intent,s.get("gps.pending",""),s.get("gps-bootstrap.pending",""));
        boolean only=true;
        for(Map.Entry<String,?> e:s.journal.getAll().entrySet())if(e.getKey().endsWith(".pending")&&!e.getKey().equals(lane+".pending")&&
                (!(e.getValue() instanceof String)||!((String)e.getValue()).isEmpty()))only=false;
        SharedPreferences owner=c.getSharedPreferences("pikmin_gps_work_lock",0);
        boolean owns=EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner",""))&&s.run.equals(owner.getString("saved_run",""));
        if(!gpsRetirementAllowed(s.confirmedStop()&&!s.enabled()&&s.needsDrain(),s.current(),only,
                DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||RouteSession.pending(c),
                WorkStatus.stoppingAll||SetupDeployment.pending(c)||CoreUpdater.pending(c)||UpdateInstaller.pending(c)||CoreInstallWizard.preparing(c),owns))
            throw new IllegalStateException("請先正常停止排程與其他工作，保留原GPS意圖");
        if(lane.equals("gps-bootstrap")){
            // A returned service start is still UNKNOWN, never NOT_SENT or a
            // successful route. Only the exact paused, stopped predecessor
            // copied into this explicitly started run may use this entry.
            String provider=s.get("gps.provider","");EmulatorSavedDailyStore prior=s.stoppedPredecessor();
            if(!s.get("intent."+intent,"").equals("START_EXISTING_PROVIDER")||
               !s.get("gps-bootstrap.returned","").equals("yes")||
               !provider.startsWith(s.get("gps-bootstrap.boot","")+":")||
               !provider.equals(prior.get("gps.provider","")))throw new IllegalStateException("Original stopped-provider bootstrap required");
            return;
        }
        String slot=s.get("rotation.pending-slot","");
        EmulatorSavedRouteRotation.Slot held=EmulatorSavedRouteRotation.decode(s.get("rotation.plan","")).retained(slot);
        if(!s.get("intent."+intent,"").equals("ROUTE_SWITCH|"+slot)||!held.city.savedName.equals(s.get("rotation.pending-name","")))
            throw new IllegalStateException("只接受原輪替的未確認換線");
    }
    static String absentGpsProviderProof(String provider){
        String[] f=provider.split(":",-1);
        if(f.length!=3||!f[0].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||!f[1].matches("[1-9][0-9]{0,9}")||!f[2].matches("[1-9][0-9]{0,18}"))
            throw new IllegalArgumentException("Exact original provider required");
        // Absence, not STATUS/PAUSED or an advisory route label. No effect here.
        return "test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+f[0]+"; command -v pidof >/dev/null; "+
            "pcc_gp=''; pcc_rc=0; pcc_gp=$(pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay) || pcc_rc=$?; test $pcc_rc = 1; test -z \"$pcc_gp\"; test ! -e /proc/"+f[1]+"; ";
    }
    private static java.nio.file.Path gpsArchive(Context c,String intent)throws Exception{
        if(!EmulatorDeadPlantingRecovery.uuid(intent))throw new IllegalStateException("Exact GPS intent required");
        java.nio.file.Path p=canonicalSavedRoot(c).resolve("gps-unconfirmed-"+intent);
        if(java.nio.file.Files.exists(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)&&
                (!java.nio.file.Files.isDirectory(p,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.isSymbolicLink(p)))throw new IllegalStateException("GPS archive anchor differs");
        return p;
    }
    /** Explicit stopped-provider maintenance only. Keeps the old intent UNKNOWN;
     * no route command, game stop, success inference or new START is performed. */
    static String retireStoppedGps(Context c)throws Exception{
        synchronized(GpsWorkLock.class){
            EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);if(s==null)throw new IllegalStateException("No original saved run");
            String intent=s.get("gps.pending","");if(intent.isEmpty())intent=s.get("gps-bootstrap.pending","");
            if(intent.isEmpty()){
                requireGpsRetirementReady(c,s);if(s.get("gps.unconfirmed-intent","").isEmpty())throw new IllegalStateException("No GPS retirement request");
                EmulatorSavedDailyService.refreshRetiredGps(s);return "原換線UNKNOWN已保存；等待每日停止收尾後，再開始原排程。";
            }
            requireGpsRetirementJournal(c,s,intent);String lane=gpsRetirementLane(intent,s.get("gps.pending",""),s.get("gps-bootstrap.pending",""));EmulatorSavedDailyRuntime reader=new EmulatorSavedDailyRuntime(c,s);Identity live=reader.game();
            String provider=s.get("gps.provider",""),action=s.get("intent."+intent,"");
            String proof=quietWorkProof(s,live,true)+absentGpsProviderProof(provider);
            root(EmulatorGpsShellFence.wrap(proof+"echo GPS_PROVIDER_ABSENT"));Thread.sleep(1200);
            Identity later=reader.game();if(!live.key().equals(later.key()))throw new IllegalStateException("Fresh owner changed");
            root(EmulatorGpsShellFence.wrap(quietWorkProof(s,later,true)+absentGpsProviderProof(provider)+"echo GPS_PROVIDER_ABSENT"));
            java.nio.file.Path dir=gpsArchive(c,intent);
            if(!java.nio.file.Files.exists(dir,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                java.nio.file.Files.createDirectory(dir);java.nio.file.Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));EmulatorAndroidDailyStore.syncDirectory(dir.getParent());
            }
            EmulatorDeadPlantingRecovery.writeExact(dir.resolve("config.tsv"),s.wire);
            EmulatorDeadPlantingRecovery.writeExact(dir.resolve("stop.tsv"),EmulatorDailyBatchOutcome.readPrivate(dir.getParent().resolve(s.run+".stop"),256));
            java.nio.file.Path original=dir.resolve("original-run.xml");
            if(!java.nio.file.Files.exists(original,java.nio.file.LinkOption.NOFOLLOW_LINKS))
                EmulatorDeadPlantingRecovery.writeExact(original,EmulatorDailyBatchOutcome.readPrivate(EmulatorAndroidDailyStore.files(c).getParent().resolve("shared_prefs/saved_daily_run_"+s.run+".xml"),262144));
            String originalSha=EmulatorDailyRunner.digest(EmulatorDailyBatchOutcome.readPrivate(original,262144));
            String request="gps-unknown-v1\t"+s.run+"\t"+intent+"\t"+provider+"\t"+EmulatorDailyRunner.base64(action)+"\t"+originalSha+"\t"+later.account+"\t"+later.pair.sha+"\tUNKNOWN\tend\n";
            EmulatorDeadPlantingRecovery.writeExact(dir.resolve("request.tsv"),request);
            synchronized(EmulatorSavedDailyStore.class){synchronized(s.journal){
                requireGpsRetirementJournal(c,s,intent);
                if(!provider.equals(s.get("gps.provider",""))||!action.equals(s.get("intent."+intent,"")))throw new IllegalStateException("GPS original changed");
                Identity finalOwner=reader.game();if(!later.key().equals(finalOwner.key()))throw new IllegalStateException("Fresh owner changed");
                root(EmulatorGpsShellFence.wrap(quietWorkProof(s,finalOwner,true)+absentGpsProviderProof(provider)+"echo GPS_ORIGINAL_RECHECKED"));
                gpsRetirementStorageFailed=true;String result="UNKNOWN_PRESERVED:"+originalSha;
                if(!s.journal.edit().putString("result."+intent,result).putString("gps.unconfirmed-intent",intent)
                        .putString("gps.unconfirmed-original-sha",originalSha).putString("gps.unconfirmed-request-sha",EmulatorDailyRunner.digest(request))
                        .putString("gps.unconfirmed-lane",lane).putString("gps.phase","RETIRED").remove(lane+".pending").commit())throw new IllegalStateException("GPS UNKNOWN preservation unconfirmed");
                if(!result.equals(s.get("result."+intent,""))||!s.get("gps.pending","").isEmpty()||!s.get("gps-bootstrap.pending","").isEmpty())throw new IllegalStateException("GPS retirement readback differs");
                gpsRetirementStorageFailed=false;
            }}
            EmulatorSavedDailyService.refreshRetiredGps(s);
            return "原定位意圖UNKNOWN及完整原件已保存，未重送。等待每日停止收尾後，再開始原排程。";
        }
    }
    static void requireGpsRetirementReady(Context c,EmulatorSavedDailyStore s)throws Exception{
        if(gpsRetirementStorageFailed)throw new IllegalStateException("GPS retirement storage unconfirmed");
        String intent=s.get("gps.unconfirmed-intent","");if(intent.isEmpty())return;
        java.nio.file.Path dir=gpsArchive(c,intent);String original=EmulatorDailyBatchOutcome.readPrivate(dir.resolve("original-run.xml"),262144);
        String request=EmulatorDailyBatchOutcome.readPrivate(dir.resolve("request.tsv"),4096),sha=EmulatorDailyRunner.digest(original);
        Identity live=new EmulatorSavedDailyRuntime(c,s).game();
        String expected="gps-unknown-v1\t"+s.run+"\t"+intent+"\t"+s.get("gps.provider","")+"\t"+EmulatorDailyRunner.base64(s.get("intent."+intent,""))+"\t"+sha+"\t"+live.account+"\t"+live.pair.sha+"\tUNKNOWN\tend\n";
        if(!request.equals(expected)||!EmulatorDailyRunner.digest(request).equals(s.get("gps.unconfirmed-request-sha",""))||
                !sha.equals(s.get("gps.unconfirmed-original-sha",""))||!s.get("result."+intent,"").equals("UNKNOWN_PRESERVED:"+sha)||
                !s.wire.equals(EmulatorDailyBatchOutcome.readPrivate(dir.resolve("config.tsv"),256))||
                !s.wire.equals(EmulatorDailyBatchOutcome.readPrivate(dir.resolve("stop.tsv"),256)))throw new IllegalStateException("Original GPS UNKNOWN archive differs");
        root(EmulatorGpsShellFence.wrap("set -eu; "+s.guard(false)+live.guard()+absentGpsProviderProof(s.get("gps.provider",""))+"echo GPS_ARCHIVE_READY"));
    }
    void refreshRetiredGps()throws Exception{
        synchronized(GpsWorkLock.class){
            if(store.enabled()||!store.get("gps.pending","").isEmpty()||!store.get("gps-bootstrap.pending","").isEmpty()||!store.get("gps.phase","").equals("RETIRED")||store.get("gps.unconfirmed-intent","").isEmpty())throw new IllegalStateException("Retired GPS receipt required");
            EmulatorSavedJoystickPort p=gps;gps=null;if(p!=null)p.close();gpsClosed=false;
        }
    }
    static boolean stoppedBatchGpsReady(String phase,boolean gpsOwned){return "PAUSED".equals(phase)||"RETIRED".equals(phase)&&!gpsOwned;}
    private static void retirementStage(Context c,String stage)throws java.io.IOException{
        if(!c.getSharedPreferences("batch_retirement_diagnostic",0).edit().putString("stage",stage).putString("error","").putLong("at",System.currentTimeMillis()).commit())throw new java.io.IOException("Maintenance diagnostic storage unconfirmed");
    }
    static String retirementDiagnostic(Context c){SharedPreferences p=c.getSharedPreferences("batch_retirement_diagnostic",0);return p.getString("stage","not-started")+" / "+p.getString("error","");}
    static void retirementFailure(Context c,Exception e){c.getSharedPreferences("batch_retirement_diagnostic",0).edit().putString("error",e.getClass().getSimpleName()).putLong("at",System.currentTimeMillis()).commit();}
    private static Identity awaitRetirementGame(Context c,EmulatorSavedDailyRuntime runtime)throws Exception{
        retirementStage(c,"waiting-fresh-core");long deadline=SystemClock.elapsedRealtime()+25000;Exception last=null;
        do{try{return runtime.game();}catch(Exception unavailable){last=unavailable;Thread.sleep(1000);}}while(SystemClock.elapsedRealtime()<deadline);
        throw new java.io.IOException("Fresh paired game not observed; originals retained",last);
    }
    static final int STOPPED_BATCH_MAX_HISTORY=16*1024*1024;
    static String stoppedBatchTerminalRows(String history,String id,String kind,long admission,long now){
        if(history==null||history.getBytes(StandardCharsets.UTF_8).length>STOPPED_BATCH_MAX_HISTORY||history.indexOf('\r')>=0||!history.endsWith("\n")||
           id==null||!id.matches("[A-Za-z0-9_-]{1,160}")||!("seed".equals(kind)||"fruit".equals(kind)||"gift".equals(kind))||admission<=0||now-admission<30000)stoppedBatchProofBad();
        int stage=0;long previous=admission;StringBuilder proof=new StringBuilder();
        for(String row:history.split("\n")){
            String[] f=row.split("\t",-1);if(f.length!=6)stoppedBatchProofBad();
            long at;try{at=Long.parseLong(f[0]);}catch(Exception e){throw new IllegalArgumentException("Malformed original history",e);}
            if(at<=0||at>now)stoppedBatchProofBad();
            if(at>=admission&&!f[3].equals(id)&&f[1].equals("start-requested"))stoppedBatchProofBad();
            if(!f[3].equals(id)||at<admission)continue;
            if(!f[2].equals(kind)||at<previous)stoppedBatchProofBad();previous=at;
            String event=f[1];
            if(event.equals("start-requested")){if(stage!=0)stoppedBatchProofBad();stage=1;proof.append(row).append('\n');}
            else if(event.equals("start-rpc-completed")){if(stage!=1)stoppedBatchProofBad();stage=2;proof.append(row).append('\n');}
            else if(event.equals("inventory-confirm-timeout")){if(stage!=2)stoppedBatchProofBad();stage=3;proof.append(row).append('\n');}
            else if(stage==0&&(event.equals("gps-requested")||event.equals("arrival-ready")||event.equals("selection-settling")))continue;
            else if(event.equals("start-rpc-faulted")||event.contains("confirmed")||event.contains("requested")||event.contains("started"))stoppedBatchProofBad();
        }
        if(stage!=3||now-previous<30000)stoppedBatchProofBad();return proof.toString();
    }
    private static void stoppedBatchProofBad(){throw new IllegalArgumentException("Exact completed Task plus unresolved inventory proof required");}
    private static void preserveStoppedHistory(java.nio.file.Path p,String text)throws Exception{
        if(text.getBytes(StandardCharsets.UTF_8).length>STOPPED_BATCH_MAX_HISTORY)throw new java.io.IOException("History bound");
        if(!java.nio.file.Files.exists(p,java.nio.file.LinkOption.NOFOLLOW_LINKS))try(java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(p,java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            java.nio.file.Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            java.nio.ByteBuffer bytes=java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));while(bytes.hasRemaining())out.write(bytes);out.force(true);
        }
        if(!text.equals(EmulatorDailyBatchOutcome.readPrivate(p,STOPPED_BATCH_MAX_HISTORY)))throw new java.io.IOException("History archive differs; never overwrite");
        EmulatorAndroidDailyStore.syncDirectory(p.getParent());
    }
    private static void retirementReadResult(Context c,BoundedCommand.Result r,String command)throws Exception{
        if(r!=null&&r.ok&&!r.timedOut&&!r.truncated&&r.exitCode!=null&&r.exitCode==0)return;
        c.getSharedPreferences("batch_retirement_diagnostic",0).edit().putString("root-result",r==null?"null":"exit="+r.exitCode+",timeout="+r.timedOut+",truncated="+r.truncated+",bytes="+r.text.getBytes(StandardCharsets.UTF_8).length)
            .putString("root-output-sha",r==null?"":EmulatorDailyRunner.digest(r.text)).commit();
        // Read-only failure evidence. Strip the complete mutation suffix;
        // even a passing re-observation never authorizes the failed effect.
        int effect=command.indexOf("umask 077;");String readOnly=effect<0?command:command.substring(0,effect);
        BoundedCommand.Result trace=BoundedCommand.root("set -x; "+readOnly,15,131072);
        c.getSharedPreferences("batch_retirement_diagnostic",0).edit().putString("private-read-only-trace-b64",trace==null?"":Base64.getEncoder().encodeToString(trace.text.getBytes(StandardCharsets.UTF_8))).commit();
        throw new java.io.IOException("Root read/effect not confirmed; original guards retained");
    }
    private static String retirementRoot(Context c,String command)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,5,131072);retirementReadResult(c,r,command);return r.text;
    }
    private static String retireRoot(Context c,boolean terminal,String command)throws Exception{
        return terminal?retirementRoot(c,command):root(command);
    }
    static String retireDeadBatch(Context c)throws Exception{
        return retireBatch(c,false);
    }
    static String retireStoppedTerminalBatch(Context c)throws Exception{
        return retireBatch(c,true);
    }
    private static String retireBatch(Context c,boolean terminalSameOwner)throws Exception{
        synchronized(GpsWorkLock.class){
            retirementStage(c,"stopped-work-guards");EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
            if(s==null||!s.current()||s.enabled()||!s.confirmedStop()||!stoppedBatchGpsReady(s.get("gps.phase",""),GpsWorkLock.heldBy(c,EmulatorSavedJoystickPort.OWNER))||
               !s.get("gps.pending","").isEmpty()||!s.get("gps-bootstrap.pending","").isEmpty()||!s.get("batch-close.pending","").isEmpty()||
               DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||WorkStatus.stoppingAll||RouteSession.pending(c)||
               SetupDeployment.pending(c)||CoreUpdater.pending(c)||UpdateInstaller.pending(c)||CoreInstallWizard.preparing(c))
                throw new IllegalStateException("先正常停止每日及其他工作；原件保留");
            String intent=s.get("batch.pending",""),key=s.get("batch.key",""),encoded=s.get("batch.binding","");
            if(intent.isEmpty())return "沒有待封存的批次；原歷史保留。";
            if(!EmulatorDeadPlantingRecovery.uuid(intent)||!("THREE_KINDS "+key).equals(s.get("intent."+intent,"")))throw new IllegalStateException("原批次意圖不符");
            EmulatorDailyRunner.Binding held=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(encoded));
            EmulatorSavedDailyRuntime runtime=new EmulatorSavedDailyRuntime(c,s);Identity live=awaitRetirementGame(c,runtime);
            // Only the reviewed successor native has the exclusion reader. An
            // old paired APK/core must not mark an exclusion it cannot enforce.
            if(CoreUpdater.pair(c).code<900157||!held.account.equals(live.account))throw new IllegalStateException("需要支援未決任務排除的新配對核心及原帳號");
            if(terminalSameOwner&&(held.pid!=live.pid||!held.boot.equals(live.boot)||held.sessionEpoch!=live.epoch||!held.coreSha.equals(live.pair.sha)))
                throw new IllegalStateException("原同程序核心完整身分不符");
            String dead=terminalSameOwner?"":"if test \"$(cat /proc/sys/kernel/random/boot_id)\" = '"+held.boot+"'; then test ! -e /proc/"+held.pid+"; fi; ";
            retirementStage(c,"fresh-stopped-proof");String proof=stoppedBatchProof(s,live)+dead;
            retireRoot(c,terminalSameOwner,proof+"echo VERIFIED");Thread.sleep(1200);live=runtime.game();proof=stoppedBatchProof(s,live)+dead;retireRoot(c,terminalSameOwner,proof+"echo VERIFIED");
            retirementStage(c,"exact-original-batch");java.nio.file.Path files=EmulatorAndroidDailyStore.files(c);
            WorkCheckpoint cp=CheckpointStore.read(c,GpsWorkLock.BATCH);
            long reviewAt=System.currentTimeMillis();
            java.nio.file.Path priorManifest=canonicalSavedRoot(c).resolve("batch-unconfirmed-"+intent).resolve("unconfirmed-inventory.tsv");
            if(terminalSameOwner&&java.nio.file.Files.exists(priorManifest,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                String[] header=EmulatorDailyBatchOutcome.readPrivate(priorManifest,262144).split("\n",-1)[0].split("\t",-1);
                if(header.length!=9||!header[0].equals("v2"))throw new IllegalStateException("原UNKNOWN manifest不符");
                reviewAt=Long.parseLong(header[5]);if(reviewAt>System.currentTimeMillis())throw new IllegalStateException("原核對時間不符");
            }
            EmulatorDailyBatchOutcome outcome=terminalSameOwner?
                EmulatorDailyBatchOutcome.reviewStoppedProjection(files,held,key,cp,reviewAt,EmulatorAndroidDailyStore::syncDirectory):
                EmulatorDailyBatchOutcome.readExact(files,held,key,false,EmulatorAndroidDailyStore::syncDirectory);
            if(outcome==null||!outcome.checkpoint.equals(cp.id)||!cp.account.equals(held.account)||!cp.owner.equals(GpsWorkLock.BATCH)||!cp.uncertain)
                throw new IllegalStateException("原checkpoint與批次原件不符");
            Map<String,String> historical=EmulatorDailyBatchWork.historicalPending(files,held.account);
            String terminalOriginal="",terminalRows="",terminalSha="";
            if(terminalSameOwner){
                String id="";for(String row:outcome.rows){String[] f=row.split("\t",-1);if(f[2].equals("authorized-unresolved")){if(!id.isEmpty())throw new IllegalStateException("只接受單一原授權未決");id=f[0];}}
                EmulatorDailyBatchReceipt.Admission admission=EmulatorDailyBatchReceipt.Admission.decode(EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-tasks").resolve(EmulatorDailyBatchWork.taskLeaf(key,id)+".tsv"),4096));
                java.util.List<java.nio.file.Path> originals=new ArrayList<>();
                originals.add(files.resolve("checkpoint-batch.properties"));
                for(String dir:Arrays.asList("daily-batch-inventory","daily-batch-selections","daily-batch-links"))originals.add(files.resolve(dir).resolve(EmulatorDailyRunner.digest(key)+".tsv"));
                originals.add(files.resolve("daily-batch-tasks").resolve(EmulatorDailyBatchWork.taskLeaf(key,id)+".tsv"));
                for(java.nio.file.Path source:originals){String sha=EmulatorDailyRunner.digest(EmulatorDailyBatchOutcome.readPrivate(source,262144));proof+="p='"+source+"'; test -f $p; test ! -L $p; uid=$(stat -c %u '"+files+"'); test \"$(stat -c %u:%g:%a:%h $p)\" = $uid:$uid:600:1; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+sha+"; ";}
                String hp=FILES+"dispatch_history.tsv";
                String checked="p='"+hp+"'; test -f $p; test ! -L $p; uid=$(stat -c %u "+FILES+"); test \"$(stat -c %u:%g:%a:%h $p)\" = $uid:$uid:644:1; test \"$(stat -c %s $p)\" -le "+STOPPED_BATCH_MAX_HISTORY+"; ";
                // Preserve the complete append-only producer bytes, not a tail
                // or an inferred missing claim. Root stdout remains bounded.
                retirementStage(c,"bounded-original-terminal-history");
                String[] metadata=retirementRoot(c,proof+checked+"stat -c %s "+hp+"; sha256sum "+hp).trim().split("\\s+");
                if(metadata.length!=3||!metadata[1].matches("[a-f0-9]{64}")||!metadata[2].equals(hp))throw new java.io.IOException("原歷史身分不符");
                long size=Long.parseLong(metadata[0]);if(size<1||size>STOPPED_BATCH_MAX_HISTORY)throw new java.io.IOException("原歷史超限");terminalSha=metadata[1];
                String stable=checked+"test \"$(sha256sum "+hp+" | cut -d' ' -f1)\" = "+terminalSha+"; ";
                StringBuilder complete=new StringBuilder();
                for(int offset=0;offset<size;offset+=65536){
                    String chunk=proof+stable+"dd if="+hp+" bs=65536 skip="+(offset/65536)+" count=1 2>/dev/null; "+stable;
                    BoundedCommand.Result read=BoundedCommand.root(chunk,15,131072);
                    retirementReadResult(c,read,chunk);complete.append(read.text);
                }
                terminalOriginal=complete.toString();if(terminalOriginal.getBytes(StandardCharsets.UTF_8).length!=size||!terminalSha.equals(EmulatorDailyRunner.digest(terminalOriginal))){c.getSharedPreferences("batch_retirement_diagnostic",0).edit().putString("root-result","history-bytes="+terminalOriginal.getBytes(StandardCharsets.UTF_8).length+",expected="+size).commit();throw new java.io.IOException("原歷史長度／SHA不符");}
                terminalRows=stoppedBatchTerminalRows(terminalOriginal,admission.id,admission.kind,admission.at,System.currentTimeMillis());
                String exact=checked+"test \"$(sha256sum "+hp+" | cut -d' ' -f1)\" = "+terminalSha+"; ";
                proof+=exact;retirementRoot(c,proof+"echo FIRST_TERMINAL_QUIET");Thread.sleep(31000);live=runtime.game();retirementRoot(c,stoppedBatchProof(s,live)+dead+exact+"echo SECOND_TERMINAL_QUIET");
            }
            retirementStage(c,"preserve-originals");java.nio.file.Path savedRoot=canonicalSavedRoot(c),archive=savedRoot.resolve("batch-unconfirmed-"+intent);
            if(!java.nio.file.Files.exists(archive,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
                java.nio.file.Files.createDirectory(archive);java.nio.file.Files.setPosixFilePermissions(archive,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));EmulatorAndroidDailyStore.syncDirectory(s.root);
            }
            if(!java.nio.file.Files.isDirectory(archive,java.nio.file.LinkOption.NOFOLLOW_LINKS)||java.nio.file.Files.isSymbolicLink(archive))throw new IllegalStateException("封存目錄不符");
            EmulatorDeadPlantingRecovery.writeExact(archive.resolve("config.tsv"),s.wire);
            EmulatorDeadPlantingRecovery.writeExact(archive.resolve("stop.tsv"),EmulatorDailyBatchOutcome.readPrivate(savedRoot.resolve(s.run+".stop"),256));
            java.nio.file.Path originalRun=archive.resolve("original-run.xml");
            if(!java.nio.file.Files.exists(originalRun,java.nio.file.LinkOption.NOFOLLOW_LINKS))EmulatorDeadPlantingRecovery.writeExact(originalRun,
                EmulatorDailyBatchOutcome.readPrivate(files.getParent().resolve("shared_prefs/saved_daily_run_"+s.run+".xml"),262144));
            String originalRunSha=EmulatorDailyRunner.digest(EmulatorDailyBatchOutcome.readPrivate(originalRun,262144));
            EmulatorDeadPlantingRecovery.writeExact(archive.resolve("request.tsv"),(terminalSameOwner?"stopped-terminal-batch-v1":"dead-batch-v1")+"\t"+s.run+"\t"+intent+"\t"+EmulatorDailyRunner.base64(key)+"\t"+encoded+"\t"+cp.id+"\t"+cp.properties().getProperty("checksum")+"\t"+originalRunSha+"\tUNKNOWN\tend\n");
            EmulatorDeadPlantingRecovery.writeExact(archive.resolve("checkpoint.properties"),EmulatorDailyBatchOutcome.readPrivate(files.resolve("checkpoint-batch.properties"),262144));
            String originalOutcome;
            if(terminalSameOwner){
                if(java.nio.file.Files.exists(files.resolve("daily-batch-outcomes").resolve(EmulatorDailyRunner.digest(key)+".tsv"),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("已有摘要，請沿原入口核對");
                originalOutcome=outcome.encode();
                EmulatorDeadPlantingRecovery.writeExact(archive.resolve("unconfirmed-inventory.tsv"),originalOutcome);
                preserveStoppedHistory(archive.resolve("original-dispatch-history.tsv"),terminalOriginal);
                EmulatorDeadPlantingRecovery.writeExact(archive.resolve("completed-task-unknown-result.tsv"),terminalRows);
                String leaf=EmulatorDailyRunner.digest(key)+".tsv";
                for(String dir:Arrays.asList("daily-batch-inventory","daily-batch-selections","daily-batch-links"))
                    EmulatorDeadPlantingRecovery.writeExact(archive.resolve(dir+".tsv"),EmulatorDailyBatchOutcome.readPrivate(files.resolve(dir).resolve(leaf),262144));
            }else{
                originalOutcome=EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-outcomes").resolve(EmulatorDailyRunner.digest(key)+".tsv"),262144);
                EmulatorDeadPlantingRecovery.writeExact(archive.resolve("outcome.tsv"),originalOutcome);
            }
            TreeMap<String,String> exclusions=new TreeMap<>();int currentUnresolved=0;
            try(java.nio.file.DirectoryStream<java.nio.file.Path> entries=java.nio.file.Files.newDirectoryStream(files.resolve("daily-batch-tasks"))){
                int count=0;for(java.nio.file.Path p:entries){
                    if(++count>8192)throw new IllegalStateException("原批次歷史超限");
                    String raw=EmulatorDailyBatchOutcome.readPrivate(p,4096);EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(raw);
                    if(!p.getFileName().toString().equals(EmulatorDailyBatchWork.taskLeaf(a.key,a.id)+".tsv"))throw new IllegalStateException("原授權檔名不符");
                    if(!a.binding.account.equals(held.account)||!historical.containsKey(a.id))continue;
                    if(a.key.equals(key)&&a.binding.same(held)&&historical.get(a.id).equals("prior-admission-unresolved"))currentUnresolved++;
                    String leaf="task-unconfirmed-"+EmulatorDailyRunner.digest(held.account+"\t"+a.id)+".tsv";
                    String reason=historical.get(a.id).equals("prior-collected")?"COLLECTED_NO_REPLAY":"UNKNOWN";
                    String row="PCCEXCLUDE/1\t"+held.account+"\t"+EmulatorDailyRunner.digest(a.id)+"\t"+EmulatorDailyRunner.digest(raw)+"\t"+reason+"\tend\n";
                    if(exclusions.containsKey(leaf)&&!exclusions.get(leaf).equals(row))throw new IllegalStateException("同任務有衝突授權；先保留原件");
                    exclusions.put(leaf,row);EmulatorDeadPlantingRecovery.writeExact(archive.resolve(p.getFileName()),raw);
                }
            }
            if(currentUnresolved!=outcome.unresolved||currentUnresolved<=0)throw new IllegalStateException("原未決授權數量不符");
            StringBuilder manifest=new StringBuilder();
            for(Map.Entry<String,String> e:exclusions.entrySet()){
                EmulatorDeadPlantingRecovery.writeExact(archive.resolve(e.getKey()),e.getValue());manifest.append(e.getKey()).append('\t').append(EmulatorDailyRunner.digest(e.getValue())).append('\n');
            }
            EmulatorDeadPlantingRecovery.writeExact(archive.resolve("exclusions.tsv"),manifest.toString());
            // Provision every denial before the checkpoint/journal is retired.
            // Existing different bytes, UID, mode or links are never replaced.
            retirementStage(c,"persist-target-denials");for(Map.Entry<String,String> e:exclusions.entrySet()){
                String dest=FILES+e.getKey(),sha=EmulatorDailyRunner.digest(e.getValue()),b64=Base64.getEncoder().encodeToString(e.getValue().getBytes(StandardCharsets.UTF_8));
                String temporary=dest+".prepare-"+UUID.randomUUID();
                retireRoot(c,terminalSameOwner,proof+"umask 077; p='"+dest+"'; test ! -L $p; uid=$(stat -c %u "+FILES+"); if test ! -e $p; then t='"+temporary+"'; (set -C; printf %s '"+b64+"' | base64 -d > $t); chown $uid:$uid $t; chmod 600 $t; test \"$(sha256sum $t | cut -d' ' -f1)\" = "+sha+"; sync; mv -n $t $p; restorecon -F $p; fi; "+
                    "test -f $p; test \"$(stat -c %u:%g:%a:%h $p)\" = $uid:$uid:600:1; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+sha+"; sync;");
            }
            retireRoot(c,terminalSameOwner,proof+"echo EXCLUSIONS_VERIFIED");
            retirementStage(c,"archive-uncertain-checkpoint");if(!CheckpointStore.archivedReviewed(c,cp))CheckpointStore.archiveUncertain(c,cp);
            synchronized(EmulatorSavedDailyStore.class){synchronized(s.journal){
                if(!s.current()||s.enabled()||!intent.equals(s.get("batch.pending",""))||!key.equals(s.get("batch.key",""))||!encoded.equals(s.get("batch.binding","")))throw new IllegalStateException("原批次已改變");
                retirementStage(c,"atomic-unknown-journal");retireRoot(c,terminalSameOwner,proof+"echo ORIGINAL_RECHECKED");batchRetirementStorageFailed=true;
                if(!cp.properties().getProperty("checksum").equals(CheckpointStore.read(c,GpsWorkLock.BATCH).properties().getProperty("checksum")))throw new IllegalStateException("原checkpoint已改變");
                String result="UNKNOWN_PRESERVED:"+EmulatorDailyRunner.digest(originalOutcome);
                if(!s.journal.edit().putString("result."+intent,result).putString("batch.unconfirmed-intent",intent)
                    .putString("batch.unconfirmed-exclusions",EmulatorDailyRunner.digest(manifest.toString())).remove("batch.pending").commit())throw new IllegalStateException("批次UNKNOWN保存未確認");
                if(!result.equals(s.get("result."+intent,""))||!s.get("batch.pending","").isEmpty())throw new IllegalStateException("批次UNKNOWN回讀不符");
                batchRetirementStorageFailed=false;
            }}
            retirementStage(c,"completed-unknown-preserved");return "舊批次已保留UNKNOWN，未決任務已持久排除重送。等待每日收尾完成，再使用既有批次診斷交還工作權。";
        }
    }
    private static String stoppedBatchProof(EmulatorSavedDailyStore s,Identity live)throws Exception{
        return quietWorkProof(s,live,false);
    }
    private static String quietWorkProof(EmulatorSavedDailyStore s,Identity live,boolean gpsRetirement)throws Exception{
        String filesDir=FILES.substring(0,FILES.length()-1);
        String base="set -eu; "+s.guard(false)+live.sessionGuard()+"test ! -e "+FILES+"emulator-command; test ! -L "+FILES+"emulator-command; test -d "+filesDir+"; test ! -L "+filesDir+"; "+
            "for n in dispatch return planter planting flower-farm; do p=/data/local/tmp/pikmin-$n-mode.txt; test ! -L $p; test \"$(cat $p)\" = off; done; "+
            "test -f /data/local/tmp/pikmin-feeding-config.tsv; test ! -L /data/local/tmp/pikmin-feeding-config.tsv; awk -F '\\t' 'NR==1 && $1==\"v3\" && $3==0 && $7==0 && $9==0 {ok=1} END {exit !ok}' /data/local/tmp/pikmin-feeding-config.tsv; "+
            "for pcc_guard_file in "+FILES+"emulator_planting_observation.tsv "+FILES+"emulator_independent_planting_transaction.tsv.safety; do test -f $pcc_guard_file; test ! -L $pcc_guard_file; test \"$(stat -c %s $pcc_guard_file)\" -le 4096; done; pcc_ob=$(cat "+FILES+"emulator_planting_observation.tsv); pcc_safety=$(cat "+FILES+"emulator_independent_planting_transaction.tsv.safety); now=$(date +%s%3N); printf '%s\\n' \"$pcc_ob\" | awk -F '\\t' -v p="+live.pid+" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}'; "+
            "printf '%s\\n' \"$pcc_safety\" | awk -F '\\t' -v p="+live.pid+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && ($6==\"idle\" || $6==\""+(gpsRetirement?"stopped-confirmed":"review")+"\") && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}'; "+live.guard()+
            "for p in "+FILES+"feeding_pending.tsv "+FILES+"planter_pending.tsv; do test ! -e $p; test ! -L $p; done; ";
        return base;
    }
    /** A fully preserved UNKNOWN can release maintenance, never become an outcome. */
    static boolean stoppedBatchUnknownReviewed(Context c,EmulatorSavedDailyStore prior)throws Exception{
        String intent=prior.get("batch.unconfirmed-intent","");if(intent.isEmpty())return false;
        if(!prior.get("batch.pending","").isEmpty()||!prior.get("batch-close.pending","").isEmpty())throw new IllegalStateException("批次仍有未決工作");
        java.nio.file.Path archive=canonicalSavedRoot(c).resolve("batch-unconfirmed-"+intent);
        String original=EmulatorDailyBatchOutcome.readPrivate(archive.resolve("unconfirmed-inventory.tsv"),262144);
        if(!prior.get("result."+intent,"").equals("UNKNOWN_PRESERVED:"+EmulatorDailyRunner.digest(original)))throw new IllegalStateException("UNKNOWN原件雜湊不符");
        WorkCheckpoint cp=CheckpointStore.read(c,GpsWorkLock.BATCH);
        if(!cp.uncertain||!cp.done.isEmpty()||!CheckpointStore.archivedReviewed(c,cp))throw new IllegalStateException("原未確認checkpoint尚未精確封存");
        String key=prior.get("batch.key","");EmulatorDailyRunner.Binding held=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(prior.get("batch.binding","")));
        String[] header=original.split("\n",-1)[0].split("\t",-1);
        if(header.length!=9)throw new IllegalStateException("UNKNOWN原件格式不符");
        EmulatorDailyBatchOutcome expected=EmulatorDailyBatchOutcome.reviewStoppedProjection(EmulatorAndroidDailyStore.files(c),held,key,cp,Long.parseLong(header[5]),EmulatorAndroidDailyStore::syncDirectory);
        if(!expected.encode().equals(original))throw new IllegalStateException("UNKNOWN與原授權原件不符");
        String maintenance=prior.get("plant.archive-maintenance","");
        if(maintenance.matches("[a-f0-9]{64}")){
            java.nio.file.Path request=canonicalSavedRoot(c).resolve("plant-unknown-"+prior.run).resolve("request.tsv");
            String wire=EmulatorDailyBatchOutcome.readPrivate(request,4096);
            if(!maintenance.equals(EmulatorDailyRunner.digest(wire)))throw new IllegalStateException("原種花封存request不符");
            String remote=FILES+"plant-unknown-"+prior.run+"/retired.tsv";
            String quiet="set -eu; test \"$(stat -c %u:%a '"+FILES+"plant-unknown-"+prior.run+"')\" = 0:700; test -z \"$(pidof "+GAME+" || true)\"; p='"+remote+"'; test -f $p; test ! -L $p; test \"$(stat -c %u:%a:%h $p)\" = 0:644:1; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+maintenance+"; ";
            boolean retired=false;try{retired=root(quiet+"echo RETIRED_GAME_ABSENT").trim().equals("RETIRED_GAME_ABSENT");}catch(java.io.IOException notYetRetired){}
            if(retired){requireBatchDenials(c,prior,quiet);return true;}
        }
        requireBatchRetirementReady(c,prior);return true;
    }
    static void requireBatchRetirementReady(Context c,EmulatorSavedDailyStore prior)throws Exception{
        String intent=prior.get("batch.unconfirmed-intent","");if(intent.isEmpty())return;
        if(!EmulatorDeadPlantingRecovery.uuid(intent)||CoreUpdater.pair(c).code<900157||
           !prior.get("result."+intent,"").startsWith("UNKNOWN_PRESERVED:"))throw new IllegalStateException("批次UNKNOWN保全不完整");
        Identity i=new EmulatorSavedDailyRuntime(c,prior).game();
        EmulatorDailyRunner.Binding old=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(prior.get("batch.binding","")));
        if(!old.account.equals(i.account))throw new IllegalStateException("原帳號不符");
        requireBatchDenials(c,prior,"set -eu; "+i.guard());
    }
    private static void requireBatchDenials(Context c,EmulatorSavedDailyStore prior,String targetGuard)throws Exception{
        java.nio.file.Path archive=canonicalSavedRoot(c).resolve("batch-unconfirmed-"+prior.get("batch.unconfirmed-intent","")).normalize();
        String manifest=EmulatorDailyBatchOutcome.readPrivate(archive.resolve("exclusions.tsv"),1048576);
        if(!EmulatorDailyRunner.digest(manifest).equals(prior.get("batch.unconfirmed-exclusions","")))throw new IllegalStateException("未決排除清單不符");
        int count=0;
        for(String row:manifest.split("\n")){
            if(++count>8192)throw new IllegalStateException("排除清單超限");String[] f=row.split("\t",-1);
            if(f.length!=2||!f[0].matches("task-unconfirmed-[a-f0-9]{64}\\.tsv")||!f[1].matches("[a-f0-9]{64}"))throw new IllegalStateException("排除清單格式不符");
            root(targetGuard+"p='"+FILES+f[0]+"'; test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:600; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+f[1]+";");
        }
        if(count==0)throw new IllegalStateException("缺少未決排除證據");
    }
    private static int identityDiagnostics;
    static String root(String command)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,5,131072);
        // Bounded identity-only diagnostics; never log effect commands or coordinates.
        if(identityDiagnostics<6&&(command.equals("cat /proc/sys/kernel/random/boot_id")||command.equals("pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true")||command.equals(ProbeSnapshot.command("cat /proc/sys/kernel/random/boot_id","pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true")))){
            identityDiagnostics++;android.util.Log.w("PccSavedDaily","identity-read "+(command.startsWith("cat ")?"boot":"provider")+" exit="+(r==null?"none":r.exitCode)+" text="+(r==null?"":r.text.substring(0,Math.min(240,r.text.length())).replaceAll("[^A-Za-z0-9_./: =-]"," ")));
        }
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0)throw new java.io.IOException("Saved daily root unconfirmed: "+(r==null?"no-result":r.failureSummary())+" ["+(command.contains("/proc/sys/kernel/random/boot_id")?"provider-identity":command.startsWith("awk ")?"provider-startticks":command.contains("config.tsv")?"saved-run-guard":"other-read")+"]");return r.text;
    }
    static int minute(long now){ZonedDateTime t=Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Taipei"));return t.getHour()*60+t.getMinute();}
    private boolean daytime(){String raw=store.get("rotation.plan","");return !raw.isEmpty()&&EmulatorSavedRouteRotation.decode(raw).daytime;}
    boolean active()throws Exception{int m=minute(System.currentTimeMillis());return !closed&&store.enabled()&&(daytime()?rotationSlot()!=null:!(m>=240&&m<360));}
    boolean plantDrainPending()throws Exception{return !closed&&store.current()&&!plantClosed&&!store.get("plant.pending","").isEmpty()&&!store.get("plant.binding","").isEmpty();}
    static boolean eveningClosing(boolean daytime,int minute){return daytime&&(minute>=1380&&minute<1440||minute>=0&&minute<60);}
    boolean closing(){int m=minute(System.currentTimeMillis());return !daytime()&&m>=210&&m<240||eveningClosing(daytime(),m)||developmentOpen();}
    static boolean preflightDue(int minute){return minute>=200&&minute<210;}
    static boolean recoveryLaunchAllowed(boolean enabled,boolean activity,boolean closing,boolean pending,boolean quiet,boolean maintenance,boolean watcher,long now,long last,int attempts){
        return enabled&&activity&&!closing&&!pending&&quiet&&!maintenance&&!watcher&&now>=last&&now-last>=300000&&attempts<2;
    }
    /** Read-only proof first. A bad sample never restarts a live game. No old
     * lease/task is cleared, adopted or replayed by this worker. HC is independent. */
    void healthTick()throws Exception{
        if(!store.enabled()){recoveryText="使用者已停止；不自動復活";return;}
        if(!active()){recoveryText=eveningClosing(daytime(),minute(System.currentTimeMillis()))?"23:00–翌日01:00三類收工；不自動重啟遊戲":daytime()?"23:00–07:00休息；不啟動復原":"04:00–06:00休息；不啟動復原";return;}
        if(SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app)||WorkStatus.stoppingAll){recoveryText="維護／停止工作中；不介入";return;}
        try{
            Identity observed=game();unhealthySamples=0;
            healthySamples=observed.key().equals(healthyBinding)?healthySamples+1:1;healthyBinding=observed.key();
            if(recoveryHold&&healthySamples>=2){store.put("recovery.hold","0");recoveryHold=false;}
            recoveryText=recoveryHold?"核心已回應；等待第二次同程序證據":"同程序核心／業務心跳新鮮；舊交易仍需各自對帳";
            return;
        }catch(Exception unavailable){healthySamples=0;healthyBinding="";if(++unhealthySamples<3){recoveryText="核心暫態待複查（"+unhealthySamples+"/3）";return;}}
        if(!recoveryHold){store.put("recovery.hold","1");recoveryHold=true;}
        recoveryText="核心持續失聯；GPS／背景工作安全暫停，步數獨立；請登入後載入核心";
        // Restrictive OFF only, and only our current run. Never overwrite a
        // batch/foreign owner's active controls or touch another native lane.
        if(DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||GpsWorkLock.anyHeld(app)&&!GpsWorkLock.heldBy(app,EmulatorSavedJoystickPort.OWNER))return;
        root("set -e; "+store.guard(false)+atomic("/data/local/tmp/pikmin-planting-mode.txt","off\n"));
        boolean pending=!store.get("gps.pending","").isEmpty()||!store.get("plant.pending","").isEmpty()||!store.get("batch.pending","").isEmpty()||!store.get("batch-close.pending","").isEmpty();
        long now=System.currentTimeMillis(),window=Long.parseLong(store.get("recovery.window","0")),last=Long.parseLong(store.get("recovery.launch-at","0"));
        int attempts=Integer.parseInt(store.get("recovery.attempts","0"));
        if(now>=window&&now-window>=1800000){window=now;attempts=0;store.put("recovery.window",Long.toString(window));store.put("recovery.attempts","0");}
        boolean maintenance=SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app)||WorkStatus.stoppingAll;
        if(!recoveryLaunchAllowed(store.enabled(),active(),closing(),pending,!gpsOwned&&helpersOff,maintenance,false,now,last,attempts))return;
        // Explicit double absence, not shell failure or a stale business file.
        String absence="command -v pidof >/dev/null; p=$(pidof "+GAME+" 2>/dev/null); r=$?; test $r -eq 1; test -z \"$p\"; ";
        root("set -e; "+store.guard(true)+absence+absence);
        Intent launch=app.getPackageManager().getLaunchIntentForPackage(GAME);
        if(launch==null||launch.getComponent()==null||!GAME.equals(launch.getComponent().getPackageName()))return;
        // Durable bounded attempt BEFORE a possibly uncertain UI launch.
        if(!store.journal.edit().putString("recovery.launch-at",Long.toString(now)).putString("recovery.attempts",Integer.toString(attempts+1)).commit())throw new IllegalStateException("Recovery limit unconfirmed");
        if(!store.enabled()||!active()||closing())return;
        app.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        recoveryText="已要求開啟消失的遊戲（最多2次/30分鐘）；仍需正常登入／180秒核心載入";
    }
    void closingStatusTick()throws Exception{
        if(daytime()){
            long now=System.currentTimeMillis();int m=minute(now);
            if(eveningClosing(true,m)&&store.enabled()){
                store.put("closing.e23.observed.day",EmulatorDailyPlan.eveningWorkDay(now).toString());
                if(!batchText.equals(store.get("closing.e23.wait","")))store.put("closing.e23.wait",batchText);
            }
            if(store.enabled())recordEndedEveningClosing(now);
            return;
        }
        long now=System.currentTimeMillis();int m=minute(now);
        String day=LocalDate.now(EmulatorDailyPlan.ZONE).toString();
        if(preflightDue(m)&&store.enabled()){
            String check;
            try{game();check="核心新鮮";}catch(Exception e){check="核心待確認";}
            check+="；"+(store.get("plant.pending","").isEmpty()?"種花無未決":"種花有本輪／未決，03:30需結算")+
                "；"+(store.get("batch.pending","").isEmpty()?"批次無未決":"批次原件保留")+"；路線將於03:30暫停";
            store.put("closing.preflight.day",day);store.put("closing.preflight",check);
        }
        if(m>=210&&m<240){
            if(!batchText.equals(store.get("closing.wait",""))){store.put("closing.wait",batchText);store.put("closing.wait-at",Long.toString(now));}
            store.put("closing.observed.day",day);
        }
        if(store.enabled())recordEndedClosing(now);
    }
    static LocalDate endedClosingDay(long now){
        ZonedDateTime t=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        return t.toLocalTime().isBefore(LocalTime.of(4,0))?t.toLocalDate().minusDays(1):t.toLocalDate();
    }
    static String closingResult(boolean pending,boolean observed,String outcome){
        if(pending)return "PENDING";
        if("COMPLETE_ALL_CLAIMS".equals(outcome))return "COMPLETE";
        if("COMPLETE".equals(outcome))return "PARTIAL";
        return observed?"UNCONFIRMED":"MISSED";
    }
    static boolean coveredClosing(long since,long start,boolean observed){return observed||since>0&&since<=start;}
    private void recordEndedEveningClosing(long now)throws Exception{
        long since=Long.parseLong(store.get("closing.e23.tracked-since","0"));
        if(since==0){store.put("closing.e23.tracked-since",Long.toString(now));since=now;}
        ZonedDateTime t=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        LocalDate ended=t.toLocalDate().minusDays(t.toLocalTime().isBefore(LocalTime.of(1,0))?2:1);
        String day=ended.toString();long start=ended.atTime(23,0).atZone(EmulatorDailyPlan.ZONE).toInstant().toEpochMilli();
        boolean observed=day.equals(store.get("closing.e23.observed.day",""));
        if(!coveredClosing(since,start,observed))return;
        String prefix="closing.e23.record."+day+".";
        String current=store.get("batch.key",""),key=store.get(prefix+"key",current),first=day+":e23h2:batch-1";
        boolean exact=!key.isEmpty()&&EmulatorDevelopmentBatch.sameRunKey(key,first);
        boolean pending=current.equals(key)&&(!store.get("batch.pending","").isEmpty()||!store.get("batch-close.pending","").isEmpty());
        String state=closingResult(pending,observed,exact?store.get("batch.outcome."+key,""):"");
        String old=store.get(prefix+"state","");
        if(!old.isEmpty()&&(!old.equals("PENDING")||!exact))return;
        String summary=state.equals("COMPLETE")?"23:00收工候選全部確認":state.equals("PARTIAL")?"23:00收工部分完成；保留剩餘待辦":
            state.equals("PENDING")?"23:00收工仍有未決；先對帳，不補送":state.equals("MISSED")?"錯過23:00收工窗口；未確認完成":
            "23:00收工未確認完成；請先診斷";
        if(!store.journal.edit().putString(prefix+"state",state).putString(prefix+"key",key).putString(prefix+"at",Long.toString(now))
                .putString("closing.summary",summary).putString("closing.summary.day",day).commit())throw new IllegalStateException("Evening closing record unconfirmed");
    }
    private void recordEndedClosing(long now)throws Exception{
        long since=Long.parseLong(store.get("run.created-at",store.get("closing.tracked-since","0")));
        if(since==0){store.put("closing.tracked-since",Long.toString(now));since=now;}
        LocalDate ended=endedClosingDay(now);String day=ended.toString();
        long start=ended.atTime(3,30).atZone(EmulatorDailyPlan.ZONE).toInstant().toEpochMilli();
        boolean observed=day.equals(store.get("closing.observed.day",""));
        if(!coveredClosing(since,start,observed))return; // no invented pre-upgrade or pre-START history
        String prefix="closing.record."+day+".";
        String currentKey=store.get("batch.key",""),key=store.get(prefix+"key",currentKey),first=ended.minusDays(1)+":n22:batch-1";
        // A manual catch-up outcome is never retroactive proof of 03:30 work.
        boolean exact=!key.isEmpty()&&EmulatorDevelopmentBatch.sameRunKey(key,first);
        boolean pending=currentKey.equals(key)&&(!store.get("batch.pending","").isEmpty()||!store.get("batch-close.pending","").isEmpty());
        String state=closingResult(pending,observed,exact?store.get("batch.outcome."+key,""):"");
        // Freeze the scheduled-window record before any later catch-up changes
        // batch.key. Only its own late outcome may subsequently resolve PENDING.
        String old=store.get(prefix+"state","");
        if(!old.isEmpty()&&(!old.equals("PENDING")||!exact))return;
        String summary=state.equals("COMPLETE")?"收工候選全部確認":state.equals("PARTIAL")?"收工部分完成；剩餘候選可另行補跑":
            state.equals("PENDING")?"收工／相關批次仍有未決；先對帳，不補送":state.equals("MISSED")?"錯過03:30收工窗口；尚無完成證據，可於活動時間補跑":
            "收工曾進入窗口但未確認完成；請先診斷再補跑";
        if(!store.journal.edit().putString(prefix+"state",state).putString(prefix+"key",key).putString(prefix+"at",Long.toString(now))
                .putString("closing.summary",summary).putString("closing.summary.day",day).commit())throw new IllegalStateException("Closing record commit unconfirmed");
    }
    boolean isClosed(){return closed;}
    void resetClosure(){gpsClosed=false;plantClosed=false;helpersOff=false;stepsClosed=false;batchClosed=false;}
    boolean drainReady()throws Exception{return !gpsRetirementStorageFailed&&!closed&&store.current()&&store.needsDrain()&&gpsClosed&&plantClosed&&helpersOff&&stepsClosed&&batchClosed&&!store.hasPending();}
    static boolean gpsWithoutOwnerClosed(String phase,boolean pending){return !pending&&(phase.isEmpty()||phase.equals("READY")||phase.equals("PAUSED")||phase.equals("RETIRED"));}
    static boolean plantMetadataEmpty(String pending,String token,String binding){return pending.isEmpty()&&token.isEmpty()&&binding.isEmpty();}
    static boolean plantStopMatches(String[] f,String token,String binding,String current,int pid,long epoch,String account){return f!=null&&f.length==10&&f[0].equals("v3")&&!token.isEmpty()&&!binding.isEmpty()&&binding.equals(current)&&f[1].equals(Integer.toString(pid))&&f[2].equals(Long.toString(epoch))&&f[3].equals(account)&&f[4].equals(token)&&f[5].equals("stopped-confirmed")&&f[7].equals("1");}

    private EmulatorDevelopmentBatch.Window development(){
        String raw=store.get("development.window","");if(raw.isEmpty())return null;
        String[] f=raw.split("\\|",-1);if(f.length!=3)throw new IllegalStateException("Development framing");
        return new EmulatorDevelopmentBatch.Window(f[0],Long.parseLong(f[1]),Long.parseLong(f[2]));
    }
    private boolean developmentOpen(){EmulatorDevelopmentBatch.Window w=development();return w!=null&&w.open(System.currentTimeMillis());}
    long closingDeadline(){
        EmulatorDevelopmentBatch.Window w=development();long now=System.currentTimeMillis();
        if(w!=null&&w.open(now))return w.deadline;
        if(eveningClosing(daytime(),minute(now)))return EmulatorDailyPlan.eveningWorkDay(now).plusDays(1).atTime(1,0).atZone(EmulatorDailyPlan.ZONE).toInstant().toEpochMilli();
        return LocalDate.now(EmulatorDailyPlan.ZONE).atTime(4,0).atZone(EmulatorDailyPlan.ZONE).toInstant().toEpochMilli();
    }
    static long remainingMinutes(long now,long deadline){
        if(now<0||deadline<=now)return 0;
        long gap=deadline-now;return gap/60000+(gap%60000==0?0:1);
    }
    static void requestClosingTest(Context c)throws Exception{
        synchronized(GpsWorkLock.class){
        EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);
        long now=System.currentTimeMillis();
        boolean pending=s==null||!s.get("gps.pending","").isEmpty()||!s.get("gps-bootstrap.pending","").isEmpty()||!s.get("batch.pending","").isEmpty()||!s.get("batch-close.pending","").isEmpty();
        boolean maintenance=WorkStatus.stoppingAll||SetupDeployment.pending(c)||CoreUpdater.pending(c)||UpdateInstaller.pending(c)||CoreInstallWizard.preparing(c);
        if(!catchupAllowed(s!=null&&s.enabled()&&EmulatorSavedDailyService.isRunning(),minute(now),pending,maintenance,DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||GpsWorkLock.anyHeld(c)&&!GpsWorkLock.heldBy(c,EmulatorSavedJoystickPort.OWNER)))
            throw new IllegalStateException("Catch-up requires active schedule, clear GPS/batch intents and no maintenance");
        String retained=s.get("batch.key","");
        if(!retained.isEmpty()&&!Arrays.asList("COMPLETE","COMPLETE_ALL_CLAIMS").contains(s.get("batch.outcome."+retained,"")))throw new IllegalStateException("Original batch outcome unresolved; no replacement");
        String old=s.get("development.window","");
        if(!old.isEmpty()&&now<Long.parseLong(old.split("\\|",-1)[2]))throw new IllegalStateException("Original bounded window retained");
        EmulatorDevelopmentBatch.Window w=EmulatorDevelopmentBatch.create(LocalDate.now(EmulatorDailyPlan.ZONE).toString(),UUID.randomUUID().toString().replace("-",""),now);
        // Keep the already-tested bounded namespace/receipt contract. A new
        // explicit request never extends or replays its predecessor window.
        if(!s.journal.edit().putString("development.window",w.key+"|"+w.created+"|"+w.deadline)
                .putString("catchup.request."+w.key,Long.toString(now)).commit())throw new IllegalStateException("Catch-up request unconfirmed");
        }
    }
    static boolean catchupAllowed(boolean enabled,int minute,boolean pending,boolean maintenance,boolean competing){
        return enabled&&minute>=0&&minute<1440&&(minute>=360||minute<195)&&!pending&&!maintenance&&!competing;
    }
    static final class Identity {
        final int pid;final long epoch;final String account,boot,ticks;final CorePair pair;
        Identity(int pid,long epoch,String account,String boot,String ticks,CorePair pair){this.pid=pid;this.epoch=epoch;this.account=account;this.boot=boot;this.ticks=ticks;this.pair=pair;}
        String key(){return boot+":"+pid+":"+ticks+":"+epoch+":"+account;}
        String sessionGuard(){
            // Snapshot first, then obtain the validating wall clock. A valid
            // producer publish between date and read must not look future-dated.
            return guard()+"for pcc_guard_file in "+FILES+"automation_session.tsv "+FILES+"core_capabilities.tsv; do test -f $pcc_guard_file; test ! -L $pcc_guard_file; test \"$(stat -c %s $pcc_guard_file)\" -le 4096; done; pcc_session=$(cat "+FILES+"automation_session.tsv); pcc_caps=$(cat "+FILES+"core_capabilities.tsv); now=$(date +%s%3N); "+
                "printf '%s\\n' \"$pcc_session\" | awk -F '\\t' -v now=$now -v p="+pid+" -v a="+account+" 'NR==1 && NF==7 && $1==\"v1\" && $3==p && $4==a && $5==\"ready\" && $7==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}'; "+
                "printf '%s\\n' \"$pcc_caps\" | awk -F '\\t' -v now=$now -v p="+pid+" -v e="+epoch+" -v v="+pair.version+" -v c="+pair.code+" 'NR==1 && NF==23 && $1==\"v1\" && $3==p && $4==v && $5==c && $6==\"157.0\" && $15==e && $16==\"ready\" && $23==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}'; "+guard();
        }
        String guard(){return "test \"$(pidof "+GAME+")\" = "+pid+"; test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+boot+"; test \"$(awk '{print $22}' /proc/"+pid+"/stat)\" = "+ticks+"; ";}
    }
    Identity game()throws Exception{
        CorePair pair=CoreUpdater.pair(app);CoreUpdater.requireReady(app);
        String[] f=ProbeSnapshot.sections(root(ProbeSnapshot.command("pidof "+GAME,"cat /proc/sys/kernel/random/boot_id","cat "+FILES+"core_capabilities.tsv","cat "+FILES+"automation_session.tsv")),4);
        int pid=Integer.parseInt(f[0].trim());String boot=f[1].trim();long now=System.currentTimeMillis();
        CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(f[2]),Integer.toString(pid),pair.version,pair.code,now);
        String account=SessionIdentity.parse(f[3],Integer.toString(pid),Integer.toString(pid),now);
        if(!caps.ok()||!caps.domainReady()||!caps.identityReady()||account.isEmpty()||!boot.matches("[a-f0-9-]{36}"))throw new IllegalStateException("Current game session not ready");
        String ticks=root("test \"$(pidof "+GAME+")\" = "+pid+"; awk '{print $22}' /proc/"+pid+"/stat").trim();
        if(!ticks.matches("[1-9][0-9]*"))throw new IllegalStateException("Current game start identity unknown");
        Identity i=new Identity(pid,caps.sessionEpoch,account,boot,ticks,pair);lastGame=i;return i;
    }
    static boolean ownedRouteNeedsObservation(String phase,String provider){
        return ("RUNNING".equals(phase)||"PAUSED".equals(phase))&&provider!=null&&!provider.isEmpty();
    }
    static boolean successorGpsHandoffAllowed(boolean current,boolean drained,boolean predecessor,boolean sameProvider,boolean paused,boolean loop,boolean speed,boolean maintenance,boolean competing){
        return current&&drained&&predecessor&&sameProvider&&paused&&loop&&speed&&!maintenance&&!competing;
    }
    static boolean successorGpsColdHandoffAllowed(boolean current,boolean drained,boolean predecessor,boolean sameProvider,boolean differentBoot,boolean providerAbsent,boolean maintenance,boolean competing){
        return current&&drained&&predecessor&&sameProvider&&differentBoot&&providerAbsent&&!maintenance&&!competing;
    }
    static boolean coldStoppedGpsAllowed(boolean current,boolean stopped,boolean drained,boolean owned,boolean differentBoot,boolean absent,boolean maintenance,boolean competing){
        return current&&stopped&&drained&&owned&&differentBoot&&absent&&!maintenance&&!competing;
    }
    private boolean gpsHandoffDrained(){
        for(String lane:new String[]{"gps","gps-bootstrap","batch","batch-close"})if(!store.get(lane+".pending","").isEmpty())return false;
        return true; // The successor's independent planting/HC work is not a GPS lease.
    }
    private boolean coldGpsOwnerMatches(SharedPreferences owner,EmulatorSavedDailyStore prior,String provider)throws Exception{
        if(!EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner","")))return false;
        // Repeated explicit STOP/START while the provider was offline may have
        // published intermediate runs without transferring the lease. Every
        // link must independently prove the same stopped, drained provider.
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int n=0;n<8;n++){
            if(!seen.add(prior.run)||!prior.confirmedStop()||!prior.drained()||!provider.equals(prior.get("gps.provider","")))return false;
            if(prior.run.equals(owner.getString("saved_run","")))return true;
            if(prior.get("gps.explicit-handoff","").isEmpty())return false;
            prior=prior.stoppedPredecessor();
        }
        return false;
    }
    /** Transfer only local ownership, never a route command or UNKNOWN receipt.
     * The explicit new START published its predecessor before config. The one
     * atomic preference commit is also the receipt: retry sees the successor,
     * not another opportunity to act on the stopped run. */
    private void inheritStoppedGpsOwner(SharedPreferences owner)throws Exception{
        EmulatorSavedDailyStore prior=store.stoppedPredecessor();
        String provider=store.get("gps.provider","");String[] identity=provider.split(":",-1);
        if(identity.length!=3||!identity[0].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalStateException("Paused provider identity required");
        int pid=Integer.parseInt(identity[1]);long ticks=Long.parseLong(identity[2]);
        if(pid<=0||ticks<=0)throw new IllegalStateException("Invalid provider identity");
        String currentBoot=root("cat /proc/sys/kernel/random/boot_id").trim();
        if(!currentBoot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalStateException("Current boot identity required");
        String currentPid=root("pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true").trim();
        boolean predecessor=EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner",""))&&prior.run.equals(owner.getString("saved_run",""));
        boolean maintenance=WorkStatus.stoppingAll||SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app);
        boolean competing=DispatchBatchService.isRunning()||FlowerFarmService.isRunning();
        boolean coldPredecessor=!currentBoot.equals(identity[0])&&currentPid.isEmpty()&&coldGpsOwnerMatches(owner,prior,provider);
        if(successorGpsColdHandoffAllowed(store.enabled(),prior.confirmedStop()&&prior.drained()&&gpsHandoffDrained(),coldPredecessor,
                provider.equals(prior.get("gps.provider","")),!currentBoot.equals(identity[0]),currentPid.isEmpty(),maintenance,competing)){
            // A stopped predecessor from another boot cannot still own a live
            // provider. Preserve its exact identity before transferring only
            // the local lease. No old route command/result is replayed. Keep
            // provider unchanged so a crash before the lease commit is retryable.
            if(!store.journal.edit().putString("gps.retired-provider",provider).putString("gps.phase","").commit())
                throw new IllegalStateException("Cold GPS retirement unconfirmed");
            root("set -eu; test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+currentBoot+"; test -z \"$(pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true)\"; "+store.guard(true));
            if(!prior.confirmedStop()||!prior.drained()||!gpsHandoffDrained()||!coldGpsOwnerMatches(owner,prior,provider))throw new IllegalStateException("Cold GPS predecessor changed");
            if(!owner.edit().putString("saved_run",store.run).putString("saved_handoff_from",prior.run)
                    .putString("saved_handoff_owner_from",owner.getString("saved_run",""))
                    .putString("saved_handoff_provider",provider).putString("saved_handoff_boot",currentBoot)
                    .putLong("touched_ms",System.currentTimeMillis()).commit())throw new IllegalStateException("Cold GPS handoff commit unconfirmed");
            return;
        }
        String fresh=currentBoot+":"+currentPid;
        if(!fresh.equals(identity[0]+":"+identity[1]))throw new IllegalStateException("Predecessor provider changed");
        // Port checks boot/PID/start ticks/APK before and after STATUS. It is
        // allowed to observe the existing saved-city owner, not acquire it.
        try(EmulatorSavedJoystickPort observation=new EmulatorSavedJoystickPort(app,store.plan,identity[0],pid,ticks)){
            EmulatorSavedJoystickProtocol.Status status=observation.observe();
            if(!successorGpsHandoffAllowed(store.enabled(),store.get("gps.pending","").isEmpty()&&store.get("gps-bootstrap.pending","").isEmpty(),predecessor,provider.equals(prior.get("gps.provider","")),status.confirmedPaused(),status.configuredLoop(),Double.compare(status.speed,19)==0,maintenance,DispatchBatchService.isRunning()||FlowerFarmService.isRunning()))
                throw new IllegalStateException("GPS predecessor handoff not safe; original lock retained");
            if(!prior.confirmedStop()||!prior.drained())throw new IllegalStateException("Predecessor changed during observation");
            if(!owner.edit().putString("saved_run",store.run).putString("saved_handoff_from",prior.run)
                    .putString("saved_handoff_provider",provider).putLong("touched_ms",System.currentTimeMillis()).commit())
                throw new IllegalStateException("GPS handoff commit unconfirmed");
        }
    }
    void gpsTick()throws Exception{
        if(closed||!store.current())return;
        try{gpsTickOwned();}finally{if(closed){EmulatorSavedJoystickPort p=gps;if(p!=null)p.close();}}
    }
    private void gpsTickOwned()throws Exception{
        if(gpsRetirementStorageFailed)throw new IllegalStateException("GPS journal storage unconfirmed");
        boolean should=active()&&!recoveryHold&&!closing()&&!DispatchBatchService.isRunning();
        synchronized(GpsWorkLock.class){
            gpsClosed=false;
            if(closed)return;
            if(!gpsOwned&&(DispatchBatchService.isRunning()||GpsWorkLock.heldBy(app,GpsWorkLock.BATCH))){
                gpsText=store.get("gps.phase","").equals("PAUSED")&&store.get("gps.pending","").isEmpty()?
                    "原每日路線已暫停；三類批次使用定位":"批次持有定位；原路線狀態待確認";
                return; // expected handoff, not a failed attempt to reacquire GPS
            }
            if(!gpsOwned){
                SharedPreferences owner=app.getSharedPreferences("pikmin_gps_work_lock",0);String held=owner.getString("owner","");
                boolean resume=held.equals(EmulatorSavedJoystickPort.OWNER)&&owner.getString("saved_run","").equals(store.run);
                if(!resume&&held.equals(EmulatorSavedJoystickPort.OWNER)&&!store.get("gps.explicit-handoff","").isEmpty()){
                    inheritStoppedGpsOwner(owner);resume=true;
                }
                // Released lock / PAUSED journal is not fresh provider evidence.
                // Our retained route may have resumed externally while CC was
                // absent. Re-observe the exact incarnation before rest/batch;
                // never adopt an unknown route or bypass a foreign owner.
                boolean retained=ownedRouteNeedsObservation(store.get("gps.phase",""),store.get("gps.provider",""));
                if(!should&&!resume&&!retained){gpsClosed=gpsWithoutOwnerClosed(store.get("gps.phase",""),!store.get("gps.pending","").isEmpty()||!store.get("gps-bootstrap.pending","").isEmpty());gpsText=gpsClosed?"本排程GPS已收尾":"GPS未接管；未確認既有路線狀態";return;}
                if((!held.isEmpty()&&!resume)||DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||WorkStatus.stoppingAll&&!resume)throw new IllegalStateException("Existing GPS owner preserved");
                if(!owner.edit().putString("owner",EmulatorSavedJoystickPort.OWNER).putString("saved_run",store.run).putLong("touched_ms",System.currentTimeMillis()).commit())throw new IllegalStateException("GPS owner commit failed");
                gpsOwned=true;
            }
            SharedPreferences exactOwner=app.getSharedPreferences("pikmin_gps_work_lock",0);
            if(!EmulatorSavedJoystickPort.OWNER.equals(exactOwner.getString("owner",""))||!store.run.equals(exactOwner.getString("saved_run","")))throw new IllegalStateException("Original GPS owner changed");
            GpsWorkLock.touch(app,EmulatorSavedJoystickPort.OWNER);
            if(gps==null){
                String[] v=ProbeSnapshot.sections(root(ProbeSnapshot.command("cat /proc/sys/kernel/random/boot_id","pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true")),2);
                boot=v[0].trim();String pid=v[1].trim();
                if(!boot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))throw new IllegalStateException("Current boot required");
                String prior=store.get("gps.provider","");
                String bootstrap=store.get("gps-bootstrap.pending","");
                boolean gpsPending=!store.get("gps.pending","").isEmpty()||!bootstrap.isEmpty();
                if(!should&&((!prior.isEmpty()&&!prior.startsWith(boot+":"))||pid.isEmpty())){
                    if(gpsPending){gpsText="原GPS provider不在；指令結果待確認，不重送";return;}
                    store.fields("gps.retired-provider",prior,"gps.phase","RETIRED");
                    releaseOwnedGps();gpsClosed=true;gpsText="本排程GPS provider已退出";return;
                }
                if(!bootstrap.isEmpty()&&!boot.equals(store.get("gps-bootstrap.boot",""))){gpsText="原GPS啟動歸屬待確認；不接管新provider";return;}
                if(!store.get("gps.pending","").isEmpty()&&prior.isEmpty())throw new IllegalStateException("GPS pending lacks original provider");
                if(closed)return;
                if(pid.isEmpty()){
                    if(!should){finishColdStoppedGps(app.getSharedPreferences("pikmin_gps_work_lock",0),boot);return;}
                    store.activeIntent("gps-bootstrap","START_EXISTING_PROVIDER","gps-bootstrap.boot",boot);
                    root("set -e; "+store.guard(true)+"am start-foreground-service --user 0 -n "+EmulatorSavedJoystickProtocol.SERVICE);
                    store.put("gps-bootstrap.returned","yes");gpsText="等待JoyStick啟動";return;
                }
                overlayPid=Integer.parseInt(pid);overlayTicks=Long.parseLong(root("awk '{print $22}' /proc/"+overlayPid+"/stat").trim());
                String provider=boot+":"+overlayPid+":"+overlayTicks;
                if(!prior.isEmpty()&&!prior.equals(provider)){
                    if(should&&!prior.startsWith(boot+":")&&!store.get("gps.explicit-handoff","").isEmpty()&&store.get("gps-bootstrap.returned","").equals("yes")&&store.get("gps.pending","").isEmpty()){
                        // Explicit new run after a confirmed paused predecessor,
                        // old boot gone, our journalled startup created provider.
                        // Drop active ownership, not history: STATUS must be idle
                        // before a NEW approved saved-name ROUTE can be sent.
                        store.put("gps.retired-provider",prior);store.put("gps.phase","");
                    }else throw new IllegalStateException("Provider changed; original run retained");
                }
                store.put("gps.provider",provider);
                gps=new EmulatorSavedJoystickPort(app,currentRoute(),boot,overlayPid,overlayTicks);
                store.confirmed("gps-bootstrap","overlay-observed");
            }
            EmulatorSavedJoystickProtocol.Status status=gps.observe();String pending=store.get("gps.pending","");
            if(!pending.isEmpty()){
                String action=store.get("intent."+pending,"");
                if(action.startsWith("ROUTE_SWITCH|")){
                    String slot=store.get("rotation.pending-slot",""),name=store.get("rotation.pending-name","");
                    EmulatorSavedRouteRotation.Slot original=EmulatorSavedRouteRotation.decode(store.get("rotation.plan","")).retained(slot);
                    if(!action.equals("ROUTE_SWITCH|"+slot)||!action.equals(store.get("gps.returned",""))||!status.runningAt19()){
                        gpsText="換線原指令待確認；不重送";return;
                    }
                    if(!name.equals(original.city.savedName))throw new IllegalStateException("Original route target differs");
                    if(!store.journal.edit().putString("result."+pending,"paused-route-replacement-observed")
                            .remove("gps.pending").putString("gps.phase","RUNNING").putString("gps.route-name",name)
                            .putString("rotation.slot",slot).remove("rotation.pending-slot").remove("rotation.pending-name").commit())
                        throw new IllegalStateException("Rotation confirmation not durable");
                    gps.close();gps=null;gpsText="新路線已運行；等待遊戲定位穩定";return;
                }
                boolean ok=action.equals("SPEED")&&Double.compare(status.speed,19)==0||action.equals("ROUTE")&&status.runningAt19()||action.equals("ROUTE_RESUME")&&status.runningAt19()||action.equals("ROUTE_PAUSE")&&!status.active;
                if(ok){store.confirmed("gps",pending,"provider-state-observed","gps.phase",action.equals("SPEED")?"READY":status.active?"RUNNING":"PAUSED");}
                else {gpsText="GPS指令待觀察，不重送";return;}
            }
            boolean ours=store.get("gps.phase","").equals("RUNNING")||store.get("gps.phase","").equals("PAUSED");
            String settling=store.get("rotation.settling","");
            if(!settling.isEmpty()&&store.get("gps.pending","").isEmpty()){
                // Named ROUTE starts walking immediately. Keep its exact new
                // owner and pause it normally before asking the unchanged
                // native arrival gate for three stable, fresh samples.
                if(rotationArrivalNeedsPause(should,ours,true,status.active)){
                    effect(EmulatorSavedJoystickProtocol.Operation.ROUTE_PAUSE);gpsText="新路線先暫停；等待遊戲接收定位";return;
                }
                if(should&&ours){
                    if(!status.confirmedPaused()){gpsText="新路線等待原暫停確認";return;}
                    Identity identity=game();FarmLocation fix=GpsModuleGateway.farmLocation(app);
                    long sent=Long.parseLong(store.get("rotation.sent-at","0")),elapsed=Long.parseLong(store.get("rotation.sent-elapsed","0"));
                    if(settling.equals(store.get("rotation.slot",""))&&fix.valid&&fix.pid.equals(Integer.toString(identity.pid))&&
                            "ready".equals(fix.state)&&fix.samples==3&&fix.at>sent&&fix.rawAt>elapsed&&fix.provider!=null&&fix.provider.fixMs>elapsed){
                        store.put("rotation.settling","");
                    }else{gpsText="新路線已暫停；等待同程序新鮮穩定定位";return;}
                }
            }
            EmulatorSavedRouteRotation.Slot desired=rotationSlot();
            if(should&&desired!=null&&!desired.key().equals(store.get("rotation.slot",""))){
                if(EmulatorSavedRouteRotation.maySelectInitial(ours,status.active,status.paused)){
                    // First daily slot has no predecessor route to replace.
                    // Select only while the provider is idle, with no GPS intent;
                    // normal SPEED/ROUTE admission below still journals the effect.
                    if(currentRoute().city!=desired.city){store.put("gps.route-name",desired.city.savedName);gps.close();gps=null;return;}
                // A new dated slot must reselect even the same saved city.
                // Evening batch movement may have left the paused provider far
                // from that route; RESUME alone does not restore its geometry.
                }else if(ours&&status.active){effect(EmulatorSavedJoystickProtocol.Operation.ROUTE_PAUSE);gpsText="換線前暫停原路線；等待種花結算";return;}
                else if(ours&&status.confirmedPaused()){switchRoute(desired,status);return;}
                else {gpsText="換線等待原路線身分／狀態；不接管外來路線";return;}
            }
            if(!should){
                if(status.active&&ours){effect(EmulatorSavedJoystickProtocol.Operation.ROUTE_PAUSE);return;}
                if(!status.active){
                    if(!store.get("gps.pending","").isEmpty()||!store.get("gps-bootstrap.pending","").isEmpty())throw new IllegalStateException("GPS pending retained");
                    if(ours)store.fields("gps.phase","PAUSED");
                    gps.close();gps=null;releaseOwnedGps();gpsClosed=true;gpsText="GPS已暫停";
                }else gpsText="原GPS狀態待確認；未停止其它路線";
                return;
            }
            if(status.active){gpsText=ours?currentRoute().savedName()+" · 循環19km/h":"既有路線保留，未接管";return;}
            if(status.paused){if(ours)effect(EmulatorSavedJoystickProtocol.Operation.ROUTE_RESUME);else gpsText="既有暫停路線未接管";return;}
            if(Double.compare(status.speed,19)!=0){effect(EmulatorSavedJoystickProtocol.Operation.SPEED);return;}
            effect(EmulatorSavedJoystickProtocol.Operation.ROUTE);
        }
    }
    private void switchRoute(final EmulatorSavedRouteRotation.Slot desired,EmulatorSavedJoystickProtocol.Status observed)throws Exception{
        if(!EmulatorSavedRouteRotation.mayReplace(store.enabled()&&active()&&!closing()&&!recoveryHold,gpsOwned,
                observed.confirmedPaused(),observed.configuredLoop()&&Double.compare(observed.speed,19)==0,
                plantClosed,helpersOff,!store.get("plant.pending","").isEmpty()||!store.get("gps.pending","").isEmpty()||!store.get("gps-bootstrap.pending","").isEmpty()||
                !store.get("batch.pending","").isEmpty()||!store.get("batch-close.pending","").isEmpty(),
                DispatchBatchService.isRunning()||FlowerFarmService.isRunning(),WorkStatus.stoppingAll||SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app))){
            gpsText="換線等待種花結算／helpers排空或未決對帳";return;
        }
        final String action="ROUTE_SWITCH|"+desired.key();
        gps.switchPaused(new EmulatorSavedRoutePlan(desired.city),new EmulatorSavedJoystickPort.IntentJournal(){
            public void before(EmulatorSavedJoystickProtocol.Operation ignored)throws Exception{
                if(!store.get("gps.pending","").isEmpty())throw new IllegalStateException("GPS intent retained");
                String id=UUID.randomUUID().toString();
                if(!store.journal.edit().putString("gps.pending",id).putString("intent."+id,action)
                        .putString("rotation.pending-slot",desired.key()).putString("rotation.pending-name",desired.city.savedName)
                        .putString("rotation.settling",desired.key()).putString("rotation.sent-at",Long.toString(System.currentTimeMillis()))
                        .putString("rotation.sent-elapsed",Long.toString(SystemClock.elapsedRealtime())).commit())throw new IllegalStateException("Rotation intent not durable");
            }
            public void returned(EmulatorSavedJoystickProtocol.Operation ignored)throws Exception{store.put("gps.returned",action);}
            public void notSent(EmulatorSavedJoystickProtocol.Operation ignored)throws Exception{
                synchronized(EmulatorSavedDailyStore.class){synchronized(store.journal){
                    String id=store.get("gps.pending","");
                    if(!store.current()||!EmulatorDeadPlantingRecovery.uuid(id)||!action.equals(store.get("intent."+id,""))||
                            !desired.key().equals(store.get("rotation.pending-slot","")))throw new IllegalStateException("Exact unsent GPS intent required");
                    // Called only by switchPaused's local pre-send catch. A
                    // transport failure never reaches this hook, even on timeout.
                    gpsRetirementStorageFailed=true;
                    if(!store.journal.edit().putString("result."+id,"NOT_SENT_BEFORE_TRANSPORT").remove("gps.pending")
                            .remove("rotation.pending-slot").remove("rotation.pending-name").remove("rotation.settling").commit())
                        throw new IllegalStateException("GPS not-sent result unconfirmed");
                    if(!store.get("gps.pending","").isEmpty()||!store.get("result."+id,"").equals("NOT_SENT_BEFORE_TRANSPORT"))throw new IllegalStateException("GPS not-sent readback differs");
                    gpsRetirementStorageFailed=false;
                }}
            }
        },()->{
            EmulatorSavedRouteRotation.Slot fresh=rotationSlot();String pending=store.get("gps.pending","");
            if(!store.enabled()||!active()||closing()||fresh==null||!fresh.key().equals(desired.key())||
                    !plantClosed||!helpersOff||recoveryHold||!store.get("plant.pending","").isEmpty()||
                    !store.get("gps-bootstrap.pending","").isEmpty()||!store.get("batch.pending","").isEmpty()||!store.get("batch-close.pending","").isEmpty()||
                    (!pending.isEmpty()&&!store.get("intent."+pending,"").equals(action))||DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||WorkStatus.stoppingAll||
                    SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app))throw new IllegalStateException("Rotation authority changed");
            Identity i=game();
            // Require actual current-PID inactive and a clear manual-start guard; a local OFF flag alone is insufficient.
            String check="now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+FILES+"emulator_planting_observation.tsv; "+
                    "awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && ($6==\"idle\" || $6==\"stopped-confirmed\") && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+FILES+"emulator_independent_planting_transaction.tsv.safety; ";
            root("set -eu; "+store.guard(true)+i.guard()+check+
                    "for n in dispatch return planter planting flower-farm; do test ! -L /data/local/tmp/pikmin-$n-mode.txt; test \"$(cat /data/local/tmp/pikmin-$n-mode.txt)\" = off; done; "+
                    "test ! -e "+FILES+"feeding_pending.tsv; test ! -e "+FILES+"planter_pending.tsv; "+
                    "awk -F '\\t' 'NR==1 && $1==\"v3\" && $3==0 && $7==0 && $9==0 {ok=1} END {exit !ok}' /data/local/tmp/pikmin-feeding-config.tsv");
        });
        gpsText="換線已送出；等待原指令觀察，不重送";
    }
    private void releaseOwnedGps()throws Exception{
        SharedPreferences owner=app.getSharedPreferences("pikmin_gps_work_lock",0);
        synchronized(GpsWorkLock.class){
            if(!EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner",""))||!store.run.equals(owner.getString("saved_run","")))throw new IllegalStateException("Exact saved GPS release required");
            if(!owner.edit().remove("owner").remove("saved_run").remove("touched_ms").commit())throw new IllegalStateException("GPS release unconfirmed");
            if(EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner",""))&&store.run.equals(owner.getString("saved_run","")))throw new IllegalStateException("GPS release readback differs");
            gpsOwned=false;
        }
    }
    private void effect(EmulatorSavedJoystickProtocol.Operation operation)throws Exception{
        gps.effect(operation,new EmulatorSavedJoystickPort.IntentJournal(){
            public void before(EmulatorSavedJoystickProtocol.Operation op)throws Exception{
                if(closed||!store.current())throw new IllegalStateException("Saved runtime closed");
                if(op==EmulatorSavedJoystickProtocol.Operation.ROUTE_PAUSE)store.intent("gps",op.name());
                else store.activeIntent("gps",op.name());
            }
            public void returned(EmulatorSavedJoystickProtocol.Operation op)throws Exception{store.put("gps.returned",op.name());}
        });gpsText="GPS "+operation+"已送出，等待狀態";
    }
    private void finishColdStoppedGps(SharedPreferences owner,String currentBoot)throws Exception{
        String provider=store.get("gps.provider","");String[] prior=provider.split(":",-1);
        if(prior.length!=3||!prior[0].matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")||
                !prior[1].matches("[1-9][0-9]*")||!prior[2].matches("[1-9][0-9]*")||
                !currentBoot.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))return;
        boolean owned=EmulatorSavedJoystickPort.OWNER.equals(owner.getString("owner",""))&&store.run.equals(owner.getString("saved_run",""));
        boolean maintenance=WorkStatus.stoppingAll||SetupDeployment.pending(app)||CoreUpdater.pending(app)||UpdateInstaller.pending(app)||CoreInstallWizard.preparing(app);
        if(!coldStoppedGpsAllowed(store.current(),store.confirmedStop()&&!store.enabled(),gpsHandoffDrained(),owned,
                !currentBoot.equals(prior[0]),true,maintenance,DispatchBatchService.isRunning()||FlowerFarmService.isRunning()))return;
        // Old-boot disappearance is distinct evidence, never an RPC success.
        root("set -eu; "+store.guard(false)+"test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+currentBoot+
                "; test -z \"$(pidof "+EmulatorSavedJoystickProtocol.PACKAGE+":overlay || true)\";");
        if(!store.confirmedStop()||!gpsHandoffDrained())throw new IllegalStateException("Cold stop changed");
        if(!store.journal.edit().putString("gps.cold-stop-provider",provider).putString("gps.cold-stop-boot",currentBoot)
                .putLong("gps.cold-stop-at",System.currentTimeMillis()).putString("gps.phase","PAUSED").commit())throw new IllegalStateException("Cold stop commit unconfirmed");
        GpsWorkLock.release(app,EmulatorSavedJoystickPort.OWNER);gpsOwned=false;gpsClosed=true;gpsText="GPS已暫停";
    }
    static String archivedPlantingToken(String raw,String liveAccount,String currentToken){
        if(raw==null||liveAccount==null)return "";String[] f=raw.split("\t",-1);
        if(f.length!=10||!f[0].equals("v3")||!f[1].matches("[1-9][0-9]{0,9}")||!f[2].matches("[1-9][0-9]{0,18}")||
           !liveAccount.matches("[a-f0-9]{64}")||!f[3].equals(liveAccount)||!EmulatorDeadPlantingRecovery.uuid(f[4])||f[4].equals(currentToken)||
           !Arrays.asList("start-pending","active-confirmed","stop-pending","review").contains(f[5])||!f[6].matches("[1-9][0-9]{0,18}")||
           !f[7].matches("[01]")||!f[8].matches("0|[1-9][0-9]{0,18}")||!f[9].equals("end"))return "";
        return f[4];
    }
    private boolean archivedPlantingYielded(Identity i,String raw,String currentToken)throws Exception{
        String old=archivedPlantingToken(raw,i.account,currentToken);if(old.isEmpty())return false;
        String archive=FILES+"planting-unconfirmed-"+old+".tsv";
        String result=root("set -eu; "+store.guard(false)+i.sessionGuard()+
            "p='"+archive+"'; if test ! -e $p && test ! -L $p; then echo NONE; exit 0; fi; "+
            "test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:600; test \"$(stat -c %u $p)\" = \"$(stat -c %u "+FILES+")\"; test \"$(stat -c %s $p)\" -le 4096; "+
            "cmp -s $p "+FILES+"emulator_independent_planting_transaction.tsv; test ! -e "+FILES+"emulator-command; test ! -L "+FILES+"emulator-command; "+
            "now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && $6==\"idle\" && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}' "+FILES+"emulator_independent_planting_transaction.tsv.safety; "+
            "awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}' "+FILES+"emulator_planting_observation.tsv; echo RETIRED").trim();
        return result.equals("RETIRED");
    }
    void plantingTick()throws Exception{
        synchronized(GpsWorkLock.class){plantingTickOwned();}
    }
    private void plantingTickOwned()throws Exception{
        plantClosed=false;if(closed||!store.current())return;
        if(plantContinuationStorageFailed){plantText="種花異常已保留；儲存結果待核對，其它工作繼續";return;}
        boolean on=active()&&!recoveryHold&&!rotationDraining()&&!closing()&&!DispatchBatchService.isRunning();
        String pending=store.get("plant.pending",""),token=store.get("plant.token",""),binding=store.get("plant.binding","");
        if(token.isEmpty()!=binding.isEmpty()||!pending.isEmpty()&&token.isEmpty()){plantText="種花原始歸屬不完整；結果待確認";return;}
        if(!on&&plantMetadataEmpty(pending,token,binding)){plantClosed=true;plantText="本排程種花已收尾";return;}
        synchronized(GpsWorkLock.class){
        Identity i=game();
        String raw=root("cat "+FILES+"emulator_independent_planting_transaction.tsv 2>/dev/null || true").trim();
        if(!pending.isEmpty()&&!binding.isEmpty()&&!binding.equals(i.key())&&!raw.isEmpty()){
            if(EmulatorDeadPlantingRecovery.terminalPredecessor(raw+"\n",i.account,token))
                root("set -eu; "+store.guard(false)+i.guard()+EmulatorDeadPlantingRecovery.deadAppOnlyGuard(binding,i.account)+"echo APP_OWNER_ENDED");
            else publishPlantOwnerProvenance(i,token,binding,raw+"\n");
        }
        // A current App admission may not have reached native yet. During
        // draining, first revoke its positive control; archival proof below
        // must never race a still-enabled admission of that same token.
        restrictPlantingAdmission(i,on,pending,binding);
        if(!pending.isEmpty()&&continuePlantingUnknown(i,pending,token,binding)){
            plantText="種花接續核對中；原件保留，不重送舊動作";return;
        }
        if(!binding.isEmpty()&&!binding.equals(i.key())){plantText="舊種花未確認，GPS獨立繼續";return;}
        // A preserved canonical UNKNOWN is not a live current intent. Native
        // keeps it until a DIFFERENT normal admission; never adopt its token.
        if(on&&!raw.isEmpty()&&archivedPlantingYielded(i,raw,token))raw="";
        if(!raw.isEmpty()){
            String[] f=raw.split("\t",-1);if(f.length!=10||!f[0].equals("v3"))throw new IllegalStateException("Plant transaction framing");
            boolean same=f[1].equals(Integer.toString(i.pid))&&f[2].equals(Long.toString(i.epoch))&&f[3].equals(i.account)&&f[4].equals(token);
            if(plantStopMatches(f,token,binding,i.key(),i.pid,i.epoch,i.account)){
                store.put("plant.closed."+token,raw);
                if(!pending.isEmpty())store.confirmed("plant",pending,"native-stopped-confirmed","plant.token","","plant.binding","");
                else store.fields("plant.token","","plant.binding","");
                token="";binding="";
                if(!on){plantClosed=store.get("plant.pending","").isEmpty();plantText="本排程種花停止已確認";return;}
            }
            else if(same&&(f[5].equals("active-confirmed")||f[5].equals("start-pending")||f[5].equals("stop-pending")))plantText="一般種花 · "+f[5];
            else if(f[5].equals("review")){
                // Restrictive OFF allows the original same-process late-stop
                // reconciler to observe its own Tasks. It never clears/replays
                // START/STOP, adopts a foreign token or fabricates closure.
                if(same)root("set -e; "+store.guard(false)+i.guard()+atomic("/data/local/tmp/pikmin-planting-mode.txt","off\n"));
                plantText="種花結果待核對；GPS不中斷";
                try{
                    String[] reason=root("cat "+FILES+"emulator_independent_planting_transaction.tsv.result 2>/dev/null || true").trim().split("\t",-1);
                    long now=System.currentTimeMillis();
                    if(reason.length==10&&reason[0].equals("v1")&&reason[2].equals(Integer.toString(i.pid))&&reason[6].matches("[a-z-]{1,80}")&&now>=Long.parseLong(reason[1])&&now-Long.parseLong(reason[1])<=12000)
                        plantText+="；診斷："+reason[6];
                }catch(Exception unavailable){plantText+="；診斷資料未確認";}
                return;
            }
            else if(!same&&!f[5].equals("stopped-confirmed")){plantText="舊種花原件保留；未接管";return;}
        }
        if(token.isEmpty()&&on){if(!plantingStartReady(createdElapsed,SystemClock.elapsedRealtime())){plantText="一般種花等待啟動緩衝；其它工作繼續";return;}token=UUID.randomUUID().toString();store.activeIntent("plant","NATIVE_ORDINARY_PLANTING","plant.token",token,"plant.binding",i.key());}
        if(token.isEmpty()){plantText="種花休息";plantClosed=!on&&store.get("plant.pending","").isEmpty();return;}
        long now=System.currentTimeMillis();String permit="v1\t"+i.boot+"\t"+i.pid+"\t"+i.epoch+"\t"+i.account+"\t"+token+"\t"+now+"\t"+(now+120000)+"\tend\n";
        String mode=on?"on":"off";
        root("set -e; "+store.guard(on)+i.guard()+atomic("/data/local/tmp/pcc-independent-planting.tsv",permit)+atomic("/data/local/tmp/pikmin-planting-mode.txt",mode+"\n"));
        // OFF publication is not native stopped-confirmed/consumed evidence.
        plantClosed=false;
        if(raw.isEmpty())plantText=on?"獨立種花等待花瓣／主地圖":"種花已要求停止";
        }
    }
    private void restrictPlantingAdmission(Identity i,boolean on,String pending,String held)throws Exception{
        if(!on&&!pending.isEmpty()&&held.equals(i.key()))
            root("set -e; "+store.guard(false)+i.guard()+atomic("/data/local/tmp/pikmin-planting-mode.txt","off\n"));
    }
    private boolean continuePlantingUnknown(Identity i,String intent,String token,String held)throws Exception{
        if(!EmulatorDeadPlantingRecovery.uuid(intent)||!EmulatorDeadPlantingRecovery.uuid(token))return false;
        if(!store.get("intent."+intent,"").equals("NATIVE_ORDINARY_PLANTING"))throw new IllegalStateException("Original planting intent differs");
        if(held.equals(i.key())&&EmulatorDeadPlantingRecovery.retireArchivedSelector(held,token,store.guard(false)+i.sessionGuard()))return true;
        String archive=FILES+"planting-unconfirmed-"+token+".tsv";
        // Only the native continuation path publishes idle after preserving
        // both originals, 30s of inactive observation and terminal/no Tasks.
        String encoded=root("set -eu; "+store.guard(false)+i.guard()+
            "p='"+archive+"'; if test ! -e $p && test ! -L $p; then echo NONE; exit 0; fi; "+
            "test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:600; test \"$(stat -c %u $p)\" = \"$(stat -c %u "+FILES+")\"; test \"$(stat -c %s $p)\" -le 4096; "+
            "base64 $p").trim();
        boolean appOnly=encoded.equals("NONE");
        String original=appOnly?"PCCAPPUNKNOWN/1\t"+intent+"\t"+token+"\t"+held+"\tUNKNOWN\tend\n":new String(Base64.getDecoder().decode(encoded.replaceAll("\\s","")),StandardCharsets.UTF_8);
        if(!appOnly)EmulatorDeadPlantingRecovery.transaction(original,held,token);
        // Native may have completed UNKNOWN preservation and admitted later
        // rounds before this observer consumes the older intent. Accept that
        // predecessor only when the CURRENT canonical is independently archived
        // byte-for-byte too. Native idle still proves terminal Tasks/controller.
        String canonicalPath=FILES+"emulator_independent_planting_transaction.tsv";
        String current=root("set -eu; "+store.guard(false)+i.guard()+"cat "+canonicalPath);
        String[] currentFields=current.split("\\t",-1);
        boolean terminalPredecessor=appOnly&&EmulatorDeadPlantingRecovery.terminalPredecessor(current,i.account,token);
        if(appOnly&&!held.equals(i.key())&&!terminalPredecessor)return false;
        String endedAppOwner=terminalPredecessor&&!held.equals(i.key())?EmulatorDeadPlantingRecovery.deadAppOnlyGuard(held,i.account):"";
        if(!endedAppOwner.isEmpty())root("set -eu; "+store.guard(false)+i.guard()+endedAppOwner+atomic("/data/local/tmp/pikmin-planting-mode.txt","off\n"));
        if(appOnly&&terminalPredecessor&&held.equals(i.key())&&current.trim().equals(store.get("plant.closed."+currentFields[4],""))&&
                preservePreparedAfterClosed(i,intent,token,held,current,original))return true;
        boolean terminalReview=false;
        // A different terminal is never changed into UNKNOWN. If native is
        // reviewing its old selector, revoke admission and let native prove
        // terminal Tasks/controller-clear and quiet before archiving this App
        // intent. OFF is restrictive even if an admission raced this read.
        if(terminalPredecessor){
            String safe=root("set -eu; "+store.guard(false)+i.guard()+"now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && $6==\"review\" && now>=$2 && now-$2<=10000 {ok=1} END {print ok?\"REVIEW\":\"WAIT\"}' "+FILES+"emulator_independent_planting_transaction.tsv.safety").trim();
            terminalReview=safe.equals("REVIEW");
            if(terminalReview)root("set -eu; "+store.guard(false)+i.guard()+atomic("/data/local/tmp/pikmin-planting-mode.txt","off\n"));
        }
        // A current native intent without an UNKNOWN archive is still owned
        // by the ordinary observer. Do not enter archival proof (and throw)
        // on every tick: that would prevent its normal permit renewal.
        // REVIEW also stays with the native terminal-Task/quiet reconciler.
        if(appOnly&&currentFields.length==10&&token.equals(currentFields[4])){
            if(!plantStopMatches(currentFields,token,held,i.key(),i.pid,i.epoch,i.account))
                EmulatorDeadPlantingRecovery.transaction(current,held,token);
            return false;
        }
        // An archived predecessor is not evidence that this newer App token
        // was never admitted. Native can accept the published permit between
        // these reads. Keep the intent and its normal permit renewal while ON;
        // only restrictive OFF may enter the original inactive/idle proof.
        if(appOnly&&!root("set -eu; "+store.guard(false)+i.guard()+
                "test ! -L /data/local/tmp/pikmin-planting-mode.txt; cat /data/local/tmp/pikmin-planting-mode.txt").trim().equals("off"))return false;
        if(!current.equals(original)&&!terminalPredecessor){
            if(currentFields.length!=10)throw new IllegalStateException("Current planting framing differs");
            EmulatorDeadPlantingRecovery.transaction(current,i.key(),currentFields[4]);
            if(token.equals(currentFields[4]))throw new IllegalStateException("Archived token bytes conflict");
        }
        String currentArchive=FILES+"planting-unconfirmed-"+currentFields[4]+".tsv";
        String terminalGuard="";
        if(terminalPredecessor){
            String leaf="planting-terminal-"+currentFields[4]+".tsv.ready-"+i.boot+"-"+i.pid+"-"+i.ticks+"-"+i.epoch;
            String encodedTerminal=root("set -eu; "+store.guard(false)+i.guard()+"p='"+FILES+leaf+"'; if test ! -e $p && test ! -L $p; then echo NONE; exit 0; fi; test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:600; test \"$(stat -c %u $p)\" = \"$(stat -c %u "+FILES+")\"; test \"$(stat -c %s $p)\" -le 4096; base64 $p").trim();
            if(encodedTerminal.equals("NONE"))return terminalReview; // retain restrictive OFF throughout native quiet
            String nativeTerminal=new String(Base64.getDecoder().decode(encodedTerminal.replaceAll("\\s","")),StandardCharsets.UTF_8);
            terminalGuard=EmulatorDeadPlantingRecovery.terminalPredecessorGuard(current,i.key(),token,nativeTerminal);
            // Save the full native provenance as independent evidence too.
            EmulatorDeadPlantingRecovery.writeExact(canonicalSavedRoot(app).resolve("plant-terminal-predecessor-"+intent+".tsv"),nativeTerminal);
        }
        String proof=root("set -eu; "+store.guard(false)+i.guard()+
            endedAppOwner+terminalGuard+"for p in "+(appOnly?"":"'"+archive+"' ")+"'"+canonicalPath+"' "+(terminalPredecessor?"":"'"+currentArchive+"' ")+"; do test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:600; test \"$(stat -c %u $p)\" = \"$(stat -c %u "+FILES+")\"; done; "+
            (appOnly?"test ! -e '"+archive+"'; test ! -L '"+archive+"'; ":"test \"$(sha256sum '"+archive+"' | cut -d' ' -f1)\" = "+EmulatorDailyRunner.digest(original)+"; ")+
            "test \"$(sha256sum '"+canonicalPath+"' | cut -d' ' -f1)\" = "+EmulatorDailyRunner.digest(current)+"; "+
            (terminalPredecessor?"":"cmp -s '"+canonicalPath+"' '"+currentArchive+"'; ")+
            "now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==6 && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+FILES+"emulator_planting_observation.tsv; "+
            "now=$(date +%s%3N); awk -F '\\t' -v p="+i.pid+" -v now=$now 'NR==1 && NF==8 && $1==\"v1\" && $3==p && $4==\"ready\" && $5==\"clear\" && $6==\"idle\" && $8==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !ok}' "+FILES+"emulator_independent_planting_transaction.tsv.safety; echo PRESERVED_IDLE").trim();
        if(!proof.equals("PRESERVED_IDLE"))throw new IllegalStateException("Archived continuation proof differs");
        if(!EmulatorDeadPlantingRecovery.binding(held)[4].equals(i.account))throw new IllegalStateException("UNKNOWN account differs");
        java.nio.file.Path local=canonicalSavedRoot(app).resolve("plant-unconfirmed-"+intent+".tsv");
        EmulatorDeadPlantingRecovery.writeExact(local,original);
        if(!current.equals(original))EmulatorDeadPlantingRecovery.writeExact(canonicalSavedRoot(app).resolve("plant-unconfirmed-successor-"+intent+".tsv"),current);
        synchronized(EmulatorSavedDailyStore.class){synchronized(store.journal){
            if(!store.current()||!intent.equals(store.get("plant.pending",""))||!token.equals(store.get("plant.token",""))||!held.equals(store.get("plant.binding","")))throw new IllegalStateException("Original planting changed");
            // Preserve the intent, original binding/token and UNKNOWN result in
            // the SAME commit that frees this lane. Never call confirmed().
            String result="UNKNOWN_PRESERVED:"+EmulatorDailyRunner.digest(original);
            plantContinuationStorageFailed=true;
            if(!store.journal.edit().putString("result."+intent,result)
                .putString("plant.unconfirmed-binding."+intent,held).putString("plant.unconfirmed-token."+intent,token)
                .remove("plant.pending").putString("plant.token","").putString("plant.binding","").commit())throw new IllegalStateException("UNKNOWN preservation unconfirmed");
            if(!result.equals(store.get("result."+intent,""))||!store.get("plant.pending","").isEmpty())throw new IllegalStateException("UNKNOWN readback differs");
            plantContinuationStorageFailed=false;
        }}
        return true;
    }
    /** Same-process successor was published but has no native transaction.
     * Restrictive OFF, an expired exact permit, unchanged terminal closure,
     * terminal controller/Tasks, and 30 seconds of advancing inactive samples
     * allow only App UNKNOWN preservation. No native record is rewritten. */
    static boolean preparedIdleProof(String held,String token,String current,String closed,String selected,
            String observation,String safety,String diagnostic,String permit,long now){
        try{
            String[] b=EmulatorDeadPlantingRecovery.binding(held),t=current.split("\t",-1),v=selected.split("\t",-1),
                o=observation.trim().split("\t",-1),s=safety.trim().split("\t",-1),d=diagnostic.trim().split("\t",-1),p=permit.trim().split("\t",-1);
            EmulatorDeadPlantingRecovery.preparedAfterClosed(current,held,token,closed);
            if(v.length!=8||!v[0].equals("v1")||!v[1].equals(b[1])||!v[2].equals(b[3])||!v[3].equals(b[4])||
                    !v[4].equals(t[4])||!v[5].equals("closed")||!v[6].matches("[A-Za-z0-9._:-]{1,80}")||!v[7].equals("end\n"))return false;
            if(o.length!=6||!o[0].equals("v1")||!o[2].equals(b[1])||!o[3].equals("inactive")||!o[4].equals("inactive")||!o[5].equals("end"))return false;
            if(s.length!=8||!s[0].equals("v1")||!s[2].equals(b[1])||!s[3].equals("ready")||!s[4].equals("clear")||!s[5].equals("idle")||!s[6].equals("attempted")||!s[7].equals("end"))return false;
            if(d.length!=12||!d[0].equals("v1")||!d[2].equals(b[1])||!d[3].matches("[1-9][0-9]{0,9}")||!d[4].equals("0")||
                    !d[5].matches("[1-9][0-9]{0,18}")||!d[6].equals("0")||!d[7].equals("0")||!d[9].equals("idle")||
                    !d[10].equals("closed-controller-task-cleared")||!d[11].equals("end"))return false;
            for(String[] sample:new String[][]{o,s,d}){long at=Long.parseLong(sample[1]);if(at<=0||now<at||now-at>10000)return false;}
            long start=Long.parseLong(t[8]),stop=Long.parseLong(t[6]),last=Long.parseLong(d[5]);
            if(start<=0||stop<start||last<start||last>stop)return false;
            if(p.length!=9||!p[0].equals("v1")||!p[1].equals(b[0])||!p[2].equals(b[1])||!p[3].equals(b[3])||!p[4].equals(b[4])||!p[5].equals(token)||!p[8].equals("end"))return false;
            long published=Long.parseLong(p[6]),expiry=Long.parseLong(p[7]);
            return published>0&&published<=now&&expiry>published&&expiry-published==120000;
        }catch(Exception invalid){return false;}
    }
    private boolean preservePreparedAfterClosed(Identity i,String intent,String token,String held,String current,String original)throws Exception{
        if(!root("set -eu; "+store.guard(false)+i.guard()+"cat /data/local/tmp/pikmin-planting-mode.txt").trim().equals("off")){preparedQuietKey="";return false;}
        String canonical=FILES+"emulator_independent_planting_transaction.tsv";
        String[] paths={canonical,canonical+".selection",FILES+"emulator_planting_observation.tsv",canonical+".safety",canonical+".diagnostic","/data/local/tmp/pcc-independent-planting.tsv"};
        String off="test ! -L /data/local/tmp/pikmin-planting-mode.txt; test \"$(cat /data/local/tmp/pikmin-planting-mode.txt)\" = off; "+
            "for p in "+FILES+"feeding_pending.tsv "+FILES+"planter_pending.tsv "+FILES+"emulator-command; do test ! -e $p; test ! -L $p; done; ";
        String command="set -eu; "+store.guard(false)+i.guard()+off;
        for(String path:paths)command+="p='"+path+"'; test -f $p; test ! -L $p; test \"$(stat -c %h:%a $p)\" = 1:"+(path.equals(canonical)||path.equals(canonical+".selection")?"600":"644")+"; test \"$(stat -c %s $p)\" -le 4096; "+
            (path.startsWith(FILES)?"test \"$(stat -c %u $p)\" = \"$(stat -c %u "+FILES+")\"; ":"test \"$(stat -c %u $p)\" = 0; ")+"base64 $p | tr -d '\\n'; echo; ";
        String encoded=root(command).trim();String[] lines=encoded.split("\n",-1);if(lines.length!=6)throw new IllegalStateException("Prepared snapshot framing");
        String[] raw=new String[6];for(int n=0;n<6;n++)raw[n]=new String(Base64.getDecoder().decode(lines[n].trim()),StandardCharsets.UTF_8);
        long now=System.currentTimeMillis();String prior=store.get("plant.closed."+current.split("\t",-1)[4],"");
        if(!raw[0].equals(current)||!preparedIdleProof(held,token,raw[0],prior,raw[1],raw[2],raw[3],raw[4],raw[5],now)){preparedQuietKey="";return false;}
        String key=EmulatorDailyRunner.digest(intent+held+raw[0]+raw[1]+raw[5]+raw[4].substring(raw[4].indexOf('\t',raw[4].indexOf('\t')+1)));
        long observed=Long.parseLong(raw[2].split("\t",-1)[1]),elapsed=SystemClock.elapsedRealtime();
        if(!key.equals(preparedQuietKey)){preparedQuietKey=key;preparedQuietElapsed=elapsed;preparedQuietObserved=observed;preparedQuietFirst=encoded;return true;}
        long expiry=Long.parseLong(raw[5].trim().split("\t",-1)[7]);
        if(elapsed-preparedQuietElapsed<30000||observed<=preparedQuietObserved||now<=expiry)return true;
        java.nio.file.Path saved=canonicalSavedRoot(app);
        EmulatorDeadPlantingRecovery.writeExact(saved.resolve("plant-prepared-quiet-first-"+intent+".tsv"),preparedQuietFirst+"\n");
        EmulatorDeadPlantingRecovery.writeExact(saved.resolve("plant-prepared-quiet-last-"+intent+".tsv"),encoded+"\n");
        EmulatorDeadPlantingRecovery.writeExact(saved.resolve("plant-unconfirmed-"+intent+".tsv"),original);
        EmulatorDeadPlantingRecovery.writeExact(saved.resolve("plant-unconfirmed-successor-"+intent+".tsv"),current);
        // Repeat all guards and exact immutable inputs after preservation. No
        // positive control is published while this runtime owns GpsWorkLock.
        String finalEncoded=root(command).trim();String[] finalLines=finalEncoded.split("\n",-1);if(finalLines.length!=6)throw new IllegalStateException("Prepared final framing");
        String[] last=new String[6];for(int n=0;n<6;n++)last[n]=new String(Base64.getDecoder().decode(finalLines[n].trim()),StandardCharsets.UTF_8);
        if(!last[0].equals(raw[0])||!last[1].equals(raw[1])||!last[5].equals(raw[5])||
                !preparedIdleProof(held,token,last[0],prior,last[1],last[2],last[3],last[4],last[5],System.currentTimeMillis()))throw new IllegalStateException("Prepared final proof changed");
        synchronized(EmulatorSavedDailyStore.class){synchronized(store.journal){
            if(!store.current()||!intent.equals(store.get("plant.pending",""))||!token.equals(store.get("plant.token",""))||!held.equals(store.get("plant.binding",""))||!prior.equals(store.get("plant.closed."+current.split("\t",-1)[4],"")))throw new IllegalStateException("Prepared original changed");
            String result="UNKNOWN_PRESERVED:"+EmulatorDailyRunner.digest(original);plantContinuationStorageFailed=true;
            if(!store.journal.edit().putString("result."+intent,result).putString("plant.unconfirmed-binding."+intent,held).putString("plant.unconfirmed-token."+intent,token)
                .remove("plant.pending").putString("plant.token","").putString("plant.binding","").commit())throw new IllegalStateException("Prepared UNKNOWN commit unconfirmed");
            if(!result.equals(store.get("result."+intent,""))||!store.get("plant.pending","").isEmpty())throw new IllegalStateException("Prepared UNKNOWN readback differs");
            plantContinuationStorageFailed=false;preparedQuietKey="";
        }}
        return true;
    }
    private void publishPlantOwnerProvenance(Identity i,String token,String held,String original)throws Exception{
        EmulatorDeadPlantingRecovery.transaction(original,held,token);String[] b=EmulatorDeadPlantingRecovery.binding(held);
        if(!b[4].equals(i.account))throw new IllegalStateException("原種花帳號不同");
        String sourceSha=EmulatorDailyRunner.digest(original);
        String row="PCCPLANTOWNER/1\t"+b[0]+"\t"+b[1]+"\t"+b[2]+"\t"+b[3]+"\t"+b[4]+"\t"+token+"\t"+sourceSha+"\tend\n";
        String sha=EmulatorDailyRunner.digest(row),base64=Base64.getEncoder().encodeToString(row.getBytes(StandardCharsets.UTF_8));
        String dest=FILES+"planting-dead-owner-"+token+".tsv",tmp=dest+".prepare-"+UUID.randomUUID();
        // This publishes the original tuple, NOT an assertion that it died.
        // Native independently compares boot or exact /proc start ticks.
        root("set -eu; "+store.guard(false)+i.sessionGuard()+"test \"$(sha256sum "+FILES+"emulator_independent_planting_transaction.tsv | cut -d' ' -f1)\" = "+sourceSha+"; "+
            "p='"+dest+"'; test ! -L $p; uid=$(stat -c %u "+FILES+"); if test ! -e $p; then t='"+tmp+"'; umask 077; (set -C; printf %s '"+base64+"'|base64 -d > $t); chown $uid:$uid $t; chmod 600 $t; sync; mv -n $t $p; restorecon -F $p; fi; "+
            "test -f $p; test \"$(stat -c %u:%g:%a:%h $p)\" = $uid:$uid:600:1; test \"$(sha256sum $p | cut -d' ' -f1)\" = "+sha+"; sync;");
    }
    void helpersTick()throws Exception{
        if(closed||!store.current())return;
        boolean on=active()&&!recoveryHold&&!rotationDraining();boolean closingReturns=closingReturnsAllowed(!closed&&store.enabled()&&!recoveryHold,developmentOpen()||eveningClosing(daytime(),minute(System.currentTimeMillis())));Identity i=on||closingReturns?game():null;
        synchronized(GpsWorkLock.class){
        helpersOff=false;
        if(DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||GpsWorkLock.heldBy(app,GpsWorkLock.BATCH)||GpsWorkLock.heldBy(app,GpsWorkLock.FLOWER_FARM)){helperText="其它工作持有原生控制；收尾待確認";return;}
        // Also applies to an already enabled schedule restored after APK update.
        // Keep desired ON across rest; effective controls below become diag.
        if(on&&!AutomationPreferences.value(app,"nectar","off").equals("on"))AutomationPreferences.choose(app,"nectar","on",()->{});
        boolean nearby=on&&!NearbyWorkState.pending(app);
        FeedingConfig feed=FeedingGateway.load(app).copy();feed.enabled=on;feed.collect=on;feed.rotate=on;
        FeedingCatalogue.select(feed,FeedingCatalogue.display(new FeedingSnapshot()).kinds);
        feed.revision=UUID.nameUUIDFromBytes((store.run+":helpers:"+on).getBytes(StandardCharsets.UTF_8)).toString();
        String modes=helperModes(on,nearby,feed);
        // A bounded rest-window closing test must collect its dispatched work.
        // Only this return lane is enabled; rest still keeps other helpers OFF.
        if(!on&&closingReturns)modes+=atomic("/data/local/tmp/pikmin-return-mode.txt","all\n");
        // Restrictive OFF does not need a fresh gameplay heartbeat.
        root("set -e; "+store.guard(on||closingReturns)+(i==null?"":i.guard())+modes);
        helpersOff=!on;
        helperText=on?(nearby?"附近精華／派遣／領獎／育苗／全花種餵收換隊 ON":"附近派遣待核對；附近精華／領獎／育苗／餵收換隊 ON"):closingReturns?"一般助手休息；收工領回開啟":"背景功能休息";
        // Native continuous nursery owns bounded observation and current-state adoption.
        }
    }
    static boolean closingReturnsAllowed(boolean enabled,boolean developmentOpen){return enabled&&developmentOpen;}
    static String helperModes(boolean on,boolean nearby,FeedingConfig feed){
        return atomic("/data/local/tmp/pikmin-dispatch-mode.txt",nearby?"armed\n":"off\n")+atomic("/data/local/tmp/pikmin-return-mode.txt",on?"all\n":"off\n")+
            atomic("/data/local/tmp/pikmin-nearby-kinds.txt","fruit seed gift\n")+atomic("/data/local/tmp/pikmin-return-postcard-policy.txt","keep\n")+
            atomic("/data/local/tmp/pikmin-planter-mode.txt",on?"auto\n":"off\n")+atomic(FeedingGateway.CONTROL,feed.encode())+
            NectarModuleGateway.controlCommand(on)+"; ";
    }
    static String atomic(String path,String wire){
        String data=Base64.getEncoder().encodeToString(wire.getBytes(StandardCharsets.UTF_8));
        return "test ! -L '"+path+"'; test ! -L '"+path+".saved-tmp'; printf %s '"+data+"'|base64 -d > '"+path+".saved-tmp'; chmod 0644 '"+path+".saved-tmp'; mv '"+path+".saved-tmp' '"+path+"'; ";
    }
    static boolean developmentConfigMatches(Context c,EmulatorDevelopmentBatchRun run)throws Exception{
        EmulatorSavedDailyStore saved=EmulatorSavedDailyStore.open(c);
        if(saved!=null&&run.configSha.equals(EmulatorDailyRunner.digest(saved.wire))){
            EmulatorDevelopmentBatch.Window w=new EmulatorSavedDailyRuntime(c,saved).development();
            String key=saved.get("batch.key","");String binding=saved.get("batch.binding","");
            return saved.enabled()&&w!=null&&w.sameRun(run.window)&&!key.isEmpty()&&
                EmulatorDevelopmentBatch.sameRunKey(key,run.window.key)&&!binding.isEmpty()&&
                run.binding.same(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(binding)));
        }
        return run.configSha.equals(EmulatorDailyRunner.digest(EmulatorAndroidDailyConfigured.read(c).encode()));
    }
    private long stepOwner(StepEngine engine)throws Exception{
        long epoch=engine.savedCommandEpoch(store.run);if(epoch==-2L)throw new IllegalStateException("Step owner unreadable");return epoch;
    }
    private void observeStepIntent(StepEngine engine)throws Exception{
        String pending=store.get("steps.pending","");if(pending.isEmpty())return;
        if(!("START_SAVED "+store.run).equals(store.get("intent."+pending,"")))throw new IllegalStateException("Original step intent differs");
        long admitted=engine.savedAdmission(store.run,pending);
        if(admitted==-3L)throw new IllegalStateException("Step admission unreadable");
        if(admitted>=0L)store.confirmed("steps",pending,"durable-start-admitted","steps.scope","owned","steps.epoch",Long.toString(admitted));
        else if(admitted==-1L)store.confirmed("steps",pending,"durable-start-not-admitted","steps.scope","none","steps.epoch","");
    }
    private void drainSteps(StepEngine engine)throws Exception{
        stepsClosed=false;observeStepIntent(engine);
        if(!store.get("steps.pending","").isEmpty()){stepsText="步數啟動結果待確認；未取得停止權限";return;}
        String scope=store.get("steps.scope",""),raw=store.get("steps.epoch","");
        StepView view=engine.snapshot();if(StepView.STORE_ERROR.equals(view.phase))throw new IllegalStateException("Step state unreadable");
        if(scope.equals("none")||scope.equals("closed")){stepsClosed=true;stepsText="本排程步數已收尾";return;}
        if(!scope.equals("owned")||!raw.matches("[0-9]+")){
            stepsClosed=!view.desiredRunning&&!StepService.isRunning();
            stepsText=stepsClosed?"步數已閒置":"舊步數歸屬待確認；目前工作保留";return;
        }
        long epoch=Long.parseLong(raw),owner=stepOwner(engine);
        if(owner>=0L&&owner!=epoch)throw new IllegalStateException("Saved step epoch changed");
        if(owner==epoch){
            view=engine.stopSaved(store.run,epoch);
            if(StepView.STORE_ERROR.equals(view.phase))throw new IllegalStateException("Owned step stop unconfirmed");
        }
        owner=stepOwner(engine);long current=engine.commandEpoch();
        if(current<0L)throw new IllegalStateException("Step epoch unreadable");
        if(owner>=0L){stepsText="等待本排程步數停止確認";return;}
        // Same epoch with no saved owner is an explicit manual transfer.
        // A retired epoch can only interrupt its exact old service token.
        if(current!=epoch&&StepService.liveCommandEpoch()==epoch){
            long token=StepService.liveRunToken();if(token!=0L)StepService.stop(app,epoch,token);
        }
        stepsClosed=current==epoch||!StepService.isServing(epoch);
        if(stepsClosed){store.fields("steps.scope","closed");stepsText=current==epoch?"步數已由手動接續；未停止目前工作":"本排程步數已停止；寫入紀錄保留";}
        else stepsText="等待原步數服務結束";
    }
    void stepsTick()throws Exception{
        stepsClosed=false;if(closed||!store.current())return;
        StepEngine engine=StepHealthGateway.engine(app);
        if(store.needsDrain()){drainSteps(engine);return;}
        if(daytime()){
            String scope=store.get("steps.scope","none");
            if(!scope.equals("none")&&!scope.equals("closed")){drainSteps(engine);stepText=stepsText;return;}
            stepsClosed=store.get("steps.pending","").isEmpty();stepsText="三城散步排程未啟用步數寫入";stepText=stepsText;return;
        }
        if(!active())return;
        observeStepIntent(engine);
        if(!store.get("steps.pending","").isEmpty()){stepsText="步數啟動結果待確認；不重送";return;}
        StepView view=engine.snapshot();if(StepView.STORE_ERROR.equals(view.phase))throw new IllegalStateException("Step state unreadable");
        long epoch=stepOwner(engine);
        if(epoch>=0L){
            if(!store.get("steps.scope","").equals("owned")||!store.get("steps.epoch","").equals(Long.toString(epoch)))throw new IllegalStateException("Exact saved step receipt required");
            if(active()&&view.desiredRunning&&StepWriterControl.mayLaunchService(view)&&!StepService.isServing(epoch))
                StepService.start(app,epoch,StepWriterControl.nextRunToken());
            stepsText="本排程步數執行中";return;
        }
        if(view.desiredRunning||StepService.isRunning()){stepsText="既有步數工作保留；未接管";return;}
        if(!view.disclosed){stepsText="步數等待使用者確認";return;}
        String intent=store.activeIntent("steps","START_SAVED "+store.run,"steps.scope","unconfirmed");
        view=engine.startSaved(store.run,intent,StepSettings.parse(500000,360,240,130,"normal",60000));
        observeStepIntent(engine);
        if(store.needsDrain()){drainSteps(engine);return;}
        epoch=stepOwner(engine);
        if(active()&&epoch>=0L&&store.get("steps.pending","").isEmpty()&&StepWriterControl.mayLaunchService(view))
            StepService.start(app,epoch,StepWriterControl.nextRunToken());
        else stepsText="步數啟動結果待確認";
    }
    static boolean batchAdmissionsAllowed(boolean enabled,boolean active,boolean developmentOpen){
        // Explicit bounded closing tests can run during rest. STOP still wins.
        return enabled&&(active||developmentOpen);
    }
    static boolean batchLocationReady(boolean valid,String actualPid,String expectedPid,String state,int samples){
        return valid&&expectedPid.equals(actualPid)&&"ready".equals(state)&&samples==3;
    }
    void batchTick()throws Exception{
        batchClosed=false;if(closed||!store.current())return;
        if(batchRetirementStorageFailed)throw new IllegalStateException("批次UNKNOWN儲存未確認；保留收尾");
        String pending=store.get("batch.pending",""),closePending=store.get("batch-close.pending","");
        batchClosed=pending.isEmpty()&&closePending.isEmpty();
        if(!pending.isEmpty()||!closePending.isEmpty()){
            EmulatorDailyRunner.Binding held=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(store.get("batch.binding","")));
            String key=store.get("batch.key","");
            if(key.isEmpty()||(!pending.isEmpty()&&!("THREE_KINDS "+key).equals(store.get("intent."+pending,"")))||(!closePending.isEmpty()&&!key.equals(store.get("intent."+closePending,""))))throw new IllegalStateException("Batch journal incomplete; preserve pending");
            // User STOP closes only our live admissions. An accepted task may
            // finish; a missing/foreign service is never adopted or restarted.
            if(!batchAdmissionsAllowed(!closed&&store.enabled(),active(),developmentOpen()||eveningClosing(daytime(),minute(System.currentTimeMillis())))&&DispatchBatchService.ownsDailyBatch(held,key)&&store.get("batch-close.pending","").isEmpty()){
                store.intent("batch-close",key);
                new EmulatorAndroidDailyBatchPort(app).closeAdmissions(held,key);
                store.put("batch-close.returned",key);
            }
            EmulatorDailyRunner.Observation observation=new EmulatorAndroidDailyBatchPort(app).observe(held,key);
            if(!held.same(observation.binding)||!key.equals(observation.key))throw new IllegalStateException("Exact batch observation required");
            if(observation.evidence==EmulatorDailyRunner.Evidence.COMPLETE||observation.evidence==EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS){
                store.put("batch.outcome."+key,observation.evidence.name());if(!pending.isEmpty())store.confirmed("batch",pending,observation.evidence.name());
                String close=store.get("batch-close.pending","");
                if(!close.isEmpty()){
                    if(!store.get("intent."+close,"").equals(key))throw new IllegalStateException("Original batch close changed");
                    store.confirmed("batch-close",close,"matched-batch-completed");
                }
                batchClosed=store.get("batch.pending","").isEmpty()&&store.get("batch-close.pending","").isEmpty();
                batchText=observation.evidence==EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS?"收工候選全部確認":"本輪收工已結束，保留其餘待辦";
            }else{
                if(observation.evidence==EmulatorDailyRunner.Evidence.ADMITTED){
                    EmulatorDevelopmentBatch.Window w=development();
                    long deadline=EmulatorDevelopmentBatch.development(key)&&w!=null&&EmulatorDevelopmentBatch.sameRunKey(key,w.key)?w.deadline:EmulatorDailyPlan.deadline(key);
                    if(!pending.isEmpty()&&DispatchBatchService.ownsDailyBatch(held,key)&&System.currentTimeMillis()>=deadline-90000&&store.get("batch-close.pending","").isEmpty()){
                        store.intent("batch-close",key);
                        new EmulatorAndroidDailyBatchPort(app).closeAdmissions(held,key);
                        store.put("batch-close.returned",key);
                    }
                    batchText="三類收工批次進行中";
                }else batchText="收工結果待確認，不重送";
                return;
            }
        }
        if(!closing()||!store.enabled())return;
        // A completed immutable observation must remain visible. Do not
        // overwrite it with a transient GPS-ownership wait on later ticks.
        EmulatorDevelopmentBatch.Window terminalWindow=developmentOpen()?development():null;
        String terminalFirst=terminalWindow==null?formalClosingFirst(daytime(),daytime()?EmulatorDailyPlan.eveningWorkDay(System.currentTimeMillis()):LocalDate.now(EmulatorDailyPlan.ZONE)):terminalWindow.key;
        String terminalKey=store.get("batch.key","");
        if(!terminalKey.isEmpty()&&EmulatorDevelopmentBatch.sameRunKey(terminalKey,terminalFirst)){
            String terminalResult=store.get("batch.outcome."+terminalKey,"");
            if(terminalResult.equals("COMPLETE_ALL_CLAIMS")){batchText="本輪收工候選全部確認";return;}
            if(terminalResult.equals("COMPLETE")&&EmulatorDevelopmentBatch.index(terminalKey)==3){batchText="三輪收工已結束；其餘待辦保留";return;}
        }
        if(recoveryHold){batchText="收工等待核心安全復原；未派遣、不重送";return;}
        if(gpsOwned){batchText="收工等待GPS暫停";return;}
        if(!plantClosed){batchText="收工等待本輪種花停止確認";return;}
        // Helpers and batch use separate clocks. Do not admit before the
        // ordinary auto-return lane has actually enabled its control, including
        // a development window requested just after the helpers' last tick.
        if(!root("cat /data/local/tmp/pikmin-return-mode.txt").trim().equals("all")){
            batchText="收工等待自動領回啟用；尚未派遣";return;
        }
        Identity i=game();EmulatorDevelopmentBatch.Window window=developmentOpen()?development():null;
        String first=window==null?formalClosingFirst(daytime(),daytime()?EmulatorDailyPlan.eveningWorkDay(System.currentTimeMillis()):LocalDate.now(EmulatorDailyPlan.ZONE)):window.key;
        String key=first;
        String retained=store.get("batch.key","");
        if(!priorBatchResolved(retained,store.get("batch.outcome."+retained,""))){
            batchText="舊收工結果未確認，保留原件、不啟動新批次";return;
        }
        if(!retained.isEmpty()&&EmulatorDevelopmentBatch.sameRunKey(retained,first)){
            String result=store.get("batch.outcome."+retained,"");
            if(result.equals("COMPLETE_ALL_CLAIMS")||EmulatorDevelopmentBatch.index(retained)==3)return;
            if(!result.equals("COMPLETE")){batchText="收工工作已保留；未知不重送";return;}
            key=EmulatorDevelopmentBatch.keyAt(retained,EmulatorDevelopmentBatch.index(retained)+1);
        }
        long deadline=window==null?EmulatorDailyPlan.deadline(key):window.deadline;
        if(System.currentTimeMillis()>=deadline-90000){batchText="保留剩餘待辦，收工前不開新輪";return;}
        if(DispatchBatchService.isRunning()||GpsWorkLock.anyHeld(app)){batchText="收工等待既有工作釋放；未派遣";return;}
        String apk=EmulatorDailyUiStore.sha(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(app.getApplicationInfo().sourceDir)));
        EmulatorDailyRunner.Binding held=new EmulatorDailyRunner.Binding("127.0.0.1:16448",i.boot,i.pid,i.account,i.epoch,i.pair.sha,apk,EmulatorDailyRunner.digest(store.plan.encode()),19);
        // Complete read-only scan BEFORE reserving gameplay work. Transient
        // publication/read failure may be observed again without replaying a
        // dispatch intent. The scan owns BATCH and pauses/restores nearby mode.
        EmulatorDailyBatchScan.Snapshot scan=EmulatorDailyBatchScan.read(app,held);
        FarmLocation location=GpsModuleGateway.farmLocation(app);
        // Fresh packets may still describe interpolation/settling and an
        // unstable epoch. Wait before reserving gameplay intent; original
        // start/claim/arrival checks still revalidate freshness and identity.
        if(!batchLocationReady(location.valid,location.pid,Integer.toString(i.pid),location.state,location.samples)){
            batchText="收工等待遊戲定位穩定；尚未派遣";return;
        }
        long admissionNow=System.currentTimeMillis();
        if(!store.enabled()||!closing()||(window!=null&&!window.open(admissionNow)))return;
        EmulatorDevelopmentBatch.Window sweep=window==null?null:window.sweep(EmulatorDevelopmentBatch.index(key));
        // Validate original scan TTL BEFORE reserving/consuming a start intent.
        // IO cannot freshen the scan or turn a failed preparation into unknown gameplay.
        EmulatorDailyBatchInventory inventory=EmulatorSavedBatchPreparation.prepare(held,key,location.epoch,deadline,scan,sweep,admissionNow);
        // Passive expiry is a pre-effect wait, not an unknown gameplay intent.
        // The original start/admission gates recheck this independently later.
        EmulatorDailyResources.require(app,held,false,false,false);
        batchClosed=false;store.activeIntent("batch","THREE_KINDS "+key,"batch.key",key,"batch.binding",EmulatorDailyRunner.base64(held.encode()));
        EmulatorAndroidDailyBatchPort port=new EmulatorAndroidDailyBatchPort(app);
        try{
        if(window==null)port.startPreparedInventory(held,inventory);
        else{
            java.nio.file.Path files=EmulatorAndroidDailyStore.files(app);
            EmulatorDailyRouteWork.PrivateMode mode=p->java.nio.file.Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            EmulatorDevelopmentBatchRun run;
            if(EmulatorDevelopmentBatch.index(key)==1){run=new EmulatorDevelopmentBatchRun(held,EmulatorDailyRunner.digest(store.wire),window);run.persist(files,EmulatorAndroidDailyStore::syncDirectory,mode);}
            else run=EmulatorDevelopmentBatchRun.read(files,first);
            run.reserve(files,key,location.epoch,admissionNow,EmulatorAndroidDailyStore::syncDirectory,mode);
            port.startPreparedInventory(held,inventory);
        }
        }catch(Exception rejected){
            // Code locations only: no message, binding, task or coordinates.
            for(StackTraceElement frame:rejected.getStackTrace()){
                String name=frame.getClassName();
                if(name.equals("dev.ody.pikmincontrol.EmulatorDailyBatchWork")||name.equals("dev.ody.pikmincontrol.DispatchBatchService"))
                    android.util.Log.w("PccDailyBatchStart","Rejected at "+name.substring(name.lastIndexOf('.')+1)+"."+frame.getMethodName()+":"+frame.getLineNumber());
            }
            throw rejected;
        }
        batchText="三類收工批次已啟動";
    }
    static String formalClosingFirst(boolean daytime,LocalDate day){return daytime?day+":e23h2:batch-1":day.minusDays(1)+":n22:batch-1";}
    static boolean priorBatchResolved(String retained,String outcome){return retained.isEmpty()||Arrays.asList("COMPLETE","COMPLETE_ALL_CLAIMS").contains(outcome);}
    // A new UI session may publish a speed/login warning after the first MAP.
    // Time-only startup settling: never reads GPS/HC, closes an unknown result,
    // extends an existing transaction or stops any other lane.
    private final long createdElapsed=SystemClock.elapsedRealtime();
    static boolean plantingStartReady(long created,long now){return created>=0&&now>=created&&now-created>=60000;}
    static StepSettings dailySteps(StepSettings saved){return StepSettings.parse(500000,360,240,saved.ratePerMinute,saved.ratePreset,saved.intervalMs);}
    static boolean retentionWindow(int minute,boolean sunday,boolean enabled){return minute>=255&&minute<300&&(!enabled||!sunday||minute>=260);}
    private boolean retentionSafe()throws Exception{
        return !closed&&store.enabled()&&store.drained()&&helpersOff&&plantClosed&&"GPS已暫停".equals(gpsText)&&
            !WorkStatus.stoppingAll&&!SetupDeployment.pending(app)&&!CoreUpdater.pending(app)&&!UpdateInstaller.pending(app)&&!CoreInstallWizard.preparing(app)&&
            !DispatchBatchService.isRunning()&&!FlowerFarmService.isRunning()&&(!GpsWorkLock.anyHeld(app)||GpsWorkLock.heldBy(app,EmulatorSavedJoystickPort.OWNER))&&
            minute(System.currentTimeMillis())>=255&&minute(System.currentTimeMillis())<300;
    }
    void retentionTick()throws Exception{
        long now=System.currentTimeMillis();ZonedDateTime local=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        boolean enabled=app.getSharedPreferences("daily_retention",0).getBoolean("enabled",false),sunday=local.getDayOfWeek()==DayOfWeek.SUNDAY;
        if(!retentionWindow(minute(now),sunday,enabled)||!retentionSafe())return;
        boolean remove=enabled&&sunday;String key=local.toLocalDate()+":"+(remove?"archive-delete":"dry-run");
        if(key.equals(store.get("retention.attempt","")))return;
        Identity identity=game();String current=EmulatorDailyRunner.digest(identity.key());
        store.put("retention.attempt",key); // once per rest/day even after an I/O fault
        try{
            int count=EmulatorDailyUiStore.retain(app.getFilesDir().toPath().resolve("daily-ui"),current,now,remove,
                EmulatorAndroidDailyStore::syncDirectory,()->retentionSafe()&&(!remove||app.getSharedPreferences("daily_retention",0).getBoolean("enabled",false))&&identity.key().equals(game().key()));
            store.put("retention.summary",(remove?"已核驗封存並清理舊自動截圖：":"清理候選：")+count+" 張；近期／目前身分／未決／收據均保留；不是全量盤點。");
        }catch(Exception failure){store.put("retention.summary","清理未完整確認；未核驗原件保留，已完成的單檔封存保留；本休息日不重試。");throw failure;}
    }
    void capacityTick()throws Exception{
        if(closed||!store.current()||!store.enabled())return;
        String summary="容量未確認；未改變任何功能開關";
        try{
            Identity i=game();
            String apk=EmulatorDailyUiStore.sha(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(app.getApplicationInfo().sourceDir)));
            EmulatorDailyRunner.Binding held=new EmulatorDailyRunner.Binding("127.0.0.1:16448",i.boot,i.pid,i.account,i.epoch,i.pair.sha,apk,EmulatorDailyRunner.digest(store.plan.encode()),19);
            EmulatorDailyResources sample=EmulatorDailyResources.read(app,held);
            if(!i.key().equals(game().key()))throw new IllegalStateException("Capacity session changed");
            summary=sample.capacitySummary(System.currentTimeMillis());
        }catch(Exception unavailable){/* Unknown is not zero, and not a global pause. */}
        if(closed||!store.current()||!store.enabled())return;
        if(!store.journal.edit().putString("capacity.summary",summary).putLong("capacity.at",System.currentTimeMillis()).commit())
            throw new IllegalStateException("Capacity summary not saved");
    }
    public void close(){closed=true;EmulatorSavedJoystickPort p=gps;if(p!=null)p.close();}
}

package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.time.LocalDate;

/** Correlation and independently checked closure for a future MuMu daily route,
 * never readiness authority.
 * A unique service claim precedes ANY original control effect. A retained claim
 * blocks replay; only this live service instance may report admission or stop.
 * No reconstruction/adoption from a PID, generic running flag or saved claim. */
final class EmulatorDailyRouteWork {
    static final String EXTRA="pcc_daily_route_v1";
    final EmulatorDailyRunner.Binding binding;
    final String key;
    final long locationEpoch,created;
    static final long DEVELOPMENT_LIMIT=15*60*1000L;
    static final long CITY_DEVELOPMENT_LIMIT=25*60*1000L;
    final boolean development;
    final boolean cityDevelopment;
    private volatile String claim;
    private volatile boolean admitted;
    enum StartStage { IDENTITY, WINDOW, ROUTE, RESOURCES, LOCATION, IDENTITY_FINAL, WINDOW_FINAL, GPS_LOCK, LOCAL_SAVE, DISPATCH }
    private StartStage startStage=StartStage.IDENTITY;
    private boolean dispatchAttempted;
    static final String NOT_DISPATCHED="v1-not-dispatched\t";
    void beforeGpsLock(){startStage=StartStage.GPS_LOCK;}
    void beforeLocalSave(){startStage=StartStage.LOCAL_SAVE;}
    void beforeServiceDispatch(){startStage=StartStage.DISPATCH;dispatchAttempted=true;}
    boolean serviceDispatchAttempted(){return dispatchAttempted;}
    String startStage(){return startStage.name();}
    private EmulatorDailyRouteClosure closure;
    private EmulatorClosedPlantingRound finalBefore;
    private String unstartedBefore;
    private long unstartedBeforeAt;
    private final StockCapture stocks;
    EmulatorDailyRouteWork(EmulatorDailyRunner.Binding binding,String key,long locationEpoch,long created) {
        this(binding,key,locationEpoch,created,false);
    }
    static EmulatorDailyRouteWork development(EmulatorDailyRunner.Binding binding,String key,long locationEpoch,long created){
        return new EmulatorDailyRouteWork(binding,key,locationEpoch,created,true);
    }
    static EmulatorDailyRouteWork cityDevelopment(EmulatorDailyRunner.Binding binding,String key,long locationEpoch,long created,HarvestRoute route){
        if(route==null||route.estimatedSeconds()>20*60)bad(); // 20-minute route + five-minute bounded setup/rotation margin
        java.util.List<FlowerFarmService.Point> points=new java.util.ArrayList<>();for(HarvestRoute.Point p:route.points)points.add(new FlowerFarmService.Point(p.lat,p.lng));
        if(binding==null||!binding.routeSha.equals(routeSha(points)))bad();
        return new EmulatorDailyRouteWork(binding,key,locationEpoch,created,true,true);
    }
    private EmulatorDailyRouteWork(EmulatorDailyRunner.Binding binding,String key,long locationEpoch,long created,boolean development) {
        this(binding,key,locationEpoch,created,development,false);
    }
    private EmulatorDailyRouteWork(EmulatorDailyRunner.Binding binding,String key,long locationEpoch,long created,boolean development,boolean cityDevelopment) {
        if(binding==null || key==null || locationEpoch<=0 || created<=0)bad();
        String day=EmulatorDailyPlan.workDay(created,!development&&EmulatorDailyPlan.overnightKey(key)).toString();
        // Production slots remain bounded. Development attempts have their own
        // unique IDs; never erase/reuse a retained claim just to run another test.
        String suffix=development?"(?:[0-9]|1[0-2]|[0-9a-f]{32})":"(?:[0-9]|1[0-2])";
        if(development?!key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:development-route-"+suffix):!routeKey(key)||key.contains(":development-route-"))bad();
        if(!key.startsWith(day+":"))bad();
        this.binding=binding;this.key=key;this.locationEpoch=locationEpoch;this.created=created;
        this.development=development;
        this.cityDevelopment=cityDevelopment;
        stocks=new StockCapture(binding);
    }
    String encode(){return (cityDevelopment?"v3-development-city":development?"v2-development":"v1")+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+locationEpoch+"\t"+created+"\tend\n";}
    static EmulatorDailyRouteWork decode(String wire) {
        if(wire==null || wire.length()>2048 || !wire.endsWith("\n") || wire.indexOf('\r')>=0)bad();
        String[] f=wire.substring(0,wire.length()-1).split("\t",-1);
        if(f.length!=6 || !(f[0].equals("v1")||f[0].equals("v2-development")||f[0].equals("v3-development-city")) || !f[5].equals("end"))bad();
        EmulatorDailyRouteWork result=new EmulatorDailyRouteWork(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),
                f[2],Long.parseLong(f[3]),Long.parseLong(f[4]),!f[0].equals("v1"),f[0].equals("v3-development-city"));
        if(!wire.equals(result.encode()))bad();return result;
    }
    static EmulatorDailyRouteWork from(Intent intent) {
        return intent.hasExtra(EXTRA)?decode(intent.getStringExtra(EXTRA)):null;
    }
    void put(Intent intent){intent.putExtra(EXTRA,encode());}
    boolean matches(EmulatorDailyRunner.Binding held,String heldKey){return binding.same(held) && key.equals(heldKey);}
    static String routeSha(java.util.List<FlowerFarmService.Point> points) {
        if(points==null || points.size()<2 || points.size()>2000)bad();
        StringBuilder text=new StringBuilder("daily-route-v1\n");
        for(FlowerFarmService.Point p:points){
            if(p==null || !FarmLocation.point(p.latitude,p.longitude))bad();
            text.append(Double.toString(p.latitude)).append('\t').append(Double.toString(p.longitude)).append('\n');
        }
        return EmulatorDailyRunner.digest(text.toString());
    }
    void requireStart(Context context,java.util.List<FlowerFarmService.Point> points)throws Exception {
        startStage=StartStage.IDENTITY;
        requireIdentity(context);
        startStage=StartStage.WINDOW;
        long now=System.currentTimeMillis();
        if(now<created || now-created>90000 || !withinRouteWindow(now))
            rejectStart("ticket-window");
        startStage=StartStage.ROUTE;
        if(!binding.routeSha.equals(routeSha(points)) || binding.kmh!=HarvestRoute.SPEED_KMH)
            rejectStart("route-binding");
        if(cityDevelopment){
            double meters=0;HarvestRoute.Point prior=null;
            for(FlowerFarmService.Point p:points){HarvestRoute.Point next=new HarvestRoute.Point(p.latitude,p.longitude);if(prior!=null)meters+=HarvestRoute.distance(prior,next);prior=next;}
            if(Math.ceil(meters/(HarvestRoute.SPEED_KMH/3.6))>20*60)rejectStart("city-route-budget");
        }
        startStage=StartStage.RESOURCES;
        EmulatorDailyResources.require(context,binding,true,true,false);
        startStage=StartStage.LOCATION;
        FarmLocation fix=GpsModuleGateway.farmLocation(context);
        if(!fix.valid || !Integer.toString(binding.pid).equals(fix.pid))rejectStart("location-ready");
        if(fix.epoch!=locationEpoch)rejectStart("location-epoch");
        // Boot/PID/account/session identity is independent of the step counter.
        // Neither this ticket nor its captured fields become ready authority.
        startStage=StartStage.IDENTITY_FINAL;
        requireIdentity(context);
        startStage=StartStage.WINDOW_FINAL;
        if(!withinRouteWindow(System.currentTimeMillis()))rejectStart("final-window");
    }
    /** Only the synchronous caller BEFORE any Android service dispatch may
     * create this terminal fence. It is not native closure or route success.
     * CREATE_NEW in the ORIGINAL claim namespace races safely with a real
     * service claim: exactly one wins, and no later service can claim/start. */
    void notDispatched(Context context)throws Exception {
        if(dispatchAttempted||claim!=null||admitted||startStage==StartStage.DISPATCH)bad();
        Path files=EmulatorAndroidDailyStore.files(context);
        if(Files.exists(files.resolve("daily-route-results").resolve(EmulatorDailyRunner.digest(key)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
        persistClaim(files.resolve("daily-route-links"),key,notDispatchedWire(startStage,System.currentTimeMillis()),EmulatorAndroidDailyStore::syncDirectory);
    }
    String notDispatchedWire(StartStage stage,long at) {
        if(stage==null||stage==StartStage.DISPATCH||dispatchAttempted||claim!=null||admitted||at<created)bad();
        String body=NOT_DISPATCHED+EmulatorDailyRunner.base64(encode())+"\t"+stage.name()+"\t"+at+"\tno-service-dispatch\tend\n";
        return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";
    }
    static EmulatorDailyRouteWork validateNotDispatched(String wire,EmulatorDailyRunner.Binding held,String key,long now) {
        if(wire==null||held==null||now<=0||wire.length()>3072||wire.indexOf('\r')>=0)bad();
        String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] f=rows[0].split("\t",-1);
        if(f.length!=6||!f[0].equals("v1-not-dispatched")||!f[4].equals("no-service-dispatch")||!f[5].equals("end"))bad();
        EmulatorDailyRouteWork work=decode(EmulatorDailyRunner.unbase64(f[1]));
        long at=Long.parseLong(f[3]);
        if(!work.matches(held,key)||at>now||!wire.equals(work.notDispatchedWire(StartStage.valueOf(f[2]),at)))bad();
        return work;
    }
    /** Fixed non-private diagnostics; never changes admission or freshness. */
    private static void rejectStart(String stage) {
        throw new IllegalStateException("Daily route start rejected: "+stage+"; preserve uncertain claim");
    }
    boolean withinRouteWindow(long now) {
        if(now<created)return false;
        if(development)return now-created<(cityDevelopment?CITY_DEVELOPMENT_LIMIT:DEVELOPMENT_LIMIT);
        java.time.ZonedDateTime local=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        int minute=local.getHour()*60+local.getMinute();
        return EmulatorDailyPlan.keyDay(key,now)&&EmulatorDailyPlan.routeWindow(now,EmulatorDailyPlan.overnightKey(key));
    }
    void requireIdentity(Context context)throws Exception {
        requireIdentity(context,binding);
    }
    static void requireIdentity(Context context,EmulatorDailyRunner.Binding held)throws Exception {
        requireIdentity(context,held,null);
    }
    /** Fixed phase labels only; observation cannot authorize or skip a gate. */
    enum IdentityStage {
        ENVIRONMENT("模擬器環境或套件身分"), PAIR("APK與核心檔案配對"),
        CORE("核心就緒與新鮮回報"), COUNTER("帳號、程序與工作階段");
        final String label;
        IdentityStage(String label){this.label=label;}
    }
    interface IdentityProgress {void stage(IdentityStage stage);}
    private static final String GAME_COMPONENT="com.nianticlabs.pikmin/com.nianticproject.ichigo.IchigoUnityPlayerActivity";
    /** UI preparation only. A missing/different process fails BEFORE am start.
     * Bringing an existing task forward is not readiness; callers still run
     * all original identity/resource/ownership gates after this returns. */
    static String foregroundCommand(EmulatorDailyRunner.Binding held){
        if(held==null)bad();
        String guard="test \"$(pidof com.nianticlabs.pikmin)\" = "+held.pid+
            " && test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+held.boot;
        return "set -e; "+guard+"; am start --activity-reorder-to-front -n '"+GAME_COMPONENT+"'; "+guard;
    }
    static void foregroundExistingGame(Context context,EmulatorDailyRunner.Binding held)throws Exception {
        EmulatorRuntime.require();
        if(context==null||held==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))bad();
        CorePair pair=CoreUpdater.pair(context);
        if(!held.coreSha.equals(pair.sha)||!held.apkSha.equals(hashApk(context)))bad();
        CoreUpdater.requireFiles(context,pair);
        BoundedCommand.Result result=BoundedCommand.root(foregroundCommand(held),4,4096);
        if(!GamePidRecoveryLaunch.accepted(result,GAME_COMPONENT))bad();
        // One bounded settle, not a retry loop, timeout extension, heartbeat
        // synthesis or loader trigger. The original fresh checks follow.
        Thread.sleep(1500);
    }
    static void requireIdentity(Context context,EmulatorDailyRunner.Binding held,IdentityProgress progress)throws Exception {
        CorePair pair=requireIdentityBase(context,held,progress);
        EmulatorDailySessionIdentity.require(context,held,pair);
    }
    // Explicit step/nursery diagnostics only. GPS identity must not call this.
    static EmulatorGameDailySteps.Observation requireIdentityCounter(Context context,EmulatorDailyRunner.Binding held,IdentityProgress progress)throws Exception {
        CorePair pair=requireIdentityBase(context,held,progress);
        return EmulatorRunnerCounterGateway.read(context,held,pair);
    }
    private static CorePair requireIdentityBase(Context context,EmulatorDailyRunner.Binding held,IdentityProgress progress)throws Exception {
        if(progress!=null)progress.stage(IdentityStage.ENVIRONMENT);
        EmulatorRuntime.require();
        if(held==null || context==null || !"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))bad();
        if(progress!=null)progress.stage(IdentityStage.PAIR);
        CorePair pair=CoreUpdater.pair(context);
        if(!held.coreSha.equals(pair.sha) || !held.apkSha.equals(hashApk(context)))bad();
        if(progress!=null)progress.stage(IdentityStage.CORE);
        CoreUpdater.requireReady(context);
        if(progress!=null)progress.stage(IdentityStage.COUNTER);
        return pair;
    }
    void claim(Context context,WorkCheckpoint checkpoint,FarmRunReport report,RouteSession session,
            java.util.List<FlowerFarmService.Point> points)throws Exception {
        if(claim!=null || admitted || checkpoint==null || report==null || session==null ||
                !"flower-farm".equals(checkpoint.owner) || !binding.account.equals(checkpoint.account) ||
                !Integer.toString(binding.pid).equals(session.pid) || !binding.account.equals(session.expectedAccount) || session.owned)
            rejectStart("claim-context");
        requireStart(context,points);
        String body=claimWire(checkpoint.id,report.id,session.token);
        Path files=EmulatorAndroidDailyStore.files(context).toAbsolutePath().normalize();
        persistClaim(files.resolve("daily-route-links"),key,body,EmulatorAndroidDailyStore::syncDirectory);
        claim=body; // Set only after the independent readback/durability barrier.
    }
    String claimWire(String checkpoint,String report,String token) {
        if(!uuid(checkpoint)||!uuid(report)||!uuid(token))bad();
        String body="v1\t"+EmulatorDailyRunner.base64(encode())+"\t"+checkpoint+"\t"+report+"\t"+token+"\tintent-not-admitted\tend\n";
        return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";
    }
    void admitted(Context context,RouteSession session)throws Exception {
        if(claim==null || admitted || session==null || !session.owned || !Integer.toString(binding.pid).equals(session.pid) ||
                !binding.account.equals(session.account) || !claim.contains("\t"+session.token+"\tintent-not-admitted\t"))bad();
        requireIdentity(context);
        String[] f=claim.split("\n",-1)[0].split("\t",-1);
        closure=new EmulatorDailyRouteClosure(binding,key,EmulatorDailyRunner.digest(claim),f[2],f[3],session.token);
        admitted=true;
        // A claim alone is never a durable completion receipt. Completion
        // requires the separate exact closed chain and original cleanup.
    }
    EmulatorDailyRunner.Observation observation(EmulatorDailyRunner.Binding held,String heldKey) {
        if(!matches(held,heldKey))bad();
        return new EmulatorDailyRunner.Observation(binding,key,admitted?EmulatorDailyRunner.Evidence.ADMITTED:EmulatorDailyRunner.Evidence.UNKNOWN);
    }
    void roundStarted(RouteSession session,long requested) {
        if(!admitted||closure==null||session==null||!session.owned||!binding.account.equals(session.account)||!Integer.toString(binding.pid).equals(session.pid))bad();
        closure.started(session.token,requested);
        // Best-effort observation must never change original route cleanup.
        try{stocks.started(session.token,requested);}catch(Exception incomplete){captureFailed=true;}
    }
    private boolean captureFailed;
    void captureStock(RouteSession session,RouteObservation observation,long requested){
        if(captureFailed)return;
        try{
            if(!admitted||session==null||!session.owned||observation==null||!Integer.toString(binding.pid).equals(session.pid)||
               !binding.account.equals(session.account)||requested<=0)bad();
            stocks.observeBound(observation.ownedCapabilities,observation.ownedJournal,observation.ownedPetals,
                session.pid,session.account,session.token,requested,System.currentTimeMillis());
        }catch(Exception incomplete){captureFailed=true;}
    }
    String[] capturedStocks(){if(captureFailed)bad();return stocks.originals();}
    /** Original bounded packets, never readiness or reconstructed service state. */
    static final class StockCapture {
        final EmulatorDailyRunner.Binding binding;
        private String firstToken,nextToken,initial,low,restarted,selected;
        private long requested,last,nativeStart;
        StockCapture(EmulatorDailyRunner.Binding binding){if(binding==null)bad();this.binding=binding;}
        void observeBound(String capabilities,String journal,String packet,String pid,String account,
                String token,long requested,long now){
            EmulatorOwnedStockObservation.Result result=EmulatorOwnedStockObservation.observe(capabilities,journal,packet,
                pid,account,token,requested,now);
            // UNKNOWN can pause gameplay, but never fills a low-stock witness.
            // Post-PAUSE reads use this same exact native binding/freshness gate.
            if(result.captured<=0||(!result.state.equals("available")&&!result.state.equals("low")&&
                !result.state.equals("low-stop-pending")&&!result.state.equals("low-stopped")))return;
            observe(token,packet,now);
        }
        void started(String token,long requested){
            if(!uuid(token)||requested<=0)bad();
            if(restarted!=null)return; // Keep the first complete pair through later legitimate rotations.
            if(firstToken==null)firstToken=token;
            else if(nextToken==null&&!firstToken.equals(token))nextToken=token;
            else bad();
            this.requested=requested;last=0;nativeStart=0;
        }
        void observe(String token,String packet,long now){
            if(restarted!=null&&token!=null&&!token.equals(nextToken))return;
            if(firstToken==null||token==null||!token.equals(nextToken==null?firstToken:nextToken)||packet==null||packet.length()>32768)bad();
            String[] h=packet.split("\n",-1)[0].split("\t",-1);if(h.length!=11)bad();
            // Game-start time is an exact positive correlation value, not the
            // App/native wall clock. observeBound still checks native sent time
            // against requested/now; the packet reader checks capture freshness.
            long nativeStarted=Long.parseLong(h[6]);if(nativeStarted<=0||(nativeStart!=0&&nativeStarted!=nativeStart))bad();
            EmulatorPetalRotation.Snapshot snapshot=EmulatorOwnedPetalReader.read(packet,binding.pid,binding.sessionEpoch,
                binding.account,token,nativeStarted,h[7],now);
            if(!snapshot.complete||snapshot.captured<last)bad();
            EmulatorPetalRotation.Petal chosen=null;
            for(EmulatorPetalRotation.Petal p:snapshot.petals)if(p.id.equals(h[7]))chosen=p;
            if(chosen==null)bad();last=snapshot.captured;nativeStart=nativeStarted;
            if(nextToken==null){
                if(initial==null&&chosen.stock>EmulatorPetalRotation.RESERVE){initial=packet;selected=chosen.id;}
                else if(initial!=null&&low==null&&chosen.id.equals(selected)&&chosen.stock<=EmulatorPetalRotation.RESERVE&&
                        Long.parseLong(initial.split("\t",-1)[1])<snapshot.captured)low=packet;
            }else if(initial!=null&&low!=null&&restarted==null&&!chosen.id.equals(selected)&&chosen.stock>EmulatorPetalRotation.RESERVE)
                restarted=packet;
        }
        String[] originals(){
            if(initial==null||low==null||restarted==null)
                throw new IllegalStateException("Original stock capture incomplete [initial="+(initial!=null)
                    +", low="+(low!=null)+", restarted="+(restarted!=null)+"]");
            return new String[]{initial,low,restarted};
        }
    }
    private EmulatorClosedPlantingRound closedRound(Context context,RouteSession session,long requested)throws Exception {
        if(session==null||!binding.account.equals(session.account)||!Integer.toString(binding.pid).equals(session.pid))bad();
        requireIdentity(context);
        String files="/data/user/0/com.nianticlabs.pikmin/files/";
        String raw=GpsModuleGateway.farmRead(ProbeSnapshot.command("pidof com.nianticlabs.pikmin","cat /proc/sys/kernel/random/boot_id",
            "head -c 2049 "+files+"core_capabilities.tsv","head -c 2049 "+files+"emulator_planting_transaction.tsv",
            "head -c 2049 "+files+"planting_control_status.tsv","cat /proc/sys/kernel/random/boot_id","pidof com.nianticlabs.pikmin"));
        EmulatorClosedPlantingRound p=EmulatorClosedPlantingRound.read(EmulatorOwnedPetalGateway.sections(raw,7),session.pid,session.account,session.token,requested,System.currentTimeMillis());
        if(!binding.boot.equals(p.boot)||binding.sessionEpoch!=p.epoch)bad();return p;
    }
    void priorClosed(Context context,RouteSession prior,long requested,RouteSession next)throws Exception {
        if(!admitted||closure==null||prior==null||prior.owned||next==null||next.owned||RouteSession.pending(context))bad();
        EmulatorClosedPlantingRound p=closedRound(context,prior,requested);
        closure.closed(prior.token,requested,p.boot,p.epoch,p.started,System.currentTimeMillis());closure.next(next.token);
    }
    private EmulatorClosedPlantingRound closedWhileOwned(Context context,RouteSession session,long requested)throws Exception {
        if(!admitted||closure==null||session==null||!session.owned||!"hold".equals(session.phase))bad();
        return EmulatorDailyStopGate.started(()->{session.requireStatus(true);},
            ()->closedRound(context,session,requested));
    }
    boolean stopConfirmed(Context context,RouteSession session,long requested)throws Exception {
        if(!admitted||closure==null||session==null||!session.owned)bad();
        // No started native transaction exists on this branch. Keep the old
        // unstarted stop/snapshot contract, never adopt an unrelated round.
        if(closure.currentUnstarted())return session.stopped();
        closedWhileOwned(context,session,requested);return true;
    }
    void beforeFinalClose(Context context,RouteSession session,long requested)throws Exception {
        if(!admitted||closure==null||finalBefore!=null||session==null||!session.owned)bad();
        if(closure.currentUnstarted()){
            if(!session.stopped())bad();
            if(unstartedBefore!=null||!closure.unusedToken().equals(session.token))bad();
            unstartedBefore=unstartedSnapshot(context,session,false);
            unstartedBeforeAt=System.currentTimeMillis();
            EmulatorUnstartedRouteOutcome.snapshot(unstartedBefore,binding,session.token,unstartedBeforeAt,false);
            return;
        }
        finalBefore=closedWhileOwned(context,session,requested);
    }
    private String unstartedSnapshot(Context context,RouteSession session,boolean after)throws Exception{
        requireIdentity(context);
        String files="/data/user/0/com.nianticlabs.pikmin/files/";
        String owner="/data/local/tmp/pcc-route-owner",lease="/data/local/tmp/pikmin-route-lease.tsv";
        String marker=after?"test ! -e "+owner+" && test ! -L "+owner+" && test ! -e "+lease+" && test ! -L "+lease+" && printf 'absent\\n'":
            "test -f "+owner+" && test ! -L "+owner+" && test \"$(cat "+owner+")\" = "+session.token+" && test -f "+lease+" && test ! -L "+lease+" && printf 'held\\n'";
        return GpsModuleGateway.farmRead(ProbeSnapshot.command("pidof com.nianticlabs.pikmin","cat /proc/sys/kernel/random/boot_id",
            "head -c 2049 "+files+"core_capabilities.tsv","head -c 2049 "+files+"emulator_planting_transaction.tsv",
            "head -c 2049 "+files+"planting_control_status.tsv","head -c 2049 "+files+"route_status.tsv",marker,
            "cat /proc/sys/kernel/random/boot_id","pidof com.nianticlabs.pikmin"));
    }
    void complete(Context context,RouteSession session,long requested,FarmRunReport report,String terminal,String errors,String runError)throws Exception {
        if(unstartedBefore!=null){
            if(!admitted||closure==null||!closure.currentUnstarted()||session.owned||RouteSession.pending(context)||
                GpsWorkLock.heldBy(context,GpsWorkLock.FLOWER_FARM)||report==null||!report.route||!errors.isEmpty()||
                !closure.report.equals(report.id)||report.ended<=0||!terminal.equals(report.terminal))bad();
            String after=unstartedSnapshot(context,session,true);long afterAt=System.currentTimeMillis();
            String wire=EmulatorUnstartedRouteOutcome.create(binding,key,EmulatorDailyRunner.digest(claim),closure.checkpoint,report.id,session.token,
                terminal,report.total,report.completed,report.ended,closure.rounds,unstartedBefore,unstartedBeforeAt,after,afterAt,System.currentTimeMillis());
            persistResult(EmulatorAndroidDailyStore.files(context).resolve("daily-route-results"),key,wire,EmulatorAndroidDailyStore::syncDirectory,
                path->Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")));
            return; // CLOSED_SKIPPED, never route completion or harvesting proof
        }
        if(!admitted||closure==null||finalBefore==null||session.owned||RouteSession.pending(context)||
           GpsWorkLock.heldBy(context,GpsWorkLock.FLOWER_FARM)||report==null||!report.route||!errors.isEmpty()||
           !closure.report.equals(report.id)||report.ended<=0||!terminal.equals(report.terminal))bad();
        boolean closedError=!runError.isEmpty();
        if(closedError&&(!terminal.equals("error")||report.completed>=report.total))bad();
        EmulatorClosedPlantingRound p=closedRound(context,session,requested);
        if(!p.boot.equals(finalBefore.boot)||p.epoch!=finalBefore.epoch||p.started!=finalBefore.started)bad();
        closure.closed(session.token,requested,p.boot,p.epoch,p.started,System.currentTimeMillis());
        String wire=closedError?closure.finishIncomplete(terminal,report.total,report.completed,System.currentTimeMillis()):
            closure.finish(terminal,report.total,report.completed,System.currentTimeMillis());
        persistResult(EmulatorAndroidDailyStore.files(context).resolve("daily-route-results"),key,wire,EmulatorAndroidDailyStore::syncDirectory,
            path->Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")));
        if(closedError)return; // safe closed error only; never success, stock acceptance or replay
        // Optional controlled-test sample follows original cleanup and durable
        // closure. Missing rotation evidence must not cancel completed gameplay.
        try {
            StockArtifact artifact=StockArtifact.create(this,claim,wire,capturedStocks(),System.currentTimeMillis());
            requireIdentity(context);
            Path captureRoot=EmulatorAndroidDailyStore.files(context).resolve("daily-route-captures");
            if(Files.exists(captureRoot,LinkOption.NOFOLLOW_LINKS))StockArtifact.requirePrivateRoot(context);
            StockArtifact.persist(captureRoot,artifact,
                EmulatorAndroidDailyStore::syncDirectory,(path,directory)->Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(directory?"rwx------":"rw-------")));
            StockArtifact.requirePrivate(context,artifact);
            requireIdentity(context);
        }catch(Exception incomplete){android.util.Log.w("PccDailyCapture","特殊輪替診斷樣本未完整保存；不影響每日步數驗收或既有路線閉合",incomplete);}
    }
    /** Private raw subset, NOT daily acceptance or authority to resume effects. */
    static final class StockArtifact {
        static final int MAX_BYTES=147456;
        final EmulatorDailyRouteWork work;
        final long capturedAt;
        private final String wire,claim,closure;
        private final String[] stocks;
        private StockArtifact(EmulatorDailyRouteWork work,long at,String wire,String[] payload){this.work=work;capturedAt=at;this.wire=wire;claim=payload[0];closure=payload[1];stocks=new String[]{payload[2],payload[3],payload[4]};}
        String encode(){return wire;}
        String[] originals(){return new String[]{claim,closure,stocks[0],stocks[1],stocks[2]};}
        static StockArtifact create(EmulatorDailyRouteWork work,String claim,String closure,String[] stocks,long at){
            if(work==null||stocks==null||stocks.length!=3)bad();
            StringBuilder body=new StringBuilder("v1\t"+EmulatorDailyRunner.base64(work.encode())+"\t"+at+"\tend\n");
            String[] payload={claim,closure,stocks[0],stocks[1],stocks[2]};
            for(int i=0;i<payload.length;i++){if(payload[i]==null)bad();body.append("p\t").append(i).append('\t').append(EmulatorDailyRunner.base64(payload[i])).append('\n');}
            String raw=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
            return decode(raw,work.binding,work.key,at);
        }
        static StockArtifact decode(String raw,EmulatorDailyRunner.Binding held,String key,long now){
            if(raw==null||raw.length()>MAX_BYTES||raw.indexOf('\r')>=0||held==null)bad();
            String[] rows=raw.split("\n",-1);if(rows.length!=8||!rows[7].isEmpty())bad();
            StringBuilder body=new StringBuilder();for(int i=0;i<6;i++)body.append(rows[i]).append('\n');
            if(!rows[6].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
            String[] h=rows[0].split("\t",-1);if(h.length!=4||!h[0].equals("v1")||!h[3].equals("end"))bad();
            EmulatorDailyRouteWork work=EmulatorDailyRouteWork.decode(EmulatorDailyRunner.unbase64(h[1]));
            if(!h[1].equals(EmulatorDailyRunner.base64(work.encode())))bad();
            long at=number(h[2]);if(at<work.created||at>now||!work.matches(held,key))bad();
            String[] payload=new String[5];
            for(int i=0;i<5;i++){
                String[] f=rows[i+1].split("\t",-1);
                if(f.length!=3||!f[0].equals("p")||!f[1].equals(Integer.toString(i)))bad();
                payload[i]=EmulatorDailyRunner.unbase64(f[2]);if(!EmulatorDailyRunner.base64(payload[i]).equals(f[2]))bad();
            }
            String[] cr=payload[0].split("\n",-1);
            if(cr.length!=3||!cr[2].isEmpty()||!cr[1].equals("sha256\t"+EmulatorDailyRunner.digest(cr[0]+"\n")))bad();
            String[] c=cr[0].split("\t",-1);
            if(c.length!=7||!payload[0].equals(work.claimWire(c[2],c[3],c[4])))bad();
            EmulatorDailyRouteClosure.validateClaim(payload[1],held,key,EmulatorDailyRunner.digest(payload[0]),c[2],c[3],c[4]);
            String[] closed=payload[1].split("\n",-1),ch=closed[0].split("\t",-1);
            if(closed.length<5||!ch[6].equals("completed")||number(ch[9])>at)bad();
            String[] first=closed[1].split("\t",-1),next=closed[2].split("\t",-1);
            StockCapture capture=new StockCapture(held);capture.started(first[1],number(first[2]));
            capture.observe(first[1],payload[2],within(payload[2],first));
            capture.observe(first[1],payload[3],within(payload[3],first));
            capture.started(next[1],number(next[2]));capture.observe(next[1],payload[4],within(payload[4],next));
            String[] retained=capture.originals();for(int i=0;i<3;i++)if(!retained[i].equals(payload[i+2]))bad();
            return new StockArtifact(work,at,raw,payload);
        }
        private static long within(String packet,String[] round){
            String[] h=packet.split("\n",-1)[0].split("\t",-1);if(h.length!=11)bad();
            long captured=number(h[1]);if(number(h[6])!=number(round[3])||captured<number(round[3])||captured>number(round[4]))bad();
            return captured; // Historical freshness uses original capture time, not current time.
        }
        interface Mode{void apply(Path path,boolean directory)throws Exception;}
        static void persist(Path root,StockArtifact artifact,EmulatorAndroidDailyStore.DirectorySync sync,Mode mode)throws Exception{
            if(artifact==null||sync==null||mode==null)bad();root=directory(root,false);
            Path leaf=root.resolve(EmulatorDailyRunner.digest(artifact.work.key)+".tsv");
            if(Files.exists(leaf,LinkOption.NOFOLLOW_LINKS))bad();
            if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(root);mode.apply(root,true);}
            directory(root,true);sync.sync(root.getParent());sync.sync(root);
            byte[] bytes=artifact.encode().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(bytes.length>MAX_BYTES)bad();
            try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                mode.apply(leaf,false);ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
            }
            sync.sync(root);if(!artifact.encode().equals(read(root,artifact.work.binding,artifact.work.key,artifact.capturedAt).encode()))bad();
        }
        static StockArtifact read(Path root,EmulatorDailyRunner.Binding held,String key,long now)throws Exception{
            root=directory(root,true);Path leaf=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");
            String raw=boundedPrivateRead(leaf,MAX_BYTES);StockArtifact artifact=decode(raw,held,key,now);
            directory(root,true);if(!raw.equals(boundedPrivateRead(leaf,MAX_BYTES)))bad();return artifact;
        }
        /** Actual private raw read/durability reconciliation. Still no HC or ready authority. */
        static StockArtifact read(Context context,EmulatorDailyRunner.Binding held,String key)throws Exception{
            requireIdentity(context,held);
            Path files=EmulatorAndroidDailyStore.files(context),root=files.resolve("daily-route-captures");
            StockArtifact artifact=read(root,held,key,System.currentTimeMillis());requirePrivate(context,artifact);
            // The saved subset must match the independently retained originals,
            // not just a checksum manufactured in the capture namespace.
            if(!artifact.claim.equals(boundedPrivateRead(files.resolve("daily-route-links").resolve(EmulatorDailyRunner.digest(key)+".tsv"),3072))||
               !artifact.closure.equals(boundedPrivateRead(files.resolve("daily-route-results").resolve(EmulatorDailyRunner.digest(key)+".tsv"),4096)))bad();
            completed(context,held,key); // Original closure durability/identity reconciliation.
            Path leaf=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");
            try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){out.force(true);}
            EmulatorAndroidDailyStore.syncDirectory(files);EmulatorAndroidDailyStore.syncDirectory(root);
            if(!artifact.encode().equals(read(root,held,key,System.currentTimeMillis()).encode()))bad();
            requirePrivate(context,artifact);requireIdentity(context,held);return artifact;
        }
        private static Path directory(Path root,boolean exists){
            if(root==null)bad();root=root.toAbsolutePath().normalize();
            if(root.getFileName()==null||!root.getFileName().toString().equals("daily-route-captures")||root.getParent()==null||
               root.getParent().getFileName()==null||!root.getParent().getFileName().toString().equals("files"))bad();
            for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
            if(!Files.isDirectory(root.getParent(),LinkOption.NOFOLLOW_LINKS)||exists&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))bad();return root;
        }
        static void requirePrivate(Context context,StockArtifact artifact)throws Exception{
            requirePrivateRoot(context);
            Path root=EmulatorAndroidDailyStore.files(context).resolve("daily-route-captures");
            Path leaf=root.resolve(EmulatorDailyRunner.digest(artifact.work.key)+".tsv");
            for(Path p:new Path[]{root,leaf}){
                if(Files.isSymbolicLink(p)||((Number)Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid||
                    !Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString(p.equals(root)?"rwx------":"rw-------")))bad();
            }
            if(((Number)Files.getAttribute(leaf,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)bad();
        }
        static void requirePrivateRoot(Context context)throws Exception{
            Path root=directory(EmulatorAndroidDailyStore.files(context).resolve("daily-route-captures"),true);
            if(((Number)Files.getAttribute(root,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid||
                !Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))bad();
        }
        private static long number(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    }
    static EmulatorDailyRunner.Observation completed(Context context,EmulatorDailyRunner.Binding held,String key)throws Exception {
        Path files=EmulatorAndroidDailyStore.files(context);
        Path link=files.resolve("daily-route-links").resolve(EmulatorDailyRunner.digest(key)+".tsv");
        Path result=files.resolve("daily-route-results").resolve(EmulatorDailyRunner.digest(key)+".tsv");
        if(Files.exists(link,LinkOption.NOFOLLOW_LINKS)){
            String fence=boundedPrivateRead(link,3072);
            if(fence.startsWith(NOT_DISPATCHED)){
                EmulatorDailyRouteWork work=validateNotDispatched(fence,held,key,System.currentTimeMillis());
                if(Files.exists(result,LinkOption.NOFOLLOW_LINKS))bad();
                work.requireIdentity(context);
                try(FileChannel in=FileChannel.open(link,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){in.force(true);}
                EmulatorAndroidDailyStore.syncDirectory(files);EmulatorAndroidDailyStore.syncDirectory(link.getParent());
                if(!fence.equals(boundedPrivateRead(link,3072)))bad();
                work.requireIdentity(context);
                return new EmulatorDailyRunner.Observation(held,key,EmulatorDailyRunner.Evidence.CLOSED_SKIPPED);
            }
        }
        if(!Files.exists(result,LinkOption.NOFOLLOW_LINKS))return new EmulatorDailyRunner.Observation(held,key,EmulatorDailyRunner.Evidence.UNKNOWN);
        String claim=boundedPrivateRead(files.resolve("daily-route-links").resolve(EmulatorDailyRunner.digest(key)+".tsv"),3072);
        String[] rows=claim.split("\n",-1);if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] f=rows[0].split("\t",-1);if(f.length!=7)bad();
        EmulatorDailyRouteWork work=decode(EmulatorDailyRunner.unbase64(f[1]));
        if(!work.matches(held,key)||!claim.equals(work.claimWire(f[2],f[3],f[4])))bad();
        work.requireIdentity(context);
        String wire=boundedPrivateRead(result,EmulatorUnstartedRouteOutcome.MAX_BYTES);
        boolean skipped=wire.startsWith(EmulatorUnstartedRouteOutcome.HEADER);
        if(skipped)EmulatorUnstartedRouteOutcome.validate(wire,held,key,EmulatorDailyRunner.digest(claim),f[2],f[3],f[4],System.currentTimeMillis());
        else if(wire.startsWith(EmulatorDailyRouteClosure.INCOMPLETE_HEADER)){
            EmulatorDailyRouteClosure.validateIncompleteClaim(wire,held,key,EmulatorDailyRunner.digest(claim),f[2],f[3],f[4],System.currentTimeMillis());
            skipped=true;
        }
        else EmulatorDailyRouteClosure.validateClaim(wire,held,key,EmulatorDailyRunner.digest(claim),f[2],f[3],f[4]);
        // A visible leaf after the writer's fsync failure is NOT durable proof.
        // Reconcile durability without repeating any route/provider effect.
        try(FileChannel in=FileChannel.open(result,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){in.force(true);}
        EmulatorAndroidDailyStore.syncDirectory(files);
        EmulatorAndroidDailyStore.syncDirectory(result.getParent());
        if(!claim.equals(boundedPrivateRead(files.resolve("daily-route-links").resolve(EmulatorDailyRunner.digest(key)+".tsv"),3072))||!wire.equals(boundedPrivateRead(result,EmulatorUnstartedRouteOutcome.MAX_BYTES)))bad();
        work.requireIdentity(context);
        return new EmulatorDailyRunner.Observation(held,key,skipped?EmulatorDailyRunner.Evidence.CLOSED_SKIPPED:EmulatorDailyRunner.Evidence.COMPLETE);
    }
    private static String boundedPrivateRead(Path leaf,int maximum)throws Exception {
        for(Path p=leaf.getParent();p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))bad();
        if(Files.isSymbolicLink(leaf)||!Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS)||Files.size(leaf)>maximum)bad();
        try(FileChannel in=FileChannel.open(leaf,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()<1||in.size()>maximum)bad();ByteBuffer data=ByteBuffer.allocate(maximum+1);
            while(in.read(data)>0){};if(data.position()>maximum)bad();
            byte[] bytes=java.util.Arrays.copyOf(data.array(),data.position());
            String text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(bytes,text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))bad();return text;
        }
    }
    static void persistResult(Path root,String key,String wire,EmulatorAndroidDailyStore.DirectorySync sync,PrivateMode mode)throws Exception {
        if(root==null||sync==null||mode==null||key==null||!routeKey(key))bad();
        root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null||!root.getFileName().toString().equals("daily-route-results")||root.getParent()==null||!root.getParent().getFileName().toString().equals("files"))bad();
        if(wire==null||wire.length()>EmulatorUnstartedRouteOutcome.MAX_BYTES)bad();String[] h=wire.split("\n",-1)[0].split("\t",-1);
        if(wire.startsWith(EmulatorUnstartedRouteOutcome.HEADER)){
            if(h.length!=15)bad();String[] rows=wire.split("\n",-1);
            String first=rows.length==5?h[6]:rows[1].split("\t",-1)[1];
            EmulatorUnstartedRouteOutcome.validate(wire,EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])),key,h[3],h[4],h[5],first,System.currentTimeMillis());
        }else if(wire.startsWith(EmulatorDailyRouteClosure.INCOMPLETE_HEADER)){
            if(h.length!=11)bad();String[] rows=wire.split("\n",-1);
            if(rows.length<4||rows[1].split("\t",-1).length!=6)bad();
            String first=rows[1].split("\t",-1)[1];
            EmulatorDailyRouteClosure.validateIncompleteClaim(wire,EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])),key,h[3],h[4],h[5],first,System.currentTimeMillis());
        }else{
            if(h.length!=11)bad();EmulatorDailyRouteClosure.validate(wire,EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])),key,h[3],h[4],h[5]);
        }
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Files.createDirectories(root);sync.sync(root.getParent());sync.sync(root);
        Path leaf=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");byte[] bytes=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(leaf);ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
        }
        sync.sync(root);if(!java.util.Arrays.equals(bytes,Files.readAllBytes(leaf)))bad();
    }
    static void persistClaim(Path root,String key,String wire,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        persistClaim(root,key,wire,sync,path->Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------")));
    }
    interface PrivateMode { void apply(Path path)throws Exception; }
    /** Host permission seam, NOT App UID/SELinux validation. */
    static void persistClaim(Path root,String key,String wire,EmulatorAndroidDailyStore.DirectorySync sync,PrivateMode mode)throws Exception {
        if(root==null || sync==null || mode==null || key==null || !routeKey(key) || wire==null || wire.length()>3072)bad();
        String[] rows=wire.split("\n",-1);
        if(rows.length!=3 || !rows[2].isEmpty() || !rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] fields=rows[0].split("\t",-1);
        if(wire.startsWith(NOT_DISPATCHED)){
            if(fields.length!=6)bad();
            EmulatorDailyRouteWork held=decode(EmulatorDailyRunner.unbase64(fields[1]));
            validateNotDispatched(wire,held.binding,key,System.currentTimeMillis());
        }else{
            if(fields.length!=7 || !fields[0].equals("v1") || !fields[5].equals("intent-not-admitted") || !fields[6].equals("end"))bad();
            EmulatorDailyRouteWork held=decode(EmulatorDailyRunner.unbase64(fields[1]));
            if(!key.equals(held.key) || !wire.equals(held.claimWire(fields[2],fields[3],fields[4])))bad();
        }
        root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null || !root.getFileName().toString().equals("daily-route-links") || root.getParent()==null ||
                root.getParent().getFileName()==null || !root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS) &&
                (Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Files.createDirectories(root);sync.sync(root.getParent());sync.sync(root);
        // Key, NOT PID/binding, names the unique slot. A changed boot/account
        // cannot create a second claim for the same daily slot.
        Path leaf=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");
        byte[] bytes=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            mode.apply(leaf);
            ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
        }
        sync.sync(root);
        if(!java.util.Arrays.equals(bytes,Files.readAllBytes(leaf)))bad();
        // Failure preserves the leaf. No erase, overwrite, replay or fallback.
    }
    private static String hashApk(Context context)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=new java.io.FileInputStream(context.getApplicationInfo().sourceDir)) {
            byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);
        }
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return hex.toString();
    }
    static boolean routeKey(String key){return key!=null&&key.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}:(?:n22:route-"+EmulatorDailyPlan.NIGHT_ROUTE_NUMBER+"|route-(?:[0-9]|1[0-2])|development-route-(?:[0-9]|1[0-2]|[0-9a-f]{32}))");}
    private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static void bad(){
        // Fixed product code location only: never append binding, coordinates,
        // paths, tokens or an underlying exception message. This also covers
        // pre-persistence validation that the requireStart labels do not reach.
        String location="unspecified";
        for(StackTraceElement frame:new Throwable().getStackTrace()){
            if(frame.getClassName().equals(EmulatorDailyRouteWork.class.getName()) && !frame.getMethodName().equals("bad")){
                String method=frame.getMethodName();
                if(method.equals("<init>"))method="constructor";
                if(method.matches("[A-Za-z][A-Za-z0-9]*") && frame.getLineNumber()>0)
                    location=method+":"+frame.getLineNumber();
                break;
            }
        }
        throw new IllegalStateException("Exact independently held daily route correlation required; preserve uncertain claim [route-check "+location+"]");
    }
}

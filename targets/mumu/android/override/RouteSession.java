package dev.ody.pikmincontrol;

import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;

/** One leased provider session. All calls on worker; broadcasts are observations only. */
final class RouteSession implements AutoCloseable {
    static final String LEASE="/data/local/tmp/pikmin-route-lease.tsv", OWNER="/data/local/tmp/pcc-route-owner";
    static final String PREFIX="theappninjas.gpsjoystick.";
    final Context c; final String pid, token=java.util.UUID.randomUUID().toString();
    final BlockingQueue<Intent> replies=new LinkedBlockingQueue<>(16);
    final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context c,Intent i){replies.offer(new Intent(i));}};
    boolean registered, owned, walking; double originalSpeed; String phase="prepare";
    String account="";
    String providerService="";
    long lastHeartbeat;
    final String expectedAccount;
    RouteSession(Context c,String pid){this(c,pid,"");}
    RouteSession(Context c,String pid,String expected){this.c=c;this.pid=pid;expectedAccount=expected;}
    static boolean pending(Context c){return c.getSharedPreferences("farm_route",0).getBoolean("pending",false);}
    void open(boolean recover)throws Exception {
        EmulatorGpsFence.prepare(c,Integer.parseInt(pid));
        EmulatorRouteGuard.requireNoReservation();
        if(!pid.matches("[1-9][0-9]*"))throw new IllegalArgumentException("遊戲程序無效");
        requireStatus(false); // Fresh capability before any control writes.
        account=SessionIdentity.read();
        if(account.isEmpty())throw new IllegalStateException("原生帳號識別未就緒，請核對核心並重新開啟遊戲");
        if(!expectedAccount.isEmpty()&&!expectedAccount.equals(account))throw new IllegalStateException("準備期間帳號已變更，未接管種花");
        IntentFilter f=new IntentFilter(PREFIX+"STATUS_RESPONSE");f.addAction(PREFIX+"ERROR");
        if(Build.VERSION.SDK_INT>=33)c.registerReceiver(receiver,f,Context.RECEIVER_EXPORTED);else c.registerReceiver(receiver,f);registered=true;
        providerService=serviceIdentity();
        if(providerService.isEmpty())throw new IllegalStateException("請先開啟 JoyStick 定位服務；採果不會自動啟動它");
        Intent before=status();
        if(!before.getBooleanExtra("is_running",false)||before.getBooleanExtra("is_route_active",true))throw new IllegalStateException("請先停止既有GPS路線，保持定位服務開啟");
        originalSpeed=EmulatorJoystickSpeed.read(before.getExtras().get("speed"));
        if(!Double.isFinite(originalSpeed)||originalSpeed<=0)throw new IllegalStateException("無法確認GPS速度");
        String prior=root("cat "+OWNER+" 2>/dev/null || true").trim();
        if(!prior.isEmpty()) {
            String[] idle=requireStatus(false);
            if(!recover||!idle[4].equals("off")||!idle[5].equals("1"))throw new IllegalStateException("前次沿路工作尚未清理，請停止全部並等待原生停止");
            StopChecks.farm();
            root("rm -f "+OWNER+" "+LEASE);
        }
        File script=new File(c.getFilesDir(),"route-watchdog.sh");
        try(InputStream in=c.getAssets().open("route-watchdog.sh");OutputStream out=new FileOutputStream(script)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;
            while((n=in.read(b))>=0){if(bytes.size()+n>16384)throw new IOException("watchdog size");bytes.write(b,0,n);}
            out.write(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8).replace("\r\n","\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if(!c.getSharedPreferences("farm_route",0).edit().putBoolean("pending",true).commit())throw new IllegalStateException("無法保存路線接管狀態");
        owned=true; heartbeat("prepare");
        root("cp '"+script.getAbsolutePath()+"' /data/local/tmp/pcc-route-watchdog.sh && chmod 0700 /data/local/tmp/pcc-route-watchdog.sh && printf '"+token+"\\n' > "+OWNER+" && chmod 0600 "+OWNER);
        root("nohup sh /data/local/tmp/pcc-route-watchdog.sh "+token+" >/data/local/tmp/pcc-route-watchdog.log 2>&1 </dev/null &");
        if(!token.equals(root("cat "+OWNER).trim()))throw new IllegalStateException("路線監護未啟動");
        Thread.sleep(500);
        if(!token.equals(root("cat "+OWNER+".ready").trim()))throw new IllegalStateException("路線監護未回報就緒");
    }
    void heartbeat(String phase)throws Exception {
        EmulatorRouteGuard.requireNoReservation();
        if(!owned)return;
        if(!phase.equals("prepare")&&!phase.equals("hold")&&!phase.equals("walk"))throw new IllegalArgumentException("phase");
        this.phase=phase;
        root("printf 'v2\\t"+pid+"\\t"+(System.currentTimeMillis()+12000)+"\\t"+token+"\\t"+phase+"\\t"+account+"\\n' > "+LEASE+".tmp && chmod 0644 "+LEASE+".tmp && mv "+LEASE+".tmp "+LEASE);
        lastHeartbeat=SystemClock.elapsedRealtime();
    }
    // Only prepare-stop sampling is coalesced. Same Root flock and in-shell
    // namespace guard surround BOTH heartbeat write and status read. No extra
    // preflight Root query, no wider StopGate gap/freshness, no flight call.
    String[] heartbeatStopStatus()throws Exception {
        if(!owned || !pid.matches("[1-9][0-9]*") || !token.matches("[0-9a-f-]{36}") || !account.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Owned same-session preparation required");
        phase="prepare";
        long expiry=System.currentTimeMillis()+12000;
        String q="test ! -L "+OWNER+" && test \"$(cat "+OWNER+")\" = '"+token+"' && "
            +"test -f "+LEASE+" && test ! -L "+LEASE+" && test ! -e "+LEASE+".tmp && test ! -L "+LEASE+".tmp && "
            +"test \"$(cut -f4 "+LEASE+")\" = '"+token+"' && "
            +"printf 'v2\\t"+pid+"\\t"+expiry+"\\t"+token+"\\tprepare\\t"+account+"\\n' > "+LEASE+".tmp && "
            +"chmod 0644 "+LEASE+".tmp && mv "+LEASE+".tmp "+LEASE+" && "
            +"cat /data/user/0/com.nianticlabs.pikmin/files/route_status.tsv";
        String raw=root(q); // Existing in-shell namespace guard + GPS flock.
        lastHeartbeat=SystemClock.elapsedRealtime();
        return new RouteState(raw,pid,System.currentTimeMillis(),token,"prepare").fields;
    }
    // The permit authorizes ONE fresh read after a checked STATUS reply, not
    // use of a cached fix. Consume before I/O, including on read failure.
    static final class WalkingObservationPermit {
        private boolean acknowledged;
        void reset(){acknowledged=false;}
        void acknowledge(){acknowledged=true;}
        void consume(){
            boolean allowed=acknowledged;acknowledged=false;
            if(!allowed)throw new IllegalStateException("walking-observation-missing");
        }
    }
    private final WalkingObservationPermit walkingObservationPermit=new WalkingObservationPermit();
    private RouteObservation walkingObservation;
    private final EmulatorDailyProviderRecheck providerRecheck=new EmulatorDailyProviderRecheck();
    RouteObservation takeWalkingObservation()throws Exception {
        walkingObservationPermit.consume();
        RouteObservation held=walkingObservation;walkingObservation=null;
        if(held==null)throw new IllegalStateException("walking-observation-missing");
        providerRecheck.observed();
        return held; // THIS post-effect frame only, after valid reply and actor exit.
    }
    RouteObservation recheckWalkingObservation()throws Exception {
        // Consume BEFORE I/O, including on failure. Full runtime/snapshot
        // under the original nonblocking GPS shell flock; no provider effect.
        providerRecheck.consume(SystemClock.elapsedRealtime());
        RouteObservation result=readWalkingObservation(true);
        providerRecheck.requireFresh(SystemClock.elapsedRealtime());
        return result;
    }
    private RouteObservation readWalkingObservation()throws Exception {return readWalkingObservation(false);}
    private RouteObservation readWalkingObservation(boolean standalone)throws Exception {
        if(!owned||!walking||!phase.equals("walk")||!pid.matches("[1-9][0-9]*")||
           !token.matches("[0-9a-f-]{36}")||!account.matches("[0-9a-f]{64}"))
            throw new IllegalStateException("Owned active route observation required");
        // The same fresh namespace check remains inside root(q+snapshot),
        // under the original GPS flock and before any optional lease write.
        // No cached absence, narrower probe, new actor or changed decision.
        boolean due=SystemClock.elapsedRealtime()-lastHeartbeat>=3000;
        String q="test \"$(pidof com.nianticlabs.pikmin)\" = '"+pid+"' && "
            +"test -f "+OWNER+" && test ! -L "+OWNER+" && test \"$(cat "+OWNER+")\" = '"+token+"' && "
            +"test -f "+LEASE+" && test ! -L "+LEASE+" && "
            +"test \"$(cut -f2 "+LEASE+")\" = '"+pid+"' && test \"$(cut -f4 "+LEASE+")\" = '"+token+"' && "
            +"test \"$(cut -f5 "+LEASE+")\" = walk && test \"$(cut -f6 "+LEASE+")\" = '"+account+"' || exit 1;\n";
        if(due)q+="test ! -e "+LEASE+".tmp && test ! -L "+LEASE+".tmp || exit 1; "
            +"printf 'v2\\t"+pid+"\\t"+(System.currentTimeMillis()+12000)+"\\t"+token+"\\twalk\\t"+account+"\\n' > "+LEASE+".tmp && "
            +"chmod 0644 "+LEASE+".tmp && mv "+LEASE+".tmp "+LEASE+" || exit 1;\n";
        // SAME GPS shell flock and in-shell namespace guard cover publication
        // plus the full original snapshot. No nested GPS actor or provider call.
        walkingTelemetry=""; // A failed read must not retain a previous frame.
        // Called ONLY immediately AFTER this STATUS effect inside the original
        // GPS actor, before its confirmed exit. Keep full runtime and ten native
        // sections, namespace, owner/lease, PID/account and all freshness checks.
        // Never acquire a nested GPS shell flock or resend a provider command.
        CorePair pair=CoreUpdater.pair(c);
        String complete=EmulatorWalkFrame.command(EmulatorRouteRuntime.command(),
            EmulatorRouteGuard.beforeShell(q+GpsModuleGateway.routeTelemetryObservationCommand()));
        if(standalone)complete=EmulatorGpsShellFence.wrap(complete);
        BoundedCommand.Result combined=BoundedCommand.root(complete,3,1048576);
        String[] frames=EmulatorWalkFrame.split(combined);
        String actual=EmulatorRouteRuntime.checkedPid(pair,new BoundedCommand.Result(frames[0],true,false,false,0),System.currentTimeMillis());
        if(!pid.equals(actual)||actual.isEmpty())throw new IllegalStateException("Route game PID changed");
        String raw=frames[1];
        String[] parts=GpsModuleGateway.routeTelemetryObservationSections(raw);
        RouteObservation result=GpsModuleGateway.routeTelemetryObservationFrom(c,pid,parts);
        walkingTelemetry=parts[8]; // Informational only; parse bindings/freshness at use.
        if(due)lastHeartbeat=SystemClock.elapsedRealtime();
        return result;
    }
    void keepAlive()throws Exception {if(SystemClock.elapsedRealtime()-lastHeartbeat>=3000)heartbeat(phase);}
    boolean stopped()throws Exception {return requireStatus(true)[5].equals("1");}
    String[] requireStatus(boolean match)throws Exception {
        String[] f=root("cat /data/user/0/com.nianticlabs.pikmin/files/route_status.tsv").trim().split("\\t",-1);
        return new RouteState(String.join("\t",f),pid,System.currentTimeMillis(),match?token:null,phase).fields;
    }
    private String walkingTelemetry="";
    RouteDispatchDiagnostics dispatchDiagnostics() throws Exception {
        if(walking && "walk".equals(phase)) {
            if(!owned || walkingTelemetry.trim().isEmpty())throw new IllegalStateException("route dispatch diagnostics missing");
            return RouteDispatchDiagnostics.parse(walkingTelemetry,pid,token,System.currentTimeMillis());
        }
        String raw=root("head -c 2049 /data/user/0/com.nianticlabs.pikmin/files/route_dispatch_diagnostics.tsv 2>/dev/null || true");
        if(raw.trim().isEmpty())throw new IllegalStateException("route dispatch diagnostics missing");
        return RouteDispatchDiagnostics.parse(raw,pid,token,System.currentTimeMillis());
    }
    void walk(HarvestRoute route,EmulatorGpsFenceCoordinator.Effect beforeWalk)throws Exception {
        if(beforeWalk==null)throw new IllegalArgumentException("Checked pre-WALK baseline required");
        EmulatorRouteGuard.requireNoReservation();
        if(!account.equals(SessionIdentity.read()))throw new IllegalStateException("帳號已變更或識別失效，未啟動路線");
        heartbeat("walk");walking=true;
        startProvider(new Intent(PREFIX+"WALK").setPackage(JoystickAdapter.PACKAGE).putExtra("waypoints",route.waypoints()).putExtra("teleport",false).putExtra("speed",19f),beforeWalk);
        root("printf 'route\\n' > /data/local/tmp/pikmin-dispatch-mode.txt && chmod 0644 /data/local/tmp/pikmin-dispatch-mode.txt");
    }
    void pause()throws Exception {send("ROUTE_PAUSE");heartbeat("hold");}
    private final EmulatorStatusReplyWindow overlapWindow=new EmulatorStatusReplyWindow();
    private long overlapStarted,overlapSent;
    private long dailyStatusGeneration,lastReplacedStatusGeneration=-1;
    static final class ReplacementObservation {
        final Intent status;final RouteObservation observation;
        ReplacementObservation(Intent status,RouteObservation observation){
            if(status==null||observation==null)throw new IllegalStateException("replacement-frame-missing");
            this.status=status;this.observation=observation;
        }
    }
    ReplacementObservation replaceDailyWalkingObservation()throws Exception {
        if(dailyStatusGeneration<=0||lastReplacedStatusGeneration==dailyStatusGeneration)
            throw new IllegalStateException("status-replacement-already-consumed");
        lastReplacedStatusGeneration=dailyStatusGeneration; // BEFORE any I/O, including failure.
        providerRecheck.consume(SystemClock.elapsedRealtime()); // original acknowledged reply must still be fresh.
        // Fresh original actor/runtime/namespace/owner+lease/full ten-source
        // frame, then ONE STATUS only. Its original two-second reply window
        // begins at its own request; old STATUS coordinates are never reused.
        beginDailyOverlappedStatus();
        Intent replacement=finishOverlappedStatus();
        lastReplacedStatusGeneration=dailyStatusGeneration; // replacement cannot authorize another replacement.
        RouteObservation frame=takeWalkingObservation();
        providerRecheck.requireFresh(SystemClock.elapsedRealtime());
        return new ReplacementObservation(replacement,frame);
    }
    private boolean dailyRuntimeInFrame;
    void beginDailyOverlappedStatus()throws Exception {
        if(!owned||!walking||!"walk".equals(phase))
            throw new IllegalStateException("daily-status-owned-walk-required");
        if(dailyRuntimeInFrame)throw new IllegalStateException("daily-status-runtime-reentry");
        dailyRuntimeInFrame=true;
        try{beginOverlappedStatus();}finally{dailyRuntimeInFrame=false;}
    }
    void beginOverlappedStatus()throws Exception {
        if(overlapWindow.pending())throw new IllegalStateException("STATUS reply already pending");
        overlapStarted=SystemClock.elapsedRealtime();
        providerRecheck.reset();walkingObservation=null;walkingObservationPermit.reset();replies.clear();send("STATUS"); // full original actor/admission/core/exit
        overlapSent=SystemClock.elapsedRealtime();
        overlapWindow.begin(SystemClock.elapsedRealtime());
    }
    Intent finishOverlappedStatus()throws Exception {
        providerRecheck.reset();walkingObservationPermit.reset();Intent i=null;
        try {
            while(true){
                long remaining=overlapWindow.remaining(SystemClock.elapsedRealtime());
                Intent candidate=replies.poll(remaining,TimeUnit.MILLISECONDS);
                if(candidate==null)throw new IllegalStateException("GPS狀態回報逾時（2秒）");
                // Also reject a delayed completion beyond the original deadline.
                overlapWindow.remaining(SystemClock.elapsedRealtime());
                // A delayed PAUSE error is not this STATUS response.
                if(RouteCleanupProof.response(candidate.getAction())){i=candidate;break;}
            }
            for(String k:new String[]{"lat","lng","speed","is_running","is_route_active"})if(!i.hasExtra(k))throw new IllegalStateException("GPS回報缺少欄位");
            if(!FarmLocation.point(i.getDoubleExtra("lat",Double.NaN),i.getDoubleExtra("lng",Double.NaN)))throw new IllegalStateException("GPS座標無效");
            android.util.Log.i("PccStatusOverlap","responseWindowMs="+(SystemClock.elapsedRealtime()-overlapSent)+
                " totalMs="+(SystemClock.elapsedRealtime()-overlapStarted));
            if(owned&&walking&&"walk".equals(phase)){
                walkingObservationPermit.acknowledge();providerRecheck.acknowledge(SystemClock.elapsedRealtime());
                if(dailyStatusGeneration==Long.MAX_VALUE)throw new IllegalStateException("status-generation-exhausted");
                dailyStatusGeneration++;
            }
            return i;
        } finally {overlapWindow.clear();}
    }
    Intent status()throws Exception {
        long statusTimingStart=SystemClock.elapsedRealtime();
        replies.clear();send("STATUS");Intent i=null;
        long statusTimingSent=SystemClock.elapsedRealtime();
        long until=SystemClock.elapsedRealtime()+2000;
        while(SystemClock.elapsedRealtime()<until){
            Intent candidate=replies.poll(Math.max(1,until-SystemClock.elapsedRealtime()),TimeUnit.MILLISECONDS);
            if(candidate==null)break;
            // A delayed PAUSE error is not the reply to this STATUS request.
            if(RouteCleanupProof.response(candidate.getAction())){i=candidate;break;}
        }
        if(i==null)throw new IllegalStateException("GPS狀態回報逾時（2秒）");
        for(String k:new String[]{"lat","lng","speed","is_running","is_route_active"})if(!i.hasExtra(k))throw new IllegalStateException("GPS回報缺少欄位");
        if(!FarmLocation.point(i.getDoubleExtra("lat",Double.NaN),i.getDoubleExtra("lng",Double.NaN)))throw new IllegalStateException("GPS座標無效");
        android.util.Log.i("PccStatusTiming","replyWaitMs="+(SystemClock.elapsedRealtime()-statusTimingSent)+
            " totalMs="+(SystemClock.elapsedRealtime()-statusTimingStart));
        return i;
    }
    void send(String action)throws Exception {startProvider(new Intent(PREFIX+action).setPackage(JoystickAdapter.PACKAGE));}
    private void startProvider(Intent intent)throws Exception {startProvider(intent,null);}
    private void startProvider(Intent intent,EmulatorGpsFenceCoordinator.Effect beforeEffect)throws Exception {
        final long[] statusTiming=new long[4];
        statusTiming[0]=SystemClock.elapsedRealtime();
        EmulatorGpsFence.execute(c,Integer.parseInt(pid),()->{
            statusTiming[1]=SystemClock.elapsedRealtime();
            if((PREFIX+"STATUS").equals(intent.getAction())){
                // Only the explicit daily WALK caller can use the full runtime
                // already checked by readWalkingObservation before this STATUS.
                // Revalidate mutable phase here inside the original actor.
                if(dailyRuntimeInFrame){
                    if(!owned||!walking||!"walk".equals(phase))
                        throw new IllegalStateException("daily-status-owned-walk-changed");
                }else EmulatorRouteRuntime.require(c,pid);
            }else {
                EmulatorRouteGuard.requireNoReservation();
                if(!pid.equals(CoreUpdater.runtimePid(c,CoreUpdater.pair(c))))throw new IllegalStateException("Route game PID changed");
            }
            statusTiming[2]=SystemClock.elapsedRealtime();
            // Only WALK supplies this callback. The read has no nested flock or
            // provider command. Validate the baseline AFTER admission costs,
            // immediately BEFORE the single actual WALK effect.
            if(beforeEffect!=null)beforeEffect.run();
            if((PREFIX+"STATUS").equals(intent.getAction())&&walking&&"walk".equals(phase)) {
                // STATUS only reads the provider. Capture AFTER all original
                // admission/runtime/beforeEffect costs, immediately before
                // requesting that read, NOT before actor admission or WALK.
                // Keep the full original runtime+native/provider read, same
                // actor/shell fence, response permit and post-read TTL/10m.
                walkingObservation=null;
                walkingObservation=EmulatorDailyStatusFrameOrder.capture(this::readWalkingObservation,()->{
                    c.startForegroundService(intent);
                    statusTiming[3]=SystemClock.elapsedRealtime();
                });
            } else {
                c.startForegroundService(intent);
                statusTiming[3]=SystemClock.elapsedRealtime();
            }
        });
        if((PREFIX+"STATUS").equals(intent.getAction()))android.util.Log.i("PccStatusTiming",
            "admissionMs="+(statusTiming[1]-statusTiming[0])+" runtimeMs="+(statusTiming[2]-statusTiming[1])+
            " serviceCallMs="+(statusTiming[3]-statusTiming[2])+" exitMs="+(SystemClock.elapsedRealtime()-statusTiming[3]));
    }
    static String root(String s)throws Exception {return GpsModuleGateway.farmRead(EmulatorGpsShellFence.wrap(EmulatorRouteGuard.beforeShell(s)));}
    void retireWatchdog()throws Exception {
        // GPS is already paused and native work stopped. Keep the lease valid
        // until the watchdog explicitly exits; never delete both files together.
        heartbeat("hold");
        root("test \"$(cat "+OWNER+")\" = "+token+" && rm -f "+OWNER);
        long until=SystemClock.elapsedRealtime()+5000;
        while(!token.equals(root("cat "+OWNER+".done."+token+" 2>/dev/null || true").trim())){
            keepAlive();
            if(SystemClock.elapsedRealtime()>=until)throw new IllegalStateException("GPS監護退出未確認，保留待收尾狀態");
            Thread.sleep(200);
        }
        android.util.Log.i("PikminRouteCleanup","watchdog-exit-confirmed");
    }
    static void returnToGameIfJoystick()throws Exception {
        // Do not steal focus from another app or unlock the device.
        root("top=$("+EmulatorSavedDailyUi.focusQuery()+"); case \"$top\" in *"+JoystickAdapter.PACKAGE+"/*) component=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.nianticlabs.pikmin | grep '^com.nianticlabs.pikmin/'); test -n \"$component\" && am start --user 0 -n \"$component\" >/dev/null;; esac");
    }
    String serviceIdentity()throws Exception {
        return RouteCleanupProof.serviceIdentity(root("dumpsys activity services "+RouteCleanupProof.SERVICE));
    }
    void requireSameService()throws Exception {
        if(providerService.isEmpty()||!providerService.equals(serviceIdentity()))
            throw new IllegalStateException("JoyStick 定位服務已中斷或變更，未自動重啟；請確認後重試收尾");
    }
    void awaitActualProvider(Intent target)throws Exception {
        String identity=c.getPackageManager().getApplicationInfo(JoystickAdapter.PACKAGE,0).uid+"/"+JoystickAdapter.PACKAGE;
        long until=SystemClock.elapsedRealtime()+12000, previous=-1;
        while(SystemClock.elapsedRealtime()<until){
            keepAlive();
            JoystickReadiness fix=JoystickReadiness.parse(root("dumpsys location"),identity);
            if(fix.fresh(SystemClock.elapsedRealtime())
                    &&FarmLocation.distance(target.getDoubleExtra("lat",0),target.getDoubleExtra("lng",0),fix.latitude,fix.longitude)<=3){
                if(previous>=0&&fix.fixMs>previous){
                    android.util.Log.i("PikminRouteCleanup","system-provider-updating-confirmed");return;
                }
                previous=fix.fixMs;
            } else previous=-1;
            Thread.sleep(300);
        }
        throw new IllegalStateException("GPS實際定位未持續更新，保留待收尾狀態");
    }
    @Override public void close()throws Exception {
        try {
            if(owned) {
                heartbeat("hold");
                requireSameService();
                if(walking) {
                    send("ROUTE_PAUSE");Thread.sleep(500);Intent at=status();
                    if(at.getBooleanExtra("is_route_active",true))throw new IllegalStateException("GPS暫停未確認");
                    if(!at.hasExtra("is_hidden"))throw new IllegalStateException("GPS顯示狀態未確認");
                    boolean hidden=at.getBooleanExtra("is_hidden",false);
                    retireWatchdog();
                    // Leave the existing provider alive and the old route paused.
                    // A later WALK replaces waypoints; never STOP/restart/TELEPORT here.
                    startProvider(new Intent(PREFIX+"SPEED").setPackage(JoystickAdapter.PACKAGE).putExtra("speed",(float)originalSpeed));
                    Thread.sleep(300);
                    Intent speed=status();
                    if(!RouteCleanupProof.speed(originalSpeed,EmulatorJoystickSpeed.read(speed.getExtras().get("speed"))))throw new IllegalStateException("GPS原始速度恢復未確認");
                    if(!speed.hasExtra("is_hidden")||speed.getBooleanExtra("is_hidden",false)!=hidden)throw new IllegalStateException("GPS顯示狀態恢復未確認");
                    if(!RouteCleanupProof.ready(speed.getBooleanExtra("is_running",false),speed.getBooleanExtra("is_route_active",true),speed.getIntExtra("route_total",-1)))throw new IllegalStateException("GPS路線暫停未確認");
                    android.util.Log.i("PikminRouteCleanup","original-speed-confirmed");
                    awaitActualProvider(at);
                    requireSameService();
                    android.util.Log.i("PikminRouteCleanup","provider-preserved; route-paused");
                    returnToGameIfJoystick();
                } else retireWatchdog();
                root("test ! -e "+OWNER+" && rm -f "+OWNER+".ready "+OWNER+".done."+token+" "+LEASE);
                owned=false;
                if(!c.getSharedPreferences("farm_route",0).edit().putBoolean("pending",false).commit())throw new IllegalStateException("路線復歸狀態未保存");
            }
        } finally {if(registered){c.unregisterReceiver(receiver);registered=false;}}
    }
}

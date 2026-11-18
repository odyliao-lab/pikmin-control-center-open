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
        originalSpeed=before.getDoubleExtra("speed",Double.NaN);
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
        if(!owned)return;
        if(!phase.equals("prepare")&&!phase.equals("hold")&&!phase.equals("walk"))throw new IllegalArgumentException("phase");
        this.phase=phase;
        root("printf 'v2\\t"+pid+"\\t"+(System.currentTimeMillis()+12000)+"\\t"+token+"\\t"+phase+"\\t"+account+"\\n' > "+LEASE+".tmp && chmod 0644 "+LEASE+".tmp && mv "+LEASE+".tmp "+LEASE);
        lastHeartbeat=SystemClock.elapsedRealtime();
    }
    void keepAlive()throws Exception {if(SystemClock.elapsedRealtime()-lastHeartbeat>=3000)heartbeat(phase);}
    boolean stopped()throws Exception {return requireStatus(true)[5].equals("1");}
    String[] requireStatus(boolean match)throws Exception {
        String[] f=root("cat /data/user/0/com.nianticlabs.pikmin/files/route_status.tsv").trim().split("\\t",-1);
        return new RouteState(String.join("\t",f),pid,System.currentTimeMillis(),match?token:null,phase).fields;
    }
    RouteDispatchDiagnostics dispatchDiagnostics() throws Exception {
        String raw=root("head -c 2049 /data/user/0/com.nianticlabs.pikmin/files/route_dispatch_diagnostics.tsv 2>/dev/null || true");
        if(raw.trim().isEmpty())throw new IllegalStateException("route dispatch diagnostics missing");
        return RouteDispatchDiagnostics.parse(raw,pid,token,System.currentTimeMillis());
    }
    void walk(HarvestRoute route)throws Exception {
        if(!account.equals(SessionIdentity.read()))throw new IllegalStateException("帳號已變更或識別失效，未啟動路線");
        heartbeat("walk");walking=true;
        c.startForegroundService(new Intent(PREFIX+"WALK").setPackage(JoystickAdapter.PACKAGE).putExtra("waypoints",route.waypoints()).putExtra("teleport",false).putExtra("speed",19f));
        root("printf 'route\\n' > /data/local/tmp/pikmin-dispatch-mode.txt && chmod 0644 /data/local/tmp/pikmin-dispatch-mode.txt");
    }
    void pause()throws Exception {send("ROUTE_PAUSE");heartbeat("hold");}
    Intent status()throws Exception {
        replies.clear();send("STATUS");Intent i=null;
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
        return i;
    }
    void send(String action){c.startForegroundService(new Intent(PREFIX+action).setPackage(JoystickAdapter.PACKAGE));}
    static String root(String s)throws Exception {return GpsModuleGateway.farmRead(s);}
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
        root("top=$(dumpsys window | grep mCurrentFocus); case \"$top\" in *"+JoystickAdapter.PACKAGE+"/*) component=$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER com.nianticlabs.pikmin | grep '^com.nianticlabs.pikmin/'); test -n \"$component\" && am start --user 0 -n \"$component\" >/dev/null;; esac");
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
                    c.startForegroundService(new Intent(PREFIX+"SPEED").setPackage(JoystickAdapter.PACKAGE).putExtra("speed",(float)originalSpeed));
                    Thread.sleep(300);
                    Intent speed=status();
                    if(!RouteCleanupProof.speed(originalSpeed,speed.getDoubleExtra("speed",Double.NaN)))throw new IllegalStateException("GPS原始速度恢復未確認");
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

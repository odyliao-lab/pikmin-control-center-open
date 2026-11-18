package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import java.io.IOException;
import java.util.UUID;

/** MuMu-only worker entry, not yet called by the daily scheduler. The product
 * actor holds the common GPS lock across every lifecycle step. This receipt
 * cannot provide provider_drained or authorize native gameplay. */
final class EmulatorGpsLifecycle {
    private static final String PKG="com.thejhijuqoqy.bdujvrwdcajvzin";
    private static final String SERVICE=PKG+"/com.theappninjas.fakegpsjoystick.service.OverlayService";
    private static EmulatorGpsClock clock(){
        long before=SystemClock.elapsedRealtime(),wall=System.currentTimeMillis(),after=SystemClock.elapsedRealtime();
        return new EmulatorGpsClock(before,wall,after);
    }
    private static String read(String command,int limit)throws Exception{
        BoundedCommand.Result r=BoundedCommand.root(command,3,limit);
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0)
            throw new IOException("GPS lifecycle observation unknown");
        return r.text.trim();
    }
    private static String service()throws Exception{
        String identity=RouteCleanupProof.serviceIdentity(read("dumpsys activity services "+SERVICE,16384));
        if(identity.isEmpty())return "";
        String id=identity.split(" ",2)[0];
        if(!id.matches("[0-9a-f]+"))throw new IOException("Service record identity unknown");
        return id;
    }
    private static int pid(String packageName)throws Exception{
        String value=read("pidof "+packageName+" || true",64);
        if(value.isEmpty())return 0;
        if(!value.matches("[1-9][0-9]{0,8}"))throw new IOException("Process is not unique");
        return Integer.parseInt(value);
    }
    private static long ticks(int pid)throws Exception{
        String stat=read("cat /proc/"+pid+"/stat",4096);
        int end=stat.lastIndexOf(')');
        if(end<0)throw new IOException("Process stat unknown");
        return Long.parseLong(stat.substring(end+2).split(" ")[19]);
    }
    private static Context worker(Context input){
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Worker only");
        Context c=input.getApplicationContext();
        if(!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))throw new SecurityException("MuMu package only");
        return c;
    }
    static synchronized String request(Context input,boolean newRound)throws Exception{
        Port port=new Port(worker(input));EmulatorGpsHandoff.Entry active=port.read();
        return EmulatorGpsRequests.choose(port,active==null?null:active.binding.operation,newRound?UUID.randomUUID().toString():null);
    }
    static synchronized String run(Context input,boolean approved,String request)throws Exception{
        Context c=worker(input);
        if(!approved)throw new SecurityException("Explicit MuMu lifecycle authorization required");
        Port prior=new Port(c);
        if(EmulatorGpsRequests.completed(prior,request))return "此交易已完成並保留紀錄；未重送定位停止／啟動。\n這是歷史紀錄，不是目前定位排空或每日就緒證明。";
        if(prior.read()==null){
            // Advisory query only, with the signed actor and original service
            // identity checked by Diagnostics. Missing/active route refuses.
            String status=EmulatorGpsDiagnostics.check(c);
            if(!status.contains("定位服務運行=true · 路線行走=false"))throw new IOException("Active or unknown JoyStick route; no stop");
        }
        int game=pid("com.nianticlabs.pikmin");
        if(game<=0)throw new IOException("Game process required");
        final String[] output={null};
        EmulatorGpsFence.execute(c,game,()->{
            Port port=new Port(c);
            EmulatorGpsHandoff.Entry old=port.read();
            EmulatorGpsHandoff.Binding binding;
            if(old==null){
                port.requireIdle();
                int original=pid(PKG+":overlay");String record=service();
                if(original<=0||record.isEmpty())throw new IOException("Original service missing; no implicit startup");
                JoystickReadiness fix=port.fix();
                if(!fix.fresh(SystemClock.elapsedRealtime()))throw new IOException("Original fix not fresh");
                port.latitude=fix.latitude;port.longitude=fix.longitude;
                port.originalClock=clock();
                binding=new EmulatorGpsHandoff.Binding(read("cat /proc/sys/kernel/random/boot_id",64),game,original,ticks(original),record,request);
            }else binding=old.binding;
            if(!request.equals(binding.operation))throw new IOException("Original lifecycle request differs; retained");
            EmulatorGpsHandoff.Phase phase=EmulatorGpsHandoff.run(port,binding,approved);
            if(phase==EmulatorGpsHandoff.Phase.RESTORED)
                EmulatorGpsRequests.complete(port,request,port.get("entry"));
            output[0]="定位交接："+phase+"\n僅服務生命周期；舊定位排空／每日工作仍待核對。";
        });
        return output[0];
    }
    private static final class Port implements EmulatorGpsHandoff.Port,EmulatorGpsRequests.Store {
        private static String read(String command,int limit)throws Exception{return EmulatorGpsLifecycle.read(command,limit);}
        final Context c;final SharedPreferences journal;
        double latitude,longitude;int newPid;long newTicks;String newService="";
        EmulatorGpsClock originalClock,startClock;
        Port(Context c){this.c=c;journal=c.getSharedPreferences("mumu_gps_lifecycle_v1",0);}
        public String get(String key){return journal.getString(key,"");}
        public void pending(String request)throws Exception{
            if(!journal.edit().putString("pending",request).commit())throw new IOException("Request commit failed; no effect");
        }
        public void complete(String request,String wire)throws Exception{
            if(!journal.edit().putString("completed."+request,wire).remove("entry").commit())
                throw new IOException("Completion archive commit unknown; original request retained");
        }
        public EmulatorGpsHandoff.Entry read()throws Exception{
            String wire=journal.getString("entry","");
            if(wire.isEmpty())return null;
            if(wire.length()>4096)throw new IOException("Lifecycle journal exceeds bound; retained");
            String[] f=wire.split("\t",-1);
            if(f.length!=15||!f[0].equals("gl2"))throw new IOException("Original lifecycle journal missing clock boundary; retained, no automatic migration");
            EmulatorGpsHandoff.Binding b=new EmulatorGpsHandoff.Binding(f[1],Integer.parseInt(f[2]),Integer.parseInt(f[3]),Long.parseLong(f[4]),f[5],f[6]);
            latitude=Double.parseDouble(f[8]);longitude=Double.parseDouble(f[9]);newPid=Integer.parseInt(f[10]);newTicks=Long.parseLong(f[11]);newService=f[12];
            originalClock=EmulatorGpsClock.decode(f[13]);startClock=f[14].equals("-")?null:EmulatorGpsClock.decode(f[14]);
            if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180||newPid<0||newTicks<0||!newService.matches("[0-9a-f]*"))
                throw new IOException("Original lifecycle coordinates/process unknown");
            return new EmulatorGpsHandoff.Entry(b,EmulatorGpsHandoff.Phase.valueOf(f[7]));
        }
        public void persist(EmulatorGpsHandoff.Entry e)throws Exception{
            EmulatorGpsHandoff.Binding b=e.binding;
            if(originalClock==null)throw new IOException("Original clock boundary unknown");
            String wire="gl2\t"+b.boot+"\t"+b.gamePid+"\t"+b.oldPid+"\t"+b.oldStartTicks+"\t"+b.service+"\t"+b.operation+"\t"+e.phase+"\t"+latitude+"\t"+longitude+"\t"+newPid+"\t"+newTicks+"\t"+newService+"\t"+originalClock.encode()+"\t"+(startClock==null?"-":startClock.encode());
            // Keep each predecessor, including unknown effect intents. Never
            // clears data or erases a journal to make a new handoff possible.
            if(!journal.edit().putString("history."+b.operation+"."+e.phase,wire).putString("entry",wire).commit())
                throw new IOException("Lifecycle commit failed; no external effect");
        }
        void requireIdle()throws Exception{
            EmulatorRouteGuard.requireNoReservation();
            if(!("package:"+PKG+" uid:10060").equals(read("cmd package list packages -U --user 0 "+PKG,256)))
                throw new IOException("JoyStick UID changed");
            String apk=read("pm path "+PKG,4096);
            if(!apk.matches("package:/data/app/[A-Za-z0-9_~=/+.-]+/base\\.apk"))throw new IOException("JoyStick APK path unknown");
            if(!read("sha256sum '"+apk.substring(8)+"'",4096).startsWith("30133899429cbc1e670f4052181b3389132f7e43c091be6c401593238b50fc02  "))
                throw new IOException("JoyStick APK changed");
            if(!c.getSharedPreferences("pikmin_gps_work_lock",0).getString("owner","").isEmpty()||
               GpsWorkLock.anyHeld(c)||WorkStatus.stoppingAll||WorkStatus.blocked(c)||
               DispatchBatchService.isRunning()||FlowerFarmService.isRunning()||
               WorkProgress.active(WorkStatus.state(c,GpsWorkLock.BATCH))||WorkProgress.active(WorkStatus.state(c,GpsWorkLock.FLOWER_FARM))||
               RouteSession.pending(c))throw new IOException("Competing or retained GPS work");
            for(String name:new String[]{"dispatch-mode","return-mode","planter-mode","planting-mode","flower-farm-mode"}){
                String path="/data/local/tmp/pikmin-"+name+".txt";
                if(!"off".equals(read("test -f "+path+" && test ! -L "+path+" && cat "+path,64)))throw new IOException("Live control is not OFF");
            }
            FeedingConfig feeding=FeedingConfig.parse(read("cat /data/local/tmp/pikmin-feeding-config.tsv",65536));
            if(feeding.enabled||feeding.collect)throw new IOException("Feeding/collect work enabled");
        }
        public boolean allowed(EmulatorGpsHandoff.Binding b,EmulatorGpsHandoff.Phase phase)throws Exception{
            requireIdle();
            EmulatorGpsClock now=clock();
            if(originalClock==null||!originalClock.consistent(now)||(startClock!=null&&!startClock.consistent(now)))return false;
            if(pid("com.nianticlabs.pikmin")!=b.gamePid||!b.boot.equals(read("cat /proc/sys/kernel/random/boot_id",64)))return false;
            return phase!=EmulatorGpsHandoff.Phase.PREPARED||
                (pid(PKG+":overlay")==b.oldPid&&ticks(b.oldPid)==b.oldStartTicks&&service().equals(b.service));
        }
        public void stopOriginal(EmulatorGpsHandoff.Binding b)throws Exception{
            // Already inside EmulatorGpsFence: never nest ShellFence here.
            // Nonzero am exit alone is not the exit barrier.
            BoundedCommand.Result r=BoundedCommand.root("am stopservice --user 0 -n "+SERVICE,3,512);
            if(r==null||r.timedOut||r.truncated||r.exitCode==null)throw new IOException("Stop reply unknown; intent retained");
        }
        public boolean originalExited(EmulatorGpsHandoff.Binding b)throws Exception{
            for(int i=0;i<10;i++){
                if("absent".equals(read("if test ! -e /proc/"+b.oldPid+"; then printf absent; else printf present; fi",64))&&
                   pid(PKG+":overlay")==0&&service().isEmpty())return true;
                Thread.sleep(500);
            }
            return false;
        }
        public void startSavedPosition(EmulatorGpsHandoff.Binding b)throws Exception{
            String prefs="/data/user/0/"+PKG+"/shared_prefs/"+PKG+"_preferences.xml";
            if(!"saved".equals(read("test -f "+prefs+" && test ! -L "+prefs+" && grep -q 'name=\"last_latitude\"' "+prefs+" && grep -q 'name=\"last_longitude\"' "+prefs+" && printf saved",64)))
                throw new IOException("Saved position missing; start intent retained");
            startClock=clock();
            if(originalClock==null||!originalClock.consistent(startClock))throw new IOException("Clock offset changed; no start");
            persist(new EmulatorGpsHandoff.Entry(b,EmulatorGpsHandoff.Phase.START_INTENT));
            // Persist/readback the exact pre-start boundary, not a later fix.
            EmulatorGpsHandoff.Entry at=read();
            if(at==null||at.phase!=EmulatorGpsHandoff.Phase.START_INTENT||!at.binding.same(b)||startClock==null)
                throw new IOException("Pre-start clock boundary not confirmed");
            BoundedCommand.Result r=BoundedCommand.root("am start-foreground-service --user 0 -n "+SERVICE,3,512);
            if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0)
                throw new IOException("Start reply unknown; no replay or adoption of an unexplained service");
            for(int i=0;i<10;i++){
                int observed=pid(PKG+":overlay");String record=service();
                if(observed>0&&observed!=b.oldPid&&!record.isEmpty()&&!record.equals(b.service)){
                    newPid=observed;newTicks=ticks(observed);newService=record;
                    persist(new EmulatorGpsHandoff.Entry(b,EmulatorGpsHandoff.Phase.START_INTENT));
                    EmulatorGpsHandoff.Entry saved=read();
                    if(saved==null||saved.phase!=EmulatorGpsHandoff.Phase.START_INTENT||!saved.binding.same(b)||newPid!=observed||!newService.equals(record))
                        throw new IOException("New service binding not durably confirmed");
                    return;
                }
                Thread.sleep(500);
            }
            throw new IOException("Start submitted but new service identity unknown; original intent retained");
        }
        JoystickReadiness fix()throws Exception{return JoystickReadiness.parse(read("dumpsys location",65536),"10060/"+PKG);}
        public boolean restored(EmulatorGpsHandoff.Binding b)throws Exception{
            int observed=pid(PKG+":overlay");String record=service();
            if(startClock==null||newPid<=0||observed<=0||observed==b.oldPid||record.isEmpty()||record.equals(b.service))return false;
            long observedTicks=ticks(observed);
            if(newPid!=observed||newTicks!=observedTicks||!newService.equals(record)||
               !read("dumpsys activity services "+SERVICE,16384).contains("isForeground=true"))return false;
            JoystickReadiness first=fix();Thread.sleep(1500);JoystickReadiness last=fix();
            EmulatorGpsClock now=clock();
            double distance=111320*Math.hypot(last.latitude-latitude,(last.longitude-longitude)*Math.cos(latitude*Math.PI/180));
            if(!originalClock.consistent(now)||!startClock.consistent(now)||first.fixMs<startClock.minimumFixMs()||
               !first.fresh(SystemClock.elapsedRealtime())||!last.fresh(SystemClock.elapsedRealtime())||last.fixMs<=first.fixMs||!Double.isFinite(distance)||distance>5||
               pid(PKG+":overlay")!=observed||ticks(observed)!=observedTicks||!service().equals(record)||
               pid("com.nianticlabs.pikmin")!=b.gamePid||!b.boot.equals(read("cat /proc/sys/kernel/random/boot_id",64)))return false;
            newPid=observed;newTicks=observedTicks;newService=record;return true;
        }
    }
    private EmulatorGpsLifecycle(){}
}

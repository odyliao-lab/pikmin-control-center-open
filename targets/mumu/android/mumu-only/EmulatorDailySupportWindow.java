package dev.ody.pikmincontrol;

import android.content.Context;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;

/** MuMu-only effective native settings. Desired user preferences and native
 * RPC/pending ledgers are never changed here. The marker is ownership, NOT
 * readiness: any ON write still requires the original live identity gate. */
final class EmulatorDailySupportWindow {
    private static final String PREFS="daily_support_window_v1",KEY="owner";
    enum State { UNMANAGED, ACTIVE, PAUSED, UNKNOWN }
    static final class Marker {
        final String configSha;final EmulatorDailyRunner.Binding binding;
        Marker(EmulatorDailyConfiguration c){configSha=EmulatorDailyRunner.digest(c.encode());binding=c.binding;}
        private Marker(String sha,EmulatorDailyRunner.Binding b){if(!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException();configSha=sha;binding=b;}
        String encode(){String body="v1\t"+configSha+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\tend\n";return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
        // Android SharedPreferences pretty-printing can append indentation to
        // multiline strings. Keep the immutable marker wire inside a strict
        // single-line envelope; this correlation never grants readiness.
        String preference(){return "v2-daily-support-owner:"+EmulatorDailyRunner.base64(encode());}
        static Marker decodePreference(String raw){
            if(raw==null||raw.length()>4096)throw new IllegalArgumentException();
            String prefix="v2-daily-support-owner:";
            if(!raw.startsWith(prefix))return decode(raw); // exact legacy only; never trim or repair
            Marker marker=decode(EmulatorDailyRunner.unbase64(raw.substring(prefix.length())));
            if(!marker.preference().equals(raw))throw new IllegalArgumentException();return marker;
        }
        static Marker decode(String raw){
            if(raw==null||raw.length()>2048||raw.indexOf('\r')>=0)throw new IllegalArgumentException();
            String[] rows=raw.split("\n",-1);if(rows.length!=3||!rows[2].isEmpty())throw new IllegalArgumentException();
            String[] f=rows[0].split("\t",-1);if(f.length!=4||!f[0].equals("v1")||!f[3].equals("end"))throw new IllegalArgumentException();
            Marker m=new Marker(f[1],EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[2])));
            if(!m.encode().equals(raw))throw new IllegalArgumentException();return m;
        }
    }
    static boolean inWindow(long now){
        if(now<=0)throw new IllegalArgumentException();java.time.ZonedDateTime t=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        return EmulatorDailyPlan.active(now,true);
    }
    static State classify(String owner,EmulatorDailyConfiguration config,String ticketWire,boolean temporary,boolean stopped,long now){
        if(owner==null)return State.UNMANAGED;
        try{
            Marker m=Marker.decodePreference(owner);
            if(config==null||!m.binding.same(config.binding)||!m.configSha.equals(EmulatorDailyRunner.digest(config.encode()))||temporary)return State.UNKNOWN;
            EmulatorDailySchedule.Ticket ticket=EmulatorDailySchedule.Ticket.decode(ticketWire,now);
            if(!ticket.configSha.equals(m.configSha))return State.UNKNOWN;
            return !stopped&&inWindow(now)?State.ACTIVE:State.PAUSED;
        }catch(Exception unknown){return State.UNKNOWN;}
    }
    static FeedingConfig effective(FeedingConfig wanted,State state){
        FeedingConfig result=wanted.copy();
        if(state==State.PAUSED||state==State.UNKNOWN){result.enabled=false;result.collect=false;result.rotate=false;}
        return result;
    }
    static String effective(String wanted,State state){
        if(!wanted.equals("auto")&&!wanted.equals("pluck")&&!wanted.equals("off"))throw new IllegalArgumentException();
        return state==State.PAUSED||state==State.UNKNOWN?"off":wanted;
    }
    static boolean effective(boolean wanted,State state){
        return wanted&&(state==State.UNMANAGED||state==State.ACTIVE);
    }
    static boolean action(Context c,boolean wanted,boolean writing)throws Exception {
        State state=state(c);boolean actual=effective(wanted,state);
        if(writing&&actual&&state==State.ACTIVE)authorize(c);
        return actual;
    }
    static String choice(Context c,String key,String wanted,boolean writing)throws Exception {
        if(!key.equals("nearby")&&!key.equals("reward")&&!key.equals("nectar"))throw new IllegalArgumentException("Scheduled action key required");
        AutomationPreferences.validate(key,wanted);
        return action(c,!wanted.equals("off"),writing)?wanted:"off";
    }
    static void configure(Context c,EmulatorDailyConfiguration config)throws Exception{
        isolated(c);Marker marker=new Marker(config);String raw=marker.preference();Object old=c.getSharedPreferences(PREFS,0).getAll().get(KEY);
        if(old!=null&&(!(old instanceof String)||!marker.encode().equals(Marker.decodePreference((String)old).encode())))
            throw new IllegalStateException("Existing helper owner needs separate review; never adopt");
        if(old==null&&!c.getSharedPreferences(PREFS,0).edit().putString(KEY,raw).commit())throw new IllegalStateException("Helper owner save unconfirmed");
        // Preserve an exact legacy owner; never rewrite/repair it implicitly.
        if(!(old==null?raw:old).equals(c.getSharedPreferences(PREFS,0).getAll().get(KEY)))throw new IllegalStateException("Helper owner readback differs");
    }
    private static String owner(Context c){Object raw=c.getSharedPreferences(PREFS,0).getAll().get(KEY);return raw==null?null:raw instanceof String?(String)raw:"invalid-owner";}
    private static boolean managed(Context c){return "dev.ody.pikmincontrol.mumu".equals(c.getPackageName())&&owner(c)!=null;}
    static State state(Context c){
        try{EmulatorSavedDailyStore s=EmulatorSavedDailyStore.open(c);if(s!=null&&s.current())return s.enabled()&&inWindow(System.currentTimeMillis())?State.ACTIVE:State.PAUSED;}catch(Exception e){return State.UNKNOWN;}
        if(!managed(c))return State.UNMANAGED;
        try{
            String marker=owner(c);EmulatorDailyConfiguration config=EmulatorAndroidDailyConfigured.read(c);
            Path root=EmulatorAndroidDailyStore.files(c).resolve("daily-schedule");privateMode(c,root,true);
            Path leaf=root.resolve("ticket.tsv");String first=read(c,leaf);
            EmulatorDailySchedule.Ticket ticket=EmulatorDailySchedule.Ticket.decode(first,System.currentTimeMillis());
            boolean temporary=Files.exists(root.resolve("ticket.tsv.tmp"),LinkOption.NOFOLLOW_LINKS);
            boolean stopped=Files.exists(root.resolve(ticket.id+".stop"),LinkOption.NOFOLLOW_LINKS);
            if(!first.equals(read(c,leaf))||!marker.equals(owner(c)))return State.UNKNOWN;
            return classify(marker,config,first,temporary,stopped,System.currentTimeMillis());
        }catch(Exception unknown){return State.UNKNOWN;}
    }
    static FeedingConfig feeding(Context c,FeedingConfig wanted,boolean writing)throws Exception{
        State state=state(c);FeedingConfig actual=effective(wanted,state);
        if(writing&&state==State.ACTIVE&&(actual.enabled||actual.collect||actual.rotate))authorize(c);
        return actual;
    }
    static String planter(Context c,String wanted,boolean writing)throws Exception{
        State state=state(c);String actual=effective(wanted,state);
        if(writing&&state==State.ACTIVE&&!actual.equals("off"))authorize(c);
        return actual;
    }
    private static void authorize(Context c)throws Exception{
        EmulatorSavedDailyStore saved=EmulatorSavedDailyStore.open(c);if(saved!=null&&saved.current()){if(!saved.enabled())throw new IllegalStateException("Saved schedule paused; no legacy authorization");new EmulatorSavedDailyRuntime(c,saved).game();return;}
        Marker marker=Marker.decodePreference(owner(c));
        EmulatorDailyRouteWork.requireIdentity(c,marker.binding); // actual core/account/PID/session/APK, never a cached permit
        if(state(c)!=State.ACTIVE)throw new IllegalStateException("Daily helper boundary/owner changed before ON write");
    }
    static void refresh(Context c)throws Exception{
        if(!managed(c))return;
        // A finished batch with an unresolved result must block new work, not
        // block the restrictive rest-window OFF transition. Preserve its
        // checkpoint/nearby override and never use this path for an ON write.
        if(state(c)==State.PAUSED&&!inWindow(System.currentTimeMillis())){
            pauseForRest(c);PlanterSettings.reconcile(c);return;
        }
        // Reuse the original atomic restore coordinator and durable GPS owner;
        // do not call settings writers directly from the daily facts reader.
        String pid=CoreUpdater.runtimePid(c,CoreUpdater.pair(c));
        if(!pid.isEmpty())SettingsRestoreJob.restoreForPid(c,pid);
        PlanterSettings.reconcile(c); // original pending-ledger review and paired controls
    }
    static boolean restStopAllowed(State state,boolean activeWindow,boolean owner,boolean route,boolean batch,boolean installing){
        return state==State.PAUSED&&!activeWindow&&!owner&&!route&&!batch&&!installing;
    }
    private static void pauseForRest(Context c)throws Exception{
        isolated(c);
        // The normal GPS acquire deliberately rejects WorkStatus.blocked.
        // Serialize only this OFF-only, idle transaction with that same monitor;
        // do not acquire/adopt/clear an unresolved work owner or its ledgers.
        synchronized(GpsWorkLock.class){
            boolean installing=CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)||SetupDeployment.pending(c)||UpdateInstaller.pending(c)||WorkStatus.stoppingAll;
            if(!restStopAllowed(state(c),inWindow(System.currentTimeMillis()),GpsWorkLock.anyHeld(c),FlowerFarmService.isRunning(),DispatchBatchService.isRunning(),installing))return;
            Marker held=Marker.decodePreference(owner(c));
            EmulatorDailyRouteWork.requireIdentity(c,held.binding);
            Object raw=c.getSharedPreferences("feeding",0).getAll().get("config");
            if(!(raw instanceof String))throw new IllegalStateException("Preserved feeding selection required for rest");
            FeedingConfig off=FeedingConfig.parse((String)raw);off.enabled=false;off.collect=false;off.rotate=false;
            String wire=off.encode();
            String[] before=ProbeSnapshot.sections(FeedingGateway.root(ProbeSnapshot.command("cat "+FeedingGateway.CONTROL,"cat /data/local/tmp/pikmin-return-mode.txt")),2);
            if(state(c)!=State.PAUSED||inWindow(System.currentTimeMillis())||!held.encode().equals(Marker.decodePreference(owner(c)).encode()))throw new IllegalStateException("Rest ownership changed before OFF");
            if(!before[0].trim().equals(wire.trim())){
                String encoded=java.util.Base64.getEncoder().encodeToString(wire.getBytes(StandardCharsets.UTF_8));
                FeedingGateway.root("printf '%s' '"+encoded+"' | base64 -d > "+FeedingGateway.CONTROL+".tmp && chmod 0644 "+FeedingGateway.CONTROL+".tmp && mv "+FeedingGateway.CONTROL+".tmp "+FeedingGateway.CONTROL);
            }
            if(!before[1].trim().equals("off"))FeedingGateway.root(ReturnRewardModuleGateway.modeCommand("off"));
            String[] after=ProbeSnapshot.sections(FeedingGateway.root(ProbeSnapshot.command("cat "+FeedingGateway.CONTROL,"cat /data/local/tmp/pikmin-return-mode.txt")),2);
            if(!after[0].trim().equals(wire.trim())||!after[1].trim().equals("off"))throw new IllegalStateException("Rest OFF readback unconfirmed");
            EmulatorDailyRouteWork.requireIdentity(c,held.binding);
            // No desired preference, pending request, receipt or lifecycle write.
        }
    }
    static void release(Context c,EmulatorDailySchedule.Ticket held)throws Exception{
        if(!managed(c))return;Marker marker=Marker.decodePreference(owner(c));if(!marker.configSha.equals(held.configSha))return;
        Path root=EmulatorAndroidDailyStore.files(c).resolve("daily-schedule");privateMode(c,root,true);
        String current=read(c,root.resolve("ticket.tsv"));
        if(!current.equals(held.encode()))return; // late STOP must not release a newer ticket's helper owner
        String raw=read(c,root.resolve(held.id+".stop"));
        if(!stopped(held,current,raw,System.currentTimeMillis()))return;
        if(!current.equals(read(c,root.resolve("ticket.tsv")))||!raw.equals(read(c,root.resolve(held.id+".stop"))))return;
        if(!c.getSharedPreferences(PREFS,0).edit().remove(KEY).commit())throw new IllegalStateException("Helper release unconfirmed");
    }
    static boolean stopped(EmulatorDailySchedule.Ticket held,String current,String raw,long now){
        if(!held.encode().equals(EmulatorDailySchedule.Ticket.decode(current,now).encode()))return false;
        String[] rows=raw.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))throw new IllegalStateException("Exact stopped helper owner required");
        String[] f=rows[0].split("\t",-1);
        if(f.length!=5||!f[0].equals("v1")||!f[1].equals(held.id)||!f[2].equals(EmulatorDailyRunner.digest(held.encode()))||!f[4].equals("stop")||Long.parseLong(f[3])<held.created||Long.parseLong(f[3])>now)throw new IllegalStateException("Exact stopped helper owner required");
        long at=Long.parseLong(f[3]);String body="v1\t"+held.id+"\t"+EmulatorDailyRunner.digest(held.encode())+"\t"+at+"\tstop\n";
        if(!raw.equals(body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n"))throw new IllegalStateException("Canonical stopped helper owner required");return true;
    }
    private static String read(Context c,Path path)throws Exception{
        privateMode(c,path,false);
        try(FileChannel in=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()<1||in.size()>2048)throw new IllegalStateException();ByteBuffer b=ByteBuffer.allocate(2049);
            while(b.hasRemaining()&&in.read(b)>0){}if(b.position()>2048)throw new IllegalStateException();
            byte[] bytes=java.util.Arrays.copyOf(b.array(),b.position());String raw=new String(bytes,StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException();return raw;
        }
    }
    private static void privateMode(Context c,Path p,boolean dir)throws Exception{
        if(Files.isSymbolicLink(p)||((Number)Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=c.getApplicationInfo().uid||
                !Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(dir?"rwx------":"rw-------"))||
                (dir?!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)))throw new IllegalStateException("Private helper schedule required");
    }
    private static void isolated(Context c){EmulatorRuntime.require();if(c==null||!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))throw new IllegalArgumentException("MuMu helpers only");}
}

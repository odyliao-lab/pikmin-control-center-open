package dev.ody.pikmincontrol;

import android.content.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** New saved-name namespace. Never adopts or edits old route/batch journals. */
final class EmulatorSavedDailyStore {
    static final String RUNNING="RUNNING",STOP_REQUESTED="STOP_REQUESTED",DRAINING="DRAINING",STOPPED="STOPPED";
    final Context app;final Path root;final String wire,run;final EmulatorSavedRoutePlan plan;
    final SharedPreferences journal;
    private EmulatorSavedDailyStore(Context c,String wire)throws Exception{
        EmulatorRuntime.require();app=c.getApplicationContext();
        if(!"dev.ody.pikmincontrol.mumu".equals(app.getPackageName()))throw new SecurityException();
        String[] f=wire.split("\t",-1);
        if(f.length!=4||!f[0].equals("saved-daily-v1")||!UUID.fromString(f[1]).toString().equals(f[1])||!f[3].equals("end\n"))throw new IOException("Saved daily config malformed");
        run=f[1];EmulatorSavedRoutePlan selected=null;for(EmulatorSavedRoutePlan.City city:EmulatorSavedRoutePlan.City.values())if(city.savedName.equals(f[2]))selected=new EmulatorSavedRoutePlan(city);
        if(selected==null)throw new IOException("Saved route not approved");plan=selected;this.wire=wire;
        root=app.getFilesDir().toPath().resolve("saved-daily");journal=app.getSharedPreferences("saved_daily_run_"+run,0);
    }
    static EmulatorSavedDailyStore open(Context c)throws Exception{
        Path p=c.getFilesDir().toPath().resolve("saved-daily/config.tsv");
        if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return null;
        if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>256)throw new IOException("Private config required");
        return new EmulatorSavedDailyStore(c,new String(Files.readAllBytes(p),StandardCharsets.UTF_8));
    }
    static EmulatorSavedDailyStore create(Context c,EmulatorSavedRoutePlan plan)throws Exception{
        return create(c,plan,"");
    }
    static EmulatorSavedDailyStore create(Context c,EmulatorSavedRoutePlan plan,String rotationPlan)throws Exception{
        synchronized(GpsWorkLock.class){
        EmulatorRuntime.require();EmulatorSavedDailyStore prior=open(c);
        if(prior!=null&&!prior.replaceable())throw new IOException("Previous saved run has not completed its drain");
        if(prior!=null&&(!prior.confirmedStop()||!prior.drained()||EmulatorSavedDailyService.isRunning()))
            throw new IOException("Previous schedule still draining; originals retained");
        if(prior!=null){EmulatorSavedDailyRuntime.requireGpsRetirementReady(c,prior);EmulatorSavedDailyRuntime.requireBatchRetirementReady(c,prior);EmulatorDeadPlantingRecovery.requireStartReady(c,prior);}
        String raw="saved-daily-v1\t"+UUID.randomUUID()+"\t"+plan.savedName()+"\tend\n";
        EmulatorSavedDailyStore next=new EmulatorSavedDailyStore(c,raw);
        next.put("run.created-at",Long.toString(System.currentTimeMillis()));
        if(!rotationPlan.isEmpty())next.put("rotation.plan",EmulatorSavedRouteRotation.decode(rotationPlan).encode());
        else if(prior!=null&&!prior.get("rotation.plan","").isEmpty()){
            String rotation=EmulatorSavedRouteRotation.decode(prior.get("rotation.plan","")).encode();
            next.put("rotation.plan",rotation);
        }
        if(!Files.exists(next.root)){Files.createDirectory(next.root);mode(next.root,true);EmulatorAndroidDailyStore.syncDirectory(next.root.getParent());}
        next.fields("run.lifecycle",RUNNING,"steps.scope","none");
        // Explicit new START may resume the exact paused route from our stopped
        // predecessor. Foreign routes, unresolved commands and another city are
        // never adopted. Runtime still checks the original provider incarnation.
        if(prior!=null&&prior.plan.city==plan.city&&prior.get("gps.phase","").equals("PAUSED")&&
                prior.get("gps.pending","").isEmpty()&&!prior.get("gps.provider","").isEmpty()){
            if(!next.journal.edit().putString("gps.phase","PAUSED").putString("gps.provider",prior.get("gps.provider",""))
                    .putString("gps.explicit-handoff",prior.run)
                    .putString("gps.route-name",prior.get("gps.route-name",prior.plan.savedName()))
                    .putString("rotation.slot",prior.get("rotation.slot",""))
                    .putString("rotation.settling",prior.get("rotation.settling",""))
                    .putString("rotation.sent-at",prior.get("rotation.sent-at","0"))
                    .putString("rotation.sent-elapsed",prior.get("rotation.sent-elapsed","0")).commit())throw new IOException("Paused predecessor handoff unconfirmed");
        }
        // Publish last: a crash must never expose a successor without the
        // durable predecessor/provider evidence needed to recover its lock.
        Path temp=next.root.resolve("config."+next.run+".tmp");writeNew(temp,raw);
        Files.move(temp,next.root.resolve("config.tsv"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);EmulatorAndroidDailyStore.syncDirectory(next.root);
        if(!next.enabled())throw new IOException("Saved config readback failed");
        return next;
        }
    }
    boolean confirmedStop()throws Exception{
        return stopMatches(root.resolve(run+".stop"),wire);
    }
    static boolean stopMatches(Path p,String wire)throws IOException{
        return Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(p)&&Files.size(p)<=256&&
            wire.equals(new String(Files.readAllBytes(p),StandardCharsets.UTF_8));
    }
    boolean drained(){
        for(String lane:new String[]{"gps","gps-bootstrap","plant","batch","batch-close"})if(!get(lane+".pending","").isEmpty())return false;
        return true;
    }
    EmulatorSavedDailyStore stoppedPredecessor()throws Exception{
        String previous=get("gps.explicit-handoff","");
        if(previous.isEmpty()||!UUID.fromString(previous).toString().equals(previous)||previous.equals(run))throw new IOException("Explicit predecessor required");
        EmulatorSavedDailyStore prior=new EmulatorSavedDailyStore(app,"saved-daily-v1\t"+previous+"\t"+plan.savedName()+"\tend\n");
        if(!prior.confirmedStop()||!prior.drained()||!prior.get("gps.phase","").equals("PAUSED")||
            prior.get("gps.provider","").isEmpty()||!prior.get("gps.provider","").equals(get("gps.provider","")))throw new IOException("Stopped predecessor evidence differs");
        return prior;
    }
    boolean current()throws Exception{return wire.equals(new String(Files.readAllBytes(root.resolve("config.tsv")),StandardCharsets.UTF_8));}
    static String lifecycleOf(String saved,boolean marked)throws IOException{
        if(!RUNNING.equals(saved)&&!STOP_REQUESTED.equals(saved)&&!DRAINING.equals(saved)&&!STOPPED.equals(saved))throw new IOException("Unknown saved lifecycle");
        if(marked&&RUNNING.equals(saved))return STOP_REQUESTED;
        if(!marked&&!RUNNING.equals(saved))throw new IOException("Stop marker missing; state retained");
        return saved;
    }
    boolean marked()throws IOException{
        Path p=root.resolve(run+".stop");if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS))return false;
        if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>256||!wire.equals(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)))throw new IOException("Exact stop marker required");
        return true;
    }
    String lifecycle()throws IOException{return lifecycleOf(get("run.lifecycle",RUNNING),marked());}
    boolean enabled()throws Exception{return current()&&RUNNING.equals(lifecycle());}
    boolean needsDrain()throws Exception{String phase=lifecycle();return current()&&(STOP_REQUESTED.equals(phase)||DRAINING.equals(phase));}
    static boolean hasPending(SharedPreferences prefs){synchronized(prefs){for(java.util.Map.Entry<String,?> e:prefs.getAll().entrySet())if(e.getKey().endsWith(".pending")&&(!(e.getValue() instanceof String)||!((String)e.getValue()).isEmpty()))return true;return false;}}
    boolean hasPending(){return hasPending(journal);}
    void fields(String... fields)throws IOException{
        if(fields==null||(fields.length&1)!=0)throw new IllegalArgumentException("Key/value pairs required");
        synchronized(journal){
            java.util.HashSet<String> seen=new java.util.HashSet<>();SharedPreferences.Editor edit=journal.edit();
            for(int n=0;n<fields.length;n+=2){if(fields[n]==null||fields[n].isEmpty()||fields[n+1]==null||!seen.add(fields[n]))throw new IllegalArgumentException("Unique fields required");edit.putString(fields[n],fields[n+1]);}
            if(!edit.commit())throw new IOException("Journal commit unconfirmed");requireReadback(journal,fields);
        }
    }
    String activeIntent(String lane,String action,String... fields)throws Exception{synchronized(EmulatorSavedDailyStore.class){if(!enabled())throw new IOException("Saved admissions closed");return intent(lane,action,fields);}}
    void stop()throws Exception{
        synchronized(EmulatorSavedDailyStore.class){
            if(!current())throw new IOException("Original saved run is not current");Path p=root.resolve(run+".stop");
            if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){writeNew(p,wire);EmulatorAndroidDailyStore.syncDirectory(root);}
            if(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||Files.size(p)>256||!wire.equals(new String(Files.readAllBytes(p),StandardCharsets.UTF_8)))throw new IOException("Exact stop marker required");
            if(RUNNING.equals(get("run.lifecycle",RUNNING)))fields("run.lifecycle",STOP_REQUESTED);
        }
    }
    void beginDrain()throws Exception{synchronized(EmulatorSavedDailyStore.class){stop();if(STOP_REQUESTED.equals(lifecycle()))fields("run.lifecycle",DRAINING);}}
    boolean completeStop(boolean closure)throws Exception{synchronized(EmulatorSavedDailyStore.class){synchronized(journal){if(!closure||!current()||!DRAINING.equals(lifecycle())||hasPending())return false;fields("run.lifecycle",STOPPED,"run.closure","owned-controls-closed; gameplay-ledgers-preserved");return STOPPED.equals(lifecycle())&&!hasPending();}}}
    boolean replaceable()throws Exception{return current()&&STOPPED.equals(lifecycle())&&!hasPending();}
    void put(String key,String value)throws IOException{synchronized(journal){if(!journal.edit().putString(key,value).commit())throw new IOException("Run journal commit unconfirmed");}}
    String get(String key,String fallback){return journal.getString(key,fallback);}
    String intent(String lane,String action)throws Exception{return intent(lane,action,new String[0]);}
    String intent(String lane,String action,String... fields)throws Exception{synchronized(EmulatorSavedDailyStore.class){
        if(!current()||STOPPED.equals(lifecycle()))throw new IOException("Saved drain is already closed");
        return beginIntent(journal,lane,action,fields);
    }}
    void confirmed(String lane,String result)throws Exception{
        synchronized(journal){String id=get(lane+".pending","");if(!id.isEmpty())confirmIntent(journal,lane,id,result);}
    }
    void confirmed(String lane,String expectedId,String result,String... fields)throws Exception{confirmIntent(journal,lane,expectedId,result,fields);}
    // Commit correlation fields with the intent, before any external effect.
    // Lock the shared preferences instance, not one of potentially many Store wrappers.
    static String beginIntent(SharedPreferences journal,String lane,String action,String... fields)throws IOException{
        requireFields(lane,fields);
        if(journal==null||action==null||action.isEmpty())throw new IllegalArgumentException("Complete intent required");
        synchronized(journal){
            if(!journal.getString(lane+".pending","").isEmpty())throw new IOException("Unconfirmed lane intent retained");
            String id=UUID.randomUUID().toString();
            SharedPreferences.Editor edit=journal.edit().putString(lane+".pending",id).putString("intent."+id,action);
            putFields(edit,fields);
            if(!edit.commit())throw new IOException("Intent commit failed");
            if(!id.equals(journal.getString(lane+".pending",""))||!action.equals(journal.getString("intent."+id,"")))throw new IOException("Intent readback differs");
            requireReadback(journal,fields);return id;
        }
    }
    static void confirmIntent(SharedPreferences journal,String lane,String expectedId,String result,String... fields)throws IOException{
        requireFields(lane,fields);
        if(journal==null||expectedId==null||expectedId.isEmpty()||result==null||result.isEmpty())throw new IllegalArgumentException("Exact confirmation required");
        synchronized(journal){
            if(!expectedId.equals(journal.getString(lane+".pending","")))throw new IOException("Pending intent changed; confirmation refused");
            SharedPreferences.Editor edit=journal.edit().putString("result."+expectedId,result).remove(lane+".pending");
            putFields(edit,fields);
            if(!edit.commit())throw new IOException("Confirmation commit failed");
            if(!journal.getString(lane+".pending","").isEmpty()||!result.equals(journal.getString("result."+expectedId,"")))throw new IOException("Confirmation readback differs");
            requireReadback(journal,fields);
        }
    }
    private static void requireFields(String lane,String[] fields){
        if(lane==null||!lane.matches("[a-z][a-z0-9-]*")||fields==null||(fields.length&1)!=0)throw new IllegalArgumentException("Canonical lane fields required");
        java.util.HashSet<String> seen=new java.util.HashSet<>();
        for(int i=0;i<fields.length;i+=2){String key=fields[i];
            if(key==null||!key.startsWith(lane+".")||key.equals(lane+".pending")||fields[i+1]==null||!seen.add(key))throw new IllegalArgumentException("Distinct lane metadata required");
        }
    }
    private static void putFields(SharedPreferences.Editor edit,String[] fields){for(int i=0;i<fields.length;i+=2)edit.putString(fields[i],fields[i+1]);}
    private static void requireReadback(SharedPreferences journal,String[] fields)throws IOException{
        for(int i=0;i<fields.length;i+=2)if(!fields[i+1].equals(journal.getString(fields[i],null)))throw new IOException("Lane metadata readback differs");
    }
    String guard(boolean active)throws Exception{
        if(active&&!enabled())throw new IOException("Saved schedule stopped");
        String p=root.resolve("config.tsv").toString();String hash=EmulatorDailyRunner.digest(wire);
        return "test -f '"+p+"' || exit 1; test ! -L '"+p+"' || exit 1; test \"$(sha256sum '"+p+"'|cut -d' ' -f1)\" = "+hash+" || exit 1; "+
            (active?"test ! -e '"+root.resolve(run+".stop")+"' || exit 1; test ! -L '"+root.resolve(run+".stop")+"' || exit 1; ":"");
    }
    private static void mode(Path p,boolean dir)throws Exception{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));}
    private static void writeNew(Path p,String text)throws Exception{
        try(java.nio.channels.FileChannel out=java.nio.channels.FileChannel.open(p,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode(p,false);java.nio.ByteBuffer b=java.nio.ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));while(b.hasRemaining())out.write(b);out.force(true);
        }
    }
}

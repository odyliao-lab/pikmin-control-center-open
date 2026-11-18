package dev.ody.pikmincontrol;

import android.content.Context;
import java.util.*;

/** Complete-candidate projection. Android briefly owns the original batch lock
 * and pauses/restores nearby dispatch; no scan request, selection, GPS movement
 * or dispatch is issued. Empty is valid only with matching independently committed
 * full-scan metadata; it NEVER proves prior claims collected. */
final class EmulatorDailyBatchScan {
    static final int MAX_BYTES=262144,MAX_TASKS=512;
    private static final String ROOT="/data/user/0/com.nianticlabs.pikmin/files";
    static final class Snapshot {
        final EmulatorDailyRunner.Binding binding;
        final long scanMs;
        final int inventoryCount;
        final List<DispatchProbeGateway.Candidate> candidates,located;
        final Set<String> unlocated;
        Snapshot(EmulatorDailyRunner.Binding b,long ms,int count,List<DispatchProbeGateway.Candidate> all,List<DispatchProbeGateway.Candidate> located,Set<String> unlocated){
            binding=b;scanMs=ms;inventoryCount=count;
            candidates=Collections.unmodifiableList(new ArrayList<>(all));this.located=Collections.unmodifiableList(new ArrayList<>(located));
            this.unlocated=Collections.unmodifiableSet(new HashSet<>(unlocated));
        }
    }
    interface SampleSource { String[] next()throws Exception; long now(); }
    static Snapshot read(Context c,EmulatorDailyRunner.Binding held)throws Exception {
        if(c==null||!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))bad();
        EmulatorDailyRouteWork.requireIdentity(c,held);
        return EmulatorDailyBatchScanScope.read(new EmulatorDailyBatchScanScope.Access(){
            public void acquire()throws Exception {
                synchronized(GpsWorkLock.class){
                    if(GpsWorkLock.anyHeld(c)||NearbyWorkState.pending(c)||DispatchBatchService.isRunning()||FlowerFarmService.isRunning())
                        throw new IllegalStateException("Existing work or restore record preserved");
                    GpsWorkLock.acquire(c,GpsWorkLock.BATCH);
                }
            }
            public void capture()throws Exception {NearbyWorkState.capture(c,GpsWorkLock.BATCH);}
            public void pause()throws Exception {
                // Use the existing bounded root gateway, not the older
                // unbounded dispatch writer; preserve the control-file contract.
                GpsModuleGateway.farmRead("set -e; p=/data/local/tmp/pikmin-dispatch-mode.txt; "
                    +"test -f \"$p\" && test ! -L \"$p\" && test \"$(stat -c %a:%h \"$p\")\" = 644:1 && "
                    +"printf 'off\\n' > \"$p\" && chmod 0644 \"$p\" && test \"$(cat \"$p\")\" = off");
            }
            public void settle()throws Exception {
                // With nearby OFF, the existing native full scan is five seconds.
                // Allow that scan plus its separate capability heartbeat to publish.
                // This is real elapsed waiting, NOT a longer packet TTL or fake clock.
                long start=android.os.SystemClock.elapsedRealtime();
                while(android.os.SystemClock.elapsedRealtime()-start<7000){
                    if(WorkStatus.stoppingAll||!GpsWorkLock.heldBy(c,GpsWorkLock.BATCH))
                        throw new IllegalStateException("Scan stopped or ownership changed");
                    GpsWorkLock.touch(c,GpsWorkLock.BATCH);Thread.sleep(100);
                }
                EmulatorDailyRouteWork.requireIdentity(c,held);
            }
            public void restore()throws Exception {
                NearbyWorkState.restore(c,GpsWorkLock.BATCH);
                if(NearbyWorkState.pending(c))throw new IllegalStateException("Original restore record retained");
            }
            public void release(){GpsWorkLock.release(c,GpsWorkLock.BATCH);}
        },()->readUnowned(c,held));
    }
    private static Snapshot readUnowned(Context c,EmulatorDailyRunner.Binding held)throws Exception {
        Snapshot result=read(new SampleSource(){
            public String[] next()throws Exception {
                EmulatorDailyRouteWork.requireIdentity(c,held);
                return EmulatorOwnedPetalGateway.sections(GpsModuleGateway.farmRead(command()),8);
            }
            public long now(){return System.currentTimeMillis();}
        },held);
        EmulatorDailyRouteWork.requireIdentity(c,held);return result;
    }
    /** Only whole fresh reads may be retried, never fields from a rejected read.
     * Three attempts bound the existing publisher's cross-file update window.
     * Transport/permission failures propagate; no empty fallback or effects. */
    static Snapshot read(SampleSource source,EmulatorDailyRunner.Binding held)throws Exception {
        if(source==null||held==null)bad();
        for(int attempt=0;attempt<3;attempt++){
            try{return decode(source.next(),held,source.now());}
            catch(IllegalArgumentException incomplete){if(attempt==2)throw incomplete;}
        }
        throw new IllegalStateException("Bounded scan exhausted");
    }
    static String command() {
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        return ProbeSnapshot.command(identity,EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),
            EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),bounded("dispatch_probe_status.tsv",256),
            bounded("dispatch_candidates.tsv",MAX_BYTES),EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),
            EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),identity);
    }
    static Snapshot decode(String[] p,EmulatorDailyRunner.Binding b,long now) {
        if(b==null||p==null||p.length!=8||now<=0)bad();
        String pid=Integer.toString(b.pid),identity=pid+"\n"+b.boot+"\n";
        if(!identity.equals(p[0])||!identity.equals(p[7])||!b.account.equals(SessionIdentity.parse(p[1],pid,pid,now))||
            !b.account.equals(SessionIdentity.parse(p[6],pid,pid,now)))bad();
        CoreCapabilities before=caps(p[2],pid,b,now),after=caps(p[5],pid,b,now);
        if(before.scanMs!=after.scanMs||before.scanCount!=after.scanCount||before.scanEpoch!=after.scanEpoch)bad();
        String[] status=line(unpack(p[3],256),256,4);
        if(num(status[0])!=before.scanMs||!Arrays.asList("observed","armed","batch").contains(status[1])||
            !Arrays.asList("no-dispatch","start-requested","selection-applied","awaiting-arrival-gate").contains(status[3]))bad();
        long count=num(status[2]);if(count>before.scanCount||count>MAX_TASKS)bad();
        String raw=unpack(p[4],MAX_BYTES);if(raw.indexOf('\r')>=0||(!raw.isEmpty()&&!raw.endsWith("\n")))bad();
        String[] rows=raw.isEmpty()?new String[0]:raw.substring(0,raw.length()-1).split("\n",-1);
        if(rows.length!=count)bad();
        List<DispatchProbeGateway.Candidate> all=new ArrayList<>(),located=new ArrayList<>();Set<String> ids=new HashSet<>(),unlocated=new HashSet<>();
        for(String row:rows){
            String[] f=line(row+"\n",1024,12);
            if(num(f[0])!=before.scanMs||!Arrays.asList("seed","fruit","gift").contains(f[1])||!f[2].matches("[A-Za-z0-9_-]{1,160}")||!ids.add(f[2])||
                !f[3].equals("0")||!f[7].equals("pending-travel-estimate")||!(f[10].equals("0")||f[10].equals("1")))bad();
            long expiration=num(f[4]);double lat=decimal(f[5]),lng=decimal(f[6]),distance=decimal(f[8]);
            // Native writes the game's signed estimate verbatim (including
            // negative values before a settled selection). It is NOT an
            // admission gate or completion proof; preserve every candidate.
            if(!FarmLocation.point(lat,lng)||distance< -1)bad();signed(f[9]);num(f[11]);
            DispatchProbeGateway.Candidate candidate=new DispatchProbeGateway.Candidate(f[2],f[1],expiration,lat,lng,distance);
            all.add(candidate);if(lat==0&&lng==0)unlocated.add(f[2]);else located.add(candidate);
        }
        return new Snapshot(b,before.scanMs,before.scanCount,all,located,unlocated);
    }
    private static CoreCapabilities caps(String raw,String pid,EmulatorDailyRunner.Binding b,long now) {
        CoreCapabilities c=EmulatorDailyCore.capabilities(raw,b,now);
        if(!c.ok()||!c.identityReady()||!c.domainReady()||!c.stepOk("expedition.methods")||!c.stepOk("return.task_list_time")||c.sessionEpoch!=b.sessionEpoch||!c.scanCurrent(now)||
            c.scanMs>now||now-c.scanMs>12000||c.scanCount>MAX_TASKS)bad();return c;
    }
    static String bounded(String leaf,int maximum) {
        if(!(leaf.equals("dispatch_candidates.tsv")&&maximum==MAX_BYTES||leaf.equals("dispatch_probe_status.tsv")&&maximum==256))bad();
        String path=ROOT+"/"+leaf;
        return "set -e; test -d "+ROOT+" && test ! -L "+ROOT+" && g=$(stat -c %u "+ROOT+") && "
            +"case \"$g\" in ''|*[!0-9]*) exit 1;; esac && test \"$g\" -ge 10000 && "
            +"test -f "+path+" && test ! -L "+path+" && exec 3<"+path+" && "
            +"IFS=' ' read -r held_pid held_stat < /proc/self/stat && case \"$held_pid\" in ''|*[!0-9]*) exit 1;; esac && "
            +"p=/proc/$held_pid/fd/3 && test \"$(stat -Lc %u:%g:%a:%h $p)\" = \"$g:$g:644:1\" && n=$(stat -Lc %s $p) && "
            +"case \"$n\" in ''|*[!0-9]*) exit 1;; esac && test \"$n\" -le "+maximum+" && "
            +"b=$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p) && raw=$(cat <&3) && "
            +"if test \"$n\" -eq 0; then test -z \"$raw\"; else test \"$n\" -eq $(( ${#raw} + 1 )); fi && "
            +"test \"$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p)\" = \"$b\" && "
            +"test ! -L "+path+" && test -f "+path+" && test \"$(stat -c %d:%i:%s:%Y:%Z:%u:%g:%a:%h "+path+")\" = \"$b\" && "
            +"printf 'v1-read\\t%s\\n' \"$n\" && if test \"$n\" -gt 0; then printf '%s\\n' \"$raw\"; fi && printf 'end-read\\n'";
    }
    static String unpack(String wire,int maximum) {
        if(wire==null||wire.length()>maximum+128||!wire.endsWith("end-read\n"))bad();
        int end=wire.indexOf('\n');if(end<0)bad();
        String[] header=wire.substring(0,end).split("\t",-1);if(header.length!=2||!header[0].equals("v1-read"))bad();
        long size=num(header[1]);String raw=wire.substring(end+1,wire.length()-9);
        if(size>maximum||size!=raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)bad();return raw;
    }
    private static String[] line(String raw,int maximum,int count) {
        if(raw==null||raw.length()>maximum||!raw.endsWith("\n"))bad();
        String row=raw.substring(0,raw.length()-1);
        for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c!='\t'&&(c<32||c>126))bad();}
        String[] f=row.split("\t",-1);if(f.length!=count)bad();return f;
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static long signed(String s){long n=Long.parseLong(s);if(!Long.toString(n).equals(s))bad();return n;}
    private static double decimal(String s){if(!s.matches("-?[0-9]+(?:\\.[0-9]+)?"))bad();double n=Double.parseDouble(s);if(!Double.isFinite(n))bad();return n;}
    private static void bad(){throw new IllegalArgumentException("Complete exact same-session batch scan required; never infer empty inventory");}
}

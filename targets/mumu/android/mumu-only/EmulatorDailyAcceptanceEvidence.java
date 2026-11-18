package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** Private retained controlled-test evidence, not a user preference or an
 * authentication protocol. Actual capture/provenance must come from the
 * controlled verifier; a checksum or HOST fixture never proves a device test. */
final class EmulatorDailyAcceptanceEvidence {
    static final int MAX_BYTES=393216;
    static final long MAX_AGE=12*60*60*1000L;
    static final long AUTOMATIC_WINDOW=10*60*1000L;
    final EmulatorDailyRunner.Binding binding;
    final long verifiedAt;
    final boolean automatic;
    private final String wire;
    private EmulatorDailyAcceptanceEvidence(EmulatorDailyRunner.Binding b,long at,String wire,boolean automatic){binding=b;verifiedAt=at;this.wire=wire;this.automatic=automatic;}
    String encode(){return wire;}
    /** Assemble originals, then run the SAME full semantic consumer. This is
     * not a capture API or provenance claim; actual sources are supplied only
     * by the isolated Android controlled observer. */
    static EmulatorDailyAcceptanceEvidence create(EmulatorDailyRunner.Binding held,String[] payload,long beforeAt,long afterAt,long at){
        return assemble(held,payload,beforeAt,afterAt,at,false);
    }
    static EmulatorDailyAcceptanceEvidence createAutomatic(EmulatorDailyRunner.Binding held,String[] payload,long beforeAt,long afterAt,long at){
        return assemble(held,payload,beforeAt,afterAt,at,true);
    }
    /** v3 certifies only continuous automatic HC adoption. Special along-route
     * harvesting/low-stock test artifacts are deliberately NOT prerequisites.
     * This never certifies a GPS route or planting, nor skips live safety gates. */
    static EmulatorDailyAcceptanceEvidence createAutomaticSteps(EmulatorDailyRunner.Binding held,String[] payload,long beforeAt,long afterAt,long at){
        if(held==null||payload==null||payload.length!=4)bad();
        StringBuilder body=new StringBuilder("v3\t"+EmulatorDailyRunner.base64(held.encode())+"\t"+at+"\t"+beforeAt+"\t"+afterAt+"\tend\n");
        for(int i=0;i<4;i++){
            if(payload[i]==null||payload[i].length()>MAX_BYTES)bad();
            body.append("p\t").append(i).append('\t').append(EmulatorDailyRunner.base64(payload[i])).append('\n');
            if(body.length()>MAX_BYTES)bad();
        }
        body.append("scope\tcontinuous-automatic-hc-adoption-only\tend\n");
        return decode(body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n",held,at);
    }
    private static EmulatorDailyAcceptanceEvidence assemble(EmulatorDailyRunner.Binding held,String[] payload,long beforeAt,long afterAt,long at,boolean automatic){
        if(held==null||payload==null||payload.length!=9)bad();
        StringBuilder body=new StringBuilder((automatic?"v2":"v1")+"\t"+EmulatorDailyRunner.base64(held.encode())+"\t"+at+"\t"+beforeAt+"\t"+afterAt+"\tend\n");
        for(int i=0;i<9;i++){if(payload[i]==null||payload[i].length()>MAX_BYTES)bad();body.append("p\t").append(i).append('\t').append(EmulatorDailyRunner.base64(payload[i])).append('\n');if(body.length()>MAX_BYTES)bad();}
        body.append(automatic?"scope\tcontrolled-round-automatic-hc-adoption\tend\n":"scope\tcontrolled-round-hc-adoption\tend\n");
        return decode(body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n",held,at);
    }
    interface Mode{void apply(Path path,boolean directory)throws Exception;}
    /** One retained slot. Existing/failed evidence is never overwritten or
     * deleted; renewal requires a separate audited archive transaction. */
    static void persist(Path root,EmulatorDailyAcceptanceEvidence evidence,EmulatorAndroidDailyStore.DirectorySync sync,Mode mode)throws Exception{
        if(evidence==null||sync==null||mode==null||root==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null||!root.getFileName().toString().equals("daily-acceptance")||root.getParent()==null||root.getParent().getFileName()==null||
            !root.getParent().getFileName().toString().equals("files")||!Files.isDirectory(root.getParent(),LinkOption.NOFOLLOW_LINKS))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Path leaf=root.resolve(EmulatorDailyRunner.digest(evidence.binding.encode())+".tsv");
        if(Files.exists(leaf,LinkOption.NOFOLLOW_LINKS))bad();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(root);mode.apply(root,true);}
        sync.sync(root.getParent());sync.sync(root);
        byte[] bytes=evidence.encode().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)bad();
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(leaf,false);ByteBuffer data=ByteBuffer.wrap(bytes);while(data.hasRemaining())out.write(data);out.force(true);
        }
        sync.sync(root);if(!evidence.encode().equals(read(root,evidence.binding,evidence.verifiedAt).encode()))bad();
    }

    /** Payloads: route claim/closure, first-active/first-low/next-active stock,
     * HC ledger before/after, game counter before/after. Preserve originals. */
    static EmulatorDailyAcceptanceEvidence decode(String wire,EmulatorDailyRunner.Binding expected,long now){
        if(wire==null||expected==null||wire.length()>MAX_BYTES||wire.indexOf('\r')>=0)bad();
        if(wire.startsWith("v3\t"))return decodeAutomaticSteps(wire,expected,now);
        String[] lines=wire.split("\n",-1);
        if(lines.length!=13||!lines[12].isEmpty())bad();
        StringBuilder body=new StringBuilder();for(int i=0;i<11;i++)body.append(lines[i]).append('\n');
        if(!lines[11].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
        String[] h=lines[0].split("\t",-1);
        if(h.length!=6||!(h[0].equals("v1")||h[0].equals("v2"))||!h[5].equals("end")||!expected.same(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1]))))bad();
        boolean automatic=h[0].equals("v2");
        long at=num(h[2]),beforeAt=num(h[3]),afterAt=num(h[4]);
        // v2 is functionality certification for this exact APK/core/account/
        // boot/PID/session/route, not permission to skip any live execution gate.
        // Do not expire it halfway through a next-day schedule. Legacy v1 keeps
        // its original expiry and is not admitted by the automatic runtime.
        if(at<=0||now<at||(!automatic&&now-at>MAX_AGE)||beforeAt<=0||afterAt<=beforeAt||afterAt-beforeAt>(automatic?AUTOMATIC_WINDOW:120000)||afterAt>at||at-afterAt>120000)bad();
        String[] payload=new String[9];
        for(int i=0;i<9;i++){
            String[] row=lines[i+1].split("\t",-1);
            if(row.length!=3||!row[0].equals("p")||!row[1].equals(Integer.toString(i)))bad();
            payload[i]=EmulatorDailyRunner.unbase64(row[2]);
            if(!EmulatorDailyRunner.base64(payload[i]).equals(row[2]))bad();
        }
        if(!lines[10].equals(automatic?"scope\tcontrolled-round-automatic-hc-adoption\tend":"scope\tcontrolled-round-hc-adoption\tend"))bad();
        String claim=payload[0],closure=payload[1];
        String[] cr=claim.split("\n",-1);
        if(cr.length!=3||!cr[2].isEmpty()||!cr[1].equals("sha256\t"+EmulatorDailyRunner.digest(cr[0]+"\n")))bad();
        String[] c=cr[0].split("\t",-1);if(c.length!=7)bad();
        EmulatorDailyRouteWork work=EmulatorDailyRouteWork.decode(EmulatorDailyRunner.unbase64(c[1]));
        if(!work.binding.same(expected)||!claim.equals(work.claimWire(c[2],c[3],c[4])))bad();
        EmulatorDailyRouteClosure.validateClaim(closure,expected,work.key,EmulatorDailyRunner.digest(claim),c[2],c[3],c[4]);
        String[] rounds=closure.split("\n",-1),rh=rounds[0].split("\t",-1);
        if(!rh[6].equals("completed")||rounds.length<5||num(rh[9])>at)bad();
        String[] first=rounds[1].split("\t",-1),next=rounds[2].split("\t",-1);
        EmulatorPetalRotation.Petal initial=petal(payload[2],expected,first,at);
        EmulatorPetalRotation.Petal low=petal(payload[3],expected,first,at);
        EmulatorPetalRotation.Petal restarted=petal(payload[4],expected,next,at);
        if(!initial.id.equals(low.id)||initial.stock<=EmulatorPetalRotation.RESERVE||low.stock>EmulatorPetalRotation.RESERVE||
           initial.stock<=low.stock||restarted.id.equals(initial.id)||restarted.stock<=EmulatorPetalRotation.RESERVE||
           captured(payload[2])>=captured(payload[3]))bad();
        validateStepAdoption(expected,payload[5],payload[6],payload[7],payload[8],beforeAt,afterAt,at,automatic);
        return new EmulatorDailyAcceptanceEvidence(expected,at,wire,automatic);
    }
    private static EmulatorDailyAcceptanceEvidence decodeAutomaticSteps(String wire,EmulatorDailyRunner.Binding expected,long now){
        String[] rows=wire.split("\n",-1);
        if(rows.length!=8||!rows[7].isEmpty())bad();
        StringBuilder body=new StringBuilder();for(int i=0;i<6;i++)body.append(rows[i]).append('\n');
        if(!rows[6].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
        String[] h=rows[0].split("\t",-1);
        if(h.length!=6||!h[0].equals("v3")||!h[5].equals("end"))bad();
        EmulatorDailyRunner.Binding parsed=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1]));
        if(!expected.same(parsed)||!h[1].equals(EmulatorDailyRunner.base64(parsed.encode())))bad();
        long at=num(h[2]),beforeAt=num(h[3]),afterAt=num(h[4]);
        if(at<=0||now<at||beforeAt<=0||afterAt<=beforeAt||afterAt-beforeAt>AUTOMATIC_WINDOW||afterAt>at||at-afterAt>120000)bad();
        String[] payload=new String[4];
        for(int i=0;i<4;i++){
            String[] f=rows[i+1].split("\t",-1);
            if(f.length!=3||!f[0].equals("p")||!f[1].equals(Integer.toString(i)))bad();
            payload[i]=EmulatorDailyRunner.unbase64(f[2]);
            if(!EmulatorDailyRunner.base64(payload[i]).equals(f[2]))bad();
        }
        if(!rows[5].equals("scope\tcontinuous-automatic-hc-adoption-only\tend"))bad();
        validateStepAdoption(expected,payload[0],payload[1],payload[2],payload[3],beforeAt,afterAt,at,true);
        return new EmulatorDailyAcceptanceEvidence(expected,at,wire,true);
    }
    /** Shared with legacy v1/v2: no weaker HC, provenance, identity or ledger rules. */
    private static void validateStepAdoption(EmulatorDailyRunner.Binding expected,String beforeLedger,String afterLedger,
            String beforeCounter,String afterCounter,long beforeAt,long afterAt,long at,boolean automatic){
        StepState prior=StepState.decode(beforeLedger),after=StepState.decode(afterLedger);
        if(!beforeLedger.equals(prior.encode())||!afterLedger.equals(after.encode()))bad();
        // The writer retains 200 display records, independently of occupied
        // spans. Reproduce its exact one-record transition, including bounded
        // display eviction; never accept arbitrary missing/edited history.
        if(!idle(prior)||!idle(after))bad();
        java.util.Set<String> ids=new java.util.HashSet<>(),clients=new java.util.HashSet<>();
        java.util.List<StepPlan> added=new java.util.ArrayList<>();long confirmed=0;
        for(StepPlan plan:after.ledger.all()){
            if(!ids.add(plan.id)||!clients.add(plan.clientRecordId))bad();
            StepPlan old=prior.ledger.byIdOrClient(plan.id,plan.clientRecordId);
            if(old==null)added.add(plan);
            else if(!old.encode().equals(plan.encode()))bad();
        }
        if(automatic?(added.size()<2||added.size()>20):added.size()!=1)bad();
        for(StepPlan plan:added){
            if(!StepPlan.CONFIRMED.equals(plan.status)||plan.count<=0||plan.count>1000||
               plan.healthId.isEmpty()||plan.clientRecordId.isEmpty()||!plan.errorKind.isEmpty()||plan.endMs>afterAt||
               plan.endMs<beforeAt-120000||plan.startMs<beforeAt-(automatic?2000:180000)||plan.clientRecordVersion<0||
               (automatic&&!StepPlan.BACKGROUND.equals(plan.source)))bad();
            confirmed+=plan.count;
        }
        if(confirmed>10000)bad();
        // StepEngine.newPlan uses version 0 for the first HC insertion. Positive
        // versions remain valid; uniqueness and exact one-record transition,
        // not a fabricated positive version, prevent replacement/replay.
        exactLedgerTransition(prior.ledger,after.ledger,added,beforeAt);
        EmulatorGameDailySteps.Observation a=EmulatorGameDailySteps.read(beforeCounter,expected,beforeAt);
        EmulatorGameDailySteps.Observation b=EmulatorGameDailySteps.read(afterCounter,expected,afterAt);
        if(a.nursery.capturedAt>=b.nursery.capturedAt||b.inventoryRevision<=a.inventoryRevision||
           !a.nursery.gameDay.equals(b.nursery.gameDay)||
           b.canonicalSteps<a.canonicalSteps||b.canonicalSteps-a.canonicalSteps!=confirmed||
           b.nursery.gameCountedSteps<a.nursery.gameCountedSteps||b.nursery.gameCountedSteps-a.nursery.gameCountedSteps!=confirmed||
           !b.nursery.gameDay.equals(Instant.ofEpochMilli(at).atZone(EmulatorDailyPlan.ZONE).toLocalDate()))bad();
    }
    private static boolean idle(StepState s){return !s.desiredRunning&&s.ledger.pending()==null&&s.writerOwner.isEmpty()&&s.lockId.isEmpty()&&s.lockRole.isEmpty();}
    private static void exactLedgerTransition(StepLedger prior,StepLedger after,java.util.List<StepPlan> added,long beforeAt){
        java.util.Set<String> ids=new java.util.HashSet<>(),clients=new java.util.HashSet<>();
        for(StepPlan old:prior.all())if(!ids.add(old.id)||!clients.add(old.clientRecordId))bad();
        for(StepPlan plan:added)if(!ids.add(plan.id)||!clients.add(plan.clientRecordId))bad();
        StepLedger expected=new StepLedger(prior.all(),prior.occupiedSpans());
        long dayStart=Instant.ofEpochMilli(beforeAt).atZone(EmulatorDailyPlan.ZONE).toLocalDate()
                .atStartOfDay(EmulatorDailyPlan.ZONE).toInstant().toEpochMilli();
        // StepEngine.persistPrepared compacts old-day occupancy, then adds the
        // pending plan; confirmation merges its occupied span. Original prior
        // and after must be idle, so final confirmed add yields the same result.
        expected.compact(dayStart);for(StepPlan plan:added)expected.add(plan);
        if(expected.all().size()!=after.all().size())bad();
        for(int i=0;i<expected.all().size();i++)
            if(!expected.all().get(i).encode().equals(after.all().get(i).encode()))bad();
        if(expected.occupiedSpans().size()!=after.occupiedSpans().size())bad();
        for(int i=0;i<expected.occupiedSpans().size();i++)
            if(!java.util.Arrays.equals(expected.occupiedSpans().get(i),after.occupiedSpans().get(i)))bad();
    }
    private static long captured(String wire){return num(wire.split("\t",-1)[1]);}
    private static EmulatorPetalRotation.Petal petal(String wire,EmulatorDailyRunner.Binding b,String[] round,long at){
        String[] h=wire.split("\n",-1)[0].split("\t",-1);if(h.length!=11)bad();
        long captured=captured(wire),started=num(round[3]),closed=num(round[4]);
        if(started<=0||captured<num(round[2])||captured>closed||captured>at)bad();
        EmulatorPetalRotation.Snapshot s=EmulatorOwnedPetalReader.read(wire,b.pid,b.sessionEpoch,b.account,round[1],started,h[7],captured);
        if(!s.complete)bad();
        for(EmulatorPetalRotation.Petal p:s.petals)if(p.id.equals(h[7]))return p;
        bad();return null;
    }
    static EmulatorDailyAcceptanceEvidence read(Path root,EmulatorDailyRunner.Binding held,long now)throws Exception{
        if(root==null||held==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null||!root.getFileName().toString().equals("daily-acceptance")||root.getParent()==null||
           !root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))bad();
        Path leaf=root.resolve(EmulatorDailyRunner.digest(held.encode())+".tsv");
        if(Files.isSymbolicLink(leaf)||!Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS))bad();
        byte[] bytes=bounded(leaf);
        String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))bad();
        EmulatorDailyAcceptanceEvidence result=decode(raw,held,now);
        for(Path p=root;p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))bad();
        if(Files.isSymbolicLink(leaf)||!java.util.Arrays.equals(bytes,bounded(leaf)))bad();
        return result;
    }
    static String ledgerOriginal(Path leaf)throws Exception{
        if(leaf==null||leaf.getFileName()==null||!leaf.getFileName().toString().equals("step-ledger.properties")||leaf.getParent()==null||
            leaf.getParent().getFileName()==null||!leaf.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=leaf.getParent();p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))bad();
        if(Files.isSymbolicLink(leaf)||!Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS))bad();
        byte[] bytes=bounded(leaf);String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!java.util.Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8))||!java.util.Arrays.equals(bytes,bounded(leaf)))bad();return raw;
    }
    private static byte[] bounded(Path leaf)throws Exception{
        try(FileChannel in=FileChannel.open(leaf,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()<1||in.size()>MAX_BYTES)bad();ByteBuffer buf=ByteBuffer.allocate(MAX_BYTES+1);
            while(in.read(buf)>0){};if(buf.position()>MAX_BYTES)bad();return java.util.Arrays.copyOf(buf.array(),buf.position());
        }
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static void bad(){throw new IllegalStateException("Exact retained controlled round and observed game adoption required; no readiness flag");}
}

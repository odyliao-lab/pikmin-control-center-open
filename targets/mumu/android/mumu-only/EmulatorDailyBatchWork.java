package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.*;
import java.util.*;

/** MuMu daily batch admission, not a scanner or completion receipt. The original
 * service retains confirmation/cleanup; closing admissions never aborts an
 * accepted task. Claims are immutable and never reconstructed as live work. */
final class EmulatorDailyBatchWork {
    static final String EXTRA="pcc_daily_batch_v1";
    final EmulatorDailyRunner.Binding binding;
    final String key,queueDigest;
    final long locationEpoch,deadline,created;
    final EmulatorDevelopmentBatch.Window developmentWindow;
    private volatile boolean admitted,closed;
    private String claim,checkpoint,acceptedTask;
    private EmulatorDailyBatchReceipt.Admission acceptedAdmission;
    private final Map<String,EmulatorDailyBatchReceipt> collected=new HashMap<>();
    private final Map<String,EmulatorDailyBatchReceipt.Admission> admissions=new HashMap<>();
    private final Map<String,EmulatorDailyBatchRejection> rejections=new HashMap<>();
    private final Map<String,EmulatorDailyBatchObserved> observed=new HashMap<>();
    private final Map<String,String> pendingReasons=new HashMap<>();
    private volatile boolean summaryNeedsReview=true;
    private final Map<String,String> candidates=new HashMap<>();
    private Map<String,String> historicalPending=Collections.emptyMap();
    static boolean preAdmissionPending(String reason){return "gift-owner-unknown".equals(reason)||"prior-admission-unresolved".equals(reason)||"prior-collected".equals(reason);}
    /** Denial-only index across work dates, experimental windows and APK
     * bindings. A different namespace never turns an old intent into permission.
     * Read once under the original exclusive batch claim; never deletes or
     * upgrades the original outcome, and never authorizes a historical task. */
    static Map<String,String> historicalPending(Path files,String account)throws Exception{
        if(files==null||!files.getFileName().toString().equals("files")||account==null||!account.matches("[a-f0-9]{64}"))bad();
        Path root=files.resolve("daily-batch-tasks");Map<String,String> pending=new HashMap<>();
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return Collections.emptyMap();
        if(Files.isSymbolicLink(root)||!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))bad();
        int count=0;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(root)){
            for(Path file:entries){
                if(++count>8192||!file.getFileName().toString().matches("[a-f0-9]{64}\\.tsv"))bad();
                EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(EmulatorDailyBatchOutcome.readPrivate(file,4096));
                if(!file.getFileName().toString().equals(taskLeaf(a.key,a.id)+".tsv"))bad();
                if(!a.binding.account.equals(account))continue;
                Path receipt=files.resolve("daily-batch-claims").resolve(file.getFileName()),rejection=files.resolve("daily-batch-rejections").resolve(file.getFileName());
                boolean got=Files.exists(receipt,LinkOption.NOFOLLOW_LINKS),notSent=Files.exists(rejection,LinkOption.NOFOLLOW_LINKS);
                if(got&&notSent)bad();
                if(notSent){EmulatorDailyBatchRejection.decode(EmulatorDailyBatchOutcome.readPrivate(rejection,4096),a);continue;}
                if(got){
                    EmulatorDailyBatchReceipt.decode(EmulatorDailyBatchOutcome.readPrivate(receipt,4096),a);
                    if(!pending.containsKey(a.id))pending.put(a.id,"prior-collected");
                }else pending.put(a.id,"prior-admission-unresolved");
            }
        }
        return Collections.unmodifiableMap(pending);
    }
    /** Restrictive MuMu-only pre-admission policy. Missing designated-owner
     * data is NOT busy, NOT a rejection and never permission to dispatch. */
    static boolean giftOwnerUnknown(DispatchPreflight.Observation o){
        return o!=null&&o.kind.equals("gift")&&o.reason.equals("unknown")
                &&o.owner.isEmpty()&&o.assigned.isEmpty()&&o.status==-1&&o.eligible==0;
    }
    static boolean confirmsGiftOwnerUnknown(DispatchPreflight.Observation first,DispatchPreflight.Observation next,long now){
        return giftOwnerUnknown(first)&&giftOwnerUnknown(next)&&first.fresh(now)&&next.fresh(now)
                &&next.at>first.at&&next.pid.equals(first.pid)&&next.task.equals(first.task);
    }
    EmulatorDailyBatchWork(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,long created,String queueDigest) {
        this(held,key,epoch,deadline,created,queueDigest,null);
    }
    static EmulatorDailyBatchWork development(EmulatorDailyRunner.Binding held,EmulatorDevelopmentBatch.Window window,long epoch,long created,String queueDigest){
        if(window==null)bad();return new EmulatorDailyBatchWork(held,window.key,epoch,window.deadline,created,queueDigest,window);
    }
    private EmulatorDailyBatchWork(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,long created,String queueDigest,EmulatorDevelopmentBatch.Window window) {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        long expected=EmulatorDailyPlan.deadline(key);
        if(epoch<=0||created<=0||queueDigest==null||!queueDigest.matches("[0-9a-f]{64}")||
                (window==null?(EmulatorDevelopmentBatch.development(key)||deadline!=expected):(!window.key.equals(key)||deadline!=window.deadline||!window.open(created))))bad();
        binding=held;this.key=key;this.queueDigest=queueDigest;locationEpoch=epoch;this.deadline=deadline;this.created=created;
        developmentWindow=window;
        if(!open(created))bad();
    }
    String encode(){return (developmentWindow==null?"v1":"v2-development")+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+locationEpoch+"\t"+deadline+"\t"+created+"\t"+queueDigest+(developmentWindow==null?"":"\t"+developmentWindow.created)+"\tend\n";}
    static EmulatorDailyBatchWork decode(String wire) {
        if(wire==null||wire.length()>2048||!wire.endsWith("\n")||wire.indexOf('\r')>=0)bad();
        String[] f=wire.substring(0,wire.length()-1).split("\t",-1);
        boolean dev=f.length==9&&f[0].equals("v2-development");
        if(!(dev||f.length==8&&f[0].equals("v1"))||!f[f.length-1].equals("end"))bad();
        EmulatorDevelopmentBatch.Window window=dev?new EmulatorDevelopmentBatch.Window(f[2],Long.parseLong(f[7]),Long.parseLong(f[4])):null;
        EmulatorDailyBatchWork w=new EmulatorDailyBatchWork(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),f[2],Long.parseLong(f[3]),Long.parseLong(f[4]),Long.parseLong(f[5]),f[6],window);
        if(!w.encode().equals(wire))bad();return w;
    }
    void put(Intent intent){intent.putExtra(EXTRA,encode());}
    static EmulatorDailyBatchWork from(Intent intent){return intent.hasExtra(EXTRA)?decode(intent.getStringExtra(EXTRA)):null;}
    boolean matches(EmulatorDailyRunner.Binding held,String key){return binding.same(held)&&this.key.equals(key);}
    boolean open(long now) {
        if(closed||now<created||now>=deadline)return false;
        if(developmentWindow!=null)return developmentWindow.open(now);
        ZonedDateTime local=Instant.ofEpochMilli(now).atZone(EmulatorDailyPlan.ZONE);
        return EmulatorDailyPlan.batchWindow(key,now);
    }
    void requireIdentity(Context c)throws Exception{EmulatorDailyRouteWork.requireIdentity(c,binding);}
    String resourcePreflight(Context c,DispatchProbeGateway.Candidate candidate)throws Exception {
        if(!admitted||claim==null||candidate==null||!row(candidate).equals(candidates.get(candidate.taskId))||!open(System.currentTimeMillis()))bad();
        requireNoAdmission(c,candidate);
        if(historicalPending.containsKey(candidate.taskId))return historicalPending.get(candidate.taskId);
        if(observed.containsKey(candidate.taskId)||Files.exists(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-observed").resolve(taskLeaf(key,candidate.taskId)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
        String reason=EmulatorDailyResources.read(c,binding).batchPreflight(candidate.kind);
        if(!open(System.currentTimeMillis()))bad();
        return reason.isEmpty()?null:reason; // Known capacity only; missing/stale throws, never a confirmed skip.
    }
    void requireStart(Context c,List<DispatchProbeGateway.Candidate> queue)throws Exception {
        if(!open(System.currentTimeMillis())||System.currentTimeMillis()-created>90000)bad();
        if(!queueDigest.equals(queueSha(BatchSites.group(queue))))bad();
        EmulatorDailyBatchInventory inventory=EmulatorDailyBatchInventory.read(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-inventory"),binding,key);
        if(inventory.locationEpoch!=locationEpoch||inventory.deadline!=deadline||created<inventory.created)bad();
        if(developmentWindow==null?inventory.developmentWindow!=null:!developmentWindow.sameRun(inventory.developmentWindow))bad();
        if(developmentWindow!=null){
            Path files=EmulatorAndroidDailyStore.files(c);EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.requireInventory(files,inventory);
            if(!run.binding.same(binding)||!run.window.sameRun(developmentWindow)||!EmulatorSavedDailyRuntime.developmentConfigMatches(c,run))bad();
            run.requireReservation(key,EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.reservationLeaf(key)+".tsv"),4096),locationEpoch);
        }
        EmulatorDailyBatchSelection selection=EmulatorDailyBatchSelection.read(EmulatorAndroidDailyStore.files(c),inventory,EmulatorAndroidDailyStore::syncDirectory);
        if(!queueDigest.equals(selection.queueSha()))bad();
        for(DispatchProbeGateway.Candidate candidate:selection.queue)for(String dir:Arrays.asList("daily-batch-tasks","daily-batch-claims","daily-batch-rejections","daily-batch-observed"))if(Files.exists(EmulatorAndroidDailyStore.files(c).resolve(dir).resolve(taskLeaf(key,candidate.taskId)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
        requireIdentity(c);EmulatorDailyResources.require(c,binding,false,false,false);
        FarmLocation p=GpsModuleGateway.farmLocation(c);
        if(!p.valid||!Integer.toString(binding.pid).equals(p.pid)||p.epoch!=locationEpoch)bad();
        requireIdentity(c);if(!open(System.currentTimeMillis()))bad();
    }
    void claim(Context c,WorkCheckpoint cp,List<DispatchProbeGateway.Candidate> queue)throws Exception {
        if(claim!=null||admitted||cp==null||!GpsWorkLock.BATCH.equals(cp.owner)||!binding.account.equals(cp.account))bad();
        List<String> entries=new ArrayList<>();for(DispatchProbeGateway.Candidate p:queue)entries.add(row(p));
        if(!entries.equals(cp.entries))bad();
        requireStart(c,queue);
        EmulatorDailyBatchInventory inventory=EmulatorDailyBatchInventory.read(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-inventory"),binding,key);
        EmulatorDailyBatchSelection selection=EmulatorDailyBatchSelection.read(EmulatorAndroidDailyStore.files(c),inventory,EmulatorAndroidDailyStore::syncDirectory);
        historicalPending=historicalPending(EmulatorAndroidDailyStore.files(c),binding.account);
        String body="v2\t"+EmulatorDailyRunner.base64(encode())+"\t"+cp.id+"\t"+queueSha(queue)+"\t"+EmulatorDailyRunner.digest(selection.encode())+"\tintent-not-admitted\tend\n";
        String wire=checked(body);
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-links"),EmulatorDailyRunner.digest(key),wire,EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        if(!open(System.currentTimeMillis()))bad();
        for(DispatchProbeGateway.Candidate p:queue)candidates.put(p.taskId,row(p));
        checkpoint=cp.id;claim=wire;
    }
    void admitted(Context c)throws Exception {
        if(claim==null||admitted||!GpsWorkLock.heldBy(c,GpsWorkLock.BATCH))bad();
        requireIdentity(c);admitted=true;
    }
    /** Restrictive live-instance latch only. The service must match the exact
     * owned instance before this call; no IO, readiness or gameplay authority. */
    void closeAdmissionsLocally(){closed=true;}
    void closeAdmissions(Context c)throws Exception {
        closeAdmissionsLocally(); // Fresh IO failure must never reopen admission.
        requireIdentity(c);
        if(!admitted||claim==null)bad();
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-links"),EmulatorDailyRunner.digest(key+":closed"),
                checked("v1\t"+EmulatorDailyRunner.digest(claim)+"\tadmissions-closed\tend\n"),EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
    }
    void admitTask(Context c,DispatchProbeGateway.Candidate candidate)throws Exception {
        if(!admitted||claim==null||!row(candidate).equals(candidates.get(candidate.taskId))||!open(System.currentTimeMillis()))bad();
        if(historicalPending.containsKey(candidate.taskId))bad(); // Denial survives every later target/GPS check.
        if(observed.containsKey(candidate.taskId)||Files.exists(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-observed").resolve(taskLeaf(key,candidate.taskId)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
        requireIdentity(c);
        EmulatorDailyResources.require(c,binding,"seed".equals(candidate.kind),false,false);
        long now=System.currentTimeMillis();if(!open(now))bad();
        EmulatorDailyBatchReceipt.Admission admission=developmentWindow==null?new EmulatorDailyBatchReceipt.Admission(binding,key,checkpoint,EmulatorDailyRunner.digest(claim),candidate.taskId,candidate.kind,now):
                EmulatorDailyBatchReceipt.Admission.development(binding,developmentWindow,checkpoint,EmulatorDailyRunner.digest(claim),candidate.taskId,candidate.kind,now);
        // Same game-day/task, NOT batch/rescan number, names the immutable leaf.
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-tasks"),taskLeaf(key,candidate.taskId),admission.encode(),EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        if(!open(System.currentTimeMillis()))bad(); // Retain intent; never replay if commit crossed the deadline.
        acceptedTask=candidate.taskId;acceptedAdmission=admission;admissions.put(candidate.taskId,admission);pendingReasons.remove(candidate.taskId);
    }
    void requireAccepted(Context c,DispatchProbeGateway.Candidate candidate)throws Exception {
        if(!admitted||acceptedTask==null||candidate==null||!acceptedTask.equals(candidate.taskId)||!row(candidate).equals(candidates.get(candidate.taskId)))bad();
        requireIdentity(c); // Existing acceptance may finish after the absolute cutoff.
    }
    void requireCollected(Context c,DispatchProbeGateway.Candidate candidate)throws Exception {
        if(candidate!=null&&observed.containsKey(candidate.taskId)){requireNoAdmission(c,candidate);requireIdentity(c);return;}
        requireAccepted(c,candidate);
        if(acceptedAdmission==null||!acceptedAdmission.id.equals(candidate.taskId)||collected.containsKey(candidate.taskId)||rejections.containsKey(candidate.taskId))bad();
        long until=android.os.SystemClock.elapsedRealtime()+30000;
        EmulatorDailyBatchReceipt receipt=null;
        while(android.os.SystemClock.elapsedRealtime()<until){
            requireIdentity(c);
            try{receipt=EmulatorDailyBatchReceipt.read(c,acceptedAdmission);}catch(IllegalArgumentException incomplete){receipt=null;}
            if(receipt!=null)break;
            Thread.sleep(1000);
        }
        if(receipt==null)throw new IllegalStateException("此任務尚未確認領回；保留授權紀錄，不重送派遣");
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-claims"),taskLeaf(key,candidate.taskId),receipt.encode(),EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        requireIdentity(c);collected.put(candidate.taskId,receipt);
    }
    void observeCollected(Context c,DispatchProbeGateway.Candidate candidate,long since)throws Exception {
        if(!admitted||claim==null||checkpoint==null||candidate==null||!row(candidate).equals(candidates.get(candidate.taskId))||since<created||observed.containsKey(candidate.taskId))bad();
        requireNoAdmission(c,candidate);requireIdentity(c);
        EmulatorDailyBatchObserved proof=EmulatorDailyBatchObserved.read(c,binding,key,checkpoint,EmulatorDailyRunner.digest(claim),candidate.taskId,candidate.kind,since,developmentWindow);
        if(proof==null)throw new IllegalStateException("領取觀察尚未獨立確認，不建立派遣授權");
        requireNoAdmission(c,candidate);
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-observed"),taskLeaf(key,candidate.taskId),proof.encode(),EmulatorAndroidDailyStore::syncDirectory,p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        requireIdentity(c);requireNoAdmission(c,candidate);observed.put(candidate.taskId,proof);pendingReasons.remove(candidate.taskId);
    }
    private void requireNoAdmission(Context c,DispatchProbeGateway.Candidate candidate)throws Exception{
        if(admissions.containsKey(candidate.taskId)||collected.containsKey(candidate.taskId)||rejections.containsKey(candidate.taskId))bad();
        for(String dir:Arrays.asList("daily-batch-tasks","daily-batch-claims","daily-batch-rejections"))if(Files.exists(EmulatorAndroidDailyStore.files(c).resolve(dir).resolve(taskLeaf(key,candidate.taskId)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
    }
    void pending(DispatchProbeGateway.Candidate candidate,String reason){
        if(candidate==null||!row(candidate).equals(candidates.get(candidate.taskId))||admissions.containsKey(candidate.taskId)||!EmulatorDailyBatchOutcome.REASONS.contains(reason))bad();
        pendingReasons.put(candidate.taskId,reason);
    }
    void requireRejected(Context c,DispatchProbeGateway.Candidate candidate,long stoppedAt)throws Exception {
        requireAccepted(c,candidate);
        if(acceptedAdmission==null||!candidate.kind.equals("gift")||collected.containsKey(candidate.taskId)||rejections.containsKey(candidate.taskId))bad();
        EmulatorDailyBatchRejection proof=EmulatorDailyBatchRejection.read(c,acceptedAdmission,stoppedAt);
        if(proof==null)throw new IllegalStateException("尚未独立確認禮物盒未派出；保留授權紀錄");
        persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-rejections"),taskLeaf(key,candidate.taskId),proof.encode(),EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        requireIdentity(c);rejections.put(candidate.taskId,proof);
    }
    String summarize(Context c,boolean clean)throws Exception {
        if(!admitted||claim==null||checkpoint==null||GpsWorkLock.heldBy(c,GpsWorkLock.BATCH))bad();
        requireIdentity(c);
        EmulatorDailyBatchInventory inventory=EmulatorDailyBatchInventory.read(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-inventory"),binding,key);
        EmulatorDailyBatchSelection selection=EmulatorDailyBatchSelection.read(EmulatorAndroidDailyStore.files(c),inventory,EmulatorAndroidDailyStore::syncDirectory);
        if(!queueDigest.equals(selection.queueSha()))bad();
        EmulatorDailyBatchOutcome outcome=new EmulatorDailyBatchOutcome(inventory,checkpoint,EmulatorDailyRunner.digest(claim),System.currentTimeMillis(),clean,admissions,collected,pendingReasons,rejections,selection,observed);
        outcome.persist(EmulatorAndroidDailyStore.files(c).resolve("daily-batch-outcomes"),EmulatorAndroidDailyStore::syncDirectory,
                p->Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        requireIdentity(c);summaryNeedsReview=outcome.needsReview();return outcome.summary(); // Summary only, never completion authority.
    }
    boolean summaryNeedsReview(){return summaryNeedsReview;}
    EmulatorDailyRunner.Observation observation(EmulatorDailyRunner.Binding held,String key) {
        if(!matches(held,key))bad();
        return new EmulatorDailyRunner.Observation(binding,key,admitted?EmulatorDailyRunner.Evidence.ADMITTED:EmulatorDailyRunner.Evidence.UNKNOWN);
    }
    static String taskLeaf(String key,String task) {
        EmulatorDevelopmentBatch.requireKeyString(key);
        if(task==null||!task.matches("[A-Za-z0-9_-]{1,160}"))bad();
        return EmulatorDailyRunner.digest(key.substring(0,10)+(EmulatorDailyPlan.overnightKey(key)?":n22":"")+"\t"+task);
    }
    static String queueSha(List<DispatchProbeGateway.Candidate> queue) {
        if(queue==null||queue.isEmpty()||queue.size()>2000)bad();
        Set<String> ids=new HashSet<>();StringBuilder body=new StringBuilder("daily-batch-queue-v1\n");
        for(DispatchProbeGateway.Candidate c:queue){String row=row(c);if(!ids.add(c.taskId))bad();body.append(row).append('\n');}
        return EmulatorDailyRunner.digest(body.toString());
    }
    private static String row(DispatchProbeGateway.Candidate c) {
        if(c==null||c.taskId==null||!c.taskId.matches("[A-Za-z0-9_-]{1,160}")||
           !("seed".equals(c.kind)||"fruit".equals(c.kind)||"gift".equals(c.kind))||c.expirationMs<0||
           !FarmLocation.point(c.latitude,c.longitude)||!Double.isFinite(c.distanceMetres)||c.distanceMetres< -1)bad();
        return c.taskId+"\t"+c.kind+"\t"+c.expirationMs+"\t"+c.latitude+"\t"+c.longitude+"\t"+c.distanceMetres;
    }
    private static String checked(String body){return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    static void persist(Path root,String leaf,String wire,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception {
        root=root.toAbsolutePath().normalize();
        if(!Arrays.asList("daily-batch-links","daily-batch-tasks","daily-batch-claims","daily-batch-rejections","daily-batch-observed").contains(root.getFileName().toString())||root.getParent()==null||
           !root.getParent().getFileName().toString().equals("files")||leaf==null||!leaf.matches("[0-9a-f]{64}")||wire==null||wire.length()>4096||sync==null||mode==null)bad();
        String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Files.createDirectories(root);sync.sync(root.getParent());sync.sync(root);
        Path path=root.resolve(leaf+".tsv");byte[] bytes=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            mode.apply(path);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);
        }
        sync.sync(root);if(!Arrays.equals(bytes,Files.readAllBytes(path)))bad();
    }
    private static void bad(){throw new IllegalStateException("Exact daily batch admission required; preserve intent and never replay");}
}

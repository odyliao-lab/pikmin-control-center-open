package dev.ody.pikmincontrol;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Complete per-inventory summary. Completion additionally requires independent
 * immutable-file reconciliation, identity, idle work locks and a fresh full scan. */
final class EmulatorDailyBatchOutcome {
    static final int MAX_BYTES=262144;
    static final Set<String> REASONS=Collections.unmodifiableSet(new HashSet<>(Arrays.asList("preflight-unavailable","gift-owner-unknown","prior-admission-unresolved","prior-collected","arrival-timeout","cutoff","not-authorized","unknown-location")));
    final EmulatorDailyBatchInventory inventory;
    final String checkpoint,claimSha,selectionSha;
    final long ended;
    final boolean cleanShutdown;
    final List<String> rows;
    final int collected,unresolved,unlocated,unavailable,notAuthorized,rejected;
    EmulatorDailyBatchOutcome(EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,long ended,boolean clean,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons) {
        this(inventory,checkpoint,claimSha,ended,clean,admissions,receipts,reasons,Collections.emptyMap());
    }
    EmulatorDailyBatchOutcome(EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,long ended,boolean clean,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections) {
        this(inventory,checkpoint,claimSha,ended,clean,admissions,receipts,reasons,rejections,null);
    }
    EmulatorDailyBatchOutcome(EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,long ended,boolean clean,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections,EmulatorDailyBatchSelection selection) {
        this(inventory,checkpoint,claimSha,ended,clean,admissions,receipts,reasons,rejections,selection,Collections.emptyMap());
    }
    EmulatorDailyBatchOutcome(EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,long ended,boolean clean,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections,EmulatorDailyBatchSelection selection,Map<String,EmulatorDailyBatchObserved> observations) {
        if(inventory==null||checkpoint==null||claimSha==null||ended<inventory.created||admissions==null||receipts==null||reasons==null||rejections==null||observations==null)bad();
        if(selection!=null&&!selection.inventory.encode().equals(inventory.encode()))bad();
        Map<String,EmulatorDailyBatchSelection.Prior> excluded=selection==null?Collections.emptyMap():selection.excluded;
        boolean noService=checkpoint.equals("-")&&claimSha.equals("-")&&(selection==null?inventory.located.isEmpty():selection.queue.isEmpty())&&admissions.isEmpty()&&receipts.isEmpty()&&rejections.isEmpty()&&observations.isEmpty();
        if(!noService&&(!checkpoint.matches("[a-f0-9-]{36}")||!claimSha.matches("[a-f0-9]{64}")))bad();
        this.inventory=inventory;this.checkpoint=checkpoint;this.claimSha=claimSha;this.ended=ended;cleanShutdown=clean;selectionSha=selection==null?null:EmulatorDailyRunner.digest(selection.encode());
        Set<String> ids=new HashSet<>();List<String> out=new ArrayList<>();int got=0,unknown=0,location=0,busy=0,pending=0,notSent=0;
        for(DispatchProbeGateway.Candidate c:inventory.candidates){
            ids.add(c.taskId);EmulatorDailyBatchReceipt.Admission a=admissions.get(c.taskId);EmulatorDailyBatchReceipt r=receipts.get(c.taskId);String reason=reasons.get(c.taskId);
            EmulatorDailyBatchRejection rejection=rejections.get(c.taskId);
            EmulatorDailyBatchSelection.Prior prior=excluded.get(c.taskId);
            EmulatorDailyBatchObserved observed=observations.get(c.taskId);
            String state,admissionSha="-",receiptSha="-",detail="-";
            if("gift-owner-unknown".equals(reason)&&!c.kind.equals("gift"))bad();
            if(prior!=null){
                if(a!=null||r!=null||rejection!=null||reason!=null||observed!=null)bad();
                state=prior.state();admissionSha=prior.authoritySha();receiptSha=prior.proofSha();detail=prior.key();
                if(prior.receipt!=null||prior.observed!=null)got++;else notSent++;
            }else if(observed!=null){
                if(a!=null||r!=null||rejection!=null||reason!=null||!observed.binding.same(inventory.binding)||!observed.key.equals(inventory.key)||!EmulatorDevelopmentBatch.sameWindow(observed.developmentWindow,inventory.developmentWindow)||!observed.id.equals(c.taskId)||!observed.kind.equals(c.kind)||!observed.checkpoint.equals(checkpoint)||!observed.claimSha.equals(claimSha)||observed.notBefore<inventory.created||observed.observedAt>ended)bad();
                EmulatorDailyBatchObserved.decode(observed.encode());state="observed-collected";admissionSha=EmulatorDailyRunner.digest(observed.encode());receiptSha=admissionSha;got++;
            }else if(a!=null){
                if(!a.binding.same(inventory.binding)||!a.key.equals(inventory.key)||!EmulatorDevelopmentBatch.sameWindow(a.developmentWindow,inventory.developmentWindow)||!a.checkpoint.equals(checkpoint)||!a.claimSha.equals(claimSha)||!a.id.equals(c.taskId)||!a.kind.equals(c.kind)||a.at<inventory.created||a.at>ended||inventory.unlocated.contains(c.taskId)||reason!=null)bad();
                admissionSha=EmulatorDailyRunner.digest(a.encode());
                if(r!=null){if(rejection!=null)bad();EmulatorDailyBatchReceipt.decode(r.encode(),a);if(r.collectedAt>ended)bad();state="collected";receiptSha=EmulatorDailyRunner.digest(r.encode());got++;}
                else if(rejection!=null){EmulatorDailyBatchRejection.decode(rejection.encode(),a);if(rejection.observedAt>ended)bad();state="confirmed-not-sent";receiptSha=EmulatorDailyRunner.digest(rejection.encode());detail="gift-unavailable";notSent++;}
                else{state="authorized-unresolved";unknown++;}
            }else{
                if(r!=null||rejection!=null||reason!=null&&!REASONS.contains(reason))bad();
                if(inventory.unlocated.contains(c.taskId)){state="unknown-location";detail="unknown-location";location++;}
                else if("preflight-unavailable".equals(reason)){state="unavailable-preflight";detail=reason;busy++;}
                else{state="not-authorized";detail=reason==null?"not-authorized":reason;pending++;}
            }
            out.add(c.taskId+"\t"+c.kind+"\t"+state+"\t"+admissionSha+"\t"+receiptSha+"\t"+detail);
        }
        if(!ids.containsAll(admissions.keySet())||!ids.containsAll(receipts.keySet())||!ids.containsAll(reasons.keySet())||!ids.containsAll(rejections.keySet())||!ids.containsAll(observations.keySet()))bad();
        rows=Collections.unmodifiableList(out);collected=got;unresolved=unknown;unlocated=location;unavailable=busy;notAuthorized=pending;rejected=notSent;
    }
    boolean needsReview(){return !cleanShutdown||unresolved>0;}
    boolean allOriginalCollected(){return cleanShutdown&&collected==inventory.candidates.size();}
    String summary(){return "候選 "+rows.size()+"；已領回 "+collected+"；授權結果待核對 "+unresolved+"；確認未派出待辦 "+rejected+"；座標未知 "+unlocated+"；派遣前不可用 "+unavailable+"；未授權待辦 "+notAuthorized+"；"+(cleanShutdown?"停止控制已核對":"收尾待核對");}
    String encode(){
        StringBuilder body=new StringBuilder((selectionSha==null?"v1":"v2")+"\t"+EmulatorDailyRunner.digest(inventory.encode())+"\t"+inventory.key+"\t"+checkpoint+"\t"+claimSha+"\t"+ended+"\t"+cleanShutdown+"\t"+rows.size()+(selectionSha==null?"":"\t"+selectionSha)+"\n");
        for(String row:rows)body.append(row).append('\n');String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
        if(wire.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)bad();return wire;
    }
    static EmulatorDailyBatchOutcome validate(String wire,EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons){
        return validate(wire,inventory,checkpoint,claimSha,admissions,receipts,reasons,Collections.emptyMap());
    }
    static EmulatorDailyBatchOutcome validate(String wire,EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections){
        return validate(wire,inventory,checkpoint,claimSha,admissions,receipts,reasons,rejections,null);
    }
    static EmulatorDailyBatchOutcome validate(String wire,EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections,EmulatorDailyBatchSelection selection){
        return validate(wire,inventory,checkpoint,claimSha,admissions,receipts,reasons,rejections,selection,Collections.emptyMap());
    }
    static EmulatorDailyBatchOutcome validate(String wire,EmulatorDailyBatchInventory inventory,String checkpoint,String claimSha,
            Map<String,EmulatorDailyBatchReceipt.Admission> admissions,Map<String,EmulatorDailyBatchReceipt> receipts,Map<String,String> reasons,Map<String,EmulatorDailyBatchRejection> rejections,EmulatorDailyBatchSelection selection,Map<String,EmulatorDailyBatchObserved> observations){
        if(wire==null||wire.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES||wire.indexOf('\r')>=0)bad();String[] lines=wire.split("\n",-1);
        if(lines.length<3||!lines[lines.length-1].isEmpty())bad();String[] f=lines[0].split("\t",-1);
        if(f.length!=(selection==null?8:9)||!f[0].equals(selection==null?"v1":"v2")||!(f[6].equals("true")||f[6].equals("false")))bad();
        EmulatorDailyBatchOutcome result=new EmulatorDailyBatchOutcome(inventory,checkpoint,claimSha,Long.parseLong(f[5]),Boolean.parseBoolean(f[6]),admissions,receipts,reasons,rejections,selection,observations);
        if(!wire.equals(result.encode()))bad();return result;
    }
    void persist(Path root,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception {
        if(root==null||sync==null||mode==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getParent()==null||!root.getFileName().toString().equals("daily-batch-outcomes")||!root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Files.createDirectories(root);sync.sync(root.getParent());sync.sync(root);
        Path file=root.resolve(EmulatorDailyRunner.digest(inventory.key)+".tsv");byte[] bytes=encode().getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(file);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);
        }
        sync.sync(root);
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()!=bytes.length)bad();ByteBuffer b=ByteBuffer.allocate(bytes.length);
            while(b.hasRemaining())if(in.read(b)<0)bad();if(in.read(ByteBuffer.allocate(1))!=-1||!Arrays.equals(bytes,b.array()))bad();
        }
    }
    /** Read-only recovery of an existing summary and its exact immutable files.
     * No service/intent replay. Missing or unresolved proof stays UNKNOWN. */
    /** Observation-only compatibility for the exact signed predecessor. Never admission authority. */
    static EmulatorDailyRunner.Binding retainedIdentity(android.content.Context c,EmulatorDailyRunner.Binding held)throws Exception {
        java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
        try(java.io.InputStream in=new java.io.FileInputStream(c.getApplicationInfo().sourceDir)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));String current=hex.toString();
        if(!held.apkSha.equals(current)&&!held.apkSha.equals("219118b8b7225736d076244b0a6dbdbd6e996b3e400b43434774854032ab017e"))bad();
        EmulatorDailyRunner.Binding live=new EmulatorDailyRunner.Binding(held.device,held.boot,held.pid,held.account,held.sessionEpoch,held.coreSha,current,held.routeSha,held.kmh);
        EmulatorDailyRouteWork.requireIdentity(c,live);return live;
    }
    /** Pure reconstruction: checkpoint completion requires an independent receipt. */
    static EmulatorDailyBatchOutcome recoverProjection(Path files,EmulatorDailyRunner.Binding held,String key,WorkCheckpoint cp,long now,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        return stoppedProjection(files,held,key,cp,now,sync,false);
    }
    /** An UNKNOWN review manifest, never persisted to daily-batch-outcomes.
     * Only the explicit stopped/terminal-task maintenance caller may use it. */
    static EmulatorDailyBatchOutcome reviewStoppedProjection(Path files,EmulatorDailyRunner.Binding held,String key,WorkCheckpoint cp,long now,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        if(cp==null||!cp.uncertain||!cp.done.isEmpty())bad();
        EmulatorDailyBatchOutcome out=stoppedProjection(files,held,key,cp,now,sync,true);
        if(out.cleanShutdown||out.collected!=0||out.unresolved!=1||out.rejected!=0||out.unlocated!=0||out.unavailable!=0||out.notAuthorized!=out.rows.size()-1)bad();
        return out;
    }
    private static EmulatorDailyBatchOutcome stoppedProjection(Path files,EmulatorDailyRunner.Binding held,String key,WorkCheckpoint cp,long now,EmulatorAndroidDailyStore.DirectorySync sync,boolean review)throws Exception {
        EmulatorDailyBatchInventory inv=EmulatorDailyBatchInventory.read(files.resolve("daily-batch-inventory"),held,key);
        EmulatorDailyBatchSelection selected=EmulatorDailyBatchSelection.read(files,inv,sync);
        String link=readPrivate(files.resolve("daily-batch-links").resolve(EmulatorDailyRunner.digest(key)+".tsv"),4096);
        String[] lines=link.split("\n",-1),f=lines[0].split("\t",-1);
        if(lines.length!=3||!lines[2].isEmpty()||!lines[1].equals("sha256\t"+EmulatorDailyRunner.digest(lines[0]+"\n"))||f.length!=7||!f[0].equals("v2")||!f[5].equals("intent-not-admitted")||!f[6].equals("end"))bad();
        EmulatorDailyBatchWork work=EmulatorDailyBatchWork.decode(EmulatorDailyRunner.unbase64(f[1]));
        if(cp==null||!cp.owner.equals("batch")||!cp.account.equals(held.account)||!cp.id.equals(f[2])||!work.matches(held,key)||!work.queueDigest.equals(selected.queueSha())||!f[3].equals(work.queueDigest)||!f[4].equals(EmulatorDailyRunner.digest(selected.encode()))||work.locationEpoch!=inv.locationEpoch||work.deadline!=inv.deadline||!EmulatorDevelopmentBatch.sameWindow(work.developmentWindow,inv.developmentWindow)||work.created<inv.created||work.created>now||cp.entries.size()!=selected.queue.size())bad();
        for(int i=0;i<cp.entries.size();i++){DispatchProbeGateway.Candidate x=selected.queue.get(i);String row=x.taskId+"\t"+x.kind+"\t"+x.expirationMs+"\t"+x.latitude+"\t"+x.longitude+"\t"+x.distanceMetres;if(!cp.entries.get(i).equals(row))bad();}
        Map<String,EmulatorDailyBatchReceipt.Admission> admissions=new HashMap<>();Map<String,EmulatorDailyBatchReceipt> receipts=new HashMap<>();Map<String,EmulatorDailyBatchRejection> rejects=new HashMap<>();Map<String,EmulatorDailyBatchObserved> observed=new HashMap<>();
        for(DispatchProbeGateway.Candidate x:inv.candidates){
            if(selected.excluded.containsKey(x.taskId))continue;
            String leaf=EmulatorDailyBatchWork.taskLeaf(key,x.taskId)+".tsv";
            Path a=files.resolve("daily-batch-tasks").resolve(leaf),r=files.resolve("daily-batch-claims").resolve(leaf),j=files.resolve("daily-batch-rejections").resolve(leaf),o=files.resolve("daily-batch-observed").resolve(leaf);
            if(Files.exists(a,LinkOption.NOFOLLOW_LINKS)){EmulatorDailyBatchReceipt.Admission admission=EmulatorDailyBatchReceipt.Admission.decode(readPrivate(a,4096));admissions.put(x.taskId,admission);if(Files.exists(r,LinkOption.NOFOLLOW_LINKS))receipts.put(x.taskId,EmulatorDailyBatchReceipt.decode(readPrivate(r,4096),admission));if(Files.exists(j,LinkOption.NOFOLLOW_LINKS))rejects.put(x.taskId,EmulatorDailyBatchRejection.decode(readPrivate(j,4096),admission));}
            else if(Files.exists(r,LinkOption.NOFOLLOW_LINKS)||Files.exists(j,LinkOption.NOFOLLOW_LINKS))bad();
            if(Files.exists(o,LinkOption.NOFOLLOW_LINKS))observed.put(x.taskId,EmulatorDailyBatchObserved.decode(readPrivate(o,4096)));
        }
        EmulatorDailyBatchOutcome outcome=new EmulatorDailyBatchOutcome(inv,cp.id,EmulatorDailyRunner.digest(link),now,!review,admissions,receipts,Collections.emptyMap(),rejects,selected,observed);
        for(int i=cp.done.nextSetBit(0);i>=0;i=cp.done.nextSetBit(i+1)){String id=selected.queue.get(i).taskId;if(!receipts.containsKey(id)&&!observed.containsKey(id)&&!rejects.containsKey(id))bad();}
        if(!review&&(cp.uncertain||outcome.unresolved!=0))bad();return outcome;
    }
    /** No dispatch or GPS movement. Recover only a missing summary after the
     * exact current saved batch stopped, with independently OFF controls. */
    static void recoverMissing(android.content.Context c,EmulatorDailyRunner.Binding held,String key)throws Exception {
        synchronized(GpsWorkLock.class){
            Path files=EmulatorAndroidDailyStore.files(c),dest=files.resolve("daily-batch-outcomes").resolve(EmulatorDailyRunner.digest(key)+".tsv");
            if(Files.exists(dest,LinkOption.NOFOLLOW_LINKS)||busy(c)||GpsWorkLock.anyHeld(c)||WorkStatus.stoppingAll||BatchRecoveryGateway.checking())return;
            EmulatorSavedDailyStore saved=EmulatorSavedDailyStore.open(c);
            if(saved==null||!saved.current()||!key.equals(saved.get("batch.key",""))||!EmulatorDailyRunner.base64(held.encode()).equals(saved.get("batch.binding",""))||!saved.get("gps.pending","").isEmpty()||!saved.get("plant.pending","").isEmpty()||!saved.get("gps.phase","").equals("PAUSED"))bad();
            retainedIdentity(c,held);StopChecks.dispatch();
            String proof="set -eu; test ! -e /data/user/0/com.nianticlabs.pikmin/files/emulator-command; for n in dispatch return planting planter flower-farm; do p=/data/local/tmp/pikmin-$n-mode.txt; test ! -L $p; test \"$(cat $p)\" = off; done; now=$(date +%s%3N); awk -F '\\t' -v p="+held.pid+" -v now=$now 'NR==1 && NF==6 && $1==\"v1\" && $3==p && $4==\"inactive\" && $5==\"inactive\" && $6==\"end\" && now>=$2 && now-$2<=10000 {ok=1} END {exit !(ok && NR==1)}' /data/user/0/com.nianticlabs.pikmin/files/emulator_planting_observation.tsv; echo VERIFIED";
            EmulatorSavedDailyRuntime.root(proof);
            WorkCheckpoint cp=CheckpointStore.read(c,GpsWorkLock.BATCH);String checksum=cp.properties().getProperty("checksum");
            String recoveryLeaf=EmulatorDailyRunner.digest("summary-recovery:"+key);Path recoveryFile=files.resolve("daily-batch-links").resolve(recoveryLeaf+".tsv");
            long ended=System.currentTimeMillis();String previous="";
            if(Files.exists(recoveryFile,LinkOption.NOFOLLOW_LINKS)){previous=readPrivate(recoveryFile,4096);String[] f=previous.split("\n",-1)[0].split("\t",-1);if(f.length!=8||!f[0].equals("PCCBATCHRECOVERY/1")||!f[1].equals(key)||!f[2].equals(cp.id)||!f[3].equals(checksum))bad();ended=Long.parseLong(f[5]);if(ended>System.currentTimeMillis())bad();}
            EmulatorDailyBatchOutcome candidate=recoverProjection(files,held,key,cp,ended,EmulatorAndroidDailyStore::syncDirectory);
            if(NearbyWorkState.pending(c)){
                if(!NearbyWorkState.ownedBy(c,GpsWorkLock.BATCH))bad();GpsWorkLock.acquireBatchRecovery(c);
                try{NearbyWorkState.restoreDeferred(c,GpsWorkLock.BATCH);}finally{GpsWorkLock.release(c,GpsWorkLock.BATCH);}
            }
            if(NearbyWorkState.pending(c)||GpsWorkLock.anyHeld(c)||busy(c))bad();
            retainedIdentity(c,held);EmulatorSavedDailyRuntime.root(proof);StopChecks.dispatch();
            if(!checksum.equals(CheckpointStore.read(c,GpsWorkLock.BATCH).properties().getProperty("checksum")))bad();
            String record="PCCBATCHRECOVERY/1\t"+key+"\t"+cp.id+"\t"+checksum+"\t"+EmulatorDailyRunner.digest(candidate.encode())+"\t"+candidate.ended+"\tOFF_RECONCILED_NO_REPLAY\tend\n";
            record+="sha256\t"+EmulatorDailyRunner.digest(record)+"\n";
            if(previous.isEmpty())EmulatorDailyBatchWork.persist(files.resolve("daily-batch-links"),recoveryLeaf,record,EmulatorAndroidDailyStore::syncDirectory,p->Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            else if(!record.equals(previous))bad();
            candidate.persist(files.resolve("daily-batch-outcomes"),EmulatorAndroidDailyStore::syncDirectory,p->Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            if(readExact(files,held,key,false,EmulatorAndroidDailyStore::syncDirectory)==null)bad();
            WorkStatus.batchRecovery(c,"stopped","既有批次收據與停止控制已重新核對；未重送任何任務，未授權候選保留。");
        }
    }
    static EmulatorDailyRunner.Observation completed(android.content.Context c,EmulatorDailyRunner.Binding held,String key)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        java.nio.file.Path files=EmulatorAndroidDailyStore.files(c),result=files.resolve("daily-batch-outcomes").resolve(EmulatorDailyRunner.digest(key)+".tsv");
        if(!Files.exists(result,LinkOption.NOFOLLOW_LINKS))recoverMissing(c,held,key);
        if(!Files.exists(result,LinkOption.NOFOLLOW_LINKS)||busy(c))return unknown(held,key);
        retainedIdentity(c,held);
        List<EmulatorDailyBatchOutcome> batches=new ArrayList<>();int count=Integer.parseInt(key.substring(key.length()-1));
        for(int i=1;i<=count;i++){
            EmulatorDailyBatchOutcome o=readExact(files,held,EmulatorDevelopmentBatch.keyAt(key,i),i<count,EmulatorAndroidDailyStore::syncDirectory);
            if(o==null||o.needsReview())return unknown(held,key);batches.add(o);
        }
        if(busy(c))return unknown(held,key);
        EmulatorDailyRunner.Binding live=retainedIdentity(c,held);
        EmulatorDailyBatchScan.Snapshot current=EmulatorDailyBatchScan.read(c,live);
        EmulatorDailyBatchScan.Snapshot fresh=new EmulatorDailyBatchScan.Snapshot(held,current.scanMs,current.inventoryCount,current.candidates,current.located,current.unlocated);
        retainedIdentity(c,held);if(busy(c))return unknown(held,key);
        EmulatorDailyBatchDay day=new EmulatorDailyBatchDay(held,key,batches,fresh);
        return new EmulatorDailyRunner.Observation(held,key,day.evidence());
    }
    /** Actual immutable-file reconciliation; sync is an Android implementation
     * in production and a recorded seam only in HOST filesystem tests. */
    static EmulatorDailyBatchOutcome readExact(Path files,EmulatorDailyRunner.Binding held,String key,boolean allowLaterAdmission,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        if(sync==null)bad();Path result=files.resolve("daily-batch-outcomes").resolve(EmulatorDailyRunner.digest(key)+".tsv");
        EmulatorDailyBatchInventory inv=EmulatorDailyBatchInventory.read(files.resolve("daily-batch-inventory"),held,key);
        String wire=readPrivate(result,MAX_BYTES);String[] lines=wire.split("\n",-1),header=lines[0].split("\t",-1);
        boolean projected=header.length==9&&header[0].equals("v2");if(!projected&&(header.length!=8||!header[0].equals("v1")))bad();String cp=header[3],claim=header[4];
        EmulatorDailyBatchSelection selection=projected?EmulatorDailyBatchSelection.read(files,inv,sync,allowLaterAdmission):null;
        if(projected&&!header[8].equals(EmulatorDailyRunner.digest(selection.encode())))bad();
        Map<String,EmulatorDailyBatchReceipt.Admission> admissions=new HashMap<>();Map<String,EmulatorDailyBatchReceipt> receipts=new HashMap<>();Map<String,String> reasons=new HashMap<>();
        Map<String,EmulatorDailyBatchRejection> rejections=new HashMap<>();
        Map<String,EmulatorDailyBatchObserved> observations=new HashMap<>();
        List<Path> evidence=new ArrayList<>();evidence.add(result);List<String> originals=new ArrayList<>();originals.add(wire);
        evidence.add(files.resolve("daily-batch-inventory").resolve(EmulatorDailyRunner.digest(key)+".tsv"));originals.add(inv.encode());
        if(inv.developmentWindow!=null){
            EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.requireInventory(files,inv);
            Path runFile=files.resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.runLeaf(run.window.key)+".tsv");
            Path reservation=files.resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.reservationLeaf(key)+".tsv");
            evidence.add(runFile);originals.add(run.encode());evidence.add(reservation);originals.add(readPrivate(reservation,4096));
        }
        if(!cp.equals("-")||!claim.equals("-")){
            Path link=files.resolve("daily-batch-links").resolve(EmulatorDailyRunner.digest(key)+".tsv");String raw=readPrivate(link,4096);
            String[] linkRows=raw.split("\n",-1),f=linkRows[0].split("\t",-1);
            if(linkRows.length!=3||!linkRows[2].isEmpty()||!linkRows[1].equals("sha256\t"+EmulatorDailyRunner.digest(linkRows[0]+"\n"))||f.length!=(projected?7:6)||!f[0].equals(projected?"v2":"v1")||!f[projected?5:4].equals("intent-not-admitted")||!f[projected?6:5].equals("end")||!f[2].equals(cp)||!EmulatorDailyRunner.digest(raw).equals(claim)||projected&&!f[4].equals(header[8]))bad();
            EmulatorDailyBatchWork work=EmulatorDailyBatchWork.decode(EmulatorDailyRunner.unbase64(f[1]));
            String digest=selection==null?EmulatorDailyBatchWork.queueSha(BatchSites.group(inv.located)):selection.queueSha();
            if(!work.matches(held,key)||!work.queueDigest.equals(digest)||!f[3].equals(digest)||work.locationEpoch!=inv.locationEpoch||work.deadline!=inv.deadline||!EmulatorDevelopmentBatch.sameWindow(work.developmentWindow,inv.developmentWindow)||work.created<inv.created||work.created>Long.parseLong(header[5]))bad();
            evidence.add(link);originals.add(raw);
        }
        for(int index=1;index<lines.length-2;index++){
            String[] f=lines[index].split("\t",-1);if(f.length!=6||!f[0].matches("[A-Za-z0-9_-]{1,160}"))bad();
            Path task=files.resolve("daily-batch-tasks").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");
            if(f[2].startsWith("prior-")){
                if(selection==null)bad();EmulatorDailyBatchSelection.Prior prior=selection.excluded.get(f[0]);
                if(prior==null||!prior.state().equals(f[2])||!prior.authoritySha().equals(f[3])||!prior.proofSha().equals(f[4])||!prior.key().equals(f[5]))bad();
                if(prior.observed==null){evidence.add(task);originals.add(prior.admission.encode());}
                Path proof=files.resolve(prior.observed!=null?"daily-batch-observed":prior.receipt!=null?"daily-batch-claims":"daily-batch-rejections").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");evidence.add(proof);originals.add(prior.observed!=null?prior.observed.encode():prior.receipt!=null?prior.receipt.encode():prior.rejection.encode());
            }else if(f[2].equals("observed-collected")){
                for(String dir:Arrays.asList("daily-batch-tasks","daily-batch-claims","daily-batch-rejections"))if(Files.exists(files.resolve(dir).resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv"),LinkOption.NOFOLLOW_LINKS))return null;
                Path proof=files.resolve("daily-batch-observed").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");String raw=readPrivate(proof,4096);
                if(!EmulatorDailyRunner.digest(raw).equals(f[3])||!f[3].equals(f[4])||observations.put(f[0],EmulatorDailyBatchObserved.decode(raw))!=null)bad();evidence.add(proof);originals.add(raw);
            }else if(f[2].equals("collected")||f[2].equals("authorized-unresolved")||f[2].equals("confirmed-not-sent")){
                if(Files.exists(files.resolve("daily-batch-observed").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv"),LinkOption.NOFOLLOW_LINKS))return null;
                String raw=readPrivate(task,4096);EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(raw);
                if(!EmulatorDailyRunner.digest(raw).equals(f[3])||admissions.put(f[0],a)!=null)bad();evidence.add(task);originals.add(raw);
                if(f[2].equals("collected")){
                    if(Files.exists(files.resolve("daily-batch-rejections").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv"),LinkOption.NOFOLLOW_LINKS))return null;
                    Path receipt=files.resolve("daily-batch-claims").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");String r=readPrivate(receipt,4096);
                    if(!EmulatorDailyRunner.digest(r).equals(f[4]))bad();receipts.put(f[0],EmulatorDailyBatchReceipt.decode(r,a));evidence.add(receipt);originals.add(r);
                }else if(f[2].equals("confirmed-not-sent")){
                    if(Files.exists(files.resolve("daily-batch-claims").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv"),LinkOption.NOFOLLOW_LINKS))return null;
                    Path rejected=files.resolve("daily-batch-rejections").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");String r=readPrivate(rejected,4096);
                    if(!EmulatorDailyRunner.digest(r).equals(f[4]))bad();rejections.put(f[0],EmulatorDailyBatchRejection.decode(r,a));evidence.add(rejected);originals.add(r);
                }
            }else{
                // Old non-admission remains true only if the immutable task
                // proves a strictly later key/time, never an earlier/partial file.
                if(Files.exists(task,LinkOption.NOFOLLOW_LINKS)){
                    if(!allowLaterAdmission)return null;
                    String raw=readPrivate(task,4096);EmulatorDailyBatchReceipt.Admission later=EmulatorDailyBatchReceipt.Admission.decode(raw);
                    if(!later.binding.same(held)||!later.id.equals(f[0])||!later.kind.equals(f[1])||!EmulatorDevelopmentBatch.earlier(key,later.key)||!EmulatorDevelopmentBatch.sameWindow(later.developmentWindow,inv.developmentWindow)||later.at<Long.parseLong(header[5]))bad();
                    evidence.add(task);originals.add(raw);
                }
                Path observation=files.resolve("daily-batch-observed").resolve(EmulatorDailyBatchWork.taskLeaf(key,f[0])+".tsv");
                if(Files.exists(observation,LinkOption.NOFOLLOW_LINKS)){
                    if(!allowLaterAdmission)return null;String raw=readPrivate(observation,4096);EmulatorDailyBatchObserved later=EmulatorDailyBatchObserved.decode(raw);
                    if(!later.binding.same(held)||!later.id.equals(f[0])||!later.kind.equals(f[1])||!EmulatorDevelopmentBatch.earlier(key,later.key)||!EmulatorDevelopmentBatch.sameWindow(later.developmentWindow,inv.developmentWindow)||later.notBefore<Long.parseLong(header[5]))bad();
                    evidence.add(observation);originals.add(raw);
                }
                reasons.put(f[0],f[5]);
            }
        }
        EmulatorDailyBatchOutcome outcome=validate(wire,inv,cp,claim,admissions,receipts,reasons,rejections,selection,observations);
        if(outcome.needsReview())return outcome;
        // Re-establish durability even if an earlier directory sync failed.
        for(Path file:evidence){try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}sync.sync(file.getParent());}
        sync.sync(files);
        for(int i=0;i<evidence.size();i++)if(!originals.get(i).equals(readPrivate(evidence.get(i),i<2?MAX_BYTES:4096)))bad();
        if(!inv.encode().equals(EmulatorDailyBatchInventory.read(files.resolve("daily-batch-inventory"),held,key).encode()))bad();return outcome;
    }
    /** Classification only; callers still need all independent file/runtime gates. */
    static EmulatorDailyRunner.Evidence evidenceFor(EmulatorDailyBatchOutcome outcome,EmulatorDailyBatchScan.Snapshot fresh){
        if(outcome==null||fresh==null||outcome.needsReview()||!outcome.inventory.binding.same(fresh.binding)||fresh.scanMs<outcome.ended)return EmulatorDailyRunner.Evidence.UNKNOWN;
        return outcome.allOriginalCollected()&&fresh.candidates.isEmpty()?EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS:EmulatorDailyRunner.Evidence.COMPLETE;
    }
    private static boolean busy(android.content.Context c){return DispatchBatchService.isRunning()||GpsWorkLock.heldBy(c,GpsWorkLock.BATCH)||GpsWorkLock.heldBy(c,GpsWorkLock.FLOWER_FARM);}
    private static EmulatorDailyRunner.Observation unknown(EmulatorDailyRunner.Binding b,String key){return new EmulatorDailyRunner.Observation(b,key,EmulatorDailyRunner.Evidence.UNKNOWN);}
    static String readPrivate(Path file,int maximum)throws Exception {
        for(Path p=file.getParent();p!=null;p=p.getParent())if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))bad();
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))bad();
        byte[] bytes;
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long n=in.size();if(n<1||n>maximum)bad();ByteBuffer b=ByteBuffer.allocate((int)n);while(b.hasRemaining())if(in.read(b)<0)bad();
            if(in.size()!=n||in.read(ByteBuffer.allocate(1))!=-1)bad();bytes=b.array();
        }
        String text=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8)))bad();return text;
    }
    private static void bad(){throw new IllegalArgumentException("Full exact batch outcome required; retain every unresolved task");}
}

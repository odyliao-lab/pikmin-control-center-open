package dev.ody.pikmincontrol;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Persisted pre-effect queue projection. Full inventory remains immutable;
 * excluded tasks carry exact OLD admissions/terminal proof, never new authority. */
final class EmulatorDailyBatchSelection {
    static final class Prior {
        final EmulatorDailyBatchReceipt.Admission admission;
        final EmulatorDailyBatchReceipt receipt;
        final EmulatorDailyBatchRejection rejection;
        final EmulatorDailyBatchObserved observed;
        Prior(EmulatorDailyBatchReceipt.Admission a,EmulatorDailyBatchReceipt receipt,EmulatorDailyBatchRejection rejection){
            if(a==null||receipt==null&&rejection==null||receipt!=null&&rejection!=null)bad();
            if(receipt!=null)EmulatorDailyBatchReceipt.decode(receipt.encode(),a);
            if(rejection!=null)EmulatorDailyBatchRejection.decode(rejection.encode(),a);
            admission=a;this.receipt=receipt;this.rejection=rejection;observed=null;
        }
        Prior(EmulatorDailyBatchObserved o){if(o==null)bad();observed=EmulatorDailyBatchObserved.decode(o.encode());admission=null;receipt=null;rejection=null;}
        String state(){return observed!=null?"prior-observed-collected":receipt!=null?"prior-collected":"prior-confirmed-not-sent";}
        String proofSha(){return EmulatorDailyRunner.digest(observed!=null?observed.encode():receipt!=null?receipt.encode():rejection.encode());}
        String authoritySha(){return observed!=null?proofSha():EmulatorDailyRunner.digest(admission.encode());}
        String key(){return observed!=null?observed.key:admission.key;}
        String id(){return observed!=null?observed.id:admission.id;}
        String kind(){return observed!=null?observed.kind:admission.kind;}
        EmulatorDailyRunner.Binding binding(){return observed!=null?observed.binding:admission.binding;}
        long ended(){return observed!=null?observed.observedAt:receipt!=null?receipt.collectedAt:rejection.observedAt;}
        EmulatorDevelopmentBatch.Window window(){return observed!=null?observed.developmentWindow:admission.developmentWindow;}
    }
    final EmulatorDailyBatchInventory inventory;
    final Map<String,Prior> excluded;
    final List<DispatchProbeGateway.Candidate> queue;
    final List<String> sourceShas;
    EmulatorDailyBatchSelection(EmulatorDailyBatchInventory inv,Map<String,Prior> excluded,List<String> sources){
        if(inv==null||excluded==null||sources==null||sources.size()!=Integer.parseInt(inv.key.substring(inv.key.length()-1))-1)bad();
        for(String sha:sources)if(sha==null||!sha.matches("[a-f0-9]{64}"))bad();
        Set<String> seen=new HashSet<>();List<DispatchProbeGateway.Candidate> eligible=new ArrayList<>();LinkedHashMap<String,Prior> refs=new LinkedHashMap<>();
        for(DispatchProbeGateway.Candidate c:inv.candidates){
            seen.add(c.taskId);Prior p=excluded.get(c.taskId);
            if(p!=null){
                if(!p.binding().same(inv.binding)||!p.id().equals(c.taskId)||!p.kind().equals(c.kind)||!EmulatorDevelopmentBatch.earlier(p.key(),inv.key)||
                        !EmulatorDevelopmentBatch.sameWindow(p.window(),inv.developmentWindow)||p.ended()>inv.created)bad();
                refs.put(c.taskId,p);
            }else if(!inv.unlocated.contains(c.taskId))eligible.add(c);
        }
        if(!seen.containsAll(excluded.keySet()))bad();
        inventory=inv;this.excluded=Collections.unmodifiableMap(refs);queue=Collections.unmodifiableList(BatchSites.group(eligible));sourceShas=Collections.unmodifiableList(new ArrayList<>(sources));
    }
    String queueSha(){return queue.isEmpty()?"-":EmulatorDailyBatchWork.queueSha(queue);}
    String encode(){
        StringBuilder body=new StringBuilder("v1\t"+inventory.key+"\t"+EmulatorDailyRunner.digest(inventory.encode())+"\t"+queueSha()+"\t"+excluded.size()+"\n");
        for(String sha:sourceShas)body.append("source\t").append(sha).append('\n');
        for(Map.Entry<String,Prior> e:excluded.entrySet()){Prior p=e.getValue();body.append(e.getKey()).append('\t').append(p.kind()).append('\t').append(p.key()).append('\t').append(p.authoritySha()).append('\t').append(p.state()).append('\t').append(p.proofSha()).append('\n');}
        String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";if(wire.getBytes(StandardCharsets.UTF_8).length>262144)bad();return wire;
    }
    static EmulatorDailyBatchSelection create(Path files,EmulatorDailyBatchInventory inv,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception{return build(files,inv,false,false,sync);}
    static EmulatorDailyBatchSelection read(Path files,EmulatorDailyBatchInventory inv,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        return read(files,inv,sync,false);
    }
    static EmulatorDailyBatchSelection read(Path files,EmulatorDailyBatchInventory inv,EmulatorAndroidDailyStore.DirectorySync sync,boolean allowLaterAdmission)throws Exception {
        EmulatorDailyBatchSelection selected=build(files,inv,true,allowLaterAdmission,sync);
        Path file=files.resolve("daily-batch-selections").resolve(EmulatorDailyRunner.digest(inv.key)+".tsv");
        String wire=EmulatorDailyBatchOutcome.readPrivate(file,262144);if(!wire.equals(selected.encode()))bad();
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){channel.force(true);}sync.sync(file.getParent());sync.sync(files);
        if(!wire.equals(EmulatorDailyBatchOutcome.readPrivate(file,262144)))bad();return selected;
    }
    private static EmulatorDailyBatchSelection build(Path files,EmulatorDailyBatchInventory inv,boolean allowCurrent,boolean allowLaterAdmission,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception {
        if(inv==null||sync==null)bad();int index=Integer.parseInt(inv.key.substring(inv.key.length()-1));
        List<EmulatorDailyBatchOutcome> prior=new ArrayList<>();List<String> sources=new ArrayList<>();Map<String,String[]> admitted=new HashMap<>();
        for(int n=1;n<index;n++){
            EmulatorDailyBatchOutcome o=EmulatorDailyBatchOutcome.readExact(files,inv.binding,EmulatorDevelopmentBatch.keyAt(inv.key,n),true,sync);
            if(o==null||o.needsReview()||o.ended>inv.created)bad();prior.add(o);sources.add(EmulatorDailyRunner.digest(o.encode()));
            for(String raw:o.rows){String[] row=raw.split("\t",-1);if(!row[3].equals("-")){String[] old=admitted.put(row[0],row);if(old!=null&&!old[3].equals(row[3]))bad();}}
        }
        if(!prior.isEmpty()){
            EmulatorDailyBatchScan.Snapshot scan=new EmulatorDailyBatchScan.Snapshot(inv.binding,inv.scanMs,inv.inventoryCount,inv.candidates,inv.located,inv.unlocated);
            EmulatorDailyBatchDay day=new EmulatorDailyBatchDay(inv.binding,EmulatorDevelopmentBatch.keyAt(inv.key,index-1),prior,scan);
            if(!day.clean||day.unresolved!=0)bad();
        }
        Map<String,Prior> refs=new HashMap<>();
        for(DispatchProbeGateway.Candidate c:inv.candidates){
            String[] row=admitted.get(c.taskId);Path task=files.resolve("daily-batch-tasks").resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv");
            if(row==null){
                if(Files.exists(task,LinkOption.NOFOLLOW_LINKS)){
                    if(!allowCurrent)bad();EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(EmulatorDailyBatchOutcome.readPrivate(task,4096));
                    if(!a.binding.same(inv.binding)||!(a.key.equals(inv.key)||allowLaterAdmission&&EmulatorDevelopmentBatch.earlier(inv.key,a.key))||!EmulatorDevelopmentBatch.sameWindow(a.developmentWindow,inv.developmentWindow)||!a.id.equals(c.taskId)||!a.kind.equals(c.kind)||a.at<inv.created)bad();
                }
                Path seen=files.resolve("daily-batch-observed").resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv");
                if(Files.exists(seen,LinkOption.NOFOLLOW_LINKS)){
                    if(!allowCurrent||Files.exists(task,LinkOption.NOFOLLOW_LINKS))bad();EmulatorDailyBatchObserved o=EmulatorDailyBatchObserved.decode(EmulatorDailyBatchOutcome.readPrivate(seen,4096));
                    if(!o.binding.same(inv.binding)||!(o.key.equals(inv.key)||allowLaterAdmission&&EmulatorDevelopmentBatch.earlier(inv.key,o.key))||!EmulatorDevelopmentBatch.sameWindow(o.developmentWindow,inv.developmentWindow)||!o.id.equals(c.taskId)||!o.kind.equals(c.kind)||o.notBefore<inv.created)bad();
                }
                continue;
            }
            if(row[2].equals("observed-collected")||row[2].equals("prior-observed-collected")){
                for(String dir:Arrays.asList("daily-batch-tasks","daily-batch-claims","daily-batch-rejections"))if(Files.exists(files.resolve(dir).resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv"),LinkOption.NOFOLLOW_LINKS))bad();
                String wire=EmulatorDailyBatchOutcome.readPrivate(files.resolve("daily-batch-observed").resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv"),4096);
                if(!EmulatorDailyRunner.digest(wire).equals(row[3])||!row[3].equals(row[4]))bad();refs.put(c.taskId,new Prior(EmulatorDailyBatchObserved.decode(wire)));continue;
            }
            EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(EmulatorDailyBatchOutcome.readPrivate(task,4096));if(!EmulatorDailyRunner.digest(a.encode()).equals(row[3]))bad();
            Path claim=files.resolve("daily-batch-claims").resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv"),reject=files.resolve("daily-batch-rejections").resolve(EmulatorDailyBatchWork.taskLeaf(inv.key,c.taskId)+".tsv");
            boolean got=row[2].equals("collected")||row[2].equals("prior-collected"),notSent=row[2].equals("confirmed-not-sent")||row[2].equals("prior-confirmed-not-sent");
            if(got==notSent||got&&Files.exists(reject,LinkOption.NOFOLLOW_LINKS)||notSent&&Files.exists(claim,LinkOption.NOFOLLOW_LINKS))bad();
            String wire=EmulatorDailyBatchOutcome.readPrivate(got?claim:reject,4096);if(!EmulatorDailyRunner.digest(wire).equals(row[4]))bad();
            refs.put(c.taskId,new Prior(a,got?EmulatorDailyBatchReceipt.decode(wire,a):null,notSent?EmulatorDailyBatchRejection.decode(wire,a):null));
        }
        return new EmulatorDailyBatchSelection(inv,refs,sources);
    }
    void persist(Path files,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception {
        Path root=files.resolve("daily-batch-selections");if(sync==null||mode==null||!files.getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        Files.createDirectories(root);sync.sync(files);sync.sync(root);Path file=root.resolve(EmulatorDailyRunner.digest(inventory.key)+".tsv");byte[] bytes=encode().getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){mode.apply(file);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);}sync.sync(root);
        if(!encode().equals(EmulatorDailyBatchOutcome.readPrivate(file,262144)))bad();
    }
    private static void bad(){throw new IllegalArgumentException("Exact immutable batch selection required; no old admission replay");}
}

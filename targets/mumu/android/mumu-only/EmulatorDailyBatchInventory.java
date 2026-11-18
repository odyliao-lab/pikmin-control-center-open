package dev.ody.pikmincontrol;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable pre-effect full inventory. Includes unlocated tasks; no claim or
 * completion authority is inferred from an empty inventory or service exit. */
final class EmulatorDailyBatchInventory {
    static final int MAX_BYTES=262144;
    final EmulatorDailyRunner.Binding binding;
    final String key;
    final long locationEpoch,deadline,created,scanMs;
    final EmulatorDevelopmentBatch.Window developmentWindow;
    final int inventoryCount;
    final List<DispatchProbeGateway.Candidate> candidates,located;
    final Set<String> unlocated;
    EmulatorDailyBatchInventory(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,long created,EmulatorDailyBatchScan.Snapshot scan) {
        this(held,key,epoch,deadline,created,scan,null);
    }
    static EmulatorDailyBatchInventory development(EmulatorDailyRunner.Binding held,EmulatorDevelopmentBatch.Window window,long epoch,long created,EmulatorDailyBatchScan.Snapshot scan){
        if(window==null)bad();return new EmulatorDailyBatchInventory(held,window.key,epoch,window.deadline,created,scan,window);
    }
    private EmulatorDailyBatchInventory(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,long created,EmulatorDailyBatchScan.Snapshot scan,EmulatorDevelopmentBatch.Window window) {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        long expected=EmulatorDailyPlan.deadline(key);
        java.time.ZonedDateTime time=java.time.Instant.ofEpochMilli(created).atZone(EmulatorDailyPlan.ZONE);
        if(epoch<=0||created<=0||created>=deadline||
           (window==null?(EmulatorDevelopmentBatch.development(key)||deadline!=expected||!EmulatorDailyPlan.batchWindow(key,created)):(!window.key.equals(key)||deadline!=window.deadline||!window.open(created)))||scan==null||!held.same(scan.binding)||scan.scanMs<=0||
           scan.scanMs>created||created-scan.scanMs>12000||scan.inventoryCount<0||scan.inventoryCount>EmulatorDailyBatchScan.MAX_TASKS||
           scan.candidates==null||scan.candidates.size()>scan.inventoryCount)bad();
        List<DispatchProbeGateway.Candidate> all=new ArrayList<>(scan.candidates),valid=new ArrayList<>();Set<String> unknown=new HashSet<>();
        if(!all.isEmpty())EmulatorDailyBatchWork.queueSha(all); // Full row/type/range/duplicate validation, not a filter.
        for(DispatchProbeGateway.Candidate c:all){if(c.latitude==0&&c.longitude==0)unknown.add(c.taskId);else valid.add(c);}
        binding=held;this.key=key;locationEpoch=epoch;this.deadline=deadline;this.created=created;scanMs=scan.scanMs;inventoryCount=scan.inventoryCount;
        developmentWindow=window;
        candidates=Collections.unmodifiableList(all);located=Collections.unmodifiableList(valid);unlocated=Collections.unmodifiableSet(unknown);
    }
    String encode() {
        StringBuilder body=new StringBuilder((developmentWindow==null?"v1":"v2-development")+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+locationEpoch+"\t"+deadline+"\t"+created+"\t"+scanMs+"\t"+inventoryCount+"\t"+candidates.size()+(developmentWindow==null?"":"\t"+developmentWindow.created)+"\n");
        for(DispatchProbeGateway.Candidate c:candidates)body.append(c.taskId).append('\t').append(c.kind).append('\t').append(c.expirationMs).append('\t').append(c.latitude).append('\t').append(c.longitude).append('\t').append(c.distanceMetres).append('\n');
        String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
        if(wire.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)bad();return wire;
    }
    static EmulatorDailyBatchInventory decode(String wire) {
        if(wire==null||wire.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES||wire.indexOf('\r')>=0||!wire.endsWith("\n"))bad();
        String[] rows=wire.split("\n",-1);if(rows.length<3||!rows[rows.length-1].isEmpty())bad();
        int tail=wire.lastIndexOf("sha256\t");if(tail<0||!rows[rows.length-2].equals("sha256\t"+EmulatorDailyRunner.digest(wire.substring(0,tail))))bad();
        String[] f=rows[0].split("\t",-1);boolean dev=f.length==10&&f[0].equals("v2-development");if(!(dev||f.length==9&&f[0].equals("v1")))bad();
        long count=Long.parseLong(f[8]);if(count<0||count>EmulatorDailyBatchScan.MAX_TASKS||count!=rows.length-3)bad();
        List<DispatchProbeGateway.Candidate> all=new ArrayList<>();
        for(int i=1;i<rows.length-2;i++){
            String[] c=rows[i].split("\t",-1);if(c.length!=6)bad();
            all.add(new DispatchProbeGateway.Candidate(c[0],c[1],Long.parseLong(c[2]),Double.parseDouble(c[3]),Double.parseDouble(c[4]),Double.parseDouble(c[5])));
        }
        EmulatorDailyRunner.Binding b=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1]));
        EmulatorDailyBatchScan.Snapshot scan=new EmulatorDailyBatchScan.Snapshot(b,Long.parseLong(f[6]),Integer.parseInt(f[7]),all,Collections.emptyList(),Collections.emptySet());
        EmulatorDevelopmentBatch.Window window=dev?new EmulatorDevelopmentBatch.Window(f[2],Long.parseLong(f[9]),Long.parseLong(f[4])):null;
        EmulatorDailyBatchInventory result=new EmulatorDailyBatchInventory(b,f[2],Long.parseLong(f[3]),Long.parseLong(f[4]),Long.parseLong(f[5]),scan,window);
        if(!wire.equals(result.encode()))bad();return result;
    }
    void persist(Path root,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyRouteWork.PrivateMode mode)throws Exception {
        root=checkedRoot(root);if(sync==null||mode==null)bad();
        Files.createDirectories(root);sync.sync(root.getParent());sync.sync(root);
        Path file=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");byte[] bytes=encode().getBytes(StandardCharsets.UTF_8);
        try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(file);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);
        }
        sync.sync(root);if(!encode().equals(read(root,binding,key).encode()))bad();
    }
    static EmulatorDailyBatchInventory read(Path root,EmulatorDailyRunner.Binding held,String key)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);root=checkedRoot(root);
        Path file=root.resolve(EmulatorDailyRunner.digest(key)+".tsv");
        if(Files.isSymbolicLink(file)||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>MAX_BYTES)bad();
        // Held descriptor and explicit bound, not an unbounded allocation after size check.
        byte[] bytes;
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long n=in.size();if(n<1||n>MAX_BYTES)bad();ByteBuffer b=ByteBuffer.allocate((int)n);
            while(b.hasRemaining()){if(in.read(b)<0)bad();}if(in.size()!=n||in.read(ByteBuffer.allocate(1))!=-1)bad();bytes=b.array();
        }
        EmulatorDailyBatchInventory result=decode(new String(bytes,StandardCharsets.UTF_8));
        if(!result.binding.same(held)||!result.key.equals(key))bad();return result;
    }
    private static Path checkedRoot(Path root) {
        if(root==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getParent()==null||!root.getFileName().toString().equals("daily-batch-inventory")||!root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();return root;
    }
    private static void bad(){throw new IllegalArgumentException("Exact full daily batch inventory required; never infer claims complete");}
}

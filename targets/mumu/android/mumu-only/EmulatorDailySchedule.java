package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.channels.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable opt-in only, NEVER readiness/acceptance. Current ticket is replaced
 * only after its immutable STOP marker is durable. Unknown tmp is retained.
 * Stop may publish while a tick holds the lock: that tick was already admitted,
 * subsequent ticks cannot proceed. No interruption or gameplay cleanup here.
 */
final class EmulatorDailySchedule implements AutoCloseable {
    static final int BOUND=1024;
    static final class Ticket {
        final String id,configSha;final long created;
        Ticket(String id,String configSha,long created){
            if(id==null||!UUID.fromString(id).toString().equals(id)||configSha==null||!configSha.matches("[0-9a-f]{64}")||created<=0)bad();
            this.id=id;this.configSha=configSha;this.created=created;
        }
        String encode(){String body="v1\t"+id+"\t"+configSha+"\t"+created+"\tend\n";return seal(body);}
        static Ticket decode(String wire,long now){
            String[] f=fields(wire,5);if(!f[0].equals("v1")||!f[4].equals("end"))bad();
            Ticket t=new Ticket(f[1],f[2],Long.parseLong(f[3]));if(now<t.created||!wire.equals(t.encode()))bad();return t;
        }
    }
    private final Path root;
    private final EmulatorAndroidDailyStore.DirectorySync sync;
    private final EmulatorDailyConfiguration.Mode mode;
    private final FileChannel channel;private final FileLock lock;
    private boolean uncertain,closed,observed;private String expected;
    EmulatorDailySchedule(Path root,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyConfiguration.Mode mode)throws Exception{
        if(sync==null||mode==null)bad();this.root=directory(root,false);this.sync=sync;this.mode=mode;
        Files.createDirectories(this.root);directory(this.root,true);mode.apply(this.root,true);
        Path lp=this.root.resolve("schedule.lock");regular(lp,false);
        channel=FileChannel.open(lp,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock held=null;
        try{mode.apply(lp,false);held=channel.tryLock();if(held==null)bad();sync.sync(this.root.getParent());sync.sync(this.root);}
        catch(Exception failure){if(held!=null)held.release();channel.close();throw failure;}
        lock=held;
    }
    Ticket read(long now)throws Exception{
        require();String wire=current();observed=true;expected=wire;
        if(wire==null)return null;
        Ticket t=Ticket.decode(wire,now);
        // Re-establish durability after an earlier visible-rename/sync error;
        // contents must already be complete/canonical, never rewritten.
        force(root.resolve("ticket.tsv"));sync.sync(root);if(!wire.equals(current()))bad();return t;
    }
    boolean enabled(Ticket held,long now)throws Exception{
        require();Ticket current=read(now);
        if(held==null||current==null||!held.encode().equals(current.encode()))return false;
        return !stopped(root,held,now,sync);
    }
    Ticket enable(String configSha,long now)throws Exception{
        require();Ticket prior=read(now);
        if(prior!=null){
            if(!prior.configSha.equals(configSha))bad(); // rebind is a separate review, never adoption
            if(!stopped(root,prior,now,sync))return prior; // repeated enabled UI start is idempotent
        }
        Ticket next=new Ticket(UUID.randomUUID().toString(),configSha,now);
        Path temp=root.resolve("ticket.tsv.tmp");
        try{
            if(!observed||!java.util.Objects.equals(expected,current()))bad();
            writeNew(temp,next.encode(),mode); // partial/unknown temp retained on any failure
            if(!java.util.Objects.equals(expected,currentIgnoringTemp()))bad();
            Files.move(temp,root.resolve("ticket.tsv"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            sync.sync(root);if(!next.encode().equals(current()))bad();expected=next.encode();return next;
        }catch(Exception failure){uncertain=true;throw failure;}
    }
    /** Independent one-way cancellation of this exact ticket, including while
     * its tick is in flight. A stale ticket can never disable a newer one.
     * Only caller-held private ticket is accepted; no state adoption. */
    static void stop(Path root,Ticket held,long now,EmulatorAndroidDailyStore.DirectorySync sync,EmulatorDailyConfiguration.Mode mode)throws Exception{
        root=directory(root,true);if(held==null||sync==null||mode==null||now<held.created)bad();
        if(stopped(root,held,now,sync))return;
        String body="v1\t"+held.id+"\t"+EmulatorDailyRunner.digest(held.encode())+"\t"+now+"\tstop\n";
        // CREATE_NEW stops simultaneous writers from replacing each other's
        // requests; a failed/partial marker blocks both resume and re-enable.
        writeNew(root.resolve(held.id+".stop"),seal(body),mode);
        sync.sync(root);if(!stopped(root,held,now,sync))bad();
    }
    private static boolean stopped(Path root,Ticket held,long now,EmulatorAndroidDailyStore.DirectorySync sync)throws Exception{
        Path leaf=root.resolve(held.id+".stop");if(!Files.exists(leaf,LinkOption.NOFOLLOW_LINKS))return false;
        String wire=bounded(leaf);String[] f=fields(wire,5);
        if(!f[0].equals("v1")||!f[1].equals(held.id)||!f[2].equals(EmulatorDailyRunner.digest(held.encode()))||!f[4].equals("stop"))bad();
        long at=Long.parseLong(f[3]);if(at<held.created||at>now||!wire.equals(seal(String.join("\t",f)+"\n")))bad();
        force(leaf);sync.sync(root);if(!wire.equals(bounded(leaf)))bad();return true;
    }
    private String current()throws Exception{
        if(Files.exists(root.resolve("ticket.tsv.tmp"),LinkOption.NOFOLLOW_LINKS))bad();
        return currentIgnoringTemp();
    }
    private String currentIgnoringTemp()throws Exception{
        Path leaf=root.resolve("ticket.tsv");regular(leaf,false);
        return Files.exists(leaf,LinkOption.NOFOLLOW_LINKS)?bounded(leaf):null;
    }
    private static void writeNew(Path leaf,String wire,EmulatorDailyConfiguration.Mode mode)throws Exception{
        byte[] bytes=wire.getBytes(StandardCharsets.UTF_8);if(bytes.length>BOUND)bad();
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            mode.apply(leaf,false);ByteBuffer buf=ByteBuffer.wrap(bytes);while(buf.hasRemaining())out.write(buf);out.force(true);
        }
    }
    private static void force(Path leaf)throws Exception{regular(leaf,true);try(FileChannel f=FileChannel.open(leaf,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){f.force(true);}}
    private static String bounded(Path leaf)throws Exception{
        regular(leaf,true);
        try(FileChannel in=FileChannel.open(leaf,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(in.size()<1||in.size()>BOUND)bad();ByteBuffer b=ByteBuffer.allocate(BOUND+1);while(b.hasRemaining()&&in.read(b)>0){}
            if(b.position()>BOUND)bad();byte[] bytes=java.util.Arrays.copyOf(b.array(),b.position());String s=new String(bytes,StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(bytes,s.getBytes(StandardCharsets.UTF_8)))bad();return s;
        }
    }
    private static String seal(String body){return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    private static String[] fields(String wire,int count){
        if(wire==null||wire.length()>BOUND||wire.indexOf('\r')>=0)bad();String[] r=wire.split("\n",-1);
        if(r.length!=3||!r[2].isEmpty()||!r[1].equals("sha256\t"+EmulatorDailyRunner.digest(r[0]+"\n")))bad();
        String[] f=r[0].split("\t",-1);if(f.length!=count)bad();return f;
    }
    private static Path directory(Path root,boolean exists){
        if(root==null)bad();root=root.toAbsolutePath().normalize();
        if(root.getFileName()==null||!root.getFileName().toString().equals("daily-schedule")||root.getParent()==null||root.getParent().getFileName()==null||!root.getParent().getFileName().toString().equals("files"))bad();
        for(Path p=root;p!=null;p=p.getParent())if(Files.exists(p,LinkOption.NOFOLLOW_LINKS)&&(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)))bad();
        if(exists&&!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))bad();return root;
    }
    private static void regular(Path p,boolean exists){if((exists||Files.exists(p,LinkOption.NOFOLLOW_LINKS))&&(Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)))bad();}
    private void require(){if(closed||uncertain||!channel.isOpen()||!lock.isValid())bad();directory(root,true);}
    @Override public void close()throws Exception{if(!closed){closed=true;try{lock.release();}finally{channel.close();}}}
    private static void bad(){throw new IllegalStateException("Exact durable schedule ticket required; preserve unknown state, no resume/adoption");}
}

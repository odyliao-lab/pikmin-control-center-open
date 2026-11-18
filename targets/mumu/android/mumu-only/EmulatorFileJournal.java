package dev.ody.pikmincontrol;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Actual host-filesystem store. Android/SELinux/directory-fsync/power-loss validation remains separate. */
abstract class EmulatorFileJournal<T> implements AutoCloseable {
    private final Path root,file,temp;
    private final FileChannel channel;private final FileLock lock;
    private final int bound;
    private boolean observed;private String expected;
    EmulatorFileJournal(Path root,String kind)throws IOException {
        if(!"daily-runner".equals(kind)&&!"petal-cycle".equals(kind))throw new IOException("Known journal kind required");
        bound=kind.equals("daily-runner")?4096:2048;
        this.root=root.toAbsolutePath().normalize();
        if(this.root.getFileName()==null || !this.root.getFileName().toString().equals(kind) ||
                this.root.getParent()==null || this.root.getParent().getFileName()==null || !this.root.getParent().getFileName().toString().equals("files"))throw new IOException("Explicit files/"+kind+" directory required");
        checkAncestors(this.root);Files.createDirectories(this.root);checkAncestors(this.root);
        file=this.root.resolve("journal.tsv");temp=this.root.resolve("journal.tsv.tmp");
        Path lockPath=this.root.resolve("runner.lock");checkLeaf(lockPath);
        channel=FileChannel.open(lockPath,StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try{acquired=channel.tryLock();}catch(Exception e){channel.close();throw new IOException("Runner lock unavailable",e);}
        if(acquired==null){channel.close();throw new IOException("Runner already active");}lock=acquired;
    }
    protected abstract T decode(String wire);
    protected abstract String encode(T state);
    public final T read()throws Exception {
        String wire=readWire();observed=true;expected=wire;
        return wire==null?null:decode(wire);
    }
    private String readWire()throws Exception {
        check();if(Files.exists(temp,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unresolved persistence; preserve tmp, no replay");
        checkLeaf(file);if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return null;
        try(FileChannel in=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer data=ByteBuffer.allocate(bound+1);while(data.hasRemaining() && in.read(data)>=0){}
            if(data.position()>bound)throw new IOException("Journal over bound");
            byte[] bytes=new byte[data.position()];data.flip();data.get(bytes);
            String text=new String(bytes,StandardCharsets.UTF_8);
            if(!java.util.Arrays.equals(bytes,text.getBytes(StandardCharsets.UTF_8)))throw new IOException("Invalid journal UTF8");
            if(!text.equals(encode(decode(text))))throw new IOException("Noncanonical journal");return text;
        }
    }
    public final void save(T state)throws Exception {
        check();checkLeaf(file);checkLeaf(temp);
        if(!observed || !java.util.Objects.equals(expected,readWire()))throw new IOException("Daily journal changed or not independently read; preserve bytes");
        String wire=encode(state);if(!wire.equals(encode(decode(wire))))throw new IOException("Noncanonical state");
        byte[] data=wire.getBytes(StandardCharsets.UTF_8);if(data.length>bound)throw new IOException("Journal over bound");
        try(FileChannel out=FileChannel.open(temp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes=ByteBuffer.wrap(data);while(bytes.hasRemaining())out.write(bytes);out.force(true);
        }
        // Recheck the old journal without mistaking our own freshly flushed tmp for recovery.
        checkLeaf(file);String prior;
        try(FileChannel in=Files.exists(file,LinkOption.NOFOLLOW_LINKS)?FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS):null) {
            if(in==null)prior=null;else {
                ByteBuffer buf=ByteBuffer.allocate(bound+1);while(buf.hasRemaining()&&in.read(buf)>=0){}
                if(buf.position()>bound)throw new IOException("Journal changed during persistence");
                byte[] old=new byte[buf.position()];buf.flip();buf.get(old);prior=new String(old,StandardCharsets.UTF_8);
            }
        }
        if(!java.util.Objects.equals(expected,prior))throw new IOException("Daily journal raced during persistence; preserve tmp");
        // Never fall back to non-atomic replacement or clean up an interrupted tmp.
        Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        expected=new String(data,StandardCharsets.UTF_8);
    }
    private void check()throws IOException{if(!channel.isOpen()||!lock.isValid())throw new IOException("Live exclusive runner lock required");checkAncestors(root);}
    private static void checkAncestors(Path p)throws IOException{for(Path q=p;q!=null;q=q.getParent())if(Files.exists(q,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(q)||!Files.isDirectory(q,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Runner ancestor not regular directory");}
    private static void checkLeaf(Path p)throws IOException{if(Files.exists(p,LinkOption.NOFOLLOW_LINKS) && (Files.isSymbolicLink(p)||!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Runner leaf not regular file");}
    public final void close()throws IOException{try{lock.release();}finally{channel.close();}}
}

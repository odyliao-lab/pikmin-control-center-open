package dev.ody.pikmincontrol;

import android.content.Context;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileDescriptor;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;

/** MuMu Android journal adapter. Existing atomic/checksummed/exclusive store
 * remains authoritative; directory durability is an additional before-effect
 * barrier, not proof that any effect was admitted or completed. */
final class EmulatorAndroidDailyStore implements EmulatorDailyRunner.Store,AutoCloseable {
    interface DirectorySync { void sync(Path directory)throws Exception; }
    private final EmulatorDailyFileStore journal;
    private final Path root;
    private final DirectorySync sync;
    private boolean uncertain,closed;

    static EmulatorAndroidDailyStore open(Context context)throws Exception {
        Path files=files(context);
        return new EmulatorAndroidDailyStore(files.resolve("daily-runner"),EmulatorAndroidDailyStore::syncDirectory);
    }
    /** Resolve only the OS-provided Context anchor, never a task/leaf path.
     * MuMu's App mount namespace aliases /data/user/0 to /data/data. The
     * canonical anchor lets the existing descendant NOFOLLOW gates remain
     * strict; it does not authorize following links inside private App data. */
    static Path files(Context context)throws Exception {
        EmulatorRuntime.require();
        if(context==null || !"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()) ||
                !context.getPackageName().equals(context.getApplicationInfo().packageName) ||
                android.os.Process.myUid()!=context.getApplicationInfo().uid)
            throw new IllegalArgumentException("Isolated MuMu App UID context required");
        return files(context.getFilesDir().toPath(),java.nio.file.Paths.get(context.getApplicationInfo().dataDir),
                context.getApplicationInfo().uid);
    }
    /** Filesystem-only test seam; no runtime/identity/work authority. */
    static Path files(Path files,Path data,int uid)throws Exception {
        if(files==null || data==null || uid<0)throw new IllegalArgumentException("Private Context anchor required");
        files=files.toAbsolutePath().normalize();data=data.toAbsolutePath().normalize();
        if(!files.equals(data.resolve("files")) || data.getFileName()==null ||
                !"dev.ody.pikmincontrol.mumu".equals(data.getFileName().toString()))anchorBad();
        // Only root/system-owned OS ancestors may contain aliases. Android's
        // /data is system:system 0771; group write is trusted only for these
        // platform IDs, never an App/shared group. Symbolic aliases require root.
        // Package and files themselves must be real directories owned by App.
        for(Path p=data.getParent();p!=null;p=p.getParent())platformAncestor(p,true);
        BasicFileAttributes beforeData=privateAnchor(data,uid,true),beforeFiles=privateAnchor(files,uid,false);
        Path realData=data.toRealPath(),realFiles=files.toRealPath();
        if(!realFiles.equals(realData.resolve("files")) ||
                !realData.getFileName().equals(data.getFileName()))anchorBad();
        for(Path p=realFiles;p!=null;p=p.getParent())
            if(Files.isSymbolicLink(p)||!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))anchorBad();
        for(Path p=realData.getParent();p!=null;p=p.getParent())platformAncestor(p,false);
        BasicFileAttributes afterData=privateAnchor(data,uid,true),afterFiles=privateAnchor(files,uid,false);
        if(beforeData.fileKey()==null || beforeFiles.fileKey()==null ||
                !beforeData.fileKey().equals(afterData.fileKey()) || !beforeFiles.fileKey().equals(afterFiles.fileKey()) ||
                !afterData.fileKey().equals(privateAnchor(realData,uid,true).fileKey()) ||
                !afterFiles.fileKey().equals(privateAnchor(realFiles,uid,false).fileKey()) ||
                !realData.equals(data.toRealPath()) || !realFiles.equals(files.toRealPath()))anchorBad();
        return realFiles;
    }
    private static void platformAncestor(Path p,boolean alias)throws Exception {
        int mode=((Number)Files.getAttribute(p,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
        int owner=((Number)Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue();
        int group=((Number)Files.getAttribute(p,"unix:gid",LinkOption.NOFOLLOW_LINKS)).intValue();
        if(Files.isSymbolicLink(p)?(!alias||owner!=0):
                (!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS) || (owner!=0&&owner!=1000) ||
                (mode&0002)!=0 || ((mode&0020)!=0&&group!=0&&group!=1000)))anchorBad();
    }
    private static BasicFileAttributes privateAnchor(Path path,int uid,boolean data)throws Exception {
        BasicFileAttributes a=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        int mode=((Number)Files.getAttribute(path,"unix:mode",LinkOption.NOFOLLOW_LINKS)).intValue();
        if(!a.isDirectory() || a.isSymbolicLink() ||
                ((Number)Files.getAttribute(path,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=uid ||
                (data?(mode&0777)!=0700:(mode&0002)!=0))anchorBad();
        return a;
    }
    private static void anchorBad(){throw new IllegalStateException("Trusted App-private directory anchor required");}
    static void syncDirectory(Path directory)throws Exception {
            // O_DIRECTORY is not exposed by Android's public SDK. Open with
            // NOFOLLOW and verify the held descriptor is a directory BEFORE
            // fsync; never substitute a regular-file flush for this barrier.
            FileDescriptor fd=Os.open(directory.toString(),OsConstants.O_RDONLY|
                    OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC,0);
            try {
                if(!OsConstants.S_ISDIR(Os.fstat(fd).st_mode))
                    throw new IllegalStateException("Directory descriptor required for journal durability");
                Os.fsync(fd);
            }finally {Os.close(fd);}
    }
    /** Injection is only a filesystem test seam, not a ready/work authority API. */
    EmulatorAndroidDailyStore(Path root,DirectorySync sync)throws Exception {
        if(root==null || sync==null)throw new IllegalArgumentException("Complete persistence ports required");
        this.root=root.toAbsolutePath().normalize();this.sync=sync;
        journal=new EmulatorDailyFileStore(this.root);
        try {
            // FileJournal validates ancestors/leaves and owns the exclusive
            // cross-process lock. Persist the newly created namespace/lock
            // before permitting a tick, including the parent's directory entry.
            sync.sync(this.root.getParent());sync.sync(this.root);
        }catch(Exception failure){
            uncertain=true;
            try{journal.close();}catch(Exception close){failure.addSuppressed(close);}
            closed=true;throw failure;
        }
    }
    @Override public EmulatorDailyRunner.State read()throws Exception {
        require();return journal.read();
    }
    @Override public void save(EmulatorDailyRunner.State state)throws Exception {
        require();
        try {
            journal.save(state);sync.sync(root);
            // Preserve the original runner's independent readback requirement.
            // A sync error even AFTER a visible rename stays uncertain locally.
        }catch(Exception unknown){uncertain=true;throw unknown;}
    }
    private void require(){
        if(closed || uncertain)throw new IllegalStateException("Journal durability unknown/closed; preserve bytes, no effect");
    }
    @Override public void close()throws Exception {
        if(!closed){closed=true;journal.close();}
    }
}

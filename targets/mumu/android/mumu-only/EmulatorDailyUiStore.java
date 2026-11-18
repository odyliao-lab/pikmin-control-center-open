package dev.ody.pikmincontrol;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Separate private UI journal: never opens runner, receipt, GPS or native ledgers. */
final class EmulatorDailyUiStore implements EmulatorDailyUiRecovery.Store,AutoCloseable {
    interface Sync {void apply(Path path)throws Exception;}
    interface Protection {void mode(Path path,boolean directory)throws Exception;void check(Path path,boolean directory)throws Exception;}
    private static final Protection POSIX=new Protection(){public void mode(Path p,boolean d)throws Exception{posixMode(p,d);}public void check(Path p,boolean d)throws Exception{posixCheck(p,d);}};
    private final Path root;private final Sync sync;private final String identity;
    private final Protection protection;
    private final FileChannel channel;private final FileLock lock;private boolean diagnosticSaved;
    private EmulatorDailyUiStore(Path root,String identity,Sync sync,Protection protection,boolean existing)throws Exception{
        if(identity==null||!identity.matches("[a-f0-9]{64}")||sync==null)throw new IllegalArgumentException();
        this.root=root;this.identity=identity;this.sync=sync;this.protection=protection;
        if(!existing){Files.createDirectory(root);protection.mode(root,true);}protection.check(root,true);
        Path leaf=root.resolve("worker.lock");if(existing)protection.check(leaf,false);
        channel=FileChannel.open(leaf,existing?StandardOpenOption.WRITE:StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;try{acquired=channel.tryLock();if(acquired==null)throw new IOException("One UI actor only");}catch(Exception e){channel.close();throw e;}lock=acquired;
        try{protection.mode(leaf,false);sync.apply(root);pending();}catch(Exception e){close();throw e;}
    }
    static EmulatorDailyUiStore open(Path root,String identity,Sync sync)throws Exception{
        return open(root,identity,sync,POSIX);
    }
    static EmulatorDailyUiStore open(Path root,String identity,Sync sync,Protection protection)throws Exception{
        return new EmulatorDailyUiStore(root,identity,sync,protection,Files.exists(root,LinkOption.NOFOLLOW_LINKS));
    }
    public String pending()throws Exception{
        String pending=null;
        for(Path leaf:intents()){
            String id=leaf.getFileName().toString().substring(7,43);String[] f=fields(leaf);
            if(f.length!=7||!f[0].equals("ui-intent-v1")||!f[1].equals(identity)||!f[2].equals(id)||!f[6].equals("UI-only-NOT-game-success"))throw new IOException("UI intent requires review");
            if(!EmulatorDailyUiMatcher.Scene.valueOf(f[3]).clickable()||Long.parseLong(f[4])<=0||!f[5].matches("[a-f0-9]{64}"))throw new IOException("UI intent malformed");
            Path reply=root.resolve("result-"+id+".tsv");
            if(!Files.exists(reply,LinkOption.NOFOLLOW_LINKS)){if(pending!=null)throw new IOException("Multiple unknown UI intents");pending=id;}
            else {String[] r=fields(reply);if(r.length!=6||!r[0].equals("ui-result-v1")||!r[1].equals(identity)||!r[2].equals(id)||!(r[3].equals("MAP")||r[3].equals("TRANSITION"))||!r[4].matches("[a-f0-9]{64}")||!r[5].equals("NOT-game-success"))throw new IOException("UI result requires review");}
        }
        return pending;
    }
    public EmulatorDailyUiMatcher.Scene pendingScene(String id)throws Exception{
        if(id==null||!id.equals(pending()))throw new IOException("Exact pending UI intent required");
        return EmulatorDailyUiMatcher.Scene.valueOf(fields(root.resolve("intent-"+id+".tsv"))[3]);
    }
    public int count()throws Exception{return intents().size();}
    public String begin(EmulatorDailyUiRecovery.Frame frame)throws Exception{
        if(Files.exists(root.resolve("owner-ended.tsv"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Retired UI owner cannot issue input");
        if(pending()!=null||count()>=128||!frame.scene.clickable())throw new IOException("UI input not admitted");
        String id=UUID.randomUUID().toString();
        write(root.resolve("intent-"+id+".tsv"),"ui-intent-v1\t"+identity+"\t"+id+"\t"+frame.scene+"\t"+frame.clock+"\t"+sha(frame.raw)+"\tUI-only-NOT-game-success\n");
        return id; // raw diagnostic saved after input; never spend its3s freshness budget on8MiB fsync
    }
    public void confirmed(String id,EmulatorDailyUiRecovery.Frame after,boolean map)throws Exception{
        if(Files.exists(root.resolve("owner-ended.tsv"),LinkOption.NOFOLLOW_LINKS))throw new IOException("Retired UI results remain unknown");
        if(id==null||!id.equals(pending())||map!=(after.scene==EmulatorDailyUiMatcher.Scene.MAP)||(!map&&!after.scene.clickable()))throw new IOException("Exact UI observation required");
        write(root.resolve("result-"+id+".tsv"),"ui-result-v1\t"+identity+"\t"+id+"\t"+(map?"MAP":"TRANSITION")+"\t"+sha(after.raw)+"\tNOT-game-success\n");
    }
    void retireOnClear(String next,EmulatorDailyUiRecovery.Frame frame)throws Exception{
        if(next==null||!next.matches("[a-f0-9]{64}")||next.equals(identity)||frame.clock<=0||frame.raw.length!=8294416||
           !EmulatorDailyUiRecovery.ownerChangeAllowed(true,true,true,frame.scene))throw new IOException("Clear new UI owner required");
        String held=pending();Path leaf=root.resolve("owner-ended.tsv");
        if(Files.exists(leaf,LinkOption.NOFOLLOW_LINKS)){
            String[] f=fields(leaf);
            if(!f[2].equals(next)||!f[3].equals(held==null?"-":held))throw new IOException("Different UI retirement retained");
            return;
        }
        write(leaf,"ui-owner-ended-v1\t"+identity+"\t"+next+"\t"+(held==null?"-":held)+"\t"+frame.scene+"\t"+frame.clock+"\t"+sha(frame.raw)+"\tUNKNOWN-retained-NOT-game-success\n");
        pending(); // Full checksum/namespace validation; never create a result for held.
    }
    public void diagnostic(EmulatorDailyUiRecovery.Frame frame)throws Exception{
        if(diagnosticSaved)return;Path leaf=root.resolve("first-unrecognized-or-action.raw");
        if(Files.exists(leaf,LinkOption.NOFOLLOW_LINKS)){protection.check(leaf,false);diagnosticSaved=true;return;}
        if(frame.raw.length!=8294416)throw new IOException("Complete private UI frame required");
        put(leaf,frame.raw);diagnosticSaved=true; // bounded one raw frame per game incarnation, no deletion/upload
    }
    private List<Path> intents()throws Exception{
        protection.check(root,true);List<Path> out=new ArrayList<>();int entries=0;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(root)){
            for(Path p:files){if(++entries>260)throw new IOException("UI journal bounded; review required");String n=p.getFileName().toString();
                protection.check(p,false);
                if(n.matches("intent-[a-f0-9-]{36}\\.tsv")){UUID.fromString(n.substring(7,43));out.add(p);}
                else if(n.matches("result-[a-f0-9-]{36}\\.tsv")){if(!Files.isRegularFile(root.resolve("intent-"+n.substring(7)),LinkOption.NOFOLLOW_LINKS))throw new IOException("Orphan UI result");}
                else if(n.equals("first-unrecognized-or-action.raw")){if(Files.size(p)!=8294416)throw new IOException("Partial UI image retained; review required");}
                else if(n.equals("owner-ended.tsv")){
                    String[] f=fields(p);
                    if(f.length!=8||!f[0].equals("ui-owner-ended-v1")||!f[1].equals(identity)||!f[2].matches("[a-f0-9]{64}")||f[2].equals(identity)||
                       !(f[3].equals("-")||f[3].matches("[a-f0-9-]{36}")&&Files.isRegularFile(root.resolve("intent-"+f[3]+".tsv"),LinkOption.NOFOLLOW_LINKS))||
                       !EmulatorDailyUiRecovery.ownerChangeAllowed(true,true,true,EmulatorDailyUiMatcher.Scene.valueOf(f[4]))||Long.parseLong(f[5])<=0||
                       !f[6].matches("[a-f0-9]{64}")||!f[7].equals("UNKNOWN-retained-NOT-game-success"))throw new IOException("UI retirement requires review");
                }
                else if(!n.equals("worker.lock"))throw new IOException("Unknown UI journal leaf");
            }
        }if(out.size()>128)throw new IOException("UI budget exhausted");return out;
    }
    private String[] fields(Path leaf)throws Exception{
        protection.check(leaf,false);if(Files.size(leaf)>2048)throw new IOException("UI record bound");String s=new String(Files.readAllBytes(leaf),StandardCharsets.UTF_8);String[] r=s.split("\n",-1);
        if(r.length!=3||!r[2].isEmpty()||!r[1].equals("sha256\t"+sha((r[0]+"\n").getBytes(StandardCharsets.UTF_8))))throw new IOException("UI record checksum");return r[0].split("\t",-1);
    }
    private void write(Path leaf,String body)throws Exception{put(leaf,(body+"sha256\t"+sha(body.getBytes(StandardCharsets.UTF_8))+"\n").getBytes(StandardCharsets.UTF_8));}
    private void put(Path leaf,byte[] bytes)throws Exception{
        try(FileChannel out=FileChannel.open(leaf,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){protection.mode(leaf,false);ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())out.write(b);out.force(true);}sync.apply(root);
    }
    private static void posixMode(Path p,boolean dir)throws Exception{Files.setPosixFilePermissions(p,PosixFilePermissions.fromString(dir?"rwx------":"rw-------"));}
    private static void posixCheck(Path p,boolean dir)throws Exception{
        if(Files.isSymbolicLink(p)||(dir?!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))||
            !Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(dir?"rwx------":"rw-------"))||
            !dir&&((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)throw new IOException("Private single-link UI storage required");
    }
    static String sha(byte[] bytes){try{byte[] h=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder b=new StringBuilder();for(byte v:h)b.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    /** Narrow retention: old diagnostic raw only; all journals and receipts
     * remain. Default caller is dry-run. An exclusive original actor lock,
     * complete journal validation and a verified gzip precede every deletion. */
    static int retain(Path root,String current,long now,boolean remove,Sync sync,java.util.concurrent.Callable<Boolean> guard)throws Exception{
        return retain(root,current,now,remove,sync,guard,POSIX);
    }
    static boolean retentionAge(long now,long modified){return modified>0&&now>=modified&&now-modified>=7L*86400000;}
    static byte[] retentionRaw(Path path)throws IOException{
        try(FileChannel file=FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            if(file.size()!=8294416)throw new IOException("Raw size bound");
            ByteBuffer bytes=ByteBuffer.allocate(8294417);
            while(bytes.hasRemaining()&&file.read(bytes)!=-1){}
            if(bytes.position()!=8294416||file.size()!=8294416)throw new IOException("Raw changed or incomplete");
            return Arrays.copyOf(bytes.array(),8294416);
        }
    }
    static int retain(Path root,String current,long now,boolean remove,Sync sync,java.util.concurrent.Callable<Boolean> guard,Protection protection)throws Exception{
        if(current==null||!current.matches("[a-f0-9]{64}")||!guard.call())throw new IOException("Retention identity/safety required");
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return 0;
        protection.check(root,true);Path parent=root.getParent();protection.check(parent,true);
        Object owner=Files.getOwner(parent,LinkOption.NOFOLLOW_LINKS);
        if(!owner.equals(Files.getOwner(root,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Retention owner differs");
        Path archive=parent.resolve("daily-ui-archive");long begun=System.nanoTime();int visited=0,count=0;
        try(DirectoryStream<Path> dirs=Files.newDirectoryStream(root)){
            for(Path dir:dirs){
                if(++visited>200||count>=12||System.nanoTime()-begun>120000000000L)break;
                String id=dir.getFileName().toString();if(id.equals(current))continue;
                if(!id.matches("[a-f0-9]{64}"))throw new IOException("Unknown retention namespace");
                protection.check(dir,true);if(!owner.equals(Files.getOwner(dir,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Retention owner differs");
                Path raw=dir.resolve("first-unrecognized-or-action.raw");
                if(!Files.exists(raw,LinkOption.NOFOLLOW_LINKS))continue;
                protection.check(raw,false);
                if(!owner.equals(Files.getOwner(raw,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Retention owner differs");
                long modified=Files.getLastModifiedTime(raw,LinkOption.NOFOLLOW_LINKS).toMillis();
                if(!retentionAge(now,modified))continue;
                // Busy actors are protected. Other I/O/format errors stop the run.
                try(EmulatorDailyUiStore journal=open(dir,id,sync,protection)){
                    if(journal.pending()!=null)continue;
                    try(DirectoryStream<Path> records=Files.newDirectoryStream(dir)){for(Path record:records)if(!owner.equals(Files.getOwner(record,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Journal owner differs");}
                    if(!guard.call())throw new IOException("Retention safety changed");
                    if(!remove){count++;continue;}
                    byte[] original=retentionRaw(raw);
                    if(Files.getFileStore(parent).getUsableSpace()<32L*1024*1024)throw new IOException("Backup space required; originals retained");
                    String hash=sha(original);
                    if(!Files.exists(archive,LinkOption.NOFOLLOW_LINKS)){Files.createDirectory(archive);protection.mode(archive,true);sync.apply(parent);}
                    protection.check(archive,true);if(!owner.equals(Files.getOwner(archive,LinkOption.NOFOLLOW_LINKS)))throw new IOException("Archive owner differs");
                    long size=0;int leaves=0;
                    try(DirectoryStream<Path> backups=Files.newDirectoryStream(archive)){
                        for(Path backup:backups){if(++leaves>1000)throw new IOException("Archive review required");protection.check(backup,false);size+=Files.size(backup);if(size>128L*1024*1024)throw new IOException("Archive budget reached; originals retained");}
                    }
                    Path backup=archive.resolve(id+"."+hash+".raw.gz");
                    if(!Files.exists(backup,LinkOption.NOFOLLOW_LINKS)){
                        if(size+original.length+65536>128L*1024*1024)throw new IOException("Archive reserve required");
                        try(FileChannel file=FileChannel.open(backup,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                            protection.mode(backup,false);java.util.zip.GZIPOutputStream zip=new java.util.zip.GZIPOutputStream(Channels.newOutputStream(file));
                            zip.write(original);zip.finish();zip.flush();file.force(true);zip.close();
                        }sync.apply(archive);
                    }
                    protection.check(backup,false);
                    if(!owner.equals(Files.getOwner(backup,LinkOption.NOFOLLOW_LINKS))||Files.size(backup)>original.length+65536)throw new IOException("Archive invalid");
                    ByteArrayOutputStream restored=new ByteArrayOutputStream(original.length);
                    try(InputStream in=new java.util.zip.GZIPInputStream(Files.newInputStream(backup,LinkOption.NOFOLLOW_LINKS))){
                        byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1){if(restored.size()+n>original.length)throw new IOException("Archive expansion bound");restored.write(block,0,n);}
                    }
                    if(restored.size()!=original.length||!sha(restored.toByteArray()).equals(hash))throw new IOException("Archive SHA differs");
                    protection.check(raw,false);
                    if(!guard.call()||journal.pending()!=null||!owner.equals(Files.getOwner(raw,LinkOption.NOFOLLOW_LINKS))||Files.getLastModifiedTime(raw,LinkOption.NOFOLLOW_LINKS).toMillis()!=modified||Files.size(raw)!=original.length||!sha(retentionRaw(raw)).equals(hash))throw new IOException("Original changed; retained");
                    Files.delete(raw);sync.apply(dir);count++;
                }catch(OverlappingFileLockException busy){/* same-process live actor retained */}
            }
        }
        return count;
    }
    public void close()throws Exception{try{lock.release();}finally{channel.close();}}
}

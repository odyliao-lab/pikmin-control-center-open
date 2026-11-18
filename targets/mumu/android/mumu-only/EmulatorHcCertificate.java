package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** Read originals, never recreate a certificate or write an approval flag.
 * Only v3 is independent of route geometry. Device, boot, process, account,
 * session and core remain exact. Current live identity is checked by caller.
 * Reusing the known 269 certificate after an APK update is a build invariant:
 * BOTH builder and independent verifier compare the complete HC/counter class
 * inventory against the original 269 jar. No other prior APK is admitted.
 */
final class EmulatorHcCertificate {
    static final String ORIGIN_APK="44f95a83b271187c1ae576ce4849d8761465633202a4be46f2a863c4a065b258";
    static final int MAX_LEAVES=64;
    static final class Retained {
        final Path leaf;
        final EmulatorDailyAcceptanceEvidence evidence;
        Retained(Path leaf,EmulatorDailyAcceptanceEvidence evidence){this.leaf=leaf;this.evidence=evidence;}
    }
    static boolean compatible(EmulatorDailyAcceptanceEvidence evidence,EmulatorDailyRunner.Binding held){
        if(evidence==null||held==null||!evidence.automatic)return false;
        EmulatorDailyRunner.Binding old=evidence.binding;
        if(!evidence.encode().startsWith("v3\t"))return old.same(held);
        return old.device.equals(held.device)&&old.boot.equals(held.boot)&&old.pid==held.pid&&
            old.account.equals(held.account)&&old.sessionEpoch==held.sessionEpoch&&old.coreSha.equals(held.coreSha)&&
            (old.apkSha.equals(held.apkSha)||old.apkSha.equals(ORIGIN_APK));
    }
    static Retained read(Path root,EmulatorDailyRunner.Binding held,long now,int uid)throws Exception{
        if(root==null||held==null||uid<0)bad();
        root=root.toAbsolutePath().normalize();
        if(!root.getFileName().toString().equals("daily-acceptance")||root.getParent()==null||
            !root.getParent().getFileName().toString().equals("files"))bad();
        privateRoot(root,uid);
        Path exact=root.resolve(EmulatorDailyRunner.digest(held.encode())+".tsv");
        if(Files.exists(exact,LinkOption.NOFOLLOW_LINKS)){
            Retained found=readLeaf(root,exact,now,uid);
            if(!compatible(found.evidence,held))bad();
            return found;
        }
        Retained selected=null;int count=0;
        try(DirectoryStream<Path> leaves=Files.newDirectoryStream(root)){
            for(Path leaf:leaves){
                if(++count>MAX_LEAVES)bad();
                // All retained originals are validated; corrupt or foreign
                // entries are not silently skipped as a ready certificate.
                Retained found=readLeaf(root,leaf,now,uid);
                if(compatible(found.evidence,held)&&(selected==null||found.evidence.verifiedAt>selected.evidence.verifiedAt))selected=found;
            }
        }
        privateRoot(root,uid);
        if(selected==null)throw new IllegalStateException("No reusable original automatic HC certificate; do not rewrite steps");
        Retained finalRead=readLeaf(root,selected.leaf,now,uid);
        if(!selected.evidence.encode().equals(finalRead.evidence.encode())||!compatible(finalRead.evidence,held))bad();
        return finalRead;
    }
    static Retained readLeaf(Path root,Path leaf,long now,int uid)throws Exception{
        privateRoot(root,uid);privateLeaf(leaf,uid);
        BasicFileAttributes before=Files.readAttributes(leaf,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        byte[] bytes=bounded(leaf);String raw=new String(bytes,StandardCharsets.UTF_8);
        if(!Arrays.equals(bytes,raw.getBytes(StandardCharsets.UTF_8)))bad();
        String[] header=raw.split("\n",2)[0].split("\t",-1);
        if(header.length!=6)bad();
        EmulatorDailyRunner.Binding original=EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(header[1]));
        // Run the unchanged FULL semantic consumer against its original binding.
        EmulatorDailyAcceptanceEvidence evidence=EmulatorDailyAcceptanceEvidence.decode(raw,original,now);
        if(!leaf.getFileName().toString().equals(EmulatorDailyRunner.digest(original.encode())+".tsv"))bad();
        BasicFileAttributes after=Files.readAttributes(leaf,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!java.util.Objects.equals(before.fileKey(),after.fileKey())||before.size()!=after.size()||
            !before.lastModifiedTime().equals(after.lastModifiedTime())||!Arrays.equals(bytes,bounded(leaf)))bad();
        privateLeaf(leaf,uid);privateRoot(root,uid);
        return new Retained(leaf,evidence);
    }
    private static byte[] bounded(Path leaf)throws Exception{
        try(FileChannel in=FileChannel.open(leaf,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            long size=in.size();if(size<=0||size>EmulatorDailyAcceptanceEvidence.MAX_BYTES)bad();
            ByteBuffer bytes=ByteBuffer.allocate((int)size);
            while(bytes.hasRemaining())if(in.read(bytes)<0)bad();
            if(in.read(ByteBuffer.allocate(1))!=-1)bad();
            return bytes.array();
        }
    }
    private static void privateRoot(Path root,int uid)throws Exception{
        for(Path path=root;path!=null;path=path.getParent())if(Files.isSymbolicLink(path)||!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))bad();
        if(((Number)Files.getAttribute(root,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=uid||
            !Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------")))bad();
    }
    private static void privateLeaf(Path leaf,int uid)throws Exception{
        if(Files.isSymbolicLink(leaf)||!Files.isRegularFile(leaf,LinkOption.NOFOLLOW_LINKS)||
            ((Number)Files.getAttribute(leaf,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=uid||
            ((Number)Files.getAttribute(leaf,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1||
            !Files.getPosixFilePermissions(leaf,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))bad();
    }
    private static void bad(){throw new IllegalStateException("Original private automatic HC certificate required");}
}

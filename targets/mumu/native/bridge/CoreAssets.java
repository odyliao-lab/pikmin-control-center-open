package dev.ody.pccbridge;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/** Off-Update Root observer helper. Caller owns APK signature/byte/FD checks.
 * This extracts assets, never authenticates a caller or grants work authority. */
public final class CoreAssets {
    private static final int MAX=12*1024*1024;
    private static final Set<String> CORE=new HashSet<String>(java.util.Arrays.asList(
        "customize.sh","LICENSE.reference","module.prop","payload-arm64.so","service.sh","zygisk/x86_64.so"));
    private CoreAssets() {}
    private static void deadline(long end)throws Exception {
        if(System.nanoTime()-end>=0)throw new IllegalArgumentException("Asset observation expired");
    }
    private static byte[] read(InputStream input,int limit,long end)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[16384];
        for(int count;(count=input.read(buffer))!=-1;){
            deadline(end);if(count==0||count>limit-bytes.size())throw new IllegalArgumentException("Asset bound");
            bytes.write(buffer,0,count);
        }
        deadline(end);return bytes.toByteArray();
    }
    private static String hash(byte[] bytes)throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder text=new StringBuilder(64);
        for(byte b:digest){text.append(Character.forDigit((b>>>4)&15,16));text.append(Character.forDigit(b&15,16));}return text.toString();
    }
    private static long number(String text,long max)throws Exception {
        if(!text.matches("[1-9][0-9]{0,12}"))throw new IllegalArgumentException("Asset number");
        long value=Long.parseLong(text);if(value>max)throw new IllegalArgumentException("Asset range");return value;
    }
    // Returns version, core code, ZIP digest, ARM digest, x64 digest. The
    // independently observed installed APK code must match core.properties.
    public static String[] observe(String path,long apkCode)throws Exception {
        if(path==null||!path.matches("/proc/self/fd/[0-9]+")||apkCode<=0||apkCode>999999999)
            throw new IllegalArgumentException("Protected APK descriptor required");
        final long end=System.nanoTime()+2_000_000_000L;
        byte[] metadata=null,archive=null;Set<String> names=new HashSet<String>();
        try(ZipFile apk=new ZipFile(path)){
            Enumeration<? extends ZipEntry> entries=apk.entries();int count=0;
            while(entries.hasMoreElements()){
                deadline(end);ZipEntry entry=entries.nextElement();String name=entry.getName();
                if(++count>64||!names.add(name)||entry.isDirectory()||name.indexOf('\\')>=0||name.startsWith("/")||
                   name.contains("../")||name.contains("//")||entry.getSize()<0||entry.getSize()>MAX)
                    throw new IllegalArgumentException("APK entry shape");
                if(name.equals("assets/core.properties")||name.equals("assets/pikmin-core.zip")){
                    int limit=name.equals("assets/core.properties")?1024:MAX;
                    byte[] data;try(InputStream input=apk.getInputStream(entry)){data=read(input,limit,end);}
                    if(data.length!=entry.getSize())throw new IllegalArgumentException("APK asset size");
                    java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(data);
                    if(crc.getValue()!=entry.getCrc())throw new IllegalArgumentException("APK asset CRC");
                    if(name.equals("assets/core.properties"))metadata=data;else archive=data;
                }
            }
        }
        if(metadata==null||archive==null)throw new IllegalArgumentException("Missing core assets");
        for(byte b:metadata)if((b<32&&b!=10)||b>126)throw new IllegalArgumentException("Metadata encoding");
        String[] lines=new String(metadata,StandardCharsets.US_ASCII).split("\n",-1);
        if(lines.length!=7||!lines[0].equals("schema=2")||!lines[1].matches("version=[0-9]+\\.[0-9]+\\.[0-9]+")||
           !lines[2].startsWith("code=")||!lines[3].startsWith("app=")||!lines[4].startsWith("size=")||
           !lines[5].matches("sha256=[0-9a-f]{64}")||!lines[6].isEmpty())throw new IllegalArgumentException("Core metadata shape");
        long code=number(lines[2].substring(5),999999999);
        if(number(lines[3].substring(4),999999999)!=apkCode||number(lines[4].substring(5),MAX)!=archive.length)
            throw new IllegalArgumentException("Core APK binding");
        String zipSha=hash(archive);if(!zipSha.equals(lines[5].substring(7)))throw new IllegalArgumentException("Core ZIP digest");
        Set<String> coreNames=new HashSet<String>();String arm=null,loader=null;long total=0;
        try(ZipInputStream core=new ZipInputStream(new ByteArrayInputStream(archive))){
            for(ZipEntry entry;(entry=core.getNextEntry())!=null;){
                deadline(end);String name=entry.getName();
                if(entry.isDirectory()||!CORE.contains(name)||!coreNames.add(name))throw new IllegalArgumentException("Core entry shape");
                byte[] data=read(core,MAX,end);total+=data.length;if(total>2L*MAX||data.length==0)throw new IllegalArgumentException("Core bytes bound");
                if(name.equals("payload-arm64.so")||name.equals("zygisk/x86_64.so")){
                    int machine=name.equals("payload-arm64.so")?183:62;
                    if(data.length<64||data[0]!=127||data[1]!=69||data[2]!=76||data[3]!=70||data[4]!=2||data[5]!=1||
                       (data[18]&255)!=machine||data[19]!=0)throw new IllegalArgumentException("Core ELF ABI");
                    if(machine==183)arm=hash(data);else loader=hash(data);
                }
                core.closeEntry();
            }
        }
        if(!coreNames.equals(CORE)||arm==null||loader==null)throw new IllegalArgumentException("Incomplete core");
        deadline(end);return new String[]{lines[1].substring(8),Long.toString(code),zipSha,arm,loader};
    }
}

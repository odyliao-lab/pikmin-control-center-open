package dev.ody.pikmincontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Exercises the production cache/ZIP validator; no Android/root/service execution. */
public final class BundledCoreCacheTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("check "+checks);}
    static final String A=String.join("",Collections.nCopies(64,"a")), B=String.join("",Collections.nCopies(64,"b"));
    static String[] expected(){return new String[]{A,B,"proof"};}
    static CorePair pair(String version,int code,int app,int size,String sha,boolean unified)throws Exception {
        return new CorePair("schema="+(unified?2:1)+"\nversion="+version+"\ncode="+code+"\napp="+app+"\nsize="+size+"\nsha256="+sha+"\n");
    }
    public static void main(String[] args)throws Exception {
        AtomicInteger loads=new AtomicInteger();String[] original=expected();
        String[] one=CoreUpdater.cachedExpected("clone",()->{loads.incrementAndGet();return original;});
        original[0]=B;one[1]=A;
        String[] two=CoreUpdater.cachedExpected("clone",()->{throw new AssertionError("hit reloaded");});
        check(two[0].equals(A)&&two[1].equals(B)&&loads.get()==1);
        for(int i=0;i<2;i++)try{CoreUpdater.cachedExpected("failed",()->{loads.incrementAndGet();throw new IOException("fixture");});throw new AssertionError();}catch(IOException expected){}
        check(loads.get()==3);
        for(int i=0;i<2;i++)try{CoreUpdater.cachedExpected("invalid",()->{loads.incrementAndGet();return new String[]{A};});throw new AssertionError();}catch(IOException expected){}
        check(loads.get()==5);
        CoreUpdater.cachedExpected("evict",()->expected());
        for(int i=0;i<4;i++)CoreUpdater.cachedExpected("fill"+i,()->expected());
        CoreUpdater.cachedExpected("evict",()->{loads.incrementAndGet();return expected();});check(loads.get()==6);

        ExecutorService workers=Executors.newFixedThreadPool(8);CountDownLatch go=new CountDownLatch(1);
        AtomicInteger parallelLoads=new AtomicInteger();List<Future<String[]>> futures=new ArrayList<>();
        try {
            for(int i=0;i<8;i++)futures.add(workers.submit(()->{go.await();return CoreUpdater.cachedExpected("parallel",()->{parallelLoads.incrementAndGet();return expected();});}));
            go.countDown();for(Future<String[]> f:futures)check(f.get(5,TimeUnit.SECONDS)[0].equals(A));
            check(parallelLoads.get()==1);
        } finally {workers.shutdownNow();}
        CorePair p=pair("1.2.3",90,100,50,A,true);
        Set<String> keys=new HashSet<>();keys.add(CoreUpdater.expectedKey("apk1","asset",p));
        keys.add(CoreUpdater.expectedKey("apk2","asset",p));keys.add(CoreUpdater.expectedKey("apk1","other",p));
        for(CorePair variant:new CorePair[]{pair("1.2.4",90,100,50,A,true),pair("1.2.3",91,100,50,A,true),
            pair("1.2.3",90,101,50,A,true),pair("1.2.3",90,100,51,A,true),pair("1.2.3",90,100,50,B,true),pair("1.2.3",90,100,50,A,false)})
            keys.add(CoreUpdater.expectedKey("apk1","asset",variant));
        check(keys.size()==9);

        boolean mumu=CoreUpdater.BASE.contains("emulator");
        String library=mumu?"zygisk/x86_64.so":"zygisk/arm64-v8a.so";
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String name:mumu?new String[]{library,"service.sh","payload-arm64.so"}:new String[]{library,"service.sh"}){
                zip.putNextEntry(new ZipEntry(name));zip.write(name.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        byte[] zip=bytes.toByteArray();CorePair zipPair=pair("1.2.3",90,100,zip.length,SetupBundle.sha(zip),true);
        String[] hashes=CoreUpdater.verifiedExpected(zipPair,zip);
        check(hashes[0].equals(SetupBundle.sha(library.getBytes(StandardCharsets.UTF_8))));
        check(hashes[1].equals(SetupBundle.sha("service.sh".getBytes(StandardCharsets.UTF_8))));
        if(mumu)check(hashes[2].contains("payload-arm64.so"));
        // Mutated private ZIPs are refused even after an immutable entry has been cached.
        CoreUpdater.cachedExpected("private-mutation",()->hashes);zip[zip.length-1]^=1;
        try{CoreUpdater.requireInstalledFiles(zipPair,zip);throw new AssertionError();}catch(IOException expected){check(expected.getMessage().contains("ZIP"));}
        for(String dir:new String[]{"android-app/src/dev/ody/pikmincontrol/","targets/mumu/android/override/"}) {
            String source=new String(Files.readAllBytes(Paths.get(dir+"CoreUpdater.java")),StandardCharsets.UTF_8);
            String require=source.substring(source.indexOf("static void requireFiles("),source.indexOf("static boolean samePair("));
            check(require.contains("bundledAsset(c,p)")&&require.contains("apk.lastUpdateTime")&&require.contains("sourceDir"));
            check(require.contains("cachedExpected(")&&require.contains("requireLiveInstalled(p,expected);"));
            String live=source.substring(source.indexOf("static void requireLiveInstalled("),source.indexOf("static Set<String> moduleEntries("));
            for(String guard:new String[]{"舊GPS模組停用檢查","STAGED","/disable","/remove","versionCode=","sha256sum","CoreModuleProof.paths(BASE)"})check(live.contains(guard));
            if(dir.startsWith("targets"))check(source.contains("return new String[]{so,service,CoreModuleProof.hashes(BASE,b)}"));
            String privateZip=source.substring(source.indexOf("static void requireInstalledFiles("),source.indexOf("static String[] verifiedExpected("));
            check(privateZip.contains("requireLiveInstalled(p,verifiedExpected(p,b))")&&!privateZip.contains("cachedExpected"));
        }
        System.out.println("PASS BundledCoreCache: "+checks+" checks; immutable cache only, no root/device calls");
    }
}

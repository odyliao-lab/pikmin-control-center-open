package dev.ody.pikmincontrol;

import java.io.*;
import java.security.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

public final class SetupBundleTest {
    static KeyPair key;static String manifest;static byte[] core,copy;static int count;
    interface Attempt {void run() throws Exception;}
    static void reject(Attempt a) throws Exception {boolean bad=false;try{a.run();}catch(Exception e){bad=true;}if(!bad)throw new AssertionError("expected rejection "+count);count++;}
    static byte[] zip(Map<String,byte[]> entries) throws Exception {ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return bytes.toByteArray();}
    static byte[] module(String id,String version) throws Exception {return module(id,version,"1.5.27".equals(version)?"95":("1.5.16".equals(version)?"84":"59"));}
    static byte[] module(String id,String version,String code) throws Exception {Map<String,byte[]> f=new LinkedHashMap<>();f.put("zygisk/",new byte[0]);f.put("module.prop",("id="+id+"\nversion="+version+"\nversionCode="+code+"\n").getBytes(StandardCharsets.UTF_8));f.put("zygisk/arm64-v8a.so",new byte[]{1,2,3});return zip(f);}
    static String withCore(byte[] c,String version,String code) throws Exception {
        return manifest.replace("native_version\t1.5.27","native_version\t"+version).replace("native_code\t95","native_code\t"+code)
                .replace(SetupBundle.sha(core),SetupBundle.sha(c)).replace("native_size\t"+core.length,"native_size\t"+c.length);
    }
    static byte[] pack(String m,byte[] c,byte[] gps) throws Exception {
        byte[] bytes=m.getBytes(StandardCharsets.UTF_8);Signature sig=Signature.getInstance("SHA256withRSA");sig.initSign(key.getPrivate());sig.update(bytes);
        Map<String,byte[]> files=new LinkedHashMap<>();files.put("manifest.tsv",bytes);files.put("manifest.sig",sig.sign());files.put("native.zip",c);files.put("copy.zip",gps);return zip(files);
    }
    public static void main(String[] args) throws Exception {
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);key=g.generateKeyPair();
        core=module("pikmin_nectar_rpc","1.5.27");copy=module("zygisk_pikmin_gps_copy","v152.0-r13");
        manifest="schema\t1\nsequence\t2\nissued\t1000\nexpires\t10000\ngame\t153.0\ngame_size\t254373256\nabi\tarm64-v8a\nsdk\t34\nnative_version\t1.5.27\nnative_code\t95\nnative_sha\t"+SetupBundle.sha(core)+"\nnative_size\t"+core.length+"\ncopy_version\tv152.0-r13\ncopy_sha\t"+SetupBundle.sha(copy)+"\ncopy_size\t"+copy.length+"\n";
        byte[] valid=pack(manifest,core,copy);
        if(SetupBundle.verify(valid,key.getPublic(),2000,1).sequence!=2)throw new AssertionError();count++;
        byte[] current=module("pikmin_nectar_rpc","1.5.30","98");
        if(!"1.5.30".equals(SetupBundle.verify(pack(withCore(current,"1.5.30","98"),current,copy),key.getPublic(),2000,0).meta.get("native_version")))throw new AssertionError();count++;
        reject(()->SetupBundle.verify(pack(withCore(current,"1.5.30","97"),current,copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.verify(valid,g.generateKeyPair().getPublic(),2000,0));
        reject(()->SetupBundle.verify(valid,key.getPublic(),999,0));reject(()->SetupBundle.verify(valid,key.getPublic(),10001,0));reject(()->SetupBundle.verify(valid,key.getPublic(),2000,3));
        if(SetupBundle.verify(valid,key.getPublic(),10001,2,false).sequence!=2)throw new AssertionError();count++;
        byte[] latest=module("pikmin_nectar_rpc","1.5.16");
        if(!"1.5.16".equals(SetupBundle.verify(pack(withCore(latest,"1.5.16","84"),latest,copy),key.getPublic(),2000,0).meta.get("native_version")))throw new AssertionError();count++;
        reject(()->SetupBundle.verify(pack(withCore(latest,"1.5.16","83"),latest,copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.verify(pack(withCore(core,"1.5.16","91"),core,copy),key.getPublic(),2000,0));
        byte[] future=module("pikmin_nectar_rpc","1.5.99","167");
        reject(()->SetupBundle.verify(pack(withCore(future,"1.5.99","167"),future,copy),key.getPublic(),2000,0));
        byte[] mismatch=module("pikmin_nectar_rpc","1.5.16","83");
        reject(()->SetupBundle.verify(pack(withCore(mismatch,"1.5.16","84"),mismatch,copy),key.getPublic(),2000,0));
        for(String[] r:new String[][]{{"schema\t1","schema\t2"},{"sequence\t2","sequence\t0"},{"game\t153.0","game\t152.0"},{"sdk\t34","sdk\t35"},{"abi\tarm64-v8a","abi\tx86"},{"game_size\t254373256","game_size\t1"},{"native_code\t95","native_code\t83"},{"copy_version\tv152.0-r13","copy_version\tv152.0-r12"},{"issued\t1000","issued\t0"},{"expires\t10000","expires\t1000"},{"expires\t10000","expires\t999999999999"}}){reject(()->SetupBundle.verify(pack(manifest.replace(r[0],r[1]),core,copy),key.getPublic(),2000,0));}
        reject(()->SetupBundle.verify(pack(manifest+"schema\t1\n",core,copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.verify(pack(manifest+"command\treboot\n",core,copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.verify(pack(manifest,new byte[]{1},copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.verify(new byte[SetupBundle.MAX+1],key.getPublic(),2000,0));
        for(String name:Arrays.asList("../native.zip","/native.zip","native\\zip","extra.zip","manifest.tsv/")){
            Map<String,byte[]> bad=new HashMap<>();bad.put(name,new byte[0]);reject(()->SetupBundle.verify(zip(bad),key.getPublic(),2000,0));
        }
        Map<String,byte[]> bomb=new HashMap<>();bomb.put("native.zip",new byte[SetupBundle.MAX+1]);reject(()->SetupBundle.verify(zip(bomb),key.getPublic(),2000,0));
        Map<String,byte[]> contents=new HashMap<>();contents.put("evil.sh",new byte[]{1});byte[] unknown=zip(contents);
        reject(()->SetupBundle.verify(pack(manifest.replace(SetupBundle.sha(core),SetupBundle.sha(unknown)).replace("native_size\t"+core.length,"native_size\t"+unknown.length),unknown,copy),key.getPublic(),2000,0));
        reject(()->SetupBundle.read(new ByteArrayInputStream(new byte[11]),10));
        System.out.println("PASS SetupBundle: "+count+" real RSA, expiry, downgrade, ZIP and hash checks");
    }
}

package dev.ody.pikmincontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Signed offline component catalogue, no URLs or shell commands accepted. */
final class SetupBundle {
    static final int MAX=16*1024*1024;
    final Map<String,String> meta;
    final byte[] core,copy;
    final long sequence;
    private SetupBundle(Map<String,String> m,byte[] core,byte[] copy){meta=m;this.core=core;this.copy=copy;sequence=Long.parseLong(m.get("sequence"));}
    static byte[] read(InputStream in,int limit) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1){if(out.size()+n>limit)throw new IOException("部署包過大");out.write(b,0,n);}return out.toByteArray();
    }
    static String sha(byte[] bytes) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder s=new StringBuilder();
        for(byte b:hash)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static SetupBundle verify(byte[] bytes,PublicKey key,long now,long minimum) throws Exception {
        return verify(bytes,key,now,minimum,true);
    }
    // Expired bundles may only be used to inspect an already-started install, never install again.
    static SetupBundle verify(byte[] bytes,PublicKey key,long now,long minimum,boolean requireCurrent) throws Exception {
        if(bytes.length>MAX)throw new IOException("部署包過大");
        Map<String,byte[]> files=unzip(bytes,new HashSet<>(Arrays.asList("manifest.tsv","manifest.sig","native.zip","copy.zip")),MAX);
        if(files.size()!=4)throw new IOException("部署包缺少必要檔案");
        byte[] manifest=files.get("manifest.tsv"),signature=files.get("manifest.sig");
        if(manifest.length>8192||signature.length>1024)throw new IOException("清單或簽章過大");
        Signature verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(key);verifier.update(manifest);
        if(!verifier.verify(signature))throw new SecurityException("部署包簽章不符，未安裝");
        Map<String,String> m=new HashMap<>();
        for(String line:new String(manifest,StandardCharsets.UTF_8).split("\n")){
            String[] f=line.split("\t",-1);if(f.length!=2||!f[0].matches("[a-z_]+")||!f[1].matches("[A-Za-z0-9._-]{1,128}")||m.put(f[0],f[1])!=null)throw new IOException("清單格式錯誤");
        }
        Set<String> keys=new HashSet<>(Arrays.asList("schema","sequence","issued","expires","game","game_size","abi","sdk","native_version","native_code","native_sha","native_size","copy_version","copy_sha","copy_size"));
        if(!m.keySet().equals(keys)||!"1".equals(m.get("schema"))||!"153.0".equals(m.get("game"))
                ||!"254373256".equals(m.get("game_size"))||!"arm64-v8a".equals(m.get("abi"))||!"34".equals(m.get("sdk")))throw new IOException("部署包相容規格不支援");
        long seq=Long.parseLong(m.get("sequence")),issued=Long.parseLong(m.get("issued")),expires=Long.parseLong(m.get("expires"));
        if(seq<1||seq<minimum||issued<=0||(requireCurrent&&(now<issued||now>expires))||expires<=issued||expires-issued>180L*86400000)throw new IOException("部署包過期、回退或手機時間不正確");
        if(!supportedCore(m.get("native_version"),m.get("native_code"))||!"v152.0-r13".equals(m.get("copy_version")))throw new IOException("部署包元件尚未列入支援");
        for(String kind:Arrays.asList("native","copy")){
            byte[] zip=files.get(kind+".zip");
            if(zip.length!=Long.parseLong(m.get(kind+"_size"))||!sha(zip).equals(m.get(kind+"_sha")))throw new SecurityException("元件校驗失敗");
            inspectModule(zip,"native".equals(kind)?"pcc_emulator_bridge_canary":"zygisk_pikmin_gps_copy",m.get(kind+"_version"),"native".equals(kind)?m.get("native_code"):null);
        }
        return new SetupBundle(m,files.get("native.zip"),files.get("copy.zip"));
    }
    static Map<String,byte[]> unzip(byte[] data,Set<String> allowed,int maximum) throws Exception {
        Map<String,byte[]> out=new HashMap<>();int total=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry e;
            while((e=zip.getNextEntry())!=null){String name=e.getName();
                if(!allowed.contains(name)||out.containsKey(name))throw new IOException("部署包含未知、重複或不安全路徑");
                byte[] value=read(zip,maximum-total);total+=value.length;out.put(name,value);
                if(e.isDirectory()&&value.length!=0)throw new IOException("不安全目錄");
            }
        }
        if(out.isEmpty())throw new IOException("空白部署包");return out;
    }
    static boolean supportedCore(String version,String code) {
        return ("1.5.30".equals(version)&&"98".equals(code))||("1.5.29".equals(version)&&"97".equals(code))||("1.5.28".equals(version)&&"96".equals(code))||("1.5.27".equals(version)&&"95".equals(code))||("1.5.26".equals(version)&&"94".equals(code))||("1.5.25".equals(version)&&"93".equals(code))||("1.5.16".equals(version)&&"84".equals(code))||("1.4.25".equals(version)&&"59".equals(code))||("1.4.26".equals(version)&&"60".equals(code));
    }
    static void inspectModule(byte[] zip,String id,String version,String code) throws Exception {
        Set<String> allowed=new HashSet<>(Arrays.asList("payload-arm64.so","LICENSE.reference","module.prop","service.sh","zygisk/x86_64.so","META-INF/com/google/android/update-binary","META-INF/com/google/android/updater-script",
                "META-INF/","META-INF/com/","META-INF/com/google/","META-INF/com/google/android/","zygisk/"));
        if("pcc_emulator_bridge_canary".equals(id))allowed.add("customize.sh");
        Map<String,byte[]> f=unzip(zip,allowed,12*1024*1024);
        if(!f.containsKey("module.prop")||!f.containsKey("zygisk/x86_64.so"))throw new IOException("模組檔案不完整");
        Properties p=new Properties();p.load(new ByteArrayInputStream(f.get("module.prop")));
        if(!id.equals(p.getProperty("id"))||!version.equals(p.getProperty("version")))throw new IOException("模組識別不符");
        if(code!=null&&!code.equals(p.getProperty("versionCode")))throw new IOException("模組版本碼不符");
    }
}

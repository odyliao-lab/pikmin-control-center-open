package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class UpdateInfoTest {
    static int count;
    static final String RAW="schema=1\npackageName=dev.ody.pikmincontrol\nversionName=0.8.0\nversionCode=41\n"
            +"apkUrl=https://github.com/odyliao-lab/pikmin-control-center-releases/releases/download/v0.8.0/pikmin-control-center-0.8.0.apk\n"
            +"sha256="+new String(new char[64]).replace('\0','a')+"\nsize=12345\nminSdk=28\nsummary=更新測試\ncompatibility=APK only\n";
    static void check(boolean value){count++;if(!value)throw new AssertionError("case "+count);}
    static void reject(String raw){try{new UpdateInfo(raw);throw new AssertionError("accepted malformed manifest");}catch(IllegalArgumentException expected){count++;}}
    public static void main(String[] args) throws Exception {
        UpdateInfo info=new UpdateInfo(RAW);check(info.code==41);check(info.version.equals("0.8.0"));check(info.summary.equals("更新測試"));
        check(info.newerThan(9));check(!info.newerThan(41));check(!info.newerThan(100));
        UpdateInfo priorPublic=new UpdateInfo(RAW.replace("0.8.0","0.32.4").replace("versionCode=41","versionCode=212"));
        check(!priorPublic.newerThan(213)); // A numerically higher display name is not an install upgrade.
        UpdateInfo integrated=new UpdateInfo(RAW.replace("0.8.0","0.32.5").replace("versionCode=41","versionCode=214"));
        check(integrated.newerThan(212));check(integrated.newerThan(213));check(!integrated.newerThan(214));
        reject(RAW+"schema=1\n");reject(RAW+"unexpected=1\n");reject(RAW.replace("schema=1","schema=2"));
        reject(RAW.replace("packageName=dev.ody.pikmincontrol","packageName=other"));
        reject(RAW.replace("versionName=0.8.0","versionName=0.8.0-rc.1"));
        for(String code:new String[]{"0","-1","2147483648","9999999999999999999999","NaN","41.0"})reject(RAW.replace("versionCode=41","versionCode="+code));
        for(String size:new String[]{"0","-1","104857601"})reject(RAW.replace("size=12345","size="+size));
        reject(RAW.replace("minSdk=28","minSdk=27"));reject(RAW.replace("summary=更新測試","summary="));
        reject(RAW.replace("/v0.8.0/","/v0.7.9/"));reject(RAW.replace("https://github.com/","http://github.com/"));
        reject(RAW.replace("https://github.com/","https://github.com.evil.example/"));
        reject(RAW.replace("sha256=aaaa","sha256=AAAA"));reject(new String(new char[16385]));
        check(UpdateInfo.autoDue(true,1000,0));check(!UpdateInfo.autoDue(false,1000,0));
        check(!UpdateInfo.autoDue(true,1000,1000));check(!UpdateInfo.autoDue(true,86_400_999,1000));
        check(UpdateInfo.autoDue(true,86_401_000,1000));check(UpdateInfo.autoDue(true,999,1000));
        for(String url:new String[]{"https://github.com/x","https://release-assets.githubusercontent.com/a?token=temporary", "https://raw.githubusercontent.com/x", "https://objects.githubusercontent.com/x"})check(UpdateInfo.allowedRedirect(url));
        for(String url:new String[]{"http://github.com/x","file:///x","https://evil.example/x","https://github.com.evil.example/x","https://user@github.com/x","https://github.com:444/x","https://github.com/x#fragment","not a url"})check(!UpdateInfo.allowedRedirect(url));
        Path file=Files.createTempFile("update-digest-",".tmp");
        try{Files.write(file,"abc".getBytes(StandardCharsets.UTF_8));check(UpdateClient.digest(file.toFile()).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));}
        finally{Files.delete(file);}
        System.out.println("PASS UpdateInfo: "+count+" version, schema, source, throttle and SHA checks");
    }
}

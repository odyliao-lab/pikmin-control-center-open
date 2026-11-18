package dev.ody.pikmincontrol;
import java.util.*;
import java.io.*;

/** Pair metadata is inside the signed APK, never trusted from public update prose. */
final class CorePair {
    final String version,sha; final int code,app; final long size;
    final boolean unified;
    CorePair(String raw) throws Exception {
        if(raw==null||raw.length()>4096)throw new IOException("核心配對資料過大");
        Properties p=new Properties();
        for(String line:raw.split("\\r?\\n")){if(line.isEmpty())continue;int i=line.indexOf('=');
            if(i<=0||p.put(line.substring(0,i),line.substring(i+1))!=null)throw new IOException("核心配對資料重複／格式錯誤");}
        unified="2".equals(p.getProperty("schema"));
        if(p.size()!=6||(!unified&&!"1".equals(p.getProperty("schema"))))throw new IOException("核心配對資料錯誤");
        version=p.getProperty("version","");sha=p.getProperty("sha256","");
        code=Integer.parseInt(p.getProperty("code"));app=Integer.parseInt(p.getProperty("app"));size=Long.parseLong(p.getProperty("size"));
        if(!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")||!sha.matches("[a-f0-9]{64}")||code<=0||app<=0||size<=0||size>SetupBundle.MAX)throw new IOException("核心配對欄位無效");
        if(unified&&code<68)throw new IOException("整合核心版本過舊");
    }
    static boolean upgrade(int current,int target){return current>0&&target>current;}
    /** Rebuild only after the caller has verified the durable private ZIP hash. */
    static CorePair pending(Properties module,int expected,boolean target,long app,int size,String sha)throws Exception {
        String version=module.getProperty("version",""),code=module.getProperty("versionCode","");
        String marker=module.getProperty("pccUnified");
        if(!"pikmin_nectar_rpc".equals(module.getProperty("id"))||!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                ||!code.matches("[1-9][0-9]*")||!code.equals(""+expected)
                ||(marker!=null&&!marker.equals("1"))||(target&&!"1".equals(marker)))
            throw new IOException("原遷移模組身分或整合標記不符");
        return new CorePair("schema="+("1".equals(marker)?2:1)+"\nversion="+version+"\ncode="+code+"\napp="+app
                +"\nsize="+size+"\nsha256="+sha+"\n");
    }
    boolean gpsRuntime(String raw,String before,String after,long now){
        if(!unified)return true;
        try{String[] f=raw.trim().split("\t",-1);if(f.length!=6)return false;long at=Long.parseLong(f[1]);
            return f[0].equals("v1")&&before.matches("[1-9][0-9]*")&&before.equals(after)&&before.equals(f[2])
                &&f[3].equals(version)&&f[4].equals(""+code)&&f[5].equals("1")&&at>0&&now>=at&&now-at<=12000;
        }catch(Exception e){return false;}
    }
    boolean runtime(String raw,String before,String after,long now){
        try{String[] f=raw.trim().split("\t",-1);long at=Long.parseLong(f[1]);
            return f.length==6&&f[0].equals("v1")&&before.matches("[1-9][0-9]*")&&before.equals(after)&&before.equals(f[2])
                &&f[3].equals(version)&&f[4].equals(""+code)&&f[5].equals("157.0")&&at>0&&now>=at&&now-at<=12000;
        }catch(Exception e){return false;}
    }
}

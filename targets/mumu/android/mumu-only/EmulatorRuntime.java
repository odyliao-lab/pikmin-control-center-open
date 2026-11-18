package dev.ody.pikmincontrol;

import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Narrow experimental profile. This class is never copied to the phone build. */
final class EmulatorRuntime {
    private static Boolean cached;
    static synchronized boolean allowed() {
        if(cached!=null)return cached;
        cached=false;
        if(Build.VERSION.SDK_INT!=35||Build.SUPPORTED_ABIS.length==0||!"x86_64".equals(Build.SUPPORTED_ABIS[0]))return false;
        Process p=null;
        try {
            p=new ProcessBuilder("/system/bin/getprop","ro.dalvik.vm.native.bridge").start();
            if(!p.waitFor(2,TimeUnit.SECONDS))return false;
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            try(InputStream in=p.getInputStream()){int b;while(out.size()<256&&(b=in.read())!=-1)out.write(b);}
            cached=p.exitValue()==0&&"libnb.so".equals(out.toString("UTF-8").trim());
        }catch(Exception ignored){}finally{if(p!=null)p.destroy();}
        return cached;
    }
    static void require() {
        if(!allowed())throw new SecurityException("此實驗版僅供 MuMu x64／Android15／libnb，不支援一般手機");
    }
    static void hostDeploymentOnly() {
        throw new IllegalStateException("MuMu 實驗核心僅可由已驗證的電腦部署流程安裝／回退，不使用手機版升級精靈");
    }
}

package dev.ody.pikmincontrol.hc;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import dev.ody.pikmincontrol.HcCompatPolicy;
import dev.ody.pikmincontrol.hccompat.CompatPolicy;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;

/** Embedded in the core SO, loaded only into the selected game. No Xposed dependency. */
public final class CoreHcBridge {
    private static final String FITNESS="com.nianticproject.ichigo.fitness.FitnessClientHealthConnect";
    private static final String CONTROL="/data/local/tmp/pikmin-hc-compat.txt";
    private static volatile String state="off",provider="unknown",feature="unknown",fg="unknown",bg="unknown",query="none";
    private static volatile long queryAt;
    private static volatile boolean enabled,verified,armed;
    private static boolean started;
    private static ClassLoader loader;
    private static native boolean nativeInit();
    private static native Method nativeHook(Method target,Object owner,Method callback);
    private static native boolean nativeDeopt(Method target);
    private static native void nativeReport(String state,String provider,String feature,String fg,String bg,String query,long queryAt);
    public static synchronized void begin(Context context,boolean legacyBlocked) {
        if(started||!HcCompatPolicy.allowedSdk(Build.VERSION.SDK_INT))return;
        started=true;loader=context.getClassLoader();
        Thread worker=new Thread(()->{
            verified=identity(context);boolean installed=false,attempted=false;
            for(;;) {
                enabled=requested();
                if(!enabled)state="off";
                else if(legacyBlocked)state="legacy-present";
                else if(!verified)state="identity-mismatch";
                else if(!attempted) {
                    attempted=true;
                    try {installed=install();armed=installed;state=installed?"hook-ready":"hooks-failed";}
                    catch(Throwable error){state="hooks-failed";}
                } else if(installed)state="hook-ready";
                publish();
                try {Thread.sleep(1000);}catch(InterruptedException stop){return;}
            }
        },"pcc-hc-compat");worker.setDaemon(true);worker.start();
    }
    private static boolean requested() {
        FileDescriptor fd=null;
        try {
            fd=Os.open(CONTROL,OsConstants.O_RDONLY|OsConstants.O_NOFOLLOW|OsConstants.O_CLOEXEC|OsConstants.O_NONBLOCK,0);
            StructStat stat=Os.fstat(fd);
            if(!OsConstants.S_ISREG(stat.st_mode)||stat.st_uid!=0||(stat.st_mode&0022)!=0||stat.st_size>8)return false;
            byte[] bytes=new byte[9];int n=Os.read(fd,bytes,0,bytes.length);
            return n>0&&HcCompatPolicy.requested(new String(bytes,0,n,java.nio.charset.StandardCharsets.UTF_8));
        }catch(Exception unavailable){return false;}
        finally {if(fd!=null)try{Os.close(fd);}catch(Exception ignored){}}
    }
    private static boolean identity(Context c) {
        try {
            PackageInfo p=c.getPackageManager().getPackageInfo(CompatPolicy.PACKAGE,0);
            MessageDigest md=MessageDigest.getInstance("SHA-256");long end=android.os.SystemClock.elapsedRealtime()+15000;
            try(FileInputStream in=new FileInputStream(p.applicationInfo.sourceDir)) {
                byte[] b=new byte[65536];int n;
                while((n=in.read(b))!=-1){if(android.os.SystemClock.elapsedRealtime()>end)return false;md.update(b,0,n);}
            }
            StringBuilder hash=new StringBuilder();for(byte b:md.digest())hash.append(String.format("%02x",b&255));
            return CompatPolicy.identity(p.versionName,p.getLongVersionCode(),hash.toString());
        }catch(Exception error){return false;}
    }
    private static boolean install()throws Exception {
        if(!nativeInit())return false;
        Class<?> fitness=Class.forName(FITNESS,false,loader);
        Method callback=Handler.class.getDeclaredMethod("invoke",Object[].class);
        Method[] methods={fitness.getDeclaredMethod("isSupported"),fitness.getDeclaredMethod("getPermissionStatus",boolean.class),fitness.getDeclaredMethod("queryStepCounts",int.class,int.class)};
        // Deoptimize callers before replacing isSupported, including already JIT-compiled callers.
        for(Method m:fitness.getDeclaredMethods())if(!java.lang.reflect.Modifier.isNative(m.getModifiers())&&!java.lang.reflect.Modifier.isAbstract(m.getModifiers())) {
            if(!nativeDeopt(m))return false;
        }
        for(int i=0;i<methods.length;i++) {
            Handler h=new Handler(i,java.lang.reflect.Modifier.isStatic(methods[i].getModifiers()));methods[i].setAccessible(true);
            synchronized(h){h.backup=nativeHook(methods[i],h,callback);if(h.backup==null)return false;h.backup.setAccessible(true);}
        }
        return true;
    }
    private static void publish(){nativeReport(state,provider,feature,fg,bg,query,queryAt);}
    public static final class Handler {
        final int kind;final boolean staticTarget;Method backup;
        Handler(int kind,boolean staticTarget){this.kind=kind;this.staticTarget=staticTarget;}
        public Object invoke(Object[] args)throws Throwable {
            Method original;synchronized(this){original=backup;}
            Object result=HcOriginalCall.invoke(original,staticTarget,args);
            // OFF and unsupported SDK always retain the original result, including true.
            if(!armed||!enabled||!verified||!HcCompatPolicy.allowedSdk(Build.VERSION.SDK_INT)||!requested())return result;
            if(kind==0&&!Boolean.TRUE.equals(result)) {
                try {
                    Class<?> fitness=Class.forName(FITNESS,false,loader);
                    Object status=fitness.getDeclaredMethod("getStatus").invoke(null);
                    Object available=Class.forName("com.nianticproject.ichigo.proto.HealthConnectStatus",false,loader).getField("AVAILABLE").get(null);
                    boolean valid=status==available;provider=valid?"available":"unavailable";
                    int capability=-1;
                    if(valid){
                        Object c=Class.forName("com.nianticproject.ichigo.util.SharedContext",false,loader).getDeclaredMethod("getApplicationContext").invoke(null);
                        Class<?> client=Class.forName("androidx.health.connect.client.HealthConnectClient",false,loader);
                        Object hc=client.getMethod("getOrCreate",Context.class).invoke(null,c);
                        Object features=client.getMethod("getFeatures").invoke(hc);
                        capability=((Number)Class.forName("androidx.health.connect.client.HealthConnectFeatures",false,loader).getMethod("getFeatureStatus",int.class).invoke(features,1)).intValue();
                    }
                    feature=capability==2?"available":"unavailable";
                    result=CompatPolicy.support(true,verified,valid,capability);
                }catch(Throwable failure){provider="error";feature="unknown";/* retain original false */}
            } else if(kind==1) {
                String value=String.valueOf(result);value="GRANTED".equals(value)?"granted":"DENIED".equals(value)?"denied":"unknown";
                if(Boolean.TRUE.equals(HcOriginalCall.parameters(staticTarget,args)[0]))fg=value;else bg=value;
            } else if(kind==2) {
                try {
                    Object source=result.getClass().getMethod("getFitnessDataSource").invoke(result);
                    int count=((Number)result.getClass().getMethod("getStepCountsCount").invoke(result)).intValue();
                    if(count<0||count>1000)query="unknown";
                    else if(!"HEALTH_CONNECT".equals(String.valueOf(source)))query="other-source";
                    else {
                        long total=0;
                        for(int i=0;i<count;i++){
                            Object bucket=result.getClass().getMethod("getStepCounts",int.class).invoke(result,i);
                            long steps=((Number)bucket.getClass().getMethod("getStepCount").invoke(bucket)).longValue();
                            if(steps<0||steps>Long.MAX_VALUE-total)throw new IllegalStateException("invalid observed count");
                            total+=steps;
                        }
                        query=total>0?"existing-nonzero":"zero";
                    }
                    queryAt=System.currentTimeMillis();
                }catch(Throwable error){query="unknown";}
            }
            publish();return result;
        }
    }
}

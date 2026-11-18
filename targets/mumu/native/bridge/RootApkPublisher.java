package dev.ody.pccbridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Process;

/** Root observation producer ONLY. No game APIs, commands, owner lease or GPS.
 * Launcher must independently verify protected code/artifacts and create the
 * fixed Root-owned witness directory. Never creates/repairs that directory. */
public final class RootApkPublisher {
    private static native boolean publish(PackageManager pm,int gamePid);
    private static native boolean acquire();
    private static native boolean release();
    public static void main(String[] args){
        try { run(args); } catch(Throwable error){
            System.err.println("Root APK observation unavailable; no repair/replay/authority");
            System.exit(1);
        }
    }
    private static void run(String[] args)throws Exception {
        if(Process.myUid()!=0||(args.length!=3&&args.length!=4)||
           !args[0].matches("/data/local/tmp/pcc-emu-planting-test-[0-9a-f]{32}"))
            throw new SecurityException("Protected Root observation launcher required");
        if(args.length==4&&!"stdin-owner".equals(args[3]))throw new SecurityException("Unknown lifetime control");
        int pid=Integer.parseInt(args[1]);long seconds=Long.parseLong(args[2]);
        if(pid<=0||pid==Process.myPid()||seconds<1||seconds>43200)throw new SecurityException("Observation scope");
        System.load(args[0]+"/apk-probe.so");
        if(!acquire())throw new SecurityException("Observation producer busy/unavailable");
        try{
        ObserverControl control=args.length==4?new ObserverControl(System.in):null;
        if(control!=null)control.start();
        android.os.Looper.prepareMainLooper();
        Class<?> thread=Class.forName("android.app.ActivityThread");
        Object system=thread.getDeclaredMethod("systemMain").invoke(null);
        Context context=(Context)thread.getDeclaredMethod("getSystemContext").invoke(system);
        final long end=System.nanoTime()+seconds*1_000_000_000L;
        boolean announced=false;
        while(System.nanoTime()-end<0&&(control==null||control.open())){
            // JNI independently reads current game /proc and installed signed
            // APK on each iteration. Any mismatch/expired I/O fails closed.
            if(!publish(context.getPackageManager(),pid))throw new SecurityException("Observation rejected");
            if(!announced){System.out.println("@root-observer-pid\t"+Process.myPid());System.out.println("@root-observer-game\t"+pid);if(control!=null)System.out.println("@root-observer-control\tstdin-owner");announced=true;}
            // Responsive stop between publications, never cancel a native
            // observation halfway through its protected write transaction.
            final long next=System.nanoTime()+1_000_000_000L;
            while(System.nanoTime()-next<0&&System.nanoTime()-end<0&&(control==null||control.open()))Thread.sleep(100);
        }
        }finally{if(!release())throw new SecurityException("Observation producer release unconfirmed");}
        System.out.println("PASS Root observation producer completed bounded lifetime; NOT OwnerEnvironment or gameplay");
        System.exit(0);
    }
}

package dev.ody.pikmincontrol.validation;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import android.os.Build;
import dev.ody.pikmincontrol.hc.HcOriginalCall;
public final class HcEngineProbe {
    static volatile boolean on;
    static volatile int marker;
    static int checks;
    static native boolean initialize();
    static native Method hook(Method target,Object owner,Method callback);
    static native boolean deopt(Method target);
    public static boolean gate(){return marker==7;}
    public static boolean caller(){return gate();}
    static void check(boolean pass){checks++;if(!pass)throw new AssertionError("HC engine check "+checks);}
    public static final class Handler {
        Method original;
        public Object invoke(Object[] args)throws Throwable {
            Method backup;synchronized(this){backup=original;}
            boolean actual;
            actual=(Boolean)HcOriginalCall.invoke(backup,true,args);
            return actual||on;
        }
    }
    public static final class Receiver {
        final int base;
        Receiver(int base){this.base=base;}
        public int sum(int a,int b){if(a<0)throw new IllegalArgumentException("original-instance-error");return base+a+b;}
    }
    public static int instanceCaller(Receiver receiver,int a,int b){return receiver.sum(a,b);}
    public static final class InstanceHandler {
        Method original;
        public Object invoke(Object[] args)throws Throwable {
            Method backup;synchronized(this){backup=original;}
            int actual=(Integer)HcOriginalCall.invoke(backup,false,args);
            return actual+(on?100:0);
        }
    }
    public static void main(String[] args)throws Exception {
        System.out.println("PROBE pid="+android.os.Process.myPid()+" sdk="+Build.VERSION.SDK_INT);
        System.load(args[0]);
        System.out.println("PROBE library loaded");
        if(Build.VERSION.SDK_INT>=34){check(!initialize());System.out.println("PASS HC engine Android14+ refused initialization; no target hook installed");return;}
        check(initialize());
        System.out.println("PROBE engine initialized");
        for(int i=0;i<20000;i++)if(caller())throw new AssertionError("unexpected original");
        check(deopt(HcEngineProbe.class.getDeclaredMethod("caller")));
        Handler h=new Handler();
        synchronized(h){h.original=hook(HcEngineProbe.class.getDeclaredMethod("gate"),h,Handler.class.getDeclaredMethod("invoke",Object[].class));check(h.original!=null);h.original.setAccessible(true);}
        check(!caller());on=true;check(caller());on=false;check(!caller());
        marker=7;check(caller());on=true;check(caller());marker=0;check(caller());on=false;check(!caller());
        Receiver first=new Receiver(70),second=new Receiver(30);
        for(int i=0;i<20000;i++)if(instanceCaller(first,1,2)!=73)throw new AssertionError("original instance");
        check(deopt(HcEngineProbe.class.getDeclaredMethod("instanceCaller",Receiver.class,int.class,int.class)));
        InstanceHandler instance=new InstanceHandler();
        synchronized(instance){instance.original=hook(Receiver.class.getDeclaredMethod("sum",int.class,int.class),instance,InstanceHandler.class.getDeclaredMethod("invoke",Object[].class));check(instance.original!=null);instance.original.setAccessible(true);}
        check(instanceCaller(first,1,2)==73);on=true;check(instanceCaller(first,1,2)==173);
        on=false;check(instanceCaller(first,1,2)==73);check(instanceCaller(second,4,5)==39);
        boolean originalError=false;try{instanceCaller(first,-1,2);}catch(IllegalArgumentException e){originalError="original-instance-error".equals(e.getMessage());}
        check(originalError);on=true;check(instanceCaller(second,4,5)==139);on=false;check(instanceCaller(second,4,5)==39);
        System.out.println("PASS HC engine "+checks+" actual Android ART/JIT caller, original-result and ON/OFF checks; isolated process, no game or health record access");
    }
}

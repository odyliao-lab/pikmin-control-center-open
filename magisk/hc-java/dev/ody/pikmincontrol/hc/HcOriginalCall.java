package dev.ody.pikmincontrol.hc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/** LSPlant includes the receiver as args[0] for an instance target. */
public final class HcOriginalCall {
    private HcOriginalCall() {}
    public static Object[] parameters(boolean staticTarget,Object[] args) {
        if(staticTarget)return args;
        if(args.length==0||args[0]==null)throw new IllegalArgumentException("Missing original receiver");
        return Arrays.copyOfRange(args,1,args.length);
    }
    public static Object invoke(Method backup,boolean staticTarget,Object[] args)throws Throwable {
        Object[] parameters=parameters(staticTarget,args);
        try {return backup.invoke(staticTarget?null:args[0],parameters);}
        catch(InvocationTargetException error){throw error.getCause();}
    }
}

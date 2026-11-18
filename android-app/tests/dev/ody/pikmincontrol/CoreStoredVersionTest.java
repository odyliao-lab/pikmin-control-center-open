package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.*;

public final class CoreStoredVersionTest {
    public static void main(String[] args)throws Exception {
        Map<String,Object> transaction=new HashMap<>();
        transaction.put("old","82");transaction.put("target",83);
        Map<String,Object> before=new HashMap<>(transaction);
        if(CoreStoredVersion.read(transaction.get("old"))!=82||CoreStoredVersion.read(transaction.get("target"))!=83)
            throw new AssertionError("Legacy mixed preference types");
        if(!before.equals(transaction))throw new AssertionError("Must preserve transaction evidence");
        if(CoreStoredVersion.read("2147483647")!=Integer.MAX_VALUE||CoreStoredVersion.read(84)!=84)
            throw new AssertionError("Valid versions");
        for(Object value:Arrays.asList(null,"","0","-1","+84"," 84","84 ","084","84.0","2147483648",0,-1,84L,84.0,true)) {
            try{CoreStoredVersion.read(value);throw new AssertionError("Accepted invalid value: "+value);}
            catch(IOException expected){}
        }
        System.out.println("PASS CoreStoredVersion: persisted String old / Integer target, bounds, invalid types, no mutation");
    }
}

package dev.ody.pikmincontrol;
import java.util.*;
public final class SessionBindingTest {
    public static void main(String[] args){
        String a=String.join("",Collections.nCopies(64,"a")),b=String.join("",Collections.nCopies(64,"b"));
        WorkCheckpoint cp=new WorkCheckpoint(UUID.randomUUID().toString(),"flower-farm","",1,1,5,Arrays.asList("1\t2"));
        WorkCheckpoint bound=cp.bindFreshAccount(a);
        if(!bound.account.equals(a)||!bound.id.equals(cp.id)||!cp.account.isEmpty())throw new AssertionError("fresh binding");
        reject(()->cp.bindFreshAccount(""));reject(()->bound.bindFreshAccount(b));
        bound.uncertain=true;reject(()->bound.bindFreshAccount(a));
        if(!SessionIdentity.parse("v1\t1000\t12\t-\tunknown\tbusy\tend","12","12",1001).isEmpty())throw new AssertionError("logout");
        System.out.println("PASS fresh post-startup account binding, switch/unknown/pending rejection; NOT native login validation");
    }
    static void reject(Runnable r){try{r.run();}catch(IllegalStateException expected){return;}throw new AssertionError("accepted unsafe binding");}
}

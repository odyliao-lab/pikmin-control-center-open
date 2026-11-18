package dev.ody.pikmincontrol;
import java.util.*;

public final class BatchLateReceiptTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("late receipt "+checks);}
    static final String ACCOUNT=String.join("",Collections.nCopies(64,"a"));
    static WorkCheckpoint checkpoint() {
        WorkCheckpoint cp=new WorkCheckpoint(UUID.randomUUID().toString(),"batch",ACCOUNT,1000,1,5,
            Arrays.asList("one\tseed\t99999\t1.0\t2.0\t0.0","two\tfruit\t99999\t1.0\t2.0\t0.0","three\tgift\t99999\t1.0\t2.0\t0.0"));
        cp.done.set(0);cp.uncertain=true;return cp;
    }
    static final String STATES="990\tpreparing\t0\t3\tstarting\tbegin\n"
        +"1100\thandoff\t1\t3\tone\tattempt_1_of_1\n1200\tawaiting-game-list\t1\t3\tone\tnative_gate\n"
        +"2100\thandoff\t2\t3\ttwo\tattempt_1_of_1\n2200\tawaiting-game-list\t2\t3\ttwo\tnative_gate\n"
        +"17200\tconfirmation-timeout\t2\t3\ttwo\tgiving_up\n18000\tstop-incomplete\t3\t3\treleased\tunknown\n";
    static String reward(long at,String task,String event,String count,String code){return at+"\t"+event+"\t"+task+"\tfruit\t"+count+"\t"+code+"\t0\tkeep\tend\n";}
    static final String REWARDS=reward(900,"old","batch-confirmed","1","0")+reward(37000,"two","batch-confirmed","3","0");
    static boolean found(WorkCheckpoint cp,String states,String rewards){return BatchLateReceipt.find(cp,states,rewards,50000)!=null;}
    public static void main(String[] args)throws Exception {
        WorkCheckpoint cp=checkpoint();String original=cp.properties().getProperty("checksum");
        BatchLateReceipt.Proof p=BatchLateReceipt.find(cp,STATES,REWARDS,50000);
        check(p!=null&&p.task.equals("two")&&p.authorizedAt==2200&&p.receivedAt==37000);
        check(cp.uncertain&&cp.done.cardinality()==1&&original.equals(cp.properties().getProperty("checksum")));
        check(found(cp,STATES,REWARDS+reward(38000,"two","batch-confirmed","3","0")));
        check(found(cp,STATES,REWARDS.replace("37000\tbatch-confirmed","37000\tone-confirmed")));
        check(!found(cp,STATES,REWARDS.replace("two","other")));
        for(String event:new String[]{"batch-dispatched","start-requested","inventory-confirmed-absent","batch-failed"})
            check(!found(cp,STATES,REWARDS.replace("37000\tbatch-confirmed","37000\t"+event)));
        for(String count:new String[]{"0","-1","2147483648","bad"})check(!found(cp,STATES,reward(900,"old","batch-confirmed","1","0")+reward(37000,"two","batch-confirmed",count,"0")));
        for(String code:new String[]{"1","-1","bad","00"})check(!found(cp,STATES,REWARDS.replace("\t3\t0\t0\tkeep","\t3\t"+code+"\t0\tkeep")));
        check(!found(cp,STATES,REWARDS.replace("37000","2100")));
        check(!found(cp,STATES,REWARDS.replace("37000","50001")));
        check(!found(cp,STATES,REWARDS.substring(REWARDS.indexOf('\n')+1)));
        check(!found(cp,STATES.substring(STATES.indexOf('\n')+1),REWARDS));
        check(!found(cp,STATES.replace("2200\tawaiting-game-list","2200\thandoff"),REWARDS));
        check(!found(cp,STATES.replace("2100\thandoff","2100\tarriving"),REWARDS));
        check(!found(cp,STATES.replace("18000\tstop-incomplete\t3\t3\treleased\tunknown\n",""),REWARDS));
        check(!found(cp,STATES+"19000\tpreparing\t0\t3\tstarting\tnew_run\n",REWARDS));
        check(!found(cp,STATES.replace("17200\tconfirmation-timeout","2100\tconfirmation-timeout"),REWARDS));
        check(!found(cp,STATES.replace("2200\tawaiting-game-list\t2\t3\ttwo","2200\tawaiting-game-list\t2\t3\tother"),REWARDS));
        check(!found(cp,STATES.replace("18000\tstop-incomplete","50001\tstop-incomplete"),REWARDS));
        check(!found(cp,STATES.substring(0,STATES.length()-1),REWARDS));
        check(!found(cp,STATES,REWARDS.substring(0,REWARDS.length()-1)));
        check(!found(cp,STATES,"broken\n"));check(!found(cp,"broken\n",REWARDS));
        check(!found(cp,STATES.replace("2100\thandoff","2100\tawaiting-game-list"),REWARDS));
        String extra="2300\thandoff\t3\t3\tthree\tattempt_1_of_1\n2400\tawaiting-game-list\t3\t3\tthree\tnative_gate\n";
        check(!found(cp,STATES.replace("17200\tconfirmation-timeout",extra+"17200\tconfirmation-timeout"),REWARDS));
        check(!found(cp,STATES.replace("17200\tconfirmation-timeout",extra.replace("three","two")+"17200\tconfirmation-timeout"),REWARDS));
        cp.uncertain=false;check(!found(cp,STATES,REWARDS));
        WorkCheckpoint emptyAccount=new WorkCheckpoint(cp.id,"batch","",1000,1,5,cp.entries);emptyAccount.uncertain=true;
        check(!found(emptyAccount,STATES,REWARDS));check(BatchLateReceipt.find(null,STATES,REWARDS,50000)==null);
        String gateway=FarmResetSourceTest.source("BatchRecoveryGateway"),store=FarmResetSourceTest.source("BatchReconciliationStore");
        check(gateway.contains("freshIdentity(c,cp,switched)")&&gateway.contains("CoreUpdater.requireReady(c)"));
        check(gateway.indexOf("freshIdentity(c,cp,switched)")<gateway.indexOf("BatchReconciliationStore.present(c,cp)"));
        check(store.contains("verify(cp,read(file))")&&store.contains("original uncertain checkpoint; NEVER replay"));
        check(store.contains("1_048_576")&&gateway.contains("600_000")&&gateway.contains("tail -n 800"));
        for(String bad:new String[]{".delete(","finishTask(","uncertain=false","CheckpointStore.save("})check(!store.contains(bad));
        check(!gateway.contains("beginBatch(")&&!gateway.contains("markArrivalReady("));
        check(gateway.contains("readFailure")&&gateway.contains("new PendingReview(cp,readFailure)"));
        System.out.println("PASS BatchLateReceipt: "+checks+" pure/source checks; NOT device recovery acceptance");
    }
}

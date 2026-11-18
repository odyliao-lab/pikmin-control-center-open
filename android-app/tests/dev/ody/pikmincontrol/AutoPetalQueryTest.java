package dev.ody.pikmincontrol;

public final class AutoPetalQueryTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception{
        String revision=new AutoPetalConfig().revision;
        String good="v1\t10000\t12\t"+revision+"\toff\t1\t-\t0\npetal\tactual-id\tflower-5\t1\t80\nend\n";
        String[] fresh={"12",good,"12"},stale={"12",good.replace("10000","1000"),"12"};
        long[] elapsed={0};int[] reads={0},waits={0};
        String[] result=AutoPetalGateway.awaitSnapshot(revision,()->{reads[0]++;return reads[0]==1?stale:fresh;},
                ()->elapsed[0],()->10001L,ms->{elapsed[0]+=ms;waits[0]++;});
        check(result==fresh);check(reads[0]==2);check(waits[0]==1);check(elapsed[0]==600);
        elapsed[0]=0;reads[0]=0;waits[0]=0;
        result=AutoPetalGateway.awaitSnapshot(revision,()->{reads[0]++;return fresh;},
                ()->elapsed[0],()->10001L,ms->{elapsed[0]+=ms;waits[0]++;});
        check(result==fresh&&reads[0]==1&&waits[0]==0);
        elapsed[0]=0;reads[0]=0;
        result=AutoPetalGateway.awaitSnapshot(revision,()->{reads[0]++;return stale;},
                ()->elapsed[0],()->10001L,ms->elapsed[0]+=ms);
        check(result==stale);check(elapsed[0]==4000);check(reads[0]<=8);
        check(!AutoPetalState.parse(result[1],result[0],result[2],revision,10001).valid);
        String[] wrongOwner={"12",good,"13"};elapsed[0]=0;
        result=AutoPetalGateway.awaitSnapshot(revision,()->wrongOwner,()->elapsed[0],()->10001L,ms->elapsed[0]+=ms);
        check(result==wrongOwner&&elapsed[0]==4000);
        check(AutoPetalState.catalogueRows(result[1],result[0],result[2],revision,10001).isEmpty());
        String[] oldRevision={"12",good.replace(revision,"old"),"12"};elapsed[0]=0;
        result=AutoPetalGateway.awaitSnapshot(revision,()->oldRevision,()->elapsed[0],()->10001L,ms->elapsed[0]+=ms);
        check(result==oldRevision&&elapsed[0]==4000);
        try{
            elapsed[0]=0;
            AutoPetalGateway.awaitSnapshot(revision,()->stale,()->elapsed[0],()->10001L,ms->{throw new InterruptedException();});
            throw new AssertionError("interruption swallowed");
        }catch(InterruptedException expected){checks++;}
        try{
            AutoPetalGateway.awaitSnapshot(revision,()->{throw new java.io.IOException();},()->0L,()->10001L,ms->{});
            throw new AssertionError("read failure swallowed");
        }catch(java.io.IOException expected){checks++;}
        System.out.println("PASS AutoPetalQuery: "+checks+" actual acknowledgement-loop/clock/owner/revision/timeout checks; fake I/O, NOT root");
    }
}

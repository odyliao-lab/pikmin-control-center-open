package dev.ody.pikmincontrol;
public final class RouteStateTest {
    static final String T="12345678-1234-1234-1234-123456789abc";
    static String row(long at,String stop){return "v1\t"+at+"\t9\t"+T+"\tprepare\t"+stop+"\t0\t300000";}
    public static void main(String[] args){
        RouteState.StopGate g=new RouteState.StopGate();
        for(int i=0;i<4;i++){boolean ready=g.observe(new RouteState(row(1000+i*1000,"1"),"9",1000+i*1000,T,"prepare"));if(ready!=(i==3))throw new AssertionError();}
        if(g.observe(new RouteState(row(5000,"0"),"9",5000,T,"prepare")))throw new AssertionError();
        for(String bad:new String[]{row(1000,"yes"),row(1000,"1").replace("300000","2000"),row(6000,"1"),row(999,"1"),row(4000,"1")+"\tx"}){
            try{new RouteState(bad,"9",4000,T,"prepare");throw new AssertionError("accepted invalid frame");}catch(IllegalArgumentException expected){}
        }
        try{new RouteState(row(4000,"1"),"10",4000,T,"prepare");throw new AssertionError();}catch(IllegalArgumentException expected){}
        try{new RouteState(row(4000,"1"),"9",4000,T,"walk");throw new AssertionError();}catch(IllegalArgumentException expected){}
        System.out.println("PASS route status: PID/token/phase, freshness, malformed flags and sustained stop acknowledgement");
    }
}

package dev.ody.pikmincontrol;
public final class RouteObservationTest {
    static int checks;
    static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    static String[] frame(){return new String[]{"10976",
            "    gps provider [mock]:\n      allowed=true\n      identity=123/example\n      last mock location=Location[gps 37.7042274,109.4021149 et=+1104000ms mock]\n    fused provider:\n",
            FarmLocationTest.row(9,"location-moving",4,37.7042274,109.4021149),
            "1788696786000\ton\t1\t1\tactive-owned\t-",
            "v1\t1788696786000\t10976\t"+HASH+"\tready\tbusy\tend","10976"};}
    static void verify(String[] p,String hash,long wall,boolean accepted) {
        boolean ok=true;try{new RouteObservation(p,"123/example","10976",wall,1104100).require("10976",hash,1788696785000L);}
        catch(IllegalStateException|IllegalArgumentException e){ok=false;}
        checks++;if(ok!=accepted)throw new AssertionError("check "+checks);
    }
    public static void main(String[] args){
        verify(frame(),HASH,1788696786100L,true);
        verify(frame(),"",1788696786100L,false);
        verify(frame(),HASH.replace('a','b'),1788696786100L,false);
        verify(frame(),HASH,1788696789001L,false);
        String[] p=frame();p[5]="222";verify(p,HASH,1788696786100L,false);
        p=frame();p[3]=p[3].replace("\ton\t1\t1\t","\toff\t0\t0\t");verify(p,HASH,1788696786100L,false);
        p=frame();p[4]="";verify(p,HASH,1788696786100L,false);
        p=frame();p[2]="";verify(p,HASH,1788696786100L,false);
        p=frame();p[1]=p[1].replace("123/example","999/other");verify(p,HASH,1788696786100L,false);
        p=frame();p[3]=p[3].replace("\ton\t1\t1\t","\toff\t0\t0\t");
        new RouteObservation(p,"123/example","10976",1788696786100L,1104100).require("10976",HASH,1788696785000L,false);checks++;
        System.out.println("PASS combined route observation: "+checks+" account/PID/planting/provider/freshness checks; NOT device validation");
    }
}

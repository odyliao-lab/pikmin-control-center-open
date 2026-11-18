package dev.ody.pikmincontrol;
import java.util.*;
public final class BatchSitesTest {
    static DispatchProbeGateway.Candidate c(String id,double lat){return new DispatchProbeGateway.Candidate(id,"fruit",0,lat,120);}
    public static void main(String[] args) {
        DispatchProbeGateway.Candidate a=c("a",25), b=c("b",26), d=c("d",25), e=c("e",25.000001);
        List<DispatchProbeGateway.Candidate> input=Arrays.asList(a,b,d,e,a);
        List<DispatchProbeGateway.Candidate> grouped=BatchSites.group(input);
        if(grouped.size()!=4 || grouped.get(0)!=a || grouped.get(1)!=d || grouped.get(2)!=e || grouped.get(3)!=b)
            throw new AssertionError("stable sites and deduplication");
        if(input.get(1)!=b || BatchSites.same(a,e) || BatchSites.same(null,a)
                || BatchSites.same(c("x",Double.NaN),c("y",Double.NaN)))throw new AssertionError("no radius chain/unknown reuse");
        DispatchProbeGateway.Candidate near=c("near",25.0005), chain=c("chain",25.001), far=c("far",28);
        grouped=BatchSites.group(Arrays.asList(a,far,near,chain));
        if(grouped.get(0)!=a||grouped.get(1)!=near||grouped.get(2)!=far||grouped.get(3)!=chain)
            throw new AssertionError("100m anchor grouping must not chain");
        if(!BatchSites.nearby(a,near)||BatchSites.nearby(a,chain)||!BatchSites.nearby(near,chain))
            throw new AssertionError("area boundary");
        double delta=Math.toDegrees(100.0/6371000.0);
        if(!BatchSites.nearby(c("o",0),c("in",delta-1e-9))||BatchSites.nearby(c("o",0),c("out",delta+1e-9)))
            throw new AssertionError("100m edge");
        DispatchProbeGateway.Candidate gift=new DispatchProbeGateway.Candidate("gift","gift",0,25,120);
        if(BatchSites.nearby(a,gift)||BatchSites.nearby(a,c("bad",91))||BatchSites.nearby(a,c("nan",Double.NaN)))
            throw new AssertionError("gift and invalid coordinates excluded");
        DispatchProbeGateway.Candidate west=new DispatchProbeGateway.Candidate("w","seed",0,0,179.9998);
        DispatchProbeGateway.Candidate east=new DispatchProbeGateway.Candidate("e","fruit",0,0,-179.9998);
        if(!BatchSites.nearby(west,east))throw new AssertionError("date line distance");
        String cap="v1\t1000\t42\t100\t2000\tend";
        if(BatchSites.supportedRadius(cap,"42","42",2000)!=100
                || BatchSites.supportedRadius(cap,"42","43",2000)!=4
                || BatchSites.supportedRadius(cap,"42","42",999)!=4
                || BatchSites.supportedRadius(cap,"42","42",13001)!=4
                || BatchSites.supportedRadius("","42","42",2000)!=4
                || BatchSites.supportedRadius(cap.replace("2000","300000"),"42","42",2000)!=4)
            throw new AssertionError("live capability required; old/malformed/future/stale/PID mismatch uses 4m");
        System.out.println("PASS BatchSites: 100m anchor groups, boundaries, no chain, dedupe, gifts/invalid excluded, date line");
    }
}

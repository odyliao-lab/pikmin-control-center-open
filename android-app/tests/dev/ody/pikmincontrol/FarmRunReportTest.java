package dev.ody.pikmincontrol;

import java.util.Properties;
import java.io.*;

public final class FarmRunReportTest {
    static int checks;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    static FarmRunReport fresh(){FarmRunReport r=new FarmRunReport("00000000-0000-0000-0000-000000000001",10000,4,5);r.openWindow(11000);r.closeWindow(20000);return r;}
    static String start(long at,String kind,String id){return at+"\tstart-requested\t"+kind+"\t"+id+"\t150\t2\n";}
    static String reward(long at,String id,String event,int count,int errors){return at+"\t"+event+"\t"+id+"\tfruit\t"+count+"\t"+errors+"\ta\tb\tc\n";}
    public static void main(String[] args) throws Exception {
        FarmRunReport r=fresh();
        r.ingest(start(9000,"fruit","old")+start(12000,"fruit","a")+start(12500,"seed","b")+start(21000,"fruit","outside"),"",22000,true);
        check(r.tasks.size()==2,"owned start windows only");check(r.pending()==2,"requests are pending, not collected");
        r.ingest("13000\tinventory-confirmed\tfruit\ta\t0\t0\n","",23000,true);
        check(r.collected(null)==0,"inventory confirmation is not reward");
        r.ingest("",reward(9000,"a","one-confirmed",1,0)+reward(14000,"a","one-requested",1,0)+reward(14000,"b","one-confirmed",1,1),23000,true);
        check(r.collected(null)==0,"old receipt, request-only and nonzero errors rejected");
        String rewards=reward(22000,"a","one-confirmed",1,0)+reward(22000,"unrelated","batch-confirmed",1,0);
        r.ingest("",rewards,23000,true);r.ingest(start(12000,"fruit","a"),rewards,23000,true);
        check(r.tasks.size()==2&&r.collected(null)==1&&r.collected("fruit")==1&&r.pending()==1,"dedup and unrelated IDs");
        r.ingest("23000\tstart-rpc-faulted\tseed\tb\t10500\t0\n","",24000,true);
        check(r.failed()==1&&r.pending()==0,"native RPC failure linked by attempt timestamp");
        r.openWindow(25000);r.closeWindow(30000);
        r.ingest(start(26000,"seed","b"),"",30000,true);
        check(r.failed()==0&&r.pending()==1,"newer retry clears old failure");
        r.ingest("40000\tstart-rpc-faulted\tseed\tb\t100\t0\n","",41000,true);
        check(r.failed()==0,"unrelated later retry fault not attributed");
        r.ingest("",reward(32000,"b","batch-confirmed",1,0),42000,true);
        check(r.collected("seed")==1&&r.pending()==0,"late receipt accepted");
        check(r.collected(null)+r.pending()+r.failed()==r.tasks.size(),"task partition");
        r.attempted=4;r.arrived=3;r.completed=2;r.skipped=2;r.ended=42000;r.terminal="completed";
        r.routeDiag="stop=observation-gap; dtMs=4050, ageMs=480, movedMeters=21.3; reached=3/38; loops=40 read=420/519/560";
        r.routeDispatchDiag="候選 12／評估 7／送出 3 · 已送出派遣";
        Properties p=r.properties();ByteArrayOutputStream bytes=new ByteArrayOutputStream();p.store(bytes,"");
        Properties read=new Properties();read.load(new ByteArrayInputStream(bytes.toByteArray()));
        FarmRunReport restored=FarmRunReport.restore(read);
        check(restored.collected(null)==2&&restored.completed==2&&restored.windows.size()==2,"durable full round trip");
        check(restored.routeDiag.equals(r.routeDiag),"route diagnostics round trip");
        check(restored.routeDispatchDiag.equals(r.routeDispatchDiag),"route dispatch diagnostics round trip");
        String shown=restored.formatted(false,50000);
        check(shown.contains("路線診斷：stop=observation-gap; dtMs=4050, ageMs=480, movedMeters=21.3; reached=3/38; loops=40 read=420/519/560"),"saved -> restored -> formatted shows stop, gap and timing: "+shown);
        check(shown.contains("沿路派遣：候選 12／評估 7／送出 3 · 已送出派遣"),"saved report shows dispatch counts");
        check(!shown.matches("(?s).*\\d{1,3}\\.\\d{4,}.*")&&!shown.contains("\ta\t"),"route diagnostics carry no coordinates or task ids");
        FarmRunReport plain=fresh();check(!plain.formatted(false,50000).contains("路線診斷"),"no route diagnostics line when absent");
        Properties noDiag=r.properties();noDiag.remove("routeDiag");noDiag.remove("routeDispatchDiag");
        check(FarmRunReport.restore(noDiag).routeDiag.isEmpty()&&FarmRunReport.restore(noDiag).routeDispatchDiag.isEmpty(),"older reports without diagnostics still load");
        StringBuilder big=new StringBuilder();while(big.length()<3000)big.append("x");Properties huge=r.properties();huge.setProperty("routeDiag",big.toString());
        check(FarmRunReport.restore(huge).routeDiag.length()==1024,"route diagnostics bounded on load");
        huge.setProperty("routeDispatchDiag",big.toString());
        check(FarmRunReport.restore(huge).routeDispatchDiag.length()==512,"route dispatch diagnostics bounded on load");
        check(FarmRunReport.restore(huge).formatted(false,50000).length()<1024+512+450,"formatted output stays bounded with maximal diagnostics lines");
        check(restored.formatted(false,50000).contains("完成停靠 2 / 4"),"point counts separate");
        check(!restored.formatted(false,50000).contains("\ta\t"),"report hides task IDs");
        FarmRunReport route=fresh();route.route=true;route.attempted=4;route.completed=4;route.arrived=0;
        route.ended=42000;route.terminal="completed";
        FarmRunReport restoredRoute=FarmRunReport.restore(route.properties());
        check(restoredRoute.route&&restoredRoute.completed==4&&restoredRoute.arrived==0,"completed route survives durable restore without point arrivals");
        check(restoredRoute.formatted(false,50000).contains("通過路線節點 4 / 4")
                &&!restoredRoute.formatted(false,50000).contains("到點 0"),"route report does not imply point arrivals");
        Properties overRoute=route.properties();overRoute.setProperty("completed","5");
        boolean routeRefused=false;try{FarmRunReport.restore(overRoute);}catch(IllegalArgumentException e){routeRefused=true;}
        check(routeRefused,"route completion above observed nodes rejected");
        Properties overPoint=r.properties();overPoint.setProperty("completed","4");
        boolean pointRefused=false;try{FarmRunReport.restore(overPoint);}catch(IllegalArgumentException e){pointRefused=true;}
        check(pointRefused,"point completion above arrivals remains rejected");
        restored.ingest("","",60000,false);check(restored.incomplete&&restored.collected(null)==2,"missing evidence never resets known counts");
        check(restored.formatted(false,10000+FarmRunReport.DAY+1).contains("保留期"),"expired evidence explicit");
        FarmRunReport empty=fresh();empty.completed=4;empty.attempted=4;empty.arrived=4;
        empty.ingestSnapshot("DISPATCH\nREWARDS\nEND\n",22000,true);
        check(!empty.incomplete&&empty.failed()==0&&empty.tasks.isEmpty(),"empty sources and no-fruit not failures");
        FarmRunReport open=fresh();open.openWindow(21000);open.ingest(start(22000,"fruit","lost"),"",23000,true);
        check(open.tasks.isEmpty(),"unclosed crash window not attributed");
        check(open.formatted(false,23000).contains("工作中斷"),"process death not success");
        FarmRunReport partial=fresh();partial.ingestSnapshot("DISPATCH\n"+start(12000,"fruit","a")+"REWARDS\n",25000,false);
        check(partial.incomplete&&partial.tasks.isEmpty(),"truncated snapshot flagged, not fabricated");
        FarmRunReport packet=fresh();packet.ingestSnapshot("DISPATCH\n"+start(12000,"fruit","a")+"REWARDS\n"+reward(24000,"a","one-confirmed",1,0)+"END\n",25000,true);
        check(packet.collected(null)==1&&!packet.incomplete,"real marker protocol");
        packet.ingest(start(26000,"fruit","future"),reward(50000,"a","one-confirmed",1,0),25000,true);
        check(packet.tasks.size()==1&&packet.tasks.get("a").collected==24000,"future timestamps rejected");
        FarmRunReport bad=fresh();bad.ingest("malformed\n","bad\n",25000,true);check(bad.incomplete,"malformed evidence flagged");
        Properties corrupt=r.properties();corrupt.setProperty("total","-1");boolean refused=false;try{FarmRunReport.restore(corrupt);}catch(IllegalArgumentException e){refused=true;}
        check(refused,"corrupt report fail closed");
        FarmRunReport stale=fresh();stale.ingestSnapshot("DISPATCH\nMISSING\nREWARDS\nMISSING\nEND\n",25000,true);
        check(stale.incomplete&&stale.failed()==0,"missing files not zero-success proof");
        FarmRunReport seeds=fresh();seeds.ingest(start(11000,"seed","boundary-a")+start(20000,"fruit","boundary-b")+start(20001,"fruit","outside"),"",22000,true);
        check(seeds.tasks.size()==2,"closed interval endpoints inclusive");
        FarmRunReport unknown=fresh();unknown.ingest(start(12000,"gift","gift"),"",22000,true);
        check(unknown.tasks.isEmpty()&&unknown.incomplete,"unexpected farm type not silently counted");
        FarmRunReport zero=fresh();zero.ingest(start(12000,"fruit","zero").replace("\t150\t2","\t0\t2"),"",22000,true);
        check(zero.tasks.isEmpty()&&zero.incomplete,"malformed zero-duration start rejected");
        FarmRunReport capped=fresh();StringBuilder many=new StringBuilder();
        for(int i=0;i<5001;i++)many.append("12000\tcandidate\tfruit\tx\t1\t1\n");
        capped.ingestSnapshot("DISPATCH\n"+many+"REWARDS\nEND\n",22000,true);
        check(capped.incomplete,"history tail may omit run start");
        FarmRunReport oldTail=fresh();many.replace(0,5,"09000");
        oldTail.ingestSnapshot("DISPATCH\n"+many+"REWARDS\nEND\n",22000,true);
        check(!oldTail.incomplete,"tail reaching before run retains full attribution period");
        FarmRunReport stopped=fresh();stopped.attempted=1;stopped.arrived=1;stopped.terminal="stopped";stopped.ended=22000;
        check(stopped.formatted(false,22000).contains("未完成 4")&&stopped.failed()==0,"stop before complete dwell is unfinished, not dispatch failure");
        FarmRunReport truth=fresh();truth.ingest(start(12000,"fruit","x")+"13000\tstart-rpc-faulted\tfruit\tx\t1000\t0\n",reward(14000,"x","one-confirmed",1,0),22000,true);
        check(truth.collected(null)==1&&truth.failed()==0,"positive receipt wins over earlier fault");
        FarmRunReport malformed=fresh();malformed.ingestSnapshot("DISPATCH\nREWARDS\nEND\n",22000,false);
        check(malformed.incomplete,"successful-looking truncated output flagged");
        System.out.println("PASS FarmRunReport: "+checks+" evidence and persistence checks");
    }
}

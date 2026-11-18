package dev.ody.pikmincontrol;

import java.util.*;

/** An incomplete job safely closed WITHOUT starting its final planting round.
 * Never GPS/planting completion evidence. Retains the two ORIGINAL root frames,
 * exact claim identity, previous closed rounds and unused token. HOST fixtures
 * or checksum alone do not establish the actual Android publication provenance.
 */
final class EmulatorUnstartedRouteOutcome {
    static final String HEADER="safe-unstarted-v1\t";
    static final int MAX_BYTES=16384;
    static void snapshot(String raw,EmulatorDailyRunner.Binding b,String unused,long at,boolean after){
        String[] p=EmulatorOwnedPetalGateway.sections(raw,9);String pid=Integer.toString(b.pid);
        if(!(pid+"\n").equals(p[0])||!p[0].equals(p[8])||!(b.boot+"\n").equals(p[1])||!p[1].equals(p[7]))bad();
        CoreCapabilities c=CoreCapabilities.parse(ReadinessSample.Read.ok(p[2]),pid,"0.20.3",900178,at);
        if(!c.ok()||!c.identityReady()||!c.domainReady()||c.sessionEpoch!=b.sessionEpoch||
            !c.live(CoreCapabilities.CAP_PLANTING_CONTROLLER)||!c.live(CoreCapabilities.CAP_LOCATION_CONTROLLER))bad();
        String[] j=line(p[3],10);
        if(!j[0].equals("v3")||!j[1].equals(pid)||num(j[2])!=b.sessionEpoch||!j[3].equals(b.account)||
            !j[7].equals("1")||!j[9].equals("end")||num(j[6])>at||num(j[8])>num(j[6])||
            !(j[5].equals("idle")||j[5].equals("stopped-confirmed"))||
            (!uuid(j[4])&&!j[4].equals("-"))||(j[5].equals("idle")&&num(j[8])!=0)||
            (j[4].equals(unused)&&num(j[8])!=0))bad(); // never adopt a started or uncertain native round
        PlantingState planting=new PlantingState(p[4],p[0],p[8],pid,at);
        if(!planting.valid||planting.planting||planting.owned||!planting.mode.equals("off"))bad();
        RouteState route=new RouteState(p[5],pid,at,after?null:unused,"hold");
        if(!route.stopped||!p[6].equals(after?"absent\n":"held\n")||
            after&&(!route.fields[3].equals("-")||!route.fields[4].equals("off")))bad();
    }
    static String create(EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,String unused,
            String terminal,int total,int completed,long reportEnded,List<String> rounds,String before,long beforeAt,String after,long afterAt,long at){
        StringBuilder body=new StringBuilder(HEADER+EmulatorDailyRunner.base64(b.encode())+"\t"+key+"\t"+claimSha+"\t"+checkpoint+"\t"+report+"\t"+unused+
            "\t"+terminal+"\t"+total+"\t"+completed+"\t"+reportEnded+"\t"+beforeAt+"\t"+afterAt+"\t"+at+"\tend\n");
        for(String round:rounds)body.append(round);
        body.append("original-before\t").append(EmulatorDailyRunner.base64(before)).append('\n');
        body.append("original-after\t").append(EmulatorDailyRunner.base64(after)).append('\n');
        String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
        validate(wire,b,key,claimSha,checkpoint,report,rounds.isEmpty()?unused:rounds.get(0).split("\t",-1)[1],at);
        return wire;
    }
    static void validate(String wire,EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,String first,long now){
        if(wire==null||wire.length()>MAX_BYTES||wire.indexOf('\r')>=0||!wire.startsWith(HEADER))bad();
        String[] rows=wire.split("\n",-1);
        if(rows.length<5||rows.length>8||!rows[rows.length-1].isEmpty())bad();
        StringBuilder body=new StringBuilder();for(int i=0;i<rows.length-2;i++)body.append(rows[i]).append('\n');
        if(!rows[rows.length-2].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
        String[] h=rows[0].split("\t",-1);
        if(h.length!=15||!h[14].equals("end")||!b.same(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])))||
            !key.equals(h[2])||!EmulatorDailyRouteWork.routeKey(key)||!claimSha.equals(h[3])||!claimSha.matches("[0-9a-f]{64}")||
            !checkpoint.equals(h[4])||!report.equals(h[5])||!uuid(checkpoint)||!uuid(report)||!uuid(h[6])||
            !(h[7].equals("error")||h[7].equals("stopped")))bad();
        long total=num(h[8]),completed=num(h[9]),ended=num(h[10]),beforeAt=num(h[11]),afterAt=num(h[12]),at=num(h[13]);
        if(total<2||total>2000||completed>total||completed==total||beforeAt<=0||ended<beforeAt||afterAt<ended||at<afterAt||now<at)bad();
        Set<String> tokens=new HashSet<>();tokens.add(h[6]);long last=0;
        for(int i=1;i<rows.length-4;i++){
            String[] f=rows[i].split("\t",-1);if(f.length!=6||!f[0].equals("round")||!uuid(f[1])||!tokens.add(f[1])||!f[5].equals("end"))bad();
            long request=num(f[2]),started=num(f[3]),closed=num(f[4]);
            if(request<=0||request<last||started<request||closed<started||closed>beforeAt)bad();last=closed;
        }
        if(rows.length==5?(!h[6].equals(first)||completed!=0):!rows[1].split("\t",-1)[1].equals(first))bad();
        String[] a=rows[rows.length-4].split("\t",-1),z=rows[rows.length-3].split("\t",-1);
        if(a.length!=2||z.length!=2||!a[0].equals("original-before")||!z[0].equals("original-after"))bad();
        snapshot(EmulatorDailyRunner.unbase64(a[1]),b,h[6],beforeAt,false);
        snapshot(EmulatorDailyRunner.unbase64(z[1]),b,h[6],afterAt,true);
    }
    private static String[] line(String raw,int count){
        if(raw==null||raw.length()>2049||!raw.endsWith("\n")||raw.indexOf('\r')>=0||raw.indexOf('\n')!=raw.length()-1)bad();
        String[] f=raw.substring(0,raw.length()-1).split("\t",-1);if(f.length!=count)bad();return f;
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static void bad(){throw new IllegalStateException("Exact safely closed unstarted route originals required; never completion");}
}

package dev.ody.pikmincontrol;

import java.util.*;

/** Bounded correlation ledger. Inputs must come from the existing independently
 * checked native closed-round adapter, never preferences/control OFF flags. */
final class EmulatorDailyRouteClosure {
    static final String INCOMPLETE_HEADER="safe-started-v1\t";
    final EmulatorDailyRunner.Binding binding;
    final String key,claimSha,checkpoint,report;
    final List<String> rounds=new ArrayList<>();
    private final Set<String> tokens=new HashSet<>();
    private String current;
    private long requested,previousClosed;
    private boolean sealed;
    EmulatorDailyRouteClosure(EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,String token){
        if(b==null||!EmulatorDailyRouteWork.routeKey(key)||
           claimSha==null||!claimSha.matches("[0-9a-f]{64}")||!uuid(checkpoint)||!uuid(report)||!uuid(token))bad();
        binding=b;this.key=key;this.claimSha=claimSha;this.checkpoint=checkpoint;this.report=report;current=token;tokens.add(token);
    }
    void started(String token,long at){
        if(sealed||!current.equals(token)||requested!=0||at<=0||at<previousClosed)bad();requested=at;
    }
    void closed(String token,long request,String boot,long epoch,long started,long at){
        if(sealed||rounds.size()>=4||!current.equals(token)||requested==0||requested!=request||
           !binding.boot.equals(boot)||binding.sessionEpoch!=epoch||started<=0||at<request||at<previousClosed)bad();
        rounds.add("round\t"+token+"\t"+request+"\t"+started+"\t"+at+"\tend\n");
        previousClosed=at;requested=0;current="";
    }
    void next(String token){
        if(sealed||!current.isEmpty()||requested!=0||rounds.size()>=4||!uuid(token)||!tokens.add(token))bad();current=token;
    }
    boolean currentUnstarted(){return !sealed&&!current.isEmpty()&&requested==0;}
    String unusedToken(){if(!currentUnstarted())bad();return current;}
    String finish(String terminal,int total,int completed,long ended){
        return finish(terminal,total,completed,ended,false);
    }
    /** Started but failed: all accepted native rounds must still be closed.
     * A distinct wire is never route success or permission to replay this key. */
    String finishIncomplete(String terminal,int total,int completed,long ended){
        return finish(terminal,total,completed,ended,true);
    }
    private String finish(String terminal,int total,int completed,long ended,boolean incomplete){
        if(sealed||!current.isEmpty()||requested!=0||rounds.isEmpty()||ended<previousClosed||total<2||total>2000||completed<0||completed>total||
           (incomplete?(!terminal.equals("error")||completed==total):(!(terminal.equals("completed")||terminal.equals("stopped"))||terminal.equals("completed")&&completed!=total)))bad();
        StringBuilder body=new StringBuilder((incomplete?INCOMPLETE_HEADER:"v1\t")+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+claimSha+"\t"+checkpoint+"\t"+report+"\t"+terminal+"\t"+total+"\t"+completed+"\t"+ended+"\tend\n");
        for(String round:rounds)body.append(round);
        String wire=body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";
        validate(wire,binding,key,claimSha,checkpoint,report,incomplete);sealed=true;return wire;
    }
    static void validate(String wire,EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report){
        validate(wire,b,key,claimSha,checkpoint,report,false);
    }
    private static void validate(String wire,EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,boolean incomplete){
        if(wire==null||wire.length()>4096||wire.indexOf('\r')>=0)bad();
        String[] lines=wire.split("\n",-1);
        if(lines.length<4||lines.length>7||!lines[lines.length-1].isEmpty())bad();
        StringBuilder body=new StringBuilder();for(int i=0;i<lines.length-2;i++)body.append(lines[i]).append('\n');
        if(!lines[lines.length-2].equals("sha256\t"+EmulatorDailyRunner.digest(body.toString())))bad();
        String[] h=lines[0].split("\t",-1);
        if(h.length!=11||!h[0].equals(incomplete?"safe-started-v1":"v1")||!h[10].equals("end")||!b.same(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(h[1])))||
           !key.equals(h[2])||!claimSha.equals(h[3])||!checkpoint.equals(h[4])||!report.equals(h[5]))bad();
        long total=num(h[7]),completed=num(h[8]),ended=num(h[9]);
        if(total<2||total>2000||completed>total||(incomplete?(!h[6].equals("error")||completed==total):(!(h[6].equals("completed")||h[6].equals("stopped"))||h[6].equals("completed")&&completed!=total)))bad();
        Set<String> tokens=new HashSet<>();long last=0;
        for(int i=1;i<lines.length-2;i++){
            String[] f=lines[i].split("\t",-1);
            if(f.length!=6||!f[0].equals("round")||!f[5].equals("end")||!uuid(f[1])||!tokens.add(f[1]))bad();
            long request=num(f[2]),started=num(f[3]),closed=num(f[4]);
            // Game PlantingStartedTimeMs is an exact correlation value, not
            // Android CLOCK_REALTIME. Keep local request/close ordering and
            // positive game identity; the native adapter checks exact binding.
            if(request==0||request<last||started<=0||closed<request||closed>ended)bad();last=closed;
        }
    }
    static void validateClaim(String wire,EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,String firstToken){
        validate(wire,b,key,claimSha,checkpoint,report);
        if(!uuid(firstToken)||!wire.split("\n",-1)[1].split("\t",-1)[1].equals(firstToken))bad();
    }
    static void validateIncompleteClaim(String wire,EmulatorDailyRunner.Binding b,String key,String claimSha,String checkpoint,String report,String firstToken,long now){
        validate(wire,b,key,claimSha,checkpoint,report,true);
        if(!uuid(firstToken)||!wire.split("\n",-1)[1].split("\t",-1)[1].equals(firstToken)||
           num(wire.split("\n",-1)[0].split("\t",-1)[9])>now)bad();
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static boolean uuid(String s){return s!=null&&s.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}");}
    private static void bad(){throw new IllegalStateException("Complete exact closed round chain required; never infer completion from absence");}
}

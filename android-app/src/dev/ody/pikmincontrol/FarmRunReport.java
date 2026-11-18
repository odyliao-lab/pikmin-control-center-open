package dev.ody.pikmincontrol;

import java.util.*;
import java.text.SimpleDateFormat;

/** Durable evidence, not game authority. No fruit observed is never a point failure. */
final class FarmRunReport {
    static final long DAY = 86400000L;
    final String id;
    final long started;
    final int total, dwell;
    long ended, lastRead;
    int attempted, arrived, completed, skipped;
    String terminal = "running";
    boolean incomplete;
    boolean route;
    /** Route diagnostics (bounded, no coordinates/accounts): stop reason with gap/age/move detail and loop timing p50/p95/max. */
    String routeDiag = "";
    String routeDispatchDiag = "";
    final List<long[]> windows = new ArrayList<>();
    final Map<String, Task> tasks = new LinkedHashMap<>();
    static final class Task {
        String kind; long first, latest, collected, failed;
        Task(String kind, long at) { this.kind=kind; first=latest=at; }
    }
    FarmRunReport(String id, long started, int total, int dwell) {
        this.id=id; this.started=started; this.total=total; this.dwell=dwell;
    }
    void openWindow(long at) { windows.add(new long[]{at, 0}); }
    void closeWindow(long at) {
        if (!windows.isEmpty()) { long[] w=windows.get(windows.size()-1); if(w[1]==0)w[1]=Math.max(w[0],at); }
    }
    boolean owned(long at) {
        // An unclosed window after process death must not capture later unrelated work.
        for(long[] w:windows)if(w[1]>0 && at>=w[0] && at<=w[1])return true;
        return false;
    }
    void ingestSnapshot(String raw, long now, boolean fullRead) {
        String text=raw.replace("\r","");int split=text.indexOf("REWARDS\n",9),end=text.indexOf("END\n",split+8);
        if(!text.startsWith("DISPATCH\n")||split<9||end<split+8||text.charAt(split-1)!='\n'||text.charAt(end-1)!='\n'){incomplete=true;return;}
        String dispatch=text.substring(9,split),rewards=text.substring(split+8,end);
        boolean complete=fullRead&&!text.contains("\nMISSING\n")&&coversStart(dispatch)&&coversStart(rewards);
        ingest(dispatch,rewards,now,complete);
    }
    private boolean coversStart(String rows){
        String[] lines=rows.split("\n");
        return lines.length<5001 || (number(lines[0].split("\t",-1)[0])>0 && number(lines[0].split("\t",-1)[0])<started);
    }
    void ingest(String dispatch, String rewards, long now, boolean fullRead) {
        if (!fullRead) incomplete=true;
        lastRead=Math.max(lastRead, now);
        String[] rows=dispatch.replace("\r", "").split("\n");
        for(String row:rows) {
            String[] f=row.split("\t",-1); if(f.length!=6){if(!row.trim().isEmpty())incomplete=true;continue;}
            long at=number(f[0]);
            if(at<=0||at>now||!"start-requested".equals(f[1])||!owned(at))continue;
            if(!("fruit".equals(f[2])||"seed".equals(f[2]))||f[3].isEmpty()||f[3].length()>256||number(f[4])<=0||number(f[5])<=0){incomplete=true;continue;}
            Task task=tasks.get(f[3]);
            if(task==null){if(tasks.size()>=10000){incomplete=true;continue;}tasks.put(f[3],new Task(f[2],at));}
            else {task.first=Math.min(task.first,at);task.latest=Math.max(task.latest,at);}
        }
        for(String row:rows) {
            String[] f=row.split("\t",-1); if(f.length!=6)continue;
            Task t=tasks.get(f[3]);long at=number(f[0]);
            long elapsed=number(f[4]);
            if(t!=null&&at>=t.latest&&at<=now&&"start-rpc-faulted".equals(f[1])&&elapsed>=0
                    &&Math.abs((at-elapsed)-t.latest)<=2000)t.failed=Math.max(t.failed,at);
            // inventory-confirmed means dispatched, NOT a reward receipt.
        }
        for(String row:rewards.replace("\r", "").split("\n")) {
            String[] f=row.split("\t",-1); if(f.length<9){if(!row.trim().isEmpty())incomplete=true;continue;}
            Task t=tasks.get(f[2]);long at=number(f[0]);
            if(t!=null&&at>=t.first&&at<=now&&("batch-confirmed".equals(f[1])||"one-confirmed".equals(f[1]))
                    &&number(f[4])>0&&"0".equals(f[5])) t.collected=Math.max(t.collected,at);
        }
    }
    int collected(String kind) {int n=0;for(Task t:tasks.values())if(t.collected>0&&(kind==null||kind.equals(t.kind)))n++;return n;}
    int failed(){int n=0;for(Task t:tasks.values())if(t.collected==0&&t.failed>=t.latest)n++;return n;}
    int pending(){return tasks.size()-collected(null)-failed();}
    String counts(){return "已領取 "+collected(null)+" · 待確認 "+pending()+" · 派遣失敗 "+failed();}
    String formatted(boolean live, long now) {
        String state="completed".equals(terminal)?"已結束":"stopped".equals(terminal)?"已停止":
                "running".equals(terminal)?(live?"執行中":"工作中斷 · 無結束回報"):"異常結束 · 請查看工作紀錄";
        long duration=Math.max(0,(ended>0?ended:live?now:Math.max(started,lastRead))-started)/1000;
        return new SimpleDateFormat("MM/dd HH:mm",Locale.TAIWAN).format(new Date(started))+" · "+state
                +"\n"+(route?"通過路線節點 ":"完成停靠 ")+completed+" / "+total+" · 略過 "+skipped+" · 未完成 "+Math.max(0,total-completed-skipped)
                +"\n"+(route?"":"到點 "+arrived+" · ")+"已嘗試 "+attempted+" · "+duration/60+"分"+duration%60+"秒"
                +"\n送出 "+tasks.size()+" 筆 · "+counts()
                +"\n［果］"+collected("fruit")+" · ［盆］"+collected("seed")+"（已領取）"
                +"\n"+(incomplete?"⚠ 紀錄不完整，數字僅為已取得證據。":"統計依本次派遣與原生領取回報，非花點產果率。")
                +(now-started>DAY?"\n已超過原生24小時保留期，未確認項目不改判失敗。":"")
                +("running".equals(terminal)&&!live?"\n中斷點可能缺少證據，不自動恢復飛行。":"")
                // Route diagnostics (stop code, gap/age/move, loop timing p50/p95/max) reach the user through this same
                // formatter: the work-record page and the diagnostics export both call it. Bounded, no coordinates/accounts.
                +(routeDiag.isEmpty()?"":"\n路線診斷："+routeDiag)
                +(routeDispatchDiag.isEmpty()?"":"\n沿路派遣："+routeDispatchDiag);
    }
    Properties properties(){
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("id",id);p.setProperty("started",""+started);
        p.setProperty("total",""+total);p.setProperty("dwell",""+dwell);p.setProperty("ended",""+ended);p.setProperty("lastRead",""+lastRead);
        p.setProperty("attempted",""+attempted);p.setProperty("arrived",""+arrived);p.setProperty("completed",""+completed);p.setProperty("skipped",""+skipped);
        p.setProperty("terminal",terminal);p.setProperty("incomplete",""+incomplete);p.setProperty("windows",""+windows.size());p.setProperty("route",""+route);
        if(!routeDiag.isEmpty())p.setProperty("routeDiag",routeDiag.length()>1024?routeDiag.substring(0,1024):routeDiag);
        if(!routeDispatchDiag.isEmpty())p.setProperty("routeDispatchDiag",routeDispatchDiag.length()>512?routeDispatchDiag.substring(0,512):routeDispatchDiag);
        for(int i=0;i<windows.size();i++)p.setProperty("w."+i,windows.get(i)[0]+","+windows.get(i)[1]);
        p.setProperty("tasks",""+tasks.size());int i=0;
        for(Map.Entry<String,Task> e:tasks.entrySet()){Task t=e.getValue();String key="t."+(i++);p.setProperty(key+".id",e.getKey());
            p.setProperty(key+".v",t.kind+","+t.first+","+t.latest+","+t.collected+","+t.failed);}
        return p;
    }
    static FarmRunReport restore(Properties p){
        if(!"1".equals(p.getProperty("schema")))throw new IllegalArgumentException("unknown report schema");
        String id=p.getProperty("id","");long start=number(p.getProperty("started",""));
        if(!id.matches("[0-9a-f-]{36}")||start<=0)throw new IllegalArgumentException("invalid report identity");
        FarmRunReport r=new FarmRunReport(id,start,integer(p,"total",20000),integer(p,"dwell",10));
        r.ended=number(p.getProperty("ended","0"));r.lastRead=number(p.getProperty("lastRead","0"));
        if((r.ended!=0&&r.ended<start)||(r.lastRead!=0&&r.lastRead<start))throw new IllegalArgumentException("report timestamps");
        r.route=Boolean.parseBoolean(p.getProperty("route","false"));
        r.attempted=integer(p,"attempted",r.total);r.arrived=integer(p,"arrived",r.attempted);
        // A route marks observed nodes as completed without a per-flower arrival;
        // point mode still requires every completed stop to have arrived first.
        r.completed=integer(p,"completed",r.route?r.attempted:r.arrived);r.skipped=integer(p,"skipped",r.attempted-r.completed);
        r.terminal=p.getProperty("terminal","unknown");r.incomplete=Boolean.parseBoolean(p.getProperty("incomplete","true"));
        r.routeDiag=p.getProperty("routeDiag","");if(r.routeDiag.length()>1024)r.routeDiag=r.routeDiag.substring(0,1024);
        r.routeDispatchDiag=p.getProperty("routeDispatchDiag","");if(r.routeDispatchDiag.length()>512)r.routeDispatchDiag=r.routeDispatchDiag.substring(0,512);
        for(int i=0,n=integer(p,"windows",20000);i<n;i++){
            String[] w=p.getProperty("w."+i,"").split(",");if(w.length!=2)throw new IllegalArgumentException("window");
            long from=number(w[0]),to=number(w[1]);if(from<start||(to!=0&&to<from))throw new IllegalArgumentException("window timestamps");
            r.windows.add(new long[]{from,to});
        }
        for(int i=0,n=integer(p,"tasks",10000);i<n;i++){
            String key="t."+i,idTask=p.getProperty(key+".id","");String[] v=p.getProperty(key+".v","").split(",");
            if(v.length!=5||idTask.isEmpty()||idTask.length()>256||!(v[0].equals("fruit")||v[0].equals("seed")))throw new IllegalArgumentException("task");
            Task t=new Task(v[0],number(v[1]));t.latest=number(v[2]);t.collected=number(v[3]);t.failed=number(v[4]);
            if(t.first<start||t.latest<t.first||(t.collected!=0&&t.collected<t.first)||(t.failed!=0&&t.failed<t.first))throw new IllegalArgumentException("task timestamps");
            r.tasks.put(idTask,t);
        }return r;
    }
    private static int integer(Properties p,String key,int max){long n=number(p.getProperty(key,""));if(n<0||n>max)throw new IllegalArgumentException(key);return (int)n;}
    private static long number(String s){try{return Long.parseLong(s);}catch(Exception e){return -1;}}
}

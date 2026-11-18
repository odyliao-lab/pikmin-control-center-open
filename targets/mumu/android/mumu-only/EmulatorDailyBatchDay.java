package dev.ody.pikmincontrol;
import java.util.*;

/** Reproducible rollup over every retained batch in this day. Candidate absence
 * never resolves an old todo; any admission prevents dispatch eligibility. */
final class EmulatorDailyBatchDay {
    final EmulatorDailyRunner.Binding binding;
    final String key;
    final List<String> rows,sourceShas;
    final List<DispatchProbeGateway.Candidate> eligible;
    final int collected,unresolved,rejected,pending,reappeared;
    final boolean clean;
    final long scanMs;
    EmulatorDailyBatchDay(EmulatorDailyRunner.Binding held,String key,List<EmulatorDailyBatchOutcome> batches,EmulatorDailyBatchScan.Snapshot fresh){
        EmulatorDevelopmentBatch.requireBatchKey(held,key);int count=Integer.parseInt(key.substring(key.length()-1));
        if(batches==null||batches.size()!=count||fresh==null||!held.same(fresh.binding))bad();
        LinkedHashMap<String,String[]> all=new LinkedHashMap<>();List<String> sources=new ArrayList<>();boolean safe=true;long previousEnd=0;
        for(int index=0;index<count;index++){
            EmulatorDailyBatchOutcome o=batches.get(index);
            if(o==null||!held.same(o.inventory.binding)||!o.inventory.key.equals(EmulatorDevelopmentBatch.keyAt(key,index+1))||o.inventory.created<previousEnd||fresh.scanMs<o.ended||
                    index>0&&!EmulatorDevelopmentBatch.sameWindow(batches.get(0).inventory.developmentWindow,o.inventory.developmentWindow))bad();
            previousEnd=o.ended;safe&=!o.needsReview();sources.add(EmulatorDailyRunner.digest(o.encode()));
            for(String raw:o.rows){
                String[] row=raw.split("\t",-1);
                if(row[2].startsWith("prior-")){row[2]=row[2].substring(6);row[5]=row[2].equals("collected")||row[2].equals("observed-collected")?"-":"gift-unavailable";}
                if(row[2].equals("observed-collected"))row[2]="collected"; // Observation proof is NOT a dispatch Admission.
                String[] prior=all.get(row[0]);
                if(prior!=null){
                    if(!prior[1].equals(row[1]))bad();
                    if(!prior[3].equals("-")){
                        if(!row[3].equals("-")&&!row[3].equals(prior[3]))bad(); // Same-day admission cannot be replaced.
                        if(!row[3].equals("-")&&!Arrays.equals(row,prior))bad(); // No conflicting terminal disposition.
                        continue; // Non-admission in a later scan cannot erase an earlier claim/todo.
                    }
                }
                all.put(row[0],row);
            }
        }
        Set<String> seen=new HashSet<>();List<DispatchProbeGateway.Candidate> queue=new ArrayList<>();int repeated=0;
        if(fresh.candidates==null||fresh.candidates.size()>512)bad();if(!fresh.candidates.isEmpty())EmulatorDailyBatchWork.queueSha(fresh.candidates);
        for(DispatchProbeGateway.Candidate c:fresh.candidates){
            if(!seen.add(c.taskId))bad();String[] prior=all.get(c.taskId);
            if(prior!=null&&!prior[1].equals(c.kind))bad();
            if(prior==null){prior=new String[]{c.taskId,c.kind,c.latitude==0&&c.longitude==0?"unknown-location":"not-authorized","-","-",c.latitude==0&&c.longitude==0?"unknown-location":"not-authorized"};all.put(c.taskId,prior);}
            if(prior[2].equals("collected"))repeated++;
            if(prior[3].equals("-")&&!(c.latitude==0&&c.longitude==0))queue.add(c);
        }
        int got=0,unknown=0,notSent=0,todo=0;List<String> out=new ArrayList<>();
        for(String[] row:all.values()){
            if(row[2].equals("collected"))got++;
            else if(row[2].equals("authorized-unresolved"))unknown++;
            else if(row[2].equals("confirmed-not-sent"))notSent++;
            else todo++;
            out.add(String.join("\t",row));
        }
        binding=held;this.key=key;rows=Collections.unmodifiableList(out);sourceShas=Collections.unmodifiableList(sources);eligible=Collections.unmodifiableList(queue);
        collected=got;unresolved=unknown;rejected=notSent;pending=todo;reappeared=repeated;clean=safe;scanMs=fresh.scanMs;
    }
    boolean allCollected(){return clean&&unresolved==0&&rejected==0&&pending==0&&reappeared==0&&eligible.isEmpty();}
    EmulatorDailyRunner.Evidence evidence(){return !clean||unresolved>0?EmulatorDailyRunner.Evidence.UNKNOWN:allCollected()?EmulatorDailyRunner.Evidence.COMPLETE_ALL_CLAIMS:EmulatorDailyRunner.Evidence.COMPLETE;}
    String summary(){return "本日批次去重候選 "+rows.size()+"；已領回 "+collected+"；授權結果待核對 "+unresolved+"；確認未派出 "+rejected+"；其餘待辦 "+pending+"；已領回仍出現在掃描 "+reappeared;}
    String encode(){StringBuilder body=new StringBuilder("v1\t"+key+"\t"+EmulatorDailyRunner.digest(binding.encode())+"\t"+scanMs+"\t"+clean+"\t"+rows.size()+"\n");for(String source:sourceShas)body.append("source\t").append(source).append('\n');for(String row:rows)body.append(row).append('\n');return body+"sha256\t"+EmulatorDailyRunner.digest(body.toString())+"\n";}
    private static void bad(){throw new IllegalArgumentException("Every exact same-day batch required; no dropped todo or admission replay");}
}

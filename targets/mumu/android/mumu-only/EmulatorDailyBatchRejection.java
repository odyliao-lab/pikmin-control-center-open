package dev.ody.pikmincontrol;

import android.content.Context;
import java.util.*;

/** Exact admitted gift proven NOT sent after stopping the native gate. This is
 * a pending task disposition, never a collection receipt or retry authority. */
final class EmulatorDailyBatchRejection {
    final EmulatorDailyBatchReceipt.Admission admission;
    final long stoppedAt,observedAt,rejectedAt,availabilityAt;
    final String historySha,rewardsSha,availabilitySha;
    EmulatorDailyBatchRejection(EmulatorDailyBatchReceipt.Admission a,long stop,long observed,long rejected,long availability,String history,String rewards,String available){
        if(a==null||!a.kind.equals("gift")||stop<a.at||observed<stop||availability<=stop||availability>observed||observed-availability>12000||rejected<a.at||rejected>stop||
           history==null||!history.matches("[a-f0-9]{64}")||rewards==null||!rewards.matches("[a-f0-9]{64}")||available==null||!available.matches("[a-f0-9]{64}"))bad();
        admission=a;stoppedAt=stop;observedAt=observed;rejectedAt=rejected;availabilityAt=availability;historySha=history;rewardsSha=rewards;availabilitySha=available;
    }
    String encode(){String body="v1\t"+EmulatorDailyRunner.base64(admission.encode())+"\t"+stoppedAt+"\t"+observedAt+"\t"+rejectedAt+"\t"+availabilityAt+"\t"+historySha+"\t"+rewardsSha+"\t"+availabilitySha+"\tconfirmed-not-sent\tend\n";return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    static EmulatorDailyBatchRejection decode(String wire,EmulatorDailyBatchReceipt.Admission expected){
        if(wire==null||wire.length()>4096||wire.indexOf('\r')>=0)bad();String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] f=rows[0].split("\t",-1);if(f.length!=11||!f[0].equals("v1")||!f[9].equals("confirmed-not-sent")||!f[10].equals("end"))bad();
        EmulatorDailyBatchReceipt.Admission a=EmulatorDailyBatchReceipt.Admission.decode(EmulatorDailyRunner.unbase64(f[1]));
        if(expected==null||!a.encode().equals(expected.encode()))bad();
        EmulatorDailyBatchRejection r=new EmulatorDailyBatchRejection(a,num(f[2]),num(f[3]),num(f[4]),num(f[5]),f[6],f[7],f[8]);
        if(!r.encode().equals(wire))bad();return r;
    }
    static EmulatorDailyBatchRejection read(Context c,EmulatorDailyBatchReceipt.Admission a,long stoppedAt)throws Exception {
        EmulatorDailyRouteWork.requireIdentity(c,a.binding);
        String[] p=EmulatorOwnedPetalGateway.sections(GpsModuleGateway.farmRead(command()),11);
        EmulatorDailyBatchRejection r=decodeSample(p,a,stoppedAt,System.currentTimeMillis());
        EmulatorDailyRouteWork.requireIdentity(c,a.binding);return r;
    }
    static EmulatorDailyBatchRejection decodeSample(String[] p,EmulatorDailyBatchReceipt.Admission a,long stopped,long now){
        if(a==null||!a.kind.equals("gift")||p==null||p.length!=11||stopped<a.at||now<stopped)bad();
        String pid=Integer.toString(a.binding.pid),id=pid+"\n"+a.binding.boot+"\n";
        if(!id.equals(p[0])||!id.equals(p[10])||!a.binding.account.equals(SessionIdentity.parse(p[1],pid,pid,now))||!a.binding.account.equals(SessionIdentity.parse(p[9],pid,pid,now))||!"off\n".equals(p[3])||!"off\n".equals(p[7]))bad();
        for(int index:new int[]{2,8}){
            CoreCapabilities caps=EmulatorDailyCore.capabilities(p[index],a.binding,now);
            if(!caps.ok()||!caps.identityReady()||!caps.domainReady()||!caps.stepOk("return.task_list_time")||caps.sessionEpoch!=a.binding.sessionEpoch)bad();
        }
        String availability=EmulatorDailyBatchScan.unpack(p[4],262144),history=EmulatorDailyBatchScan.unpack(p[5],262144),rewards=EmulatorDailyBatchScan.unpack(p[6],262144);
        // Reject truncation/normalization before using the legacy preflight parser.
        String[] availabilityRows=asciiRows(availability,514);if(availabilityRows.length<2)bad();
        String[] header=availabilityRows[0].split("\t",-1),footer=availabilityRows[availabilityRows.length-1].split("\t",-1);
        if(header.length!=3||!header[0].equals("v1")||!header[2].equals(pid)||num(header[1])<=0||footer.length!=2||!footer[0].equals("end")||num(footer[1])!=availabilityRows.length-2)bad();
        Set<String> ids=new HashSet<>();
        for(int i=1;i<availabilityRows.length-1;i++){
            String[] row=availabilityRows[i].split("\t",-1);
            if(row.length!=7||!row[0].matches("[A-Za-z0-9_-]{1,160}")||!ids.add(row[0])||!Arrays.asList("seed","fruit","gift").contains(row[1]))bad();
            // Native owner status is signed: -1 means not projected, including
            // ordinary seed/fruit rows. It is not a negative eligible count.
            // Accepting its framing does not make UNKNOWN a blocked status;
            // exact stopped-gate rejection/history/identity checks below remain.
            status(row[5]);num(row[6]);
        }
        DispatchPreflight.Observation after=DispatchPreflight.parse(availability,pid,a.id,a.kind,now);
        if(after==null||after.at<=stopped)return null;
        long rejected=0;boolean covered=false;
        for(String row:asciiRows(history,200)){
            String[] f=row.split("\t",-1);if(f.length!=6)bad();long at=num(f[0]);
            // The batch producer records a removed task with kind=unknown.
            // Only this exact native event/zero payload has that wire shape.
            boolean absentUnknown=f[1].equals("inventory-confirmed-absent")&&f[2].equals("unknown")&&f[4].equals("0")&&f[5].equals("0");
            if(at<=0||at>now||!f[1].matches("[a-z0-9-]+")||!(Arrays.asList("seed","fruit","gift").contains(f[2])||absentUnknown)||!f[3].matches("[A-Za-z0-9_-]{1,160}"))bad();
            long duration=Long.parseLong(f[4]);if(!Long.toString(duration).equals(f[4]))bad();num(f[5]);
            if(at<=a.at)covered=true;
            if(!f[3].equals(a.id)||at<a.at)continue;
            if(f[1].startsWith("start-")||f[1].startsWith("inventory-confirmed")||f[1].contains("fault"))return null;
            if(!f[2].equals(a.kind))bad();
            if(f[1].equals("gift-pikmin-unavailable")&&at<=stopped)rejected=Math.max(rejected,at);
        }
        // Collection validation is stricter than an absence-of-effects proof:
        // even a partial/discard/unknown-mode confirmation forbids NOT-SENT.
        EmulatorDailyBatchReceipt.find(rewards,a,now); // Validate the full reward framing/time first.
        for(String row:asciiRows(rewards,200)){
            String[] f=row.split("\t",-1);if(f.length!=9)bad();
            if(f[2].equals(a.id)&&num(f[0])>=a.at&&(f[1].equals("one-confirmed")||f[1].equals("batch-confirmed")))return null;
        }
        if(!covered||rejected==0)return null;
        return new EmulatorDailyBatchRejection(a,stopped,now,rejected,after.at,EmulatorDailyRunner.digest(history),EmulatorDailyRunner.digest(rewards),EmulatorDailyRunner.digest(availability));
    }
    static String command(){
        String id="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        String stopped="test ! -L /data/local/tmp/pikmin-dispatch-mode.txt && test \"$(cat /data/local/tmp/pikmin-dispatch-mode.txt)\" = off && test ! -e /data/local/tmp/pikmin-dispatch-target.txt && test ! -L /data/local/tmp/pikmin-dispatch-target.txt && test ! -e /data/local/tmp/pikmin-dispatch-ready.tsv && test ! -L /data/local/tmp/pikmin-dispatch-ready.tsv && printf 'off\\n'";
        return ProbeSnapshot.command(id,EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),stopped,
            EmulatorDailyBatchReceipt.historyCommand("dispatch_availability.tsv",514),EmulatorDailyBatchReceipt.historyCommand("dispatch_history.tsv",200),EmulatorDailyBatchReceipt.historyCommand(),stopped,
            EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),id);
    }
    private static String[] asciiRows(String raw,int max){
        if(raw==null||raw.indexOf('\r')>=0||(!raw.isEmpty()&&!raw.endsWith("\n")))bad();
        if(raw.isEmpty())return new String[0];String[] rows=raw.substring(0,raw.length()-1).split("\n",-1);if(rows.length>max)bad();
        for(String row:rows){if(row.isEmpty())bad();for(int i=0;i<row.length();i++){char v=row.charAt(i);if(v!='\t'&&(v<32||v>126))bad();}}
        return rows;
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static int status(String s){int n=Integer.parseInt(s);if(n< -1||!Integer.toString(n).equals(s))bad();return n;}
    private static void bad(){throw new IllegalArgumentException("Exact stopped-gate gift rejection required; retain unresolved admission");}
}

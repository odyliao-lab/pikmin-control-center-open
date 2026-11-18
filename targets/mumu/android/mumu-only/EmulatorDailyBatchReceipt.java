package dev.ody.pikmincontrol;

import android.content.Context;
import java.util.*;

/** Exact task receipt after this daily authorization. Read-only native history;
 * no return request, missing-task inference or cumulative-count attribution. */
final class EmulatorDailyBatchReceipt {
    static final int MAX_HISTORY=262144;
    static final class Admission {
        final EmulatorDailyRunner.Binding binding;
        final String key,checkpoint,claimSha,id,kind;
        final long at;
        final EmulatorDevelopmentBatch.Window developmentWindow;
        Admission(EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long at) {
            this(b,key,cp,claim,id,kind,at,null);
        }
        static Admission development(EmulatorDailyRunner.Binding b,EmulatorDevelopmentBatch.Window window,String cp,String claim,String id,String kind,long at){
            if(window==null)bad();return new Admission(b,window.key,cp,claim,id,kind,at,window);
        }
        private Admission(EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long at,EmulatorDevelopmentBatch.Window window) {
            EmulatorDevelopmentBatch.requireBatchKey(b,key);
            java.time.ZonedDateTime time=java.time.Instant.ofEpochMilli(at).atZone(EmulatorDailyPlan.ZONE);
            int minute=time.getHour()*60+time.getMinute();
            if(cp==null||!cp.matches("[a-f0-9-]{36}")||claim==null||!claim.matches("[a-f0-9]{64}")||id==null||!id.matches("[A-Za-z0-9_-]{1,160}")||
               !Arrays.asList("seed","fruit","gift").contains(kind)||at<=0||
               (window==null?(EmulatorDevelopmentBatch.development(key)||!EmulatorDailyPlan.batchWindow(key,at)):(!window.key.equals(key)||!window.open(at))))bad();
            binding=b;this.key=key;checkpoint=cp;claimSha=claim;this.id=id;this.kind=kind;this.at=at;
            developmentWindow=window;
        }
        String encode(){return checked((developmentWindow==null?"v1":"v2-development")+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+checkpoint+"\t"+claimSha+"\t"+id+"\t"+kind+"\t"+at+
                (developmentWindow==null?"":"\t"+developmentWindow.created+"\t"+developmentWindow.deadline)+"\tintent-not-confirmed\tend\n");}
        static Admission decode(String wire) {
            String[] f=body(wire).split("\t",-1);
            boolean dev=f.length==12&&f[0].equals("v2-development");
            if(!(dev||f.length==10&&f[0].equals("v1"))||!f[f.length-2].equals("intent-not-confirmed")||!f[f.length-1].equals("end"))bad();
            EmulatorDevelopmentBatch.Window window=dev?new EmulatorDevelopmentBatch.Window(f[2],num(f[8]),num(f[9])):null;
            Admission a=new Admission(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),f[2],f[3],f[4],f[5],f[6],num(f[7]),window);
            if(!wire.equals(a.encode()))bad();return a;
        }
    }
    final Admission admission;
    final long collectedAt;
    final String event,historySha,rewardSha;
    EmulatorDailyBatchReceipt(Admission a,long at,String event,String historySha,String rewardSha) {
        if(a==null||at<a.at||!Arrays.asList("one-confirmed","batch-confirmed").contains(event)||historySha==null||!historySha.matches("[a-f0-9]{64}")||rewardSha==null||!rewardSha.matches("[a-f0-9]{64}"))bad();
        admission=a;collectedAt=at;this.event=event;this.historySha=historySha;this.rewardSha=rewardSha;
    }
    String encode(){return checked("v1\t"+EmulatorDailyRunner.base64(admission.encode())+"\t"+collectedAt+"\t"+event+"\t"+historySha+"\t"+rewardSha+"\tcollected\tend\n");}
    static EmulatorDailyBatchReceipt decode(String wire,Admission expected) {
        String[] f=body(wire).split("\t",-1);
        if(f.length!=8||!f[0].equals("v1")||!f[6].equals("collected")||!f[7].equals("end"))bad();
        Admission a=Admission.decode(EmulatorDailyRunner.unbase64(f[1]));
        if(expected==null||!expected.encode().equals(a.encode()))bad();
        EmulatorDailyBatchReceipt r=new EmulatorDailyBatchReceipt(a,num(f[2]),f[3],f[4],f[5]);
        if(!wire.equals(r.encode()))bad();return r;
    }
    static EmulatorDailyBatchReceipt find(String history,Admission a,long now) {
        if(a==null)bad();Reward reward=findReward(history,a.id,a.at,now);
        return reward==null?null:new EmulatorDailyBatchReceipt(a,reward.at,reward.event,reward.historySha,reward.rewardSha);
    }
    static final class Reward {
        final long at;final String event,historySha,rewardSha;
        Reward(long at,String event,String history,String reward){this.at=at;this.event=event;historySha=history;rewardSha=reward;}
    }
    /** Evidence parser only, deliberately has no Admission parameter or effect. */
    static Reward findReward(String history,String id,long notBefore,long now) {
        if(id==null||!id.matches("[A-Za-z0-9_-]{1,160}")||notBefore<=0||now<notBefore||history==null||history.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_HISTORY||history.indexOf('\r')>=0||(!history.isEmpty()&&!history.endsWith("\n")))bad();
        if(history.isEmpty())return null;
        String[] rows=history.substring(0,history.length()-1).split("\n",-1);if(rows.length>200)bad();
        Reward found=null;
        for(String row:rows){
            String[] f=row.split("\t",-1);if(f.length!=9)bad();
            for(int i=0;i<row.length();i++){char c=row.charAt(i);if(c!='\t'&&(c<32||c>126))bad();}
            long at=num(f[0]);if(at<=0||at>now)bad();
            if(!id.equals(f[2])||at<notBefore||!Arrays.asList("one-confirmed","batch-confirmed").contains(f[1]))continue;
            if(num(f[3])>512||num(f[4])==0||!f[5].equals("0")||!f[6].equals("keep")||!Arrays.asList("one","batch","all").contains(f[7])||f[8].isEmpty())continue;
            if(found==null||at<found.at)found=new Reward(at,f[1],EmulatorDailyRunner.digest(row+"\n"),EmulatorDailyRunner.digest(f[8]));
        }
        return found;
    }
    static EmulatorDailyBatchReceipt read(Context c,Admission a)throws Exception {
        EmulatorDailyRouteWork.requireIdentity(c,a.binding);
        String[] p=EmulatorOwnedPetalGateway.sections(GpsModuleGateway.farmRead(command()),7);
        EmulatorDailyBatchReceipt result=decodeSample(p,a,System.currentTimeMillis());
        EmulatorDailyRouteWork.requireIdentity(c,a.binding);return result;
    }
    static EmulatorDailyBatchReceipt decodeSample(String[] p,Admission a,long now) {
        if(a==null||p==null||p.length!=7)bad();
        String pid=Integer.toString(a.binding.pid),identity=pid+"\n"+a.binding.boot+"\n";
        if(!identity.equals(p[0])||!identity.equals(p[6])||!a.binding.account.equals(SessionIdentity.parse(p[1],pid,pid,now))||!a.binding.account.equals(SessionIdentity.parse(p[5],pid,pid,now)))bad();
        for(int i:new int[]{2,4}){
            CoreCapabilities caps=EmulatorDailyCore.capabilities(p[i],a.binding,now);
            if(!caps.ok()||!caps.identityReady()||!caps.domainReady()||!caps.stepOk("return.task_list_time")||caps.sessionEpoch!=a.binding.sessionEpoch)bad();
        }
        return find(EmulatorDailyBatchScan.unpack(p[3],MAX_HISTORY),a,now);
    }
    static String command() {
        String identity="pidof com.nianticlabs.pikmin && cat /proc/sys/kernel/random/boot_id";
        return ProbeSnapshot.command(identity,EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),
            historyCommand(),EmulatorRunnerCounterGateway.bounded("core_capabilities.tsv",32768),EmulatorRunnerCounterGateway.bounded("automation_session.tsv",2048),identity);
    }
    static String historyCommand() {
        return historyCommand("return_reward_history.tsv",200);
    }
    static String historyCommand(String leaf,int lines) {
        if(!(leaf.equals("return_reward_history.tsv")&&lines==200||leaf.equals("dispatch_history.tsv")&&lines==200||leaf.equals("dispatch_availability.tsv")&&lines==514))bad();
        String root="/data/user/0/com.nianticlabs.pikmin/files",path=root+"/"+leaf;
        // Read a bounded tail from the held descriptor, not the whole growing log.
        // Independent byte count detects stripped NULs/trailing partial rows.
        return "set -e; export LC_ALL=C; test -d "+root+" && test ! -L "+root+" && g=$(stat -c %u "+root+") && "
            +"case \"$g\" in ''|*[!0-9]*) exit 1;; esac && test \"$g\" -ge 10000 && test -f "+path+" && test ! -L "+path+" && exec 3<"+path+" && "
            +"IFS=' ' read -r held_pid held_stat < /proc/self/stat && p=/proc/$held_pid/fd/3 && test \"$(stat -Lc %u:%g:%a:%h $p)\" = \"$g:$g:644:1\" && "
            +"b=$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p) && raw=$(tail -n "+lines+" $p) && n=$(tail -n "+lines+" $p | wc -c) && "
            +"case \"$n\" in ''|*[!0-9]*) exit 1;; esac && test \"$n\" -le "+MAX_HISTORY+" && "
            +"if test \"$n\" -eq 0; then test -z \"$raw\"; else test \"$n\" -eq $(( ${#raw} + 1 )); fi && "
            +"test \"$(stat -Lc %d:%i:%s:%Y:%Z:%u:%g:%a:%h $p)\" = \"$b\" && test ! -L "+path+" && test \"$(stat -c %d:%i:%s:%Y:%Z:%u:%g:%a:%h "+path+")\" = \"$b\" && "
            +"printf 'v1-read\\t%s\\n' \"$n\" && if test \"$n\" -gt 0; then printf '%s\\n' \"$raw\"; fi && printf 'end-read\\n'";
    }
    private static String checked(String body){return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    private static String body(String wire) {
        if(wire==null||wire.length()>4096||wire.indexOf('\r')>=0)bad();String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();return rows[0];
    }
    private static long num(String s){long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))bad();return n;}
    private static void bad(){throw new IllegalArgumentException("Exact same-session task receipt after authorization required");}
}

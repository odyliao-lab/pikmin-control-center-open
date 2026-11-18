package dev.ody.pikmincontrol;
import android.content.Context;
import java.util.*;

/** Confirmed collection seen BEFORE our per-task dispatch admission. This is
 * observational evidence, never a synthetic admission or claim of our dispatch. */
final class EmulatorDailyBatchObserved {
    final EmulatorDailyRunner.Binding binding;
    final String key,checkpoint,claimSha,id,kind,event,historySha,rewardSha;
    final long notBefore,collectedAt,observedAt;
    final EmulatorDevelopmentBatch.Window developmentWindow;
    EmulatorDailyBatchObserved(EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long notBefore,long collected,long observed,String event,String history,String reward){
        this(b,key,cp,claim,id,kind,notBefore,collected,observed,event,history,reward,null);
    }
    static EmulatorDailyBatchObserved development(EmulatorDailyRunner.Binding b,EmulatorDevelopmentBatch.Window window,String cp,String claim,String id,String kind,long notBefore,long collected,long observed,String event,String history,String reward){
        if(window==null)bad();return new EmulatorDailyBatchObserved(b,window.key,cp,claim,id,kind,notBefore,collected,observed,event,history,reward,window);
    }
    private EmulatorDailyBatchObserved(EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long notBefore,long collected,long observed,String event,String history,String reward,EmulatorDevelopmentBatch.Window window){
        EmulatorDevelopmentBatch.requireBatchKey(b,key);
        java.time.ZonedDateTime t=java.time.Instant.ofEpochMilli(notBefore).atZone(EmulatorDailyPlan.ZONE);int minute=t.getHour()*60+t.getMinute();
        if(cp==null||!cp.matches("[a-f0-9-]{36}")||claim==null||!claim.matches("[a-f0-9]{64}")||id==null||!id.matches("[A-Za-z0-9_-]{1,160}")||!Arrays.asList("seed","fruit","gift").contains(kind)||
           (window==null?(EmulatorDevelopmentBatch.development(key)||!EmulatorDailyPlan.batchWindow(key,notBefore)):(!window.key.equals(key)||!window.open(notBefore)))||collected<notBefore||observed<collected||
           !Arrays.asList("one-confirmed","batch-confirmed").contains(event)||history==null||!history.matches("[a-f0-9]{64}")||reward==null||!reward.matches("[a-f0-9]{64}"))bad();
        binding=b;this.key=key;checkpoint=cp;claimSha=claim;this.id=id;this.kind=kind;this.notBefore=notBefore;collectedAt=collected;observedAt=observed;this.event=event;historySha=history;rewardSha=reward;
        developmentWindow=window;
    }
    String encode(){String body=(developmentWindow==null?"v1":"v2-development")+"\t"+EmulatorDailyRunner.base64(binding.encode())+"\t"+key+"\t"+checkpoint+"\t"+claimSha+"\t"+id+"\t"+kind+"\t"+notBefore+"\t"+collectedAt+"\t"+observedAt+"\t"+event+"\t"+historySha+"\t"+rewardSha+
            (developmentWindow==null?"":"\t"+developmentWindow.created+"\t"+developmentWindow.deadline)+"\tobserved-collected-no-admission\tend\n";return body+"sha256\t"+EmulatorDailyRunner.digest(body)+"\n";}
    static EmulatorDailyBatchObserved decode(String wire){
        if(wire==null||wire.length()>4096||wire.indexOf('\r')>=0)bad();String[] rows=wire.split("\n",-1);
        if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+EmulatorDailyRunner.digest(rows[0]+"\n")))bad();
        String[] f=rows[0].split("\t",-1);boolean dev=f.length==17&&f[0].equals("v2-development");
        if(!(dev||f.length==15&&f[0].equals("v1"))||!f[f.length-2].equals("observed-collected-no-admission")||!f[f.length-1].equals("end"))bad();
        EmulatorDevelopmentBatch.Window window=dev?new EmulatorDevelopmentBatch.Window(f[2],Long.parseLong(f[13]),Long.parseLong(f[14])):null;
        EmulatorDailyBatchObserved o=new EmulatorDailyBatchObserved(EmulatorDailyRunner.Binding.decode(EmulatorDailyRunner.unbase64(f[1])),f[2],f[3],f[4],f[5],f[6],Long.parseLong(f[7]),Long.parseLong(f[8]),Long.parseLong(f[9]),f[10],f[11],f[12],window);
        if(!wire.equals(o.encode()))bad();return o;
    }
    static EmulatorDailyBatchObserved read(Context c,EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long since)throws Exception {
        return read(c,b,key,cp,claim,id,kind,since,null);
    }
    static EmulatorDailyBatchObserved read(Context c,EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long since,EmulatorDevelopmentBatch.Window window)throws Exception {
        EmulatorDailyRouteWork.requireIdentity(c,b);
        String[] p=EmulatorOwnedPetalGateway.sections(GpsModuleGateway.farmRead(EmulatorDailyBatchReceipt.command()),7);
        EmulatorDailyBatchObserved o=decodeSample(p,b,key,cp,claim,id,kind,since,System.currentTimeMillis(),window);
        EmulatorDailyRouteWork.requireIdentity(c,b);return o;
    }
    static EmulatorDailyBatchObserved decodeSample(String[] p,EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long since,long now){
        return decodeSample(p,b,key,cp,claim,id,kind,since,now,null);
    }
    static EmulatorDailyBatchObserved decodeSample(String[] p,EmulatorDailyRunner.Binding b,String key,String cp,String claim,String id,String kind,long since,long now,EmulatorDevelopmentBatch.Window window){
        if(b==null||p==null||p.length!=7)bad();String pid=Integer.toString(b.pid),identity=pid+"\n"+b.boot+"\n";
        if(!identity.equals(p[0])||!identity.equals(p[6])||!b.account.equals(SessionIdentity.parse(p[1],pid,pid,now))||!b.account.equals(SessionIdentity.parse(p[5],pid,pid,now)))bad();
        for(int i:new int[]{2,4}){CoreCapabilities caps=EmulatorDailyCore.capabilities(p[i],b,now);if(!caps.ok()||!caps.identityReady()||!caps.domainReady()||!caps.stepOk("return.task_list_time")||caps.sessionEpoch!=b.sessionEpoch)bad();}
        EmulatorDailyBatchReceipt.Reward r=EmulatorDailyBatchReceipt.findReward(EmulatorDailyBatchScan.unpack(p[3],262144),id,since,now);
        return r==null?null:new EmulatorDailyBatchObserved(b,key,cp,claim,id,kind,since,r.at,now,r.event,r.historySha,r.rewardSha,window);
    }
    private static void bad(){throw new IllegalArgumentException("Exact observation without dispatch admission required");}
}

package dev.ody.pikmincontrol;

import java.util.*;

/** Reconcile one stopped authorization, never infer success from a missing candidate. */
final class BatchLateReceipt {
    static final class Proof {
        final String task;
        final long authorizedAt, receivedAt, checkedAt;
        Proof(String task,long authorizedAt,long receivedAt,long checkedAt) {
            this.task=task;this.authorizedAt=authorizedAt;this.receivedAt=receivedAt;this.checkedAt=checkedAt;
        }
    }
    static Proof find(WorkCheckpoint cp,String states,String rewards,long now) {
        if(cp==null || !"batch".equals(cp.owner) || !cp.uncertain || cp.account.isEmpty()
                || now<cp.created || states==null || rewards==null) return null;
        Map<String,Integer> tasks=new HashMap<>();
        for(int i=0;i<cp.entries.size();i++)tasks.put(cp.entries.get(i).split("\t",-1)[0],i);
        String pending=null;long handoff=0,authorized=0,terminal=0,previous=0;
        boolean covered=false,rewardsCovered=false;
        int handoffs=0,authorizations=0;
        try {
            if(!states.endsWith("\n") || !rewards.endsWith("\n"))return null;
            for(String line:states.replace("\r","").split("\n")) {
                String[] f=line.split("\t",-1);
                if(f.length!=6)return null;
                long at=Long.parseLong(f[0]);
                if(at<=0 || at<previous || at>now)return null;
                previous=at;
                if(at<=cp.created)covered=true;
                if(at<cp.created)continue;
                if(terminal!=0)return null; // Another run/operation followed this terminal.
                if("released".equals(f[4]) && Arrays.asList("released","stopped","error","stop-incomplete").contains(f[1])) {
                    terminal=at;continue;
                }
                if(!"handoff".equals(f[1]) && !"awaiting-game-list".equals(f[1]))continue;
                Integer index=tasks.get(f[4]);
                if(index==null)return null;
                if(cp.done.get(index))continue;
                if(pending!=null && !pending.equals(f[4]))return null;
                pending=f[4];
                if("handoff".equals(f[1])) {handoff=at;handoffs++;}
                else {if(handoff==0 || at<handoff)return null;authorized=at;authorizations++;}
            }
            // The old checkpoint has no attempt ID. Only one fully covered, stopped
            // handoff can be recovered automatically; ambiguity stays manual review.
            if(!covered || pending==null || handoffs!=1 || authorizations!=1 || terminal<authorized)return null;
            long received=0;
            for(String line:rewards.replace("\r","").split("\n")) {
                String[] f=line.split("\t",-1);
                if(f.length!=9)return null;
                long at=Long.parseLong(f[0]);
                if(at<=0 || at>now)return null;
                if(at<=cp.created)rewardsCovered=true;
                if(!pending.equals(f[2]) || at<authorized)continue;
                if(("batch-confirmed".equals(f[1]) || "one-confirmed".equals(f[1]))
                        && Integer.parseInt(f[4])>0 && "0".equals(f[5]))
                    received=received==0?at:Math.min(received,at);
            }
            return rewardsCovered && received>0 ? new Proof(pending,authorized,received,now) : null;
        }catch(RuntimeException invalid){return null;}
    }
}

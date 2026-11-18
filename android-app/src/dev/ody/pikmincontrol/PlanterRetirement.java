package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Explicit manual UNKNOWN retirement, never confirmation or replay of the old plant. */
final class PlanterRetirement {
    /** Current immutable consent basis, independent of an expired recovery request. */
    static class Basis {
        final String pid,ticks,boot,account,ledger,sha,inventorySha,baseline;
        final long sampled;
        Basis(String[] s,long now)throws Exception {
            if(s.length!=11)throw new IllegalStateException("育苗封存資料不完整");
            pid=s[0].trim();ticks=s[1].trim();boot=s[2].trim();
            if(!pid.matches("[1-9][0-9]*")||!pid.equals(s[10].trim())||!ticks.matches("[1-9][0-9]*")||!boot.matches("[a-f0-9-]{36}"))throw new IllegalStateException("遊戲程序已變更");
            String[] status=s[3].trim().split("\t",-1);
            PlanterState state=new PlanterState("auto",s[3],s[4],"",pid,now);
            if(!state.fresh||status.length<5||status.length>6||!(status[3].equals("restart-pending-review-required")
                ||status[3].equals("rpc-failed-review-required")||status[3].equals("server-rejected-review-required")))
                throw new IllegalStateException("需新鮮且完整的育苗安全暫停回報");
            ledger=s[6];String[] l=ledger.trim().split("\\s+",-1);
            if(l.length!=4||!l[1].equals("plant")||!l[2].matches("[A-Za-z0-9_:-]{1,256}")||!l[3].matches("[0-7]"))throw new IllegalStateException("僅處理已核對的舊補種紀錄；拔苗請先走原生核對");
            long sent=Long.parseLong(l[0]);if(sent<=0||now<sent||now-sent<60000)throw new IllegalStateException("舊補種時間尚未確認");
            account=SessionIdentity.parse(s[8],pid,pid,now);if(account.isEmpty())throw new IllegalStateException("帳號狀態尚未確認");
            if(!s[9].trim().equals("157.0"))throw new IllegalStateException("此恢復流程僅適用已配對的遊戲157");
            String[] rows=s[4].trim().split("\n");String[] header=rows[0].split("\t",-1);sampled=Long.parseLong(header[1]);
            if(sampled>now||now-sampled>12000||now-Long.parseLong(status[1])>12000)throw new IllegalStateException("請先回遊戲更新育苗回報");
            Set<String> seeds=new HashSet<>(),slots=new HashSet<>();boolean permanent=false;String target="absent",slotSeed="";
            for(int n=1;n<rows.length-1;n++){
                String[] f=rows[n].split("\t",-1);
                if(f[0].equals("seed")){
                    if(f.length<8||!seeds.add(f[1]))throw new IllegalStateException("花苗庫存不完整");
                    if(f[1].equals(l[2])){
                        if(!f[2].equals("0"))throw new IllegalStateException("原花苗仍種植中或狀態未知；不封存");
                        target="unplanted";
                    }
                }
                else if(f[0].equals("slot")){
                    if(f.length<9||!f[1].matches("[0-7]")||!slots.add(f[1])||f[4].equals(l[2]))throw new IllegalStateException("舊花苗仍被槽位引用或槽位重複");
                    if(f[1].equals(l[3])){permanent=f[2].equals("0");slotSeed=f[4];}
                }else if(!f[0].equals("steps"))throw new IllegalStateException("未知育苗庫存欄位");
            }
            if(!permanent||!slots.contains("0")||!slots.contains("1"))throw new IllegalStateException("兩個永久槽位尚未完整確認");
            baseline=target+":"+slotSeed;
            // Ignore only the report heartbeat, not inventory content or sample freshness.
            sha=hash(ledger);inventorySha=hash(inventoryIdentity(s[4]));
        }
        /** Same original operation and owner; inventory disposition is allowed to refresh after user consent. */
        boolean same(Basis next){return next!=null&&pid.equals(next.pid)&&ticks.equals(next.ticks)&&boot.equals(next.boot)&&account.equals(next.account)&&ledger.equals(next.ledger);}
        boolean sameDisposition(Basis next){return same(next)&&baseline.equals(next.baseline)&&inventorySha.equals(next.inventorySha);}
        String guard(){return "test \"$("+OwnerGameProcess.command()+")\" = "+pid+"; test \"$(awk '{print $22}' /proc/"+pid+"/stat)\" = "+ticks+"; test \"$(cat /proc/sys/kernel/random/boot_id)\" = "+boot+"; ";}
    }
    /** Retirement additionally requires the exact fresh UNKNOWN ACK and its request. */
    static final class Proof extends Basis {
        final String requestNonce;
        Proof(String[] s,long now)throws Exception {
            super(s,now);
            PlanterRecovery recovery=new PlanterRecovery(s[5],pid,now);
            if(!recovery.supported||!recovery.result.equals("result-unknown"))throw new IllegalStateException("原生尚未確認為結果不明；不封存執行中請求");
            if(!recovery.nonce.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalStateException("育苗核對識別無效");
            String[] l=ledger.trim().split("\\s+",-1),request=s[7].trim().split("\\s+",-1);
            if(request.length!=8||!request[0].equals("v1")||!request[1].equals(recovery.nonce)||!request[2].equals(pid)
                ||!request[4].equals(l[0])||!request[5].equals(l[1])||!request[6].equals(l[2])||!request[7].equals(l[3]))throw new IllegalStateException("核對回覆與目前原件不匹配，請回遊戲更新");
            long asked=Long.parseLong(request[3]),sent=Long.parseLong(l[0]);
            if(asked<sent||now<asked||now-asked>90000)throw new IllegalStateException("原生核對已逾時");
            requestNonce=recovery.nonce;
        }
    }
    interface Port {
        void guard()throws Exception;
        void saveIntent(Proof p)throws Exception;
        void retire(Proof p)throws Exception;
        void launch()throws Exception;
        void complete()throws Exception;
    }
    static void run(Port p,Proof consent)throws Exception {
        if(consent==null)throw new IllegalStateException("尚未取得使用者確認的最新育苗狀態");
        // Flow.confirm has just renewed this proof after the user's explicit choice.
        // Requiring repeated identical inventory snapshots can deadlock when unrelated
        // seeds change while the game is live; the final device-side guard still checks
        // the exact current inventory, pending bytes, owner, session, nonce and UNKNOWN ACK.
        p.guard();p.saveIntent(consent);p.guard();p.retire(consent);p.launch();p.complete();
    }
    /** Heartbeats and growing-slot progress cannot change the identity/disposition of a seed. */
    static String inventoryIdentity(String raw) {
        StringBuilder out=new StringBuilder();String[] rows=raw.split("\n",-1);
        for(int i=0;i<rows.length;i++) {
            if(i==rows.length-1&&rows[i].isEmpty())break;
            String[] f=rows[i].split("\t",-1);
            if(i==0&&f.length==5)f[1]="0";
            if(f.length>=9&&f[0].equals("slot")) {
                if(!f[5].matches("[0-9]{1,19}")||Long.parseLong(f[5])<0)
                    throw new IllegalStateException("槽位步數不完整，未封存");
                f[5]="0";
            }
            out.append(String.join("\t",f)).append('\n');
        }
        return out.toString();
    }
    static String hash(String raw)throws Exception{
        byte[] b=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();
    }
}

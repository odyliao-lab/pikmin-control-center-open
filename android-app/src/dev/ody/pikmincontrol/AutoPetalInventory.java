package dev.ody.pikmincontrol;
import java.util.*;

/** Account/epoch-bound read-only cache. Never an action status or spending authorization. */
final class AutoPetalInventory {
    boolean valid;long at;String account="";long epoch;
    final Map<String,String[]> rows=new LinkedHashMap<>();
    boolean recent(long now){return valid&&now>=at&&now-at<=300000;}
    boolean needsGame(long now){return !valid||now<at||now-at>20000;}
    String contentKey(long now){return valid?account+":"+epoch+":"+recent(now)+":"+rows.values().stream().map(Arrays::toString).collect(java.util.stream.Collectors.joining()):"unknown";}
    // The game's identity heartbeat can pause with Unity. Fresh loader capabilities,
    // epoch and login time fence this historical identity; it is not a live-action proof.
    private static String account(String raw,String pid,long login,long now){
        String[] f=raw.trim().split("\t",-1);
        if(f.length!=7||!f[0].equals("v1")||!f[2].equals(pid)||!f[3].matches("[a-f0-9]{64}")
                ||!f[4].equals("ready")||!f[5].matches("idle|busy")||!f[6].equals("end"))return "";
        long at=Long.parseLong(f[1]);return at>=login&&at>0&&at<=now?f[3]:"";
    }
    static AutoPetalInventory parse(String[] p,String version,int code,long now){
        AutoPetalInventory result=new AutoPetalInventory();
        try{
            if(p==null||p.length!=8||!p[0].trim().matches("[1-9][0-9]*")||!p[0].trim().equals(p[7].trim()))return result;
            String pid=p[0].trim();
            CoreCapabilities before=CoreCapabilities.parse(ReadinessSample.Read.ok(p[2]),pid,version,code,now);
            CoreCapabilities after=CoreCapabilities.parse(ReadinessSample.Read.ok(p[5]),pid,version,code,now);
            String a=account(p[1],pid,before.lastLoginMs,now),b=account(p[6],pid,after.lastLoginMs,now);
            if(a.isEmpty()||!a.equals(b)||!before.identityReady()||!after.identityReady()
                    ||before.epochMs>now||after.epochMs>now||before.sessionEpoch!=after.sessionEpoch
                    ||before.sessionGen!=after.sessionGen||before.lastLoginMs!=after.lastLoginMs)return result;
            String raw=p[3];if(raw.length()>131072)return result;
            String[] lines=raw.split("\n",-1),h=lines[0].split("\t",-1);
            if(h.length!=7||!h[0].equals("v2")||!h[2].equals(pid)||!h[3].equals(a)||!h[6].equals("1")
                    ||lines.length<3||!lines[lines.length-2].equals("end")||!lines[lines.length-1].isEmpty())return result;
            long at=Long.parseLong(h[1]),epoch=Long.parseLong(h[4]);
            if(at<=0||at>now||at<after.lastLoginMs||epoch<=0||epoch!=after.sessionEpoch||Long.parseLong(h[5])<=0)return result;
            for(int i=1;i<lines.length-2;i++){
                String[] r=lines[i].split("\t",-1);
                if(r.length!=5||!r[0].equals("petal")||!AutoPetalConfig.id(r[1])||!AutoPetalConfig.id(r[2])
                        ||!r[3].matches("[1-4]")||!r[4].matches("0|[1-9][0-9]{0,6}")||Long.parseLong(r[4])>1000000
                        ||result.rows.put(r[1],r)!=null||result.rows.size()>512)throw new IllegalArgumentException();
            }
            result.at=at;result.epoch=epoch;result.account=a;result.valid=true;
        }catch(RuntimeException invalid){result.rows.clear();result.valid=false;}
        return result;
    }
}

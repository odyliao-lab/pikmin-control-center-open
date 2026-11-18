package dev.ody.pikmincontrol;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Completion ledger. No automatic resume, no reconstruction from PID or missing task rows. */
final class WorkCheckpoint {
    final String id,owner,account;
    final long created;
    final int rounds,dwell;
    final List<String> entries;
    final BitSet done=new BitSet();
    boolean uncertain;
    WorkCheckpoint(String id,String owner,String account,long created,int rounds,int dwell,List<String> entries) {
        if(!id.matches("[a-f0-9-]{36}") || !(owner.equals("batch")||owner.equals("flower-farm"))
                || !(account.isEmpty()||account.matches("[a-f0-9]{64}")) || created<=0
                || (rounds!=1&&rounds!=2) || (dwell!=5&&dwell!=10) || entries.isEmpty() || entries.size()>2000)
            throw new IllegalArgumentException("接續紀錄格式不符");
        if(owner.equals("batch")&&rounds!=1)throw new IllegalArgumentException("batch rounds");
        Set<String> ids=new HashSet<>();
        for(String row:entries) {
            String[] f=row.split("\t",-1);
            if(owner.equals("batch")) {
                if(f.length!=6 || !f[0].matches("[A-Za-z0-9_-]{1,160}") || !ids.add(f[0])
                        || !(f[1].equals("seed")||f[1].equals("fruit")||f[1].equals("gift")))throw new IllegalArgumentException("task row");
                Long.parseLong(f[2]);coords(f[3],f[4]);Double.parseDouble(f[5]);
            }else {if(f.length!=2)throw new IllegalArgumentException("point row");coords(f[0],f[1]);}
        }
        this.id=id;this.owner=owner;this.account=account;this.created=created;this.rounds=rounds;this.dwell=dwell;
        this.entries=new ArrayList<>(entries);
    }
    private static void coords(String a,String b){double lat=Double.parseDouble(a),lng=Double.parseDouble(b);
        if(!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180)throw new IllegalArgumentException("coordinates");}
    int total(){return entries.size()*rounds;}
    WorkCheckpoint bindFreshAccount(String live) {
        if(live==null||!live.matches("[a-f0-9]{64}"))throw new IllegalStateException("原生帳號識別尚未就緒，請確認核心版本並重新開啟遊戲");
        if(!account.isEmpty()&&!account.equals(live))throw new IllegalStateException("準備期間帳號已變更，請重新開始工作");
        if(uncertain||!done.isEmpty())throw new IllegalStateException("不可重新綁定已執行的工作");
        return new WorkCheckpoint(id,owner,live,created,rounds,dwell,entries);
    }
    int remaining(){return total()-done.cardinality();}
    void finishTask(String task){for(int i=0;i<entries.size();i++)if(entries.get(i).startsWith(task+"\t"))done.set(i);}
    String reason(String live,long now){
        if(remaining()==0)return "工作已全部完成";
        if(now<created || now-created>86_400_000L)return "紀錄已過期，請重新掃描或提供有效花點";
        if(account.isEmpty()||live.isEmpty())return "原生帳號識別尚未確認，不能接續；不以PID代替帳號";
        if(!account.equals(live))return "目前帳號與原工作不同，禁止接續";
        if(uncertain)return "有未確認的原生操作，禁止重送；請先核對原工作紀錄";
        return "";
    }
    Properties properties(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("id",id);p.setProperty("owner",owner);
        p.setProperty("account",account);p.setProperty("created",Long.toString(created));p.setProperty("rounds",Integer.toString(rounds));
        p.setProperty("dwell",Integer.toString(dwell));p.setProperty("entries",String.join("\n",entries));p.setProperty("uncertain",Boolean.toString(uncertain));
        StringJoiner bits=new StringJoiner(",");for(int i=done.nextSetBit(0);i>=0;i=done.nextSetBit(i+1))bits.add(Integer.toString(i));
        p.setProperty("done",bits.toString());p.setProperty("checksum",checksum(p));return p;}
    static WorkCheckpoint restore(Properties p){
        if(p.size()!=11 || !"1".equals(p.getProperty("schema")) || !checksum(p).equals(p.getProperty("checksum")))throw new IllegalArgumentException("損壞的接續紀錄");
        WorkCheckpoint c=new WorkCheckpoint(p.getProperty("id"),p.getProperty("owner"),p.getProperty("account"),Long.parseLong(p.getProperty("created")),
                Integer.parseInt(p.getProperty("rounds")),Integer.parseInt(p.getProperty("dwell")),Arrays.asList(p.getProperty("entries").split("\n",-1)));
        String uncertain=p.getProperty("uncertain");if(!"true".equals(uncertain)&&!"false".equals(uncertain))throw new IllegalArgumentException("uncertain");c.uncertain=Boolean.parseBoolean(uncertain);
        for(String bit:p.getProperty("done").split(","))if(!bit.isEmpty()){int i=Integer.parseInt(bit);if(i<0||i>=c.total()||c.done.get(i))throw new IllegalArgumentException("completion index");c.done.set(i);}
        return c;
    }
    private static String checksum(Properties p){try{MessageDigest d=MessageDigest.getInstance("SHA-256");
        List<String> keys=new ArrayList<>(p.stringPropertyNames());Collections.sort(keys);
        for(String k:keys)if(!k.equals("checksum")){d.update((k+"\0"+p.getProperty(k)+"\0").getBytes(StandardCharsets.UTF_8));}
        StringBuilder out=new StringBuilder();for(byte b:d.digest())out.append(String.format(Locale.US,"%02x",b&255));return out.toString();
    }catch(Exception e){throw new IllegalStateException(e);}}
}

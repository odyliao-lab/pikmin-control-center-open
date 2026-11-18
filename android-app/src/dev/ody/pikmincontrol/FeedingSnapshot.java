package dev.ody.pikmincontrol;
import java.util.*;

/** Versioned complete catalogue. Missing or old observations never authorise spending. */
final class FeedingSnapshot {
    static final class Kind {
        final String id,family,name,color;
        final boolean ordinary;
        final long nectar,petals,capacity;
        Kind(String[] f){id=f[1];family=f[2];name=f[3];color=f[4];ordinary=f[5].equals("1");
            nectar=Long.parseLong(f[6]);petals=Long.parseLong(f[7]);capacity=Long.parseLong(f[8]);}
    }
    final List<Kind> kinds=new ArrayList<>();
    boolean fresh,ready;
    String state="waiting-native",revision="";
    boolean runtimeFresh;
    String runtimeState="",runtimeRevision="";
    void applyRuntime(String raw,String before,String after,long now){
        runtimeFresh=false;runtimeState="";runtimeRevision="";
        try {
            if(raw==null||raw.length()>1024)throw new IllegalArgumentException();
            String[] lines=raw.split("\n");String[] h=lines[0].split("\t",-1);
            if(lines.length!=2||!lines[1].equals("end")||h.length!=5||!h[0].equals("v2")
                    ||!before.matches("[1-9][0-9]*")||!before.equals(after)||!before.equals(h[2]))throw new IllegalArgumentException();
            long at=Long.parseLong(h[1]);
            if(at<=0||at>now||now-at>12000||!h[3].matches("-|[a-f0-9-]{36}")||!h[4].matches("[a-z-]{1,64}"))throw new IllegalArgumentException();
            runtimeFresh=true;runtimeRevision=h[3];runtimeState=h[4];
        }catch(Exception ignored){}
    }
    static FeedingSnapshot parse(String raw,String before,String after,long now){
        FeedingSnapshot s=new FeedingSnapshot();
        try {
            if(raw==null||raw.length()>262144)throw new IllegalArgumentException();
            String[] lines=raw.split("\n");String[] h=lines[0].split("\t",-1);
            if(h.length!=7||!h[0].equals("v1")||!before.matches("[1-9][0-9]*")||!before.equals(after)||!before.equals(h[2]))throw new IllegalArgumentException();
            long at=Long.parseLong(h[1]);if(at<=0||at>now||now-at>12000)throw new IllegalArgumentException();
            if(!h[3].equals("observe-only")&&!h[3].equals("ready"))throw new IllegalArgumentException();
            int n=FeedingConfig.number(h[6],0,FeedingConfig.MAX_KINDS);
            if(lines.length!=n+2||!lines[n+1].equals("end")||!h[4].matches("[a-z-]{1,64}")||!h[5].matches("-|[a-f0-9-]{36}"))throw new IllegalArgumentException();
            Set<String> ids=new HashSet<>();
            for(int i=1;i<=n;i++){
                String[] f=lines[i].split("\t",-1);
                if(f.length!=9||!f[0].equals("kind")||!FeedingConfig.id(f[1])||!FeedingConfig.id(f[2])||f[3].isEmpty()||f[3].length()>80
                        ||!f[4].matches("white|yellow|red|blue|other")||!f[5].matches("[01]")||!ids.add(f[1]))throw new IllegalArgumentException();
                for(int j=6;j<=8;j++)if(!f[j].matches("-1|[0-9]{1,7}"))throw new IllegalArgumentException();
                // Confirmed game inventory can exceed capacity (e.g. bonus petals).
                // This read-only catalogue must retain that count; native feeding
                // admission independently keeps its existing capacity guards.
                s.kinds.add(new Kind(f));
            }
            s.fresh=true;s.ready=h[3].equals("ready");s.state=h[4];s.revision=h[5];
        }catch(Exception e){s.kinds.clear();s.fresh=false;s.ready=false;}
        return s;
    }
    static String color(String c){return "white".equals(c)?"白":"yellow".equals(c)?"黃":"red".equals(c)?"紅":"blue".equals(c)?"藍":"其他";}
    List<Kind> filter(String query,String mode,String color,FeedingConfig config){
        List<Kind> out=new ArrayList<>();String q=query.trim().toLowerCase(Locale.ROOT);
        for(Kind k:kinds)if((k.name.toLowerCase(Locale.ROOT).contains(q)||k.id.toLowerCase(Locale.ROOT).contains(q))
                &&(color.equals("all")||color.equals(k.color))&&(!mode.equals("stock")||k.nectar>0)&&(!mode.equals("selected")||config.rules.containsKey(k.id)))out.add(k);
        return out;
    }
    String message(FeedingConfig c){
        if(runtimeFresh){
            if(runtimeState.equals("unconfirmed-cooldown"))return "結果未確認 · 冷卻後自動續行";
            if(runtimeState.equals("recovery-storage-retry"))return "紀錄暫時無法保存 · 自動重試中";
            if(runtimeState.contains("review"))return "異常已記錄 · 自動核對中";
            if(runtimeState.equals("waiting-rpc-no-retry")||runtimeState.equals("waiting-inventory-confirmation"))return "等待遊戲確認 · 不會重送";
            if(c.enabled&&!runtimeRevision.equals(c.revision))return "設定尚未由原生確認";
            if(c.enabled){
                if(runtimeState.startsWith("rotation-")||runtimeState.equals("rotate-confirmed"))return FeedingHistory.label(runtimeState);
                if(runtimeState.equals("feed-confirmed"))return "餵食成功 · 精華消耗已確認";
                if(runtimeState.equals("collect-confirmed"))return "收瓣成功 · 花瓣已入庫";
                if(runtimeState.equals("batch-squad-changed"))return "已略過離隊目標 · 繼續目前隊伍";
                if(runtimeState.equals("batch-confirmation-timeout"))return "部分結果未確認 · 其他隊員繼續處理";
                if(runtimeState.equals("sent"))return "已送出 · 等待遊戲確認";
                if(runtimeState.equals("waiting-other-work"))return "等待中 · 其他自動工作優先";
                if(runtimeState.equals("waiting-eligible-squad"))return c.rules.isEmpty()?
                    (c.collect?"僅收花瓣 · 餵食請先選擇精華":"未選擇精華 · 尚未餵食"):"等待符合條件的隊員或精華";
                if(runtimeState.equals("off"))return "等待原生套用開關";
                return "等待原生資料 · "+runtimeState;
            }
        }
        if(!c.enabled)return "已關閉";
        if(!fresh)return "已開啟 · 等待遊戲原生資料";
        if(!ready)return "已開啟 · 等待原生執行狀態";
        if(!revision.equals(c.revision))return "設定尚未由原生確認";
        if(c.rules.isEmpty())return "等待中 · 請選擇精華";
        return "running".equals(state)?"自動餵食中":"安全等待 · "+state;
    }
}

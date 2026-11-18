package dev.ody.pikmincontrol;

import java.util.*;

/** Read-only explanation of each current-squad observation. Raw game IDs stay private. */
final class FeedingSquadDiagnostics {
    static boolean unavailable(String report){return report==null||report.startsWith("尚無可用的隊伍診斷");}
    private static final class Row {
        final int status,flower,count,next;
        final double hearts;
        final boolean restrictionKnown,restricted;
        Row(String[] f,boolean v2){
            status=number(f[2],0,32);hearts=Double.parseDouble(f[3]);flower=number(f[4],0,6);
            count=number(f[5],0,1_000_000);next=number(f[6],0,1_000_000);
            restrictionKnown=v2;restricted=v2&&"1".equals(f[7]);
            if(!Double.isFinite(hearts)||hearts<0||hearts>8)throw new IllegalArgumentException();
        }
    }
    static String parse(String raw,String before,String after,long now,FeedingConfig config){
        try {
            if(raw==null||raw.length()>32768)throw new IllegalArgumentException();
            String[] lines=raw.split("\\n");String[] h=lines[0].split("\\t",-1);
            boolean v1=h.length==6&&"v1".equals(h[0]),v2=h.length==6&&"v2".equals(h[0]);
            if((!v1&&!v2)||!before.matches("[1-9][0-9]*")||!before.equals(after)||!before.equals(h[2])
                    ||!"observe-only".equals(h[3])||!h[4].matches("[a-z-]{1,64}"))throw new IllegalArgumentException();
            long at=Long.parseLong(h[1]);int n=FeedingConfig.number(h[5],0,100);
            if(at<=0||at>now||now-at>12000||lines.length!=n+2||!"end".equals(lines[n+1]))throw new IllegalArgumentException();
            ArrayList<Row> rows=new ArrayList<>();
            for(int i=1;i<=n;i++){
                String[] f=lines[i].split("\\t",-1);
                if((v1&&f.length!=7)||(v2&&f.length!=8)||!"pikmin".equals(f[0])||f[1].isEmpty()||f[1].length()>256
                        ||(v2&&!f[7].matches("[01]")))throw new IllegalArgumentException();
                rows.add(new Row(f,v2));
            }
            StringBuilder out=new StringBuilder("本次原生隊伍快照：").append(rows.size()).append(" 隻");
            if(!v2)out.append("\n限制旗標尚未提供；請安裝新版核心後更新狀態。");
            Map<String,Integer> reasons=new LinkedHashMap<>();
            for(Row r:rows)reasons.put(reason(r,config),reasons.getOrDefault(reason(r,config),0)+1);
            out.append("\n\n摘要");for(Map.Entry<String,Integer> e:reasons.entrySet())out.append("\n").append(e.getValue()).append(" 隻 · ").append(e.getKey());
            out.append("\n\n逐隻資料（不顯示遊戲 ID）");
            for(int i=0;i<rows.size();i++){
                Row r=rows.get(i);out.append("\n").append(i+1).append(". status ").append(r.status)
                    .append(" · 花況 ").append(r.flower).append(" · 愛心 ").append(String.format(Locale.US,"%.2f",r.hearts))
                    .append(" · FlowerStateFlowerCount ").append(r.count).append("（僅觀察）")
                    .append(" · 下一次花瓣 ").append(r.next).append("\n   ").append(reason(r,config));
            }
            out.append("\n\nFlowerStateFlowerCount 是遊戲原始值，暫不作為餵食次數或第 5／6 次門檻。");
            return out.toString();
        }catch(Exception e){return "尚無可用的隊伍診斷；請停在遊戲內並稍候重試。";}
    }
    private static int number(String s,int min,int max){return FeedingConfig.number(s,min,max);}
    private static String reason(Row r,FeedingConfig c){
        if(r.status!=1)return "略過：遊戲狀態非可用（status "+r.status+"）";
        if(!r.restrictionKnown)return "限制旗標未知";
        if(r.restricted)return "略過：遊戲原生暫時限制操作";
        if(r.flower==5)return c.collect?"候選：可收花瓣":"略過：自動收花瓣已關閉";
        if(r.flower!=1&&r.flower!=3&&r.flower!=4)return "略過：目前花況不是餵食候選（state "+r.flower+"）";
        if(r.hearts>=c.hearts)return "略過：已達友好度門檻";
        if(c.rules.isEmpty())return "略過：尚未選擇可用精華";
        return "候選：可餵食，送出前仍會重核精華庫存與花瓣上限";
    }
}

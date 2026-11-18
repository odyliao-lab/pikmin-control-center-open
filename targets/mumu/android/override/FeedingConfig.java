package dev.ody.pikmincontrol;

import java.util.*;

/** Persisted desired settings, never proof that the native consumer is running. */
final class FeedingConfig {
    static final int MAX_COUNT=1000000, MAX_KINDS=512;
    boolean enabled, collect=false, aggressivePick=true, rotate;
    int hearts=4, reserve=100, ceiling=MAX_COUNT;
    String revision=UUID.randomUUID().toString();
    final LinkedHashMap<String,Rule> rules=new LinkedHashMap<>();
    static final class Rule {
        final int reserve,ceiling;
        Rule(int reserve,int ceiling) {
            if(!((reserve==-1&&ceiling==-1)||(validCount(reserve)&&validCount(ceiling)&&ceiling>0)))throw new IllegalArgumentException("門檻無效");
            this.reserve=reserve;this.ceiling=ceiling;
        }
        boolean inherited(){return reserve==-1;}
    }
    static boolean validCount(int n){return n>=0&&n<=MAX_COUNT;}
    static boolean id(String s){return s!=null&&s.matches("[A-Za-z0-9_.:-]{1,96}");}
    static int number(String s,int minimum,int maximum){
        if(!s.matches("[0-9]{1,7}"))throw new IllegalArgumentException("請輸入整數");
        int n=Integer.parseInt(s);if(n<minimum||n>maximum)throw new IllegalArgumentException("數值超出範圍");return n;
    }
    void validate(){
        if(hearts<1||hearts>8||!validCount(reserve)||!validCount(ceiling)||ceiling<1||rules.size()>MAX_KINDS
                ||!revision.matches("[a-f0-9-]{36}"))throw new IllegalArgumentException("餵食設定無效");
        for(Map.Entry<String,Rule> e:rules.entrySet())if(!id(e.getKey())||e.getValue()==null)throw new IllegalArgumentException("精華識別無效");
    }
    int reserve(String id){Rule r=rules.get(id);return r==null||r.inherited()?reserve:r.reserve;}
    int ceiling(String id){Rule r=rules.get(id);return r==null||r.inherited()?ceiling:r.ceiling;}
    boolean useGameCapacity(){
        // Existing native protocol applies min(live player capacity, ceiling).
        // MAX_COUNT removes only the user ceiling; unknown capacity still blocks.
        boolean changed=ceiling!=MAX_COUNT;ceiling=MAX_COUNT;
        for(Map.Entry<String,Rule> entry:rules.entrySet()){
            Rule r=entry.getValue();
            if(!r.inherited()&&r.ceiling!=MAX_COUNT){entry.setValue(new Rule(r.reserve,MAX_COUNT));changed=true;}
        }
        if(changed)revision=UUID.nameUUIDFromBytes((revision+":game-capacity-v1").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        return changed;
    }
    String encode(){
        validate();StringBuilder out=new StringBuilder("v3\t"+revision+"\t"+(enabled?1:0)+"\t"+hearts+"\t"+reserve+"\t"+ceiling+"\t"+(collect?1:0)+"\t"+(aggressivePick?1:0)+"\t"+(rotate?1:0)+"\t"+rules.size()+"\n");
        for(Map.Entry<String,Rule> e:rules.entrySet())out.append("rule\t").append(e.getKey()).append('\t').append(e.getValue().reserve).append('\t').append(e.getValue().ceiling).append('\n');
        return out.append("end\n").toString();
    }
    static FeedingConfig parse(String raw){
        if(raw==null||raw.length()>65536)throw new IllegalArgumentException("設定過大");
        // SharedPreferences XML can retain indentation after an encoded final
        // newline. The protocol has no meaningful trailing whitespace, so
        // accept that serialization artefact without relaxing its records.
        raw=raw.replaceFirst("[ \t\r\n]+\\z","");
        String[] lines=raw.split("\n");String[] h=lines[0].split("\t",-1);
        boolean legacy=h.length==8&&h[0].equals("v1"),current=h.length==9&&h[0].equals("v2"),rotation=h.length==10&&h[0].equals("v3");
        if((!legacy&&!current&&!rotation)||!h[2].matches("[01]")||!h[6].matches("[01]")||(!legacy&&!h[7].matches("[01]"))||(rotation&&!h[8].matches("[01]")))throw new IllegalArgumentException("設定格式不符");
        FeedingConfig c=new FeedingConfig();c.revision=h[1];c.enabled=h[2].equals("1");c.hearts=number(h[3],1,8);
        c.reserve=number(h[4],0,MAX_COUNT);c.ceiling=number(h[5],1,MAX_COUNT);c.collect=h[6].equals("1");
        // Retained only for v1/v2 control-file compatibility. The raw game
        // FlowerStateFlowerCount is not a verified feed counter, so current
        // policy no longer turns this value into a fifth/sixth action cutoff.
        c.aggressivePick=legacy||h[7].equals("1");
        c.rotate=rotation&&h[8].equals("1");
        int count=number(h[legacy?7:rotation?9:8],0,MAX_KINDS);if(lines.length!=count+2||!lines[count+1].equals("end"))throw new IllegalArgumentException("設定不完整");
        for(int i=1;i<=count;i++){
            String[] f=lines[i].split("\t",-1);if(f.length!=4||!f[0].equals("rule")||c.rules.containsKey(f[1]))throw new IllegalArgumentException("重複或錯誤種類");
            c.rules.put(f[1],new Rule(f[2].equals("-1")?-1:number(f[2],0,MAX_COUNT),f[3].equals("-1")?-1:number(f[3],1,MAX_COUNT)));
        }
        c.validate();return c;
    }
    FeedingConfig copy(){return parse(encode());}
}

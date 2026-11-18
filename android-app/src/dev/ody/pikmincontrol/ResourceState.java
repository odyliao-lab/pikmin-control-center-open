package dev.ody.pikmincontrol;

/** Optional read-only v1 protocol. Unknown is never interpreted as zero or unlimited. */
final class ResourceState {
    long pikminUsed=-1,pikminMax=-1,seedUsed=-1,seedMax=-1,petals=-1;
    static ResourceState parse(String raw,String before,String after,long now) {
        ResourceState s=new ResourceState();
        try {
            String[] f=raw.trim().split("\t",-1);
            if(f.length!=10 || !f[0].equals("v1") || !before.matches("[1-9][0-9]*")
                    || !before.equals(after) || !before.equals(f[2]))return s;
            long at=Long.parseLong(f[1]);if(at<=0 || now<at || now-at>12000)return s;
            long pu=value(f[3]),pm=value(f[4]),su=value(f[5]),sm=value(f[6]),pet=value(f[7]);
            if(!f[9].equals("end") || (pet>=0 && !f[8].matches("[A-Za-z0-9_.-]{1,128}")))return s;
            if(pm>0 && pu>=0 && pu<=pm){s.pikminUsed=pu;s.pikminMax=pm;}
            if(sm>0 && su>=0 && su<=sm){s.seedUsed=su;s.seedMax=sm;}
            s.petals=pet;
        } catch(RuntimeException malformed) {return new ResourceState();}
        return s;
    }
    static long value(String text){long v=Long.parseLong(text);if(v < -1 || v>1_000_000)throw new IllegalArgumentException();return v;}
    String blocker(boolean seeds,boolean planting) {
        if(seeds && seedMax<0)return "花苗容量待確認";
        if(seeds && seedMax-seedUsed<=5)return "花苗剩餘空間不多（保留5格）";
        if(planting && petals<0)return "目前所選花瓣數量待確認";
        if(planting && petals<=20)return "目前所選花瓣不足（保留20片）";
        return "";
    }
    String summary(){return "皮克敏空間："+capacity(pikminUsed,pikminMax,10)+"\n花苗空間："+capacity(seedUsed,seedMax,5)
            +"\n所選花瓣："+(petals<0?"待確認":petals+(petals<=20?" · 數量偏低":" 片"));}
    private String capacity(long used,long max,int reserve){return max<0?"待確認":used+" / "+max+(max-used<=reserve?" · 即將滿倉":"");}
}

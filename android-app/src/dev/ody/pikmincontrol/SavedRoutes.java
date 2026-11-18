package dev.ody.pikmincontrol;

import java.util.*;

/** Private user drafts, not proof of flower validity and never an execution queue. */
final class SavedRoutes {
    static final int MAX=20;
    final List<Entry> entries;
    static final class Entry {
        final String id,name,coordinates;
        final long saved,expires;
        final HarvestRoute route;
        Entry(String id,String name,String coordinates,long saved,long expires) {
            if(!id.matches("[0-9a-f-]{36}") || name==null || !name.equals(name.trim()) || name.isEmpty() || name.length()>40
                || name.matches("(?s).*[\\p{Cntrl}].*") || saved<=0 || expires<0 || (expires!=0&&expires<saved))
                throw new IllegalArgumentException("路線名稱或時間不符");
            this.route=HarvestRoute.coordinates(coordinates);
            this.id=id;this.name=name;this.coordinates=coordinates;this.saved=saved;this.expires=expires;
        }
        String label(long now) {
            return name+" · "+route.points.size()+" 點 · "+Math.round(route.meters)+" m · 約 "+((route.estimatedSeconds()+59)/60)+" 分鐘"
                +(expires==0?" · 效期未設定":now>=expires?" · 已過期":" · 剩約 "+((expires-now+59999)/60000)+" 分鐘");
        }
    }
    SavedRoutes(List<Entry> values) {
        if(values.size()>MAX)throw new IllegalArgumentException("最多儲存20條路線");
        Set<String> ids=new HashSet<>(),names=new HashSet<>();
        for(Entry e:values)if(!ids.add(e.id)||!names.add(e.name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("路線名稱重複");
        entries=Collections.unmodifiableList(new ArrayList<>(values));
    }
    SavedRoutes add(String name,String points,long now,long minutes) {
        if(minutes<0||minutes>1440)throw new IllegalArgumentException("效期超出範圍");
        List<Entry> copy=new ArrayList<>(entries);
        copy.add(new Entry(UUID.randomUUID().toString(),name.trim(),points,now,minutes==0?0:Math.addExact(now,minutes*60000)));
        return new SavedRoutes(copy);
    }
    SavedRoutes remove(String id) {
        List<Entry> copy=new ArrayList<>();boolean found=false;
        for(Entry e:entries)if(e.id.equals(id))found=true;else copy.add(e);
        if(!found)throw new IllegalArgumentException("路線已變更，請重開清單");
        return new SavedRoutes(copy);
    }
    Properties encode() {
        Properties p=new Properties();p.setProperty("schema","1");p.setProperty("count",""+entries.size());
        for(int i=0;i<entries.size();i++){Entry e=entries.get(i);String k=i+".";
            p.setProperty(k+"id",e.id);p.setProperty(k+"name",e.name);p.setProperty(k+"points",e.coordinates);
            p.setProperty(k+"saved",""+e.saved);p.setProperty(k+"expires",""+e.expires);
        }return p;
    }
    static SavedRoutes decode(Properties p) {
        if(!"1".equals(p.getProperty("schema")))throw new IllegalArgumentException("路線庫版本不符");
        int count=Integer.parseInt(p.getProperty("count","-1"));
        if(count<0||count>MAX||p.size()!=2+count*5)throw new IllegalArgumentException("路線庫不完整，保留原檔");
        List<Entry> values=new ArrayList<>();
        for(int i=0;i<count;i++){String k=i+".";values.add(new Entry(p.getProperty(k+"id",""),p.getProperty(k+"name",""),
            p.getProperty(k+"points",""),Long.parseLong(p.getProperty(k+"saved","0")),Long.parseLong(p.getProperty(k+"expires","-1"))));}
        return new SavedRoutes(values);
    }
}

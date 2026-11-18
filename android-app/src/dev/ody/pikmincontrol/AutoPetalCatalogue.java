package dev.ody.pikmincontrol;

import java.util.*;

/** Picker presentation only. Built-in family/color keys must NEVER be saved as petal IDs. */
final class AutoPetalCatalogue {
    static final class Entry {
        final String key, id, family, name, color;
        final long stock, lastStock;
        Entry(String key, String id, String family, String color, long stock, long lastStock) {
            this.key=key;this.id=id;this.family=family;this.color=color;this.stock=stock;this.lastStock=lastStock;
            name="flower-5".equals(family)?"普通花瓣":FeedingCatalogue.name(family);
        }
        boolean selectable(){return !id.isEmpty();}
        String label(){return name+" · "+AutoPetalState.color(color);}
        String quantity(){return lastStock<0?"庫存未知":"快照 "+lastStock+" 片";}
    }
    final List<Entry> entries=new ArrayList<>();
    final Map<String,Entry> byId=new LinkedHashMap<>();
    final boolean fresh;
    final long reportedAt;

    AutoPetalCatalogue(String[] snapshot,String revision,long now){
        AutoPetalState state=snapshot==null?new AutoPetalState():AutoPetalState.parse(
                snapshot[1],snapshot[0].trim(),snapshot[2].trim(),revision,now);
        fresh=state.valid&&state.complete;
        Map<String,String[]> rows=fresh?state.rows:snapshot==null?Collections.emptyMap():AutoPetalState.catalogueRows(
                snapshot[1],snapshot[0].trim(),snapshot[2].trim(),revision,now);
        Map<String,String[]> history=snapshot==null?Collections.emptyMap():AutoPetalState.displayRows(
                snapshot[1],snapshot[0].trim(),snapshot[2].trim(),revision,now);
        reportedAt=history.isEmpty()?0:Long.parseLong(snapshot[1].split("\n",2)[0].split("\t",-1)[1]);
        fill(rows,history,fresh);
    }
    AutoPetalCatalogue(AutoPetalInventory inventory,long now){
        fresh=inventory.valid&&now>=inventory.at&&now-inventory.at<=5000;
        reportedAt=inventory.recent(now)?inventory.at:0;
        Map<String,String[]> rows=inventory.valid?inventory.rows:Collections.emptyMap();
        fill(rows,inventory.recent(now)?rows:Collections.emptyMap(),fresh);
    }
    private void fill(Map<String,String[]> rows,Map<String,String[]> history,boolean fresh){
        Map<String,List<String[]>> identities=new LinkedHashMap<>();
        for(String[] row:rows.values())identities.computeIfAbsent(row[2]+":"+row[3],k->new ArrayList<>()).add(row);
        List<FeedingSnapshot.Kind> observedTypes=new ArrayList<>();
        for(Map.Entry<String,List<String[]>> group:identities.entrySet()){
            String[] row=group.getValue().get(0);
            observedTypes.add(new FeedingSnapshot.Kind(new String[]{"kind",group.getKey(),row[2],FeedingCatalogue.name(row[2]),
                    FeedingCatalogue.colorName(row[3]),"flower-5".equals(row[2])?"1":"0","-1","-1","-1"}));
        }
        for(FeedingSnapshot.Kind kind:FeedingCatalogue.index(observedTypes)){
            List<String[]> group=identities.get(kind.id);
            String[] row=group!=null&&group.size()==1?group.get(0):null;
            entries.add(new Entry(kind.id,row==null?"":row[1],kind.family,FeedingCatalogue.colorCode(kind.color),
                    row!=null&&fresh?Long.parseLong(row[4]):-1,
                    row!=null&&history.containsKey(row[1])?Long.parseLong(history.get(row[1])[4]):-1));
        }
        for(Entry entry:entries)if(entry.selectable())byId.put(entry.id,entry);
    }
    List<Entry> filter(String query,String mode,String color,List<String> selected){
        String q=query.trim().toLowerCase(Locale.ROOT);List<Entry> out=new ArrayList<>();
        for(Entry entry:entries)if((entry.label().toLowerCase(Locale.ROOT).contains(q)||entry.family.contains(q))
                &&(color.equals("all")||color.equals(entry.color))
                &&(!mode.equals("stock")||entry.stock>0)&&(!mode.equals("snapshot-stock")||entry.lastStock>0)
                &&(!mode.equals("selected")||selected.contains(entry.id)))out.add(entry);
        return out;
    }
}

package dev.ody.pikmincontrol;
import java.util.*;
public final class FeedingCatalogueTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    public static void main(String[] args){
        FeedingSnapshot live=new FeedingSnapshot(),offline=FeedingCatalogue.display(live);
        FeedingConfig original=new FeedingConfig(),draft=original.copy();
        check(offline.kinds.size()==188&&!offline.fresh&&!offline.ready&&!live.fresh);
        List<FeedingSnapshot.Kind> geraniums=offline.filter("天竺葵","all","all",draft);
        check(geraniums.size()==4&&offline.filter("geranium","all","all",draft).size()==4);
        check(FeedingCatalogue.name("flower-s-geranium").equals("天竺葵"));
        String[] colors={"white","red","blue","yellow"};
        for(int color=1;color<=4;color++){
            final String geraniumId="flower-s-geranium:"+color;
            List<FeedingSnapshot.Kind> matching=offline.filter("天竺葵","all",colors[color-1],draft);
            check(matching.size()==1&&matching.get(0).id.equals(geraniumId));
            check(!matching.get(0).ordinary&&!draft.rules.containsKey(geraniumId));
        }
        List<FeedingSnapshot.Kind> thistles=offline.filter("薊花","all","all",draft);
        check(thistles.size()==4&&offline.filter("thistle","all","all",draft).size()==4);
        check(FeedingCatalogue.name("flower-s-thistle").equals("薊花"));
        for(int color=1;color<=4;color++){
            final String thistleId="flower-s-thistle:"+color;
            check(thistles.stream().filter(k->k.id.equals(thistleId)).count()==1);
            check(!draft.rules.containsKey(thistleId));
        }
        check(offline.filter("兔耳花","all","all",draft).size()==3);
        check(offline.filter("cyclamen","all","all",draft).size()==3);
        check(offline.filter("","stock","all",draft).isEmpty());
        for(FeedingSnapshot.Kind k:offline.kinds)check(k.nectar==-1&&k.petals==-1&&!k.id.contains("happy"));
        String id="flower-s-anemone:1";
        draft.rules.put(id,new FeedingConfig.Rule(123,456));
        FeedingCatalogue.select(draft,offline.filter("","all","all",draft));
        check(draft.rules.size()==188&&draft.reserve(id)==123&&draft.ceiling(id)==456&&original.rules.isEmpty());
        draft.rules.clear();FeedingCatalogue.select(draft,offline.filter("","all","blue",draft));
        check(draft.rules.size()>20&&draft.rules.size()<188);
        for(String key:draft.rules.keySet())check(key.endsWith(":3"));
        live.fresh=true;
        live.kinds.add(new FeedingSnapshot.Kind(new String[]{"kind",id,"flower-s-anemone","anemone","white","0","9","0","300"}));
        FeedingSnapshot merged=FeedingCatalogue.display(live);
        check(merged.kinds.size()==188&&!merged.ready&&!merged.fresh);
        live.kinds.add(new FeedingSnapshot.Kind(new String[]{"kind","flower-s-thistle:4","flower-s-thistle","thistle","yellow","0","120","42","1000"}));
        FeedingSnapshot thistleMerged=FeedingCatalogue.display(live);
        check(thistleMerged.kinds.size()==188&&!thistleMerged.fresh&&!thistleMerged.ready);
        List<FeedingSnapshot.Kind> stocked=thistleMerged.filter("薊花","stock","all",draft);
        check(stocked.size()==1&&stocked.get(0).name.equals("薊花"));
        check(stocked.get(0).nectar==120&&stocked.get(0).petals==42&&stocked.get(0).capacity==1000);
        check(original.rules.isEmpty()&&!draft.rules.containsKey("flower-s-thistle:4"));
        check(merged.filter("銀蓮花","stock","all",draft).size()==1);
        live.kinds.add(new FeedingSnapshot.Kind(new String[]{"kind","flower-s-geranium:4","flower-s-geranium","geranium","yellow","0","18","27","500"}));
        FeedingSnapshot geraniumMerged=FeedingCatalogue.display(live);
        check(geraniumMerged.kinds.size()==188&&!geraniumMerged.fresh&&!geraniumMerged.ready);
        List<FeedingSnapshot.Kind> geraniumStock=geraniumMerged.filter("天竺葵","stock","yellow",draft);
        check(geraniumStock.size()==1&&geraniumStock.get(0).name.equals("天竺葵"));
        check(geraniumStock.get(0).nectar==18&&geraniumStock.get(0).petals==27&&geraniumStock.get(0).capacity==500);
        check(original.rules.isEmpty()&&!draft.rules.containsKey("flower-s-geranium:4"));
        live.fresh=false;check(FeedingCatalogue.display(live).filter("","stock","all",draft).isEmpty());
        check(FeedingCatalogue.display(live).filter("天竺葵","all","all",draft).size()==4);
        check(FeedingCatalogue.display(live).filter("薊花","all","all",draft).size()==4);
        check(!FeedingCatalogue.name("flower-s-rose2025").equals(FeedingCatalogue.name("flower-s-rose")));
        check(offline.filter("風信子","all","all",draft).size()==4);
        check(offline.filter("菊花","all","all",draft).size()==4);
        check(offline.filter("蝴蝶蘭","all","all",draft).size()==4);
        check(offline.filter("油菜花","all","all",draft).size()==1);
        check(offline.filter("海芋","all","blue",draft).size()==1);
        check(offline.filter("玫瑰","all","white",draft).stream().anyMatch(k->k.family.equals("flower-s-rose")));
        check(offline.filter("鬱金香","all","all",draft).stream().filter(k->k.family.equals("flower-s-tulip")).count()==4);
        check(original.rules.isEmpty()); // Filling the index never expands the user's whitelist.
        System.out.println("FeedingCatalogueTest passed");
    }
}

package dev.ody.pikmincontrol;

import java.util.*;
public final class AutoPetalCatalogueTest {
    static int checks;
    static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        AutoPetalConfig config=new AutoPetalConfig();
        String raw="v1\t1000\t12\t"+config.revision+"\toff\t1\t-\t0\n"
                +"petal\topaque-white\tflower-5\t1\t100\npetal\topaque-red\tflower-s-geranium\t2\t0\nend\n";
        List<String> selected=new ArrayList<>();
        AutoPetalCatalogue empty=new AutoPetalCatalogue(null,config.revision,1001);
        check(empty.entries.size()==FeedingCatalogue.display(new FeedingSnapshot()).kinds.size());
        check(!empty.fresh&&empty.byId.isEmpty());
        check(empty.filter("天竺葵","all","all",selected).size()==4);
        for(AutoPetalCatalogue.Entry e:empty.entries)check(!e.selectable()&&e.stock==-1);
        AutoPetalCatalogue fresh=new AutoPetalCatalogue(new String[]{"12",raw,"12"},config.revision,1001);
        check(fresh.fresh&&fresh.byId.size()==2);
        check(fresh.byId.get("opaque-white").label().equals("普通花瓣 · 白"));
        check(fresh.byId.get("opaque-red").stock==0);
        check(fresh.filter("","stock","all",selected).size()==1);
        selected.add("opaque-red");
        check(fresh.filter("","selected","2",selected).size()==1);
        AutoPetalCatalogue stale=new AutoPetalCatalogue(new String[]{"12",raw,"12"},config.revision,8000);
        check(!stale.fresh&&stale.byId.size()==2);
        check(stale.byId.get("opaque-white").stock==-1);
        check(stale.filter("","stock","all",selected).isEmpty());
        check(!AutoPetalState.parse(raw,"12","12",config.revision,8000).valid); // Strict status stays strict.
        check(AutoPetalState.catalogueRows(raw,"12","12",config.revision,8000).get("opaque-white")[4].equals("-1"));
        check(new AutoPetalCatalogue(new String[]{"13",raw,"13"},config.revision,1001).byId.isEmpty());
        check(new AutoPetalCatalogue(new String[]{"12",raw,"13"},config.revision,1001).byId.isEmpty());
        check(new AutoPetalCatalogue(new String[]{"12",raw,"12"},"other",1001).byId.isEmpty());
        check(new AutoPetalCatalogue(new String[]{"12",raw,"12"},config.revision,999).byId.isEmpty());
        check(new AutoPetalCatalogue(new String[]{"12",raw.replace("end\n",""),"12"},config.revision,1001).byId.isEmpty());
        check(new AutoPetalCatalogue(new String[]{"12",raw.replace("\t1\t-\t0","\t0\t-\t0"),"12"},config.revision,1001).byId.isEmpty());
        String ambiguous=raw.replace("end\n","petal\tother-white\tflower-5\t1\t90\nend\n");
        check(!new AutoPetalCatalogue(new String[]{"12",ambiguous,"12"},config.revision,1001).byId.containsKey("opaque-white"));
        Set<String> nectarTypes=new LinkedHashSet<>(),petalTypes=new LinkedHashSet<>();
        for(FeedingSnapshot.Kind kind:FeedingCatalogue.display(new FeedingSnapshot()).kinds)nectarTypes.add(kind.id);
        for(AutoPetalCatalogue.Entry entry:empty.entries)petalTypes.add(entry.key);
        check(nectarTypes.equals(petalTypes)); // Same complete family/color set, not just the same size.
        String extended=raw.replace("end\n","petal\topaque-new\tflower-s-future\t4\t20\nend\n");
        AutoPetalCatalogue petals=new AutoPetalCatalogue(new String[]{"12",extended,"12"},config.revision,1001);
        FeedingSnapshot observed=new FeedingSnapshot();observed.fresh=true;
        observed.kinds.add(new FeedingSnapshot.Kind(new String[]{"kind","flower-s-future:4","flower-s-future","Future","yellow","0","9","17","300"}));
        nectarTypes.clear();petalTypes.clear();
        for(FeedingSnapshot.Kind kind:FeedingCatalogue.display(observed).kinds)nectarTypes.add(kind.id);
        for(AutoPetalCatalogue.Entry entry:petals.entries)petalTypes.add(entry.key);
        check(nectarTypes.equals(petalTypes)); // Observed extensions use that same shared index.
        check(petals.byId.get("opaque-new").stock==20); // Nectar and petal amounts are independent.
        for(FeedingSnapshot.Kind kind:FeedingCatalogue.index(observed.kinds))check(kind.nectar==-1&&kind.petals==-1&&kind.capacity==-1);
        check(!fresh.byId.containsKey("flower-5:1")); // Display identity is never a spendable ID.
        System.out.println("PASS AutoPetalCatalogue: "+checks+" built-in/opaque-ID/stale-quantity/owner/ambiguity checks; UI catalogue only");
    }
}

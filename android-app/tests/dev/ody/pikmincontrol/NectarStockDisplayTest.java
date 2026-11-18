package dev.ody.pikmincontrol;
public final class NectarStockDisplayTest {
    static void check(boolean b){if(!b)throw new AssertionError();}
    static FeedingSnapshot.Kind kind(long n,long p,long cap){return new FeedingSnapshot.Kind(new String[]{"kind","flower-5:1","flower-5","普通","white","1",""+n,""+p,""+cap});}
    public static void main(String[] args){
        check(NectarStockDisplay.summary(kind(32,0,300)).equals("精華 32 · 花瓣 0/300\n花瓣空間 300\n有精華／零花瓣"));
        check(NectarStockDisplay.summary(kind(-1,-1,-1)).equals("精華 未知 · 花瓣 未知/未知"));
        check(!NectarStockDisplay.zeroPetals(kind(1,-1,300)));
        check(!NectarStockDisplay.zeroPetals(kind(0,0,300)));
        check(!NectarStockDisplay.full(kind(1,-1,-1)));
        check(!NectarStockDisplay.full(kind(1,0,0)));
        check(NectarStockDisplay.full(kind(1,300,300)));
        check(NectarStockDisplay.summary(kind(1,300,300)).contains("花瓣滿倉"));
        check(NectarStockDisplay.summary(kind(68,318,300)).equals("精華 68 · 花瓣 318/300\n花瓣滿倉"));
        String overflow="v1\t1000\t42\tobserve-only\tcatalogue-unverified\t-\t1\nkind\tflower-5:1\tflower-5\t普通\twhite\t1\t68\t318\t300\nend\n";
        FeedingSnapshot parsed=FeedingSnapshot.parse(overflow,"42","42",1000);
        check(parsed.fresh&&parsed.kinds.size()==1&&parsed.kinds.get(0).petals==318);
        check(!FeedingSnapshot.parse(overflow,"42","43",1000).fresh);
        check(!FeedingSnapshot.parse(overflow,"42","42",13001).fresh);
        check(!FeedingSnapshot.parse(overflow.replace("\t318\t","\t-2\t"),"42","42",1000).fresh);
        FeedingConfig config=new FeedingConfig();
        FeedingSnapshot s=new FeedingSnapshot();s.kinds.add(kind(32,0,300));s.kinds.add(kind(1,-1,-1));s.kinds.add(kind(0,0,300));s.kinds.add(kind(1,300,300));
        check(NectarStockDisplay.filter(s,"","zero-petals","all",config).size()==1);
        check(NectarStockDisplay.filter(s,"","full","all",config).size()==1);
        check(NectarStockDisplay.filter(s,"","zero-petals","blue",config).isEmpty());
        check(config.rules.isEmpty()&&!config.enabled); // presentation never selects or starts
        System.out.println("NectarStockDisplayTest passed (18 assertions; confirmed overflow retained, freshness/PID/malformed guards unchanged)");
    }
}

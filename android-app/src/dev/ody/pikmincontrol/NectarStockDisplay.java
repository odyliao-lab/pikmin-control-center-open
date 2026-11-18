package dev.ody.pikmincontrol;

/** Read-only presentation of an opening-time snapshot; NEVER feeding admission. */
final class NectarStockDisplay {
    private NectarStockDisplay() {}
    static String quantity(long n) { return n<0?"未知":Long.toString(n); }
    static boolean zeroPetals(FeedingSnapshot.Kind k) { return k.nectar>0&&k.petals==0; }
    static boolean full(FeedingSnapshot.Kind k) { return k.capacity>0&&k.petals>=k.capacity; }
    static java.util.List<FeedingSnapshot.Kind> filter(FeedingSnapshot snapshot,String query,String mode,String color,FeedingConfig config) {
        boolean zero=mode.equals("zero-petals"),fullOnly=mode.equals("full");
        java.util.List<FeedingSnapshot.Kind> rows=snapshot.filter(query,zero||fullOnly?"all":mode,color,config);
        if(zero||fullOnly)rows.removeIf(k->zero?!zeroPetals(k):!full(k));
        return rows;
    }
    static String summary(FeedingSnapshot.Kind k) {
        String text="精華 "+quantity(k.nectar)+" · 花瓣 "+quantity(k.petals)+"/"+(k.capacity>0?quantity(k.capacity):"未知");
        if(k.capacity>0&&k.petals>=0)
            text+="\n"+(full(k)?"花瓣滿倉":"花瓣空間 "+(k.capacity-k.petals));
        if(zeroPetals(k))text+="\n有精華／零花瓣";
        // A zero petal balance does NOT establish that petals were never made,
        // or that any current Pikmin is eligible to be fed.
        return text;
    }
}

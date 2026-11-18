package dev.ody.pikmincontrol;

import java.util.*;

/** Reproduces the stale-picker failure without admitting old data as live inventory. */
public final class AutoPetalDisplayTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("case "+checks);}
    static AutoPetalCatalogue catalogue(String raw,String revision,long now){
        return new AutoPetalCatalogue(new String[]{"12",raw,"12"},revision,now);
    }
    public static void main(String[] args)throws Exception{
        String revision=new AutoPetalConfig().revision;
        String raw="v1\t10000\t12\t"+revision+"\toff\t1\t-\t0\n"
                +"petal\twhite-id\tflower-5\t1\t80\npetal\tred-id\tflower-5\t2\t0\nend\n";
        AutoPetalCatalogue fresh=catalogue(raw,revision,10001);
        check(fresh.fresh&&fresh.reportedAt==10000);
        check(fresh.byId.get("white-id").stock==80&&fresh.byId.get("white-id").lastStock==80);
        AutoPetalCatalogue delayed=catalogue(raw,revision,16000);
        check(!delayed.fresh);check(delayed.byId.size()==2);
        check(delayed.byId.get("white-id").stock==-1); // Original live meaning remains unknown.
        check(delayed.byId.get("white-id").lastStock==80);
        check(delayed.byId.get("white-id").quantity().equals("快照 80 片"));
        check(delayed.byId.get("red-id").lastStock==0);
        check(delayed.filter("","stock","all",Collections.emptyList()).isEmpty());
        check(delayed.filter("","snapshot-stock","all",Collections.emptyList()).size()==1);
        check(!AutoPetalState.parse(raw,"12","12",revision,16000).valid);
        check(AutoPetalState.catalogueRows(raw,"12","12",revision,16000).get("white-id")[4].equals("-1"));
        check(catalogue(raw,revision,310000).byId.get("white-id").lastStock==80);
        AutoPetalCatalogue expired=catalogue(raw,revision,310001);
        check(expired.reportedAt==0&&expired.byId.size()==2);
        check(expired.byId.get("white-id").lastStock==-1);
        check(expired.byId.get("white-id").quantity().equals("庫存未知"));
        check(expired.filter("","snapshot-stock","all",Collections.emptyList()).isEmpty());
        check(catalogue(raw,"new-revision",16000).byId.isEmpty());
        check(catalogue(raw,revision,9999).byId.isEmpty());
        check(AutoPetalState.displayRows(raw,"12","13",revision,16000).isEmpty());
        check(AutoPetalState.displayRows(raw,"13","13",revision,16000).isEmpty());
        check(AutoPetalState.displayRows(raw.replace("end\n",""),"12","12",revision,16000).isEmpty());
        check(AutoPetalState.displayRows(raw.replace("\toff\t1\t","\toff\t0\t"),"12","12",revision,16000).isEmpty());
        check(catalogue(raw.replace("end\n","petal\tother-white\tflower-5\t1\t90\nend\n"),revision,16000).byId.size()==1);
        Map<String,String[]> rows=AutoPetalState.displayRows(raw,"12","12",revision,16000);rows.get("white-id")[4]="999";
        check(AutoPetalState.displayRows(raw,"12","12",revision,16000).get("white-id")[4].equals("80"));
        AutoPetalCatalogue updated=catalogue(raw.replace("10000","20000").replace("\t80\n","\t65\n"),revision,26000);
        check(updated.byId.get("white-id").lastStock==65&&updated.reportedAt==20000);
        for(AutoPetalCatalogue.Entry e:delayed.entries)if(!e.selectable())check(e.stock==-1&&e.lastStock==-1);
        System.out.println("PASS AutoPetalDisplay: "+checks+" captured-snapshot/expiry/unknown/live-guard/revision/PID checks; not transaction authority");
    }
}

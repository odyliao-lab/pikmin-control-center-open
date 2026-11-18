package dev.ody.pikmincontrol;
import java.util.*;
public final class DailyResultsTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    public static void main(String[] args){
        long now=1704110400000L;DailyResults r=new DailyResults(now,TimeZone.getTimeZone("UTC"));
        String dispatched=now+"\tinventory-confirmed\tseed\ttask\t1000\t1\n";
        r.ingest("dispatch",dispatched+dispatched,now,true);r.ingest("dispatch",now+"\tstart-requested\tfruit\tother\t1\t1",now,true);
        check(r.evidence.get("seed").size()==1&&r.evidence.get("fruit").isEmpty());
        r.ingest("feeding",now+"\tfeed-confirmed\twhite\n"+now+"\twaiting-rpc-no-retry\twhite",now,true);
        check(r.evidence.get("feed").size()==1&&r.evidence.get("collect").isEmpty());
        check(r.formatted().contains("領取確認紀錄：未取得")&&r.formatted().contains("未推算"));
        r.ingest("rewards","MISSING",now,false);check(r.incomplete);
        r.ingest("rewards",now+"\tbatch-confirmed\ttask\t1\t1\t0\tkeep\tfruit\titem\n"+now+"\tbatch-confirmed\tpending\t1\t1\t1\tkeep\tfruit\titem",now,true);
        check(r.evidence.get("rewards").size()==1);
        r.ingest("planter",now+"\tpluck-confirmed\tseed\t1\tpikmin",now,true);check(r.evidence.get("pluck").size()==1);
        DailyResults copy=new DailyResults(now,TimeZone.getTimeZone("UTC"));copy.restore(r.encode());copy.ingest("dispatch",dispatched,now,true);check(copy.evidence.get("seed").size()==1);
        Properties bad=r.encode();bad.remove("ids.seed");try{copy.restore(bad);throw new AssertionError();}catch(IllegalArgumentException expected){}
        r.ingest("feeding",(r.start-1)+"\tfeed-confirmed\twhite",now,true);check(r.evidence.get("feed").size()==1);
        r.ingest("feeding",(now+1)+"\tfeed-confirmed\twhite",now,true);check(r.evidence.get("feed").size()==1);
        Calendar c=Calendar.getInstance(TimeZone.getTimeZone("America/New_York"));c.set(2024,Calendar.MARCH,10,12,0,0);
        DailyResults dst=new DailyResults(c.getTimeInMillis(),c.getTimeZone());check(dst.end-dst.start==23*3600000L);
        check(!r.encode().toString().contains("task"));
        System.out.println("PASS DailyResults: confirmed-only, duplicates, unknown/missing, persistence, truncation and local DST day; NOT full-day game totals");
    }
}

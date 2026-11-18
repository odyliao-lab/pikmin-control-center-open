package dev.ody.pikmincontrol;

public final class RouteHarvestSummaryTest {
    private static int checks;

    public static void main(String[] args) {
        duplicateObservationsAreNotExtraFruit();
        routeWindowAndKindsAreBounded();
        liveAndFinishedLabelsStayOperatorFacing();
        malformedRowsDoNotInventCounts();
        System.out.println("PASS RouteHarvestSummary: " + checks + " checks");
    }

    private static void duplicateObservationsAreNotExtraFruit() {
        RouteHarvestSummary s = new RouteHarvestSummary(1_000);
        s.ingest(row(1_100,"nearby-selection-settling","fruit","fruit-a")
                +row(1_200,"nearby-selection-reset","fruit","fruit-a")
                +row(1_300,"nearby-selection-settling","fruit","fruit-a")
                +row(1_400,"start-requested","fruit","fruit-a")
                +row(1_500,"start-requested","fruit","unrelated-manual"), 2_000);
        check(s.fruits()==1,"retry remains one fruit");
        check(s.dispatched()==1,"retry remains one dispatch");
        check(s.remaining()==0,"no false pending fruit");
    }

    private static void routeWindowAndKindsAreBounded() {
        RouteHarvestSummary s = new RouteHarvestSummary(2_000);
        s.ingest(row(1_999,"nearby-selection-settling","fruit","old")
                +row(2_100,"nearby-duration-blocked","fruit","old-long-trip")
                +row(2_200,"nearby-selection-settling","seed","seed-a")
                +row(2_300,"nearby-selection-settling","fruit","fruit-b")
                +row(2_400,"start-requested","fruit","fruit-b")
                +row(3_001,"nearby-selection-settling","fruit","future"), 3_000);
        check(s.fruits()==1,"only eligible in-window fruit observed");
        check(s.dispatched()==1,"only in-window fruit dispatched");
    }

    private static void liveAndFinishedLabelsStayOperatorFacing() {
        RouteHarvestSummary s = new RouteHarvestSummary(5_000);
        s.ingest(row(5_100,"nearby-selection-settling","fruit","fruit-a")
                +row(5_200,"nearby-selection-settling","fruit","fruit-b")
                +row(5_300,"start-requested","fruit","fruit-a"), 6_000);
        check(s.label(false).equals("水果 2　已派 1　待派 1"),"live label");
        check(s.label(true).equals("水果 2　已派 1　略過 1"),"finished label");
        RouteHarvestSummary empty = new RouteHarvestSummary(5_000);
        check(empty.label(false).equals("等待水果出現"),"empty live label");
        check(empty.label(true).equals("水果 0　已派 0　略過 0"),"empty finished label");
    }

    private static void malformedRowsDoNotInventCounts() {
        RouteHarvestSummary s = new RouteHarvestSummary(10_000);
        s.ingest("bad\n10x\tnearby-selection-settling\tfruit\tx\t1\t1\n"
                +row(10_100,"nearby-selection-settling","fruit","bad id")
                +row(10_150,"nearby-selection-settling","fruit","fruit-ok")
                +row(10_200,"start-requested","fruit","fruit-ok"), 11_000);
        check(s.fruits()==1&&s.dispatched()==1,"malformed rows ignored safely");
        boolean rejected=false;
        try{s.ingest(null,11_000);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"null framing rejected");
    }

    private static String row(long at,String event,String kind,String id) {
        return at+"\t"+event+"\t"+kind+"\t"+id+"\t1\t1\n";
    }

    private static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
}

package dev.ody.pikmincontrol;
public final class SeedPreviewTargetTest {
    static void check(boolean ok){if(!ok)throw new AssertionError("preview target binding");}
    static String report(String game){return "v1\t10000\t123\t"+game+"\tready\t1\t1\t0\t0\t0\n";}
    public static void main(String[] args){
        for(String game:new String[]{"157.0","156.1"}){
            check(new SeedPreviewState("on",report(game),"123","123",11000,game).applied);
            String other=game.equals("157.0")?"156.1":"157.0";
            check(!new SeedPreviewState("on",report(other),"123","123",11000,game).fresh);
            check(!new SeedPreviewState("on",report(game),"123","124",11000,game).applied);
            check(!new SeedPreviewState("on",report(game),"123","123",16000,game).fresh);
        }
        check(!new SeedPreviewState("on",report("156.1"),"123","123",11000,"156.x").fresh);
        check(!new SeedPreviewState("on",report("156.1"),"123","123",11000,null).fresh);
        check(new SeedPreviewState("on",report(SeedPreviewTarget.GAME),"123","123",11000).applied);
        System.out.println("PASS preview target: exact157.0/156.1 binding, unknown target/PID/stale rejected");
    }
}

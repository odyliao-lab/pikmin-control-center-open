package dev.ody.pikmincontrol;

public final class DispatchPreflightTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static String raw(long at, String row) { return "v1\t"+at+"\t42\n"+row+"\nend\t1\n"; }
    private static DispatchPreflight.Observation read(String raw, String kind, long now) {
        return DispatchPreflight.parse(raw, "42", "task", kind, now);
    }
    public static void main(String[] args) {
        String busy = "task\tgift\tgift-owner-busy\towner\tmushroom\t2\t0";
        DispatchPreflight.Observation first = read(raw(1000,busy), "gift", 1000);
        check(first != null && first.blocked());
        check(!first.confirms(first,1001));
        check(read(raw(6000,busy),"gift",6001).confirms(first,6001));
        check(!read(raw(6000,busy.replace("mushroom","other")),"gift",6001).confirms(first,6001));
        check(read(raw(1000,busy),"gift",13001)==null);
        check(read(raw(1001,busy),"gift",1000)==null);
        check(read(raw(1000,busy).replace("\t42\n","\t43\n"),"gift",1000)==null);
        check(DispatchPreflight.parse(raw(1000,busy),"42 43","task","gift",1000)==null);
        check(read(raw(1000,busy).replace("v1","v2"),"gift",1000)==null);
        check(read(raw(1000,busy).replace("end\t1","end\t2"),"gift",1000)==null);
        check(read(raw(1000,busy).replace("end\t1", ""),"gift",1000)==null);
        check(read(raw(1000,busy).replace(busy,busy+"\n"+busy).replace("end\t1","end\t2"),"gift",1000)==null);
        check(read(raw(1000,busy.replace("mushroom","task")),"gift",1000)==null);
        check(read(raw(1000,busy.replace("owner\t","\t")),"gift",1000)==null);
        check(read(raw(1000,busy.replace("\t2\t0","\t1\t0")),"gift",1000)==null);
        check(read(raw(1000,busy.replace("\t2\t0","\t2\t1")),"gift",1000)==null);
        check(read(raw(1000,busy),"fruit",1000)==null);
        check(!read(raw(1000,busy.replace("gift-owner-busy","unknown")),"gift",1000).blocked());
        check(!read(raw(1000,busy.replace("gift-owner-busy","future-reason")),"gift",1000).blocked());
        for (String kind : new String[]{"seed","fruit"}) {
            String none = "task\t"+kind+"\tno-available-team\t\t\t-1\t0";
            check(read(raw(1000,none),kind,1000).blocked());
            check(read(raw(1000,none.replace("\t-1\t0","\t-1\t1")),kind,1000)==null);
        }
        check(read("", "gift",1000)==null);
        check(read("v1\t1000\t42\nend\t0\n","gift",1000)==null);
        check(read(raw(1000,busy).replace("1000","bad"),"gift",1000)==null);
        check(read(raw(1000,busy).replace("\n","\r\n"),"gift",1000).blocked());
        check(!read(raw(14000,busy),"gift",14000).confirms(first,14000));
        String active = "task\tgift\tunknown\towner\ttask\t2\t0";
        check(read(raw(1000,active),"gift",1000).alreadyDispatched());
        check(read(raw(1000,active),"gift",1000).blocked());
        check(!read(raw(1000,active.replace("\t2\t0","\t32\t0")),"gift",1000).alreadyDispatched());
        check(!read(raw(1000,active.replace("owner","")),"gift",1000).alreadyDispatched());
        check(!read(raw(1000,active.replace("\t2\t0","\t2\t1")),"gift",1000).alreadyDispatched());
        check(read(raw(1000,active),"gift",14000)==null);
        check(read(raw(1100,active),"gift",1100).confirms(read(raw(1000,active),"gift",1000),1100));
        System.out.println("DispatchPreflightTest PASS " + checks + " checks");
    }
}

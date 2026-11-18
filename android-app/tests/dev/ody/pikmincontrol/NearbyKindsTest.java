package dev.ody.pikmincontrol;
public final class NearbyKindsTest {
    public static void main(String[] args) {
        check(NearbyKinds.parse(null) == 3);
        for (int i = 0; i < 8; i++) check(NearbyKinds.parse(NearbyKinds.encode(i)) == i);
        for (String bad : new String[]{"", "none", "fruit garbage", "farm", "all", "gift none"})
            check(NearbyKinds.parse(bad) == 0);
        check(NearbyKinds.parse(" gift\nfruit\tseed ") == 7);
        NearbyState ok = state("armed", "all", "fruit seed", "v1\t1000\t42\t3\tarmed\tall", "42", 2000);
        check(ok.fresh && ok.mask == 3 && ok.observed == 3 && !ok.busy);
        check(!state("armed", "all", "none", "v1\t1000\t42\t3\tarmed\tall", "43", 2000).fresh);
        check(!state("armed", "all", "none", "v1\t1000\t42\t3\tarmed\tall", "42", 92000).fresh);
        check(!state("off", "all", "none", "v1\t1000\t42\t3\tarmed\tall", "42", 2000).fresh);
        check(!state("armed", "all", "none", "v1\t3000\t42\t3\tarmed\tall", "42", 2000).fresh);
        check(!state("armed", "all", "none", "v1\t1000\t42\t8\tarmed\tall", "42", 2000).fresh);
        check(!state("armed", "all", "none", "v1\t1000\t42\t4294967299\tarmed\tall", "42", 2000).fresh);
        check(state("armed", "farm", "gift", "v1\t1000\t42\t4\tarmed\tfarm", "42", 2000).busy);
        check(state("batch", "all", "none", "", "42", 2000).busy);
        check(!state("off", "all", "none", "", "42", 2000).fresh);
        System.out.println("PASS: 8 mask round-trips, defaults, malformed config, native freshness/mode/PID");
    }
    static NearbyState state(String m, String l, String c, String a, String p, long n) {
        return new NearbyState(m,l,c,a,p,n);
    }
    static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}

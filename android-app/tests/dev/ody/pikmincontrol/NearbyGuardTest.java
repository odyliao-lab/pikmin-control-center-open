package dev.ody.pikmincontrol;
public final class NearbyGuardTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        String row = "v1\t1000\t42\tready\t0\t3\t25\t121\t25\t121\t1000\t300000";
        check(new NearbyGuard(row,"42",2000).supported);
        check(!new NearbyGuard(row,"43",2000).supported);
        check(!new NearbyGuard(row,"42",11001).supported);
        check(!new NearbyGuard(row,"42",999).supported);
        check(!new NearbyGuard(row.replace("300000","120000"),"42",2000).supported);
        check(!new NearbyGuard(row.replace("ready","unknown"),"42",2000).supported);
        check(!new NearbyGuard("","42",2000).supported);
        check(!new NearbyGuard(row+"\textra","42",2000).supported);
        check(new NearbyGuard(row.replace("ready","game-location-catching-up"),"42",2000).summary().contains("追趕中"));
        check(new NearbyGuard(row.replace("ready","waiting-game-location"),"42",2000).supported);
        check(!new NearbyGuard(row,"42",2000).timeOnly);
        check(new NearbyGuard(row.replace("v1\t","v2\t"),"42",2000).timeOnly);
        check(new NearbyGuard(row.replace("v1\t","v2\t"),"42",2000).summary().contains("≤ 5 分鐘"));
        System.out.println("PASS: 11 nearby guard heartbeat / PID / limit / state / fail-closed cases");
    }
}

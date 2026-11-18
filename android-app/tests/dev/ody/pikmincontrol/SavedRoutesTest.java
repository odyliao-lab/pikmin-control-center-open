package dev.ody.pikmincontrol;
import java.util.*;
public final class SavedRoutesTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    static void reject(Runnable op){try{op.run();throw new AssertionError();}catch(IllegalArgumentException expected){}}
    public static void main(String[] args) {
        String points="1,2\n1.001,2\n1,2";
        SavedRoutes empty=new SavedRoutes(Collections.emptyList()),one=empty.add("花田",points,1000,10);
        SavedRoutes loaded=SavedRoutes.decode(one.encode());SavedRoutes.Entry e=loaded.entries.get(0);
        check(e.coordinates.equals(points)&&e.route.points.size()==3&&e.route.points.get(0).lat==e.route.points.get(2).lat);
        check(e.label(1001).contains("剩約")&&e.label(601000).contains("已過期"));
        check(empty.add("未知",points,1000,0).entries.get(0).label(900000).contains("效期未設定"));
        reject(()->one.add("花田",points,1000,0));reject(()->one.add("bad\nname",points,1000,0));
        reject(()->empty.add("bad","1,2\nwrong",1000,0));reject(()->empty.add("bad",points,1000,1441));
        Properties truncated=one.encode();truncated.remove("0.points");reject(()->SavedRoutes.decode(truncated));
        Properties extra=one.encode();extra.put("unexpected","x");reject(()->SavedRoutes.decode(extra));
        SavedRoutes full=empty;for(int i=0;i<20;i++)full=full.add("route"+i,points,1000,0);
        SavedRoutes limit=full;reject(()->limit.add("overflow",points,1000,0));
        check(loaded.remove(e.id).entries.isEmpty());reject(()->loaded.remove("missing"));check(empty.entries.isEmpty());
        System.out.println("PASS SavedRoutes: private draft roundtrip, exact order/repeats, expiry/unknown, limits and corrupt refusal; NOT Android UI");
    }
}

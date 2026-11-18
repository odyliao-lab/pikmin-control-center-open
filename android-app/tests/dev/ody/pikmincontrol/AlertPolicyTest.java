package dev.ody.pikmincontrol;
public final class AlertPolicyTest {
    public static void main(String[] args) {
        if(!"review".equals(AlertPolicy.code("stop-incomplete","GPS",1,0))
                || !"location".equals(AlertPolicy.code("error","定位失效",0,0))
                || !"partial".equals(AlertPolicy.code("completed","",0,1))
                || !AlertPolicy.code("running","",2,0).isEmpty()
                || !AlertPolicy.code("stopped","",0,0).isEmpty()
                || !AlertPolicy.code("completed","",0,0).isEmpty())throw new AssertionError("classification");
        if(AlertPolicy.due(1000,2000)||!AlertPolicy.due(1000,901000)||!AlertPolicy.due(2000,1000)
                || !AlertPolicy.due(0,100))throw new AssertionError("dedupe including clock rollback");
        if(AlertPolicy.message("secret-coordinate").contains("secret-coordinate"))throw new AssertionError("raw data leaked");
        System.out.println("PASS AlertPolicy: categories, 15-minute cooldown, no raw detail disclosure");
    }
}

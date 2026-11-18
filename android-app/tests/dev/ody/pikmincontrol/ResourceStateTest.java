package dev.ody.pikmincontrol;
public final class ResourceStateTest {
    public static void main(String[] args) {
        String row="v1\t1000\t123\t90\t100\t40\t50\t21\twhite\tend";
        ResourceState s=ResourceState.parse(row,"123","123",2000);
        if(!s.blocker(true,true).isEmpty() || !s.summary().contains("即將滿倉"))throw new AssertionError("healthy/low warning");
        if(ResourceState.parse(row.replace("21\t","20\t"),"123","123",2000).blocker(false,true).isEmpty())throw new AssertionError("reserve boundary");
        for(String r:new String[]{"",row+"\textra",row.replace("end","bad"),row.replace("90\t","-2\t"),row.replace("white","")})
            if(ResourceState.parse(r,"123","123",2000).blocker(true,true).isEmpty())throw new AssertionError("bad input allowed");
        if(ResourceState.parse(row,"123","124",2000).seedMax!=-1 || ResourceState.parse(row,"123","123",14000).petals!=-1
                || ResourceState.parse(row,"123","123",999).petals!=-1)throw new AssertionError("fresh same process");
        if(!new ResourceState().blocker(false,false).isEmpty())throw new AssertionError("unrelated work blocked");
        System.out.println("PASS ResourceState: exact protocol, unknown/freshness, reserves, targeted guards");
    }
}

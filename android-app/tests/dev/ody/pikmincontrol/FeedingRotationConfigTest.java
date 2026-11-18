package dev.ody.pikmincontrol;
public final class FeedingRotationConfigTest {
    static void check(boolean x){if(!x)throw new AssertionError();}
    public static void main(String[] args){
        FeedingConfig c=new FeedingConfig();check(!c.rotate);c.rotate=true;c.enabled=true;
        c.rules.put("flower-5:1",new FeedingConfig.Rule(-1,-1));
        FeedingConfig d=FeedingConfig.parse(c.encode());check(d.rotate&&d.enabled&&d.rules.size()==1);check(d.copy().rotate);
        for(String old:new String[]{"v1\t00000000-0000-0000-0000-000000000001\t1\t4\t100\t250\t1\t0\nend\n",
            "v2\t00000000-0000-0000-0000-000000000001\t1\t4\t100\t250\t1\t1\t0\nend\n"})check(!FeedingConfig.parse(old).rotate);
        for(String broken:new String[]{c.encode().replace("\t1\t1\t1\t1\n","\t1\t1\t2\t1\n"),c.encode().replace("end\n","")}){
            boolean failed=false;try{FeedingConfig.parse(broken);}catch(IllegalArgumentException expected){failed=true;}check(failed);
        }
        check(FeedingHistory.label("rotation-sent").contains("等待"));check(FeedingHistory.label("rotate-confirmed").contains("完成"));
        System.out.println("PASS feeding rotation config: v3 roundtrip, v1/v2 off migration, malformed, Chinese status");
    }
}

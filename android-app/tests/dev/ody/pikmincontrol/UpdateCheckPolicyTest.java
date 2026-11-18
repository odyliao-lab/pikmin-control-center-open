package dev.ody.pikmincontrol;
public final class UpdateCheckPolicyTest {
    static int checks;
    static void ok(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    public static void main(String[] args){
        long start=1_000_000L;
        for(int failures=0;failures<5;failures++){
            ok(!UpdateCheckPolicy.due(false,start+100_000_000,start,0,failures));
            ok(UpdateCheckPolicy.due(true,start,0,0,failures));
            ok(UpdateCheckPolicy.due(true,start-1,start,0,failures));
        }
        long[] waits={300_000,300_000,1_800_000,86_400_000,86_400_000};
        for(int i=0;i<waits.length;i++){
            ok(!UpdateCheckPolicy.due(true,start+waits[i]-1,start,0,i));
            ok(UpdateCheckPolicy.due(true,start+waits[i],start,0,i));
        }
        ok(!UpdateCheckPolicy.due(true,start+86_400_000-1,start,start+1,0));
        ok(UpdateCheckPolicy.due(true,start+86_400_000,start,start+1,0));
        ok(UpdateCheckPolicy.status(202,204,28,33,"0.30.0").contains("本機安裝序號較高"));
        ok(UpdateCheckPolicy.status(212,213,28,33,"0.32.4").equals("本機安裝序號較高（213）；目前正式版 0.32.4（212）"));
        ok(UpdateCheckPolicy.status(214,213,28,33,"0.32.5").equals("發現新版 0.32.5"));
        ok(UpdateCheckPolicy.status(204,204,28,33,"0.31.1").equals("目前已是最新正式版"));
        ok(UpdateCheckPolicy.status(205,204,35,33,"0.31.2").contains("Android"));
        ok(UpdateCheckPolicy.status(205,204,28,33,"0.31.2").equals("發現新版 0.31.2"));
        System.out.println("PASS update metadata retry/status policy "+checks+" checks; no device or network");
    }
}

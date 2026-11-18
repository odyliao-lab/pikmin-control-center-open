package dev.ody.pikmincontrol;
public final class FeedingSquadDiagnosticsTest {
    static int checks;
    static void check(boolean value){if(!value)throw new AssertionError("diagnostic "+checks);checks++;}
    public static void main(String[] args){
        FeedingConfig c=new FeedingConfig();c.enabled=true;c.hearts=4;c.collect=true;c.rules.put("flower-5:1",new FeedingConfig.Rule(-1,-1));
        String v2="v2\t1000\t42\tobserve-only\tsquad-unverified\t3\n"
            +"pikmin\ta\t1\t2.5\t4\t1\t2\t0\n"
            +"pikmin\tb\t1\t2.5\t4\t6\t2\t1\n"
            +"pikmin\tc\t1\t4.0\t4\t2\t1\t0\nend\n";
        String parsed=FeedingSquadDiagnostics.parse(v2,"42","42",2000,c);
        check(parsed.contains("本次原生隊伍快照：3 隻"));check(parsed.contains("可餵食"));check(parsed.contains("遊戲原生暫時限制操作"));check(parsed.contains("已達友好度門檻"));check(parsed.contains("僅觀察"));
        String v1=v2.replaceFirst("v2","v1").replace("\t2\t0\n","\t2\n").replace("\t2\t1\n","\t2\n").replace("\t1\t0\nend","\t1\nend");
        check(FeedingSquadDiagnostics.parse(v1,"42","42",2000,c).contains("限制旗標尚未提供"));
        check(FeedingSquadDiagnostics.parse(v2,"42","43",2000,c).contains("尚無可用"));
        check(FeedingSquadDiagnostics.parse(v2,"42","42",13001,c).contains("尚無可用"));
        check(!FeedingSquadDiagnostics.unavailable(parsed));
        check(FeedingSquadDiagnostics.unavailable("尚無可用的隊伍診斷；請停在遊戲內並稍候重試。"));
        System.out.println("PASS FeedingSquadDiagnostics: "+checks+" v1/v2, raw count observation, restriction and candidate reasons");
    }
}

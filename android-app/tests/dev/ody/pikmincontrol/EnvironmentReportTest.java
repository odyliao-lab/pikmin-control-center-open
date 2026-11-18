package dev.ody.pikmincontrol;
public final class EnvironmentReportTest {
    static int n;static void check(boolean b){if(!b)throw new AssertionError("case "+n);n++;}
    public static void main(String[] args){
        String good="root_uid=0\nnative=1.5.16\nnative_disabled=no\ngame=157.0\njoystick=5.3.3\nruntime=compatible\npid=123\nguard_pid=123\nguard_at=9000\nguard_schema=v2\nguard_limit=300000\ndispatch_perm=644\ncopy=v152-r13";
        EnvironmentReport r=new EnvironmentReport(good,"0.8.3",34,true,10000);check(!r.warning);check(r.summary.contains("不限距離能力已載入"));
        check(new EnvironmentReport(good,"0.8.3",34,true,30000).summary.contains("等待新回報"));
        check(new EnvironmentReport(good.replace("v2","v1"),"0.8.3",34,true,10000).summary.contains("需1.4.23"));
        check(new EnvironmentReport(good.replace("guard_pid=123","guard_pid=124"),"x",34,true,10000).summary.contains("需1.4.23"));
        check(new EnvironmentReport(good,"x",34,false,10000).warning);
        check(new EnvironmentReport(good.replace("native_disabled=no","native_disabled=yes"),"x",34,true,10000).warning);
        check(new EnvironmentReport(good.replace("644","600"),"x",34,true,10000).warning);
        check(new EnvironmentReport(good.replace("157.0","152.0"),"x",34,true,10000).warning);
        check(new EnvironmentReport(good.replace("5.3.3","5.4.0"),"x",34,true,10000).warning);
        check(!new EnvironmentReport(good+"\nprivate=GPS -12.125,47.25 secret\n","x",34,true,10000).details.contains("secret"));
        check(new EnvironmentReport("","x",35,false,10000).details.contains("Android 15"));
        String unified=good.replace("native=1.5.16","native=1.5.0")+"\nnative_code=68\npair_version=1.5.0\npair_code=68\npair_unified=yes\npair_files=yes\npair_pid=123\npair_at=9500\n";
        check(!new EnvironmentReport(unified,"x",34,true,10000).warning);
        check(new EnvironmentReport(unified,"x",34,true,10000).details.contains("整合核心 · 同程序已載入"));
        check(new EnvironmentReport(unified.replace("pair_files=yes","pair_files=no"),"x",34,true,10000).warning);
        check(new EnvironmentReport(unified.replace("pair_pid=123","pair_pid=124"),"x",34,true,10000).warning);
        check(new EnvironmentReport(unified.replace("pair_at=9500","pair_at=10001"),"x",34,true,10000).warning);
        check(new EnvironmentReport(unified.replace("pair_code=68","pair_code=67"),"x",34,true,10000).warning);
        System.out.println("PASS EnvironmentReport "+n+" checks");
    }
}

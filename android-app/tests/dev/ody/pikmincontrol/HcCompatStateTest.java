package dev.ody.pikmincontrol;
public final class HcCompatStateTest {
    static int checks;
    static void check(boolean pass){checks++;if(!pass)throw new AssertionError("HC UI policy "+checks);}
    static String row(long at,String pid,int sdk,String phase,String query,long q){return "v1\t"+at+"\t"+pid+"\t"+sdk+"\t"+phase+"\ton\tavailable\tavailable\tgranted\tgranted\t"+query+"\t"+q;}
    public static void main(String[] args){
        for(int sdk=0;sdk<100;sdk++)check(HcCompatPolicy.allowedSdk(sdk)==(sdk>=28&&sdk<=33));
        for(String v:new String[]{null,"","ON","on\r\n","on\noff"," on","off"})check(!HcCompatPolicy.requested(v));
        check(HcCompatPolicy.requested("on"));check(HcCompatPolicy.requested("on\n"));
        HcCompatState ok=new HcCompatState(33,"on",row(10000,"42",33,"hook-ready","existing-nonzero",9900),"42","42",11000);
        check(ok.fresh&&ok.desired&&ok.message.contains("已讀到既有紀錄")&&ok.message.contains("不代表新增步數採計"));
        for(int sdk:new int[]{34,35,36}){
            HcCompatState off=new HcCompatState(sdk,"on",row(10000,"42",sdk,"hook-ready","existing-nonzero",9900),"42","42",11000);
            check(!off.desired&&!off.fresh&&off.message.contains("不需要"));
        }
        for(String raw:new String[]{"",null,"v1",row(1,"42",33,"hook-ready","zero",0),row(12000,"42",33,"hook-ready","zero",0),row(10000,"43",33,"hook-ready","zero",0),row(10000,"42",34,"hook-ready","zero",0),row(10000,"42",33,"hook-ready","zero",12000)}){
            HcCompatState missing=new HcCompatState(33,"on",raw,"42","42",11000);check(!missing.fresh&&missing.message.contains("等待"));
        }
        check(!new HcCompatState(33,"on",row(10000,"42",33,"hook-ready","zero",0),"42","43",11000).fresh);
        check(new HcCompatState(33,"on",row(10000,"42",33,"legacy-present","none",0),"42","42",11000).message.contains("獨立"));
        check(new HcCompatState(33,"on",row(10000,"42",33,"identity-mismatch","none",0),"42","42",11000).message.contains("未套用"));
        check(new HcCompatState(33,"off",row(10000,"42",33,"hook-ready","existing-nonzero",9900),"42","42",11000).message.contains("已關閉"));
        check(new HcCompatState(33,"on",row(10000,"42",33,"hook-ready","unknown",0),"42","42",11000).message.contains("等待確認"));
        check(HcCompatState.loaderMatches("v1\t10000\t42\t1.18.1\t138\t0","42","42","1.18.1",138,11000));
        check(HcCompatState.loaderMatches("v1\t10000\t42\t1.18.1\t138\t1","42","42","1.18.1",138,11000));
        for(String raw:new String[]{null,"","v1\t1\t42\t1.18.1\t138\t1","v1\t12000\t42\t1.18.1\t138\t1","v1\t10000\t43\t1.18.1\t138\t1","v1\t10000\t42\t1.18.0\t137\t1","v1\t10000\t42\t1.18.1\t138\tunknown"})
            check(!HcCompatState.loaderMatches(raw,"42","42","1.18.1",138,11000));
        check(!HcCompatState.loaderMatches("v1\t10000\t42\t1.18.1\t138\t1","42","43","1.18.1",138,11000));
        System.out.println("PASS HC compatibility "+checks+" SDK boundary, stale/owner/schema and unknown/read-only presentation checks; not device acceptance");
    }
}

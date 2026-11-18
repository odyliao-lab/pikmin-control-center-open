package dev.ody.pikmincontrol;
public final class CorePairTest {
    static int n; static void check(boolean b){if(!b)throw new AssertionError("core pair "+n);n++;}
    public static void main(String[] args)throws Exception{
        String raw="schema=1\nversion=1.4.29\ncode=63\napp=56\nsize=100\nsha256="+new String(new char[64]).replace('\0','a')+"\n";
        CorePair p=new CorePair(raw);check(p.app==56&&p.code==63);
        String live="v1\t1000\t42\t1.4.29\t63\t157.0\n";
        check(p.runtime(live,"42","42",2000));check(!p.runtime(live,"42","43",2000));
        check(!p.runtime(live,"43","43",2000));check(!p.runtime(live,"42","42",13001));check(!p.runtime(live,"42","42",999));
        check(!p.runtime(live.replace("63","62"),"42","42",2000));check(!p.runtime(live.replace("157.0","152.0"),"42","42",2000));
        check(!p.runtime("","42","42",2000));check(!p.runtime(live,"","",2000));
        check(!p.runtime(live.replace("157.0","153.0"),"42","42",2000));
        for(String bad:new String[]{raw+"app=56\n",raw.replace("code=63","code=-1"),raw.replace("schema=1","schema=2"),raw.replace("size=100","size=999999999"),raw.replace("version=1.4.29","version=x")}){
            boolean rejected=false;try{new CorePair(bad);}catch(Exception e){rejected=true;}check(rejected);}
        check(CorePair.upgrade(60,63));check(!CorePair.upgrade(63,63));check(!CorePair.upgrade(64,63));check(!CorePair.upgrade(0,63));
        CorePair u=new CorePair(raw.replace("schema=1","schema=2").replace("1.4.29","1.5.0").replace("code=63","code=68"));
        String gps="v1\t1000\t42\t1.5.0\t68\t1\n";
        check(u.unified);check(u.gpsRuntime(gps,"42","42",2000));
        check(!u.gpsRuntime(gps,"42","43",2000));check(!u.gpsRuntime(gps,"43","43",2000));
        check(!u.gpsRuntime(gps,"42","42",13001));check(!u.gpsRuntime(gps,"42","42",999));
        check(!u.gpsRuntime(gps.replace("68","67"),"42","42",2000));
        check(!u.gpsRuntime(gps.replace("1.5.0","1.4.33"),"42","42",2000));
        check(!u.gpsRuntime(gps.replace("\t1\n","\t0\n"),"42","42",2000));
        check(!u.gpsRuntime("","42","42",2000));check(p.gpsRuntime("","42","42",2000));
        java.util.Properties module=new java.util.Properties();
        module.setProperty("id","pikmin_nectar_rpc");module.setProperty("version","1.4.2");module.setProperty("versionCode","36");
        String sha=new String(new char[64]).replace('\0','a');
        check(!CorePair.pending(module,36,false,141,100,sha).unified);
        for(int failure=0;failure<5;failure++){
            java.util.Properties bad=(java.util.Properties)module.clone();
            if(failure==0)bad.setProperty("pccUnified","1"); // code36 cannot claim unified.
            if(failure==1)bad.setProperty("pccUnified","0");
            if(failure==2)bad.setProperty("id","other");
            if(failure==3)bad.setProperty("versionCode","37");
            boolean rejected=false;try{CorePair.pending(bad,36,failure==4,141,100,sha);}catch(Exception e){rejected=true;}check(rejected);
        }
        module.setProperty("version","1.5.37");module.setProperty("versionCode","105");module.setProperty("pccUnified","1");
        check(CorePair.pending(module,105,true,141,100,sha).unified);
        check(CorePair.pending(module,105,false,141,100,sha).unified);
        System.out.println("PASS CorePair: "+n+" version, stale/PID/runtime, malformed metadata and downgrade checks; NOT Magisk execution");
    }
}

package dev.ody.pikmincontrol;
public final class LegacyGpsSnapshotTest {
    static int n;static void check(boolean b){if(!b)throw new AssertionError("GPS snapshot "+n);++n;}
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--command")){System.out.print(LegacyGpsSnapshot.command());return;}
        if(args.length==1&&(args[0].startsWith("--proof-")||args[0].startsWith("--archive-"))) {
            boolean archive=args[0].startsWith("--archive-");String state=args[0].substring(archive?10:8);
            String fields=state.equals("absent")?"-\t-":SetupBundle.sha("gps\n".getBytes("UTF-8"))+"\t"+SetupBundle.sha("gps-so\n".getBytes("UTF-8"));
            LegacyGpsSnapshot g=new LegacyGpsSnapshot("v1\t"+state+"\t"+fields);
            System.out.print(archive?CoreFreshInstall.gpsArchive("11111111-2222-3333-4444-555555555555",g):g.proof(state.equals("disabled")));return;
        }
        String hash=new String(new char[64]).replace('\0','a');
        for(String state:new String[]{"absent","enabled","disabled"}) {
            String fields=state.equals("absent")?"-\t-":hash+"\t"+hash;
            LegacyGpsSnapshot gps=new LegacyGpsSnapshot("v1\t"+state+"\t"+fields);
            check(gps.state.equals(state));check(!gps.proof(true).contains("rm "));check(!gps.proof(false).contains("printf"));
            CoreInstallSnapshot core=new CoreInstallSnapshot("v1\t67\t"+hash+"\t"+hash+"\t"+hash+"\t"+state+"\t"+fields);
            check(gps.raw.equals(LegacyGpsSnapshot.from(core).raw));
            check(gps.proof(true).contains(LegacyGpsSnapshot.STAGED));
        }
        for(String bad:new String[]{null,"","v2\tabsent\t-\t-","v1\tabsent\t-\t-\n","v1\tabsent\t"+hash+"\t-",
                "v1\tenabled\t-\t-","v1\tunknown\t"+hash+"\t"+hash,"v1\tdisabled\t$(id)\t"+hash}) {
            boolean rejected=false;try{new LegacyGpsSnapshot(bad);}catch(Exception e){rejected=true;}check(rejected);
        }
        check(!LegacyGpsSnapshot.command().contains("pikmin_nectar_rpc"));
        check(LegacyGpsSnapshot.command().contains("test -d /data/adb/modules_update"));
        System.out.println("PASS LegacyGpsSnapshot: "+n+" independent state/proof checks; NOT mixed installation or Android validation");
    }
}

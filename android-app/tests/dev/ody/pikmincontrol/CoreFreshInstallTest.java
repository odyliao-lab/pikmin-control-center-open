package dev.ody.pikmincontrol;

public final class CoreFreshInstallTest {
    static int n;
    static void check(boolean b){if(!b)throw new AssertionError("case "+n);n++;}
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--command")){System.out.print(CoreFreshInstall.recoveryProof(CoreUpdater.BASE,CoreModuleProofTest.zip(false)));return;}
        for(String s:new String[]{"prepared","gps-intent","copy-intent","install-intent","staged","accepted","remove-intent"})check(CoreFreshInstall.rollbackPhase(s));
        for(String s:new String[]{null,"","prepare-intent","removed","restored","unknown","accepted\n"})check(!CoreFreshInstall.rollbackPhase(s));
        String absent=CoreFreshInstall.coreAbsent();
        for(String path:new String[]{CoreUpdater.BASE,CoreUpdater.STAGED,CoreFreshInstall.GPS,"/data/adb/modules_update/zygisk_pikmin_gps_copy"})check(absent.contains(path));
        check(absent.contains("test ! -L \"$p\""));check(absent.contains("test ! -e \"$p\""));
        check(!absent.contains("for p in "+CoreFreshInstall.GPS));
        check(!absent.contains("rm ")&&!absent.contains("mv ")&&!absent.contains("magisk --install"));
        for(String bad:new String[]{"/","/data/adb/modules/other","/data/adb/modules/pikmin_nectar_rpc/../x","/data/adb/pcc-core-backups/x/removed-core"}) {
            boolean rejected=false;try{CoreFreshInstall.recoveryProof(bad,CoreModuleProofTest.zip(false));}catch(Exception e){rejected=true;}check(rejected);
        }
        System.out.println("PASS CoreFreshInstall: "+n+" phase/path checks; NOT transaction/fault/root/reboot validation");
    }
}

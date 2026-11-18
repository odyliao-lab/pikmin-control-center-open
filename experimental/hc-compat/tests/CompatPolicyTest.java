import dev.ody.pikmincontrol.hccompat.CompatPolicy;
public final class CompatPolicyTest {
    static int checks;
    static void check(boolean v) { checks++; if(!v) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        check(CompatPolicy.target(CompatPolicy.PACKAGE,CompatPolicy.PACKAGE,33,"2306EPN60G"));
        check(!CompatPolicy.target(CompatPolicy.PACKAGE,"android",33,"2306EPN60G"));
        check(!CompatPolicy.target("other",CompatPolicy.PACKAGE,33,"2306EPN60G"));
        for(int sdk:new int[]{28,32,34,35}) check(!CompatPolicy.target(CompatPolicy.PACKAGE,CompatPolicy.PACKAGE,sdk,"2306EPN60G"));
        check(!CompatPolicy.target(CompatPolicy.PACKAGE,CompatPolicy.PACKAGE,33,"Pixel 6 Pro"));
        check(CompatPolicy.APK_SHA.equals("4d28ce6c8e5c7efa1e6a864edaa545991ffe52597f751d37db55bce69c4af957"));
        check(CompatPolicy.LEGACY_APK_SHA.equals("070c75d6e253317438dc4ac1b0f60b5dbf9e5250763827b7c6b6197f0e81e9dc"));
        check(CompatPolicy.identity("157.0",1793360009L,CompatPolicy.APK_SHA));
        check(CompatPolicy.identity("156.1",1793076318L,CompatPolicy.LEGACY_APK_SHA));
        for(String version:new String[]{"156.1","157.0","157.1",null})
          for(long code:new long[]{1793076318L,1793360009L,0})
            for(String hash:new String[]{CompatPolicy.APK_SHA,CompatPolicy.LEGACY_APK_SHA,"",null}) {
              boolean expected=("157.0".equals(version)&&code==1793360009L&&CompatPolicy.APK_SHA.equals(hash))
                  ||("156.1".equals(version)&&code==1793076318L&&CompatPolicy.LEGACY_APK_SHA.equals(hash));
              check(CompatPolicy.identity(version,code,hash)==expected);
            }
        check(!CompatPolicy.identity("156.0",1792142832L,"61c13513f3710b469f33ae3f63af9eaeca7b80fa046f3669ac73fa35392bdd8a"));
        check(!CompatPolicy.identity("156.1",1793076318L,"8403677b1a29eea6faf14d27e4accab7e1be575afb3a56583fe3834f988856c2"));
        check(!CompatPolicy.identity("155.0",1791279164L,"303685dc63a8f8b927c10da19499ffd76fce081c4d0d5e2f61b54692d8e9cc49"));
        check(!CompatPolicy.identity("154.0",1793076318L,CompatPolicy.APK_SHA));
        check(!CompatPolicy.identity("156.1",1L,CompatPolicy.APK_SHA));
        check(!CompatPolicy.identity("156.1",1793076318L,""));
        for(boolean on:new boolean[]{false,true}) for(boolean identity:new boolean[]{false,true})
          for(boolean provider:new boolean[]{false,true}) for(int feature:new int[]{-1,1,2})
            check(CompatPolicy.support(on,identity,provider,feature)==(on&&identity&&provider&&feature==2));
        System.out.println("PASS HC compatibility policy " + checks + " checks (not device acceptance)");
    }
}

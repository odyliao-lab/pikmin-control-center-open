package dev.ody.pikmincontrol;

public final class CoreInstallSnapshotTest {
    static int n;
    static void check(boolean value){if(!value)throw new AssertionError("snapshot "+n);n++;}
    static String hash(char c){return new String(new char[64]).replace('\0',c);}
    public static void main(String[] args)throws Exception {
        String raw="v1\t67\t"+hash('a')+"\t"+hash('b')+"\t"+hash('c')+"\tenabled\t"+hash('d')+"\t"+hash('e');
        CoreInstallSnapshot original=new CoreInstallSnapshot(raw);
        check(original.coreCode.equals("67"));check(original.coreSo.equals(hash('b')));
        check(original.unchanged(new CoreInstallSnapshot(raw)));check(!original.unchanged(null));
        for(String changed:new String[]{raw.replace("enabled","disabled"),raw.replace("\t67\t","\t68\t"),
                raw.replace(hash('a'),hash('f')),raw.replace(hash('b'),hash('f')),raw.replace(hash('c'),hash('f')),
                raw.replace(hash('d'),hash('f')),raw.replace(hash('e'),hash('f'))})
            check(!original.unchanged(new CoreInstallSnapshot(changed)));
        String absent="v1\t67\t"+hash('a')+"\t"+hash('b')+"\t"+hash('c')+"\tabsent\t-\t-";
        check(new CoreInstallSnapshot(absent).gpsState.equals("absent"));check(!original.unchanged(new CoreInstallSnapshot(absent)));
        for(String bad:new String[]{null,"",raw+"\textra",raw+"\n",raw.replace("v1","v2"),raw.replace("67","0"),
                raw.replace("67","-1"),raw.replace("67","99999999999999"),raw.replace("enabled","unknown"),
                raw.replace("enabled","absent"),absent.replace("absent","enabled"),raw.replace(hash('a'),""),
                raw.replace(hash('b'),hash('A')),raw.replace(hash('c'),"$(id)"),raw.replace(hash('d'),"-"),raw.replace(hash('e'),"-")}) {
            boolean rejected=false;try{new CoreInstallSnapshot(bad);}catch(Exception expected){rejected=true;}check(rejected);
        }
        System.out.println("PASS CoreInstallSnapshot: "+n+" parser/change/recovery checks; NOT root or Magisk execution");
    }
}

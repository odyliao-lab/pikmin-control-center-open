package dev.ody.pikmincontrol;
import java.util.*;
public final class CoreRollbackCatalogTest {
    interface Action {void run() throws Exception;}
    static int n;
    static void check(boolean ok){if(!ok)throw new AssertionError("catalog "+n);n++;}
    static void reject(Action a)throws Exception {boolean failed=false;try{a.run();}catch(java.io.IOException e){failed=true;}check(failed);}
    static CorePair pair(int code,int app,char hash)throws Exception {
        return new CorePair("schema="+(code>=68?2:1)+"\nversion=1.0.0\ncode="+code+"\napp="+app+"\nsize=100\nsha256="+new String(new char[64]).replace('\0',hash)+"\n");
    }
    public static void main(String[] args)throws Exception {
        CorePair old=pair(67,68,'a'), prior=pair(68,68,'b');
        List<CorePair> catalog=Arrays.asList(old,prior);
        CoreRollbackCatalog.validate(catalog,68,69);check(true);
        check(CoreRollbackCatalog.installed(catalog,"67",69)==old);
        check(CoreRollbackCatalog.installed(catalog,"68",69)==prior);
        check(CoreRollbackCatalog.recorded(catalog,prior.sha)==prior);
        check(CoreRollbackCatalog.recorded(catalog,old.sha)==old);
        CorePair exampleDevice=pair(36,140,'c');
        CoreRollbackCatalog.validate(Arrays.asList(exampleDevice),140,105);
        check(CoreRollbackCatalog.installed(Arrays.asList(exampleDevice),"36",105)==exampleDevice);
        reject(()->CoreRollbackCatalog.installed(Arrays.asList(exampleDevice),"37",105));
        for(String bad:Arrays.asList("","69","70","066","68\n","unknown"))reject(()->CoreRollbackCatalog.installed(catalog,bad,69));
        reject(()->CoreRollbackCatalog.recorded(catalog,""));
        reject(()->CoreRollbackCatalog.recorded(catalog,prior.sha.replace('b','c')));
        reject(()->CoreRollbackCatalog.validate(Collections.emptyList(),68,69));
        reject(()->CoreRollbackCatalog.validate(Arrays.asList(old,old),68,69));
        reject(()->CoreRollbackCatalog.validate(catalog,67,69));
        reject(()->CoreRollbackCatalog.validate(catalog,68,68));
        reject(()->CoreRollbackCatalog.validate(Collections.nCopies(17,old),68,69));
        List<CorePair> many=new ArrayList<>();
        for(int i=1;i<=96;i++){
            many.add(pair(i,100,'a'));
            if(i==65){CoreRollbackCatalog.validate(many,100,100);check(true);}
        }
        CoreRollbackCatalog.validate(many,100,100);check(true);
        many.add(pair(97,100,'a'));
        reject(()->CoreRollbackCatalog.validate(many,100,100));
        for(String gps:Arrays.asList("absent","enabled","disabled")) {
            check(!CoreRollbackCatalog.disableLegacyCopy(true,gps,false));
            check(!CoreRollbackCatalog.disableLegacyCopy(false,gps,true));
            check(CoreRollbackCatalog.disableLegacyCopy(false,gps,false)==CopyModeState.needsOff(gps));
        }
        System.out.println("PASS CoreRollbackCatalog: "+n+" selection/rejection checks; NOT package bytes or device acceptance");
    }
}

package dev.ody.pikmincontrol;
import java.util.*;
public class AutoPetalInventoryTest {
    static int checks;static String account=String.join("",Collections.nCopies(64,"a"));
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("case "+checks);}
    static String caps(long at,long epoch,long login){
        String[] c=new String[23];Arrays.fill(c,"0");
        c[0]="v1";c[1]=""+at;c[2]="12";c[3]="1.21.0";c[4]="146";c[5]="157.0";c[6]="1";
        c[7]="hooks_called";c[8]="-";c[9]="ready";
        c[10]=Arrays.stream(CoreCapabilities.STEPS).map(s->s+"=-").collect(java.util.stream.Collectors.joining(","));
        c[13]="1";c[14]=""+epoch;c[15]="ready";c[16]=""+login;c[20]="-1";c[22]="end";return String.join("\t",c)+"\n";
    }
    static String[] fixture(long now){
        String session="v1\t9000\t12\t"+account+"\tready\tbusy\tend\n";
        String raw="v2\t10000\t12\t"+account+"\t2\t100\t1\npetal\twhite\tflower-5\t1\t80\npetal\tred\tflower-5\t2\t0\nend\n";
        return new String[]{"12",session,caps(now,2,1000),raw,"",caps(now,2,1000),session,"12"};
    }
    static AutoPetalInventory parse(String[] p,long now){return AutoPetalInventory.parse(p,"1.21.0",146,now);}
    public static void main(String[] args){
        String[] p=fixture(11000);AutoPetalInventory s=parse(p,11000);
        check(s.valid&&s.at==10000&&s.epoch==2&&s.rows.size()==2);check(!s.needsGame(11000));
        AutoPetalCatalogue cat=new AutoPetalCatalogue(s,11000);check(cat.byId.size()==2&&cat.byId.get("white").lastStock==80);
        check(cat.byId.get("red").lastStock==0);
        s=parse(fixture(100000),100000);check(s.valid&&s.recent(100000)&&s.needsGame(100000));
        check(new AutoPetalCatalogue(s,100000).byId.get("white").lastStock==80); // Paused identity, fresh loader epoch.
        s=parse(fixture(310001),310001);check(s.valid&&!s.recent(310001));
        cat=new AutoPetalCatalogue(s,310001);check(cat.byId.size()==2&&cat.byId.get("white").lastStock==-1);
        for(int slot:new int[]{0,7}){p=fixture(11000);p[slot]="13";check(!parse(p,11000).valid);}
        for(int slot:new int[]{1,6}){p=fixture(11000);p[slot]=p[slot].replace(account,String.join("",Collections.nCopies(64,"b")));check(!parse(p,11000).valid);}
        for(int slot:new int[]{2,5}){
            p=fixture(11000);p[slot]=caps(11000,3,1000);check(!parse(p,11000).valid);
            p=fixture(11000);p[slot]=caps(1,2,1000);check(!parse(p,13000).valid);
            p=fixture(11000);p[slot]=caps(11001,2,1000);check(!parse(p,11000).valid);
            p=fixture(11000);p[slot]=caps(11000,2,10500);check(!parse(p,11000).valid);
        }
        for(String raw:new String[]{"",fixture(11000)[3].replace("v2\t","v3\t"),fixture(11000)[3].replace("10000\t12","12000\t12"),
                fixture(11000)[3].replace("end\n",""),fixture(11000)[3].replace("\t100\t1\n","\t100\t0\n"),
                fixture(11000)[3].replace("\t80\n","\t-1\n"),fixture(11000)[3].replace("end\n","petal\twhite\tflower-5\t1\t5\nend\n")}){
            p=fixture(11000);p[3]=raw;check(!parse(p,11000).valid);
        }
        p=fixture(11000);check(!AutoPetalInventory.parse(p,"1.20.3",145,11000).valid);
        p=fixture(11000);AutoPetalInventory a=parse(p,11000);p[3]=p[3].replace("10000\t12","10001\t12");
        check(a.contentKey(11000).equals(parse(p,11000).contentKey(11000)));
        p[3]=p[3].replace("\t80\n","\t70\n");check(!a.contentKey(11000).equals(parse(p,11000).contentKey(11000)));
        check(!new AutoPetalState().valid); // Read-only cache does not manufacture an action phase.
        System.out.println("PASS AutoPetalInventory: "+checks+" account/epoch/login/PID/pair/paused-snapshot/unknown/update checks; HOST only");
    }
}

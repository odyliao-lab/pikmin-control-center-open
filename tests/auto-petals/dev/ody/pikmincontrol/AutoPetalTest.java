package dev.ody.pikmincontrol;
public class AutoPetalTest {
    static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
    static void rejects(String raw){try{AutoPetalConfig.parse(raw);throw new AssertionError("accepted invalid config");}catch(IllegalArgumentException expected){checks++;}}
    public static void main(String[] args){
        check(AutoPetalState.color("1").equals("白"));check(AutoPetalState.color("2").equals("紅"));check(AutoPetalState.color("3").equals("藍"));check(AutoPetalState.color("4").equals("黃"));check(AutoPetalState.color("0").equals("未知"));
        AutoPetalConfig c=new AutoPetalConfig();c.order.add("white");c.order.add("red");c.enabled=true;String wire=c.encode();
        AutoPetalConfig empty=new AutoPetalConfig();
        empty.enabled=true;
        check(AutoPetalConfig.parse(empty.encode()).enabled&&AutoPetalConfig.parse(empty.encode()).order.isEmpty());
        empty.enabled=false;
        check(!AutoPetalConfig.parse(empty.encode()).enabled);
        check(AutoPetalConfig.parse(wire).order.equals(c.order));rejects(wire+"extra");rejects(wire.replace("\nend","\npetal\twhite\nend"));rejects(wire.replace("\t50\n","\t-1\n"));rejects(wire.replace("petal\twhite","petal\twhite'"));rejects(wire.replace("\ton\t","\tother\t"));rejects(wire.replace("end\n",""));
        String raw="v1\t1000\t12\t"+c.revision+"\tplanting\t1\twhite\t900\npetal\twhite\tflower-5\t1\t100\nend\n";
        check(AutoPetalState.parse(raw,"12","12",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw,"12","13",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw,"13","13",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw,"12","12",c.revision,7000).valid);
        check(!AutoPetalState.parse(raw,"12","12",c.revision,999).valid);
        check(!AutoPetalState.parse(raw,"12","12","wrong",1001).valid);
        check(!AutoPetalState.parse(raw.replace("end\n",""),"12","12",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw.replace("\t100\n","\t-1\n"),"12","12",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw.replace("\t1\twhite\t900","\t0\twhite\t900"),"12","12",c.revision,1001).valid);
        check(!AutoPetalState.parse(raw.replace("end\n","petal\twhite\tflower-5\t1\t100\nend\n"),"12","12",c.revision,1001).valid);
        System.out.println("PASS auto petal config/status "+checks+" checks");
    }
}

package dev.ody.pikmincontrol;

public final class DisplayOptionsStateTest {
    static int count;
    static void check(boolean b){count++;if(!b)throw new AssertionError("case "+count);}
    static final String ON="v1\t10000\t123\t157.0\tready\t1\t0\t0\t0\t0";
    static DisplayOptionsState state(String control,String... reports){return new DisplayOptionsState(control,reports,"123","123",11000);}
    public static void main(String[] args){
        if(args.length==3&&args[0].equals("--command")){
            System.out.print(DisplayOptionsGateway.configCommand(Integer.parseInt(args[1]),args[2].equals("migrate")?null:Boolean.valueOf(args[2].equals("on"))));return;
        }
        check(DisplayOptionsGateway.COUNT==4);
        check(java.util.Arrays.equals(DisplayOptionsGateway.members(0),new String[]{"seed-preview","seed-collection","planter-display"}));
        check(java.util.Arrays.equals(DisplayOptionsGateway.members(1),new String[]{"adult-state-preview","adult-decoration-preview"}));
        check(java.util.Arrays.equals(DisplayOptionsGateway.members(2),new String[]{"gift-preview","expedition-display"}));
        check(java.util.Arrays.equals(DisplayOptionsGateway.members(3),new String[]{"nectar-stock-preview"}));
        String[] copy=DisplayOptionsGateway.members(0);copy[0]="bad";check(!DisplayOptionsGateway.members(0)[0].equals("bad"));
        check(state("on",ON,ON,ON).desired&&state("on",ON,ON,ON).message.contains("對應頁面"));
        check(state("on",ON,ON,"").message.contains("等待遊戲回報"));
        check(state("on",ON,ON.replace("ready","metadata-mismatch")).message.contains("尚未就緒"));
        check(state("on",ON,ON.replace("\t1\t","\t0\t")).message.contains("等待套用"));
        check(state("off",ON,ON).message.contains("正在還原"));
        check(state("off",ON.replace("\t1\t","\t0\t")).message.contains("原資訊"));
        check(!state("off","").desired&&state("off","").message.contains("等待遊戲確認"));
        check(new DisplayOptionsState("on",new String[]{ON},"123","124",11000).message.contains("等待遊戲回報"));
        check(new DisplayOptionsState("on",new String[]{ON},"123","123",16001).message.contains("等待遊戲回報"));
        check(state("on",ON.replace("ready","waiting-cards")).message.contains("對應頁面"));
        for(String bad:new String[]{"","unknown","on\noff"}){
            boolean refused=false;try{state(bad,ON);}catch(IllegalStateException e){refused=true;}check(refused);
        }
        for(int bad:new int[]{-1,4}){
            boolean refused=false;try{DisplayOptionsGateway.configCommand(bad,true);}catch(IllegalArgumentException e){refused=true;}check(refused);
        }
        System.out.println("PASS display groups "+count+" mapping, current-report and unknown-state assertions");
    }
}

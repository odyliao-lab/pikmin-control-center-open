package dev.ody.pikmincontrol;
public final class FeedingTest {
    static int checks;
    static void check(boolean b){if(!b)throw new AssertionError("case "+checks);checks++;}
    static void reject(Runnable r){boolean rejected=false;try{r.run();}catch(Exception e){rejected=true;}check(rejected);}
    static FeedingPolicy.Facts facts(){FeedingPolicy.Facts f=new FeedingPolicy.Facts();f.complete=f.accountReady=f.inSquad=f.idle=f.canFeed=true;
        f.nectar=101;f.petals=249;f.capacity=300;f.cost=f.yield=1;f.heartMilli=3999;f.flowerStateCount=4;return f;}
    public static void main(String[] args){
        FeedingConfig c=new FeedingConfig();check(!c.enabled&&c.collect&&c.rules.isEmpty());check(c.ceiling==FeedingConfig.MAX_COUNT);
        c.ceiling=250; // Legacy protocol policy regression; automatic-capacity behavior has its own tests.
        c.enabled=true;c.rules.put("normal-red",new FeedingConfig.Rule(-1,-1));
        check(c.copy().encode().equals(c.encode()));check(c.reserve("normal-red")==100);
        c.rules.put("rose-blue",new FeedingConfig.Rule(150,200));check(c.copy().reserve("rose-blue")==150);check(c.copy().aggressivePick);
        String legacy="v1\t01234567-89ab-cdef-0123-456789abcdef\t1\t4\t100\t250\t1\t0\nend\n";check(FeedingConfig.parse(legacy).aggressivePick);
        c.aggressivePick=false;check(!FeedingConfig.parse(c.encode()).aggressivePick);c.aggressivePick=true;
        reject(()->new FeedingConfig.Rule(-1,20));reject(()->FeedingConfig.number("1.5",0,100));reject(()->FeedingConfig.number("-1",0,100));reject(()->FeedingConfig.number("1000001",0,1000000));
        reject(()->FeedingConfig.parse(c.encode().replace("end\n","")));reject(()->FeedingConfig.parse(c.encode().replace("normal-red","';reboot")));
        reject(()->FeedingConfig.parse(c.encode().replace("rule\trose-blue\t150\t200","rule\tnormal-red\t150\t200")));
        FeedingPolicy.Facts f=facts();check(FeedingPolicy.decide(c,"normal-red",f).equals("feed"));
        f.petals=0;check(FeedingPolicy.decide(c,"normal-red",f).equals("feed"));
        f.petals=-1;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-data"));f=facts();
        f.nectar=100;check(FeedingPolicy.decide(c,"normal-red",f).equals("nectar-reserve"));f=facts();f.reservedNectar=1;check(FeedingPolicy.decide(c,"normal-red",f).equals("nectar-reserve"));
        f=facts();f.yield=2;check(FeedingPolicy.decide(c,"normal-red",f).equals("petal-limit"));
        f=facts();f.reservedPetals=1;check(FeedingPolicy.decide(c,"normal-red",f).equals("petal-limit"));
        f=facts();f.heartMilli=4000;check(FeedingPolicy.decide(c,"normal-red",f).equals("friendship-reached"));
        f=facts();f.flowerStateCount=5;check(FeedingPolicy.decide(c,"normal-red",f).equals("feed"));f.flowerStateCount=6;check(FeedingPolicy.decide(c,"normal-red",f).equals("feed"));
        f=facts();c.aggressivePick=false;f.flowerStateCount=5;check(FeedingPolicy.decide(c,"normal-red",f).equals("feed"));c.aggressivePick=true;
        f=facts();f.priorityWork=true;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-other-work"));
        f=facts();f.pending=true;check(FeedingPolicy.decide(c,"normal-red",f).equals("pending-review"));
        f=facts();f.accountReady=false;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-data"));
        f=facts();f.idle=false;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-squad"));
        check(FeedingPolicy.decide(c,"new-kind",facts()).equals("not-allowed"));
        f=facts();f.canCollect=true;f.heartMilli=4000;check(FeedingPolicy.decide(c,"normal-red",f).equals("collect"));
        check(FeedingPolicy.decide(c,"unselected-manually-fed",f).equals("collect"));
        c.collect=false;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-collect"));c.collect=true;
        f=facts();f.cost=Long.MAX_VALUE;check(FeedingPolicy.decide(c,"normal-red",f).equals("nectar-reserve"));
        f=facts();f.reservedPetals=Long.MAX_VALUE;check(FeedingPolicy.decide(c,"normal-red",f).equals("petal-limit"));
        String s="v1\t1000\t42\tobserve-only\twaiting-validation\t-\t2\nkind\tnormal-red\tnormal\t普通\tred\t1\t200\t100\t300\nkind\trose-blue\trose\t玫瑰\tblue\t0\t0\t-1\t-1\nend\n";
        FeedingSnapshot snapshot=FeedingSnapshot.parse(s,"42","42",2000);check(snapshot.fresh&&!snapshot.ready&&snapshot.kinds.size()==2);
        check(snapshot.filter("玫瑰","all","all",c).size()==1);check(snapshot.filter("","stock","all",c).size()==1);check(snapshot.filter("","selected","all",c).size()==2);
        check(snapshot.message(c).contains("等待原生執行狀態"));
        String runtime="v2\t1000\t42\t"+c.revision+"\tfeed-confirmed\nend\n";
        snapshot.applyRuntime(runtime,"42","42",2000);check(snapshot.runtimeFresh&&snapshot.message(c).contains("餵食成功"));
        snapshot.applyRuntime(runtime.replace("feed-confirmed","collect-confirmed"),"42","42",2000);check(snapshot.message(c).contains("花瓣已入庫"));
        snapshot.applyRuntime(runtime.replace("feed-confirmed","pending-review"),"42","42",2000);c.enabled=false;check(snapshot.message(c).contains("自動核對"));c.enabled=true;
        snapshot.applyRuntime(runtime.replace("feed-confirmed","waiting-rpc-no-retry"),"42","42",2000);check(snapshot.message(c).contains("不會重送"));
        snapshot.applyRuntime(runtime,"42","43",2000);check(!snapshot.runtimeFresh);
        snapshot.applyRuntime(runtime,"42","42",13001);check(!snapshot.runtimeFresh);
        snapshot.applyRuntime(runtime,"42","42",999);check(!snapshot.runtimeFresh);
        snapshot.applyRuntime(runtime.replace("end\n",""),"42","42",2000);check(!snapshot.runtimeFresh);
        check(!FeedingSnapshot.parse(s,"42","43",2000).fresh);check(!FeedingSnapshot.parse(s,"42","42",999).fresh);check(!FeedingSnapshot.parse(s,"42","42",13001).fresh);
        check(!FeedingSnapshot.parse(s.replace("end\n",""),"42","42",2000).fresh);check(!FeedingSnapshot.parse(s.replace("rose-blue","normal-red"),"42","42",2000).fresh);
        FeedingSnapshot overflow=FeedingSnapshot.parse(s.replace("\t100\t300","\t318\t300"),"42","42",2000);
        check(overflow.fresh&&overflow.kinds.size()==2&&overflow.kinds.get(0).petals==318);
        f=facts();f.petals=318;check(FeedingPolicy.decide(c,"normal-red",f).equals("waiting-data")); // display acceptance never grants feeding
        for(int stock=0;stock<150;stock++)for(int petals=240;petals<=255;petals++){
            f=facts();f.nectar=stock;f.petals=petals;
            check(FeedingPolicy.decide(c,"normal-red",f).equals("feed")== (stock>=101&&petals<=249));
        }
        System.out.println("PASS Feeding: "+checks+" config/parser/picker filters and resource boundaries; NOT native execution");
    }
}

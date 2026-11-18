package dev.ody.pikmincontrol;
public final class FeedingCapacityTest {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("capacity "+checks);}
    public static void main(String[] args)throws Exception {
        FeedingConfig old=new FeedingConfig();old.enabled=true;old.hearts=3;old.reserve=76;old.ceiling=250;
        old.rules.put("a",new FeedingConfig.Rule(-1,-1));old.rules.put("b",new FeedingConfig.Rule(120,200));
        String raw=old.encode();FeedingSavedState migrated=new FeedingSavedState(raw,raw);
        FeedingConfig c=migrated.config;
        check(c.ceiling("a")==1000000&&c.ceiling("b")==1000000);
        check(c.reserve("a")==76&&c.reserve("b")==120&&c.hearts==3&&c.enabled&&c.collect);
        check(!c.revision.equals(old.revision));
        check(c.revision.equals(new FeedingSavedState(raw,raw).config.revision));
        check(!new FeedingSavedState(c.encode(),c.encode()).capacityMigrated);
        check(new FeedingSavedState("corrupt",raw).config.encode().equals(c.encode()));
        check(new FeedingSavedState("corrupt","corrupt").damaged);
        FeedingPolicy.Facts f=new FeedingPolicy.Facts();
        f.complete=f.accountReady=f.inSquad=f.idle=f.canFeed=true;
        f.nectar=500;f.petals=449;f.capacity=450;f.cost=1;f.yield=1;f.heartMilli=0;f.flowerStateCount=0;
        check(FeedingPolicy.decide(c,"a",f).equals("feed")); // above old manual 250
        f.canCollect=true;check(FeedingPolicy.decide(c,"a",f).equals("collect"));
        f.yield=2;check(FeedingPolicy.decide(c,"a",f).equals("petal-limit"));
        f.capacity=1000;check(FeedingPolicy.decide(c,"a",f).equals("collect")); // upgrade needs no APK setting change
        f.capacity=450;f.yield=1;f.reservedPetals=1;check(FeedingPolicy.decide(c,"a",f).equals("petal-limit"));
        f.reservedPetals=0;f.capacity=-1;check(FeedingPolicy.decide(c,"a",f).equals("waiting-data"));
        f.capacity=0;check(FeedingPolicy.decide(c,"a",f).equals("waiting-data"));
        f.capacity=300;check(FeedingPolicy.decide(c,"a",f).equals("waiting-data"));
        java.nio.file.Path app=java.nio.file.Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent();
        String panel=new String(java.nio.file.Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/FeedingPanel.java")),java.nio.charset.StandardCharsets.UTF_8);
        check(!panel.contains("花瓣達到即停止"));
        check(panel.contains("花瓣上限依遊戲容量"));
        String nativePolicy=new String(java.nio.file.Files.readAllBytes(app.getParent().resolve("native/automation/zygisk-module/cpp/feeding_policy.h")),java.nio.charset.StandardCharsets.UTF_8);
        check(nativePolicy.contains("std::min(f.capacity, c.ceiling)"));
        System.out.println("PASS FeedingCapacity: "+checks+" migration/live-capacity policy/source checks; NOT device feeding proof");
    }
}

package dev.ody.pikmincontrol;

public final class FeedingSavedStateTest {
    static void check(boolean value){if(!value)throw new AssertionError();}
    public static void main(String[] args){
        FeedingConfig config=new FeedingConfig();config.enabled=true;config.hearts=3;
        config.reserve=37;config.ceiling=451;config.collect=false;
        config.rules.put("flower-s-rose:1",new FeedingConfig.Rule(125,300));
        String raw=config.encode();
        FeedingConfig migrated=config.copy();migrated.useGameCapacity();String current=migrated.encode();
        // Android XML may retain closing-tag indentation after the final newline.
        check(FeedingConfig.parse(raw+"    \t\r\n").encode().equals(raw));
        FeedingSavedState fresh=new FeedingSavedState("","");
        check(!fresh.present&&!fresh.damaged&&!fresh.config.enabled);
        FeedingSavedState stored=new FeedingSavedState(raw,"");
        check(stored.present&&!stored.recovered&&!stored.damaged);
        check(stored.config.enabled&&stored.config.hearts==3&&stored.config.reserve==37&&!stored.config.collect);
        check(stored.config.rules.get("flower-s-rose:1").reserve==125);
        FeedingSavedState recovered=new FeedingSavedState("incomplete",raw);
        check(recovered.recovered&&!recovered.damaged&&recovered.config.encode().equals(current));
        check(new FeedingSavedState(raw,"corrupt").config.encode().equals(current));
        check(new FeedingSavedState("wrong","broken").damaged);
        check(new FeedingSavedState("",raw).recovered);
        // A runtime snapshot is not an input; missing squad/inventory cannot reset saved choices.
        check(new FeedingSavedState(raw,raw).config.encode().equals(current));
        check(stored.capacityMigrated&&stored.config.ceiling==FeedingConfig.MAX_COUNT);
        check(!new FeedingSavedState(current,current).capacityMigrated);
        System.out.println("PASS FeedingSavedState: settings roundtrip, backup recovery, corrupt data retained/fail closed");
    }
}

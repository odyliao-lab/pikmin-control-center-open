package dev.ody.pikmincontrol;
import java.util.*;
public final class SettingsProfileTest {
    static void check(boolean v){if(!v)throw new AssertionError();}
    static void reject(Runnable f){try{f.run();throw new AssertionError();}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        Map<String,String> m=new LinkedHashMap<>();for(String k:SettingsProfile.KEYS)m.put(k,"0");
        FeedingConfig f=new FeedingConfig();f.collect=false;f.rules.put("white",new FeedingConfig.Rule(-1,-1));
        m.put("name","散步");m.put("feeding",f.encode());m.put("kinds","3");m.put("postcard","keep");m.put("appearance","dark");m.put("rounds","1");m.put("dwell","10");m.put("timeout","5");
        m.put("stepTarget","50000");m.put("stepEnd","1380");m.put("stepRate","350");m.put("stepPreset","run");m.put("stepInterval","60000");
        SettingsProfile p=new SettingsProfile(m);String encoded=p.encode();SettingsProfile r=SettingsProfile.decode(encoded);
        check(r.feeding().rules.containsKey("white")&&!r.feeding().collect&&!r.feeding().enabled);check(r.steps().target==50000&&r.steps().ratePerMinute==350);
        reject(()->SettingsProfile.decode(encoded.substring(0,encoded.length()-1)));
        reject(()->SettingsProfile.decode(encoded.replace("PCCPROFILE/1","PCCPROFILE/2")));
        reject(()->SettingsProfile.decode(encoded.replace("kinds=","pid=")));
        Map<String,String> forbidden=new LinkedHashMap<>(m);forbidden.put("desiredRunning","true");reject(()->new SettingsProfile(forbidden));
        f.enabled=true;Map<String,String> running=new LinkedHashMap<>(m);running.put("feeding",f.encode());reject(()->new SettingsProfile(running));
        Map<String,String> bad=new LinkedHashMap<>(m);bad.put("stepRate","5000");reject(()->new SettingsProfile(bad));
        check(!encoded.contains("GPS")&&!encoded.contains("desiredRunning"));
        check(SettingsProfiles.differences(p,r).contains("條件相同"));
        Map<String,String> changed=new LinkedHashMap<>(m);changed.put("stepTarget","60000");check(SettingsProfiles.differences(p,new SettingsProfile(changed)).contains("50000→60000"));
        StepEngineTest.Fixture fixture=StepEngineTest.fx();fixture.engine.configureStopped(p.steps());check(fixture.engine.safeSettings().target==50000);
        fixture.engine.disclose();fixture.engine.start();
        try{fixture.engine.configureStopped(new SettingsProfile(changed).steps());throw new AssertionError();}catch(IllegalStateException expected){}
        check(fixture.engine.safeSettings().target==50000);
        System.out.println("PASS SettingsProfile: strict whitelist, checksum/truncation, portable choices and no run intent; NOT multi-store Android acceptance");
    }
}

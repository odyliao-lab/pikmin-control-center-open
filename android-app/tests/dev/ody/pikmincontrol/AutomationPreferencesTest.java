package dev.ody.pikmincontrol;

public final class AutomationPreferencesTest {
    static void reject(String key,String value){
        try{AutomationPreferences.validate(key,value);throw new AssertionError(key);}
        catch(IllegalArgumentException expected){}
    }
    public static void main(String[] args){
        for(String key:new String[]{"nectar","nearby","copy"}){
            AutomationPreferences.validate(key,"on");AutomationPreferences.validate(key,"off");reject(key,"unknown");
        }
        AutomationPreferences.validate("reward","all");AutomationPreferences.validate("reward","off");
        reject("reward","batch"); // temporary runtime mode is not a persistent user preference
        AutomationPreferences.validate("postcard","keep");AutomationPreferences.validate("postcard","discard");
        for(int i=0;i<8;i++)AutomationPreferences.validate("kinds",Integer.toString(i));
        reject("kinds","8");reject("kinds","-1");reject("route","start");reject("copy","on; reboot");
        System.out.println("PASS AutomationPreferences: known user choices only, temporary jobs and malformed values rejected");
    }
}

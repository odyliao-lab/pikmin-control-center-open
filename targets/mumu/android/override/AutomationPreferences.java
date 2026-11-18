package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** User choices only. Temporary batch/installer controls must never be copied into this store. */
final class AutomationPreferences {
    interface Action { void run() throws Exception; }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("automation_choices_v1",0);}
    static String value(Context c,String key,String fallback){
        Object stored=prefs(c).getAll().get(key);
        if(!(stored instanceof String))return fallback;
        try{validate(key,(String)stored);return (String)stored;}catch(Exception invalid){return fallback;}
    }
    static void choose(Context c,String key,String value,Action action)throws Exception {
        try {
        synchronized(AutomationPreferences.class){
            validate(key,value);
            if(!prefs(c).edit().putString(key,value).commit())throw new IllegalStateException("無法保存設定");
            action.run();
        }
        } finally { CollectionMonitorService.sync(c); }
    }
    static void chooseCollection(Context c,String key,boolean enabled)throws Exception {
        if(!key.equals("nectar")&&!key.equals("reward"))throw new IllegalArgumentException("非領取設定");
        // Persist before any runtime checks. An unavailable game must not undo the choice.
        try {
            choose(c,key,enabled?(key.equals("reward")?"all":"on"):"off",()->{
                CollectionMonitorService.markOff(c,key,!enabled);
                if(!enabled)FeedingGateway.root(key.equals("reward")?ReturnRewardModuleGateway.modeCommand("off"):NectarModuleGateway.controlCommand(false));
                if(!enabled)CollectionMonitorService.markOff(c,key,false);
            });
        } finally {CollectionMonitorService.sync(c);}
    }
    static void chooseNearby(Context c,boolean enabled)throws Exception {
        // No game/core/location precondition is allowed before persisting the user's intent.
        // The monitor uses its lease and capability proof before arming the native control.
        choose(c,"nearby",enabled?"on":"off",()->CollectionMonitorService.markOff(c,"nearby",!enabled));
    }
    static void validate(String key,String value){
        boolean valid;
        switch(key){
            case "nectar": case "nearby": case "copy": valid=value.equals("on")||value.equals("off");break;
            case "reward": valid=value.equals("all")||value.equals("off");break;
            case "postcard": valid=value.equals("keep")||value.equals("discard");break;
            case "kinds": valid=value.matches("[0-7]");break;
            default: valid=false;
        }
        if(!valid)throw new IllegalArgumentException("無效的設定");
    }
    static void stopChoices(Context c)throws Exception {
        synchronized(AutomationPreferences.class){
            if(!prefs(c).edit().putString("nectar","off").putString("nearby","off")
                    .putString("reward","off").commit())throw new IllegalStateException("無法保存停止設定");
            CollectionMonitorService.sync(c);
        }
    }
    private static void importExisting(Context c)throws Exception {
        String[] keys={"nectar","nearby","reward","postcard","copy","kinds"};
        boolean missing=false;for(String key:keys)if(!prefs(c).contains(key))missing=true;
        if(!missing)return;
        String[] files={"/data/adb/modules/pcc_emulator_bridge_canary/nectar_control_mode.txt",
                "/data/local/tmp/pikmin-dispatch-mode.txt","/data/local/tmp/pikmin-return-mode.txt",
                "/data/local/tmp/pikmin-return-postcard-policy.txt",
                "/data/user/0/com.nianticlabs.pikmin/files/gps_copy_mode.txt","/data/local/tmp/pikmin-nearby-kinds.txt"};
        String[] commands=new String[files.length];
        for(int i=0;i<files.length;i++)commands[i]="if [ -f "+files[i]+" ]; then cat "+files[i]+"; fi";
        String[] values=ProbeSnapshot.sections(FeedingGateway.root(ProbeSnapshot.command(commands)),files.length);
        SharedPreferences.Editor edit=prefs(c).edit();
        for(int i=0;i<keys.length;i++){
            if(prefs(c).contains(keys[i]))continue;
            String value=values[i].trim();
            if(i==0)value=value.equals("auto")?"on":value.equals("diag")?"off":value;
            if(i==1&&value.equals("armed"))value="on";
            if(i==5){
                if(!value.matches("(?:fruit|seed|gift)(?:\\s+(?:fruit|seed|gift))*|none"))continue;
                value=Integer.toString(NearbyKinds.parse(value));
            }
            try{validate(keys[i],value);edit.putString(keys[i],value);}catch(IllegalArgumentException invalid){}
        }
        if(!edit.commit())throw new IllegalStateException("無法保存原有設定");
    }
    static boolean reconcile(Context c){
        if(EmulatorSavedDailyRuntime.managed(c))return true;
        synchronized(AutomationPreferences.class){
            if(CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)
                    ||SetupDeployment.pending(c)||UpdateInstaller.pending(c)||WorkStatus.stoppingAll||WorkStatus.blocked(c)
                    ||NearbyWorkState.pending(c)
                    ||GpsWorkLock.heldBy(c,GpsWorkLock.BATCH)||GpsWorkLock.heldBy(c,GpsWorkLock.FLOWER_FARM))return false;
            try{CoreUpdater.requireFiles(c,CoreUpdater.pair(c));}catch(Exception unavailable){return false;}
            try{importExisting(c);}catch(Exception unavailable){return false; /* never import defaults on read failure */}
            boolean complete=true;
            for(String key:new String[]{"kinds","postcard","copy","nectar","reward","nearby"}){
                if(Thread.currentThread().isInterrupted()||WorkStatus.stoppingAll||CoreInstallWizard.preparing(c))return false;
                Object raw=prefs(c).getAll().get(key);if(!(raw instanceof String))continue;
                String value=(String)raw;
                try{
                    validate(key,value);
                    if(key.equals("nearby")||key.equals("reward")||key.equals("nectar"))value=EmulatorDailySupportWindow.choice(c,key,value,true);
                    switch(key){
                        case "kinds": NearbyGateway.setKinds(Integer.parseInt(value));break;
                        case "postcard": FeedingGateway.root(ReturnRewardModuleGateway.postcardCommand(value));break;
                        case "copy": GpsModuleGateway.setAutoCopy(c,value.equals("on"));break;
                        case "nectar": FeedingGateway.root(NectarModuleGateway.controlCommand(value.equals("on")));break;
                        case "reward": FeedingGateway.root(ReturnRewardModuleGateway.modeCommand(value));break;
                        case "nearby": if(!NearbyGateway.reconcile(c,value.equals("on")).isEmpty())complete=false;break;
                    }
                }catch(Exception unavailable){complete=false; /* keep desired choice and retry */}
            }
            return complete;
        }
    }
}

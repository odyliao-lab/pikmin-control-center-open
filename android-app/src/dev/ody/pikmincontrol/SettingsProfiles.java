package dev.ody.pikmincontrol;
import android.content.*;
import java.util.*;

/** Named local profiles and explicit settings-only apply. No service start or HC writes. */
final class SettingsProfiles {
    private static SharedPreferences store(Context c){return c.getSharedPreferences("settings_profiles_v1",0);}
    static List<SettingsProfile> list(Context c){List<SettingsProfile> all=new ArrayList<>();
        for(Object value:store(c).getAll().values()){if(!(value instanceof String))throw new IllegalArgumentException("設定組合庫損壞，保留原檔");all.add(SettingsProfile.decode((String)value));}
        if(all.size()>10)throw new IllegalArgumentException("設定組合庫超出上限");all.sort((a,b)->a.get("name").compareTo(b.get("name")));return all;
    }
    static void save(Context c,SettingsProfile p){
        List<SettingsProfile> all=list(c);String key=SettingsProfile.sha(p.get("name").toLowerCase(Locale.ROOT));
        if(!store(c).contains(key)&&all.size()>=10)throw new IllegalArgumentException("最多10組，請先刪除不用的組合");
        if(!store(c).edit().putString(key,p.encode()).commit())throw new IllegalStateException("保存失敗");
    }
    static void delete(Context c,SettingsProfile p){if(!store(c).edit().remove(SettingsProfile.sha(p.get("name").toLowerCase(Locale.ROOT))).commit())throw new IllegalStateException("刪除失敗");}
    static SettingsProfile capture(Context c,String name){
        Map<String,String> m=new LinkedHashMap<>();m.put("name",name.trim());
        Map<String,?> saved=c.getSharedPreferences("feeding",0).getAll();
        if(new FeedingSavedState(saved.containsKey("config")?String.valueOf(saved.get("config")):"",saved.containsKey("config_backup")?String.valueOf(saved.get("config_backup")):"").damaged)
            throw new IllegalStateException("餵食設定損壞，保留原檔，不匯出預設值");
        FeedingConfig f=FeedingGateway.load(c).copy();f.enabled=false;f.collect=false;m.put("feeding",f.encode());
        m.put("kinds",AutomationPreferences.value(c,"kinds","3"));m.put("postcard",AutomationPreferences.value(c,"postcard","keep"));
        m.put("appearance",ControlSettings.appearance(c));m.put("overlay",ControlSettings.dispatchOverlayEnabled(c)?"1":"0");
        m.put("route",ControlSettings.routeHarvest(c)?"1":"0");m.put("rounds",""+ControlSettings.flowerFarmRounds(c));m.put("dwell",""+ControlSettings.flowerFarmDwell(c));
        m.put("timeout",""+ControlSettings.timeoutMinutes(c));m.put("joystickBoot",ControlSettings.startJoystickOnBoot(c)?"1":"0");
        m.put("gameGuard",ControlSettings.restartGameOnPidLoss(c)?"1":"0");m.put("resource",ResourceGuard.enabled(c)?"1":"0");
        StepSettings s=StepHealthGateway.engine(c).safeSettings();if(s==null)throw new IllegalStateException("步數設定無法讀取，不以預設值匯出");
        m.put("stepTarget",""+s.target);m.put("stepStart",""+s.windowStartMin);m.put("stepEnd",""+s.windowEndMin);
        m.put("stepRate",""+s.ratePerMinute);m.put("stepPreset",s.ratePreset);m.put("stepInterval",""+s.intervalMs);return new SettingsProfile(m);
    }
    static void apply(Context c,SettingsProfile p)throws Exception {
        p.validate();
        synchronized(AutomationPreferences.class){
        synchronized(FeedingGateway.class){
            if(GpsWorkLock.anyHeld(c)||NearbyWorkState.pending(c)||WorkStatus.blocked(c)||WorkStatus.stoppingAll||CoreUpdater.pending(c)||CoreInstallWizard.preparing(c)||SetupDeployment.pending(c)||UpdateInstaller.blocking(c))throw new IllegalStateException("請先完成或停止目前工作");
            FeedingConfig live=FeedingGateway.load(c);StepEngine engine=StepHealthGateway.engine(c);StepView step=engine.snapshot();
            if(live.enabled||live.collect||CollectionMonitorService.desired(c,"reward")||CollectionMonitorService.desired(c,"nectar")||CollectionMonitorService.desired(c,"nearby")
                ||step.desiredRunning||step.pending||!step.writerOwner.isEmpty()||StepView.STORE_ERROR.equals(step.phase))throw new IllegalStateException("請先停止餵食、派遣、領取與步數；組合不會幫你啟動功能");
            // Require actual native feeding switches off, not merely saved intent.
            FeedingGateway.requirePausedForInstall();
            StepView stepSaved=engine.configureStopped(p.steps());
            if(StepView.STORE_ERROR.equals(stepSaved.phase))throw new IllegalStateException("步數設定保存未確認");
            // Multi-store apply is retryable settings only, not an atomic runtime transaction.
            FeedingConfig feed=p.feeding();feed.revision=UUID.randomUUID().toString();String raw=feed.encode();
            if(!c.getSharedPreferences("feeding",0).edit().putString("config",raw).putString("config_backup",raw).remove("synced").commit())throw new IllegalStateException("餵食設定保存失敗");
            if(!c.getSharedPreferences("automation_choices_v1",0).edit().putString("kinds",p.get("kinds")).putString("postcard",p.get("postcard")).commit())throw new IllegalStateException("派遣設定保存失敗");
            SharedPreferences.Editor edit=c.getSharedPreferences("pikmin_control",0).edit();
            edit.putString("appearance",p.get("appearance")).putBoolean("dispatch_overlay_enabled",p.flag("overlay"))
                .putBoolean("route_harvest",p.flag("route")).putInt("flower_farm_rounds",p.integer("rounds",1,2)).putInt("flower_farm_dwell",p.integer("dwell",5,10))
                .putInt("timeout_minutes",p.integer("timeout",1,30)).putBoolean("start_joystick_on_boot",p.flag("joystickBoot")).putBoolean("restart_game_on_pid_loss",p.flag("gameGuard"));
            if(!edit.commit())throw new IllegalStateException("一般設定保存失敗");
            if(!c.getSharedPreferences("resource_guard",0).edit().putBoolean("strict",p.flag("resource")).commit())throw new IllegalStateException("資源保護設定保存失敗");
        }
        }
        // No reconcile/sync/start: the user must explicitly enable gameplay afterward.
    }
    static String differences(SettingsProfile current,SettingsProfile target){
        StringBuilder text=new StringBuilder();
        String[] keys={"kinds","postcard","appearance","overlay","route","rounds","dwell","timeout","joystickBoot","gameGuard","resource","stepTarget","stepStart","stepEnd","stepRate","stepPreset","stepInterval"};
        String[] labels={"派遣類型","明信片","外觀","懸浮視窗","沿路模式","採果輪數","停留秒數","領取逾時","開機Joystick","遊戲守護","資源保護","步數目標","時段開始（分）","時段結束（分）","步數速率","速率選项","步數間隔（ms）"};
        FeedingConfig a=current.feeding(),b=target.feeding();a.revision=b.revision;
        if(!a.encode().equals(b.encode()))text.append("餵食條件：友好度 ").append(a.hearts).append("→").append(b.hearts).append("；保留 ").append(a.reserve).append("→").append(b.reserve).append("；花種 ").append(a.rules.size()).append("→").append(b.rules.size()).append("\n");
        for(int i=0;i<keys.length;i++)if(!current.get(keys[i]).equals(target.get(keys[i])))text.append(labels[i]).append("：").append(current.get(keys[i])).append("→").append(target.get(keys[i])).append('\n');
        return text.length()==0?"條件相同；不包含任何執行開關。":text.toString();
    }
}

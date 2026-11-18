package dev.ody.pikmincontrol;
import android.content.Context;
import java.nio.charset.StandardCharsets;

final class FeedingGateway {
    static final String CONTROL="/data/local/tmp/pikmin-feeding-config.tsv";
    static FeedingConfig load(Context c){
        return saved(c).config;
    }
    private static String text(android.content.SharedPreferences p,String key){
        Object value=p.getAll().get(key);return value==null?"":String.valueOf(value);
    }
    private static FeedingSavedState saved(Context c){
        android.content.SharedPreferences p=c.getSharedPreferences("feeding",0);
        return new FeedingSavedState(text(p,"config"),text(p,"config_backup"));
    }
    static synchronized void save(Context c,FeedingConfig config) throws Exception {
        android.content.SharedPreferences p=c.getSharedPreferences("feeding",0);
        if(saved(c).damaged)throw new IllegalStateException("設定檔無法解析，已保留原檔；請至系統診斷，勿以預設值覆蓋");
        config.validate();config.useGameCapacity();config.revision=java.util.UUID.randomUUID().toString();String raw=config.encode();
        android.content.SharedPreferences.Editor edit=p.edit();
        if(saved(c).capacityMigrated&&!p.contains("config_before_capacity"))edit.putString("config_before_capacity",text(p,saved(c).recovered?"config_backup":"config"));
        if(saved(c).recovered)edit.putString("config_unreadable",text(p,"config"));
        if(!edit.putString("config",raw).putString("config_backup",raw).commit())throw new IllegalStateException("無法保存餵食設定");
        try { sync(c,config); } finally { CollectionMonitorService.sync(c); }
    }
    private static void sync(Context c,FeedingConfig config) throws Exception {
        String raw=config.encode();
        // Encoded bytes only: names never enter a shell command. One atomic file carries every limit.
        String encoded=java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        root("printf '%s' '"+encoded+"' | base64 -d > "+CONTROL+".tmp && chmod 0644 "+CONTROL+".tmp && mv "+CONTROL+".tmp "+CONTROL);
        String read=root("cat "+CONTROL);
        if(!read.trim().equals(raw.trim()))throw new IllegalStateException("餵食設定尚未同步至原生控制檔");
        if(!c.getSharedPreferences("feeding",0).edit().putString("synced",config.revision).remove("sync_error").commit())throw new IllegalStateException("無法保存同步結果");
    }
    static String message(Context c,FeedingSnapshot snapshot){
        FeedingSavedState stored=saved(c);
        if(stored.damaged)return "設定讀取異常 · 原檔已保留，請至系統診斷";
        FeedingConfig f=load(c);
        android.content.SharedPreferences p=c.getSharedPreferences("feeding",0);
        if(stored.recovered)return "已讀取設定備份 · 等待核對同步";
        if(p.contains("config")&&(!f.revision.equals(text(p,"synced"))||!text(p,"sync_error").isEmpty()))return "設定已保存 · 等待同步核心";
        return snapshot.message(f);
    }
    /** Bounded startup repair of the config only. Never clears pending ledgers or replays RPCs. */
    static synchronized boolean reconcile(Context c){
        FeedingSavedState stored=saved(c);
        if(!stored.present)return true;
        if(stored.damaged||Thread.currentThread().isInterrupted())return false;
        try {
            {
                if(CoreInstallWizard.preparing(c)||CoreUpdater.running||CoreUpdater.pending(c)
                    ||SetupDeployment.pending(c)||UpdateInstaller.blocking(c)||WorkStatus.stoppingAll||WorkStatus.blocked(c)
                    ||GpsWorkLock.heldBy(c,GpsWorkLock.BATCH)||GpsWorkLock.heldBy(c,GpsWorkLock.FLOWER_FARM))return false;
                String actual=root("if [ -f "+CONTROL+" ]; then cat "+CONTROL+"; fi");
                if(Thread.currentThread().isInterrupted())return false;
                if(!actual.trim().equals(stored.config.encode().trim())){
                    CoreUpdater.requireFiles(c,CoreUpdater.pair(c));
                    if(WorkStatus.stoppingAll||UpdatePreparation.blocking(c)||CoreInstallWizard.preparing(c)||Thread.currentThread().isInterrupted())return false;
                    sync(c,stored.config);
                }else{
                    root("chmod 0644 "+CONTROL);
                }
                android.content.SharedPreferences p=c.getSharedPreferences("feeding",0);
                android.content.SharedPreferences.Editor edit=p.edit();
                if(stored.capacityMigrated&&!p.contains("config_before_capacity"))edit.putString("config_before_capacity",text(p,stored.recovered?"config_backup":"config"));
                if(stored.recovered)edit.putString("config_unreadable",text(p,"config"));
                if(!edit.putString("config",stored.config.encode()).putString("config_backup",stored.config.encode())
                        .putString("synced",stored.config.revision).remove("sync_error").commit())throw new IllegalStateException("保存核對結果失敗");
            }
            return true;
        }catch(Exception failure){c.getSharedPreferences("feeding",0).edit().putString("sync_error","設定已保存 · 核心同步待重試").apply();return false;}
    }
    static synchronized void stop(Context c) throws Exception {FeedingConfig f=load(c);f.enabled=false;f.collect=false;save(c,f);}
    static synchronized void pauseForInstall(Context c) throws Exception {
        FeedingConfig f=load(c);f.enabled=false;f.collect=false;
        f.revision=java.util.UUID.randomUUID().toString();
        // Temporary installer stop must not replace the user's saved selection and switches.
        sync(c,f);
        c.getSharedPreferences("feeding",0).edit().putString("sync_error","安裝期間暫停，設定保留").commit();
    }
    static void requirePausedForInstall() throws Exception {
        // Saved intent is deliberately kept ON by pauseForInstall. Only a
        // complete, readable native control acknowledgement proves paused.
        FeedingConfig actual=FeedingConfig.parse(root("cat "+CONTROL));
        if(actual.enabled||actual.collect)throw new IllegalStateException("餵食／收花瓣控制尚未暫停，請重試停止確認");
    }
    /** Requests one short-lived, read-only snapshot for the current game PID. */
    static void requestSquadSnapshot() throws Exception {
        // Short-lived read-only permission, bound to the current game process.
        // This never writes or enables the feeding configuration.
        root("p=$("+OwnerGameProcess.command()+"); case \"$p\" in ''|*[!0-9]*) ;; *) printf 'v1\\t%s\\t%s\\n' '"+
                System.currentTimeMillis()+"' \"$p\" > /data/local/tmp/pikmin-feeding-read-request.tsv.tmp && chmod 0644 /data/local/tmp/pikmin-feeding-read-request.tsv.tmp && mv /data/local/tmp/pikmin-feeding-read-request.tsv.tmp /data/local/tmp/pikmin-feeding-read-request.tsv ;; esac");
    }
    static FeedingSnapshot read() throws Exception {
        requestSquadSnapshot();
        String[] p=ProbeSnapshot.sections(root(ProbeSnapshot.command(OwnerGameProcess.command(),
                "cat /data/user/0/com.nianticlabs.pikmin/files/feeding_snapshot.tsv 2>/dev/null || true",
                "cat /data/user/0/com.nianticlabs.pikmin/files/feeding_state.tsv 2>/dev/null || true",OwnerGameProcess.command())),4);
        long now=System.currentTimeMillis();
        FeedingSnapshot snapshot=FeedingSnapshot.parse(p[1],p[0].trim(),p[3].trim(),now);
        snapshot.applyRuntime(p[2],p[0].trim(),p[3].trim(),now);
        return snapshot;
    }
    static String history() throws Exception {return root("tail -n 40 /data/user/0/com.nianticlabs.pikmin/files/feeding_history.tsv 2>/dev/null || true");}
    static String squadDiagnostics(Context c) throws Exception {
        String[] p=ProbeSnapshot.sections(root(ProbeSnapshot.command(OwnerGameProcess.command(),
                "cat /data/user/0/com.nianticlabs.pikmin/files/feeding_squad_snapshot.tsv 2>/dev/null || true",
                OwnerGameProcess.command())),3);
        return FeedingSquadDiagnostics.parse(p[1],p[0].trim(),p[2].trim(),System.currentTimeMillis(),load(c));
    }
    static String root(String cmd) throws Exception {BoundedCommand.Result r=BoundedCommand.root(cmd,4,262144);if(!r.ok||r.truncated)throw new IllegalStateException("餵食資料讀取／同步失敗");return r.text;}
}

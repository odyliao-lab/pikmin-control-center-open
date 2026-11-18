package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Persist before the first control write; never infer nearby=on from a farm's armed mode. */
final class NearbyWorkState {
    interface Backend {
        String owner(); boolean armed(); String readMode() throws Exception; String readKinds() throws Exception;
        void save(String owner, boolean armed) throws Exception; void clear() throws Exception;
        boolean stopping(); boolean held(String owner); void write(boolean armed) throws Exception;
    }
    interface DeferredChoice {void save(boolean original)throws Exception;}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("nearby_work_restore",0); }
    private static Backend backend(Context c) {
        return new Backend() {
            public String owner(){return prefs(c).getString("owner", "");}
            public boolean armed(){return prefs(c).getBoolean("armed",false);}
            public String readMode() throws Exception {
                String actual=GpsModuleGateway.farmRead("if test -f /data/local/tmp/pikmin-dispatch-mode.txt; then cat /data/local/tmp/pikmin-dispatch-mode.txt; else printf off; fi").trim();
                // Waiting for a game/core may leave actual=off although the user chose ON.
                // Preserve that intent across temporary work; never disguise an active batch mode.
                return NearbyPreference.captureMode(actual,AutomationPreferences.value(c,"nearby",""));
            }
            public String readKinds() throws Exception {return GpsModuleGateway.farmRead("if test -f /data/local/tmp/pikmin-dispatch-kinds.txt; then cat /data/local/tmp/pikmin-dispatch-kinds.txt; else printf all; fi").trim();}
            public void save(String owner,boolean armed){if(!prefs(c).edit().putString("owner",owner).putBoolean("armed",armed).commit())throw new IllegalStateException("無法保存附近派遣原始狀態");}
            public void clear(){discard(c);}
            public boolean stopping(){return WorkStatus.stoppingAll;}
            public boolean held(String owner){return GpsWorkLock.heldBy(c,owner);}
            public void write(boolean armed) throws Exception {
                String mode=armed?"armed":"off";
                GpsModuleGateway.farmRead("printf '"+mode+"\\n' > /data/local/tmp/pikmin-dispatch-mode.txt && chmod 0644 /data/local/tmp/pikmin-dispatch-mode.txt && test \"$(cat /data/local/tmp/pikmin-dispatch-mode.txt)\" = "+mode);
            }
        };
    }
    static synchronized boolean pending(Context c) { return prefs(c).contains("owner"); }
    static synchronized boolean ownedBy(Context c,String owner) { return owner.equals(prefs(c).getString("owner","")); }
    static synchronized boolean originallyArmed(Context c) { return prefs(c).getBoolean("armed",false); }
    static synchronized void capture(Context c,String owner) throws Exception { capture(backend(c),owner); }
    static synchronized void capture(Context c,String owner,boolean recovery) throws Exception { capture(backend(c),owner,recovery); }
    static void capture(Backend p,String owner) throws Exception {
        capture(p,owner,false);
    }
    static void capture(Backend p,String owner,boolean recovery) throws Exception {
        if(!p.owner().isEmpty()) {
            if(!owner.equals(p.owner())) throw new IllegalStateException("另一工作仍有待恢復的附近派遣狀態，請先處理原工作");
            return;
        }
        String mode=p.readMode();
        if(!"off".equals(mode)&&!"armed".equals(mode))throw new IllegalStateException("派遣控制不是閒置狀態，請先安全停止");
        String kinds=p.readKinds();
        // An older APK left no original-state record. Explicit farm recovery may
        // clear its residual farm mask, but must not infer nearby=on from it.
        if(recovery && "farm".equals(kinds)) { p.save(owner,false); return; }
        if(!"all".equals(kinds))throw new IllegalStateException("花田控制尚未解除，請先安全停止");
        p.save(owner,"armed".equals(mode));
    }
    static synchronized void restore(Context c,String owner) throws Exception { restore(backend(c),owner); }
    static synchronized void restoreDeferred(Context c,String owner)throws Exception {
        restoreDeferred(backend(c),owner,original -> AutomationPreferences.chooseNearby(c,
                "on".equals(AutomationPreferences.value(c,"nearby",original?"on":"off"))));
    }
    static void restoreDeferred(Backend p,String owner,DeferredChoice choice)throws Exception {
        if(!owner.equals(p.owner()))return;
        if(p.stopping()){p.clear();return;}
        if(!p.held(owner))throw new IllegalStateException("GPS工作權已變更，保留恢復紀錄");
        p.write(false);
        if(!p.stopping())choice.save(p.armed());
        p.clear(); // Native ON is left to the paired-core/capability guarded monitor.
    }
    static void restore(Backend p,String owner) throws Exception {
        if(!owner.equals(p.owner()))return;
        if(p.stopping()) { p.clear(); return; }
        if(!p.held(owner))throw new IllegalStateException("GPS工作權已變更，保留附近派遣恢復紀錄");
        // Called while still holding ownership, after stop readback, before releasing it.
        boolean armed=p.armed();
        try {
        p.write(armed);
        if(p.stopping()) p.write(false);
        p.clear();
        } catch(Exception error) {
            try { p.write(false); } catch(Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }
    static synchronized void discard(Context c) {
        if(!prefs(c).edit().clear().commit())throw new IllegalStateException("無法取消附近派遣恢復");
    }
}

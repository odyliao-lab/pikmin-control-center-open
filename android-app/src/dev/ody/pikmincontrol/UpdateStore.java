package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;

final class UpdateStore {
    static SharedPreferences prefs(Context c){return c.getApplicationContext().getSharedPreferences("pikmin_updates",0);}
    static boolean automatic(Context c){return prefs(c).getBoolean("automatic",true);}
    static void automatic(Context c,boolean value){prefs(c).edit().putBoolean("automatic",value).apply();}
    static UpdateInfo latest(Context c){try{String pinned=PhoneUpdateFlow.manifest(c);
        return new UpdateInfo(pinned.isEmpty()?prefs(c).getString("manifest",""):pinned);}catch(Exception ignored){return null;}}
    static long installed(Context c){try{return c.getPackageManager().getPackageInfo(c.getPackageName(),0).getLongVersionCode();}catch(Exception e){throw new IllegalStateException(e);}}
}

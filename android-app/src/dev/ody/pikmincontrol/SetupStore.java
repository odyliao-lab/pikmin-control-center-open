package dev.ody.pikmincontrol;

import android.content.*;

/** Separate durable wizard state; never imports game/session/pending data. */
final class SetupStore {
    static SharedPreferences prefs(Context c){return c.getSharedPreferences("pikmin_setup",Context.MODE_PRIVATE);}
    static int step(Context c){return Math.max(0,Math.min(5,prefs(c).getInt("step",0)));}
    static void step(Context c,int step){save(prefs(c).edit().putInt("step",Math.max(0,Math.min(5,step))));}
    static void save(SharedPreferences.Editor e){if(!e.commit())throw new IllegalStateException("無法保存設定進度");}
    static boolean gps(Context c){return prefs(c).getBoolean("gps",true);}
    static boolean copy(Context c){return prefs(c).getBoolean("copy",false);}
    static void choose(Context c,boolean gps,boolean copy){save(prefs(c).edit().putBoolean("gps",gps).putBoolean("copy",copy));}
    static String result(Context c){return prefs(c).getString("result","尚未安裝；可稍後繼續");}
    static void result(Context c,String s){save(prefs(c).edit().putString("result",s));}
    static boolean offer(Context c){return !prefs(c).getBoolean("dismissed",false)&&!prefs(c).getBoolean("seen",false)
            &&c.getSharedPreferences("pikmin_control",Context.MODE_PRIVATE).getAll().isEmpty();}
}

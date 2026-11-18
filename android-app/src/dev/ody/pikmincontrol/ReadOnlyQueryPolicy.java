package dev.ody.pikmincontrol;

import android.content.Context;

/** Reversible APK-only optimization. Safety gateways never use the display coalescer. */
final class ReadOnlyQueryPolicy {
    static final DisplayReadCoalescer DISPLAY = new DisplayReadCoalescer();
    static boolean enabled(Context c) { return c.getSharedPreferences("readonly-query",0).getBoolean("enabled",true); }
    static void load(Context c) { DISPLAY.enabled(enabled(c)); }
    static void choose(Context c, boolean value) {
        if(!c.getSharedPreferences("readonly-query",0).edit().putBoolean("enabled",value).commit())
            throw new IllegalStateException("無法保存唯讀優化設定");
        DISPLAY.enabled(value);
    }
}

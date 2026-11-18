package android.content;

import java.util.HashMap;
import java.util.Map;

/** HOST fixture only. Never included in product or normal unit-test compilation. */
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final Map<String, SharedPreferences> stores = new HashMap<>();
    public Context getApplicationContext() { return this; }
    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        SharedPreferences value = stores.get(name);
        if (value == null) { value = new SharedPreferences(); stores.put(name, value); }
        return value;
    }
}

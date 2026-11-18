package android.content;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** An atomic, in-memory SharedPreferences fixture; no disk/Android timing claim. */
public class SharedPreferences {
    private final Map<String, Object> values = new HashMap<>();
    private int allReads, scalarReads;
    public synchronized Map<String, ?> getAll() { allReads++; return new HashMap<>(values); }
    public synchronized String getString(String key, String fallback) {
        scalarReads++; return values.containsKey(key) ? (String) values.get(key) : fallback;
    }
    public synchronized long getLong(String key, long fallback) {
        scalarReads++; return values.containsKey(key) ? (Long) values.get(key) : fallback;
    }
    public synchronized void resetReads() { allReads = 0; scalarReads = 0; }
    public synchronized int allReads() { return allReads; }
    public synchronized int scalarReads() { return scalarReads; }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        public Editor putString(String key, String value) {
            pending.put(key, value); removed.remove(key); return this;
        }
        public Editor putLong(String key, long value) {
            pending.put(key, value); removed.remove(key); return this;
        }
        public Editor remove(String key) { pending.remove(key); removed.add(key); return this; }
        public boolean commit() {
            synchronized (SharedPreferences.this) {
                for (String key : removed) values.remove(key);
                values.putAll(pending);
            }
            return true;
        }
        public void apply() { commit(); }
    }
}

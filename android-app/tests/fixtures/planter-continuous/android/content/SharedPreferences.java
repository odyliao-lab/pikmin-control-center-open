package android.content;
import java.util.HashMap;
import java.util.Map;
/** Atomic in-memory preferences double, used only in the isolated child JVM. */
public final class SharedPreferences {
    private final Map<String,Object> data=new HashMap<>();
    public synchronized String getString(String key,String fallback){return data.containsKey(key)?(String)data.get(key):fallback;}
    public synchronized int getInt(String key,int fallback){return data.containsKey(key)?(Integer)data.get(key):fallback;}
    public synchronized boolean contains(String key){return data.containsKey(key);}
    public Editor edit(){return new Editor();}
    public final class Editor {
        private final Map<String,Object> values=new HashMap<>();
        public Editor putString(String key,String value){values.put(key,value);return this;}
        public Editor putInt(String key,int value){values.put(key,value);return this;}
        public boolean commit(){synchronized(SharedPreferences.this){data.putAll(values);}return true;}
        public void apply(){commit();}
    }
}

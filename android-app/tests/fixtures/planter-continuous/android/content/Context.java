package android.content;
import java.util.HashMap;
import java.util.Map;
/** In-memory HOST fixture; no Android or disk behavior claim. */
public final class Context {
    private final Map<String,SharedPreferences> stores=new HashMap<>();
    public Context getApplicationContext(){return this;}
    public synchronized SharedPreferences getSharedPreferences(String name,int mode){
        SharedPreferences result=stores.get(name);
        if(result==null){result=new SharedPreferences();stores.put(name,result);}return result;
    }
}

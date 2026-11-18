package dev.ody.pikmincontrol;
import java.util.LinkedHashMap;
import java.util.Map;

/** Transport-neutral helpers. API33 must not load framework34 to use paging. */
final class StepPaging {
    static long remainingMs(long deadlineNs) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNs-System.nanoTime()); }
    static boolean stalledPage(int page,long previous,long token){return page>0 && token==previous;}
    // Successful AndroidX responses use either null or an empty string for the last page.
    static String sdkNextToken(String raw){return raw==null || raw.isEmpty()?null:raw;}
    static final class Tokens {
        private final Map<Long,String> tokens=new LinkedHashMap<Long,String>();
        private long next=1;
        synchronized String resolve(long token){return tokens.get(token);}
        synchronized long remember(String raw){
            if(raw==null) return -1;
            if(raw.isEmpty() || raw.length()>8192) throw new IllegalArgumentException("invalid-page-token");
            for(Map.Entry<Long,String> e:tokens.entrySet()) if(raw.equals(e.getValue())) return e.getKey();
            if(tokens.size()>=64) tokens.remove(tokens.keySet().iterator().next());
            long id=next++; tokens.put(id,raw); return id;
        }
    }
}

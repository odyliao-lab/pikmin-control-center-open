package dev.ody.pikmincontrol;
import java.util.*;
import java.text.SimpleDateFormat;

/** Confirmed observations, not authoritative totals or account attribution. */
final class DailyResults {
    static final String[] SOURCES={"dispatch","rewards","feeding","planter"};
    static final String[] CATEGORIES={"fruit","seed","gift","rewards","feed","collect","plant","pluck"};
    final long start,end;
    final String day,zone;
    final Map<String,Set<String>> evidence=new LinkedHashMap<>();
    final Set<String> observed=new HashSet<>();
    boolean incomplete;
    DailyResults(long now,TimeZone timeZone){
        Calendar c=Calendar.getInstance(timeZone);c.setTimeInMillis(now);c.set(Calendar.HOUR_OF_DAY,0);c.set(Calendar.MINUTE,0);c.set(Calendar.SECOND,0);c.set(Calendar.MILLISECOND,0);
        start=c.getTimeInMillis();c.add(Calendar.DAY_OF_MONTH,1);end=c.getTimeInMillis();
        SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);f.setTimeZone(timeZone);day=f.format(new Date(now));zone=timeZone.getID();
        for(String category:CATEGORIES)evidence.put(category,new HashSet<>());
    }
    void add(String category,String key){Set<String> set=evidence.get(category);if(set.size()>=20000){incomplete=true;return;}set.add(digest(key));}
    static String digest(String key){try{byte[] raw=java.security.MessageDigest.getInstance("SHA-256").digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:raw)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    void ingest(String source,String text,long now,boolean full){
        if(!Arrays.asList(SOURCES).contains(source))throw new IllegalArgumentException("source");
        if(text==null||text.trim().equals("MISSING")||text.trim().equals("ERROR")){incomplete=true;return;}
        observed.add(source);if(!full)incomplete=true;
        String[] rows=text.replace("\r","").split("\n");if(rows.length>=10001)incomplete=true;
        for(String row:rows){if(row.isEmpty())continue;String[] f=row.split("\t",-1);
            try {long at=Long.parseLong(f[0]);if(at<=0||at>now)throw new IllegalArgumentException();if(at<start||at>=end)continue;
                if(source.equals("dispatch")){
                    if(f.length!=6)throw new IllegalArgumentException();
                    if("inventory-confirmed".equals(f[1])){
                        if(!Arrays.asList("fruit","seed","gift").contains(f[2])||f[3].isEmpty())throw new IllegalArgumentException();
                        add(f[2],source+":"+f[3]);
                    }
                }else if(source.equals("rewards")){
                    if(f.length<9)throw new IllegalArgumentException();
                    if((f[1].equals("batch-confirmed")||f[1].equals("one-confirmed"))&&Long.parseLong(f[4])>0&&f[5].equals("0")){
                        if(f[2].isEmpty())throw new IllegalArgumentException();add("rewards",source+":"+f[2]);
                    }
                }else if(source.equals("feeding")){
                    if(f.length!=3)throw new IllegalArgumentException();
                    if(f[1].equals("feed-confirmed"))add("feed",source+":"+row);
                    else if(f[1].equals("collect-confirmed"))add("collect",source+":"+row);
                }else{
                    if(f.length!=5)throw new IllegalArgumentException();
                    if(f[1].equals("plant-confirmed")||f[1].equals("pluck-confirmed")){
                        if(f[2].isEmpty())throw new IllegalArgumentException();add(f[1].equals("plant-confirmed")?"plant":"pluck",source+":"+f[2]);
                    }
                }
            }catch(Exception malformed){incomplete=true;}
        }
    }
    String count(String category,String source,String unit){return observed.contains(source)||!evidence.get(category).isEmpty()?evidence.get(category).size()+unit:"未取得";}
    String formatted(){return day+" · "+zone+"\n本機已觀察（跨帳號合計，非遊戲全日總量）"
        +"\n已確認出發：果 "+count("fruit","dispatch","筆")+"／苗 "+count("seed","dispatch","筆")+"／禮物 "+count("gift","dispatch","筆")
        +"\n領取確認紀錄："+count("rewards","rewards","批")
        +"\n餵食完成："+count("feed","feeding","批")+" · 收瓣完成："+count("collect","feeding","批")
        +"\n補種："+count("plant","planter","盆")+" · 拔苗："+count("pluck","planter","盆")
        +"\n精華消耗／花瓣顆數：現有紀錄無法可靠歸屬，未推算。"
        +"\n"+(incomplete?"⚠ 部分來源缺失、截斷或格式異常；僅列已取得證據。":"原生日誌有24小時／筆數限制；未持續觀察的操作可能缺漏。")
        +"\n僅按「更新今日成果」讀取；不增加背景輪詢或遊戲操作。";}
    Properties encode(){Properties p=new Properties();p.setProperty("schema","1");p.setProperty("day",day);p.setProperty("zone",zone);p.setProperty("incomplete",""+incomplete);
        for(String s:SOURCES)p.setProperty("seen."+s,""+observed.contains(s));
        for(String c:CATEGORIES){List<String> ids=new ArrayList<>(evidence.get(c));Collections.sort(ids);p.setProperty("ids."+c,String.join(",",ids));}return p;}
    void restore(Properties p){
        if(p.size()!=4+SOURCES.length+CATEGORIES.length||!"1".equals(p.getProperty("schema"))||!day.equals(p.getProperty("day"))||!zone.equals(p.getProperty("zone")))throw new IllegalArgumentException("daily snapshot");
        if(!Arrays.asList("true","false").contains(p.getProperty("incomplete")))throw new IllegalArgumentException("daily flag");
        Map<String,Set<String>> parsed=new HashMap<>();Set<String> seen=new HashSet<>();
        for(String s:SOURCES){String flag=p.getProperty("seen."+s);if(!Arrays.asList("true","false").contains(flag))throw new IllegalArgumentException("daily source");if(flag.equals("true"))seen.add(s);}
        for(String c:CATEGORIES){String raw=p.getProperty("ids."+c);if(raw==null)throw new IllegalArgumentException("daily count");Set<String> set=new HashSet<>();
            if(!raw.isEmpty())for(String id:raw.split(",",-1))if(!id.matches("[0-9a-f]{64}")||!set.add(id)||set.size()>20000)throw new IllegalArgumentException("daily evidence");parsed.put(c,set);}
        for(String c:CATEGORIES)evidence.get(c).addAll(parsed.get(c));observed.addAll(seen);incomplete|=Boolean.parseBoolean(p.getProperty("incomplete"));
    }
}

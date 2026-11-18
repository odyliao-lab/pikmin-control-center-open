package dev.ody.pikmincontrol;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Strict portable choices. Runtime, credentials, coordinates and run intent are forbidden. */
final class SettingsProfile {
    static final int MAX_BYTES=100000;
    static final String[] KEYS={"name","feeding","kinds","postcard","appearance","overlay","route","rounds","dwell","timeout","joystickBoot","gameGuard","resource","stepTarget","stepStart","stepEnd","stepRate","stepPreset","stepInterval"};
    final Map<String,String> values;
    SettingsProfile(Map<String,String> input) {
        if(input.size()!=KEYS.length||!input.keySet().equals(new HashSet<>(Arrays.asList(KEYS))))throw new IllegalArgumentException("設定組合欄位不完整或含非設定資料");
        values=Collections.unmodifiableMap(new LinkedHashMap<>(input));validate();
    }
    String get(String key){return values.get(key);}
    int integer(String key,int min,int max){return FeedingConfig.number(get(key),min,max);}
    boolean flag(String key){if(!get(key).matches("[01]"))throw new IllegalArgumentException("開關格式不符");return "1".equals(get(key));}
    FeedingConfig feeding(){FeedingConfig f=FeedingConfig.parse(get("feeding"));if(f.enabled||f.collect)throw new IllegalArgumentException("組合不可自動啟動餵食或收瓣");return f;}
    StepSettings steps(){return StepSettings.parse(integer("stepTarget",1,1000000),integer("stepStart",0,1439),integer("stepEnd",0,1439),
        integer("stepRate",1,100000),get("stepPreset"),integer("stepInterval",5000,180000));}
    void validate(){
        String name=get("name");if(name.isEmpty()||name.length()>40||!name.equals(name.trim())||name.matches("(?s).*[\\p{Cntrl}].*"))throw new IllegalArgumentException("組合名稱須為1～40字");
        feeding();AutomationPreferences.validate("kinds",get("kinds"));AutomationPreferences.validate("postcard",get("postcard"));
        if(!Arrays.asList("system","light","dark").contains(get("appearance")))throw new IllegalArgumentException("外觀格式不符");
        for(String key:new String[]{"overlay","route","joystickBoot","gameGuard","resource"})flag(key);
        integer("rounds",1,2);if(integer("dwell",5,10)!=5&&integer("dwell",5,10)!=10)throw new IllegalArgumentException("停留時間不符");
        integer("timeout",1,30);
        if(!Arrays.asList("slow","normal","fast","run","custom").contains(get("stepPreset")))throw new IllegalArgumentException("步數速率不符");
        StepSettings s=steps();if(s.ratePerMinute!=integer("stepRate",1,100000))throw new IllegalArgumentException("步數速率與選項不一致");
    }
    String encode(){
        StringBuilder text=new StringBuilder("PCCPROFILE/1\n");
        for(String key:KEYS)text.append(key).append('=').append(Base64.getEncoder().encodeToString(get(key).getBytes(StandardCharsets.UTF_8))).append('\n');
        String body=text.toString();text.append("sha256=").append(sha(body)).append('\n');
        if(text.length()>MAX_BYTES)throw new IllegalArgumentException("設定組合過大");return text.toString();
    }
    static SettingsProfile decode(String text){
        if(text==null||text.length()>MAX_BYTES||!text.endsWith("\n"))throw new IllegalArgumentException("組合空白、過大或截斷");
        String[] lines=text.split("\n",-1);if(lines.length!=KEYS.length+3||!lines[0].equals("PCCPROFILE/1"))throw new IllegalArgumentException("組合版本或完整性不符");
        Map<String,String> map=new LinkedHashMap<>();
        for(int i=0;i<KEYS.length;i++){String[] f=lines[i+1].split("=",2);if(f.length!=2||!f[0].equals(KEYS[i]))throw new IllegalArgumentException("組合含重複或非允許欄位");
            byte[] raw=Base64.getDecoder().decode(f[1]);if(!Base64.getEncoder().encodeToString(raw).equals(f[1]))throw new IllegalArgumentException("組合編碼不符");
            map.put(f[0],new String(raw,StandardCharsets.UTF_8));}
        SettingsProfile profile=new SettingsProfile(map);if(!profile.encode().equals(text))throw new IllegalArgumentException("組合校驗不符");return profile;
    }
    static String sha(String body){try {byte[] digest=MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}

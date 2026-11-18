package dev.ody.pikmincontrol;

import java.util.*;

/** Read-only evidence. Installed files do not prove runtime capability or gameplay success. */
final class SetupSnapshot {
    final Map<String,String> fields=new HashMap<>();
    final boolean root,magisk,compatible,runtime,nativePending,copyPending;
    final int sdk; final long available,observedAt;
    SetupSnapshot(String raw,int sdk,boolean arm64,long available,long now){
        this.sdk=sdk;this.available=available;this.observedAt=now;
        for(String line:raw.split("\n")){int i=line.indexOf('=');if(i>0)fields.put(line.substring(0,i),line.substring(i+1).trim());}
        root="yes".equals(get("probe_complete"))&&"0".equals(get("root_uid"));
        magisk=root&&number("magisk")>0;
        compatible=magisk&&sdk==35&&EmulatorRuntime.allowed()&&"157.0".equals(get("game"))&&number("il2cpp_size")==257043464L;
        nativePending="yes".equals(get("native_pending"));copyPending="yes".equals(get("copy_pending"));
        long at=number("guard_at");
        runtime=compatible&&!nativePending&&!"yes".equals(get("native_disabled"))
                &&"compatible".equals(get("runtime"))&&"v2".equals(get("guard_schema"))
                &&"300000".equals(get("guard_limit"))&&number("pid")>0&&get("pid").equals(get("guard_pid"))
                &&at>0&&now>=at&&now-at<=10000&&(!fields.containsKey("pair_files")||pairedRuntime(now));
    }
    String get(String key){String s=fields.get(key);return s==null||s.isEmpty()?"未知":s;}
    long number(String key){try{return Long.parseLong(get(key));}catch(Exception e){return -1;}}
    boolean currentRuntime(long now){return runtime&&now>=observedAt&&now-observedAt<=10000&&now-number("guard_at")<=10000;}
    boolean pairedFiles(){return root&&"yes".equals(get("pair_files"))&&get("native").equals(get("pair_version"))
            &&number("native_code")>0&&number("native_code")==number("pair_code")&&!nativePending&&!"yes".equals(get("native_disabled"));}
    boolean unified(){return "yes".equals(get("pair_unified"));}
    boolean pairedRuntime(long now){long at=number("pair_at");return pairedFiles()&&number("pid")>0&&get("pid").equals(get("pair_pid"))&&at>0&&now>=at&&now-at<=10000;}
    boolean coreInstalled(){if(fields.containsKey("pair_files"))return pairedFiles();return root&&(("1.5.30".equals(get("native"))&&number("native_code")==98)||("1.5.29".equals(get("native"))&&number("native_code")==97)||("1.5.28".equals(get("native"))&&number("native_code")==96)||("1.5.27".equals(get("native"))&&number("native_code")==95)
            ||("1.4.24".equals(get("native"))&&number("native_code")==58)
            ||("1.4.25".equals(get("native"))&&number("native_code")==59)
            ||("1.4.26".equals(get("native"))&&number("native_code")==60))&&!"yes".equals(get("native_disabled"))&&!nativePending;}
    boolean copyInstalled(){if(unified())return pairedFiles()&&!copyPending;return root&&"v152.0-r13".equals(get("copy"))&&!"yes".equals(get("copy_disabled"))&&!copyPending;}
    boolean components(boolean gps,boolean copy){return compatible&&coreInstalled()&&(!gps||JoystickAdapter.supported(get("joystick")))&&(!copy||copyInstalled());}
    String summary(){return "Android API "+sdk+" · "+(root?"Root可用":"Root未授權／讀取失敗")
            +"\nMagisk："+get("magisk")+"\nPikmin："+get("game")
            +"\n核心模組："+get("native")+(nativePending?" · 待重開":"")
            +"\nGPS Copy："+(unified()?(pairedFiles()?"整合核心內建":"整合核心待安裝／核對"):get("copy"))+(copyPending?" · 待重開":"")
            +"\nJoyStick："+get("joystick")+"\n原生連線："+(runtime?"同程序回報已確認":"待開啟遊戲確認（含Zygisk）")
            +"\n可用空間："+(available/1048576)+" MB"
            +(compatible?"":"\nMuMu實驗版僅支援x64／Android15／libnb／Pikmin157；不支援一般手機。");}
}

package dev.ody.pikmincontrol;

import java.util.HashMap;
import java.util.Map;

/** Presentation only. Never grants dispatch authority or changes modules/settings. */
final class EnvironmentReport {
    final String summary,details;final boolean warning;
    EnvironmentReport(String raw,String app,int sdk,boolean complete,long now){
        Map<String,String> fields=new HashMap<>();
        for(String line:raw.split("\n")){int p=line.indexOf('=');if(p>0){String value=line.substring(p+1).trim();if(value.matches("[A-Za-z0-9_. -]{0,80}"))fields.put(line.substring(0,p),value);}}
        boolean root=complete&&"0".equals(fields.get("root_uid"));
        String nativeVersion=value(fields,"native"),game=value(fields,"game"),joystick=value(fields,"joystick");
        boolean module=root&&!"yes".equals(fields.get("native_disabled"))&&!"未知".equals(nativeVersion);
        boolean paired=fields.containsKey("pair_files"),unified="yes".equals(fields.get("pair_unified"));
        if(paired)module=module&&"yes".equals(fields.get("pair_files"))&&nativeVersion.equals(fields.get("pair_version"))&&value(fields,"native_code").equals(value(fields,"pair_code"));
        boolean pairRuntime=!paired;
        try{long at=Long.parseLong(value(fields,"pair_at"));pairRuntime=at>0&&now>=at&&now-at<=10000&&value(fields,"pid").matches("[1-9][0-9]*")&&value(fields,"pid").equals(value(fields,"pair_pid"));}catch(Exception ignored){}
        boolean runtime=module&&"compatible".equals(fields.get("runtime"))&&pairRuntime;
        boolean fresh=false;try{long at=Long.parseLong(value(fields,"guard_at"));fresh=at>0&&now>=at&&now-at<=10000;}catch(Exception ignored){}
        boolean timeOnly=runtime&&"v2".equals(fields.get("guard_schema"))&&"300000".equals(fields.get("guard_limit"))
                && value(fields,"pid").matches("[1-9][0-9]*")&&value(fields,"pid").equals(value(fields,"guard_pid"));
        boolean gps=JoystickAdapter.supported(joystick);
        boolean permission="644".equals(fields.get("dispatch_perm"));
        warning=!root||!module||!runtime||!"157.0".equals(game)||!gps||!permission;
        summary="Root："+(root?"可用":"未授權或讀取失敗")+"\n遊戲："+game+" · native："+nativeVersion
                +"\n5分鐘派遣："+(!module?"模組未就緒":!timeOnly?"需1.4.23並開啟遊戲":fresh?"不限距離能力已載入":"能力已載入 · 等待新回報")
                +"\nJoyStick："+joystick+(gps?"":" · 未驗證版本")
                +"\n控制檔："+(permission?"0644":"需檢查權限");
        details="Control Center "+app+" / Android API "+sdk+"\n"+summary
                +"\n原生相容回報："+value(fields,"runtime")+"\nGPS Copy："+(unified?(module?(runtime?"整合核心 · 同程序已載入":"整合核心 · 待開啟遊戲核對"):"整合核心待安裝／核對"):value(fields,"copy"))+"（僅擷取座標，非飛行引擎）"
                +"\n本頁只讀取；不安裝、啟停或修復任何模組。定位／隊員仍須由各功能即時重驗。"
                +(sdk>=35?"\nAndroid 15以上仍需個別驗證背景服務及安裝確認。":"");
    }
    private static String value(Map<String,String> m,String key){String v=m.get(key);return v==null||v.isEmpty()?"未知":v;}
}

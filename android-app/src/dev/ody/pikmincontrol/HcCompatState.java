package dev.ody.pikmincontrol;

/** Compatibility/read-only observation, never a successful step-write receipt. */
final class HcCompatState {
    static boolean loaderMatches(String raw,String before,String after,String version,int code,long now){
        try {String[] f=raw.trim().split("\t",-1);long at=Long.parseLong(f[1]);
            return f.length==6&&"v1".equals(f[0])&&before.matches("[1-9][0-9]{0,6}")&&before.equals(after)&&before.equals(f[2])
                    &&version.equals(f[3])&&Integer.toString(code).equals(f[4])&&("0".equals(f[5])||"1".equals(f[5]))&&at>0&&now>=at&&now-at<=5000;
        }catch(RuntimeException invalid){return false;}
    }
    final boolean desired,fresh;
    final String message;
    HcCompatState(int sdk,String control,String raw,String before,String after,long now) {
        if(!HcCompatPolicy.allowedSdk(sdk)) {desired=false;fresh=false;message="Android 14 以上不需要 HC 相容處理";return;}
        if(!"on".equals(control)&&!"off".equals(control))throw new IllegalStateException("HC 相容設定讀取未完成");
        desired="on".equals(control);boolean valid=false;String phase="waiting",provider="unknown",fg="unknown",bg="unknown",query="none";
        try {
            String[] f=raw.trim().split("\t",-1);long at=Long.parseLong(f[1]);
            valid=f.length==12&&"v1".equals(f[0])&&before.matches("[1-9][0-9]{0,6}")&&before.equals(after)&&before.equals(f[2])
                    &&Integer.parseInt(f[3])==sdk&&at>0&&now>=at&&now-at<=5000&&("on".equals(f[5])||"off".equals(f[5]));
            if(valid){phase=f[4];provider=f[6];fg=f[8];bg=f[9];query=f[10];long q=Long.parseLong(f[11]);if(q<0||q>now)valid=false;}
        }catch(RuntimeException bad){valid=false;}
        fresh=valid;
        if(!desired){message=!valid?"已關閉，等待核心回報":"已關閉，保留遊戲原判定";return;}
        if(!valid){message="已開啟，等待目前遊戲程序的核心回報";return;}
        if("legacy-present".equals(phase)){message="偵測到獨立 HC 套件；移除後重開遊戲，避免重複套用";return;}
        if("identity-mismatch".equals(phase)){message="目前遊戲套件尚未核對，未套用 HC 相容處理";return;}
        if(!"hook-ready".equals(phase)){message="HC 相容尚未就緒（"+phase+"）";return;}
        message="相容處理已載入 · 請在遊戲選用 Health Connect\n最近遊戲回報 · HC 服務："+label(provider)+" · 前景："+label(fg)+" · 背景："+label(bg)
                +"\n遊戲讀取："+("existing-nonzero".equals(query)?"已讀到既有紀錄":"zero".equals(query)?"已讀取，該次為零":"other-source".equals(query)?"使用其他來源":"等待確認")+"；不代表新增步數採計";
    }
    private static String label(String v){return "available".equals(v)?"可用":"granted".equals(v)?"已授權":"denied".equals(v)?"未授權":"unavailable".equals(v)?"不可用":"待確認";}
}

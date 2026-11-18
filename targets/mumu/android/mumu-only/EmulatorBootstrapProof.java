package dev.ody.pikmincontrol;

import java.io.IOException;

/** Pure protocol validation shared by UI and host fixtures, no Android or root dependencies. */
final class EmulatorBootstrapProof {
    static long waitingToken(String report,String pid,long now)throws IOException {
        if(pid==null||!pid.matches("[1-9][0-9]*"))throw new IOException("遊戲程序不唯一／未啟動");
        String[] f=report.trim().split("\t",-1);
        if(f.length!=10||!f[0].equals("v2")||!f[2].equals(pid)||!f[3].equals("entry")||!f[4].equals("manual")
            ||!f[5].equals("created")||!f[6].equals("framework")||!f[7].equals("waiting")||!f[8].equals("0"))
            throw new IOException("不是本輪待確認回報（已載入可直接檢查）");
        try {
            if(!f[1].matches("[1-9][0-9]*")||!f[9].matches("[1-9][0-9]*"))throw new NumberFormatException();
            long at=Long.parseLong(f[1]),token=Long.parseLong(f[9]);
            if(at>now||now-at>12000||token>at||now<token||now-token>180000)throw new IOException("等待回報已過期，請重啟遊戲");
            return token;
        }catch(NumberFormatException bad){throw new IOException("等待回報格式錯誤",bad);}
    }
}

package dev.ody.pikmincontrol;

import android.content.*;
import android.os.*;
import java.io.IOException;
import java.util.concurrent.*;

/** Real App receiver + Root actor test. STATUS only, no GPS movement or game
 * RPC. A broadcast is observation, never authenticated ownership/drain proof. */
final class EmulatorGpsDiagnostics {
    private static String read(String command,int limit)throws Exception {
        BoundedCommand.Result r=BoundedCommand.root(command,3,limit);
        if(r==null||!r.ok||r.timedOut||r.truncated||r.exitCode==null||r.exitCode!=0)
            throw new IOException("GPS診斷讀取未確認");
        return r.text.trim();
    }
    static String check(Context input)throws Exception {
        EmulatorRuntime.require();
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Worker only");
        final Context c=input.getApplicationContext();
        final String pid=read("pidof com.nianticlabs.pikmin",64);
        if(!pid.matches("[1-9][0-9]{0,8}"))throw new IOException("遊戲程序不唯一／未啟動");
        final String before=RouteCleanupProof.serviceIdentity(read("dumpsys activity services "+RouteCleanupProof.SERVICE,8192));
        if(before.isEmpty())throw new IOException("既有JoyStick服務未啟動，診斷不代為啟動");
        final BlockingQueue<Intent> replies=new LinkedBlockingQueue<>(1);
        final BroadcastReceiver receiver=new BroadcastReceiver(){public void onReceive(Context x,Intent i){
            if("theappninjas.gpsjoystick.STATUS_RESPONSE".equals(i.getAction()))replies.offer(new Intent(i));
        }};
        c.registerReceiver(receiver,new IntentFilter("theappninjas.gpsjoystick.STATUS_RESPONSE"),Context.RECEIVER_EXPORTED);
        final String[] result={null};
        try {
            EmulatorGpsFence.execute(c,Integer.parseInt(pid),()->{
                EmulatorRouteGuard.requireNoReservation();
                if(!pid.equals(read("pidof com.nianticlabs.pikmin",64))||
                   !before.equals(RouteCleanupProof.serviceIdentity(read("dumpsys activity services "+RouteCleanupProof.SERVICE,8192))))
                    throw new IOException("原遊戲／定位服務已變更");
                replies.clear();
                // One query while the original Root GPS actor is held. No
                // resend on timeout and no implicit launch of absent service.
                c.startForegroundService(new Intent("theappninjas.gpsjoystick.STATUS")
                    .setComponent(ComponentName.unflattenFromString(RouteCleanupProof.SERVICE)));
                Intent at=replies.poll(3,TimeUnit.SECONDS);
                if(at==null)throw new IOException("原STATUS回覆逾時，未重送");
                if(!at.hasExtra("speed")||!at.hasExtra("is_running")||!at.hasExtra("is_route_active"))
                    throw new IOException("STATUS欄位缺失");
                Object raw=at.getExtras().get("speed");
                double speed=EmulatorJoystickSpeed.read(raw);
                if(!pid.equals(read("pidof com.nianticlabs.pikmin",64))||
                   !before.equals(RouteCleanupProof.serviceIdentity(read("dumpsys activity services "+RouteCleanupProof.SERVICE,8192))))
                    throw new IOException("回覆後原遊戲／定位服務已變更");
                result[0]="App收到STATUS：速度型別 "+raw.getClass().getSimpleName()+" · "+speed+" km/h"
                    +"\n定位服務運行="+at.getBooleanExtra("is_running",false)
                    +" · 路線行走="+at.getBooleanExtra("is_route_active",true);
            });
        } finally {c.unregisterReceiver(receiver);}
        return result[0]+"\nRoot GPS鎖正常取得／釋放及程序退出已確認。"
            +"\n僅診斷；不是provider排空、核心載入或每日作業就緒。";
    }
}

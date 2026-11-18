package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Explicit visible handoff; bounded same-consent observation, never installs or reboots. */
public final class PhoneConnectionService extends Service {
    private static final int ID=178;
    private static final String CHANNEL="phone-connection",STOP="stop-observing",CONSENT="consent_id";
    private volatile boolean stopped,resultPosted;
    private Thread worker;
    private final Handler deadlineHandler=new Handler(Looper.getMainLooper());
    private final Runnable timeout=()->finishReview(true);
    private String key="";
    static void start(Context c){
        String key=PhoneUpdateFlow.observationKey(c);
        if(!PhoneUpdateFlow.observing(c)||key.isEmpty())return;
        c.startForegroundService(new Intent(c,PhoneConnectionService.class).putExtra(CONSENT,key));
    }
    @Override public void onCreate(){
        super.onCreate();
        try{
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"遊戲連線核對",NotificationManager.IMPORTANCE_LOW));
            Notification n=notification("正在核對遊戲連線","請讓 Pikmin 保持前景；最多兩分鐘",true);
            if(Build.VERSION.SDK_INT>=34)startForeground(ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);else startForeground(ID,n);
            deadlineHandler.postDelayed(timeout,120_000);
        }catch(RuntimeException denied){stopped=true;stopSelf();}
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent!=null&&STOP.equals(intent.getAction())){stopped=true;stopSelf();return START_NOT_STICKY;}
        if(stopped||worker!=null)return START_NOT_STICKY;
        key=intent==null?"":intent.getStringExtra(CONSENT);
        if(key==null||key.isEmpty()||!key.equals(PhoneUpdateFlow.observationKey(this))||!PhoneUpdateFlow.observing(this)){
            stopped=true;stopSelf();return START_NOT_STICKY;
        }
        worker=new Thread(()->{
            long end=SystemClock.elapsedRealtime()+120_000;
            try{
                while(!stopped&&SystemClock.elapsedRealtime()<end&&key.equals(PhoneUpdateFlow.observationKey(this))&&PhoneUpdateFlow.observing(this)){
                    PhoneUpdateFlow.observe(this,key);
                    if(!PhoneUpdateFlow.active(this)||"error".equals(PhoneUpdateFlow.phase(this)))break;
                    Thread.sleep(1500);
                }
            }catch(InterruptedException cancelled){Thread.currentThread().interrupt();}
            catch(RuntimeException unavailable){/* Durable flow stays available for visible retry. */}
            finally{deadlineHandler.post(()->finishReview(false));}
        },"phone-connection-review");worker.start();
        return START_NOT_STICKY;
    }
    /** Deadline and completion are serialized on the main looper; timeout never claims success. */
    private void finishReview(boolean timedOut){
        if(stopped)return;
        stopped=true;deadlineHandler.removeCallbacks(timeout);
        stopForeground(STOP_FOREGROUND_REMOVE);
        if(key!=null&&!key.isEmpty()&&key.equals(PhoneUpdateFlow.observationKey(this))){
            boolean complete="complete".equals(PhoneUpdateFlow.phase(this));
            String detail=complete?PhoneUpdateFlow.status(this):timedOut
                ?"兩分鐘核對時間已到，尚未確認完成；點此繼續。原設定與交易紀錄保留。"
                :"進度已保存，點此返回控制中心繼續核對。";
            try{getSystemService(NotificationManager.class).notify(ID,notification(complete?"遊戲連線已確認":"遊戲連線仍待核對",detail,false));resultPosted=true;}
            catch(RuntimeException denied){/* App retains the same durable state if notifications are denied. */}
        }
        stopSelf();
    }
    private Notification notification(String title,String text,boolean ongoing){
        Intent open=PhoneUpdateFlow.setup(this)?new Intent(this,SetupActivity.class):new Intent(this,MainActivity.class).putExtra("open_updates",true);
        PendingIntent p=PendingIntent.getActivity(this,ID,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setContentIntent(p).setOnlyAlertOnce(true).setOngoing(ongoing).setAutoCancel(!ongoing);
        if(ongoing)b.addAction(new Notification.Action.Builder(null,"停止核對",PendingIntent.getService(this,ID,
            new Intent(this,PhoneConnectionService.class).setAction(STOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
        return b.build();
    }
    @Override public void onDestroy(){deadlineHandler.removeCallbacks(timeout);stopped=true;if(worker!=null)worker.interrupt();if(!resultPosted)stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}

package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.util.concurrent.*;

/** MuMu foreground scheduler source, NOT registered/deployed. Explicit UI
 * enable is separate. Sticky restore only reads the durable same-config ticket;
 * every tick still checks actual core/PID/account/location/acceptance gates.
 */
public final class EmulatorDailyService extends Service {
    static final String START="dev.ody.pikmincontrol.mumu.DAILY_START";
    static final String STOP="dev.ody.pikmincontrol.mumu.DAILY_STOP";
    static final String EXTRA="daily-ticket";
    private static final String CHANNEL="mumu-daily";
    private static final int NOTICE=241;
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private EmulatorDailyLoop loop;
    private EmulatorDailySchedule.Ticket held;
    private final EmulatorDailyServiceControl lifecycle=new EmulatorDailyServiceControl();

    /** Caller must first explicitly enable via the actual idle/acceptance gate.
     * Service never calls enable itself, including sticky null-intent restore. */
    static void start(Context context,EmulatorDailySchedule.Ticket ticket){
        EmulatorRuntime.require();
        if(context==null||ticket==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))throw new IllegalArgumentException("MuMu held ticket required");
        context.startForegroundService(new Intent(context,EmulatorDailyService.class).setAction(START).putExtra(EXTRA,ticket.encode()));
    }
    @Override public void onCreate(){super.onCreate();EmulatorRuntime.require();}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        try{
            if(intent!=null&&STOP.equals(intent.getAction())){
                return stopRequested(intent);
            }
            if(intent!=null&&!START.equals(intent.getAction())){if(lifecycle.idle())finishService();return lifecycle.idle()?START_NOT_STICKY:START_STICKY;}
            final EmulatorDailySchedule.Ticket requested=intent==null?null:
                EmulatorDailySchedule.Ticket.decode(intent.getStringExtra(EXTRA),System.currentTimeMillis());
            if(!StepPermissions.notifications(this))throw new IllegalStateException("Visible scheduler notification required");
            NotificationManager manager=getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,"每日作業",NotificationManager.IMPORTANCE_LOW));
            startForeground(NOTICE,notification("正在核對保存的排程"),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            final long mine;
            synchronized(this){mine=lifecycle.request(requested);}
            control.execute(()->activate(requested,mine));
            return START_STICKY;
        }catch(Exception unknown){
            // Preserve ticket/unknown bytes. Never re-enable or auto-load core.
            synchronized(this){lifecycle.destroy();if(loop!=null)loop.close();}
            finishService();return START_NOT_STICKY;
        }
    }
    private int stopRequested(Intent intent){
        EmulatorDailySchedule.Ticket stopping=null;
        try{
            stopping=EmulatorDailySchedule.Ticket.decode(intent.getStringExtra(EXTRA),System.currentTimeMillis());
            // Independent immutable marker: never wait for tick locks. Stale
            // STOP changes only its own marker, not current service lifetime.
            EmulatorAndroidDailySchedule.stop(this,stopping);
            synchronized(this){
                EmulatorDailyServiceControl.Stop decision=lifecycle.stop(stopping);
                if(decision.closeActive&&loop!=null)loop.close();
                if(decision.finish)finishService();
                return decision.finish?START_NOT_STICKY:START_STICKY;
            }
        }catch(Exception unknown){
            synchronized(this){
                if(stopping!=null){
                    EmulatorDailyServiceControl.Stop decision=lifecycle.stop(stopping);
                    if(decision.closeActive&&loop!=null)loop.close();
                }
                if(lifecycle.idle())finishService();
                // Preserve unknown bytes; never claim durable stopped success.
                // Failure/late STOP cannot cancel another pending generation.
                return lifecycle.idle()?START_NOT_STICKY:START_STICKY;
            }
        }
    }
    private void activate(EmulatorDailySchedule.Ticket requested,long mine){
        boolean admitted=false;
        try{
            EmulatorDailyLoop draining=null;
            synchronized(this){
                if(!lifecycle.current(mine))return;
                if(loop!=null&&((requested!=null&&held!=null&&!held.encode().equals(requested.encode()))||loop.snapshot().phase!=EmulatorDailyLoop.Phase.RUNNING)){
                    draining=loop;draining.close();
                }
            }
            if(draining!=null&&!draining.awaitStopped(5000)){
                // Wait ONLY this confirmed in-process timer/accepted tick, in
                // bounded chunks. No effects or generic storage/root retries.
                synchronized(this){if(lifecycle.current(mine))control.execute(()->activate(requested,mine));}return;
            }
            EmulatorDailySchedule.Ticket saved=EmulatorAndroidDailySchedule.enabled(this);
            synchronized(this){
                if(!lifecycle.current(mine))return;
                if(!lifecycle.admit(mine,saved)){
                    if(lifecycle.idle())finishService();return;
                }
                admitted=true;
                if(loop!=null){
                    if(held!=null&&held.encode().equals(saved.encode())&&loop.snapshot().phase==EmulatorDailyLoop.Phase.RUNNING)return;
                    loop.close(); // new ticks still use exclusive store locks, never adopt work
                }
                held=saved;loop=EmulatorAndroidDailySchedule.create(this,saved);loop.start();
                main.removeCallbacks(status);main.post(status);
            }
        }catch(Exception failure){
            synchronized(this){
                if(admitted){
                    if(!lifecycle.sameGeneration(mine))return;
                    if(loop!=null)loop.close();if(lifecycle.launchFailed(mine))finishService();
                }else{
                    if(!lifecycle.current(mine))return;if(lifecycle.failed(mine))finishService();
                }
            }
        }
    }
    private final Runnable status=new Runnable(){public void run(){
        synchronized(EmulatorDailyService.this){
            if(lifecycle.destroyed()||loop==null)return;
            EmulatorDailyLoop.Snapshot view=loop.snapshot();
            String text=view.inFlight?"正在核對本輪工作":view.outcome==EmulatorDailyLoop.Outcome.TICK_FAILED?"等待就緒；未立即重送":
                view.phase==EmulatorDailyLoop.Phase.RUNNING?"持續排程已開啟；每輪重新核對":"排程已停止；既有工作獨立確認";
            getSystemService(NotificationManager.class).notify(NOTICE,notification(text));
            if(view.phase==EmulatorDailyLoop.Phase.STOPPED&&lifecycle.loopFinished(held)){finishService();return;}
            main.postDelayed(this,10000);
        }
    }};
    private void finishService(){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    private synchronized Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,NOTICE,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("每日作業").setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true);
        if(held!=null){
            Intent stop=new Intent(this,EmulatorDailyService.class).setAction(STOP).setData(Uri.parse("pikmincontrol://daily-stop/"+held.id)).putExtra(EXTRA,held.encode());
            PendingIntent action=PendingIntent.getService(this,NOTICE,stop,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null,"停止排程",action).build());
        }
        return b.build();
    }
    @Override public void onTimeout(int startId,int fgsType){
        synchronized(this){lifecycle.destroy();if(loop!=null)loop.close();}finishService();
    }
    @Override public void onDestroy(){
        synchronized(this){lifecycle.destroy();if(loop!=null)loop.close();}
        main.removeCallbacks(status);control.shutdown(); // never interrupt accepted tick/effects
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent){return null;}
}

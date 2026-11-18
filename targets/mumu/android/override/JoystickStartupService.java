package dev.ody.pikmincontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/** Bounded, non-sticky boot restoration; not the GPS provider itself. */
public final class JoystickStartupService extends Service {
    static final String CANCEL = "dev.ody.pikmincontrol.CANCEL_GPS_STARTUP";
    private static final String CHANNEL = "joystick-startup";
    private static final int NOTIFICATION_ID = 153;
    private final Handler main = new Handler(Looper.getMainLooper());
    private JoystickStartup.Session session;
    private Thread worker;
    private boolean foreground;
    private final Runnable timeout = () -> {
        if (session != null && session.finished) { stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(JoystickStartupService.this); stopSelf(); return; }
        if (session != null) session.cancelled = true;
        if (worker != null) worker.interrupt();
        JoystickStartup.record(this, "failed", "啟動檢查已達120秒上限；不停止既有 GPS");
        stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(JoystickStartupService.this);
        stopSelf();
    };

    @Override public void onCreate() {
        super.onCreate();
        try {
            getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "GPS 開機啟動", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(this, 153, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent cancel = PendingIntent.getService(this, 153, new Intent(this, JoystickStartupService.class).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification notification = ControlNotifications.grouped(this,new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentTitle("正在啟動 GPS JoyStick").setContentText("檢查定位後自動結束；最多120秒")
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                    .addAction(new Notification.Action.Builder(null, "停止檢查", cancel).build()));
            if (Build.VERSION.SDK_INT >= 34) {startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);ControlNotifications.refresh(this);}
            else {startForeground(NOTIFICATION_ID, notification);ControlNotifications.refresh(this);}
            foreground = true;
            main.postDelayed(timeout, 120_000);
        } catch (RuntimeException error) {
            JoystickStartup.creationFailed(this, error);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && CANCEL.equals(intent.getAction())) { JoystickStartup.cancel(this); return START_NOT_STICKY; }
        if (!foreground) return START_NOT_STICKY;
        if (worker != null) return START_NOT_STICKY; // Duplicate starts never extend the deadline.
        session = JoystickStartup.begin(this, intent == null ? null : intent.getStringExtra("token"));
        if (session == null) { stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(JoystickStartupService.this); stopSelf(); return START_NOT_STICKY; }
        JoystickStartup.Session run = session;
        worker = new Thread(() -> {
            JoystickStartup.run(this, run);
            main.post(() -> { main.removeCallbacks(timeout); stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(JoystickStartupService.this); stopSelf(); });
        }, "joystick-foreground-startup");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        main.removeCallbacks(timeout);
        if (session != null) JoystickStartup.destroyed(this, session);
        if (worker != null && worker.isAlive()) worker.interrupt();
        stopForeground(STOP_FOREGROUND_REMOVE);ControlNotifications.refresh(JoystickStartupService.this);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}

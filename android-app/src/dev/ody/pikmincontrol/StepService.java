package dev.ody.pikmincontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/** Shared start/stop run identity for the service worker and the op queue. */
final class StepRunIdentity {
    private static final java.util.concurrent.atomic.AtomicLong LIVE = new java.util.concurrent.atomic.AtomicLong();
    private long mine;
    private long epoch = -1L;

    long begin(long commandEpoch, long token) {
        epoch = commandEpoch;
        mine = token;
        LIVE.set(token);
        return mine;
    }

    boolean matches(long commandEpoch, long token) {
        return mine != 0L && mine == token && epoch == commandEpoch && mine == LIVE.get();
    }

    boolean isCurrent() {
        return mine != 0L && mine == LIVE.get();
    }

    long id() { return mine; }

    long epoch() { return epoch; }

    static long live() { return LIVE.get(); }
}

/** Experimental steps writer. Default off. Never writes via root. START is a single writer. */
public final class StepService extends Service {
    static final String START = "dev.ody.pikmincontrol.START_STEPS";
    static final String STOP = "dev.ody.pikmincontrol.STOP_STEPS";
    private static final String CHANNEL = "step-tool";
    private static final int NOTIFICATION_ID = 161;
    private static volatile StepService active;
    private static volatile boolean running;
    private static volatile long liveEpoch = -1L;
    private static volatile long liveRun;
    private final StepWriterControl.Controller controller = new StepWriterControl.Controller();
    private StepWriterControl.Command current;
    private Thread worker;
    private volatile long stoppingRun;

    static synchronized boolean start(Context context, long commandEpoch, long runToken) {
        if(UpdateInstaller.blocking(context)||CoreInstallWizard.preparing(context))return false;
        if (running && liveEpoch == commandEpoch) return true;
        Intent intent = new Intent(context, StepService.class);
        StepWriterControl.applyTo(intent, StepWriterControl.startCommand(commandEpoch, runToken));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
            return true;
        } catch (RuntimeException failure) {
            StepHealthGateway.engine(context).launchFailed(commandEpoch);
            return false;
        }
    }

    /** Called only while a Control Center Activity is visible; boot restore remains a bounded JobScheduler fallback. */
    static boolean resumeFromVisibleApp(Context context) {
        StepEngine engine = StepHealthGateway.engine(context.getApplicationContext());
        StepView view = engine.snapshot();
        if (!StepWriterControl.mayLaunchService(view)) return false;
        long epoch = engine.commandEpoch();
        if (isServing(epoch)) return true;
        boolean launched = start(context.getApplicationContext(), epoch, StepWriterControl.nextRunToken());
        if (launched) StepSyncJob.scheduleRecovery(context.getApplicationContext(), epoch);
        return launched;
    }

    static void signalStop(Context context) {
        long epoch = liveEpoch;
        long run = liveRun;
        if (epoch < 0L || run == 0L) return;
        try { context.startService(stopIntent(context, epoch, run)); }
        catch (RuntimeException ignored) { }
    }
    static void pauseForInstall(Context c)throws Exception {
        StepSyncJob.cancel(c);c.stopService(new Intent(c,StepService.class));
        if(!UpdatePreparation.STEPS.drain(15_000))throw new IllegalStateException("步數寫入尚在返回，請重試安裝準備");
    }

    static StepView stop(Context context, long commandEpoch, long runToken) {
        context.startService(stopIntent(context, commandEpoch, runToken));
        StepView view = new StepView(StepView.STOPPED, "已停止；設定保留，未完成寫入仍會核對。",
                null, null, 0L, 0, false, true, false, false, "", null, null, "");
        return view;
    }

    static Intent stopIntent(Context context, long commandEpoch, long runToken) {
        Intent intent = new Intent(context, StepService.class);
        StepWriterControl.applyTo(intent, StepWriterControl.stopCommand(commandEpoch, runToken));
        return intent;
    }

    static boolean isRunning() { return running; }

    static long liveCommandEpoch() { return liveEpoch; }

    static long liveRunToken() { return liveRun; }

    static boolean isServing(long epoch) { return running && liveEpoch == epoch; }

    @Override public void onCreate() {
        super.onCreate();
        active = this;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        StepWriterControl.Command command = StepWriterControl.fromIntent(intent);
        if (STOP.equals(command.action)) {
            if (!StepWriterControl.shouldApplyStop(command, liveEpoch, liveRun))
                return START_NOT_STICKY;
            stoppingRun = command.runToken;
            StepEngine engine = StepHealthGateway.engine(this);
            if (StepWriterControl.shouldStopEngine(engine, command)) engine.stop();
            if (worker != null) worker.interrupt();
            if (!running) { stopSelf(); return START_NOT_STICKY; }
            return START_NOT_STICKY;
        }
        if (!START.equals(command.action)) { if (!running) stopSelf(); return START_NOT_STICKY; }
        StepEngine engine = StepHealthGateway.engine(this);
        if(UpdateInstaller.blocking(this)||CoreInstallWizard.preparing(this)){stopSelf();return START_NOT_STICKY;}
        if (!controller.accept(engine, command)) {
            if (!running) stopSelf();
            return START_NOT_STICKY;
        }
        current = command;
        final long epoch = command.commandEpoch;
        final long runId = command.runToken;
        liveEpoch = epoch;
        liveRun = runId;
        // Previous worker may still be returning from I/O. Its bound tick cannot send for this epoch.
        stoppingRun = 0L;
        try {
            if (!StepPermissions.notifications(this)) throw new IllegalStateException("notifications");
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "步數工具", NotificationManager.IMPORTANCE_LOW));
            if (Build.VERSION.SDK_INT >= 34)
                startForeground(NOTIFICATION_ID, notification("步數工具已開", "維持約每分鐘寫入"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(NOTIFICATION_ID, notification("步數工具已開", "尚未寫入"));
            ControlNotifications.refresh(this);
        } catch (RuntimeException error) {
            controller.launchFailed(engine, command);
            finish(command);
            return START_NOT_STICKY;
        }
        running = true;
        worker = new Thread(new Runnable() {
            public void run() { loop(epoch, runId); }
        }, "step-writer");
        worker.start();
        return START_NOT_STICKY;
    }

    private void loop(long epoch, long runId) {
        StepEngine engine = StepHealthGateway.engine(this);
        try {
            StepView admitted = StepWriterControl.admitWorker(engine, StepWriterControl.startCommand(epoch, runId));
            if (!StepWriterControl.mayLaunchService(admitted)
                    || !controller.admitted(engine, StepWriterControl.startCommand(epoch, runId))) return;
            notify(admitted);
            while (!UpdateInstaller.blocking(this) && !CoreInstallWizard.preparing(this) && stoppingRun != runId && controller.matches(StepWriterControl.startCommand(epoch, runId)) && engine.epochCurrent(epoch) && engine.snapshot().desiredRunning) {
                StepView view = engine.tickBound(epoch);
                if (controller.matches(StepWriterControl.startCommand(epoch, runId))) notify(view);
                if (StepView.STOPPED.equals(view.phase) || StepView.UNSUPPORTED.equals(view.phase)
                        || StepView.STORE_ERROR.equals(view.phase) || StepView.REJECTED.equals(view.phase)) break;
                long wait = engine.nextTickDelayMs();
                if (StepView.WAITING_PERMISSION.equals(view.phase)) wait = Math.max(wait, 5_000L);
                try { Thread.sleep(wait); }
                catch (InterruptedException ignored) { return; }
            }
        } finally {
            finish(StepWriterControl.startCommand(epoch, runId));
        }
    }

    private void finish(StepWriterControl.Command command) {
        controller.finish(command, new Runnable() {
            public void run() {
                running = false;
                liveEpoch = -1L;
                liveRun = 0L;
                stopForeground(STOP_FOREGROUND_REMOVE);
                ControlNotifications.refresh(StepService.this);
                stopSelf();
            }
        });
    }

    private void notify(StepView view) {
        AutomationAlerts.steps(this,view);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) ControlNotifications.post(this, NOTIFICATION_ID, notification(NoticePresentation.stepTitle(view.phase), NoticePresentation.stepText(view)));
    }

    private Notification notification(String title, String text) {
        PendingIntent open = PendingIntent.getActivity(this, 161, new Intent(this, StepActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 162, stopIntent(this, liveEpoch, liveRun),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return ControlNotifications.grouped(this, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(new Notification.Action.Builder(null, "停止步數", stop).build()));
    }

    @Override public void onDestroy() {
        stoppingRun = current == null ? 0L : current.runToken;
        if (worker != null) worker.interrupt();
        controller.finish(current, new Runnable() {
            public void run() {
                if (active == StepService.this) {
                    running = false;
                    liveEpoch = -1L;
                    liveRun = 0L;
                }
            }
        });
        if (active == this) active = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
        ControlNotifications.refresh(this);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}

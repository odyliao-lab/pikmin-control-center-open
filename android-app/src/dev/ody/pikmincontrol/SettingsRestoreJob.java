package dev.ody.pikmincontrol;

import android.app.job.*;
import android.content.*;

/** One bounded check after boot/update. Does not start a batch, a route or a game. */
public final class SettingsRestoreJob extends JobService {
    private static final int ID = 154;
    private volatile Thread task;
    private static final java.util.concurrent.atomic.AtomicBoolean restoring=new java.util.concurrent.atomic.AtomicBoolean();
    static boolean restoreForPid(Context c,String pid) {
        if(!UpdatePreparation.restore(c))return false;
        if(!restoring.compareAndSet(false,true))return false;
        boolean held=false;
        try {
            GpsWorkLock.acquire(c,"settings-restore");held=true;
            CorePair pair=CoreUpdater.pair(c);
            if(!pid.equals(CoreUpdater.runtimePid(c,pair)))return false;
            boolean feeding=FeedingGateway.reconcile(c);
            GpsWorkLock.touch(c,"settings-restore");
            boolean choices=AutomationPreferences.reconcile(c);
            return feeding && choices && pid.equals(CoreUpdater.runtimePid(c,pair));
        } catch(Exception unavailable) {return false;}
        finally {if(held)GpsWorkLock.release(c,"settings-restore");restoring.set(false);}
    }
    static void restore(Context c){
        if(!UpdatePreparation.restore(c))return;
        if(!restoring.compareAndSet(false,true))return;
        boolean held=false;
        try{
            // A short-lived owner excludes jobs/installers without holding their UI monitor during root I/O.
            GpsWorkLock.acquire(c,"settings-restore");held=true;
            FeedingGateway.reconcile(c);GpsWorkLock.touch(c,"settings-restore");AutomationPreferences.reconcile(c);
        }catch(Exception unavailable){/* Retry on a subsequent open/unlock; never clear an existing owner. */}
        finally{if(held)GpsWorkLock.release(c,"settings-restore");restoring.set(false);CollectionMonitorService.sync(c);JoystickStartup.afterCoreReady(c);}
    }
    static void schedule(Context context) {
        JobScheduler jobs=context.getSystemService(JobScheduler.class);
        if(jobs!=null)jobs.schedule(new JobInfo.Builder(ID,new ComponentName(context,SettingsRestoreJob.class))
                .setMinimumLatency(3000).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        task=new Thread(()->{try{restore(getApplicationContext());}
            finally{
                jobFinished(params,false);
                if(UpdatePreparation.needsRestore(this)){
                    JobScheduler jobs=getSystemService(JobScheduler.class);
                    if(jobs!=null)jobs.schedule(new JobInfo.Builder(ID,new ComponentName(this,SettingsRestoreJob.class))
                        .setMinimumLatency(15_000).build());
                }
            }},"settings-restore");task.start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { if(task!=null)task.interrupt();return false; }
}

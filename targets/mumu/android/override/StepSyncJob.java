package dev.ody.pikmincontrol;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

/** Best-effort background writer. One bounded tick per job; never a health foreground service. */
public final class StepSyncJob extends JobService {
    static final int JOB_A = 31610;
    static final int JOB_B = 31611;
    static final long MIN_DELAY_MS = 60_000L;
    static final long RECOVERY_DELAY_MS = 5 * 60_000L;

    static boolean schedule(Context context, long epoch) { return schedule(context, epoch, 0, MIN_DELAY_MS); }

    static boolean scheduleRecovery(Context context, long epoch) {
        return schedule(context, epoch, 0, RECOVERY_DELAY_MS);
    }

    static boolean schedule(Context context, long epoch, int slot, long delayMs) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || epoch < 0L) return false;
        PersistableBundle extras = new PersistableBundle();
        extras.putLong("epoch", epoch);
        extras.putInt("slot", slot);
        JobInfo info = new JobInfo.Builder(slot == 0 ? JOB_A : JOB_B,
                new ComponentName(context, StepSyncJob.class))
                .setMinimumLatency(Math.max(MIN_DELAY_MS, delayMs))
                .setOverrideDeadline(Math.max(MIN_DELAY_MS, delayMs) + 5 * 60_000L)
                .setPersisted(true)
                .setExtras(extras)
                .build();
        return scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS;
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) { scheduler.cancel(JOB_A); scheduler.cancel(JOB_B); }
    }

    static void restore(Context context) {
        StepEngine engine = StepHealthGateway.engine(context.getApplicationContext());
        StepView view = engine.snapshot();
        if (view.desiredRunning) scheduleRecovery(context, engine.commandEpoch());
        else cancel(context);
    }

    @Override public boolean onStartJob(final JobParameters params) {
        final long epoch = params.getExtras().getLong("epoch", -1L);
        final int slot = params.getExtras().getInt("slot", 0);
        new Thread(() -> {
            StepEngine engine = StepHealthGateway.engine(getApplicationContext());
            StepView before = engine.continueAuthorizedRun(epoch);
            StepView after = before;
            if (before.desiredRunning && engine.epochCurrent(epoch) && !StepService.isServing(epoch))
                after = engine.tickBound(epoch);
            boolean again = after.desiredRunning && engine.epochCurrent(epoch)
                    && !StepView.STOPPED.equals(after.phase) && !StepView.UNSUPPORTED.equals(after.phase)
                    && !StepView.STORE_ERROR.equals(after.phase);
            jobFinished(params, false);
            if (again) {
                StepSettings settings = engine.safeSettings();
                long delay = settings == null ? RECOVERY_DELAY_MS
                        : Math.max(RECOVERY_DELAY_MS, settings.intervalMs);
                schedule(getApplicationContext(), epoch, slot == 0 ? 1 : 0, delay);
            }
        }, "step-sync-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // No replay: the ledger resolves uncertain writes by the same clientRecordId on the next run.
        return false;
    }
}

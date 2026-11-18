package dev.ody.pikmincontrol;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;

/** Compatibility shim for old callers/job153. New versions never schedule a GPS job. */
public final class JoystickStartupJob extends JobService {
    static final int JOB_ID = 153;
    static void schedule(Context c, boolean manual) { JoystickStartup.request(c, manual, manual ? "manual" : "boot"); }
    static void recoverOnOpen(Context c) { JoystickStartup.recoverOnOpen(c); }
    static void cancelAutomatic(Context c) { JoystickStartup.cancelAutomatic(c); }
    static String status(Context c) { return JoystickStartup.status(c); }
    @Override public boolean onStartJob(JobParameters params) { return false; }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}

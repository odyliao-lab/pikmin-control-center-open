package dev.ody.pikmincontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores only the user's explicit GPS JoyStick startup preference. */
public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        EmulatorSavedDailyService.restore(context);
        if (intent == null || (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && !Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        SetupReminder.onBoot(context);
        SettingsRestoreJob.schedule(context);
        CollectionMonitorService.sync(context);
        StepSyncJob.restore(context);
        if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))GamePidRecoveryService.forgetBaseline(context);
        if(ControlSettings.restartGameOnPidLoss(context) && Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
            try { GamePidRecoveryService.sync(context); }
            catch(RuntimeException error) { android.util.Log.e("PikminPidRecovery","Cannot restore opt-in guard",error); }
        }
        if (!ControlSettings.startJoystickOnBoot(context) || SetupDeployment.pending(context)) return;
        try {
            JoystickStartup.request(context, false, intent.getAction());
        } catch (Exception error) {
            android.util.Log.e("PikminJoystickStartup", "Cannot schedule boot check", error);
        }
    }
}

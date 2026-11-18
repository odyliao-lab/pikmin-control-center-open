package dev.ody.pikmincontrol;

import android.content.Context;

/** Actual configured tick composition for the future foreground scheduler.
 * Creating this driver does not start it, establish opt-in, load a core or
 * prove readiness. No normal-phone registration or product UI is added here.
 */
final class EmulatorAndroidDailyLoop {
    static EmulatorDailyLoop create(Context context){
        EmulatorRuntime.require();
        if(context==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))
            throw new IllegalArgumentException("MuMu-only application context required");
        Context app=context.getApplicationContext();
        if(app==null||!"dev.ody.pikmincontrol.mumu".equals(app.getPackageName()))
            throw new IllegalArgumentException("MuMu-only application context required");
        return new EmulatorDailyLoop(()->EmulatorAndroidDailyConfigured.run(app),60000);
    }
}

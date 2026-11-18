package dev.ody.pikmincontrol;

import android.app.Application;

public final class EmulatorApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        EmulatorRuntime.require();
        if(!"dev.ody.pikmincontrol.mumu".equals(getPackageName()))throw new SecurityException("Wrong experimental package");
        // Separate preferences; no migration of phone choices and no public release channel.
        UpdateStore.automatic(this,false);
    }
}

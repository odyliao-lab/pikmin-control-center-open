package dev.ody.pikmincontrol;
import android.content.Context;import java.io.*;
final class UpdateInfo {static final String PACKAGE="dev.ody.pikmincontrol";long code=57,size=3;}
final class MainActivity extends android.app.Activity {}
final class UpdateController {
 static boolean busy(){return UpdateInstaller.preparing;}static File apk(Context c){return UpdateStore.apk(c);}
 static void verify(Context c,File f,UpdateInfo i)throws Exception{UpdateStore.verify(c,f,i);}
}
final class GpsWorkLock {static boolean blocked;static void requireInstallIdle(Context c){if(blocked)throw new IllegalStateException("active work");}}
final class UpdatePreparation {
 static boolean held;static int ended;static boolean blocking(Context c){return held;}
 static void prepare(Context c,long code){}static void requireReady(Context c){}
 static void installedOrCancelled(Context c){held=false;ended++;}
}
final class PhoneUpdateFlow {static int intents,ended;static void apkIntent(Context c){intents++;UpdatePreparation.held=true;}static void apkEnded(Context c){ended++;}}
final class SetupDeployment {static boolean active;static void requireGameIdle(){if(active)throw new IllegalStateException("game active");}}
final class FeedingGateway {boolean enabled;static boolean active;static FeedingGateway load(Context c){FeedingGateway g=new FeedingGateway();g.enabled=active;return g;}}

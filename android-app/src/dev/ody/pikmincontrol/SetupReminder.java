package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;

final class SetupReminder {
    static void onBoot(Context c){
        if(!SetupStore.prefs(c).getBoolean("install_review",false))return;
        try {
            NotificationManager n=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
            n.createNotificationChannel(new NotificationChannel("device-setup","裝置設定",NotificationManager.IMPORTANCE_DEFAULT));
            Intent i=new Intent(c,SetupActivity.class);
            PendingIntent p=PendingIntent.getActivity(c,158,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            n.notify(158,new Notification.Builder(c,"device-setup").setSmallIcon(android.R.drawable.stat_sys_warning)
                    .setContentTitle("繼續設定這支手機").setContentText("重開後請核對模組，再開啟遊戲驗證。").setContentIntent(p).setAutoCancel(true).build());
        }catch(RuntimeException ignored){/* Notification denied: the persistent System entry remains available. */}
    }
}

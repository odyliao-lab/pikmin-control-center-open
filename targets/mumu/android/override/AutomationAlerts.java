package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Local-only bounded summaries; never copy raw errors, coordinates or account IDs into notifications. */
final class AutomationAlerts {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("automation_alerts",0);}
    static boolean enabled(Context c){return prefs(c).getBoolean("enabled",true);}
    static synchronized void enabled(Context c,boolean value){
        prefs(c).edit().putBoolean("enabled",value).remove("active."+GpsWorkLock.BATCH)
                .remove("active."+GpsWorkLock.FLOWER_FARM).remove("active.nursery").remove("active.steps").commit();
        if(!value)try{NotificationManager m=c.getSystemService(NotificationManager.class);m.cancel(180);m.cancel(181);m.cancel(182);m.cancel(183);}
        catch(RuntimeException e){android.util.Log.w("PikminAlerts","Local alert mute unavailable");}
    }
    static String history(Context c){return prefs(c).getString("history","尚無自動化異常紀錄。");}
    static synchronized void work(Context c,String owner,String state,String detail,int failures,int skipped) {
        String code=AlertPolicy.code(state,detail==null?"":detail,failures,skipped);
        if(!code.isEmpty())record(c,owner,code);
        else if(NoticePresentation.clearAlert(state))resolve(c,owner);
    }
    private static boolean supported(String owner){return GpsWorkLock.BATCH.equals(owner)||GpsWorkLock.FLOWER_FARM.equals(owner)||"nursery".equals(owner)||"steps".equals(owner);}
    private static int noticeId(String owner){return owner.equals("steps")?183:owner.equals("nursery")?182:owner.equals(GpsWorkLock.BATCH)?180:181;}
    static void steps(Context c,StepView v){
        String code=NoticePresentation.stepAlert(v.phase);
        if(!code.isEmpty())record(c,"steps",code);
        else if(NoticePresentation.stepHealthy(v.phase))resolve(c,"steps");
    }
    static synchronized void resolve(Context c,String owner){
        if(!supported(owner))return;
        try{
            SharedPreferences p=prefs(c);
            if(p.contains("active."+owner))p.edit().remove("active."+owner).commit();
            // Also removes a notification left by a pre-upgrade build without the new marker.
            NotificationManager m=c.getSystemService(NotificationManager.class);
            for(android.service.notification.StatusBarNotification n:m.getActiveNotifications())
                if(n.getTag()==null && n.getId()==noticeId(owner)){m.cancel(noticeId(owner));break;}
        }
        catch(RuntimeException e){android.util.Log.w("PikminAlerts","Local alert clear unavailable");}
    }
    static synchronized void record(Context c,String owner,String code) {
        if(!supported(owner))return;
        try {
            long now=System.currentTimeMillis();SharedPreferences p=prefs(c);
            String key="last."+owner+"."+code;
            boolean logDue=AlertPolicy.due(p.getLong(key,0),now);
            boolean newAlert=NoticePresentation.newAlert(p.getString("active."+owner,""),code);
            if(!logDue && !newAlert)return;
            String label=owner.equals("steps")?"步數工具":owner.equals("nursery")?"自動育苗":GpsWorkLock.label(owner);
            String message=AlertPolicy.message(code);
            StringBuilder log=new StringBuilder(new SimpleDateFormat("MM-dd HH:mm",Locale.TAIWAN).format(new Date(now)))
                    .append(" · ").append(label).append("：").append(message);
            String old=p.getString("history","");int count=1;
            for(String line:old.split("\n"))if(!line.isEmpty()&&count++<20)log.append('\n').append(line);
            SharedPreferences.Editor edit=p.edit().putString("active."+owner,code);
            if(logDue)edit.putLong(key,now).putString("history",log.toString());
            if(!edit.commit())return;
            if(!enabled(c)||!newAlert)return; // Same problem is not resurrected after dismissal; history remains available.
            NotificationManager manager=c.getSystemService(NotificationManager.class);
            manager.createNotificationChannel(new NotificationChannel("automation_attention","自動化異常提醒",NotificationManager.IMPORTANCE_DEFAULT));
            Intent intent=new Intent(c,owner.equals("steps")?StepActivity.class:MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(owner.equals("nursery")?MainActivity.EXTRA_OPEN_PLANTER:
                    owner.equals(GpsWorkLock.BATCH)?MainActivity.EXTRA_OPEN_DISPATCH:MainActivity.EXTRA_OPEN_FLOWER_FARM,true);
            int id=noticeId(owner);
            PendingIntent open=PendingIntent.getActivity(c,id,intent,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            manager.notify(id,new Notification.Builder(c,"automation_attention")
                    .setSmallIcon(android.R.drawable.ic_dialog_alert).setContentTitle(label+" · 需要查看")
                    .setContentText(message).setStyle(new Notification.BigTextStyle().bigText(message))
                    .setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentIntent(open).setAutoCancel(true).build());
        } catch(RuntimeException unavailable) {
            // Denied notification permission must not interrupt cleanup or native controls.
            android.util.Log.w("PikminAlerts","Local alert unavailable");
        }
    }
}

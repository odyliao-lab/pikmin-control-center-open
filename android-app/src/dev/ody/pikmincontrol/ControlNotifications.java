package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.service.notification.StatusBarNotification;
import java.util.*;

/** One silent grouped summary. OS-posted children are authoritative; no saved phantom workers. */
final class ControlNotifications {
    static final String GROUP="pcc.running";
    private static final String CHANNEL="control-summary";
    private static final int SUMMARY=160;
    private static String lastSummary="";
    private static final android.os.Handler MAIN=new android.os.Handler(android.os.Looper.getMainLooper());
    private static boolean settling;
    static Notification grouped(Context c,Notification.Builder b){
        Notification publicView=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle("Pikmin 控制中心").setContentText("背景工作執行中").build();
        return b.setGroup(GROUP).setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
                .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicView).build();
    }
    static void post(Context c,int id,Notification n){
        try{c.getSystemService(NotificationManager.class).notify(id,n);refresh(c);}catch(RuntimeException e){unavailable();}
    }
    static void batchResult(Context c,String terminal,int successes,int failures,int skipped){
        // Partial/error results already have attention alerts, never a second success card.
        if(!"completed".equals(terminal)||failures>0||skipped>0)return;
        try{
            NotificationManager m=c.getSystemService(NotificationManager.class);
            m.createNotificationChannel(new NotificationChannel("control-results","工作結果",NotificationManager.IMPORTANCE_LOW));
            PendingIntent open=PendingIntent.getActivity(c,184,new Intent(c,MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_DISPATCH,true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            m.notify(184,new Notification.Builder(c,"control-results").setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .setContentTitle("批次探險已結束").setContentText("已確認派遣 "+successes+" 筆，點此查看紀錄")
                    .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE).build());
        }catch(RuntimeException e){unavailable();}
    }
    static synchronized void refresh(Context c){
        refreshNow(c);
        // NotificationManager posts/removes asynchronously. Two coalesced, bounded
        // display-only follow-ups; not a polling loop and never an extra root read.
        if(!settling){
            settling=true;Context app=c.getApplicationContext();
            MAIN.postDelayed(()->refreshNow(app),200);
            MAIN.postDelayed(()->{synchronized(ControlNotifications.class){settling=false;refreshNow(app);}},1000);
        }
    }
    private static synchronized void refreshNow(Context c){
        try{
            NotificationManager m=c.getSystemService(NotificationManager.class);
            m.createNotificationChannel(new NotificationChannel(CHANNEL,"控制中心執行摘要",NotificationManager.IMPORTANCE_LOW));
            List<NoticePresentation.Row> rows=new ArrayList<>();
            List<Notification.Action> actions=new ArrayList<>();
            Map<Integer,Notification> children=new HashMap<>();
            boolean summaryExists=false;
            for(StatusBarNotification s:m.getActiveNotifications()){
                if(s.getId()==SUMMARY && s.getTag()==null)summaryExists=true;
                if(s.getTag()!=null || !NoticePresentation.ongoing(s.getId()) || !GROUP.equals(s.getNotification().getGroup()))continue;
                Notification n=s.getNotification(); children.put(s.getId(),n);
                if((n.flags & Notification.FLAG_FOREGROUND_SERVICE)==0)continue;
                rows.add(new NoticePresentation.Row(s.getId(),String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE,"")),
                    String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TEXT,""))));
            }
            rows=NoticePresentation.ordered(rows);
            if(rows.isEmpty()){if(summaryExists)m.cancel(SUMMARY);lastSummary="";return;}
            Notification.InboxStyle style=new Notification.InboxStyle();
            for(NoticePresentation.Row r:rows){style.addLine(r.title+" · "+r.text);
                Notification.Action[] a=children.get(r.id).actions;if(a!=null)for(Notification.Action action:a)
                    if(action.title!=null && action.title.toString().contains("停止") && actions.size()<2)actions.add(action);
            }
            StringBuilder signature=new StringBuilder();
            for(NoticePresentation.Row r:rows)signature.append(r.id).append('\n').append(r.title).append('\n').append(r.text).append('\n');
            for(Notification.Action a:actions)signature.append(a.title).append(':').append(a.actionIntent).append('\n');
            if(summaryExists && lastSummary.equals(signature.toString()))return;
            PendingIntent open=PendingIntent.getActivity(c,SUMMARY,new Intent(c,MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder b=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setContentTitle("Pikmin 控制中心").setContentText(NoticePresentation.headline(rows)).setStyle(style)
                    .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setGroup(GROUP).setGroupSummary(true)
                    .setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY).setVisibility(Notification.VISIBILITY_PRIVATE);
            for(Notification.Action action:actions)b.addAction(action);
            b.setPublicVersion(new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_sync)
                    .setContentTitle("Pikmin 控制中心").setContentText("背景工作執行中").build());
            m.notify(SUMMARY,b.build());
            lastSummary=signature.toString();
        }catch(RuntimeException e){unavailable();}
    }
    private static void unavailable(){android.util.Log.w("PikminNotices","Notification display unavailable");}
}

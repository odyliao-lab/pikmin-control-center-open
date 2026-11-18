package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MuMu-only explicit scheduling controls. Opening or granting permission does
 * not enable/restore a service. Cached display state is never admission proof.
 * Registration belongs to a successor APK, not the installed213 projection. */
public final class EmulatorDailyScheduleActivity extends Activity {
    private static final int NOTICE_PERMISSION=242;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;
    private Button start,stop,refresh,permission,acceptance;
    private EmulatorDailySchedule.Ticket shown;
    private boolean configured,busy,destroyed;

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ControlSettings.applyAppearance(base));}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(!"dev.ody.pikmincontrol.mumu".equals(getPackageName())){finish();return;}
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(12),dp(18),dp(18));page.setBackgroundColor(color("app_background"));
        page.addView(text("每日作業排程",22));
        page.addView(text("MuMu 開發驗收 · 開啟畫面不會啟動工作",14));
        status=text("尚未核對保存的設定與排程。",16);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(status);
        page.addView(text("每日目標是路線移動時持續種花，不要求特殊沿路採果。自動步數驗收可讀回原件；每輪仍重新檢查核心、PID、帳號、定位及資源，不會自動登入或載入核心。",14));
        page.addView(text("每日運作 06:00–隔日04:00，僅04:00–06:00休息；03:30轉花苗、水果及禮物盒收工批次。城市13段循環使用，午夜不重置工作。沿用既有餵食、收瓣、換隊及育苗選擇，休息時暫停新操作，不改寫開關或清除待確認紀錄。停止排程會解除時段管理。步數時段請設06–04。",14));
        acceptance=button(page,"開啟每日作業驗收",()->startActivity(new Intent(this,EmulatorDailyAcceptanceActivity.class)));
        button(page,"開發：15分鐘三類收工批次",()->{if(!busy)startActivity(new Intent(this,EmulatorDevelopmentBatchActivity.class));});
        permission=button(page,"允許排程狀態通知",()->requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},NOTICE_PERMISSION));
        refresh=button(page,"重新核對狀態",this::refresh);
        start=button(page,"啟用每日排程",this::confirmStart);
        stop=button(page,"停止接收新的每日工作",this::confirmStop);
        page.addView(text("停止只取消這份排程後續的 tick；已接收的路線、派遣及領取仍需各自安全確認。不會強制中斷、刪除日誌或重送未知操作。",14));
        button(page,"返回",this::finish);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);setContentView(scroll);controls();
    }
    @Override protected void onResume(){super.onResume();if(status!=null)refresh();}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==NOTICE_PERMISSION&&status!=null)refresh(); // never auto-enable after permission
    }
    private void refresh(){task("正在核對保存的排程…",()->readView(),"狀態未確認。原設定與工作保留；未啟用或重送任何操作。");}
    private View readView()throws Exception{
        EmulatorDailySchedule.Ticket ticket=EmulatorAndroidDailySchedule.enabled(this);
        boolean valid=false;EmulatorDailyConfiguration config=null;
        try{
            config=EmulatorAndroidDailyConfigured.read(this);
            valid=ticket==null||ticket.configSha.equals(EmulatorDailyRunner.digest(config.encode()));
        }catch(Exception invalid){ /* unknown configuration never permits start; a valid ticket can still be stopped */ }
        String message=ticket==null?
                "未開啟新的每日工作；既有工作仍需獨立確認。":
                "已有保存的排程啟用請求；這不是服務正在運行或本輪完成的證明。";
        return new View(ticket,valid,message+"\n"+(valid?daySummary(config):EmulatorDailyStatusPresenter.UNAVAILABLE));
    }
    private String daySummary(EmulatorDailyConfiguration config){
        if(config==null)return EmulatorDailyStatusPresenter.UNAVAILABLE;
        try{
            java.nio.file.Path files=EmulatorAndroidDailyStore.files(this);
            if(!java.nio.file.Files.exists(files.resolve("daily-runner").resolve("journal.tsv"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return EmulatorDailyStatusPresenter.render(null,config.binding,System.currentTimeMillis());
            if(!java.nio.file.Files.isRegularFile(files.resolve("daily-runner").resolve("runner.lock"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return EmulatorDailyStatusPresenter.UNAVAILABLE;
            // Existing namespace only; a nonblocking lock conflict displays
            // unknown. No state creation, readiness adoption or tick retry.
            try(EmulatorAndroidDailyStore store=EmulatorAndroidDailyStore.open(this)){
                return EmulatorDailyStatusPresenter.render(store.read(),config.binding,System.currentTimeMillis());
            }
        }catch(Exception unavailable){return EmulatorDailyStatusPresenter.UNAVAILABLE;}
    }
    private void confirmStart(){
        if(busy||destroyed||!configured||!StepPermissions.notifications(this))return;
        final EmulatorDailySchedule.Ticket requested=shown;
        new AlertDialog.Builder(this).setTitle(requested==null?"啟用每日排程？":"要求恢復已保存排程？")
            .setMessage("將要求啟動有持續狀態通知的服務。每輪仍受原驗收及安全守門限制，不會登入、載入核心或接管其他工作。")
            .setNegativeButton("取消",null).setPositiveButton("確認",(d,w)->task("正在保存並要求啟動…",()->{
                if(!StepPermissions.notifications(this))throw new IllegalStateException("Visible notification required");
                EmulatorDailySchedule.Ticket ticket;
                if(requested==null){
                    ticket=EmulatorAndroidDailySchedule.enable(this); // original fresh acceptance/idle lock and durability checks
                }else{
                    ticket=EmulatorAndroidDailySchedule.enabled(this);
                    if(ticket==null||!ticket.encode().equals(requested.encode()))throw new IllegalStateException("Saved ticket changed; no adoption");
                    EmulatorDailyConfiguration config=EmulatorAndroidDailyConfigured.read(this);
                    if(!ticket.configSha.equals(EmulatorDailyRunner.digest(config.encode())))throw new IllegalStateException("Configuration changed");
                }
                EmulatorDailyService.start(this,ticket);
                return new View(ticket,true,"啟用請求已保存並要求啟動服務。實際運行與本輪結果請核對持續通知及工作紀錄。");
            },"啟用結果未確認。保存的請求與原工作保留，不會自動重送；請重新核對狀態。" )).show();
    }
    private void confirmStop(){
        if(busy||destroyed||shown==null)return;
        final EmulatorDailySchedule.Ticket requested=shown;
        new AlertDialog.Builder(this).setTitle("停止這份排程的新工作？")
            .setMessage("保存不可撤回的停止標記。已接收工作不會被中斷，須繼續核對其安全收尾。")
            .setNegativeButton("取消",null).setPositiveButton("停止新工作",(d,w)->task("正在保存停止請求…",()->{
                EmulatorAndroidDailySchedule.stop(this,requested); // exact held ticket; stale STOP cannot stop a successor
                startService(new Intent(this,EmulatorDailyService.class).setAction(EmulatorDailyService.STOP)
                        .putExtra(EmulatorDailyService.EXTRA,requested.encode()));
                View actual=readView();
                return new View(actual.ticket,actual.configured,"這份排程的停止標記已保存；既有工作仍需獨立安全確認。");
            },"停止結果未確認。原標記與工作保留；未強制中斷或刪除，請重新核對狀態。" )).show();
    }
    private interface Operation{View run()throws Exception;}
    private void task(String pending,Operation operation,String failure){
        if(busy||destroyed)return;busy=true;status.setText(pending);controls();
        worker.execute(()->{
            View result=null;try{result=operation.run();}catch(Exception unknown){ }
            final View actual=result;
            runOnUiThread(()->{
                if(destroyed)return;busy=false;
                if(actual==null){shown=null;configured=false;status.setText(failure);}
                else{shown=actual.ticket;configured=actual.configured;status.setText(actual.message+
                        (configured?"\n設定已核對；開始仍需原安全守門。":"\n設定未確認；不可啟用，可核對或停止已保存的排程。"));}
                controls();
            });
        });
    }
    private static final class View{
        final EmulatorDailySchedule.Ticket ticket;final boolean configured;final String message;
        View(EmulatorDailySchedule.Ticket ticket,boolean configured,String message){this.ticket=ticket;this.configured=configured;this.message=message;}
    }
    private void controls(){
        if(start==null)return;
        start.setText(shown==null?"啟用每日排程":"要求恢復已保存排程");
        start.setEnabled(!busy&&configured&&StepPermissions.notifications(this));
        stop.setEnabled(!busy&&shown!=null);refresh.setEnabled(!busy);acceptance.setEnabled(!busy);
        permission.setEnabled(!busy&&!StepPermissions.notifications(this));
    }
    private Button button(LinearLayout page,String label,Runnable action){
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinimumHeight(dp(48));
        AppButtonStyle.apply(b,AppButtonStyle.Kind.SECONDARY);b.setOnClickListener(v->action.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);p.bottomMargin=dp(4);page.addView(b,p);return b;
    }
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color("app_text_primary"));t.setPadding(0,dp(8),0,dp(8));return t;}
    private int color(String name){return getColor(getResources().getIdentifier(name,"color",getPackageName()));}
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    @Override protected void onDestroy(){destroyed=true;worker.shutdown();super.onDestroy();} // drain, never interrupt accepted effects
}

package dev.ody.pikmincontrol;

import android.app.*;
import android.content.Context;
import android.os.Bundle;
import android.widget.*;
import java.util.concurrent.*;

/** Original isolated UI only; opening, restoring or granting notification
 * permission NEVER starts/adopts a run. No binding/request supplied by Intent. */
public final class EmulatorDevelopmentBatchActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private TextView status;private Button start,stop;private boolean busy,destroyed;
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ControlSettings.applyAppearance(base));}
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);if(!"dev.ody.pikmincontrol.mumu".equals(getPackageName())){finish();return;}
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(18),dp(12),dp(18),dp(18));page.setBackgroundColor(color("app_background"));
        page.addView(text("受控三類收工批次",22));
        page.addView(text("僅開發測試，不啟用正式每日排程。一次確認建立全新請求，水果／花苗／禮物盒一併掃描；新任務共同上限15分鐘、最多三次補掃。",14));
        page.addView(text("使用已保存且符合目前APK、核心、PID及帳號的設定，沿用原定位、資源、逐ID派遣／領取與安全收尾。已授權任務可完成確認；結果不明不重送。已驗收自動步數、餵食、收瓣、育苗保持，不要求特殊沿路採果。",14));
        status=text(EmulatorAndroidDevelopmentBatch.message(),16);status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(status);
        start=button(page,"開始新的15分鐘三類批次",this::confirm);
        stop=button(page,"停止本次接收新任務",()->{
            try{EmulatorAndroidDevelopmentBatch.stop(EmulatorAndroidDevelopmentBatch.activeKey());status.setText("已要求停止接收新任務；原批次會繼續核對已接受任務及安全收尾。");}catch(Exception unknown){status.setText("沒有可核對的本次活動工作；未停止或接管其他批次。");}controls();
        });
        button(page,"唯讀更新本次狀態",()->{status.setText(EmulatorAndroidDevelopmentBatch.message());controls();});
        button(page,"返回",this::finish);ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);controls();
    }
    private void confirm(){
        if(busy||destroyed||EmulatorAndroidDevelopmentBatch.active())return;
        new AlertDialog.Builder(this).setTitle("開始一次受控三類批次？")
            .setMessage("會依原批次方式實際移動GPS並派遣／領取三類任務。使用真實現在時間及首次固定15分鐘上限，最多三次補掃；不買物品、不丟棄、放生、清資料或啟用正式排程。")
            .setNegativeButton("取消",null).setPositiveButton("開始本次測試",(d,w)->{
                if(busy||destroyed||EmulatorAndroidDevelopmentBatch.active())return;
                busy=true;status.setText("正在核對原設定與實際安全守門；尚未表示工作開始。");controls();
                worker.execute(()->{
                    try{EmulatorAndroidDevelopmentBatch.run(this,value->runOnUiThread(()->{if(!destroyed){status.setText(value);controls();}}));}catch(Exception unknown){ /* fixed-stage status already preserved; never retry */ }
                    runOnUiThread(()->{if(!destroyed){busy=false;status.setText(EmulatorAndroidDevelopmentBatch.message());controls();}});
                });
            }).show();
    }
    private void controls(){if(start==null)return;start.setEnabled(!busy&&!EmulatorAndroidDevelopmentBatch.active());stop.setEnabled(EmulatorAndroidDevelopmentBatch.active()&&!EmulatorAndroidDevelopmentBatch.activeKey().isEmpty());}
    private Button button(LinearLayout page,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinimumHeight(dp(48));AppButtonStyle.apply(b,AppButtonStyle.Kind.SECONDARY);b.setOnClickListener(v->action.run());LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(8);p.bottomMargin=dp(4);page.addView(b,p);return b;}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color("app_text_primary"));t.setPadding(0,dp(8),0,dp(8));return t;}
    private int color(String name){return getColor(getResources().getIdentifier(name,"color",getPackageName()));}
    private int dp(int value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}
    @Override protected void onResume(){super.onResume();if(status!=null){status.setText(EmulatorAndroidDevelopmentBatch.message());controls();}}
    @Override protected void onDestroy(){destroyed=true;worker.shutdown();super.onDestroy();} // drain, never interrupt accepted work
}

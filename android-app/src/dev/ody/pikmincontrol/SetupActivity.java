package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.util.concurrent.*;

/** One resumable page. The shared update flow owns all core transactions. */
public final class SetupActivity extends Activity {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private SetupSnapshot snapshot;
    private boolean busy,visible;
    private String message="正在檢查手機…";
    private LinearLayout content;
    private TextView progress;
    private String shownState="";
    private final Runnable poll=new Runnable(){public void run(){
        if(!visible)return;
        if(PhoneUpdateFlow.active(SetupActivity.this))PhoneUpdateFlow.requestAdvance(getApplicationContext());
        if(progress!=null)progress.setText(PhoneUpdateFlow.active(SetupActivity.this)||PhoneUpdateFlow.setup(SetupActivity.this)
            ?PhoneUpdateFlow.status(SetupActivity.this):message);
        String state=PhoneUpdateFlow.phase(SetupActivity.this)+"/"+PhoneUpdateFlow.action(SetupActivity.this);
        if(!busy&&!state.equals(shownState)){render();if(!PhoneUpdateFlow.active(SetupActivity.this))refresh();}
        handler.postDelayed(this,1000);
    }};
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ControlSettings.applyAppearance(base));}
    @Override public void onCreate(Bundle state){super.onCreate(state);SetupStore.save(SetupStore.prefs(this).edit().putBoolean("seen",true));render();}
    @Override public void onResume(){super.onResume();visible=true;refresh();handler.removeCallbacks(poll);handler.post(poll);}
    @Override public void onPause(){visible=false;handler.removeCallbacks(poll);super.onPause();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int color(String n){return getResources().getColor(getResources().getIdentifier(n,"color",getPackageName()),getTheme());}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color("app_text_primary"));t.setPadding(0,dp(8),0,dp(8));content.addView(t);return t;}
    private Button button(String value,Runnable action){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setMinHeight(dp(48));b.setTextColor(color("app_text_primary"));b.setBackgroundTintList(ColorStateList.valueOf(color("app_selected")));b.setOnClickListener(v->action.run());content.addView(b);return b;}
    private void choice(String label,boolean checked,android.widget.CompoundButton.OnCheckedChangeListener listener){CheckBox b=new CheckBox(this);b.setText(label);b.setTextColor(color("app_text_primary"));b.setChecked(checked);b.setOnCheckedChangeListener(listener);b.setEnabled(!PhoneUpdateFlow.active(this)&&!busy);content.addView(b);}
    private void render(){
        shownState=PhoneUpdateFlow.phase(this)+"/"+PhoneUpdateFlow.action(this);
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(20),dp(16),dp(20),dp(24));content.setBackgroundColor(color("app_background"));scroll.addView(content);
        text("設定這支手機",24);
        text("檢查與準備 → 安裝與重開 → 遊戲連線",15);
        progress=text(PhoneUpdateFlow.active(this)||PhoneUpdateFlow.setup(this)?PhoneUpdateFlow.status(this):message,16);
        boolean active=PhoneUpdateFlow.active(this),done=PhoneUpdateFlow.setup(this)&&"complete".equals(PhoneUpdateFlow.phase(this));
        if(!active&&!done){
            text("需要哪些工具？只準備環境，不會自動開始遊戲操作。",16);
            choice("GPS 路線與批次飛行",SetupStore.gps(this),(v,on)->{SetupStore.choose(this,on,SetupStore.copy(this));render();});
            choice("新機啟用探險座標擷取",SetupStore.copy(this),(v,on)->SetupStore.choose(this,SetupStore.gps(this),on));
            text("既有裝置保留原開關；座標擷取已包含在配對核心中。",14);
            choice("使用 Health Connect 步數工具",SetupStore.prefs(this).getBoolean("steps",false),(v,on)->{SetupStore.save(SetupStore.prefs(this).edit().putBoolean("steps",on));render();});
        }
        if(active){
            if(!PhoneUpdateFlow.setup(this))button("繼續目前更新",this::system);
            else {
                String action=PhoneUpdateFlow.action(this);
                if("reboot".equals(action))button("重新啟動手機",this::confirmReboot);
                else if("open-game".equals(action))button("開啟 Pikmin 完成連線驗證",this::openGame);
                else if("retry".equals(action))button("重新核對並繼續",()->task(()->{PhoneUpdateFlow.retry(this);return "正在重新核對…";}));
                else text("正在自動接續，進度已保存。",14);
                button("稍後繼續",this::finish);
                button("取消尚未送出的準備",()->task(()->{PhoneUpdateFlow.cancel(this);return PhoneUpdateFlow.status(this);}));
            }
        }else if(done){
            text("✓ 遊戲連線已確認",20);
            text("安裝與載入已確認；派遣、餵食、育苗等功能的實際效果仍需另行驗證。",14);
            button("完成設定",()->{SetupStore.save(SetupStore.prefs(this).edit().putBoolean("dismissed",true));finish();});
            button("重新檢查連線",this::refresh);
        }else{
            Button start=button("準備並連接遊戲",this::confirmSetup);
            start.setEnabled(!busy&&snapshot!=null&&snapshot.compatible);
            if(CoreUpdater.pending(this)||CoreInstallWizard.preparing(this)||SetupDeployment.pending(this)){
                start.setEnabled(false);button("完成已有核心交易",this::system);
            }
            button(snapshot!=null&&snapshot.root?"重新檢查":"檢查並授予 Root",this::refresh);
        }
        text("授權與工具",18);
        if(Build.VERSION.SDK_INT>=33&&!getSystemService(NotificationManager.class).areNotificationsEnabled())
            button("允許通知（顯示連線核對結果）",()->requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},811));
        if(!getPackageManager().canRequestPackageInstalls())button("允許此來源安裝更新",()->open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));
        if(SetupStore.gps(this)){
            if(!Settings.canDrawOverlays(this))button("允許工作懸浮窗（選用）",()->open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
            boolean installed=false;try{getPackageManager().getPackageInfo(JoystickAdapter.PACKAGE,0);installed=true;}catch(Exception missing){}
            text(installed?"JoyStick 已安裝；定位更新需獨立檢查。":"GPS 飛行另需安裝相容 JoyStick；本 App 不提供第三方 APK。",14);
            button("設定模擬定位工具",()->open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
            if(installed){button("開啟 JoyStick 設定位置",()->openPackage(JoystickAdapter.PACKAGE));button("檢查 GPS 定位更新",()->task(()->{String result=GpsModuleGateway.readiness(this);runOnUiThread(()->{if(!isDestroyed()&&!isFinishing())new AlertDialog.Builder(this).setTitle("GPS 定位檢查").setMessage(result).setPositiveButton("知道了",null).show();});return result;}));}
        }
        if(SetupStore.prefs(this).getBoolean("steps",false)){
            text("Health Connect："+(StepPermissions.healthRead(this)&&StepPermissions.healthWrite(this)?"已授予步數讀寫":"等待步數讀寫授權")+"；不會自動寫入步數。",14);
            button("設定步數權限",()->StepPermissions.open(this));
        }
        button("環境與進階工具",()->new AlertDialog.Builder(this).setTitle("目前環境").setMessage(snapshot==null?message:snapshot.summary())
            .setNegativeButton("關閉",null).setPositiveButton("核心維修",(d,w)->system()).show());
        if(!active&&!done)button("稍後設定",this::finish);
        setContentView(scroll);
    }
    private void confirmSetup(){
        if(busy||PhoneUpdateFlow.active(this))return;
        new AlertDialog.Builder(this).setTitle("準備並連接遊戲？")
            .setMessage("自動檢查配對核心；需要安裝時先安全暫停功能、關閉 Pikmin，備份後安裝。相同核心不重裝。保留設定與未確認紀錄，重開由你選擇。")
            .setNegativeButton("稍後",null).setPositiveButton("開始準備",(d,w)->task(()->{PhoneUpdateFlow.startSetup(this);PhoneUpdateFlow.requestAdvance(this);return "正在準備…";})).show();
    }
    private void confirmReboot(){new AlertDialog.Builder(this).setTitle("重新啟動手機？").setMessage("現在會中斷手機使用。重開後解鎖並開啟控制中心，進度會自動接續。")
        .setNegativeButton("稍後",null).setPositiveButton("立即重開",(d,w)->task(()->{PhoneUpdateFlow.requireReboot(this);CoreUpdater.root("reboot");return "等待手機重開";})).show();}
    private void openGame(){
        Intent game=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
        if(game==null){message="請先安裝 Pikmin";render();return;}
        try{PhoneConnectionService.start(this);}catch(RuntimeException e){message="背景核對未啟動，可返回此頁繼續";render();}
        open(game);
    }
    private interface Action{String run()throws Exception;}
    private void task(Action action){if(busy)return;busy=true;render();WORKER.execute(()->{String out;try{out=action.run();}catch(Exception e){out="尚未完成："+e.getMessage();}final String value=out;
        runOnUiThread(()->{busy=false;if(isDestroyed())return;message=value;render();});});}
    private void refresh(){if(busy)return;task(()->{
        snapshot=SetupDeployment.inspect(this);
        if(!PhoneUpdateFlow.active(this)&&!PhoneUpdateFlow.setup(this))return snapshot.compatible?"1/3 手機環境已確認，可以開始準備":"環境有待處理項目，請查看環境與進階工具";
        return PhoneUpdateFlow.status(this);
    });}
    private void openPackage(String pkg){Intent i=getPackageManager().getLaunchIntentForPackage(pkg);if(i!=null)open(i);else{message="尚未安裝相容套件";render();}}
    private void open(Intent i){try{startActivity(i);}catch(RuntimeException e){message="無法直接開啟，請從系統設定處理";render();}}
    private void system(){open(new Intent(this,MainActivity.class).putExtra("open_updates",true));}
}

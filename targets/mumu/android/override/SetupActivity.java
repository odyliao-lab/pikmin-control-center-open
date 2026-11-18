package dev.ody.pikmincontrol;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

/** Resumable local setup; progress never asserts gameplay acceptance. */
public final class SetupActivity extends Activity {
    private static final int IMPORT=810;
    private static final String[] TITLES={"選擇用途","檢查手機","準備套件","授權與GPS","重開並接續","驗證與完成"};
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private LinearLayout content;private TextView status;private Button next,previous;
    private SetupSnapshot snapshot;private boolean busy;private String message="正在檢查…";private int revision;
    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ControlSettings.applyAppearance(base));}
    @Override protected void onCreate(Bundle saved){super.onCreate(saved);SetupStore.save(SetupStore.prefs(this).edit().putBoolean("seen",true));render();}
    @Override protected void onResume(){super.onResume();refresh();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private int color(String name){return getResources().getColor(getResources().getIdentifier(name,"color",getPackageName()),getTheme());}
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color("app_text_primary"));t.setPadding(0,dp(8),0,dp(8));return t;}
    private Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setMinHeight(dp(48));b.setTextColor(color("app_text_primary"));b.setBackgroundTintList(ColorStateList.valueOf(color("app_selected")));b.setOnClickListener(v->action.run());content.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    private void render(){
        revision++;int step=SetupStore.step(this);
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(1);shell.setPadding(dp(18),dp(12),dp(18),dp(12));shell.setBackgroundColor(color("app_background"));
        shell.addView(text("🌱 裝置設定　"+(step+1)+" / 6",22));
        ScrollView scroll=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(1);content.setPadding(dp(18),dp(12),dp(18),dp(18));
        GradientDrawable bg=new GradientDrawable();bg.setColor(color("app_surface"));bg.setCornerRadius(dp(24));bg.setStroke(dp(1),color("app_outline"));content.setBackground(bg);
        scroll.addView(content);shell.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));content.addView(text(TITLES[step],24));
        status=text(message,14);content.addView(status);
        if(step==0){
            content.addView(text("只準備需要的元件，不自動開啟遊戲操作。",16));
            CheckBox gps=new CheckBox(this);gps.setText("完整功能（含批次／花田GPS）");gps.setChecked(SetupStore.gps(this));content.addView(gps);
            CheckBox copy=new CheckBox(this);copy.setText("另外啟用點探險複製座標");copy.setChecked(SetupStore.copy(this));content.addView(copy);
            gps.setOnCheckedChangeListener((v,on)->SetupStore.choose(this,on,copy.isChecked()));copy.setOnCheckedChangeListener((v,on)->SetupStore.choose(this,gps.isChecked(),on));
            content.addView(text("不勾完整功能：只準備領取／育苗。GPS Copy不是飛行引擎。",14));
        }else if(step==1){
            if(snapshot!=null)content.addView(text(snapshot.summary(),15));
            button("重新檢查／授予Root",this::refresh);
            content.addView(text("首版自動部署支援Android14、Magisk/Zygisk、arm64及Pikmin152。其他組合只提供檢查，不嘗試不相容注入。",14));
            button("安裝或開啟 Pikmin",()->openPackage("com.nianticlabs.pikmin",true));
            button("開啟系統App清單（查看Magisk）",()->open(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        }else if(step==2){
            boolean unified;
            try{unified=CoreUpdater.readPair(this,"core.properties").unified;}catch(Exception invalid){unified=true;}
            if(unified) {
                content.addView(text("使用本APK內附整合核心，已含GPS Copy；JoyStick仍須另外安裝。",16));
                content.addView(text("新機複製座標："+(SetupStore.copy(this)?"開啟":"關閉")+"；舊核心遷移則保留原設定。",14));
                button("遊戲App資訊（需要時強制停止）",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:com.nianticlabs.pikmin"))));
                button("安裝配對整合核心",()->new AlertDialog.Builder(this).setTitle("安裝整合核心？")
                    .setMessage("請停止自動化並關閉遊戲。舊核心或既有GPS模組會先備份，停用舊GPS後安裝單一核心。完成後須手動重開並開啟遊戲核對，不另外安裝GPS模組。")
                    .setNegativeButton("取消",null).setPositiveButton("確認安裝",(d,w)->task(()->{SetupDeployment.install(getApplicationContext());return SetupStore.result(this);})).show());
            }else {
            content.addView(text("私人部署包：核心1.4.25／1.4.26＋選用GPS Copy r13。既有模組不覆寫。",16));
            button("遊戲App資訊（需要時強制停止）",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:com.nianticlabs.pikmin"))));
            button("匯入部署包",()->{if(SetupDeployment.pending(this)){say("請先到重開步驟核對上次安裝");return;}openForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE),IMPORT);});
            button("檢查部署包",()->task(()->{SetupDeployment.load(this);return "部署包簽章、元件與有效期已確認；尚未安裝";}));
            button("安裝缺少的模組",()->new AlertDialog.Builder(this).setTitle("安裝缺少的模組？")
                    .setMessage("請先關閉遊戲及自動功能。\n依已校驗部署包安裝缺少的核心"+(SetupStore.copy(this)?"與GPS Copy r13":"")+"，已有模組不改動。安裝後須重開；安裝結果不明時不自動重試。")
                    .setNegativeButton("取消",null).setPositiveButton("確認安裝",(d,w)->task(()->{SetupDeployment.install(getApplicationContext());return SetupStore.result(this);})).show());
            content.addView(text("請先在Magisk啟用Zygisk。安裝時保持此頁；開始後不強制中斷，意外中斷請重開後核對。",14));
            }
            if(SetupStore.gps(this)){button("開啟已支援的 JoyStick",()->openPackage(JoystickAdapter.PACKAGE,false));
                content.addView(text("目前適配既有JoyStick 5.3.3套件。請從原合法安裝檔安裝；同名但不同套件不會被自動接管。未提供第三方APK下載。",14));}
        }else if(step==3){
            button("通知權限",()->{if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},811);else open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName()));});
            button("懸浮視窗權限",()->open(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
            button("允許此來源安裝更新",()->open(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))));
            if(SetupStore.gps(this)){
                button("模擬定位設定",()->open(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)));
                button("開啟JoyStick設定初始位置",()->openPackage(JoystickAdapter.PACKAGE,false));
                button("檢查GPS持續更新",()->task(()->GpsModuleGateway.readiness(this)));
                CheckBox boot=new CheckBox(this);boot.setText("首次解鎖後啟動JoyStick");boot.setChecked(ControlSettings.startJoystickOnBoot(this));content.addView(boot);
                boot.setOnCheckedChangeListener((v,on)->ControlSettings.setStartJoystickOnBoot(this,on));
                content.addView(text("請手動保存位置並啟動一次；精靈不會飛到舊手機的座標。權限返回後會重新檢查。",14));
            }
        }else if(step==4){
            content.addView(text(SetupStore.result(this),16));
            button("已重開，核對安裝",()->task(()->{
                if(CoreUpdater.readPair(this,"core.properties").unified){CoreUpdater.review(this);return CoreUpdater.inspect(this);}
                SetupDeployment.review(this);return SetupStore.result(this);}));
            button("系統核心狀態與回退",()->open(new Intent(this,MainActivity.class).putExtra("open_updates",true)));
            button("開啟系統電源選單說明",()->new AlertDialog.Builder(this).setTitle("請自行重開手機")
                    .setMessage("使用手機電源選單重新啟動；首次解鎖後回到控制中心的裝置設定。進度已保存，不會自動重裝。\n若無法開機，App無法自救，請使用該機型的Magisk救援方式；勿清空全部模組。")
                    .setPositiveButton("知道了",null).show());
        }else{
            boolean components=snapshot!=null&&snapshot.components(SetupStore.gps(this),SetupStore.copy(this))&&!SetupDeployment.pending(this);
            content.addView(text("部署元件（上次檢查）："+(components?"已確認":"仍有待處理項目")+"\n原生連線："+(snapshot!=null&&snapshot.currentRuntime(System.currentTimeMillis())?"同PID新鮮回報已確認":"待開遊戲核對")+"\n遊戲功能實測：待完成",18));
            content.addView(text("此頁不自動派遣、種花或拔苗。請先確認連線，再使用現有功能做單筆測試；沒有候選不是失敗。",14));
            button("開啟遊戲並登入",()->openPackage("com.nianticlabs.pikmin",false));button("重新核對",this::refresh);
            button("分享部署摘要",()->open(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT,"Control Center 裝置設定摘要\n"+(snapshot==null?"尚未取得環境資料":snapshot.summary())
                            +"\n"+SetupStore.result(this)+"\n功能實測待完成；摘要不含座標、帳號、任務與安裝識別碼。"),"選擇儲存或分享方式")));
            button("結束本次設定（保留待測狀態）",this::leave);
        }
        LinearLayout footer=new LinearLayout(this);
        previous=new Button(this);previous.setText("上一步");previous.setEnabled(step>0);previous.setOnClickListener(v->{SetupStore.step(this,step-1);render();});footer.addView(previous,new LinearLayout.LayoutParams(0,-2,1));
        next=new Button(this);next.setText(step==5?"稍後繼續":"下一步");next.setOnClickListener(v->{if(step==5)leave();else{SetupStore.step(this,step+1);render();}});footer.addView(next,new LinearLayout.LayoutParams(0,-2,1));shell.addView(footer);
        setContentView(shell);if(busy){next.setEnabled(false);previous.setEnabled(false);}
    }
    private interface Action {String run() throws Exception;}
    private void task(Action action){if(busy){say("正在處理，請稍候");return;}busy=true;render();
        WORKER.execute(()->{String result;try{result=action.run();}catch(Exception e){result="⚠ "+e.getMessage();}
            final String value=result;runOnUiThread(()->{busy=false;if(isDestroyed())return;message=value;render();});});}
    private void refresh(){if(busy)return;task(()->{SetupSnapshot s=SetupDeployment.inspect(this);snapshot=s;
        return (s.compatible?"環境檢查完成":"環境有待處理項目，請看步驟2")+"\n通知："+(((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).areNotificationsEnabled()?"已允許":"待授權")
                +" · 懸浮窗："+(Settings.canDrawOverlays(this)?"已允許":"選用／待授權");});}
    private void say(String s){message=s;if(status!=null)status.setText(s);}
    private void leave(){if(SetupDeployment.pending(this))moveTaskToBack(true);else finish();}
    @Override public void onBackPressed(){leave();}
    private void open(Intent i){try{startActivity(i);}catch(RuntimeException e){say("此手機無法直接開啟，請從系統設定手動處理");}}
    private void openForResult(Intent i,int code){try{startActivityForResult(i,code);}catch(RuntimeException e){say("找不到檔案選擇器，請安裝系統檔案管理工具");}}
    private void openPackage(String pkg,boolean store){Intent i=getPackageManager().getLaunchIntentForPackage(pkg);
        if(i!=null)open(i);else if(store)open(new Intent(Intent.ACTION_VIEW,Uri.parse("https://play.google.com/store/apps/details?id="+pkg)));else say("尚未安裝已支援套件，請先使用合法安裝檔安裝");}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==IMPORT&&result==RESULT_OK&&data!=null&&data.getData()!=null){Uri uri=data.getData();task(()->{
            byte[] bytes=SetupInput.read(()->getContentResolver().openInputStream(uri),SetupBundle.MAX,20000);
            SetupDeployment.importBundle(this,new ByteArrayInputStream(bytes));return SetupStore.result(this);});}}
}

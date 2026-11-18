package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.*;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** MuMu-only controlled acceptance UI. Import is an independently held input,
 * NOT persisted configuration, readiness, or permission to schedule gameplay.
 * No capture survives Activity destruction; no automatic HC write/replay. */
public final class EmulatorDailyAcceptanceActivity extends Activity {
    private static final int IMPORT=41;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private EmulatorDailyConfiguration proposed;
    private EmulatorDailyRouteLibrary library;
    private EmulatorAndroidDailyAcceptance observer;
    private EmulatorAndroidDailyAcceptance.Capture capture;
    private TextView status;
    private Spinner round;
    private Spinner city;
    private Button selectCity;
    private Button input,begin,steps,verify,readExisting,save,routeStart,routeStop,discard;
    private String requestedRoute;
    private boolean busy,destroyed,verified;
    private volatile String importFailure;

    @Override protected void attachBaseContext(Context base){super.attachBaseContext(ControlSettings.applyAppearance(base));}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        if(!"dev.ody.pikmincontrol.mumu".equals(getPackageName())){finish();return;}
        observer=new EmulatorAndroidDailyAcceptance(this);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18),dp(12),dp(18),dp(18));page.setBackgroundColor(color("app_background"));
        page.addView(text("每日作業驗收",22));
        page.addView(text("MuMu 測試入口 · 不會啟用每日排程",14));
        status=text("尚未匯入私人路線與獨立身分資料。",16);
        status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(status);
        page.addView(text("1. 匯入測試資料",18));
        page.addView(text("匯入及即時核對會將已開啟的遊戲帶回前景，保持回報；完成後由最近使用的App返回查看。不重啟遊戲、不自動載入核心。匯入不保存設定或開始移動。",14));
        input=button(page,"選取私人設定檔",()->{
            Intent request=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            try{startActivityForResult(request,IMPORT);}catch(Exception ignored){message("無法開啟檔案選擇器，請確認系統可用。",false);}
        });
        page.addView(text("三地路線包可選大台北／大阪／首爾；選擇只核對候選，不替換既有排程。單一路線檔仍可使用。",14));
        city=new Spinner(this);city.setMinimumHeight(dp(48));
        ArrayAdapter<String> cities=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"大台北","大阪","首爾"});
        city.setAdapter(cities);page.addView(city);
        selectCity=button(page,"核對選取地區的路線",()->{
            if(library==null||busy||capture!=null||requestedRoute!=null||verified)return;
            final EmulatorDailyConfiguration selected=library.selected(EmulatorDailyRouteLibrary.CITIES[city.getSelectedItemPosition()]);
            proposed=null;verified=false;
            task("正在核對選取地區與實際遊戲身分…",()->{
                EmulatorDailyRouteWork.foregroundExistingGame(this,selected.binding);
                EmulatorDailyRouteWork.requireIdentity(this,selected.binding);
                proposed=selected;
                return "選取地區已核對，尚未保存或開始工作。可讀回既有自動步數驗收，不要求特殊採果。";
            },"選取地區未核對；未替換既有設定、未開始工作。");
        });
        TextView label=text("路線測試編號（步數驗收不需要）",16);label.setId(android.view.View.generateViewId());page.addView(label);
        round=new Spinner(this);round.setId(android.view.View.generateViewId());label.setLabelFor(round.getId());round.setMinimumHeight(dp(48));
        String[] options=new String[14];for(int i=0;i<13;i++)options[i]="今天第 "+(i+1)+" 輪";
        options[13]="新測試（獨立任務，不重用舊紀錄）";
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);round.setAdapter(adapter);round.setSelection(13);page.addView(round);
        page.addView(text("首次驗收可明確啟動一轮城市路段測試：預估路程最多20分鐘，含準備及換瓣總上限25分鐘，不受正式時段限制。沿用19 km/h、原資源／定位／身分守門及安全收尾；不啟用每日排程。",14));
        routeStart=button(page,"啟動一輪受控種花路線",this::confirmRoute);
        routeStop=button(page,"安全停止本次受控路線",()->task("正在要求原路線安全停止…",()->{
            EmulatorDailyRouteWork.foregroundExistingGame(this,proposed.binding);
            FlowerFarmService.stopDailyRoute(this,proposed.binding,requestedRoute);
            return "已要求這份路線安全停止，須等原工作收尾。停止不等於輪替驗收完成。";
        },"停止結果未確認，原工作與日誌保留。未接管其他路線或重送開始。"));
        page.addView(text("2. 驗證持續自動步數採計",18));
        page.addView(text("不要求特殊沿路採果驗收。開始觀測後十分鐘內，讓既有自動步數服務完成至少兩筆寫入；安全停止步數服務後返回核對遊戲採計。不要求手動／補走；這項結果不代表路線或種花已驗收。",14));
        readExisting=button(page,"核對既有自動步數驗收",()->task("正在唯讀核對已保存的步數驗收…",()->{
            verified=false;
            EmulatorDailyRouteWork.foregroundExistingGame(this,proposed.binding);
            observer.require(proposed.binding);
            verified=true;
            return "既有自動步數原件已核對；沒有重寫步數或重跑路線。這不代表每日路線已驗收。";
        },"找不到符合目前身分的完整步數驗收；原件保持。請先檢查紀錄，不要重寫步數。"));
        begin=button(page,"開始前後觀測",this::beginCapture);
        steps=button(page,"開啟既有步數工具",()->{
            try{startActivity(new Intent(this,StepActivity.class));}catch(Exception ignored){message("步數工具不可用；未寫入任何步數。",false);}
        });
        verify=button(page,"核對遊戲採計並保存驗收",()->task("正在核對原始帳本與遊戲採計…",()->{
            EmulatorDailyRouteWork.foregroundExistingGame(this,proposed.binding);
            observer.finish(capture);
            verified=true;capture=null;
            return "驗收原件已保存並重讀確認。每日排程仍未啟用。";
        },()->"未完成驗收（階段："+observer.failureStage()+"）。請核對既有紀錄與遊戲採計；不要重寫步數。逾時或發布結果不明時保留原件待檢查。"));
        discard=button(page,"取消本次唯讀步數觀測",()->task("正在取消唯讀觀測…",()->{
            observer.abandon(capture);capture=null;verified=false;
            return "唯讀觀測已取消；步數紀錄、路線成果與已保存檔案保持。可重新觀測自動步數，不重跑路線。";
        },"觀測未確認取消。已嘗試發布的結果不能取消或重送。"));
        page.addView(text("3. 保存正式設定",18));
        page.addView(text("已核對的私人路線仍須即時通過核心、帳號、定位及資源檢查；步數驗收獨立，不是保存路線的前置。已有設定不覆寫，這一步不啟動每日工作。",14));
        save=button(page,"保存已核對路線設定",()->new AlertDialog.Builder(this).setTitle("保存每日作業設定？")
            .setMessage("將保存這份已核對的私人路線與身分資料。已有設定不會被取代，每日排程仍關閉。")
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->task("正在保存設定…",()->{
                EmulatorDailyRouteWork.foregroundExistingGame(this,proposed.binding);
                EmulatorAndroidDailyConfigured.configure(this,proposed);
                return "設定已保存。每日排程尚未啟用。";
            },"設定未確認保存。原有資料保留，請檢查後再操作；不會自動重送。" )).show());
        button(page,"返回",this::finish);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);setContentView(scroll);controls();
    }
    private String selectedRoute(){
        String suffix=round.getSelectedItemPosition()==13?java.util.UUID.randomUUID().toString().replace("-",""):Integer.toString(round.getSelectedItemPosition());
        return java.time.Instant.ofEpochMilli(System.currentTimeMillis()).atZone(EmulatorDailyPlan.ZONE).toLocalDate()+":development-route-"+suffix;
    }
    private void confirmRoute(){
        if(busy||destroyed||proposed==null||capture!=null||requestedRoute!=null)return;
        final EmulatorDailyConfiguration request=proposed;final String key=selectedRoute();
        new AlertDialog.Builder(this).setTitle("啟動一次受控路線？")
            .setMessage("使用這份已核對的私人路線：預估路程最多20分鐘，準備及換瓣在內總上限25分鐘。開始仍須原核心、帳號、定位及資源守門；不寫步數、不啟用每日排程。結果不明時不自動重送。舊15分鐘任務不延長。")
            .setNegativeButton("取消",null).setPositiveButton("開始測試",(d,w)->{
                if(busy||destroyed||proposed!=request||requestedRoute!=null||capture!=null)return;
                requestedRoute=key; // Retain before the request; no live-Activity retry after an unknown reply.
                task("正在要求受控路線…",()->{
                    EmulatorDailyRouteWork.foregroundExistingGame(this,request.binding);
                    FlowerFarmService.startDevelopmentRoute(this,request.route,request.binding,key);
                    return "已送出一次每日種花路線請求，尚未證明完成。這與自動步數驗收獨立，不要求特殊採果。";
                },"路線啟動結果未確認。未自動重送，請核對既有工作；保存的claim不能作完成證據。");
            }).show();
    }
    private void beginCapture(){
        final String key=requestedRoute==null?selectedRoute():requestedRoute;
        task("正在讀取自動步數前置帳本…",()->{
            EmulatorDailyRouteWork.foregroundExistingGame(this,proposed.binding);
            capture=observer.begin(proposed.binding,key);verified=false;
            return "前置觀測已完成。請在十分鐘內完成至少兩筆自動寫入，停止步數服務後返回核對。";
        },()->"無法開始觀測（階段："+observer.failureStage()+"）。請確認同一帳號／核心已載入且工作閒置。沒有寫入步數；已有驗收請先讀回。");
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT||result!=RESULT_OK||data==null||data.getData()==null||busy||capture!=null||requestedRoute!=null)return;
        final Uri uri=data.getData();
        proposed=null;library=null;verified=false; // Never silently fall back to a prior import.
        importFailure="私人檔案讀取或UTF-8檢查未通過。";
        task("正在核對私人設定檔…",()->{
            String raw;
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new IllegalStateException("Input unavailable");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){if(n==0)continue;if(bytes.size()+n>Math.max(EmulatorDailyConfiguration.MAX_BYTES,EmulatorDailyRouteLibrary.MAX_BYTES))throw new IllegalStateException("Input bounded");bytes.write(buffer,0,n);}
                byte[] original=bytes.toByteArray();raw=new String(original,StandardCharsets.UTF_8);
                if(!java.util.Arrays.equals(original,raw.getBytes(StandardCharsets.UTF_8)))throw new IllegalStateException("Exact UTF8 required");
            }
            importFailure="私人設定格式、雜湊或建立時間檢查未通過。";
            if(raw.startsWith(EmulatorDailyRouteLibrary.HEADER)){
                library=EmulatorDailyRouteLibrary.decode(raw,System.currentTimeMillis());
                return "三地候選已讀取。請選擇地區並核對；未保存、未啟動或替換排程。";
            }
            EmulatorDailyConfiguration candidate=EmulatorDailyConfiguration.decode(raw,System.currentTimeMillis());
            importFailure="配對遊戲前景準備未通過。";
            EmulatorDailyRouteWork.foregroundExistingGame(this,candidate.binding);
            EmulatorDailyRouteWork.requireIdentity(this,candidate.binding,stage->
                importFailure=stage.label+"檢查未通過。");
            proposed=candidate;verified=false;
            return "私人路線與身分已核對，尚未保存。已有步數驗收請讀回，不需重做採果。";
        },()->importFailure+"未保存資料、未開始工作。");
    }
    private interface Action {String run()throws Exception;}
    private void task(String pending,Action action,String failure){
        task(pending,action,()->failure);
    }
    private void task(String pending,Action action,java.util.function.Supplier<String> failure){
        if(busy||destroyed)return;busy=true;message(pending,true);controls();
        worker.execute(()->{
            String outcome;try{outcome=action.run();}catch(Exception error){
                // Private device diagnostics only. Preserve the original error
                // and refusal: no retry, new permission, effects or raw UI text.
                android.util.Log.w("PccDailyAcceptance","action="+pending,error);
                outcome=failure.get();
            }
            final String delivered=outcome;runOnUiThread(()->{if(destroyed)return;busy=false;message(delivered,false);controls();});
        });
    }
    private void message(String value,boolean pending){if(status!=null)status.setText(value);}
    private void controls(){
        AppButtonStyle.setEnabled(input,!busy&&capture==null&&requestedRoute==null);
        city.setEnabled(!busy&&library!=null&&capture==null&&requestedRoute==null);
        AppButtonStyle.setEnabled(selectCity,!busy&&library!=null&&capture==null&&requestedRoute==null);
        round.setEnabled(!busy&&capture==null&&requestedRoute==null);
        AppButtonStyle.setEnabled(routeStart,!busy&&proposed!=null&&capture==null&&requestedRoute==null);
        AppButtonStyle.setEnabled(routeStop,!busy&&proposed!=null&&capture==null&&requestedRoute!=null);
        AppButtonStyle.setEnabled(begin,!busy&&proposed!=null&&capture==null&&!verified);
        AppButtonStyle.setEnabled(readExisting,!busy&&proposed!=null&&capture==null);
        AppButtonStyle.setEnabled(steps,!busy&&capture!=null);
        AppButtonStyle.setEnabled(verify,!busy&&capture!=null);
        AppButtonStyle.setEnabled(discard,!busy&&capture!=null&&!verified);
        AppButtonStyle.setEnabled(save,!busy&&proposed!=null&&capture==null&&requestedRoute==null);
    }
    @Override protected void onDestroy(){
        destroyed=true;
        // Drain accepted reads/publication; do not interrupt, serialize capture,
        // restart any operation, or reconstruct a token in the next Activity.
        worker.shutdown();super.onDestroy();
    }
    private Button button(LinearLayout parent,String label,Runnable action){
        Button b=AppButtonStyle.create(this,label,AppButtonStyle.Kind.SECONDARY,v->action.run());b.setMinimumHeight(dp(48));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(6),0,dp(6));parent.addView(b,p);return b;
    }
    private TextView text(String value,int size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color("app_text_primary"));t.setPadding(0,dp(6),0,dp(6));return t;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private int color(String name){return getResources().getColor(getResources().getIdentifier(name,"color",getPackageName()),getTheme());}
}

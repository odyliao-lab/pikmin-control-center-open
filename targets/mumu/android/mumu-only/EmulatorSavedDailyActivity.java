package dev.ody.pikmincontrol;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.widget.*;

/** One normal daily entry; no route files, old acceptance ceremony or fake clock. */
public final class EmulatorSavedDailyActivity extends Activity {
    private TextView status;private boolean busy,resumed,confirming;
    private Button start,stop,test,diagnose,retire,rotation,overnight;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService work=java.util.concurrent.Executors.newSingleThreadExecutor();
    private final Runnable refresh=new Runnable(){public void run(){if(!resumed)return;if(!busy&&!confirming)readStatus(false);handler.postDelayed(this,5000);}};
    @Override public void onCreate(Bundle b){super.onCreate(b);EmulatorRuntime.require();
        LinearLayout page=new LinearLayout(this);page.setOrientation(1);int pad=(int)(20*getResources().getDisplayMetrics().density);page.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("每日自動排程");title.setTextSize(24);page.addView(title);
        Button home=new Button(this);home.setText("返回控制中心主頁");page.addView(home);home.setOnClickListener(v->{startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish();});
        TextView help=new TextView(this);help.setText("JoyStick預存路線 · 19km/h · 循環至起點\n07:00–15:00第一城、15:00–23:00第二城；23:00停止移動\n三日循環：台北＋首爾、首爾＋東京、東京＋台北\n一般種花輪替所有花種（含季節／特殊花），剩餘≤50瓣時換花，與GPS／步數獨立。\n附近精華採收、附近派遣／領獎、育苗拔補、全花種餵收換隊同時開啟。\n不購買、不丟棄，未知派遣／消耗不重送。");page.addView(help);
        start=new Button(this);start.setText("開始每日排程");page.addView(start);start.setOnClickListener(v->{run(()->{
            AutomationPreferences.choose(this,"nectar","on",()->{});AutomationPreferences.choose(this,"nearby","on",()->{});AutomationPreferences.choose(this,"kinds","7",()->{});AutomationPreferences.choose(this,"reward","all",()->{});AutomationPreferences.choose(this,"postcard","keep",()->{});
            PlanterSettings.choose(this,"auto");FeedingConfig feed=FeedingGateway.load(this).copy();feed.enabled=true;feed.collect=true;feed.rotate=true;FeedingCatalogue.select(feed,FeedingCatalogue.display(new FeedingSnapshot()).kinds);
            if(!getSharedPreferences("feeding",0).edit().putString("config",feed.encode()).putString("config_backup",feed.encode()).commit())throw new IllegalStateException("Cannot save all-flower choice");
            // This walking schedule does not opt into Health Connect writes.
            EmulatorSavedDailyStore prior=EmulatorSavedDailyStore.open(this);
            EmulatorSavedRoutePlan chosen=prior!=null&&prior.enabled()?prior.plan:new EmulatorSavedRoutePlan(EmulatorSavedRoutePlan.City.TAIPEI);
            EmulatorSavedDailyService.start(this,chosen,true);
        },true);});
        overnight=new Button(this);overnight.setText("以原通宵作息開始（06:00–翌04:00）");page.addView(overnight);
        overnight.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setTitle("恢復原通宵作息")
            .setMessage("請先停止每日排程，等待原工作正常收尾。新工作沿用原輪替起日與三條既有路線，06:00第一線、16:45第二線，03:30三類批次收工，04:00–06:00休息。\n未決不重送，安全條件不足會拒絕；不改獨立步數設定。")
            .setNegativeButton("取消",null).setPositiveButton("確認原作息",(d,w)->run(()->EmulatorSavedDailyService.startOvernight(this),true)).show());
        stop=new Button(this);stop.setText("停止每日排程");page.addView(stop);stop.setOnClickListener(v->run(()->EmulatorSavedDailyService.stop(this),false));
        Button returns=new Button(this);returns.setText("批次領回（最多10分鐘，不啟動排程）");page.addView(returns);
        returns.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setMessage("僅在每日與批次已停止、無未決時領回已完成的探險。保留明信片，不啟動GPS／種花／HC，不派遣或重送舊任務；最多10分鐘，成果依獨立收據核對。")
            .setNegativeButton("取消",null).setPositiveButton("確認批次領回",(d,w)->run(()->EmulatorStoppedBatchReturns.request(this),true)).show());
        Button stopReturns=new Button(this);stopReturns.setText("停止批次領回");page.addView(stopReturns);stopReturns.setOnClickListener(v->run(()->EmulatorStoppedBatchReturns.stop(this),true));
        rotation=new Button(this);rotation.setText("明日起將既有單城排程改為三城日間");page.addView(rotation);
        rotation.setOnClickListener(v->{if(busy||confirming)return;confirming=true;
            final java.time.LocalDate first=java.time.LocalDate.now(EmulatorDailyPlan.ZONE).plusDays(1);
            new android.app.AlertDialog.Builder(this).setTitle("每天兩條既有路線")
                .setMessage(first+" 起：第1天台北＋首爾，第2天首爾＋東京，第3天東京＋台北，持續循環。\n07:00第一條、15:00第二條；23:00停止移動。\n換線前等待種花正常結算、GPS暫停及背景工作排空；未決不重送，停止排程仍有效。")
                .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false)
                .setPositiveButton("啟用輪替",(d,w)->{confirming=false;run(()->EmulatorSavedDailyRuntime.scheduleRotation(this,first),false);}).show();
        });
        test=new Button(this);test.setText("補跑收工（最多15分鐘）");page.addView(test);test.setOnClickListener(v->new android.app.AlertDialog.Builder(this).setMessage("僅在每日排程活動中且無GPS／批次未決時補跑；03:15–06:00不開新補跑。\n先暫停路線、等待本輪種花正常結算，再重新掃描花苗／水果／禮物盒，最多三輪、15分鐘。\n舊UNKNOWN不重送、不補成功；種花未決會繼續等待。到期按原作息恢復，原收工缺漏紀錄仍保留。").setNegativeButton("取消",null).setPositiveButton("確認補跑",(d,w)->run(()->EmulatorSavedDailyRuntime.requestClosingTest(this),false)).show());
        Button core=new Button(this);core.setText("登入後載入核心（原安全流程）");page.addView(core);core.setOnClickListener(v->startActivity(new Intent(this,EmulatorBootstrapActivity.class)));
        Button terminalBatch=new Button(this);terminalBatch.setText("保全已停止批次（原程序仍在線）");page.addView(terminalBatch);
        terminalBatch.setOnClickListener(v->{if(busy||confirming)return;confirming=true;new android.app.AlertDialog.Builder(this)
            .setTitle("保留UNKNOWN並排除舊任務重送")
            .setMessage("先正常停止每日排程、種花與定位。僅接受同完整原程序、單一未決授權，並由原生歷史證明Start Task已完成、庫存仍未確認的情況。核對完整原件與30秒靜止後保存UNKNOWN及原任務排除，不建立成功摘要、不重送或關閉遊戲。缺證仍拒絕。")
            .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false)
            .setPositiveButton("核對並保全UNKNOWN",(d,w)->{confirming=false;Intent game=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);if(game!=null)startActivity(game.addFlags(0x10200000));run(()->{Thread.sleep(2000);String result;try{result=EmulatorSavedDailyRuntime.retireStoppedTerminalBatch(this);}catch(Exception e){EmulatorSavedDailyRuntime.retirementFailure(this,e);result="原件保留；尚未完成。"+EmulatorSavedDailyRuntime.retirementDiagnostic(this);}final String message=result;runOnUiThread(()->{if(!isDestroyed()){startActivity(new Intent(this,EmulatorSavedDailyActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));new android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("確定",null).show();}});},false);}).show();});
        Button oldBatch=new Button(this);oldBatch.setText("封存已失聯批次（保留UNKNOWN）");page.addView(oldBatch);
        oldBatch.setOnClickListener(v->{if(busy||confirming)return;confirming=true;new android.app.AlertDialog.Builder(this)
            .setTitle("保留未確認結果並恢復收尾")
            .setMessage("請先正常停止每日排程。會核對原帳號、舊批次程序已退出與目前沒有種花及其他在途工作；保留原件並持久排除舊任務重送，不把UNKNOWN算成功。完成後等待原每日收尾，再做既有批次診斷。")
            .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false)
            .setPositiveButton("核對並封存",(d,w)->{confirming=false;Intent game=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);if(game!=null)startActivity(game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                run(()->{String outcome;try{outcome=EmulatorSavedDailyRuntime.retireDeadBatch(this);}catch(Exception e){EmulatorSavedDailyRuntime.retirementFailure(this,e);outcome="封存未確認；原件保留。步驟："+EmulatorSavedDailyRuntime.retirementDiagnostic(this);}final String result=outcome;runOnUiThread(()->{if(!isDestroyed()){startActivity(new Intent(this,EmulatorSavedDailyActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));new android.app.AlertDialog.Builder(this).setMessage(result).setPositiveButton("確定",null).show();}});},false);
            }).show();});
        Button maintenance=new Button(this);maintenance.setText("維護停止逾期種花（保留UNKNOWN）");page.addView(maintenance);
        maintenance.setOnClickListener(v->{if(busy||confirming)return;confirming=true;new android.app.AlertDialog.Builder(this).setTitle("維護停止並保留未確認結果")
            .setMessage("僅處理每日排程已正常停止、定位與其他工作已收尾、原程序已不種花但結算逾期的情況。會先保存原件及雜湊，再關閉目前遊戲與收尾觀察服務。原遠端結果仍為UNKNOWN，不代表停止交易成功。\n重新登入、正常載入核心，再用「封存已失聯種花」完成原件封存；不重送舊動作。")
            .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false).setPositiveButton("確認維護停止",(d,w)->{
                confirming=false;Intent game=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);if(game!=null)startActivity(game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                run(()->{Thread.sleep(2000);String result;
                    try{result=EmulatorDeadPlantingRecovery.quiesceLive(this);}catch(Exception e){result="維護未確認；原件保留。檢查步驟："+EmulatorDeadPlantingRecovery.diagnostic()+"（"+e.getClass().getSimpleName()+"）";}
                    final String message=result;runOnUiThread(()->{if(isDestroyed())return;startActivity(new Intent(this,EmulatorSavedDailyActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));new android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("核心安全入口",(a,b1)->startActivity(new Intent(this,EmulatorBootstrapActivity.class))).setNegativeButton("保留現況",null).show();});},false);
            }).show();});
        diagnose=new Button(this);diagnose.setText("診斷與安全恢復");page.addView(diagnose);diagnose.setOnClickListener(v->diagnose());
        Button gpsRetire=new Button(this);gpsRetire.setText("封存已停止定位的未決意圖");page.addView(gpsRetire);
        gpsRetire.setOnClickListener(v->{if(busy||confirming)return;confirming=true;
            new android.app.AlertDialog.Builder(this).setTitle("保留未確認定位並結束舊排程")
                .setMessage("先正常停止每日排程與定位App。只有原定位程序確實已退出、遊戲未種花且其他工作均已收尾時才接受。\n只處理原換線，或繼承已停止定位後未確認的啟動意圖；保存原件與雜湊，結果仍為UNKNOWN。不重送、不停止遊戲。完成後等待排程停止，再按開始每日排程。")
                .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false)
                .setPositiveButton("核對並保留未確認結果",(d,w)->{confirming=false;
                    Intent game=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);if(game!=null)startActivity(game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    run(()->{Thread.sleep(1500);String outcome;
                        try{outcome=EmulatorSavedDailyRuntime.retireStoppedGps(this);}catch(Exception refused){outcome="未確認，原換線仍保留。請核對原定位已退出、排程已停止及遊戲未種花。（"+refused.getClass().getSimpleName()+"）";}
                        final String message=outcome;runOnUiThread(()->{if(!isDestroyed()){startActivity(new Intent(this,EmulatorSavedDailyActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));new android.app.AlertDialog.Builder(this).setMessage(message).setPositiveButton("確定",null).show();}});
                    },false);
                }).show();
        });
        retire=new Button(this);retire.setText("封存已失聯種花（保留UNKNOWN）");page.addView(retire);
        retire.setOnClickListener(v->{if(busy||confirming)return;confirming=true;new android.app.AlertDialog.Builder(this).setTitle("恢復被舊種花擋住的排程")
            .setMessage("僅處理已停止的每日排程。會核對舊遊戲程序已退出、目前遊戲確實未種花且無在途操作；保留完整原件及雜湊，歷史結果仍是UNKNOWN。\n核驗成功後會關閉遊戲。重新登入、正常載入核心，再按開始每日排程；不重送舊交易、不清資料。")
            .setNegativeButton("取消",(d,w)->confirming=false).setOnCancelListener(d->confirming=false).setPositiveButton("核對並封存",(d,w)->{
                confirming=false;
                if(EmulatorDeadPlantingRecovery.archiveNeedsGame(this)){
                    Intent game=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);
                    if(game!=null)startActivity(game.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                }
                run(()->{Thread.sleep(2000);String result;boolean recovered;
                    try{result=EmulatorDeadPlantingRecovery.recover(this);recovered=true;}
                    catch(Exception e){result="未確認；原件保留。檢查步驟："+EmulatorDeadPlantingRecovery.diagnostic()+"（"+e.getClass().getSimpleName()+"）";recovered=false;}
                    final String message=result;final boolean ok=recovered;
                    runOnUiThread(()->{if(isDestroyed())return;startActivity(new Intent(this,EmulatorSavedDailyActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        android.app.AlertDialog.Builder dialog=new android.app.AlertDialog.Builder(this).setMessage(message);
                        if(ok)dialog.setPositiveButton("核心安全入口",(a,b1)->startActivity(new Intent(this,EmulatorBootstrapActivity.class)));
                        else dialog.setPositiveButton("保留現況",null);dialog.show();});},false);
            }).show();});
        CheckBox retention=new CheckBox(this);retention.setText("每週日封存並清理7天前的舊自動截圖");
        retention.setChecked(getSharedPreferences("daily_retention",0).getBoolean("enabled",false));page.addView(retention);
        retention.setOnClickListener(v->{boolean chosen=retention.isChecked();if(!getSharedPreferences("daily_retention",0).edit().putBoolean("enabled",chosen).commit()){retention.setChecked(!chosen);Toast.makeText(this,"清理設定未保存",Toast.LENGTH_LONG).show();}});
        status=new TextView(this);status.setText("讀取本輪狀態中…");page.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(page);setContentView(scroll);readStatus(true);
    }
    private void controls(boolean enabled){start.setEnabled(enabled);stop.setEnabled(enabled);test.setEnabled(enabled);diagnose.setEnabled(enabled);retire.setEnabled(enabled);rotation.setEnabled(enabled);overnight.setEnabled(enabled);}
    private void diagnose(){
        if(busy)return;busy=true;controls(false);status.setText("唯讀診斷中；不切換任何功能…");
        work.execute(()->{
            String report=DiagnosticsExporter.freshNativeSummary(this)+"\n"+DiagnosticsExporter.savedDailySummary(this);
            runOnUiThread(()->{if(isDestroyed())return;busy=false;controls(true);status.setText(report);
                new android.app.AlertDialog.Builder(this).setTitle("診斷與安全恢復").setMessage(report+"\n恢復只喚醒已啟用的原每日服務；不解除使用者STOP、不重送UNKNOWN、不重載核心。育苗／批次未決仍走各自原診斷對帳入口。")
                    .setNegativeButton("保留現況",null)
                    .setNeutralButton("核心安全入口",(d,w)->startActivity(new Intent(this,EmulatorBootstrapActivity.class)))
                    .setPositiveButton("恢復原服務",(d,w)->run(()->{String result=EmulatorSavedDailyService.requestRecovery(this);runOnUiThread(()->{if(!isDestroyed())Toast.makeText(this,result,Toast.LENGTH_LONG).show();});},false)).show();
            });
        });
    }
    private void readStatus(boolean selection){busy=true;work.execute(()->{
        String text;boolean offer=false;
        try{EmulatorSavedDailyStore saved=EmulatorSavedDailyStore.open(this);text=EmulatorSavedDailyService.status(this,saved);
            offer=saved!=null&&saved.enabled()&&saved.get("rotation.plan","").isEmpty();
            text+="\n"+EmulatorStoppedBatchReturns.summary(this);
        }catch(Exception e){text="無法確認本輪狀態；原件保留（"+e.getClass().getSimpleName()+"）";}
        final String shown=text;final boolean showRotation=offer;
        runOnUiThread(()->{if(!isDestroyed()){status.setText(shown);rotation.setVisibility(showRotation?android.view.View.VISIBLE:android.view.View.GONE);busy=false;controls(true);}});
    });}
    private void run(EmulatorSavedDailyService.Work action,boolean openGame){if(busy)return;busy=true;controls(false);status.setText("處理中…");work.execute(()->{boolean ok=false;try{action.run();ok=true;}catch(Exception e){final String failure="未確認；原件保留（"+e.getClass().getSimpleName()+"）";runOnUiThread(()->Toast.makeText(this,failure,Toast.LENGTH_LONG).show());}final boolean game=ok&&openGame;runOnUiThread(()->{if(isDestroyed())return;busy=false;readStatus(false);if(game){Intent i=getPackageManager().getLaunchIntentForPackage(EmulatorSavedDailyRuntime.GAME);if(i!=null)startActivity(i.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));}});});}
    @Override public void onResume(){super.onResume();resumed=true;handler.post(refresh);}
    @Override public void onPause(){resumed=false;handler.removeCallbacks(refresh);super.onPause();}
    @Override public void onDestroy(){handler.removeCallbacks(refresh);work.shutdown();super.onDestroy();}
}

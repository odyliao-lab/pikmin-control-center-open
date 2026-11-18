package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;
import java.io.IOException;

/** Explicit post-login gate. A timeout/old PID never turns into automatic loading. */
public final class EmulatorBootstrapActivity extends Activity {
    private TextView status;
    private boolean busy;
    private static final String GAME="/data/user/0/com.nianticlabs.pikmin";
    @Override public void onCreate(Bundle b){
        super.onCreate(b);EmulatorRuntime.require();
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(20*getResources().getDisplayMetrics().density);box.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("MuMu 遊戲連線");title.setTextSize(24);box.addView(title);
        TextView help=new TextView(this);help.setText("1. 開啟 Pikmin，等到正常主畫面。\n2. 返回這裡，確認後載入核心；會自動回到遊戲。\n3. 稍後返回，檢查核心與 GPS Copy。\n\n每次遊戲重啟都需要再次確認；等待窗口為 180 秒。若已逾時，請重新啟動遊戲。載入不會自動派遣、領取或餵食；自動操作需另外驗收。");box.addView(help);
        Button open=new Button(this);open.setText("開啟 Pikmin");open.setOnClickListener(v->{
            Intent i=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");if(i!=null)startActivity(i);
        });box.addView(open);
        Button load=new Button(this);load.setText("我已看到主畫面，載入核心");load.setOnClickListener(v->new AlertDialog.Builder(this)
            .setMessage("確認 Pikmin 已完成登入，且所有自動操作已暫停？若還在載入畫面，請先取消。")
            .setNegativeButton("取消",null).setPositiveButton("確認載入",(d,w)->execute(true)).show());box.addView(load);
        Button refresh=new Button(this);refresh.setText("檢查核心與 GPS Copy");refresh.setOnClickListener(v->execute(false));box.addView(refresh);
        Button observe=new Button(this);observe.setText("啟動唯讀配對觀測（不執行遊戲操作）");observe.setOnClickListener(v->observe(true));box.addView(observe);
        Button stopObserve=new Button(this);stopObserve.setText("停止唯讀配對觀測");stopObserve.setOnClickListener(v->observe(false));box.addView(stopObserve);
        Button gps=new Button(this);gps.setText("GPS鎖與STATUS診斷（不移動定位）");gps.setOnClickListener(v->gps(false));box.addView(gps);
        Button stopGps=new Button(this);stopGps.setText("停止待核對GPS鎖程序");stopGps.setOnClickListener(v->gps(true));box.addView(stopGps);
        Button handoff=new Button(this);handoff.setText("定位交接 · 開始新一輪");handoff.setOnClickListener(v->confirmHandoff(true));box.addView(handoff);
        Button resume=new Button(this);resume.setText("定位交接 · 核對／續行原交易");resume.setOnClickListener(v->confirmHandoff(false));box.addView(resume);
        status=new TextView(this);status.setTextIsSelectable(true);box.addView(status);
        ScrollView scroll=new ScrollView(this);scroll.addView(box);setContentView(scroll);execute(false);
    }
    private void confirmHandoff(boolean newRound){
        if(busy)return;
        new AlertDialog.Builder(this).setMessage(newRound?
            "僅 MuMu：停止原 JoyStick 定位服務，確認退出後以保存位置重啟。遊戲不重開、資料不清除。需無路線及自動工作；這不代表每日作業已就緒。":
            "核對保留的原交易；未知停止／啟動不重送。已完成紀錄只顯示歷史，不新增一輪。")
            .setNegativeButton("取消",null).setPositiveButton("確認",(d,w)->handoff(newRound)).show();
    }
    private void handoff(boolean newRound){
        if(busy)return;busy=true;status.setText("定位交接核對中…");
        new Thread(()->{
            String message;
            try{
                String request=EmulatorGpsLifecycle.request(getApplicationContext(),newRound);
                message=EmulatorGpsLifecycle.run(getApplicationContext(),true,request);
            }catch(Exception error){message="定位交接未確認："+error.getMessage()+"\n保留原交易；請使用核對／續行，不要清除資料。";}
            final String result=message;runOnUiThread(()->{busy=false;status.setText(result);});
        },"mumu-gps-lifecycle").start();
    }
    private void gps(boolean stop){
        if(busy)return;busy=true;status.setText(stop?"核對原GPS程序退出…":"GPS鎖與STATUS診斷中…");
        new Thread(()->{
            String message;
            try {
                if(stop){EmulatorGpsFence.stop();message="原GPS鎖停止核對完成；不變更定位或遊戲開關。";}
                else message=EmulatorGpsDiagnostics.check(getApplicationContext());
            } catch(Exception error){message="GPS診斷未確認："+error.getMessage();}
            final String result=message;runOnUiThread(()->{busy=false;status.setText(result);});
        },"mumu-gps-diagnostics").start();
    }
    private void observe(boolean start){
        if(busy)return;busy=true;status.setText(start?"準備唯讀觀測…":"停止唯讀觀測…");
        new Thread(()->{
            String message;
            try{
                if(start){
                    String pid=CoreUpdater.root("pidof com.nianticlabs.pikmin").trim();
                    if(!pid.matches("[1-9][0-9]*"))throw new IOException("遊戲程序不唯一／未啟動");
                    EmulatorRootObserver.stageAndStart(getApplicationContext(),Integer.parseInt(pid),43200);
                    message="唯讀觀測已啟動；這不是核心／GPS工作權或每日作業已就緒。";
                }else{EmulatorRootObserver.stop();message="唯讀觀測已停止；未修改遊戲或自動化開關。";}
            }catch(Exception error){message="觀測未確認："+error.getMessage();}
            final String result=message;runOnUiThread(()->{busy=false;status.setText(result);});
        },"mumu-observer-control").start();
    }
    private void execute(boolean trigger){
        if(busy)return;busy=true;status.setText("核對中…");
        new Thread(()->{
            String message;boolean sent=false;
            try {
                CorePair pair=CoreUpdater.pair(this);CoreUpdater.requireFiles(this,pair);
                if(trigger){load();sent=true;}
                String pid=CoreUpdater.runtimePid(this,pair);
                message=pid.isEmpty()?"核心檔案配對通過；等待遊戲載入回報。若剛載入，請稍後再按檢查。"
                    :"核心與 GPS Copy：同程序、新鮮回報通過\nPID "+pid+" · 核心 "+pair.version+"\n這不是全部自動化功能的實機驗收。";
                if(!trigger && !pid.isEmpty())message+=EmulatorGameCounterDiagnostic.check(this,pid,pair);
            }catch(Exception e){message="未就緒："+e.getMessage();}
            String result=message;boolean returnToGame=sent;
            runOnUiThread(()->{busy=false;status.setText(result);
                if(returnToGame){Intent i=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");if(i!=null)startActivity(i);}
            });
        },"mumu-bootstrap").start();
    }
    private void load()throws Exception {
        String pid=CoreUpdater.root("pidof com.nianticlabs.pikmin");
        String report=CoreUpdater.root("test ! -L "+GAME+"/files/emulator_bridge_loader.tsv && cat "+GAME+"/files/emulator_bridge_loader.tsv");
        long token=EmulatorBootstrapProof.waitingToken(report,pid,System.currentTimeMillis());
        String trigger=GAME+"/files/emulator_bridge_trigger.tsv";
        // Re-read the exact report and PID inside the same command before the atomic trigger.
        CoreUpdater.root("set -e; test \"$(pidof com.nianticlabs.pikmin)\" = "+pid+"; "
            +"test ! -L "+GAME+"; test ! -L "+GAME+"/files; test ! -L "+trigger+"; test ! -L "+trigger+".tmp; "
            +"test \"$(cut -f3,8,10 "+GAME+"/files/emulator_bridge_loader.tsv)\" = \""+pid+"\twaiting\t"+token+"\"; "
            +"for f in /data/local/tmp/pikmin-dispatch-mode.txt /data/local/tmp/pikmin-return-mode.txt /data/local/tmp/pikmin-planter-mode.txt /data/local/tmp/pikmin-planting-mode.txt /data/local/tmp/pikmin-flower-farm-mode.txt; do test ! -L $f; test \"$(cat $f)\" = off; done; "
            +"f="+CoreUpdater.BASE+"/nectar_control_mode.txt; test ! -L $f; case $(cat $f) in off|diag) ;; *) exit 1;; esac; "
            +"test ! -L /data/local/tmp/pikmin-feeding-config.tsv; awk -F '\\t' 'NR==1 && $1==\"v3\" && $3==0 && $7==0 {ok=1} END {exit !ok}' /data/local/tmp/pikmin-feeding-config.tsv; "
            +"owner=$(stat -c %u "+GAME+"); test $owner -ge 10000; umask 077; printf 'v1\\t"+pid+"\\t"+token+"\\n' > "+trigger+".tmp; "
            +"chown $owner:$owner "+trigger+".tmp; chmod 0600 "+trigger+".tmp; mv "+trigger+".tmp "+trigger);
    }
}

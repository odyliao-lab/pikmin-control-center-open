package dev.ody.pikmincontrol;
import android.app.Activity;
import android.os.Build;
import android.widget.Switch;
import android.widget.TextView;
import android.view.View;
import java.util.concurrent.Executor;

/** Shared phone/MuMu UI binding; unsupported SDK does not issue an enable command. */
final class HcCompatCard {
    static Runnable bind(Activity c,Switch toggle,TextView status,View refresh,Executor worker){
        boolean allowed=HcCompatPolicy.allowedSdk(Build.VERSION.SDK_INT);
        boolean[] rendering={false},busy={false},known={false};
        Runnable read=()->{
            if(!allowed){toggle.setChecked(false);toggle.setEnabled(false);status.setText("Android 14 以上不需要 HC 相容處理");return;}
            if(busy[0])return;busy[0]=true;toggle.setEnabled(false);refresh.setEnabled(false);
            worker.execute(()->{
                try {HcCompatState state=HcCompatGateway.read(Build.VERSION.SDK_INT);c.runOnUiThread(()->{
                    known[0]=true;rendering[0]=true;toggle.setChecked(state.desired);rendering[0]=false;status.setText(state.message);
                });}catch(Exception error){c.runOnUiThread(()->{known[0]=false;status.setText("HC 相容讀取未完成："+error.getMessage());});}
                finally {c.runOnUiThread(()->{busy[0]=false;toggle.setEnabled(known[0]);refresh.setEnabled(true);});}
            });
        };
        toggle.setOnCheckedChangeListener((v,on)->{
            if(rendering[0])return;
            if(!allowed){rendering[0]=true;toggle.setChecked(false);rendering[0]=false;toggle.setEnabled(false);return;}
            if(busy[0])return;busy[0]=true;toggle.setEnabled(false);refresh.setEnabled(false);
            worker.execute(()->{
                try {HcCompatGateway.set(c,on,Build.VERSION.SDK_INT);c.runOnUiThread(()->{known[0]=true;status.setText(on?"已保存，等待遊戲確認 HC 相容狀態":"已關閉，保留遊戲原判定");});}
                catch(Exception error){c.runOnUiThread(()->{known[0]=false;rendering[0]=true;toggle.setChecked(!on);rendering[0]=false;status.setText("設定尚待核對："+error.getMessage()+"；請按重新核對");});}
                finally {c.runOnUiThread(()->{busy[0]=false;toggle.setEnabled(known[0]);refresh.setEnabled(true);});}
            });
        });
        refresh.setOnClickListener(v->read.run());read.run();return read;
    }
}

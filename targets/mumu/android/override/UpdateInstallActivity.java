package dev.ody.pikmincontrol;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.widget.Toast;

/** Explicit, non-exported PackageInstaller callback. No install operation is initiated here. */
public final class UpdateInstallActivity extends Activity {
    @Override protected void onCreate(Bundle state){super.onCreate(state);handle(getIntent());}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);handle(intent);}
    private void handle(Intent intent) {
        int expected=intent.getIntExtra("expected_session",-1);
        int received=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-2);
        if(expected<0 || expected!=received || expected!=UpdateInstaller.session(this)){finish();return;}
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null) {
                try{startActivity(confirmation);finish();return;}
                catch(Exception ignored){}
            }
            // Release protection only after Android confirms abandonment. On failure,
            // retain the pending session and recovery state for an explicit retry.
            String message="無法開啟 Android 安裝確認；原版本保留，請回更新頁重試。";
            try{UpdateInstaller.cancel(this);}
            catch(Exception failure){message="無法取消安裝或完成恢復；請回更新頁查看狀態並重試。";}
            UpdateStore.prefs(this).edit().putString("install_result",message).commit();
            Toast.makeText(this,message,Toast.LENGTH_LONG).show();
            openUpdates();return;
        }
        UpdateInstaller.clear(this,status==PackageInstaller.STATUS_SUCCESS?"更新安裝完成":"安裝已取消或失敗，原版本保留（"+status+"）");
        openUpdates();
    }
    private void openUpdates() {
        startActivity(new Intent(this,MainActivity.class).putExtra("open_updates",true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}

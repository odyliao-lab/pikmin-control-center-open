package dev.ody.pikmincontrol.hccompat;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;

public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this); root.setOrientation(1); root.setPadding(40,60,40,40);
        TextView info = new TextView(this); info.setTextSize(20);
        info.setText("HC 相容套件\n\n僅 Android 13／已核對的 Pikmin 156.1、157.0 原始套件。\n不改系統版本、不寫步數、不偽造權限。\n\n先在 LSPosed 啟用本模組，作用域只選 Pikmin。開關修改後重開遊戲。\n\n回退：先在遊戲選回使用手機追蹤，再關閉本補丁並重開遊戲。\n"); root.addView(info);
        Switch toggle = new Switch(this); toggle.setText("啟用 HC 相容套件（預設關閉）"); root.addView(toggle);
        TextView result = new TextView(this); root.addView(result);
        try {
            @SuppressWarnings("deprecation") SharedPreferences prefs = getSharedPreferences("compat", MODE_WORLD_READABLE);
            toggle.setChecked(prefs.getBoolean("enabled", false));
            toggle.setOnCheckedChangeListener((button, checked) -> {
                if (!prefs.edit().putBoolean("enabled", checked).commit()) { toggle.setEnabled(false); result.setText("設定未保存；不啟用補丁。"); }
                else result.setText("已保存，請重開遊戲；真正的 HC 授權仍須在系統完成。");
            });
        } catch (RuntimeException e) { toggle.setEnabled(false); result.setText("LSPosed 尚未啟用此模組；設定不可共享，不啟用補丁。"); }
        setContentView(root);
    }
}

package dev.ody.pikmincontrol;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Health Connect's required permission-rationale destination. It never writes records. */
public final class StepPermissionUsageActivity extends Activity {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(ControlSettings.applyAppearance(base));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        body.setPadding(p, p, p, p);
        body.setBackgroundColor(getResources().getColor(
                getResources().getIdentifier("app_background", "color", getPackageName()), getTheme()));
        body.addView(line("步數工具的健康資料用途", 22));
        body.addView(line("讀取今日步數，用來判斷是否已達使用者設定的目標。", 16));
        body.addView(line("寫入本 App 建立的人工／程式步數。這些資料不是感測器量測，也不是實際步行證明。", 16));
        body.addView(line("背景讀取只在使用者主動開啟自動寫入後使用；關閉後不再排程。", 16));
        body.addView(line(StepView.outcomeNote(), 14));
        setContentView(body);
    }

    private TextView line(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, 10, 0, 18);
        view.setTextColor(getResources().getColor(
                getResources().getIdentifier("app_text_primary", "color", getPackageName()), getTheme()));
        return view;
    }
}

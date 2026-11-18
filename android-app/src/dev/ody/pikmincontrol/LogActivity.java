package dev.ody.pikmincontrol;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only detailed native log pages. */
public final class LogActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView body;

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ControlSettings.applyAppearance(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        String page = getIntent().getStringExtra("page");
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(28));
        TextView title = new TextView(this);
        title.setText("nectar".equals(page) ? "大花精華收取紀錄" : "return".equals(page) ? "返程獎勵收取紀錄" : "系統健康詳情");
        title.setTextSize(24);
        root.addView(title);
        body = new TextView(this);
        body.setTextSize(13);
        body.setPadding(0, dp(16), 0, 0);
        body.setText("讀取中…");
        root.addView(body);
        scroll.addView(root);
        setContentView(scroll);
        worker.execute(() -> load(page));
    }

    private void load(String page) {
        try {
            String value;
            if ("nectar".equals(page)) value = NectarModuleGateway.recentClaims();
            else if ("return".equals(page)) value = ReturnRewardModuleGateway.recentDetailedEvents();
            else value = ReturnRewardModuleGateway.healthSummary();
            final String shown = value.isEmpty() ? "尚無紀錄。" : value;
            runOnUiThread(() -> body.setText(shown));
        } catch (Exception error) {
            runOnUiThread(() -> body.setText("無法讀取紀錄：" + error.getMessage()));
        }
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
}

package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Standalone experimental steps page. Not wired from MainActivity in round 1. */
public final class StepActivity extends Activity {
    private StepEngine engine;
    private TextView status;
    private TextView totals;
    private TextView chips;
    private TextView logs;
    private LinearLayout settingsBox;
    private LinearLayout advancedBox;
    private EditText targetField, startField, endField, rateField, intervalField;
    private EditText manualField, catchSteps;
    private TextView catchRateLabel;
    private int catchRateValue = StepSettings.DEFAULT_RATE;
    private Button writerToggle, settingsToggle, advancedToggle;
    private boolean settingsOpen;
    private boolean advancedOpen;
    private StepOperations ops;

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(ControlSettings.applyAppearance(base));
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        engine = StepHealthGateway.engine(this);
        ops = new StepOperations(engine);
        render();
        StepView view = ops.latest();
        if (!view.disclosed) disclose();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onDestroy() {
        if (ops != null) ops.close();
        super.onDestroy();
    }

    private void disclose() {
        new AlertDialog.Builder(this)
                .setTitle("實驗步數工具")
                .setMessage("這是人工／程式建立的非感測器步數，不是實際步行證明。\n\n"
                        + StepView.outcomeNote()
                        + "\n本頁不會宣稱遊戲已加步數。預設關閉。")
                .setNegativeButton("離開", (d, w) -> finish())
                .setPositiveButton("我了解", (d, w) -> {
                    ops.enqueue(new Runnable() {
                        public void run() {
                            engine.disclose();
                            runOnUiThread(new Runnable() { public void run() { refresh(); } });
                        }
                    });
                })
                .setCancelable(false)
                .show();
    }

    private void render() {
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(dp(18), dp(12), dp(18), dp(12));
        shell.setBackgroundColor(color("app_background"));
        TextView title = text("步數工具", 22);
        shell.addView(title);
        shell.addView(text("實驗功能 · 只寫本 App 的 Health Connect 步數", 14));
        chips = text("檢查中…", 14);
        shell.addView(chips);
        button(shell, "Health Connect 權限", () -> open(StepPermissions.manageHealthIntent(getPackageName())));
        button(shell, "應用程式設定", () -> open(settingsApp(this)));
        ScrollView scroll = new ScrollView(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("app_surface"));
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(1), color("app_outline"));
        card.setBackground(bg);
        status = text("已停止", 18);
        totals = text("今日總數：未知", 16);
        card.addView(status);
        card.addView(totals);
        card.addView(text(StepView.outcomeNote(), 13));
        writerToggle = primaryButton(card, "開始寫入", this::toggleWriter);
        settingsToggle = button(card, settingsOpen ? "收合設定" : "目標／時段／速率", () -> { settingsOpen = !settingsOpen; render(); refresh(); });
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setVisibility(settingsOpen ? View.VISIBLE : View.GONE);
        targetField = field("目標（1–1000000）");
        startField = field("開始（0–23）");
        endField = field("結束（0–23，相同=全天）");
        startField.setInputType(InputType.TYPE_CLASS_NUMBER);
        endField.setInputType(InputType.TYPE_CLASS_NUMBER);
        rateField = field("速率步/分（一般130）");
        intervalField = field("背景間隔秒（60–180）");
        settingsBox.addView(targetField);
        settingsBox.addView(startField);
        settingsBox.addView(endField);
        settingsBox.addView(rateField);
        settingsBox.addView(intervalField);
        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        button(presets, "一般", () -> applyPreset("normal"));
        button(presets, "快走", () -> applyPreset("fast"));
        button(presets, "奔跑", () -> applyPreset("run"));
        settingsBox.addView(presets);
        primaryButton(settingsBox, "保存設定", this::saveSettings);
        settingsBox.addView(text("極端速率只作醒目預覽，不是健康建議。", 13));
        card.addView(settingsBox);
        advancedToggle = button(card, advancedOpen ? "收合進階" : "手動／補走", () -> { advancedOpen = !advancedOpen; render(); refresh(); });
        advancedBox = new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(advancedOpen ? View.VISIBLE : View.GONE);
        advancedBox.addView(text("手動寫入", 16));
        advancedBox.addView(text("以最近一分鐘補寫，單次最多 350 步。", 13));
        manualField = field("現在補寫步數（1–350）");
        manualField.setInputType(InputType.TYPE_CLASS_NUMBER);
        advancedBox.addView(manualField);
        LinearLayout manualPresets = new LinearLayout(this);
        manualPresets.setOrientation(LinearLayout.HORIZONTAL);
        button(manualPresets, "100", () -> manualField.setText("100"));
        button(manualPresets, "200", () -> manualField.setText("200"));
        button(manualPresets, "350", () -> manualField.setText("350"));
        advancedBox.addView(manualPresets);
        primaryButton(advancedBox, "預覽並寫入", this::previewManual);
        advancedBox.addView(text("時段補走", 16));
        advancedBox.addView(text("只輸入總量；系統依速度自動往前回推。", 13));
        catchSteps = field("補走總步數");
        catchSteps.setInputType(InputType.TYPE_CLASS_NUMBER);
        advancedBox.addView(catchSteps);
        button(advancedBox, "補到今日目標", this::fillCatchUpToTarget);
        catchRateLabel = text("速度：一般（130 步／分）", 14);
        advancedBox.addView(catchRateLabel);
        LinearLayout catchRates = new LinearLayout(this);
        catchRates.setOrientation(LinearLayout.HORIZONTAL);
        button(catchRates, "一般 130", () -> setCatchRate(StepSettings.DEFAULT_RATE, "一般"));
        button(catchRates, "快走 200", () -> setCatchRate(StepSettings.FAST_RATE, "快走"));
        button(catchRates, "奔跑 350", () -> setCatchRate(StepSettings.RUN_RATE, "奔跑"));
        advancedBox.addView(catchRates);
        primaryButton(advancedBox, "預覽時段補走", this::previewCatchUp);
        advancedBox.addView(text("每分鐘最多 350 步；不寫未來、不跨午夜、不重疊本 App 已寫區間。", 13));
        card.addView(advancedBox);
        logs = text("尚無工作紀錄。", 13);
        card.addView(text("工作紀錄", 16));
        card.addView(logs);
        scroll.addView(card);
        shell.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(shell);
    }

    private void refresh() {
        StepView view = engine.snapshot();
        chips.setText(chipText());
        status.setText(StepUiAdapter.statusLine(view) + (view.desiredRunning
                ? " · 背景排程已啟用"
                : " · 背景排程未啟用"));
        totals.setText("今日全來源：" + StepView.totalText(view.allSourcesToday)
                + "\n本 App 今日：" + StepView.totalText(view.thisAppToday)
                + "\n本次確認：" + view.sessionConfirmed
                + "\n目標：" + view.target
                + (view.extremeRate ? "\n極端速率，請先看預覽。" : ""));
        if (writerToggle != null)
            writerToggle.setText(StepView.STORE_ERROR.equals(view.phase) ? "重試停止／核對儲存"
                    : view.desiredRunning ? "停止寫入" : "開始寫入");
        if (writerToggle != null) {
            AppButtonStyle.apply(writerToggle, view.desiredRunning || StepView.STORE_ERROR.equals(view.phase)
                    ? AppButtonStyle.Kind.DANGER : AppButtonStyle.Kind.PRIMARY);
            AppButtonStyle.setEnabled(writerToggle, true);
        }
        boolean editable = !view.desiredRunning && !StepUiAdapter.writesDisabled(view);
        if (settingsToggle != null) AppButtonStyle.setEnabled(settingsToggle, editable);
        if (advancedToggle != null) AppButtonStyle.setEnabled(advancedToggle, editable);
        setEnabledTree(settingsBox, editable);
        setEnabledTree(advancedBox, editable);
        logs.setText(logText(view.logs));
        if (StepUiAdapter.writesDisabled(view)) {
            // The stop/recovery action must stay available even when new writes are prohibited.
        } else if (targetField.getText().length() == 0) {
            StepSettings settings = engine.safeSettings();
            if (settings != null) {
                targetField.setText(Integer.toString(settings.target));
                startField.setText(StepPanelModel.hourText(settings.windowStartMin));
                endField.setText(StepPanelModel.hourText(settings.windowEndMin));
                rateField.setText(Integer.toString(settings.ratePerMinute));
                intervalField.setText(Long.toString(settings.intervalMs / 1000L));
            }
        }
    }

    private String chipText() {
        boolean hc = Build.VERSION.SDK_INT >= 34 && StepHealthGateway.create(this).available();
        boolean rw = StepPermissions.healthRead(this) && StepPermissions.healthWrite(this);
        boolean bg = StepPermissions.healthBackground(this);
        return "Health Connect：" + (Build.VERSION.SDK_INT < 34 ? "不支援" : hc ? "可用" : "未知")
                + "　權限：" + (rw ? "讀寫已授" : "待授權")
                + "\n背景讀取：" + (bg ? "已授權" : "待授權")
                + " · " + (StepService.isRunning() ? "前景服務約每分鐘執行。" : "備援排程可能延後執行。");
    }

    private void toggleWriter() {
        StepView latest = engine.snapshot();
        if (StepView.STORE_ERROR.equals(latest.phase) || latest.desiredRunning) stopWriter();
        else startWriter();
    }

    private void applyPreset(String preset) {
        try {
            int start = StepPanelModel.hour(startField.getText().toString());
            int end = StepPanelModel.hour(endField.getText().toString());
            long interval = Long.parseLong(intervalField.getText().toString().trim()) * 1000L;
            if (interval < StepSyncJob.MIN_DELAY_MS) throw new IllegalArgumentException("背景間隔最短 60 秒");
            int rate = Integer.parseInt(rateField.getText().toString().trim());
            StepSettings settings = StepSettings.parse(Integer.parseInt(targetField.getText().toString().trim()),
                    start * 60, end * 60, rate, preset, interval);
            rateField.setText(Integer.toString(settings.ratePerMinute));
            StepView view = engine.configure(settings);
            toast(view.detail + " 預設：" + preset + " " + settings.ratePerMinute + " 步/分");
        } catch (RuntimeException error) {
            toast(error.getMessage() == null ? "設定無效" : error.getMessage());
        }
        refresh();
    }

    private void startWriter() {
        StepSettings current = engine.safeSettings();
        if (current == null || current.intervalMs < StepSyncJob.MIN_DELAY_MS) {
            toast("背景排程間隔最短 60 秒，請先調整設定。");
            return;
        }
        if (Build.VERSION.SDK_INT >= 34 && (!StepPermissions.healthRead(this) || !StepPermissions.healthWrite(this)
                || !StepPermissions.healthBackground(this))) {
            try { startActivity(StepPermissions.manageHealthIntent(getPackageName())); }
            catch (RuntimeException error) { requestPermissions(StepPermissions.requested(), 162); }
            return;
        }
        ops.enqueue("start", new Runnable() {
            public void run() {
                StepView attempted = engine.start();
                boolean foreground = attempted.desiredRunning && StepWriterControl.mayLaunchService(attempted)
                        && StepService.start(StepActivity.this, engine.commandEpoch(), StepWriterControl.nextRunToken());
                boolean scheduled = attempted.desiredRunning
                        && StepSyncJob.scheduleRecovery(StepActivity.this, engine.commandEpoch());
                if (attempted.desiredRunning && !foreground && !scheduled) attempted = engine.stop();
                final StepView view = attempted;
                runOnUiThread(new Runnable() {
                    public void run() {
                        if (StepView.REJECTED.equals(view.phase) || StepView.UNSUPPORTED.equals(view.phase)
                                || StepView.WAITING_PERMISSION.equals(view.phase) || StepView.STORE_ERROR.equals(view.phase)
                                || StepView.STOPPED.equals(view.phase))
                            toast(view.detail);
                        refresh();
                    }
                });
            }
        });
    }

    private void stopWriter() {
        StepView view = engine.stop();
        StepService.signalStop(this);
        StepSyncJob.cancel(this);
        if (StepView.STORE_ERROR.equals(view.phase)) toast(view.detail);
        refresh();
    }

    private void saveSettings() {
        try {
            int start = StepPanelModel.hour(startField.getText().toString());
            int end = StepPanelModel.hour(endField.getText().toString());
            long interval = Long.parseLong(intervalField.getText().toString().trim()) * 1000L;
            if (interval < StepSyncJob.MIN_DELAY_MS) throw new IllegalArgumentException("背景間隔最短 60 秒");
            StepSettings settings = StepSettings.parse(Integer.parseInt(targetField.getText().toString().trim()),
                    start * 60, end * 60,
                    Integer.parseInt(rateField.getText().toString().trim()), "custom", interval);
            StepView view = engine.configure(settings);
            toast(view.detail);
        } catch (RuntimeException error) {
            toast(error.getMessage() == null ? "設定無效" : error.getMessage());
        }
        refresh();
    }

    private void previewManual() {
        try {
            final long count = Long.parseLong(manualField.getText().toString().trim());
            final StepView preview = engine.previewManual(count);
            if (StepView.REJECTED.equals(preview.phase)) { toast(preview.detail); return; }
            new AlertDialog.Builder(this).setTitle("確認手動寫入？")
                    .setMessage(preview.detail + "\n最近一分鐘 · " + count + " 步\n" + preview.previewWarning)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("寫入", (d, w) -> ops.enqueue(new Runnable() {
                        public void run() {
                            final StepView attempted = engine.confirmManual(count, preview.previewId);
                            runOnUiThread(new Runnable() { public void run() { toast(attempted.detail); refresh(); } });
                        }
                    }))
                    .show();
        } catch (RuntimeException error) { toast(error.getMessage() == null ? "請輸入 1–350" : error.getMessage()); }
    }

    private void previewCatchUp() {
        try {
            StepZone zone = new StepSystemZone();
            long now = System.currentTimeMillis();
            int[] ymd = StepCatchUpPlanner.localYmd(zone, now);
            final StepCatchUpRequest request = new StepCatchUpRequest("recent",
                    Long.parseLong(catchSteps.getText().toString().trim()),
                    catchRateValue, ymd[0], ymd[1], ymd[2], 0, 0, 0, 0,
                    null, null, zone.clockEpoch(now));
            final StepView preview = engine.previewCatchUp(request);
            if (StepView.REJECTED.equals(preview.phase)) { toast(preview.detail); return; }
            new AlertDialog.Builder(this).setTitle("確認補走？")
                    .setMessage(preview.detail + "\n" + preview.previewWarning + "\n"
                            + catchPreviewTimes(preview) + "\n共 " + preview.previewPlans.size() + " 筆。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("執行", (d, w) -> ops.enqueue(new Runnable() {
                        public void run() {
                            final StepView attempted = engine.confirmCatchUp(request, preview.previewId);
                            runOnUiThread(new Runnable() { public void run() { toast(attempted.detail); refresh(); } });
                        }
                    }))
                    .show();
        } catch (RuntimeException error) { toast(error.getMessage() == null ? "補走資料無效" : error.getMessage()); }
    }

    private void fillCatchUpToTarget() {
        StepView view = engine.snapshot();
        if (view.allSourcesToday == null) {
            toast("尚未讀取今日總數，請先更新後再試。");
            return;
        }
        long remaining = Math.max(0L, (long) view.target - view.allSourcesToday.longValue());
        if (remaining == 0L) {
            toast("今日已達目標。");
            return;
        }
        catchSteps.setText(Long.toString(remaining));
    }

    private void setCatchRate(int rate, String label) {
        catchRateValue = rate;
        if (catchRateLabel != null) catchRateLabel.setText("速度：" + label + "（" + rate + " 步／分）");
    }

    private String logText(List<StepLogEntry> entries) {
        if (entries == null || entries.isEmpty()) return "尚無工作紀錄。";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            StepLogEntry e = entries.get(i);
            out.append(e.atMs).append(" · ").append(e.count).append(" 步 · ")
                    .append(StepView.sourceLabel(e.source)).append(" · ").append(e.result).append('\n');
        }
        return out.toString();
    }

    private void open(Intent intent) {
        try { startActivity(intent); } catch (RuntimeException error) { toast("打不開系統設定"); }
    }

    private void toast(String message) {
        status.setText(message);
    }

    private EditText field(String hint) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setTextColor(color("app_text_primary"));
        field.setHintTextColor(color("app_text_secondary"));
        return field;
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        return button(parent, label, AppButtonStyle.Kind.SECONDARY, action);
    }

    private Button primaryButton(LinearLayout parent, String label, Runnable action) {
        return button(parent, label, AppButtonStyle.Kind.PRIMARY, action);
    }

    private Button button(LinearLayout parent, String label, AppButtonStyle.Kind kind, Runnable action) {
        Button button = AppButtonStyle.create(this, label, kind, v -> action.run());
        boolean horizontal = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(horizontal ? 0 : -1, -2, horizontal ? 1 : 0);
        if (horizontal) params.setMargins(dp(4), dp(2), dp(4), dp(2));
        else params.setMargins(0, dp(4), 0, 0);
        parent.addView(button, params);
        return button;
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color("app_text_primary"));
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private void setEnabledTree(View view, boolean enabled) {
        if (view == null) return;
        if (view instanceof Button) AppButtonStyle.setEnabled((Button) view, enabled);
        else view.setEnabled(enabled);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) setEnabledTree(group.getChildAt(i), enabled);
        }
    }

    private String catchPreviewTimes(StepView preview) {
        if (preview.previewPlans == null || preview.previewPlans.isEmpty()) return "無筆數";
        StepZone zone = new StepSystemZone();
        StepPlan first = preview.previewPlans.get(0);
        StepPlan last = preview.previewPlans.get(preview.previewPlans.size() - 1);
        return "自動回推 " + hhmm(zone.minuteOfDay(first.startMs)) + "–"
                + hhmm(zone.minuteOfDay(last.endMs)) + " · " + preview.previewPlans.size() + " 筆";
    }

    private static String hhmm(int minute) {
        int h = minute / 60, m = minute % 60;
        return (h < 10 ? "0" : "") + h + ":" + (m < 10 ? "0" : "") + m;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private int color(String name) { return getResources().getColor(getResources().getIdentifier(name, "color", getPackageName()), getTheme()); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    static Intent settingsNotifications(Context context) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
    }

    static Intent settingsBattery(Context context) {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
    }

    static Intent settingsApp(Context context) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.getPackageName()));
    }
}

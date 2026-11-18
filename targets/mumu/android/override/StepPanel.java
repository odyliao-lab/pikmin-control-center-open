package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Main-tab UI. Health I/O stays in the engine; explicit START launches the minute writer service. */
final class StepPanel {
    private final Activity activity;
    private final LinearLayout root;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private StepEngine engine;
    private StepSettings settings;
    private TextView phase, total, detail, configuration, records, environment, feedback;
    private Button configure, reload, writer, manual, catchUp, permissions;
    private AlertDialog dialog;
    private boolean closed, loading;

    StepPanel(Activity activity, LinearLayout parent) {
        this.activity = activity;
        root = parent;
        LinearLayout hero = card();
        hero.addView(text("今日步數", 14, false));
        total = text("尚未讀取", 32, true);
        hero.addView(total);
        detail = text("Health Connect 全來源 · 本機保存值", 12, false);
        hero.addView(detail);
        phase = text("正在讀取本機設定…", 14, false);
        hero.addView(phase);

        LinearLayout setup = card();
        setup.addView(text("自動寫入", 18, true));
        setup.addView(text(StepPanelModel.GATE, 13, false));
        configuration = text("正在讀取設定…", 15, false);
        setup.addView(configuration);
        configure = button(setup, "調整目標與時段", this::editSettings);
        AppButtonStyle.setEnabled(configure, false);
        writer = primaryButton(setup, "開始自動寫入", this::toggleWriter);
        AppButtonStyle.setEnabled(writer, false);
        feedback = text("設定可先儲存，不會因此開始寫入。", 12, false);
        setup.addView(feedback);

        LinearLayout tools = card();
        tools.addView(text("手動與補走", 18, true));
        tools.addView(text("先預覽，再由使用者確認寫入。", 13, false));
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        manual = button(actions, "手動寫入", this::openAdvanced);
        catchUp = button(actions, "時段補走", this::openAdvanced);
        tools.addView(actions);

        LinearLayout envBody = column();
        environment = text("", 13, false);
        envBody.addView(environment);
        envBody.addView(text("自動寫入需要步數讀寫與背景健康資料權限；不使用感測器或 root。Android 13 暫不支援。", 13, false));
        permissions = button(envBody, "開啟 Health Connect 權限", this::openHealthPermissions);
        button(envBody, "背景執行設定", this::openBackgroundSettings);
        disclosure("環境與權限", envBody);
        LinearLayout recordBody = column();
        records = text("正在讀取紀錄…", 13, false);
        recordBody.addView(records);
        reload = button(recordBody, "更新本機摘要", this::refresh);
        disclosure("工作紀錄", recordBody);
        LinearLayout noteBody = column();
        noteBody.addView(text("這是人工／程式建立的非感測器步數，不是實際步行證明。", 13, false));
        noteBody.addView(text(StepView.outcomeNote(), 13, false));
        noteBody.addView(text("只有工作紀錄顯示成功時，才代表本 App 已向 Health Connect 寫入；仍不代表 Pikmin 已採計。", 13, false));
        disclosure("使用說明", noteBody);
    }

    void refresh() {
        if (closed || loading) return;
        loading = true;
        AppButtonStyle.setEnabled(reload, false);
        worker.execute(() -> {
            try {
                if (engine == null) engine = StepHealthGateway.engine(activity.getApplicationContext());
                StepView view = StepPermissions.healthRead(activity) ? engine.refreshTotals() : engine.snapshot();
                StepSettings readSettings = engine.safeSettings();
                activity.runOnUiThread(() -> {
                    if (closed) return;
                    settings = readSettings;
                    render(view);
                    loading = false;
                    AppButtonStyle.setEnabled(reload, true);
                });
            } catch (RuntimeException e) { failRead(); }
        });
    }

    private void failRead() {
        activity.runOnUiThread(() -> {
            if (closed) return;
            loading = false;
            settings = null;
            AppButtonStyle.setEnabled(configure, false);
            AppButtonStyle.setEnabled(writer, false);
            AppButtonStyle.setEnabled(manual, false);
            AppButtonStyle.setEnabled(catchUp, false);
            AppButtonStyle.setEnabled(reload, true);
            phase.setText("本機資料未能讀取");
            feedback.setText("未改動設定，請重試更新本機摘要。");
        });
    }

    private void render(StepView view) {
        total.setText(StepPanelModel.total(view.allSourcesToday));
        detail.setText("全來源保存值，非即時讀取\n本 App：" + StepPanelModel.total(view.thisAppToday)
                + " 步 · 本次確認：" + view.sessionConfirmed + " 步");
        phase.setText("本機狀態 · " + StepView.phaseLabel(view.phase));
        configuration.setText(StepPanelModel.summary(settings));
        AppButtonStyle.setEnabled(configure, settings != null && !StepUiAdapter.writesDisabled(view) && !view.desiredRunning);
        boolean supported = Build.VERSION.SDK_INT >= 34;
        AppButtonStyle.apply(writer, view.desiredRunning ? AppButtonStyle.Kind.DANGER : AppButtonStyle.Kind.PRIMARY);
        AppButtonStyle.setEnabled(writer, supported && !StepUiAdapter.writesDisabled(view));
        writer.setText(view.desiredRunning ? "停止自動寫入" : "開始自動寫入");
        AppButtonStyle.setEnabled(manual, supported && !StepUiAdapter.writesDisabled(view) && !view.desiredRunning);
        AppButtonStyle.setEnabled(catchUp, supported && !StepUiAdapter.writesDisabled(view) && !view.desiredRunning);
        if (StepUiAdapter.writesDisabled(view)) feedback.setText("儲存異常，設定未覆寫；保留原始資料待核對。");
        environment.setText("系統：Android " + Build.VERSION.RELEASE
                + (Build.VERSION.SDK_INT < 34 ? "（暫不支援）" : "（版本符合，Health Connect 待實測）")
                + "\n健康讀取：" + (StepPermissions.healthRead(activity) ? "已授權" : "未授權")
                + "\n健康寫入：" + (StepPermissions.healthWrite(activity) ? "已授權" : "未授權")
                + "\n背景讀取：" + (StepPermissions.healthBackground(activity) ? "已授權" : "未授權")
                + "\n自動寫入：" + (view.desiredRunning
                ? (StepService.isRunning() ? "前景服務執行中（約每分鐘）" : "備援排程執行中（可能延後）")
                : "未啟用（按「開始自動寫入」開啟）"));
        StringBuilder log = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("MM/dd HH:mm", Locale.TAIWAN);
        // StepState.addLog prepends. Preserve latest-first order, at most twenty rows.
        for (int i = 0; i < view.logs.size() && i < 20; i++) {
            StepLogEntry e = view.logs.get(i);
            log.append(time.format(new Date(e.atMs))).append(" · ").append(StepView.sourceLabel(e.source))
                    .append(" · ").append(e.count).append(" 步 · ")
                    .append(StepPanelModel.result(e.result)).append('\n');
        }
        records.setText(log.length() == 0 ? "尚無工作紀錄" : log.toString().trim());
    }

    private void editSettings() {
        if (settings == null || loading || closed) return;
        StepSettings original = settings;
        LinearLayout form = column();
        form.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText target = field(form, "每日目標（步）", Integer.toString(original.target), true);
        EditText start = field(form, "開始時間（0–23）", StepPanelModel.hourText(original.windowStartMin), true);
        EditText end = field(form, "結束時間（0–23）", StepPanelModel.hourText(original.windowEndMin), true);
        form.addView(text("相同時間為全天；結束早於開始代表跨日。", 12, false));
        EditText rate = field(form, "速率（步／分）", Integer.toString(original.ratePerMinute), true);
        LinearLayout presets = new LinearLayout(activity);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        button(presets, "一般", () -> rate.setText("130"));
        button(presets, "快走", () -> rate.setText("200"));
        button(presets, "奔跑", () -> rate.setText("350"));
        form.addView(presets);
        EditText interval = field(form, "寫入間隔（60–180 秒）", Long.toString(original.intervalMs / 1000), true);
        TextView error = text("只儲存設定，不啟動寫入。", 13, false);
        form.addView(error);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        dialog = new AlertDialog.Builder(activity).setTitle("步數設定").setView(scroll)
                .setNegativeButton("取消", null).setPositiveButton("儲存", null).create();
        final AlertDialog editing = dialog;
        editing.setOnShowListener(d -> editing.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final StepSettings next;
            try {
                next = StepPanelModel.settings(target.getText().toString(), start.getText().toString(),
                        end.getText().toString(), rate.getText().toString(), interval.getText().toString());
            } catch (IllegalArgumentException e) { error.setText(e.getMessage()); return; }
            editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            worker.execute(() -> {
                try {
                    StepView result = engine.configure(next);
                    activity.runOnUiThread(() -> {
                        if (closed) return;
                        if (StepView.STORE_ERROR.equals(result.phase) || StepView.REJECTED.equals(result.phase)) {
                            error.setText(result.detail);
                            editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        } else {
                            editing.dismiss();
                            feedback.setText("設定已儲存 · 寫入仍關閉" + (next.extremePreview() ? "\n目前為極端速率，啟用前需再次確認。" : ""));
                            refresh();
                        }
                    });
                } catch (RuntimeException e) {
                    activity.runOnUiThread(() -> {
                        if (closed) return;
                        error.setText("未確認儲存成功，請重試。");
                        editing.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    });
                }
            });
        }));
        editing.show();
    }

    private void toggleWriter() {
        if (engine == null || loading || closed) return;
        StepView current = engine.snapshot();
        if (current.desiredRunning) {
            loading = true;
            worker.execute(() -> {
                StepView stopped = engine.stop();
                StepService.signalStop(activity.getApplicationContext());
                StepSyncJob.cancel(activity.getApplicationContext());
                activity.runOnUiThread(() -> { loading = false; feedback.setText(stopped.detail); refresh(); });
            });
            return;
        }
        withDisclosure(() -> {
            if (!hasRequiredPermissions()) { openHealthPermissions(); return; }
            if (settings == null || settings.intervalMs < StepSyncJob.MIN_DELAY_MS) {
                feedback.setText("背景排程間隔最短 60 秒，請先調整設定。");
                return;
            }
            loading = true;
            worker.execute(() -> {
                StepView started = engine.start();
                boolean foreground = started.desiredRunning && StepWriterControl.mayLaunchService(started)
                        && StepService.start(activity.getApplicationContext(), engine.commandEpoch(), StepWriterControl.nextRunToken());
                boolean scheduled = started.desiredRunning
                        && StepSyncJob.scheduleRecovery(activity.getApplicationContext(), engine.commandEpoch());
                if (started.desiredRunning && !foreground && !scheduled) started = engine.stop();
                final StepView result = started;
                final boolean precise = foreground;
                final boolean ok = foreground || scheduled;
                activity.runOnUiThread(() -> {
                    loading = false;
                    feedback.setText(ok ? (precise
                            ? "自動寫入已開；通知常駐期間維持約每分鐘寫入。"
                            : "前景服務未啟動；目前由系統備援排程接續，時間可能延後。") : result.detail);
                    refresh();
                });
            });
        });
    }

    private boolean hasRequiredPermissions() {
        return StepPermissions.healthRead(activity) && StepPermissions.healthWrite(activity)
                && StepPermissions.healthBackground(activity);
    }

    private void withDisclosure(Runnable action) {
        StepView current = engine.snapshot();
        if (current.disclosed) { action.run(); return; }
        dialog = new AlertDialog.Builder(activity)
                .setTitle("啟用實驗步數工具？")
                .setMessage("這是人工／程式建立的非感測器步數，不是實際步行證明。\n\n"
                        + StepView.outcomeNote() + "\n預設關閉，可隨時停止。")
                .setNegativeButton("取消", null)
                .setPositiveButton("我了解", (d, w) -> worker.execute(() -> {
                    engine.disclose();
                    activity.runOnUiThread(action);
                })).create();
        dialog.show();
    }

    private void openHealthPermissions() {
        if (Build.VERSION.SDK_INT < 34) { feedback.setText("Android 13 暫不支援此步數工具。"); return; }
        try { activity.startActivity(StepPermissions.manageHealthIntent(activity.getPackageName())); }
        catch (RuntimeException error) {
            activity.requestPermissions(StepPermissions.requested(), 162);
        }
    }

    private void openBackgroundSettings() {
        dialog = new AlertDialog.Builder(activity)
                .setTitle("背景執行設定")
                .setMessage("開始後會顯示常駐通知，維持約每分鐘寫入；若服務被系統終止，會改由備援排程接續，時間可能延後。"
                        + "可在應用程式設定中允許背景活動，Health Connect 的背景讀取權限則由上方按鈕管理。")
                .setNegativeButton("知道了", null)
                .setNeutralButton("Health Connect 權限", (d, w) -> openHealthPermissions())
                .setPositiveButton("開啟應用程式設定", (d, w) -> {
                    try {
                        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + activity.getPackageName())));
                    } catch (RuntimeException error) {
                        feedback.setText("無法開啟系統設定，請從 Android 設定進入本 App。");
                    }
                }).create();
        dialog.show();
    }

    private void openAdvanced() {
        withDisclosure(() -> {
            if (!StepPermissions.healthRead(activity) || !StepPermissions.healthWrite(activity)) {
                openHealthPermissions();
                return;
            }
            activity.startActivity(new Intent(activity, StepActivity.class));
        });
    }

    void close() {
        closed = true;
        if (dialog != null) dialog.dismiss();
        worker.shutdown(); // Complete any explicitly requested settings save; discard UI callbacks.
    }

    private LinearLayout column() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }
    private LinearLayout card() {
        LinearLayout box = column();
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color("app_surface")); bg.setCornerRadius(dp(20));
        box.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(20), dp(10), dp(20), 0);
        root.addView(box, lp);
        return box;
    }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(value); view.setTextSize(size);
        view.setTextColor(color(bold ? "app_text_primary" : "app_text_secondary"));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setPadding(0, dp(5), 0, dp(5));
        return view;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        return button(parent, label, AppButtonStyle.Kind.SECONDARY, action);
    }
    private Button primaryButton(LinearLayout parent, String label, Runnable action) {
        return button(parent, label, AppButtonStyle.Kind.PRIMARY, action);
    }
    private Button button(LinearLayout parent, String label, AppButtonStyle.Kind kind, Runnable action) {
        Button b = AppButtonStyle.create(activity, label, kind, v -> action.run());
        boolean row = parent.getOrientation() == LinearLayout.HORIZONTAL;
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(row ? 0 : -1, -2, row ? 1 : 0);
        if (row) params.setMargins(dp(4), dp(2), dp(4), dp(2));
        else params.setMargins(0, dp(4), 0, 0);
        parent.addView(b, params);
        return b;
    }
    private EditText field(LinearLayout parent, String label, String initial, boolean number) {
        parent.addView(text(label, 13, false));
        EditText e = new EditText(activity);
        e.setContentDescription(label); e.setText(initial); e.setSingleLine(true);
        e.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_TIME);
        e.setTextColor(color("app_text_primary"));
        e.setMinHeight(dp(48)); parent.addView(e);
        return e;
    }
    private void disclosure(String title, LinearLayout contents) {
        LinearLayout box = card();
        contents.setVisibility(View.GONE);
        Button toggle = button(box, title + " ▾", AppButtonStyle.Kind.LINK, () -> { });
        toggle.setOnClickListener(v -> {
            boolean open = contents.getVisibility() != View.VISIBLE;
            contents.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(title + (open ? " ▴" : " ▾"));
            toggle.setContentDescription(title + (open ? "，已展開" : "，已收合"));
        });
        box.addView(contents);
    }
    private int dp(int n) { return Math.round(n * activity.getResources().getDisplayMetrics().density); }
    private int color(String name) {
        return activity.getResources().getColor(activity.getResources().getIdentifier(name, "color", activity.getPackageName()), activity.getTheme());
    }
}

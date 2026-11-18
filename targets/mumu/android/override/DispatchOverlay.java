package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.ContextThemeWrapper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.Locale;

/** Compact, task-bound overlay owned by an active dispatch or flower-farm service. */
final class DispatchOverlay {
    private final Context context;
    private final Context displayContext;
    private final WindowManager windowManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager.LayoutParams params;
    private LinearLayout root, expandedContent;
    private TextView dragTitle, toggle, progressLabel, itemText, statusText;
    private ProgressBar progressBar;
    private boolean attached, expanded;
    private final String title;
    private final Runnable stopAction;
    private String phase = "handoff", detail = "";
    private int position, total, successes, failures, skipped;
    private DispatchProbeGateway.Candidate candidate;
    private boolean flowerFarm;
    private String flowerFarmItem = "";
    private String flowerFarmDispatch = "";

    DispatchOverlay(Context serviceContext) {
        this(serviceContext, "批次探險", () -> DispatchBatchService.stop(serviceContext));
    }

    DispatchOverlay(Context serviceContext, String overlayTitle, Runnable onStop) {
        displayContext = serviceContext;
        Context appearance = ControlSettings.applyAppearance(serviceContext);
        int themeId = appearance.getResources().getIdentifier("AppTheme", "style", serviceContext.getPackageName());
        context = themeId == 0 ? appearance : new ContextThemeWrapper(appearance, themeId);
        windowManager = (WindowManager) serviceContext.getSystemService(Context.WINDOW_SERVICE);
        title = overlayTitle;
        stopAction = onStop;
    }

    void syncVisibility() {
        mainHandler.post(() -> {
            if (ControlSettings.dispatchOverlayEnabled(context) && Settings.canDrawOverlays(context)) showInternal();
            else hideInternal();
        });
    }

    void update(String nextPhase, int nextPosition, int nextTotal, DispatchProbeGateway.Candidate nextCandidate,
                String nextDetail, int nextSuccesses, int nextFailures) {
        update(nextPhase, nextPosition, nextTotal, nextCandidate, nextDetail, nextSuccesses, nextFailures, 0);
    }
    void update(String nextPhase, int nextPosition, int nextTotal, DispatchProbeGateway.Candidate nextCandidate,
                String nextDetail, int nextSuccesses, int nextFailures, int nextSkipped) {
        mainHandler.post(() -> {
            phase = nextPhase;
            position = nextPosition;
            total = nextTotal;
            candidate = nextCandidate;
            detail = nextDetail == null ? "" : nextDetail;
            successes = Math.max(0, nextSuccesses);
            failures = Math.max(0, nextFailures);
            skipped = Math.max(0, nextSkipped);
            flowerFarm = false;
            if (ControlSettings.dispatchOverlayEnabled(context) && Settings.canDrawOverlays(context)) {
                showInternal();
                render();
            } else hideInternal();
        });
    }

    void updateFlowerFarm(String nextStage, int nextPosition, int nextTotal, String nextItem) {
        updateFlowerFarm(nextStage, nextPosition, nextTotal, nextItem, "");
    }

    void updateFlowerFarm(String nextStage, int nextPosition, int nextTotal, String nextItem, String nextDispatch) {
        mainHandler.post(() -> {
            flowerFarm = true;
            phase = nextStage == null ? "準備中" : nextStage;
            position = nextPosition;
            total = nextTotal;
            candidate = null;
            flowerFarmItem = nextItem == null ? "" : nextItem;
            flowerFarmDispatch = nextDispatch == null ? "" : nextDispatch;
            if (ControlSettings.dispatchOverlayEnabled(context) && Settings.canDrawOverlays(context)) {
                showInternal();
                render();
            } else hideInternal();
        });
    }

    void hide() { mainHandler.post(this::hideInternal); }

    void onConfigurationChanged() {
        mainHandler.post(() -> {
            if (!attached || params == null || root == null) return;
            params.width = Math.min(dp(expanded ? 300 : 252), screenWidth() - dp(24));
            clampPosition(params.width);
            try { windowManager.updateViewLayout(root, params); } catch (RuntimeException ignored) { }
        });
    }

    private void showInternal() {
        if (attached) return;
        buildView();
        int screenWidth = screenWidth();
        int collapsedWidth = Math.min(dp(252), screenWidth - dp(24));
        params = new WindowManager.LayoutParams(collapsedWidth, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = ControlSettings.dispatchOverlayX(context, Math.max(dp(12), screenWidth - collapsedWidth - dp(12)));
        params.y = ControlSettings.dispatchOverlayY(context, dp(72));
        clampPosition(collapsedWidth);
        try {
            windowManager.addView(root, params);
            attached = true;
            render();
        } catch (RuntimeException ignored) {
            attached = false;
            root = null;
        }
    }

    private void hideInternal() {
        if (!attached || root == null) return;
        try { windowManager.removeView(root); } catch (RuntimeException ignored) { }
        attached = false;
        root = null;
    }

    private void buildView() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(4), dp(8), dp(8));
        root.setBackground(rounded(surface(), outline(), 1, dp(16)));
        root.setElevation(dp(8));
        root.setContentDescription(title + "懸浮控制");

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        dragTitle = text(title, 15, primary(), true);
        dragTitle.setMinHeight(dp(48));
        dragTitle.setGravity(Gravity.CENTER_VERTICAL);
        dragTitle.setContentDescription("拖曳懸浮視窗；點一下展開或收合");
        attachDragGesture(dragTitle);
        header.addView(dragTitle, new LinearLayout.LayoutParams(0, dp(48), 1));

        toggle = text("⌄", 20, accent(), true);
        toggle.setGravity(Gravity.CENTER);
        toggle.setMinWidth(dp(48));
        toggle.setMinHeight(dp(48));
        toggle.setContentDescription("展開懸浮視窗");
        toggle.setBackground(ripple(Color.TRANSPARENT, Color.TRANSPARENT, dp(14)));
        toggle.setOnClickListener(v -> setExpanded(!expanded));
        header.addView(toggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header);

        expandedContent = new LinearLayout(context);
        expandedContent.setOrientation(LinearLayout.VERTICAL);
        expandedContent.setPadding(dp(2), 0, dp(2), dp(2));
        progressLabel = text("準備中", 13, accent(), true);
        progressLabel.setPadding(0, 0, 0, dp(4));
        expandedContent.addView(progressLabel);
        progressBar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        expandedContent.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(16)));
        itemText = text("正在準備第一筆…", 15, primary(), true);
        itemText.setPadding(0, dp(10), 0, dp(2));
        expandedContent.addView(itemText);
        statusText = text("準備派遣", 14, secondary(), false);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusText.setPadding(0, 0, 0, dp(8));
        expandedContent.addView(statusText);

        Button open = actionButton("開啟控制中心", accent(), false);
        open.setOnClickListener(v -> {
            Intent intent = new Intent(context, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_OPEN_DISPATCH, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(intent);
        });
        expandedContent.addView(open, new LinearLayout.LayoutParams(-1, dp(48)));

        Button stop = actionButton("安全停止", danger(), true);
        stop.setOnClickListener(v -> {
            setTextIfChanged(statusText, "正在安全停止並釋放 GPS…");
            stopAction.run();
        });
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-1, dp(48));
        stopParams.topMargin = dp(6);
        expandedContent.addView(stop, stopParams);
        expandedContent.setVisibility(View.GONE);
        root.addView(expandedContent);
    }

    private void setExpanded(boolean value) {
        if (expanded == value || root == null || params == null) return;
        expanded = value;
        expandedContent.setVisibility(expanded ? View.VISIBLE : View.GONE);
        toggle.setText(expanded ? "⌃" : "⌄");
        toggle.setContentDescription(expanded ? "收合懸浮視窗" : "展開懸浮視窗");
        int screenWidth = screenWidth();
        params.width = Math.min(dp(expanded ? 300 : 252), screenWidth - dp(24));
        clampPosition(params.width);
        try { windowManager.updateViewLayout(root, params); } catch (RuntimeException ignored) { }
        render();
    }

    private void render() {
        if (!attached || root == null) return;
        String progress = total > 0 ? position + " / " + total : "準備中";
        String outcomes = "成功 " + successes + "／失敗 " + failures + (skipped > 0 ? "／略過 " + skipped : "");
        dragTitle.setText(expanded ? title : flowerFarm ? ("城市移動種花".equals(title) ? "● 種花 " : "● 花田 ") + progress
                + (flowerFarmDispatch.isEmpty() ? "" : " · " + compactDispatch(flowerFarmDispatch))
                : "● 批次 " + progress + " · 成" + successes + " 失" + failures
                + (skipped > 0 ? " 略" + skipped : ""));
        if (total > 0) {
            progressBar.setMax(Math.max(1, total));
            progressBar.setProgress(Math.max(0, position));
            progressBar.setContentDescription("批次進度 " + progress);
        } else {
            progressBar.setIndeterminate(true);
            progressBar.setContentDescription("批次準備中");
        }
        setTextIfChanged(progressLabel, total > 0
                ? flowerFarm ? ("城市移動種花".equals(title) ? "城市移動種花 " : "花田採果 ") + progress : "進度 " + progress + "（" + outcomes + "）"
                : "準備中");
        itemText.setText(flowerFarm ? (flowerFarmItem.isEmpty() ? "正在準備第一點…" : flowerFarmItem) : candidateLabel(candidate));
        if (flowerFarm) {
            setTextIfChanged(statusText, phase);
            return;
        }
        String phaseHuman = phaseLabel(phase);
        String detailHuman = DispatchProbeGateway.detailLabel(detail);
        setTextIfChanged(statusText, detailHuman.isEmpty() || detailHuman.equals(phaseHuman)
                ? phaseHuman : phaseHuman + "・" + detailHuman);
    }

    private String candidateLabel(DispatchProbeGateway.Candidate item) {
        if (item == null) return "正在準備第一筆…";
        String kind = "seed".equals(item.kind) ? "花苗" : "fruit".equals(item.kind) ? "水果" : "禮物盒";
        if (item.distanceMetres < 0) return kind;
        String distance = item.distanceMetres < 1000
                ? String.format(Locale.TAIWAN, "%.0f m", item.distanceMetres)
                : String.format(Locale.TAIWAN, "%.1f km", item.distanceMetres / 1000);
        return kind + " · " + distance;
    }

    private String compactDispatch(String value) {
        return value.replace("候選 ", "候").replace("／評估 ", " 評").replace("／送出 ", " 送");
    }

    private String phaseLabel(String value) {
        switch (value) {
            case "preflight": return "起飛前複查可用性";
            case "preflight-skipped": return "已略過，未起飛";
            case "handoff": return "準備派遣";
            case "awaiting-game-list": return "等待遊戲更新清單";
            case "arriving": return "移動到目標中";
            case "gps-drift": return "位置飄移，正在重新校正";
            case "start-requested": return "已送出派遣，等待確認";
            case "gift-sync-grace": return "等待禮物盒同步";
            case "gift-pikmin-unavailable": return "指定皮克敏不可用，已略過";
            case "rpc-faulted": return "派遣失敗，準備重試";
            case "confirmation-timeout": return "確認逾時";
            case "inventory-confirmed": return "已確認出發";
            case "post-dispatch-wait": return "準備前往下一筆";
            case "stopping": return "正在安全停止";
            case "stopped": return "停止控制已確認";
            case "stop-incomplete": return "停止未完成";
            case "failed": return "本筆失敗，繼續下一筆";
            default: return "批次準備中";
        }
    }

    private void attachDragGesture(View dragView) {
        dragView.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX, downRawY;
            private int downX, downY;
            private boolean moved;

            @Override public boolean onTouch(View view, MotionEvent event) {
                if (params == null || !attached) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = params.x;
                        downY = params.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) moved = true;
                        if (moved) {
                            params.x = downX + Math.round(dx);
                            params.y = downY + Math.round(dy);
                            clampPosition(params.width);
                            try { windowManager.updateViewLayout(root, params); } catch (RuntimeException ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (moved) ControlSettings.setDispatchOverlayPosition(context, params.x, params.y);
                        else if (event.getActionMasked() == MotionEvent.ACTION_UP) setExpanded(!expanded);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void clampPosition(int width) {
        int screenWidth = screenWidth();
        int screenHeight = screenHeight();
        params.x = Math.max(dp(8), Math.min(params.x, screenWidth - width - dp(8)));
        params.y = Math.max(dp(24), Math.min(params.y, screenHeight - dp(96)));
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button actionButton(String label, int color, boolean destructive) {
        Button button = new Button(context);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setTextColor(color);
        button.setMinHeight(dp(48));
        button.setBackground(ripple(destructive ? dangerSurface() : surfaceVariant(), color, dp(12)));
        return button;
    }

    private RippleDrawable ripple(int fill, int stroke, int radius) {
        GradientDrawable shape = rounded(fill, stroke, stroke == Color.TRANSPARENT ? 0 : 1, radius);
        return new RippleDrawable(ColorStateList.valueOf(withAlpha(primary(), 36)), shape, null);
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeWidth, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(radius);
        if (strokeWidth > 0) shape.setStroke(dp(strokeWidth), stroke);
        return shape;
    }

    private void setTextIfChanged(TextView view, String value) {
        if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private int color(String name, int fallback) {
        int id = context.getResources().getIdentifier(name, "color", context.getPackageName());
        return id == 0 ? fallback : context.getColor(id);
    }

    private int surface() { return color("app_surface", Color.WHITE); }
    private int surfaceVariant() { return color("app_surface_variant", Color.rgb(237, 241, 246)); }
    private int dangerSurface() { return withAlpha(danger(), 24); }
    private int primary() { return color("app_text_primary", Color.rgb(25, 30, 40)); }
    private int secondary() { return color("app_text_secondary", Color.rgb(83, 96, 114)); }
    private int accent() { return color("app_accent", Color.rgb(0, 101, 184)); }
    private int danger() { return color("app_danger", Color.rgb(179, 38, 30)); }
    private int outline() { return color("app_outline", Color.rgb(203, 212, 223)); }
    private int withAlpha(int value, int alpha) { return Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value)); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private int screenWidth() { return displayContext.getResources().getDisplayMetrics().widthPixels; }
    private int screenHeight() { return displayContext.getResources().getDisplayMetrics().heightPixels; }
}

package dev.ody.pikmincontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

/**
 * Owns the return-reward automation lifecycle. Target detection is intentionally fail-closed
 * until calibrated against supported game screenshots; it never dispatches a gesture by default.
 */
public final class ReturnRewardAccessibilityService extends AccessibilityService {
    private static final String GAME_PACKAGE = "com.nianticlabs.pikmin";
    private static final long SCAN_DELAY_MILLIS = 1000L;
    private static final long AFTER_TAP_DELAY_MILLIS = 1200L;
    private static volatile ReturnRewardAccessibilityService connected;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile String status = "服務未連線";
    private boolean scanInFlight;
    private boolean scanScheduled;
    private long sessionStartedAt;
    private long lastTapAt;
    private ReturnRewardDetector.Target pendingTarget;
    private int targetConfirmations;
    private ReturnedItemDetector.Target pendingItem;
    private int itemConfirmations;
    private long itemTappedAt;
    private ReturnedItemDetector.Kind tappedItemKind = ReturnedItemDetector.Kind.NONE;

    @Override public void onServiceConnected() {
        connected = this;
        status = ControlSettings.returnRewardEnabled(this)
                ? "已啟用；等待 Pikmin Bloom 前景" : "已連線，功能關閉";
        if (ControlSettings.returnRewardEnabled(this)) beginSession();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        if (!GAME_PACKAGE.contentEquals(event.getPackageName())) return;
        if (!ControlSettings.returnRewardEnabled(this)) {
            status = "功能關閉";
            resetSession();
            return;
        }
        beginSession();
    }

    @Override public void onInterrupt() { status = "無障礙服務被中斷"; }

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    static boolean isConnected() { return connected != null; }
    static String status() { return connected == null ? "尚未授權無障礙服務" : connected.status; }
    static void requestStart() {
        ReturnRewardAccessibilityService service = connected;
        if (service != null) service.beginSession();
    }

    private void beginSession() {
        if (!ControlSettings.returnRewardEnabled(this)) return;
        if (sessionStartedAt == 0L) {
            sessionStartedAt = android.os.SystemClock.elapsedRealtime();
            ControlSettings.resetSessionClaims(this);
            AutomationJournal.record(this, "返程收取已開始");
        }
        scheduleScan(0L);
    }

    private void scheduleScan(long delayMillis) {
        if (scanScheduled || scanInFlight) return;
        scanScheduled = true;
        handler.postDelayed(() -> {
            scanScheduled = false;
            takeRewardScreenshot();
        }, delayMillis);
    }

    private void takeRewardScreenshot() {
        if (!ControlSettings.returnRewardEnabled(this)) { resetSession(); return; }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            status = "Android 11 以上才支援返程畫面辨識";
            return;
        }
        long timeoutMillis = ControlSettings.timeoutMinutes(this) * 60L * 1000L;
        if (sessionStartedAt > 0 && android.os.SystemClock.elapsedRealtime() - sessionStartedAt >= timeoutMillis) {
            status = "安全逾時，已停止返程收取";
            ControlSettings.setReturnRewardEnabled(this, false);
            AutomationJournal.record(this, "安全逾時，已停止");
            resetSession();
            return;
        }
        scanInFlight = true;
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                scanInFlight = false;
                Bitmap bitmap = copy(result);
                if (bitmap == null) { status = "無法讀取遊戲畫面，已停止"; return; }
                try { handleScreenshot(bitmap); }
                finally { bitmap.recycle(); }
            }

            @Override public void onFailure(int errorCode) {
                scanInFlight = false;
                status = "畫面擷取失敗，等待重試（錯誤 " + errorCode + "）";
                scheduleScan(1000L);
            }
        });
    }

    private Bitmap copy(ScreenshotResult result) {
        HardwareBuffer buffer = result.getHardwareBuffer();
        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
        if (hardware == null) return null;
        try { return hardware.copy(Bitmap.Config.ARGB_8888, false); }
        finally { hardware.recycle(); buffer.close(); }
    }

    private void handleScreenshot(Bitmap bitmap) {
        ReturnedItemDetector.Target item = ReturnedItemDetector.find(bitmap);
        if (item != null) {
            handleReturnedItem(item, bitmap);
            return;
        }
        if (itemTappedAt != 0L) {
            completeReturnedItem();
            scheduleScan(AFTER_TAP_DELAY_MILLIS);
            return;
        }
        ReturnRewardDetector.Target target = ReturnRewardDetector.find(bitmap);
        if (target == null) {
            pendingTarget = null;
            targetConfirmations = 0;
            status = "等待可領取的完成探險卡片（本輪 " + ControlSettings.sessionClaims(this) + " 筆）";
            scheduleScan(SCAN_DELAY_MILLIS);
            return;
        }
        if (pendingTarget != null && Math.abs(target.x - pendingTarget.x) <= bitmap.getWidth() * 0.05f
                && Math.abs(target.y - pendingTarget.y) <= bitmap.getHeight() * 0.05f) {
            targetConfirmations++;
        } else {
            pendingTarget = target;
            targetConfirmations = 1;
        }
        if (targetConfirmations < 2) {
            status = "已找到完成卡片，進行安全確認";
            scheduleScan(SCAN_DELAY_MILLIS);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastTapAt < AFTER_TAP_DELAY_MILLIS) { scheduleScan(AFTER_TAP_DELAY_MILLIS); return; }
        status = "正在領取完成探險獎勵";
        AutomationJournal.record(this, "發現完成探險卡片，等待確認");
        lastTapAt = now;
        pendingTarget = null;
        targetConfirmations = 0;
        tap(target.x, target.y);
        scheduleScan(AFTER_TAP_DELAY_MILLIS);
    }

    private void handleReturnedItem(ReturnedItemDetector.Target item, Bitmap bitmap) {
        if (itemTappedAt != 0L) {
            if (item.kind != tappedItemKind) {
                itemTappedAt = 0L;
                pendingItem = null;
                itemConfirmations = 0;
            } else {
            status = "等待遊戲完成收取動畫";
            scheduleScan(SCAN_DELAY_MILLIS);
            return;
            }
        }
        if (pendingItem != null && Math.abs(item.x - pendingItem.x) <= bitmap.getWidth() * 0.05f
                && Math.abs(item.y - pendingItem.y) <= bitmap.getHeight() * 0.05f) {
            itemConfirmations++;
        } else {
            pendingItem = item;
            itemConfirmations = 1;
        }
        if (itemConfirmations < 2) {
            status = "已找到返程道具，進行安全確認";
            scheduleScan(SCAN_DELAY_MILLIS);
            return;
        }
        status = "正在收取返程道具";
        AutomationJournal.record(this, "發現返程道具，等待確認");
        itemTappedAt = android.os.SystemClock.elapsedRealtime();
        tappedItemKind = item.kind;
        pendingItem = null;
        itemConfirmations = 0;
        tap(item.x, item.y);
        scheduleScan(AFTER_TAP_DELAY_MILLIS);
    }

    private void completeReturnedItem() {
        itemTappedAt = 0L;
        tappedItemKind = ReturnedItemDetector.Kind.NONE;
        int claims = ControlSettings.incrementSessionClaims(this);
        status = "已完成收取第 " + claims + " 筆返程獎勵";
        AutomationJournal.record(this, "已完成第 " + claims + " 筆返程獎勵");
    }

    private boolean tap(int x, int y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0L, 60L)).build();
        boolean dispatched = dispatchGesture(gesture, null, null);
        if (!dispatched) status = "系統拒絕觸控，已停止";
        return dispatched;
    }

    private void resetSession() {
        sessionStartedAt = 0L;
        lastTapAt = 0L;
        pendingTarget = null;
        targetConfirmations = 0;
        pendingItem = null;
        itemConfirmations = 0;
        itemTappedAt = 0L;
        tappedItemKind = ReturnedItemDetector.Kind.NONE;
        scanScheduled = false;
    }
}

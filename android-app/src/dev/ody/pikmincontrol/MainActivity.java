package dev.ody.pikmincontrol;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local game control with optional anonymous HTTPS release checks. */
public final class MainActivity extends Activity {
    static final String EXTRA_OPEN_DISPATCH = "open_dispatch";
    static final String EXTRA_OPEN_PLANTER = "open_planter";
    static final String EXTRA_OPEN_RETURN = "open_return";
    static final String EXTRA_OPEN_FLOWER_FARM = "open_flower_farm";
    private static final int OVERVIEW = 0, NECTAR = 1, RETURN = 2, DISPATCH = 3, SYSTEM = 4;
    private static final int STEPS = MainNavigation.STEPS;
    private static final String STATE_SELECTED_TAB = "selected_tab";
    private static final long DISPATCH_AUTO_REFRESH_MS = 2_000L;
    private static final double[] RANGE_METRES = {0, 1_000, 10_000, 100_000};
    private static final int[] TEST_COUNTS = {1, 3, 10};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final DisplayRefreshGate displayRefresh = new DisplayRefreshGate();
    private java.io.File pendingDiagnostic;
    private String pendingProfileExport;
    private boolean exportingDiagnostic;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LinearLayout page, tabBar, safetyBar;
    private ScrollView pageScroll;
    private int[] scrollPositions = new int[MainNavigation.SCROLL_SLOTS];
    private int renderedPageKey = -1, pageRevision, expeditionTab;
    private Bundle expandedSections = new Bundle();
    private Switch nectarSwitch, returnSwitch, postcardSwitch, gpsSwitch, armedSwitch, overlaySwitch, bootJoystickSwitch, gameGuardSwitch;
    private FeedingPanel feedingPanel;
    private StepPanel stepPanel;
    private TextView overviewText, overviewStats, nectarStatus, nectarDetail, nectarLog, returnStatus, returnDetail, returnLog;
    private Switch planterSwitch, planterRefillSwitch;
    private Switch[] displaySwitches = new Switch[DisplayOptionsGateway.COUNT];
    private TextView[] displayStatuses = new TextView[DisplayOptionsGateway.COUNT];
    private final boolean[] displayBusy = new boolean[DisplayOptionsGateway.COUNT];
    private final int[] displayGenerations = new int[DisplayOptionsGateway.COUNT];
    private CheckBox[] planterStepBoxes;
    private CheckBox[] nearbyKindBoxes;
    private TextView nearbyStatus;
    private TextView nearbyDetail;
    private LinearLayout planterSlots;
    private TextView planterStatus, planterDetail, planterLog;
    private TextView gpsStatus, healthStatus, healthText, systemFeedback, overlayStatus, joystickStartupStatus, joystickStartupSummary, gameGuardStatus;
    private TextView dispatchStatus, dispatchFeedback, dispatchQueueSummary, dispatchLog;
    private TextView dispatchHistory, dispatchMetrics, dispatchStatusHistory;
    private TextView flowerFarmStatus, flowerFarmHistory;
    private TextView flowerFarmFeedback, flowerFarmPreview, flowerFarmIssues, queuePageLabel;
    private TextView overviewWork, overviewNearby, overviewPlanter, overviewBatch, overviewFarm, overviewReturn;
    private TextView overviewPlanterDetail, flowerFarmTitle, flowerFarmHint, flowerFarmSavedSummary;
    private LinearLayout flowerFarmNotice, flowerFarmEditor, flowerFarmEditActions, flowerFarmDwellBox, flowerFarmSaved;
    private Button flowerFarmClear, flowerFarmExpand, flowerFarmEditSaved;
    private boolean flowerFarmEditingRecovery;
    private Button flowerFarmUndo, queuePrevious, queueNext;
    private String clearedFlowerDraft;
    private final Runnable saveFlowerDraft = () -> { persistFlowerDraft(); updateFlowerPreview(); };
    private Button scanButton, batchTest, batchStart, batchStop, batchRecover;
    private Button flowerFarmStart, flowerFarmStop, flowerFarmRecover, flowerFarmReset;
    private CheckBox batchSeed, batchFruit, batchGift, batchNearestFirst;
    private Spinner batchRange, batchCount;
    private Spinner flowerFarmDwell;
    private EditText flowerFarmPoints;
    private int rangeIndex;
    private int batchKindMask = 7, testIndex, queuePage;
    private long scanGeneration;
    private String scannedKey = "";
    private volatile boolean dispatchReadPending;
    private volatile boolean nearbyReadPending;
    private boolean scanning;
    private boolean sortByDistance, refreshing, batchScanned, notificationPermissionPrompted, pendingOverlayEnable;
    private int selected = OVERVIEW;
    private Switch automaticUpdates;
    private TextView updateStatus, updateVersion, updateSummary, updateNotice;
    private Button updateCheck, updateInstall, updateCancel;
    private TextView updateConsent;
    private UpdateInfo displayedUpdate;
    private volatile boolean updateVisible;
    private final Runnable updateRefresh = new Runnable() {
        @Override public void run() {
            renderUpdateStatus();
            if(updateVisible)PhoneUpdateFlow.requestAdvance(getApplicationContext());
            mainHandler.postDelayed(this,1000);
        }
    };
    private List<DispatchProbeGateway.Candidate> batchQueue = new ArrayList<>();

    private final Runnable dispatchAutoRefresh = new Runnable() {
        @Override public void run() {
            if (selected != DISPATCH) return;
            refreshDispatch();
            mainHandler.postDelayed(this, DISPATCH_AUTO_REFRESH_MS);
        }
    };

    private final Runnable joystickStatusRefresh = new Runnable() {
        @Override public void run() {
            if (selected != SYSTEM) return;
            updateJoystickStatus();
            if(gameGuardStatus!=null)gameGuardStatus.setText(GamePidRecoveryService.status(MainActivity.this));
            mainHandler.postDelayed(this, 2000);
        }
    };
    private final Runnable nearbyStatusRefresh = new Runnable() {
        private long planterAt;
        @Override public void run() {
            if (selected != RETURN && selected != NECTAR) return;
            if(System.currentTimeMillis()-planterAt>15000){
                planterAt=System.currentTimeMillis();
                if(selected==RETURN)refreshReturn();else refreshNectar();
            } else if(selected==RETURN)refreshNearby();
            mainHandler.postDelayed(this, 3000);
        }
    };

    @Override protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(ControlSettings.applyAppearance(newBase));
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        ReadOnlyQueryPolicy.load(this);
        BatchUiSelection saved = ControlSettings.batchUi(this);
        batchKindMask = saved.kinds; rangeIndex = saved.range;
        sortByDistance = saved.nearest; testIndex = saved.test;
        expeditionTab = ControlSettings.expeditionTab(this);
        if (state != null) {
            String diagnosticName = state.getString("diagnostic_name", "");
            if (diagnosticName.matches("pikmin-diagnostics-[A-Za-z0-9-]+\\.txt")) {
                java.io.File savedDiagnostic = new java.io.File(getCacheDir(), diagnosticName);
                if (savedDiagnostic.isFile()) pendingDiagnostic = savedDiagnostic;
            }
            int[] positions = state.getIntArray("page_scrolls");
            scrollPositions = MainNavigation.restoreScrolls(positions);
            Bundle expanded = state.getBundle("expanded_sections");
            if (expanded != null) expandedSections = expanded;
            clearedFlowerDraft = state.getString("cleared_flower_draft");
        }
        if (getIntent().getBooleanExtra(EXTRA_OPEN_DISPATCH, false)) expeditionTab = 0;
        if (getIntent().getBooleanExtra(EXTRA_OPEN_FLOWER_FARM, false)) expeditionTab = 1;
        setContentView(buildShell());
        int restoredTab = getIntent().getBooleanExtra(EXTRA_OPEN_DISPATCH, false)
                || getIntent().getBooleanExtra(EXTRA_OPEN_FLOWER_FARM, false)
                ? DISPATCH : state == null ? OVERVIEW : state.getInt(STATE_SELECTED_TAB, OVERVIEW);
        showPage(MainNavigation.validPage(restoredTab) ? restoredTab : OVERVIEW);
        if (getIntent().getBooleanExtra(EXTRA_OPEN_PLANTER, false)) openPlanter();
        if (getIntent().getBooleanExtra(EXTRA_OPEN_RETURN, false)) showPage(RETURN);
        if (getIntent().getBooleanExtra("open_updates", false)) showPage(SYSTEM);
        if (getIntent().getBooleanExtra("open_flower_report", false)) { openExpedition(1); showFlowerReport(false); }
        if (SetupStore.offer(this)) new AlertDialog.Builder(this).setTitle("設定這支手機？")
                .setMessage("檢查環境、準備套件並引導必要權限；可稍後從系統繼續。")
                .setNegativeButton("稍後",(d,w)->SetupStore.save(SetupStore.prefs(this).edit().putBoolean("dismissed",true)))
                .setPositiveButton("開始設定",(d,w)->startActivity(new Intent(this,SetupActivity.class))).show();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_DISPATCH, false)) openExpedition(0);
        else if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_FLOWER_FARM, false)) openExpedition(1);
        else if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_PLANTER, false)) openPlanter();
        else if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_RETURN, false)) showPage(RETURN);
        else if (intent != null && intent.getBooleanExtra("open_updates", false)) showPage(SYSTEM);
        else if (intent != null && intent.getBooleanExtra("open_flower_report", false)) { openExpedition(1); showFlowerReport(false); }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (pendingDiagnostic != null) state.putString("diagnostic_name", pendingDiagnostic.getName());
        persistBatchTestSelection();
        persistFlowerDraft();
        saveScrollPosition();
        state.putInt(STATE_SELECTED_TAB, selected);
        state.putIntArray("page_scrolls", scrollPositions);
        state.putBundle("expanded_sections", expandedSections);
        state.putString("cleared_flower_draft", clearedFlowerDraft);
        super.onSaveInstanceState(state);
    }

    @Override protected void onResume() {
        super.onResume();
        updateVisible=true;
        ControlNotifications.refresh(this);
        CollectionMonitorService.visibility(true);
        PlanterSettings.start(this);
        StepService.resumeFromVisibleApp(getApplicationContext());
          worker.execute(() -> {SettingsRestoreJob.restore(getApplicationContext());
              runOnUiThread(()->{if(!isDestroyed())StepService.resumeFromVisibleApp(getApplicationContext());});});
        CollectionMonitorService.sync(this);
        if (SetupDeployment.pending(this)) { startActivity(new Intent(this, SetupActivity.class)); return; }
        if(ControlSettings.restartGameOnPidLoss(this)) {
            try { GamePidRecoveryService.sync(this); }
            catch(RuntimeException error) { android.util.Log.e("PikminPidRecovery","Cannot restore guard",error); }
        }
        UpdateController.check(this,false);
        mainHandler.removeCallbacks(updateRefresh);
        mainHandler.post(updateRefresh);
        JoystickStartupJob.recoverOnOpen(this);
        mainHandler.removeCallbacks(joystickStatusRefresh);
        if (selected == SYSTEM) mainHandler.post(joystickStatusRefresh);
        refreshVisible();
        mainHandler.removeCallbacks(dispatchAutoRefresh);
        if (selected == DISPATCH) mainHandler.postDelayed(dispatchAutoRefresh, DISPATCH_AUTO_REFRESH_MS);
        mainHandler.removeCallbacks(nearbyStatusRefresh);
        if (selected == RETURN || selected == NECTAR) mainHandler.postDelayed(nearbyStatusRefresh, 3000);
    }

    @Override protected void onPause() {
        updateVisible=false;
        CollectionMonitorService.visibility(false);
        ReadOnlyQueryPolicy.DISPLAY.invalidate();
        displayRefresh.invalidate();
        mainHandler.removeCallbacks(updateRefresh);
        mainHandler.removeCallbacks(nearbyStatusRefresh);
        persistBatchTestSelection();
        persistFlowerDraft();
        mainHandler.removeCallbacks(joystickStatusRefresh);
        mainHandler.removeCallbacks(dispatchAutoRefresh);
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (stepPanel != null) stepPanel.close();
        ++coreRenderGeneration;++corePollGeneration;
        if (readiness != null) readiness.cancel();
        if(coreDialog!=null)coreDialog.dismiss();
        dismissFlowerFarmConfirmation();
        mainHandler.removeCallbacks(updateRefresh);
        mainHandler.removeCallbacks(nearbyStatusRefresh);
        mainHandler.removeCallbacks(saveFlowerDraft);
        mainHandler.removeCallbacks(joystickStatusRefresh);
        mainHandler.removeCallbacks(dispatchAutoRefresh);
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != 1) return;
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        showExpeditionFeedback(granted
                ? "✓ 通知已允許。請再次確認並開始工作。"
                : "⚠ 未允許通知；背景進度可能不會顯示。", !granted);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==702||requestCode==703){
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            Uri uri=data.getData();String export=pendingProfileExport;pendingProfileExport=null;
            worker.execute(()->{
                try {
                    if(requestCode==702){
                        if(export==null)throw new IllegalStateException("請重新選擇匯出組合");
                        try(java.io.OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException("無法開啟檔案");out.write(export.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                        showSystemFeedback("設定組合已匯出；不含座標、權限或工作狀態。",false);
                    }else{
                        SettingsProfile profile;
                        try(java.io.InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new java.io.IOException("無法開啟檔案");profile=SettingsProfile.decode(new String(SetupBundle.read(in,SettingsProfile.MAX_BYTES),java.nio.charset.StandardCharsets.UTF_8));}
                        runOnUiThread(()->new AlertDialog.Builder(this).setTitle("匯入「"+profile.get("name")+"」？").setMessage("只儲存組合，同名會取代。不套用、不啟動功能；不含座標與授權。")
                            .setNegativeButton("取消",null).setPositiveButton("儲存",(d,w)->{try{SettingsProfiles.save(this,profile);showSystemFeedback("已匯入組合。",false);}catch(Exception e){showSystemFeedback(e.getMessage(),true);}}).show());
                    }
                }catch(Exception e){showSystemFeedback("組合未完成："+e.getMessage(),true);}
            });return;
        }
        if (requestCode == 701) {
            final java.io.File source = pendingDiagnostic;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && source != null) {
                final Uri destination = data.getData();
                worker.execute(() -> {
                    try (java.io.InputStream in = new java.io.FileInputStream(source);
                         java.io.OutputStream out = getContentResolver().openOutputStream(destination)) {
                        if (out == null) throw new java.io.IOException("無法開啟儲存位置");
                        byte[] buffer = new byte[8192]; int n;
                        while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
                        showSystemFeedback("✓ 診斷包已儲存", false);
                    } catch (Exception e) { showSystemFeedback("⚠ 儲存失敗，請重新匯出", true); }
                });
            }
            return;
        }
        if (requestCode != 2) return;
        boolean granted = Settings.canDrawOverlays(this);
        pendingOverlayEnable = false;
        ControlSettings.setDispatchOverlayEnabled(this, granted);
        DispatchBatchService.refreshOverlay(this);
        FlowerFarmService.refreshOverlay(this);
        refreshOverlaySetting();
        showSystemFeedback(granted
                ? "✓ 懸浮視窗已啟用，只會在批次或花田執行期間顯示。"
                : "⚠ 未允許顯示在其他 App 上層，懸浮視窗維持關閉。", !granted);
    }

    private View buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(background());

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, dp(6), 0, dp(18));
        ScrollView scroll = new ScrollView(this);
        pageScroll = scroll;
        scroll.setFillViewport(true);
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        safetyBar = new LinearLayout(this);
        safetyBar.setOrientation(LinearLayout.VERTICAL);
        safetyBar.setPadding(dp(18), dp(6), dp(18), dp(6));
        safetyBar.setBackgroundColor(background());
        safetyBar.setVisibility(View.GONE);
        root.addView(safetyBar, new LinearLayout.LayoutParams(-1, -2));

        tabBar = new LinearLayout(this);
        tabBar.setGravity(Gravity.CENTER);
        tabBar.setPadding(dp(6), dp(5), dp(6), dp(7));
        tabBar.setBackgroundColor(surface());
        tabBar.setElevation(0);
        root.addView(tabBar, new LinearLayout.LayoutParams(-1, dp(68)));
        return root;
    }

    private void showPage(int target) {
        ReadOnlyQueryPolicy.DISPLAY.invalidate();
        displayRefresh.invalidate();
        if (stepPanel != null) { stepPanel.close(); stepPanel = null; }
        dismissFlowerFarmConfirmation();
        mainHandler.removeCallbacks(nearbyStatusRefresh);
        persistBatchTestSelection();
        persistFlowerDraft();
        saveScrollPosition();
        final int revision = ++pageRevision;
        if (readiness != null) readiness.cancel();
        readinessStatus = readinessDetail = null;
        mainHandler.removeCallbacks(joystickStatusRefresh);
        if (target == SYSTEM) mainHandler.post(joystickStatusRefresh);
        mainHandler.removeCallbacks(dispatchAutoRefresh);
        selected = target;
        page.removeAllViews();
        automaticUpdates=null;updateStatus=updateVersion=updateSummary=updateNotice=null;
        updateCheck=updateInstall=updateCancel=null;
        updateConsent=null;displayedUpdate=null;
        safetyBar.removeAllViews();
        safetyBar.setVisibility(View.GONE);
        overviewText = overviewStats = nectarStatus = nectarLog = returnStatus = returnLog = null;
        nectarDetail = returnDetail = planterDetail = null;
        feedingPanel = null;
        planterSwitch = planterRefillSwitch = null;
        java.util.Arrays.fill(displaySwitches,null);
        java.util.Arrays.fill(displayStatuses,null);
        planterStepBoxes = null;
        nearbyKindBoxes = null;
        nearbyStatus = null;
        nearbyDetail = null;
        planterSlots = null;
        planterStatus = planterLog = null;
        gpsStatus = healthStatus = healthText = systemFeedback = overlayStatus = joystickStartupStatus = null;
        coreAdvancedTools = null;
        joystickStartupSummary = gameGuardStatus = null;
        dispatchStatus = dispatchFeedback = dispatchQueueSummary = dispatchLog = null;
        dispatchHistory = dispatchMetrics = dispatchStatusHistory = null;
        flowerFarmStatus = flowerFarmHistory = null;
        flowerFarmFeedback = flowerFarmPreview = flowerFarmIssues = queuePageLabel = null;
        overviewWork = overviewNearby = overviewPlanter = null;
        overviewBatch = overviewFarm = overviewReturn = null;
        overviewPlanterDetail = flowerFarmTitle = flowerFarmHint = flowerFarmSavedSummary = null;
        flowerFarmNotice = flowerFarmEditor = flowerFarmEditActions = flowerFarmDwellBox = flowerFarmSaved = null;
        flowerFarmClear = flowerFarmExpand = flowerFarmEditSaved = null;
        flowerFarmEditingRecovery = false;
        flowerFarmUndo = queuePrevious = queueNext = null;
        nectarSwitch = returnSwitch = postcardSwitch = gpsSwitch = armedSwitch = overlaySwitch = gameGuardSwitch = null;
        scanButton = batchTest = batchStart = batchStop = batchRecover = null;
        flowerFarmStart = flowerFarmStop = flowerFarmRecover = null;
        flowerFarmReset = null;
        batchRange = batchCount = null;
        flowerFarmDwell = null;
        flowerFarmMode = flowerFarmRounds = null;
        flowerStepOptions = null;
        flowerFarmEstimate = null;
        flowerFarmPoints = null;
        batchSeed = batchFruit = batchGift = batchNearestFirst = null;

        if (target == OVERVIEW) overviewPage();
        else if (target == NECTAR) nectarPage();
        else if (target == RETURN) returnPage();
        else if (target == DISPATCH) dispatchPage();
        else if (target == STEPS) {
            heading("步數", "");
            stepPanel = new StepPanel(this, page);
        }
        else systemPage();
        renderedPageKey = MainNavigation.scrollKey(target, expeditionTab);
        final int savedScroll = scrollPositions[renderedPageKey];
        page.post(() -> { if (revision == pageRevision) pageScroll.scrollTo(0, savedScroll); });
        renderTabs();
        refreshVisible();
        if (target == DISPATCH) mainHandler.postDelayed(dispatchAutoRefresh, DISPATCH_AUTO_REFRESH_MS);
        if (target == RETURN || target == NECTAR) mainHandler.postDelayed(nearbyStatusRefresh, 3000);
    }

    private void saveScrollPosition() {
        if (pageScroll != null && renderedPageKey >= 0) scrollPositions[renderedPageKey] = pageScroll.getScrollY();
    }

    private void openExpedition(int tab) {
        expeditionTab = tab == 1 ? 1 : 0;
        ControlSettings.setExpeditionTab(this, expeditionTab);
        showPage(DISPATCH);
    }

    private BatchUiSelection batchSelection() {
        return new BatchUiSelection(batchKindMask, rangeIndex, sortByDistance, testIndex);
    }

    private void saveBatchSelection() { ControlSettings.setBatchUi(this, batchSelection()); }

    private void persistBatchTestSelection() {
        if (batchCount == null || batchCount.getSelectedItemPosition() < 0) return;
        testIndex = batchCount.getSelectedItemPosition();
        saveBatchSelection();
    }

    private void overviewPage() {
        heading("總覽", "");
        updateNotice=noteBody("");
        updateNotice.setVisibility(View.GONE);
        updateNotice.setOnClickListener(v -> showPage(SYSTEM));
        page.addView(updateNotice,sideMargin());
        LinearLayout work = cardBox();
        work.addView(gardenCaption("GPS 工作"));
        overviewWork = gardenState("讀取中…");
        work.addView(overviewWork);
        overviewBatch = gardenCaption("批次 · 讀取中…");
        overviewFarm = gardenCaption("花田 · 讀取中…");
        work.addView(overviewBatch);
        work.addView(overviewFarm);
        addCard(work);
        LinearLayout nearby = cardBox();
        nearby.addView(gardenState("日常自動化"));
        overviewNearby = gardenAutomationRow(nearby, "附近派遣", v -> showPage(RETURN));
        LinearLayout rewards = nearby;
        // Separate, labelled targets: nectar readiness cannot prove return-claim readiness.
        overviewText = gardenAutomationRow(rewards, "大花精華", v -> showPage(NECTAR));
        overviewReturn = gardenAutomationRow(rewards, "獎勵領取", v -> showPage(RETURN));
        LinearLayout nursery = nearby;
        overviewPlanter = gardenAutomationRow(nursery, "育苗", v -> openPlanter());
        overviewPlanterDetail = gardenCaption("等待槽位回報");
        nursery.addView(overviewPlanterDetail);
        addCard(nursery);
        overviewStats = mono("正在讀取統計…");
        compactDisclosure("收益與派遣統計", overviewStats);
        Button daily=secondaryButton("更新今日成果",v->{
            v.setEnabled(false);worker.execute(()->{
                try{String summary=DailyResultsStore.refresh(this);runOnUiThread(()->{
                    if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("今日成果").setMessage(summary).setPositiveButton("關閉",null).show();
                });}catch(Exception e){runOnUiThread(()->{if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle("今日成果未更新").setMessage("原紀錄保留："+e.getMessage()).setPositiveButton("知道了",null).show();});}
                finally{runOnUiThread(()->v.setEnabled(true));}
            });
        });
        page.addView(daily,insetMargin(6));
        LinearLayout actions = new LinearLayout(this);
        actions.addView(secondaryButton("更新", v -> refreshOverview()), new LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(dangerButton("停止全部", v -> confirmStopAll()), new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(actions, insetMargin(8));
    }

    private void nectarPage() {
        heading("精華", "");
        feedingPanel = new FeedingPanel(this,worker);
        page.addView(feedingPanel.view(),insetMargin(8));
        page.addView(new AutoPetalPanel(this,worker).view(),insetMargin(8));
        LinearLayout nectarCard = cardBox();
        nectarSwitch = settingSwitch("自動收取附近精華", this::toggleNectar);
        setChecked(nectarSwitch,CollectionMonitorService.desired(this,"nectar"));
        nectarCard.addView(nectarSwitch);
        nectarStatus = liveBody("正在讀取精華模組…");
        statusWithRefresh(nectarCard, nectarStatus, v -> refreshNectar());
        addCard(nectarCard);
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        nectarDetail = body("");
        details.addView(nectarDetail);
        nectarLog = mono("正在讀取紀錄…");
        details.addView(nectarLog);
        collapsibleSection("收取紀錄", details);
    }

    private void returnPage() {
        heading("派遣", "");
        LinearLayout nearbyCard = cardBox();
        nearbyCard.setBackground(rounded(selectedSurface(), outline(), 1, dp(24)));
        armedSwitch = settingSwitch("附近自動派遣", this::toggleArmed);
        armedSwitch.setEnabled(false);
        nearbyCard.addView(armedSwitch);
        optionExplanation(nearbyCard, "附近派遣說明", "原生往返 ≤ 5 分鐘 · 不限距離");
        nearbyStatus = liveBody("正在讀取附近派遣設定…");
        nearbyStatus.setTextColor(accent());
        nearbyStatus.setPadding(0, dp(8), 0, dp(4));
        nearbyCard.addView(nearbyStatus);
        LinearLayout kindsRow = new LinearLayout(this);
        kindsRow.setOrientation(LinearLayout.HORIZONTAL);
        nearbyKindBoxes = new CheckBox[3];
        for (int i = 0; i < nearbyKindBoxes.length; i++) {
            CheckBox box = new CheckBox(this);
            box.setText(NearbyKinds.LABELS[i]);
            box.setTextSize(14);
            box.setTextColor(primary());
            styleChoice(box);
            box.setMinHeight(dp(48));
            box.setChecked((NearbyKinds.DEFAULT & (1 << i)) != 0);
            box.setEnabled(false);
            box.setOnCheckedChangeListener(this::toggleNearbyKinds);
            nearbyKindBoxes[i] = box;
            kindsRow.addView(box, new LinearLayout.LayoutParams(0, -2, 1));
        }
        nearbyCard.addView(kindsRow);
        nearbyDetail = mono("正在讀取保護狀態…");
        addCard(nearbyCard);
        LinearLayout rewardsCard = cardBox();
        returnSwitch = settingSwitch("自動領取獎勵", this::toggleReturn);
        setChecked(returnSwitch,CollectionMonitorService.desired(this,"reward"));
        rewardsCard.addView(returnSwitch);
        postcardSwitch = settingSwitch("捨棄明信片", this::togglePostcard);
        rewardsCard.addView(postcardSwitch);
        optionExplanation(rewardsCard, "領取與明信片說明", "返程、散步、蘑菇", "開啟即捨棄；預設保留。");
        returnStatus = liveBody("正在讀取返程狀態…");
        returnStatus.setTextSize(14);
        rewardsCard.addView(returnStatus);
        returnDetail = body("");
        returnLog = mono("正在讀取紀錄…");
        LinearLayout rewardDetails = new LinearLayout(this);
        rewardDetails.setOrientation(LinearLayout.VERTICAL);
        rewardDetails.addView(returnLog);
        disclosure(rewardsCard, "領取紀錄", rewardDetails);
        addCard(rewardsCard);
        sectionLabel("自動育苗");
        LinearLayout nurseryCard = cardBox();
        planterSwitch = settingSwitch("自動拔出成熟花苗", this::togglePlanter);
        nurseryCard.addView(planterSwitch);
        planterSlots = new LinearLayout(this);
        planterSlots.setOrientation(LinearLayout.HORIZONTAL);
        planterSlots.addView(noteBody("正在讀取育苗槽位…"));
        nurseryCard.addView(planterSlots);
        planterRefillSwitch = settingSwitch("空槽自動補種", this::togglePlanter);
        nurseryCard.addView(planterRefillSwitch);
        optionExplanation(nurseryCard, "育苗說明", "永久槽 · 保留加星苗");
        LinearLayout stepsCard = nurseryCard;
        HorizontalScrollView stepsScroll = new HorizontalScrollView(this);
        LinearLayout stepsRow = new LinearLayout(this);
        stepsRow.setOrientation(LinearLayout.HORIZONTAL);
        planterStepBoxes = new CheckBox[PlanterSteps.LABELS.length];
        for (int i = 0; i < planterStepBoxes.length; i++) {
            CheckBox box = new CheckBox(this);
            box.setText(PlanterSteps.LABELS[i]);
            box.setTextSize(14);
            box.setTextColor(primary());
            styleChoice(box);
            box.setMinHeight(dp(48));
            box.setChecked((PlanterSteps.DEFAULT & (1 << i)) != 0);
            box.setEnabled(false);
            box.setOnCheckedChangeListener(this::togglePlanterSteps);
            planterStepBoxes[i] = box;
            stepsRow.addView(box);
        }
        stepsScroll.addView(stepsRow);
        stepsCard.addView(secondaryButton("選擇補種步數", v -> editPlanterSteps()));
        planterStatus = liveBody("正在讀取育苗狀態…");
        planterStatus.setTextSize(14);
        nurseryCard.addView(planterStatus);
        planterDetail = body("");
        planterLog = mono("正在讀取紀錄…");
        LinearLayout planterDetails = new LinearLayout(this);
        planterDetails.setOrientation(LinearLayout.VERTICAL);
        planterDetails.addView(planterLog);
        disclosure(nurseryCard, "育苗紀錄", planterDetails);
        addCard(stepsCard);
        sectionLabel("遊戲資訊顯示");
        LinearLayout previewCard = cardBox();
        String[] displayLabels={"花苗資訊","皮克敏資訊","探險資訊","精華／花瓣對照"};
        String[] displayHelp={
            "花苗背包及探險花苗卡顯示已知顏色、種類、收集狀態與持／育／待數；育苗槽點開已種花苗，在詳細頁步數列顯示種類與剩餘步數。持＝成年持有、育＝正在育苗、待＝背包未種；缺資料顯示未知原因，不預測稀有或拔苗時間。",
            "成年皮克敏主清單同時顯示目前已取得裝飾與花態／收瓣狀態。蕾／花／枯為花態，收n為待收n瓣，任為任務中，？為未知。長名使用短標，完整名稱仍在原詳細頁；不推算餵食次數或恢復時間。",
            "探險清單及詳細頁辨識一般白禮物盒的明確對象與一般裝飾；清單地點列同時顯示待派期限、去程、搬運或返程時間，原地點請點詳細頁查看。時間向上取整、資料不足維持未知；遊戲原本隱藏地點列時不強制顯示，不預測稀有結果。",
            "遊戲餵食的選精華畫面保留原精華數量，另外對照花瓣、容量與剩餘空間；？代表未知。"
        };
        for(int i=0;i<DisplayOptionsGateway.COUNT;i++) {
            final int group=i;
            displaySwitches[i]=settingSwitch(displayLabels[i],(view,enabled)->toggleDisplayOption(group,view,enabled));
            displaySwitches[i].setEnabled(false);
            previewCard.addView(displaySwitches[i]);
            displayStatuses[i]=liveBody("正在讀取顯示設定…");
            previewCard.addView(displayStatuses[i]);
            optionExplanation(previewCard,displayLabels[i]+"說明",displayHelp[i]);
        }
        addCard(previewCard);
    }

    private void systemPage() {
        heading("系統", "");
        updatesCard();
        hcCompatCard();
        sectionLabel("偏好設定");
        LinearLayout appearanceBox = cardBox();
        appearanceBox.addView(fieldLabel("外觀"));
        String appearance = ControlSettings.appearance(this);
        int appearanceIndex = ControlSettings.APPEARANCE_LIGHT.equals(appearance) ? 1
                : ControlSettings.APPEARANCE_DARK.equals(appearance) ? 2 : 0;
        Spinner appearanceSpinner = spinner(new String[]{"跟隨系統", "自然花園 · 淺色", "夜間花園 · 深色"}, appearanceIndex);
        appearanceSpinner.setContentDescription("外觀模式");
        appearanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (parent != appearanceSpinner) return;
                String next = position == 1 ? ControlSettings.APPEARANCE_LIGHT
                        : position == 2 ? ControlSettings.APPEARANCE_DARK
                        : ControlSettings.APPEARANCE_SYSTEM;
                if (next.equals(ControlSettings.appearance(MainActivity.this))) return;
                ControlSettings.setAppearance(MainActivity.this, next);
                recreate();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        appearanceBox.addView(appearanceSpinner, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout overlayBox = appearanceBox;
        overlaySwitch = settingSwitch("顯示工作懸浮窗", this::toggleOverlay);
        overlayBox.addView(overlaySwitch);
        overlayStatus = noteBody("正在確認權限…");
        overlayBox.addView(overlayStatus);
        gpsSwitch = settingSwitch("擷取探險座標", this::toggleGps);
        appearanceBox.addView(gpsSwitch);
        Switch alertSwitch = settingSwitch("自動化異常通知", (view,enabled) -> AutomationAlerts.enabled(this,enabled));
        setChecked(alertSwitch,AutomationAlerts.enabled(this));
        appearanceBox.addView(alertSwitch);
        Switch resourceSwitch = settingSwitch("資源不足時停止GPS工作", (view,enabled) -> ResourceGuard.enabled(this,enabled));
        setChecked(resourceSwitch,ResourceGuard.enabled(this));
        appearanceBox.addView(resourceSwitch);
        optionExplanation(appearanceBox, "資源保護說明", "預設關閉；開啟後保留花苗5格、花瓣20片。資料待確認也會停止。");
        addCard(appearanceBox);
        page.addView(secondaryButton("設定組合 · 儲存／匯入匯出",v->showSettingsProfiles()),insetMargin(6));

        sectionLabel("GPS 與開機");
        LinearLayout bootBox = cardBox();
        bootJoystickSwitch = settingSwitch("開機啟動 JoyStick", this::toggleBootJoystick);
        bootBox.addView(bootJoystickSwitch);
        joystickStartupStatus = noteBody(JoystickStartupJob.status(this));
        joystickStartupSummary = noteBody(UiSummary.startup(JoystickStartupJob.status(this)));
        bootBox.addView(joystickStartupSummary);
        gpsStatus = liveBody("正在讀取 GPS 狀態…");
        gpsStatus.setTextSize(14);
        bootBox.addView(gpsStatus);
        bootBox.addView(secondaryButton("啟動並檢查 GPS", v -> {
            JoystickStartupJob.schedule(this, true);
            updateJoystickStatus();
        }));
        disclosure(bootBox, "啟動詳情", joystickStartupStatus);
        addCard(bootBox);

        LinearLayout guardBox=cardBox();
        gameGuardSwitch=settingSwitch("遊戲程序自動復原",this::toggleGameGuard);
        guardBox.addView(gameGuardSwitch);
        optionExplanation(guardBox, "自動復原說明", "預設關閉；只在曾確認遊戲程序後監看。新程序已正常載入時直接接續；異常時有限次重啟，不重送未確認操作。");
        gameGuardStatus=noteBody(GamePidRecoveryService.status(this));
        guardBox.addView(gameGuardStatus);
        addCard(guardBox);

        sectionLabel("裝置與環境");
        LinearLayout diagnostics = cardBox();
        healthStatus = liveBody("正在檢查模組…");
        healthStatus.setTextSize(14);
        statusWithRefresh(diagnostics, healthStatus, v -> refreshSystem());
        systemFeedback = feedbackText();
        diagnostics.addView(systemFeedback);
        diagnostics.addView(secondaryButton("新手機部署與修復", v -> startActivity(new Intent(this, SetupActivity.class))));
        diagnostics.addView(secondaryButton("診斷與進階工具", v -> openDiagnostics()));
        healthText = mono("正在讀取進階診斷…");
        addCard(diagnostics);
        page.addView(note("作者：Ody Liao\n版本：" + appVersion()), sideMargin());
    }

    private LinearLayout coreAdvancedTools;
    private interface DiagnosticRead { String read() throws Exception; }

    private void diagnosticRead(String title, DiagnosticRead action) {
        TextView content = body("正在讀取…");
        content.setPadding(dp(20),dp(12),dp(20),dp(12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(title).setView(scroll)
                .setPositiveButton("關閉",null).create();
        dialog.show();
        worker.execute(() -> {
            String result;
            try { result=action.read(); } catch(Exception e){ result="讀取未完成："+e.getMessage()+"\n設定不會因此重置。"; }
            final String text=result;
            runOnUiThread(() -> {if(!isFinishing()&&dialog.isShowing())content.setText(text);});
        });
    }

    private void openDiagnostics() {
        // Opening the directory performs no probe or mutation. Each action is explicit.
        String[] items={"環境與核心摘要","餵食：隊伍診斷","派遣：距離與狀態","領取：狀態與紀錄",
                "育苗：狀態與紀錄","GPS：啟動詳情","容量與資源","自動化異常紀錄",
                "餵食：原始紀錄","匯出診斷包","開發測試：小批次派遣","核心診斷與回退","唯讀查詢：統計與回退"};
        new AlertDialog.Builder(this).setTitle("診斷與進階工具").setItems(items,(dialog,index)->{
            switch(index){
                case 0: final String environment=healthText==null?"請回系統更新環境摘要。":healthText.getText().toString();
                    diagnosticRead(items[index],()->environment);break;
                case 1: new FeedingPanel(this,worker).squadDiagnostics();break;
                case 2: diagnosticRead(items[index],()->DispatchProbeGateway.status()+"\n\n"+DispatchProbeGateway.metricsFormatted()+"\n\n"+DispatchProbeGateway.batchStatusHistoryFormatted());break;
                case 3: diagnosticRead(items[index],()->ReturnRewardModuleGateway.statusSummary()+"\n\n"+ReturnRewardModuleGateway.recentDetailedEvents());break;
                case 4: diagnosticRead(items[index],()->{PlanterState p=PlanterGateway.read();return p.summary+"\n\n"+p.history;});break;
                case 5: diagnosticRead(items[index],()->JoystickStartupJob.status(this));break;
                case 6: diagnosticRead(items[index],()->ResourceGuard.read().summary());break;
                case 7: diagnosticRead(items[index],()->AutomationAlerts.history(this));break;
                case 8: diagnosticRead(items[index],FeedingGateway::history);break;
                case 9: exportDiagnostics();break;
                case 10: openBatchTest();break;
                case 11:
                    if(coreAdvancedTools==null){toast("核心資料讀取中，請稍候再開啟");break;}
                    if(coreAdvancedTools.getParent() instanceof android.view.ViewGroup)
                        ((android.view.ViewGroup)coreAdvancedTools.getParent()).removeView(coreAdvancedTools);
                    ScrollView tools=new ScrollView(this);tools.addView(coreAdvancedTools);
                    new AlertDialog.Builder(this).setTitle(items[index]).setView(tools).setPositiveButton("關閉",null).show();break;
                case 12:
                    final boolean optimized=ReadOnlyQueryPolicy.enabled(this);
                    new AlertDialog.Builder(this).setTitle(items[index])
                        .setMessage((optimized?"唯讀合併已開啟":"原始查詢模式")+"\n\n"+RootQueryStats.summary()
                            +"\n\n只合併領取／精華畫面與監控查詢；不更改自動操作頻率。完成結果不快取。")
                        .setNegativeButton("關閉",null)
                        .setPositiveButton(optimized?"停用優化":"啟用優化",(d,w)->{
                            try {ReadOnlyQueryPolicy.choose(this,!optimized);displayRefresh.invalidate();refreshVisible();}
                            catch(Exception e){toast(e.getMessage());}
                        }).show();break;
            }
        }).setNegativeButton("關閉",null).show();
    }

    private void openBatchTest() {
        openExpedition(0);
        new AlertDialog.Builder(this).setTitle("開發測試：小批次派遣")
                .setMessage("將使用目前掃描清單，實際移動 GPS 並派遣皮克敏。請先掃描；一般使用請按開始全部派遣。")
                .setPositiveButton("選擇測試筆數",(d,w)->new AlertDialog.Builder(this).setTitle("測試筆數")
                        .setSingleChoiceItems(new String[]{"1 筆","3 筆","10 筆"},testIndex,(choice,i)->{
                            testIndex=i; if(batchCount!=null)batchCount.setSelection(i); saveBatchSelection();
                        }).setNegativeButton("取消",null).setPositiveButton("執行測試",(choice,i)->startTestBatch()).show())
                .setNegativeButton("取消",null).show();
    }

    private void dispatchPage() {
        heading("探險", "");
        LinearLayout tabs = new LinearLayout(this);
        Button batchTab = expeditionTab == 0 ? primaryButton("批次探險", v -> openExpedition(0))
                : secondaryButton("批次探險", v -> openExpedition(0));
        Button farmTab = expeditionTab == 1 ? primaryButton("花田採果", v -> openExpedition(1))
                : secondaryButton("花田採果", v -> openExpedition(1));
        batchTab.setSelected(expeditionTab == 0); farmTab.setSelected(expeditionTab == 1);
        if (Build.VERSION.SDK_INT >= 30) {
            batchTab.setStateDescription(expeditionTab == 0 ? "已選取" : "未選取");
            farmTab.setStateDescription(expeditionTab == 1 ? "已選取" : "未選取");
        }
        pairedButtons(tabs, batchTab, farmTab);
        page.addView(tabs, insetMargin(6));
        if (expeditionTab == 1) { flowerFarmPage(); return; }

        dispatchStatus = liveBody("正在讀取批次狀態…");
        dispatchStatus.setTextSize(14);
        page.addView(dispatchStatus, insetMargin(8));
        batchRecover=secondaryButton("診斷並恢復批次",v -> confirmBatchRecovery());
        page.addView(batchRecover,insetMargin(6));

        LinearLayout typeBox = cardBox();
        LinearLayout kinds = new LinearLayout(this);
        batchSeed = batchType("花苗", 1);
        batchFruit = batchType("水果", 2);
        batchGift = batchType("禮物盒", 4);
        kinds.addView(batchSeed, new LinearLayout.LayoutParams(0, -2, 1));
        kinds.addView(batchFruit, new LinearLayout.LayoutParams(0, -2, 1));
        kinds.addView(batchGift, new LinearLayout.LayoutParams(0, -2, 1));
        typeBox.addView(kinds);

        LinearLayout filterBox = typeBox;
        batchNearestFirst = new CheckBox(this);
        batchNearestFirst.setText("由近到遠");
        batchNearestFirst.setTextColor(primary());
        batchNearestFirst.setMinHeight(dp(48));
        batchNearestFirst.setOnCheckedChangeListener(null);
        batchNearestFirst.setChecked(sortByDistance);
        batchNearestFirst.setOnCheckedChangeListener((v, checked) -> {
            sortByDistance = checked;
            saveBatchSelection();
            invalidateQueue();
        });
        filterBox.addView(batchNearestFirst);
        LinearLayout rangeRow = new LinearLayout(this);
        rangeRow.setGravity(Gravity.CENTER_VERTICAL);
        rangeRow.addView(body("最遠距離"), new LinearLayout.LayoutParams(0, -2, 1));
        batchRange = spinner(new String[]{"不限", "1 公里", "10 公里", "100 公里"}, rangeIndex);
        batchRange.setContentDescription("最遠距離");
        batchRange.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (rangeIndex != position) {
                    rangeIndex = position;
                    saveBatchSelection();
                    invalidateQueue();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        rangeRow.addView(batchRange, new LinearLayout.LayoutParams(0, dp(52), 1));
        filterBox.addView(rangeRow);

        scanButton = primaryButton("掃描勾選類型", v -> scanBatch());
        filterBox.addView(scanButton);
        addCard(filterBox);
        dispatchFeedback = feedbackText();
        page.addView(dispatchFeedback, sideMargin());
        dispatchQueueSummary = liveBody("尚未掃描 · 先開啟遊戲探險頁。");
        card(dispatchQueueSummary);
        dispatchLog = body("尚未建立批次清單。");
        LinearLayout queueBox = new LinearLayout(this);
        queueBox.setOrientation(LinearLayout.VERTICAL);
        queuePageLabel = noteBody("");
        queueBox.addView(queuePageLabel);
        queueBox.addView(dispatchLog);
        LinearLayout paging = new LinearLayout(this);
        queuePrevious = secondaryButton("上一頁", v -> moveQueuePage(-1));
        queueNext = secondaryButton("下一頁", v -> moveQueuePage(1));
        paging.addView(queuePrevious, new LinearLayout.LayoutParams(0, -2, 1));
        paging.addView(queueNext, new LinearLayout.LayoutParams(0, -2, 1));
        queueBox.addView(paging);
        collapsibleSection("掃描結果明細", queueBox);
        renderQueue();

        LinearLayout testBox = cardBox();
        testBox.addView(fieldLabel("測試筆數"));
        batchCount = spinner(new String[]{"1 筆", "3 筆", "10 筆"}, testIndex);
        batchCount.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                if (p != batchCount) return;
                testIndex = i; saveBatchSelection();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        batchCount.setContentDescription("測試筆數");
        testBox.addView(batchCount, new LinearLayout.LayoutParams(-1, dp(52)));
        batchTest = secondaryButton("執行測試", v -> startTestBatch());
        testBox.addView(batchTest, new LinearLayout.LayoutParams(-1, -2));
        // Developer tests are opened from System; the live queue remains in this Activity.
        batchStart = primaryButton("開始全部派遣", v -> startBatch());
        page.addView(batchStart, insetMargin(6));
        page.addView(checkpointButton("接續未完成批次",GpsWorkLock.BATCH),insetMargin(6));
        batchStop = dangerButton("安全停止", v -> stopBatch());
        safetyBar.addView(batchStop);

        dispatchHistory = mono("正在讀取派遣歷史…");
        collapsibleSection("派遣歷史（最近 24 小時）", dispatchHistory);
        dispatchMetrics = mono("正在讀取距離量測…");
        dispatchStatusHistory = mono("正在讀取狀態歷程…");
        LinearLayout debug = new LinearLayout(this);
        debug.setOrientation(LinearLayout.VERTICAL);
        debug.addView(fieldLabel("到點距離量測"));
        debug.addView(dispatchMetrics);
        debug.addView(fieldLabel("進階狀態歷程"));
        debug.addView(dispatchStatusHistory);
        updateBatchStatus("");
        updateQueueControls();
    }

    private void flowerFarmPage() {
        flowerFarmNotice = cardBox();
        flowerFarmTitle = gardenCaption("");
        flowerFarmStatus = gardenState("正在讀取花田狀態…");
        flowerFarmHint = gardenCaption("");
        flowerFarmNotice.addView(flowerFarmTitle);
        flowerFarmNotice.addView(flowerFarmStatus);
        flowerFarmNotice.addView(flowerFarmHint);
        addCard(flowerFarmNotice);
        flowerFarmSaved = cardBox();
        flowerFarmSavedSummary = gardenState("");
        flowerFarmSaved.addView(flowerFarmSavedSummary);
        flowerFarmEditSaved = secondaryButton("編輯花點", v -> {
            if (!farmPresentation().canEdit) return;
            flowerFarmEditingRecovery = !flowerFarmEditingRecovery;
            renderFlowerFarm();
        });
        flowerFarmSaved.addView(flowerFarmEditSaved);
        addCard(flowerFarmSaved);
        flowerFarmEditor = cardBox();
        TextView inputTitle = gardenState("花點座標");
        flowerFarmEditor.addView(inputTitle);
        flowerFarmPoints = new EditText(this);
        flowerFarmPoints.setText(ControlSettings.flowerFarmPoints(this));
        flowerFarmPoints.setHint("緯度, 經度\n每行一組座標");
        flowerFarmPoints.setTextSize(16);
        flowerFarmPoints.setTextColor(primary());
        flowerFarmPoints.setHintTextColor(secondary());
        flowerFarmPoints.setGravity(Gravity.TOP | Gravity.START);
        flowerFarmPoints.setPadding(dp(12), dp(12), dp(12), dp(12));
        flowerFarmPoints.setBackground(rounded(background(), accent(), 2, dp(12)));
        flowerFarmPoints.setContentDescription("花田 GPS 座標輸入區，每行一個緯度與經度");
        flowerFarmPoints.setMinLines(4);
        flowerFarmPoints.setMaxLines(4);
        flowerFarmPoints.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        flowerFarmEditor.addView(flowerFarmPoints, new LinearLayout.LayoutParams(-1, -2));
        flowerFarmPreview = gardenCaption("");
        flowerFarmEditor.addView(flowerFarmPreview);
        addCard(flowerFarmEditor);
        flowerFarmEditActions = new LinearLayout(this);
        flowerFarmExpand = secondaryButton("展開編輯", v -> {
            if (!farmPresentation().canEdit) return;
            boolean expanded = flowerFarmPoints.getMaxLines() == 4;
            flowerFarmPoints.setMaxLines(expanded ? 12 : 4);
            ((Button) v).setText(expanded ? "收合編輯" : "展開編輯");
        });
        flowerFarmClear = secondaryButton("清除", v -> clearFlowerFarmPoints());
        flowerFarmClear.setContentDescription("清除座標");
        pairedButtons(flowerFarmEditActions, flowerFarmExpand, flowerFarmClear);
        page.addView(flowerFarmEditActions, insetMargin(10));
        LinearLayout savedRoutes = new LinearLayout(this);
        pairedButtons(savedRoutes, secondaryButton("儲存路線", v -> saveRouteDraft()),
                secondaryButton("路線庫", v -> showSavedRoutes()));
        page.addView(savedRoutes, insetMargin(6));
        flowerFarmUndo = secondaryButton("復原清除", v -> undoFlowerFarmClear());
        flowerFarmUndo.setVisibility(clearedFlowerDraft == null ? View.GONE : View.VISIBLE);
        page.addView(flowerFarmUndo, insetMargin(6));
        flowerFarmDwellBox = cardBox();
        flowerFarmDwellBox.addView(fieldLabel("採果方式"));
        flowerFarmMode = spinner(new String[]{"逐點採果", "沿路採果 · 19 km/h 單趟"},ControlSettings.routeHarvest(this)?1:0);
        flowerFarmDwellBox.addView(flowerFarmMode,new LinearLayout.LayoutParams(-1,dp(52)));
        flowerFarmMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int i,long id){
                if(!farmPresentation().canEdit)return;
                ControlSettings.setRouteHarvest(MainActivity.this,i==1);renderFlowerFarm();
            }
            public void onNothingSelected(AdapterView<?> p){}
        });
        flowerFarmEstimate = noteBody("");
        flowerFarmDwellBox.addView(flowerFarmEstimate);
        disclosure(flowerFarmDwellBox, "時間估算說明", gardenCaption(FlowerFarmEstimate.LIMITS));
        flowerStepOptions = new LinearLayout(this);flowerStepOptions.setOrientation(LinearLayout.VERTICAL);
        flowerFarmDwellBox.addView(flowerStepOptions);
        LinearLayout dwellRow = new LinearLayout(this);
        dwellRow.setGravity(Gravity.CENTER_VERTICAL);
        dwellRow.addView(body("每點停留"), new LinearLayout.LayoutParams(0, -2, 1));
        flowerFarmDwell = spinner(new String[]{"5 秒", "10 秒"}, ControlSettings.flowerFarmDwell(this) == 5 ? 0 : 1);
        flowerFarmDwell.setContentDescription("花田每點停留時間");
        flowerFarmDwell.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) { persistFlowerDraft(); updateFlowerEstimate(); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        flowerFarmDwell.setMinimumWidth(dp(132));
        dwellRow.addView(flowerFarmDwell, new LinearLayout.LayoutParams(-2, dp(52)));
        flowerStepOptions.addView(dwellRow);
        LinearLayout roundsRow = new LinearLayout(this);
        roundsRow.setGravity(Gravity.CENTER_VERTICAL);
        roundsRow.addView(body("採果輪數"), new LinearLayout.LayoutParams(0, -2, 1));
        flowerFarmRounds = spinner(new String[]{"1 輪", "2 輪"}, ControlSettings.flowerFarmRounds(this) - 1);
        flowerFarmRounds.setContentDescription("花田採果輪數");
        flowerFarmRounds.setMinimumWidth(dp(132));
        flowerFarmRounds.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int i, long id) {
                ControlSettings.setFlowerFarmRounds(MainActivity.this, i + 1);
                updateFlowerEstimate();
            }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
        roundsRow.addView(flowerFarmRounds, new LinearLayout.LayoutParams(-2, dp(52)));
        flowerStepOptions.addView(roundsRow);
        flowerFarmDwellBox.addView(gardenCaption("首點到達後種花 · 結束後交還控制"));
        addCard(flowerFarmDwellBox);
        flowerFarmStart = primaryButton("開始採果", v -> {
            if(ControlSettings.routeHarvest(this))confirmRouteHarvest();else confirmFlowerFarmStart();
        });
        page.addView(flowerFarmStart, insetMargin(10));
        flowerFarmRecover = primaryButton("復原並重新開始", v -> {
            if(ControlSettings.routeHarvest(this))confirmRouteHarvest();else confirmFlowerFarmStart(true);
        });
        page.addView(flowerFarmRecover, insetMargin(10));
        flowerFarmReset = secondaryButton("檢查並重置", v -> confirmFarmReset());
        page.addView(flowerFarmReset, insetMargin(10));
        flowerFarmStop = dangerButton("安全停止", v -> stopFlowerFarm());
        safetyBar.addView(flowerFarmStop);
        flowerFarmFeedback = feedbackText();
        page.addView(flowerFarmFeedback, sideMargin());
        flowerFarmIssues = body("");
        flowerFarmHistory = body("尚無執行紀錄。");
        LinearLayout records = new LinearLayout(this);
        records.setOrientation(LinearLayout.VERTICAL);
        records.addView(secondaryButton("採果結果報告", v -> showFlowerReport(true)));
        records.addView(checkpointButton("接續未完成花田",GpsWorkLock.FLOWER_FARM));
        records.addView(fieldLabel("上次執行紀錄"));
        records.addView(flowerFarmHistory);
        records.addView(fieldLabel("座標檢查明細"));
        records.addView(flowerFarmIssues);
        optionExplanation(records, "花田執行說明", "每點停留後繼續，不等待水果。所選輪數結束再停止種花。");
        compactDisclosure("工作紀錄與路線診斷", records);
        updateSafetyBar(FlowerFarmService.isRunning(), GpsWorkLock.FLOWER_FARM, flowerFarmStop);
        updateFlowerPreview();
        flowerFarmPoints.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                mainHandler.removeCallbacks(saveFlowerDraft);
                mainHandler.postDelayed(saveFlowerDraft, 300L);
            }
        });
    }

    private void renderTabs() {
        tabBar.removeAllViews();
        tab("總覽", OVERVIEW, "nav_overview");
        tab("精華", NECTAR, "nav_nectar");
        tab("派遣", RETURN, "nav_return");
        tab("探險", DISPATCH, "nav_dispatch");
        tab("步數", STEPS, "nav_steps");
    }

    private void tab(String label, int target, String iconName) {
        boolean active = target == selected;
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(11);
        view.setAllCaps(false);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(3), dp(3), dp(3), dp(3));
        view.setTextColor(active ? accent() : secondary());
        int icon = getResources().getIdentifier(iconName, "drawable", getPackageName());
        view.setCompoundDrawablesWithIntrinsicBounds(0, icon, 0, 0);
        view.setCompoundDrawablePadding(dp(2));
        if (Build.VERSION.SDK_INT >= 23)
            view.setCompoundDrawableTintList(ColorStateList.valueOf(active ? accent() : secondary()));
        view.setBackground(ripple(active ? selectedSurface() : surface(), active ? accent() : Color.TRANSPARENT, dp(14)));
        view.setElevation(0);
        view.setStateListAnimator(null);
        view.setSelected(active);
        view.setContentDescription(label + (active ? "，目前分頁" : ""));
        if (Build.VERSION.SDK_INT >= 30) view.setStateDescription(active ? "已選取" : "未選取");
        view.setOnClickListener(v -> showPage(target));
        tabBar.addView(view, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void toggleNectar(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        view.setEnabled(false);
        showInline(nectarStatus, "正在更新精華自動收取…", false);
        worker.execute(() -> {
            try { AutomationPreferences.chooseCollection(this,"nectar",enabled); }
            catch (Exception e) { showInline(nectarStatus, "⚠ 無法更新精華自動收取：" + e.getMessage(), true); }
            finally { runOnUiThread(() -> { view.setEnabled(true); refreshNectar(); }); }
        });
    }

    private void toggleReturn(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        view.setEnabled(false);
        setReturnMode(enabled ? "all" : "off");
    }

    private void togglePostcard(CompoundButton view, boolean discard) {
        if (refreshing) return;
        view.setEnabled(false);
        showInline(returnStatus, "正在更新明信片設定…", false);
        worker.execute(() -> {
            try { AutomationPreferences.choose(this,"postcard",discard?"discard":"keep",()->ReturnRewardModuleGateway.setPostcardPolicy(discard ? "discard" : "keep")); }
            catch (Exception e) { showInline(returnStatus, "⚠ 無法設定明信片：" + e.getMessage(), true); }
            finally { refreshReturn(); }
        });
    }

    private void toggleGps(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        view.setEnabled(false);
        showInline(gpsStatus, "正在更新座標擷取設定…", false);
        worker.execute(() -> {
            try { AutomationPreferences.choose(this,"copy",enabled?"on":"off",()->GpsModuleGateway.setAutoCopy(this,enabled)); }
            catch (Exception e) { showInline(gpsStatus, "⚠ 無法設定 GPS 模組：" + e.getMessage(), true); }
            finally { refreshGps(); }
        });
    }

    private void toggleOverlay(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        if (!enabled) {
            pendingOverlayEnable = false;
            ControlSettings.setDispatchOverlayEnabled(this, false);
            DispatchBatchService.refreshOverlay(this);
            FlowerFarmService.refreshOverlay(this);
            refreshOverlaySetting();
            showSystemFeedback("✓ 懸浮視窗已關閉。", false);
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            ControlSettings.setDispatchOverlayEnabled(this, true);
            DispatchBatchService.refreshOverlay(this);
            FlowerFarmService.refreshOverlay(this);
            refreshOverlaySetting();
            showSystemFeedback("✓ 懸浮視窗已啟用，只會在批次或花田執行期間顯示。", false);
            return;
        }
        pendingOverlayEnable = true;
        view.setEnabled(false);
        new AlertDialog.Builder(this)
                .setTitle("允許懸浮視窗？")
                .setMessage("控制中心只會在批次探險或花田採果執行期間顯示精簡進度與安全停止按鈕。你可以隨時回到此處關閉。\n\n"
                        + "部分機型（例如部分小米／HyperOS 版本）的系統設定清單不會列出本機安裝的 App，"
                        + "如果進入系統設定後找不到「Pikmin 控制中心」，可改用 adb 指令授權：\n"
                        + "adb shell appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow")
                .setNegativeButton("取消", (dialog, which) -> {
                    pendingOverlayEnable = false;
                    setChecked(view, false);
                    view.setEnabled(true);
                })
                .setPositiveButton("前往系統設定", (dialog, which) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intent, 2);
                    } catch (Exception error) {
                        pendingOverlayEnable = false;
                        setChecked(view, false);
                        view.setEnabled(true);
                        showSystemFeedback("⚠ 無法開啟懸浮視窗權限設定。", true);
                    }
                })
                .setOnCancelListener(dialog -> {
                    pendingOverlayEnable = false;
                    setChecked(view, false);
                    view.setEnabled(true);
                })
                .show();
    }

    private void toggleArmed(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        view.setEnabled(false);
        worker.execute(() -> {
            try {
                AutomationPreferences.chooseNearby(this,enabled);
            } catch (Exception e) {
                toast("附近派遣設定未保存：" + e.getMessage());
            } finally {
                refreshNearby();
            }
        });
    }

    private void toggleNearbyKinds(CompoundButton view, boolean enabled) {
        if (refreshing || nearbyKindBoxes == null) return;
        int selectedKinds = 0;
        for (int i = 0; i < nearbyKindBoxes.length; i++) {
            if (nearbyKindBoxes[i].isChecked()) selectedKinds |= 1 << i;
            nearbyKindBoxes[i].setEnabled(false);
        }
        final int mask = selectedKinds;
        worker.execute(() -> {
            try { AutomationPreferences.choose(this,"kinds",Integer.toString(mask),()->NearbyGateway.setKinds(mask)); }
            catch (Exception e) { toast("種類設定未更新：" + e.getMessage()); }
            finally {
                refreshNearby();
                mainHandler.postDelayed(() -> refreshNearby(), 6_000L);
            }
        });
    }

    private void refreshNearby() {
        if (nearbyStatus == null || nearbyReadPending) return;
        nearbyReadPending = true;
        worker.execute(() -> {
            try {
                NearbyState state = NearbyGateway.read();
                boolean held = GpsWorkLock.observedHeldBy(this, GpsWorkLock.BATCH)
                        || GpsWorkLock.observedHeldBy(this, GpsWorkLock.FLOWER_FARM)
                        || GpsWorkLock.observedHeldBy(this, GpsWorkLock.JOYSTICK_STARTUP);
                runOnUiThread(() -> {
                    if (nearbyStatus == null) return;
                    boolean restorePending = NearbyWorkState.pending(this);
                    boolean originalOn = NearbyWorkState.originallyArmed(this);
                    setChecked(armedSwitch, restorePending ? originalOn : "on".equals(AutomationPreferences.value(this,"nearby","armed".equals(state.mode)&&!state.busy?"on":"off")));
                    armedSwitch.setEnabled(!restorePending && state.canToggle(held));
                    for (int i = 0; i < nearbyKindBoxes.length; i++) {
                        int savedMask=Integer.parseInt(AutomationPreferences.value(this,"kinds",Integer.toString(state.mask)));
                        setChecked(nearbyKindBoxes[i], (savedMask & (1 << i)) != 0);
                        nearbyKindBoxes[i].setEnabled(true);
                    }
                    nearbyStatus.setText(restorePending
                            ? (originalOn ? "附近派遣暫停中 · 工作安全結束後恢復開啟" : "附近派遣原為關閉 · 工作結束後維持關閉")
                            : CollectionMonitorService.displayNearby(this,state));
                    if (nearbyDetail != null) nearbyDetail.setText(state.summary() + "\n禮物盒僅派遣一般白盒；稀有／未知盒與專屬皮克敏忙碌時略過。");
                });
            } catch (Exception e) {
                runOnUiThread(()->{
                    if(nearbyStatus==null)return;
                    boolean desired=CollectionMonitorService.desired(this,"nearby");
                    setChecked(armedSwitch,desired);
                    armedSwitch.setEnabled(!GpsWorkLock.observedAnyHeld(this)&&!NearbyWorkState.pending(this));
                    nearbyStatus.setText(desired?"已開啟 · 等待核心或遊戲資料，背景持續重試":"已關閉 · 狀態暫時無法讀取");
                });
            }
            finally { nearbyReadPending = false; }
        });
    }

    private void scanBatch() {
        final boolean seed = batchSeed != null && batchSeed.isChecked();
        final boolean fruit = batchFruit != null && batchFruit.isChecked();
        final boolean gift = batchGift != null && batchGift.isChecked();
        if (!seed && !fruit && !gift) {
            showDispatchFeedback("⚠ 請至少選擇一種探險類型。", true);
            return;
        }
        setButtonEnabled(scanButton, false);
        scanning = true;
        batchQueue = new ArrayList<>();
        batchScanned = false;
        scannedKey = "";
        queuePage = 0;
        updateQueueControls();
        renderQueue();
        showDispatchFeedback("正在掃描探險清單…", false);
        dispatchQueueSummary.setText("正在建立批次清單…");
        final double maxMetres = RANGE_METRES[rangeIndex];
        final boolean nearestFirst = sortByDistance;
        final String requestedKey = batchSelection().scanKey();
        final long requestedGeneration = ++scanGeneration;
        worker.execute(() -> {
            try {
                List<DispatchProbeGateway.Candidate> scanned = DispatchProbeGateway.selectedCandidates(seed, fruit, gift, maxMetres, nearestFirst);
                runOnUiThread(() -> {
                    if (!batchSelection().acceptsScan(requestedGeneration, scanGeneration, requestedKey)) return;
                    batchQueue = scanned;
                    batchScanned = true;
                    scannedKey = requestedKey;
                    queuePage = 0;
                    if (dispatchLog == null) return;
                    renderQueue();
                    showDispatchFeedback(scanned.isEmpty()
                            ? "⚠ 沒有找到符合條件的項目。請在 Pikmin 探險頁重新載入後再試。"
                            : "✓ 掃描完成，已建立 " + scanned.size() + " 筆批次清單。", scanned.isEmpty());
                    updateQueueControls();
                });
            } catch (Exception e) {
                showDispatchFeedback("⚠ 無法掃描探險清單：" + e.getMessage(), true);
            } finally {
                runOnUiThread(() -> {
                    if (requestedGeneration != scanGeneration) return;
                    scanning = false;
                    setButtonEnabled(scanButton, true);
                    updateQueueControls();
                });
                refreshDispatch();
            }
        });
    }

    private void startTestBatch() {
        int selectedIndex = batchCount == null ? 0 : batchCount.getSelectedItemPosition();
        int count = Math.min(TEST_COUNTS[Math.max(0, selectedIndex)], batchQueue.size());
        confirmBatchStart(count, "測試");
    }

    private void startBatch() { confirmBatchStart(batchQueue.size(), "全部"); }

    private void confirmBatchStart(int count, String mode) {
        if (!batchSelection().scanKey().equals(scannedKey)) {
            invalidateQueue();
            showDispatchFeedback("條件與清單不一致，請重新掃描。", true);
            return;
        }
        if (count <= 0 || batchQueue.isEmpty()) {
            showDispatchFeedback("⚠ 請先掃描並確認可執行項目。", true);
            return;
        }
        if (!ensureNotificationPermission()) return;
        List<DispatchProbeGateway.Candidate> selectedItems = new ArrayList<>(batchQueue.subList(0, Math.min(count, batchQueue.size())));
        new AlertDialog.Builder(this)
                .setTitle("開始" + mode + "派遣？")
                .setMessage(queueSummary(selectedItems) + "\n\n開始後請讓 Pikmin 保持在探險頁。")
                .setNegativeButton("取消", null)
                .setPositiveButton("開始", (dialog, which) -> startBatchItems(selectedItems))
                .show();
    }

    private boolean ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return true;
        String permission = "android.permission.POST_NOTIFICATIONS";
        if (checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true;
        if (!notificationPermissionPrompted) {
            notificationPermissionPrompted = true;
            requestPermissions(new String[]{permission}, 1);
            showExpeditionFeedback("請允許通知，以便離開 App 後仍能查看工作進度。", false);
            return false;
        }
        showExpeditionFeedback("⚠ 通知未啟用；背景進度可能不會顯示。", true);
        return true;
    }

    private static final String PIKMIN_PACKAGE = "com.nianticlabs.pikmin";

    private Button checkpointButton(String label,String owner) {
        Button button=secondaryButton(label,v -> confirmCheckpoint(owner));
        button.setVisibility(View.GONE);
        final int revision=pageRevision;
        worker.execute(()->{
            boolean pending=false;
            try {
                WorkCheckpoint cp=CheckpointStore.read(this,owner);
                pending=cp.remaining()>0&&!cp.uncertain;
            }catch(Exception unavailable){}
            final boolean show=pending;
            runOnUiThread(()->{if(revision==pageRevision){button.setVisibility(show?View.VISIBLE:View.GONE);button.setEnabled(!gpsWorkBusy());}});
        });
        return button;
    }

    private void confirmCheckpoint(String owner) {
        if(!ensureNotificationPermission())return;
        worker.execute(() -> {
            try {
                WorkCheckpoint cp=CheckpointStore.read(this,owner);
                String reason=cp.reason(SessionIdentity.read(true),System.currentTimeMillis());
                if(!reason.isEmpty())throw new IllegalStateException(reason);
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("接續未完成工作？")
                        .setMessage("剩餘 "+cp.remaining()+" / "+cp.total()+" 項，使用原路線與設定，已確認完成的項目不重做。\n請確認仍為原帳號、花點仍有效。會重新核對定位及清理狀態；未知結果不強制放行。")
                        .setNegativeButton("取消",null).setPositiveButton("確認並接續",(dialog,which) -> worker.execute(() -> {
                            try {
                                if(owner.equals(GpsWorkLock.BATCH))DispatchBatchService.resume(this,cp.id);
                                else FlowerFarmService.resume(this,cp.id);
                                switchToPikminBloom();
                            }catch(Exception error){showExpeditionFeedback("無法接續："+error.getMessage(),true);}
                        })).show());
            }catch(Exception error){showExpeditionFeedback("無法接續："+error.getMessage(),true);}
        });
    }

    private void startBatchItems(List<DispatchProbeGateway.Candidate> items) {
        showDispatchFeedback("正在啟動 " + items.size() + " 筆派遣…", false);
        worker.execute(() -> {
            try {
                DispatchBatchService.start(this, items);
                switchToPikminBloom();
                showDispatchFeedback("✓ 批次已開始，已切換到 Pikmin，請保持在探險頁。", false);
            } catch (Exception e) {
                showDispatchFeedback("⚠ 無法開始批次：" + e.getMessage(), true);
            } finally {
                refreshDispatch();
            }
        });
    }

    // The game must stay in the foreground for its own native scanner to keep
    // rescanning and reporting fresh game-side distances -- when it is
    // backgrounded, every arrival check stalls on "awaiting_native_scan"
    // until the 60 s wait times out (observed 2026-08-30 when the user was
    // left on Control Center's own UI through an entire failed batch).
    // getLaunchIntentForPackage resolves the game's actual exported launcher
    // activity, unlike guessing a component name directly, which the game
    // does not export and refuses with a SecurityException.
    private void switchToPikminBloom() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(PIKMIN_PACKAGE);
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(launch);
    }

    private void stopBatch() {
        showDispatchFeedback("正在安全停止並釋放 GPS…", false);
        DispatchBatchService.stop(this);
        refreshDispatch();
    }
    private void confirmBatchRecovery() {
        new AlertDialog.Builder(this).setTitle("診斷並恢復批次？")
            .setMessage(WorkStatus.summary(this,GpsWorkLock.BATCH,false)+"\n\n檢查並關閉殘留批次控制，再核對遲到的領回收據。回報過期時會暫時切回遊戲，最多等待20秒後返回；不會飛行或重送舊批次。確認停止後交還原附近派遣設定。清單、原失敗與未確認紀錄保留；缺乏完整證據時仍需人工核對。")
            .setNegativeButton("取消",null).setPositiveButton("診斷並恢復",(dialog,which)->{
                setButtonEnabled(batchRecover,false);
                showDispatchFeedback("正在診斷與恢復，不會開始派遣…",false);
                worker.execute(()->{
                    try {
                        String result=BatchRecoveryGateway.checkAndReset(this);
                        runOnUiThread(()->{invalidateQueue();showDispatchFeedback(result,false);});
                    }catch(Exception error){
                        showDispatchFeedback("診斷未完成："+error.getMessage(),true);
                        BatchRecoveryGateway.PendingReview review=BatchRecoveryGateway.review(error);
                        if(review!=null)runOnUiThread(()->confirmBatchReview(review));
                    }
                    finally {refreshDispatch();}
                });
            }).show();
    }
    private void confirmBatchReview(BatchRecoveryGateway.PendingReview review) {
        if(isFinishing()||isDestroyed()||selected!=DISPATCH||expeditionTab!=0)return;
        new AlertDialog.Builder(this).setTitle("仍有派遣結果待核對")
            .setMessage("已停止新增批次派遣，但上一筆可能已出發。請先到遊戲及派遣歷史核對。\n\n只有確認後才按下方按鈕：原始紀錄將封存為未確認（不算成功），不會接續或重送舊批次；恢復後需重新掃描，並交還原附近派遣設定。")
            .setNegativeButton("保留暫停",null).setPositiveButton("我已核對，封存並恢復",(d,w)->worker.execute(()->{
                try {
                    String result=BatchRecoveryGateway.checkAndReset(this,review.id,review.checksum);
                    runOnUiThread(()->{invalidateQueue();showDispatchFeedback(result,false);});
                }catch(Exception e){showDispatchFeedback("恢復未完成："+e.getMessage(),true);}
                finally {refreshDispatch();}
            })).show();
    }

    private void toggleBootJoystick(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        ControlSettings.setStartJoystickOnBoot(this, enabled);
        if (!enabled) JoystickStartupJob.cancelAutomatic(this);
        setChecked(view, enabled);
        showSystemFeedback(enabled
                ? "✓ 下次開機解鎖後啟動 GPS 服務並檢查定位更新；不傳送座標。"
                : "✓ 已關閉開機啟動 GPS JoyStick。", false);
    }

    private void toggleGameGuard(CompoundButton view,boolean enabled) {
        if(refreshing)return;
        if(!enabled) {
            ControlSettings.setRestartGameOnPidLoss(this,false);
            GamePidRecoveryService.sync(this);
            GamePidRecoveryService.forgetBaseline(this);
            showSystemFeedback("✓ 已關閉遊戲程序自動復原；不會再自動重啟遊戲。",false);
            return;
        }
        setChecked(view,false);
        new AlertDialog.Builder(this).setTitle("開啟遊戲程序自動復原？")
                .setMessage("控制中心會顯示常駐通知。只有曾確認過的遊戲程序消失，或新程序長時間未就緒，才會有限次開啟／重啟遊戲；批次與花田會先安全停止，不自動重送未確認操作。")
                .setNegativeButton("取消",null)
                .setPositiveButton("開啟",(dialog,which)->{
                    if(!ControlSettings.setRestartGameOnPidLoss(this,true)) {showSystemFeedback("⚠ 無法保存設定",true);return;}
                    try {GamePidRecoveryService.sync(this);setChecked(gameGuardSwitch,true);
                        showSystemFeedback("✓ 已開啟遊戲程序守護；等待首次確認遊戲 PID。",false);}
                    catch(RuntimeException error) {ControlSettings.setRestartGameOnPidLoss(this,false);
                        showSystemFeedback("⚠ 無法啟動程序守護："+error.getClass().getSimpleName(),true);}
                }).show();
    }

    private AlertDialog flowerFarmConfirmation;
    private Spinner flowerFarmRounds;
    private Spinner flowerFarmMode;
    private LinearLayout flowerStepOptions;
    private TextView flowerFarmEstimate;
    private void confirmRouteHarvest() {
        if(flowerFarmConfirmation!=null)return;
        FarmUi ui=farmPresentation();
        if(!ui.canStart&&!ui.canRecover){showFlowerFeedback(ui.message,true);return;}
        try {
            String raw=flowerFarmPoints.getText().toString().trim();
            final HarvestRoute route=raw.startsWith("<")?HarvestRoute.gpx(raw):HarvestRoute.coordinates(raw);
            final boolean recover=WorkStatus.farmNeedsRecovery(this);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("開始沿路採果？")
                .setMessage(route.points.size()+" 個路線節點 · 約 "+Math.round(route.meters)+" 公尺\n19 km/h · 單趟，保留輸入順序及重複點。\n先自動停止既有種花並關閉結算，首點到達後再開始種花。水果／花苗原生往返≤5分鐘才派遣。\n\n"+flowerFarmEstimate(raw).confirmation())
                .setNegativeButton("取消",null).setPositiveButton("開始",(d,w)->{
                    try {
                        FarmUi latest=farmPresentation();if(!(recover?latest.canRecover:latest.canStart))throw new IllegalStateException(latest.message);
                        Intent game=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                        if(game==null)throw new IllegalStateException("找不到Pikmin");
                        FlowerFarmService.startRoute(this,route,recover);
                        startActivity(game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                    }catch(Exception e){showFlowerFeedback("無法啟動："+e.getMessage(),true);}
                }).create();
            flowerFarmConfirmation=dialog;
            dialog.setOnDismissListener(d->{if(flowerFarmConfirmation==dialog)flowerFarmConfirmation=null;});
            dialog.show();
        }catch(Exception e){showFlowerFeedback("路線格式錯誤："+e.getMessage(),true);}
    }

    private void dismissFlowerFarmConfirmation() {
        if (flowerFarmConfirmation != null) flowerFarmConfirmation.dismiss();
    }

    private void confirmFlowerFarmStart() {
        confirmFlowerFarmStart(WorkStatus.farmNeedsRecovery(this));
    }

    private void confirmFlowerFarmStart(boolean recover) {
        if (flowerFarmConfirmation != null && flowerFarmConfirmation.isShowing()) return;
        final String rawPoints = flowerFarmPoints == null ? "" : flowerFarmPoints.getText().toString();
        final FlowerFarmInput input = normalizeFlowerFarmPoints(rawPoints);
        if (input.points.isEmpty()) {
            showFlowerFeedback("⚠ 找不到有效的花田座標；請使用每行「緯度,經度」。", true);
            return;
        }
        FarmUi currentUi = farmPresentation();
        if (!(recover ? currentUi.canRecover : currentUi.canStart)) {
            showFlowerFeedback(currentUi.message, true);
            return;
        }
        persistFlowerDraft(); // Confirmation never rewrites the original input.
        if (!ensureNotificationPermission()) return;
        final int dwell = flowerFarmDwell != null && flowerFarmDwell.getSelectedItemPosition() == 0 ? 5 : 10;
        String filtered = input.skipped == 0 && input.duplicates == 0 ? ""
                : "\n\n已略過 " + input.skipped + " 筆無效格式"
                + (input.duplicates == 0 ? "。" : "，並合併 " + input.duplicates + " 筆重複座標。");
        AlertDialog dialog = new AlertDialog.Builder(this).create();
        flowerFarmConfirmation = dialog;
        dialog.setOnDismissListener(ignored -> {
            if (flowerFarmConfirmation == dialog) flowerFarmConfirmation = null;
        });
        LinearLayout content = cardBox();
        content.setPadding(dp(24), dp(20), dp(24), dp(20));
        final int rounds = ControlSettings.flowerFarmRounds(this);
        content.addView(gardenState(recover ? "從第 1 點重新開始？" : "開始採果？"));
        content.addView(noteBody(input.points.size() + " 個花點 · " + rounds + " 輪 · 每點 " + dwell + " 秒" + filtered));
        content.addView(noteBody(flowerFarmEstimate(rawPoints).confirmation()));
        content.addView(noteBody(recover
                ? "保留座標與歷史報告。先確認舊工作與種花已停止，再從第 1 點開始。"
                : "首點到達後種花，全程結束再停止；每點不等待水果。"));
        content.addView(noteBody("會先自動停止既有種花並關閉結算；未確認就不移動。過程中勿切換帳號。"));
        content.addView(gardenCaption("Pikmin 需保持開啟；結束不會關閉 GPS JoyStick。"));
        Button confirm = primaryButton(recover ? "確認復原並開始" : "開始", v -> {
            // Re-check after the dialog: background work may have acquired GPS meanwhile.
            FarmUi latest = farmPresentation();
            if (!(recover ? latest.canRecover : latest.canStart)) {
                dialog.dismiss();
                showFlowerFeedback(latest.message, true);
                renderFlowerFarm();
                return;
            }
            dialog.dismiss();
            ControlSettings.setFlowerFarmRounds(this, rounds);
            startFlowerFarm(input.points, dwell, recover);
        });
        content.addView(confirm, margin(0, 12, 0, 0));
        content.addView(secondaryButton("取消", v -> dialog.dismiss()), margin(0, 8, 0, 0));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        dialog.setView(scroll, 0, 0, 0, 0);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(rounded(surface(), Color.TRANSPARENT, 0, dp(20)));
            dialog.getWindow().setLayout(Math.min(dp(420), getResources().getDisplayMetrics().widthPixels - dp(40)), -2);
        }
    }

    private void confirmFarmReset() {
        if(!farmPresentation().canReset){showFlowerFeedback("目前不能重置，請先確認工作已停止。",true);return;}
        new AlertDialog.Builder(this).setTitle("檢查並重置花田狀態？")
                .setMessage("只確認停止種花、路線收尾及附近派遣恢復，不會飛行或開始採果。花點、歷史報告與未確認請求保留。請先讓遊戲停在地圖並保持JoyStick定位服務開啟。")
                .setNegativeButton("取消",null).setPositiveButton("檢查並重置",(d,w)->{
                    try {
                        if(!farmPresentation().canReset)throw new IllegalStateException("工作狀態已改變，不能重置");
                        if(!ensureNotificationPermission())return;
                        FlowerFarmService.checkAndReset(this);
                        Intent game=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                        if(game!=null)startActivity(game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP));
                        showFlowerFeedback("正在檢查並重置；不會開始採果。",false);
                    } catch(Exception e) {showFlowerFeedback("重置未完成："+e.getMessage(),true);}
                }).show();
    }

    private void showSettingsProfiles() {
        new AlertDialog.Builder(this).setTitle("設定組合").setItems(new String[]{"儲存目前設定","選擇已儲存組合","匯入組合檔"},(d,i)->{
            if(i==0){EditText name=new EditText(this);name.setSingleLine(true);name.setHint("組合名稱（同名取代）");name.setTextColor(primary());
                new AlertDialog.Builder(this).setTitle("儲存設定").setMessage("餵食條件、派遣類型、步數目標、花田方式與外觀；不保存功能開關、權限、座標或工作狀態。")
                    .setView(name).setNegativeButton("取消",null).setPositiveButton("儲存",(x,w)->{String profileName=name.getText().toString();worker.execute(()->{
                        try{SettingsProfiles.save(this,SettingsProfiles.capture(this,profileName));showSystemFeedback("已儲存設定組合。",false);}
                        catch(Exception e){showSystemFeedback("未儲存："+e.getMessage(),true);}
                    });}).show();
            }else if(i==2){Intent open=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(open,703);}
            else try{List<SettingsProfile> profiles=SettingsProfiles.list(this);if(profiles.isEmpty()){showSystemFeedback("尚無設定組合。",false);return;}
                String[] names=new String[profiles.size()];for(int n=0;n<names.length;n++)names[n]=profiles.get(n).get("name");
                new AlertDialog.Builder(this).setTitle("選擇組合").setItems(names,(x,n)->{
                    SettingsProfile p=profiles.get(n);
                    new AlertDialog.Builder(this).setTitle(p.get("name")).setItems(new String[]{"套用設定（不啟動）","匯出","刪除"},(z,action)->{
                        if(action==1){pendingProfileExport=p.encode();Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"pikmin-settings-profile.txt");startActivityForResult(create,702);}
                        else if(action==2)new AlertDialog.Builder(this).setMessage("刪除這個組合？不改目前設定。")
                            .setNegativeButton("取消",null).setPositiveButton("刪除",(q,b)->{try{SettingsProfiles.delete(this,p);showSystemFeedback("已刪除組合。",false);}catch(Exception e){showSystemFeedback(e.getMessage(),true);}}).show();
                        else worker.execute(()->{try{
                            String diff=SettingsProfiles.differences(SettingsProfiles.capture(this,"目前設定"),p);
                            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("設定差異").setMessage(diff+"\n請先停止自動化。不啟動功能；跨區保存失敗可重試，不清除紀錄。")
                                .setNegativeButton("取消",null).setPositiveButton("套用",(q,b)->worker.execute(()->{
                                    try{SettingsProfiles.apply(this,p);runOnUiThread(this::recreate);}
                                    catch(Exception e){showSystemFeedback("未完整套用："+e.getMessage()+"；可停止工作後重新套用。",true);}
                                })).show());
                        }catch(Exception e){showSystemFeedback("无法預覽設定差異："+e.getMessage(),true);}});
                    }).show();
                }).show();
            }catch(Exception e){showSystemFeedback("組合庫保留未修改："+e.getMessage(),true);}
        }).setNegativeButton("關閉",null).show();
    }

    private void saveRouteDraft() {
        if (!farmPresentation().canEdit || flowerFarmPoints == null) return;
        try { HarvestRoute.coordinates(flowerFarmPoints.getText().toString()); }
        catch(Exception e){showFlowerFeedback("路線格式有誤；請先修正座標。",true);return;}
        LinearLayout box=cardBox();EditText name=new EditText(this);name.setTextColor(primary());
        name.setHint("路線名稱");name.setHintTextColor(secondary());name.setSingleLine(true);box.addView(name);
        Spinner expiry=spinner(new String[]{"效期未知","剩餘10分鐘","剩餘30分鐘","剩餘60分鐘"},0);box.addView(expiry);
        box.addView(gardenCaption("僅儲存草稿；效期由你確認，不代表仍會產生水果。"));
        new AlertDialog.Builder(this).setTitle("儲存路線").setView(box).setNegativeButton("取消",null)
            .setPositiveButton("儲存",(d,w)->{
                if(!farmPresentation().canEdit)return;
                try { long minutes=new long[]{0,10,30,60}[expiry.getSelectedItemPosition()];
                    SavedRouteStore.save(this,SavedRouteStore.load(this).add(name.getText().toString(),flowerFarmPoints.getText().toString(),System.currentTimeMillis(),minutes));
                    showFlowerFeedback("已儲存路線。",false);
                }catch(Exception e){showFlowerFeedback("無法儲存："+e.getMessage(),true);}
            }).show();
    }

    private void showSavedRoutes() {
        if(!farmPresentation().canEdit)return;
        try { SavedRoutes routes=SavedRouteStore.load(this);
            if(routes.entries.isEmpty()){showFlowerFeedback("路線庫尚無草稿。",false);return;}
            String[] labels=new String[routes.entries.size()];long now=System.currentTimeMillis();
            for(int i=0;i<labels.length;i++)labels[i]=routes.entries.get(i).label(now);
            new AlertDialog.Builder(this).setTitle("路線庫 · 僅載入草稿").setItems(labels,(d,index)->{
                SavedRoutes.Entry entry=routes.entries.get(index);
                new AlertDialog.Builder(this).setTitle(entry.name).setMessage(entry.label(System.currentTimeMillis())+"\n不會自動開始；載入將取代輸入框草稿。")
                    .setNegativeButton("取消",null).setNeutralButton("刪除",(x,w)->{
                        if(!farmPresentation().canEdit)return;
                        try{SavedRouteStore.save(this,SavedRouteStore.load(this).remove(entry.id));showFlowerFeedback("已刪除此路線。",false);}
                        catch(Exception e){showFlowerFeedback("路線庫未修改："+e.getMessage(),true);}
                    }).setPositiveButton("載入",(x,w)->{
                        if(!farmPresentation().canEdit||flowerFarmPoints==null)return;
                        flowerFarmPoints.setText(entry.coordinates);persistFlowerDraft();updateFlowerPreview();
                        showFlowerFeedback(entry.expires>0&&System.currentTimeMillis()>=entry.expires?"已載入過期草稿；請確認有效性後再開始。":"已載入草稿，請自行確認花點仍有效。",false);
                    }).show();
            }).setNegativeButton("關閉",null).show();
        }catch(Exception e){showFlowerFeedback("路線庫無法讀取，保留原檔："+e.getMessage(),true);}
    }

    private void clearFlowerFarmPoints() {
        if (!farmPresentation().canEdit) return;
        if (flowerFarmPoints == null || flowerFarmPoints.length() == 0) return;
        clearedFlowerDraft = flowerFarmPoints.getText().toString();
        if (flowerFarmPoints != null) flowerFarmPoints.setText("");
        persistFlowerDraft();
        updateFlowerPreview();
        flowerFarmUndo.setVisibility(View.VISIBLE);
        showFlowerFeedback("已清除座標，可復原。", false);
    }

    private void undoFlowerFarmClear() {
        if (!farmPresentation().canEdit) return;
        if (flowerFarmPoints == null || clearedFlowerDraft == null) return;
        if (flowerFarmPoints.length() > 0) {
            showFlowerFeedback("輸入框已有新內容；請先自行保留，復原不會覆寫新草稿。", true);
            return;
        }
        flowerFarmPoints.setText(clearedFlowerDraft);
        clearedFlowerDraft = null;
        flowerFarmUndo.setVisibility(View.GONE);
        persistFlowerDraft();
        updateFlowerPreview();
        showFlowerFeedback("✓ 已復原座標草稿。", false);
    }

    private void persistFlowerDraft() {
        mainHandler.removeCallbacks(saveFlowerDraft);
        if (flowerFarmPoints == null || flowerFarmDwell == null) return;
        ControlSettings.setFlowerFarm(this, flowerFarmPoints.getText().toString(),
                flowerFarmDwell.getSelectedItemPosition() == 0 ? 5 : 10);
    }

    private void updateFlowerPreview() {
        if (flowerFarmPoints == null || flowerFarmPreview == null) return;
        FlowerFarmDraft draft = new FlowerFarmDraft(flowerFarmPoints.getText().toString());
        flowerFarmPreview.setText(draft.summary());
        if (flowerFarmIssues != null) flowerFarmIssues.setText(draft.issues.isEmpty()
                ? "沒有無效或重複的座標行。" : draft.issues);
        renderFlowerFarm();
    }

    private FarmUi farmPresentation() {
        String blocker = "";
        if (SetupDeployment.pending(this)) blocker = "裝置部署待核對 · 到系統繼續";
        else if (UpdateInstaller.blocking(this)) blocker = "更新準備／安裝中 · 到系統完成或取消";
        else if (WorkStatus.stoppingAll) blocker = "停止全部中 · 等待確認";
        else if (!WorkStatus.allError(this).isEmpty()
                || "stop-incomplete".equals(WorkStatus.state(this, GpsWorkLock.BATCH)))
            blocker = "停止未完成 · 到總覽重試停止全部";
        else if (DispatchBatchService.isRunning() || GpsWorkLock.observedHeldBy(this, GpsWorkLock.BATCH))
            blocker = "批次探險使用 GPS 中";
        else if (WorkProgress.active(WorkStatus.state(this, GpsWorkLock.BATCH)))
            blocker = "批次工作中斷 · 到總覽確認停止";
        else if (GpsWorkLock.observedHeldBy(this, GpsWorkLock.JOYSTICK_STARTUP)) blocker = "GPS 啟動檢查中";
        boolean hasPoints = flowerFarmPoints != null
                && !new FlowerFarmDraft(flowerFarmPoints.getText().toString()).points.isEmpty();
        return new FarmUi(WorkStatus.state(this, GpsWorkLock.FLOWER_FARM), FlowerFarmService.isRunning(),
                GpsWorkLock.observedHeldBy(this, GpsWorkLock.FLOWER_FARM), gpsWorkBusy() || !blocker.isEmpty(),
                GpsWorkLock.farmRecoveryAvailable(this), blocker, hasPoints, flowerFarmEditingRecovery, WorkStatus.farmNeedsRecovery(this));
    }

    private FlowerFarmEstimate flowerFarmEstimate(String raw) {
        int dwell = flowerFarmDwell == null ? ControlSettings.flowerFarmDwell(this)
                : flowerFarmDwell.getSelectedItemPosition() == 0 ? 5 : 10;
        return new FlowerFarmEstimate(raw, ControlSettings.flowerFarmRounds(this), dwell);
    }

    private void updateFlowerEstimate() {
        if (flowerFarmEstimate != null && flowerFarmPoints != null)
            flowerFarmEstimate.setText(flowerFarmEstimate(flowerFarmPoints.getText().toString()).comparison());
    }

    private void renderFlowerFarm() {
        if (flowerFarmPoints == null || flowerFarmStart == null) return;
        FarmUi ui = farmPresentation();
        flowerFarmNotice.setVisibility(ui.showNotice ? View.VISIBLE : View.GONE);
        flowerFarmNotice.setBackground(rounded(FlowerFarmService.isRunning() && !ui.warning
                ? selectedSurface() : surface(), ui.warning ? danger() : Color.TRANSPARENT, ui.warning ? 1 : 0, dp(20)));
        flowerFarmTitle.setText(ui.title);
        flowerFarmStatus.setText(ui.message);
        flowerFarmStatus.setTextColor(ui.warning ? danger() : primary());
        flowerFarmHint.setText(ui.detail);
        if(FlowerFarmService.isCheckingReset()) {
            flowerFarmTitle.setText("正在檢查並重置");flowerFarmStatus.setText("不會開始採果");
            flowerFarmHint.setText("確認停止與交還後解除復原提示，原紀錄保留。");
        }
        flowerFarmEditor.setVisibility(ui.showEditor ? View.VISIBLE : View.GONE);
        flowerFarmEditActions.setVisibility(ui.showEditor ? View.VISIBLE : View.GONE);
        flowerFarmDwellBox.setVisibility(ui.showEditor ? View.VISIBLE : View.GONE);
        flowerStepOptions.setVisibility(ControlSettings.routeHarvest(this)?View.GONE:View.VISIBLE);
        updateFlowerEstimate();
        flowerFarmMode.setEnabled(ui.canEdit);
        flowerFarmPoints.setEnabled(ui.canEdit);
        flowerFarmDwell.setEnabled(ui.canEdit);
        if (flowerFarmRounds != null) flowerFarmRounds.setEnabled(ui.canEdit);
        setButtonEnabled(flowerFarmExpand, ui.canEdit);
        setButtonEnabled(flowerFarmClear, ui.canEdit && flowerFarmPoints.length() > 0);
        flowerFarmUndo.setVisibility(ui.showEditor && clearedFlowerDraft != null ? View.VISIBLE : View.GONE);
        setButtonEnabled(flowerFarmUndo, ui.canEdit);
        flowerFarmSaved.setVisibility(!ui.showEditor || ui.recovery ? View.VISIBLE : View.GONE);
        FlowerFarmDraft draft = new FlowerFarmDraft(flowerFarmPoints.getText().toString());
        // A saved draft is not evidence of a running service's captured route.
        flowerFarmSavedSummary.setText(FlowerFarmService.isRunning()
                ? "使用啟動時的路線與停留設定"
                : "保留 " + draft.points.size() + " 個花點 · " + (ControlSettings.routeHarvest(this)?"沿路單趟":ControlSettings.flowerFarmRounds(this) + " 輪"));
        flowerFarmEditSaved.setVisibility(ui.recovery ? View.VISIBLE : View.GONE);
        flowerFarmEditSaved.setText(ui.showEditor ? "收合編輯" : "編輯花點");
        setButtonEnabled(flowerFarmEditSaved, ui.canEdit);
        flowerFarmStart.setVisibility(FlowerFarmService.isRunning() || ui.recovery ? View.GONE : View.VISIBLE);
        flowerFarmRecover.setVisibility(ui.recovery ? View.VISIBLE : View.GONE);
        setButtonEnabled(flowerFarmStart, ui.canStart);
        setButtonEnabled(flowerFarmRecover, ui.canRecover);
        flowerFarmReset.setVisibility(ui.recovery ? View.VISIBLE : View.GONE);
        setButtonEnabled(flowerFarmReset, ui.canReset);
        updateSafetyBar(FlowerFarmService.isRunning(), GpsWorkLock.FLOWER_FARM, flowerFarmStop);
    }

    private void startFlowerFarm(List<FlowerFarmService.Point> points, int dwell) {
        startFlowerFarm(points, dwell, false);
    }

    private void startFlowerFarm(List<FlowerFarmService.Point> points, int dwell, boolean recover) {
        showFlowerFeedback("正在啟動花田採果…", false);
        // Service creation and foreground launch share the main queue. No root work here.
        runOnUiThread(() -> {
            boolean accepted = false;
            try {
                Intent game = getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                if (game == null) throw new IllegalStateException("找不到 Pikmin App");
                FlowerFarmService.start(this, points, dwell, recover);
                accepted = true;
                game.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                showFlowerFeedback("啟動中，正在等待遊戲定位。", false);
                startActivity(game);
            } catch (Exception e) {
                if (accepted) FlowerFarmService.stop(this);
                showFlowerFeedback("⚠ 無法開始花田採果：" + e.getMessage(), true);
            } finally { refreshDispatch(); }
        });
    }

    private void stopFlowerFarm() {
        FlowerFarmService.stop(this);
        showFlowerFeedback("✓ 已要求安全停止花田採果；GPS JoyStick App 不會被關閉。", false);
        refreshDispatch();
    }

    private FlowerFarmInput normalizeFlowerFarmPoints(String input) {
        ArrayList<FlowerFarmService.Point> points = new ArrayList<>();
        FlowerFarmDraft draft = new FlowerFarmDraft(input);
        for (double[] point : draft.points) points.add(new FlowerFarmService.Point(point[0], point[1]));
        return new FlowerFarmInput(points, draft.normalized, draft.skipped, draft.duplicates);
    }

    private static final class FlowerFarmInput {
        final List<FlowerFarmService.Point> points;
        final String normalized;
        final int skipped, duplicates;
        FlowerFarmInput(List<FlowerFarmService.Point> points, String normalized, int skipped, int duplicates) {
            this.points = points; this.normalized = normalized; this.skipped = skipped; this.duplicates = duplicates;
        }
    }

    private void exportDiagnostics() {
        if (exportingDiagnostic) { toast("診斷包收集中…"); return; }
        CheckBox raw = new CheckBox(this);
        raw.setText("包含原始紀錄（含座標與任務資料）");
        raw.setPadding(dp(20), dp(12), dp(20), dp(12));
        new AlertDialog.Builder(this).setTitle("匯出診斷包")
                .setMessage("預設只收集環境摘要。完成後可預覽並另存檔案，不會自動上傳。")
                .setView(raw).setNegativeButton("取消", null)
                .setPositiveButton("產生預覽", (d, w) -> collectDiagnostics(raw.isChecked())).show();
    }

    private void showFlowerReport(boolean refresh) {
        worker.execute(() -> {
            String report = FarmReports.formatted(this, refresh);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                TextView text = body(report); text.setTextIsSelectable(true);
                text.setPadding(dp(16), dp(12), dp(16), dp(12));
                ScrollView scroll = new ScrollView(this); scroll.addView(text);
                new AlertDialog.Builder(this).setTitle("採果結果 · 最近20次")
                        .setView(scroll).setNegativeButton("關閉", null)
                        .setPositiveButton("更新領取", (d, w) -> showFlowerReport(true)).show();
            });
        });
    }

    private void collectDiagnostics(boolean raw) {
        exportingDiagnostic = true;
        showSystemFeedback("正在收集診斷資料…", false);
        worker.execute(() -> {
            try {
                java.io.File f = DiagnosticsExporter.export(this, raw);
                String preview = DiagnosticsExporter.preview(f);
                runOnUiThread(() -> {
                    exportingDiagnostic = false;
                    if (isFinishing() || isDestroyed()) return;
                    pendingDiagnostic = f;
                    TextView text = body(preview); text.setTextIsSelectable(true);
                    text.setPadding(dp(16), dp(12), dp(16), dp(12));
                    ScrollView scroll = new ScrollView(this); scroll.addView(text);
                    new AlertDialog.Builder(this).setTitle(raw ? "原始診斷預覽 · 含私人資料" : "環境摘要預覽")
                            .setView(scroll).setNegativeButton("關閉", null)
                            .setPositiveButton("另存檔案", (d, w) -> {
                                Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain")
                                        .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, f.getName());
                                try { startActivityForResult(save, 701); }
                                catch (Exception e) { showSystemFeedback("⚠ 找不到檔案儲存工具", true); }
                            }).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> exportingDiagnostic = false);
                showSystemFeedback("⚠ 匯出失敗：" + e.getMessage(), true);
            }
        });
    }

    private void setReturnMode(String mode) {
        showInline(returnStatus, "正在更新返程自動領取…", false);
        worker.execute(() -> {
            try { AutomationPreferences.chooseCollection(this,"reward",!"off".equals(mode)); }
            catch (Exception e) { showInline(returnStatus, "⚠ 無法更新返程設定：" + e.getMessage(), true); }
            finally { refreshReturn(); }
        });
    }

    private void confirmStopAll() {
        new AlertDialog.Builder(this)
                .setTitle("停止全部自動化？")
                .setMessage("將關閉步數、餵食、精華、返程領取與育苗，停止批次與花田採果並釋放 GPS 控制。")
                .setNegativeButton("取消", null)
                .setPositiveButton("停止全部", (dialog, which) -> stopAll())
                .show();
    }

    private void stopAll() {
        stopAll(null);
    }
    private void stopAll(Runnable afterStopped) {
        stopAll(afterStopped,null);
    }
    private void stopAll(Runnable afterStopped,Runnable afterFailure) {
        if (WorkStatus.stoppingAll) { toast("仍在等待停止確認"); return; }
        WorkStatus.stoppingAll = true;
        toast("停止中，正在等待工作結束與控制檔確認");
        DispatchBatchService.stop(this);
        FlowerFarmService.stop(this);
        worker.execute(() -> {
            StringBuilder errors = new StringBuilder();
            StopChecks.attempt(errors, "步數", () -> {
                if(afterStopped!=null){StepService.pauseForInstall(this);return;}
                StepView stopped = StepHealthGateway.engine(getApplicationContext()).stop();
                StepService.signalStop(getApplicationContext());
                StepSyncJob.cancel(getApplicationContext());
                if (StepView.STORE_ERROR.equals(stopped.phase)) throw new IllegalStateException(stopped.detail);
            });
            if(afterStopped==null)StopChecks.attempt(errors,"保存關閉設定",()->AutomationPreferences.stopChoices(this));
            StopChecks.attempt(errors, "取消附近派遣自動恢復", () -> NearbyWorkState.discard(this));
            StopChecks.attempt(errors, "精華", () -> NectarModuleGateway.setAutomatic(false));
            StopChecks.attempt(errors, "餵食", () -> {
                if(afterStopped==null)FeedingGateway.stop(this);else FeedingGateway.pauseForInstall(this);
            });
            StopChecks.attempt(errors, "領取", () -> ReturnRewardModuleGateway.setMode("off"));
            StopChecks.attempt(errors, "育苗", () -> {
                if(afterStopped==null)PlanterSettings.choose(this,"off");
                PlanterGateway.setMode("off");
            });
            StopChecks.attempt(errors, "派遣", DispatchProbeGateway::stopBatch);
            // Never race final cleanup with a worker that could still re-arm.
            long deadline = android.os.SystemClock.elapsedRealtime() + 25_000;
            while ((DispatchBatchService.isRunning() || FlowerFarmService.isRunning())
                    && android.os.SystemClock.elapsedRealtime() < deadline) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (DispatchBatchService.isRunning() || FlowerFarmService.isRunning()) {
                errors.append("；工作仍在停止中，尚未確認");
            } else {
                StopChecks.attempt(errors, "派遣最終清理", DispatchProbeGateway::stopBatch);
                StopChecks.attempt(errors, "花田派遣", () -> DispatchProbeGateway.setFarmArmed(false));
                StopChecks.attempt(errors, "自動換花", () -> AutoPetalGateway.disable());
                StopChecks.attempt(errors, "種花", () -> PlantingControlGateway.setEnabled(false));
                StopChecks.attempt(errors, "花田模式", () -> PlantingControlGateway.setFlowerFarmSession(false));
                StopChecks.attempt(errors, "停止回讀", StopChecks::all);
            }
            if (errors.length() == 0) {
                WorkStatus.recovered(this, GpsWorkLock.BATCH);
                WorkStatus.recovered(this, GpsWorkLock.FLOWER_FARM);
                GpsWorkLock.release(this, GpsWorkLock.BATCH);
                GpsWorkLock.release(this, GpsWorkLock.FLOWER_FARM);
            }
            String message = errors.length() == 0
                    ? "已停止新增自動操作，控制檔已確認。已送出的遊戲請求仍可能完成；GPS JoyStick 不會關閉。"
                    : "停止未完成：" + errors + "\n請稍後重試停止全部。";
            WorkStatus.allResult(this, errors.toString());
            WorkStatus.stoppingAll = false;
            if(afterStopped!=null && errors.length()==0){runOnUiThread(afterStopped);return;}
            if(afterFailure!=null)runOnUiThread(afterFailure);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                new AlertDialog.Builder(this).setTitle(errors.length() == 0 ? "停止控制已確認" : "停止未完成")
                        .setMessage(message).setPositiveButton("知道了", null).show();
                refreshVisible();
            });
        });
    }

    private void refreshVisible() {
        if (selected == OVERVIEW) refreshOverview();
        else if (selected == NECTAR) refreshNectar();
        else if (selected == RETURN) refreshReturn();
        else if (selected == DISPATCH) refreshDispatch();
        else if (selected == STEPS) { if (stepPanel != null) stepPanel.refresh(); }
        else refreshSystem();
    }

    private void refreshOverview() {
        if (overviewText == null) return;
        final int revision = pageRevision;
        worker.execute(() -> {
            try {
                String owner = gpsOwnerSummary();
                String batch = WorkStatus.lastCounts(this, GpsWorkLock.BATCH, DispatchBatchService.isRunning());
                String farm = WorkStatus.lastCounts(this, GpsWorkLock.FLOWER_FARM, FlowerFarmService.isRunning());
                String error = WorkStatus.allError(this);
                String work = WorkStatus.stoppingAll ? "停止中 · 等待確認"
                        : !error.isEmpty() ? "停止未完成 · 請重試停止全部" : owner;
                runOnUiThread(() -> {
                    if (pageRevision != revision || overviewWork == null) return;
                    overviewWork.setText(work);
                    overviewWork.setTextColor(error.isEmpty() ? primary() : danger());
                    overviewBatch.setText("批次 · " + batch);
                    overviewFarm.setText("花田 · " + farm);
                    overviewBatch.setTextColor(workColor(GpsWorkLock.BATCH, DispatchBatchService.isRunning()));
                    overviewFarm.setTextColor(workColor(GpsWorkLock.FLOWER_FARM, FlowerFarmService.isRunning()));
                });
            } catch (Exception e) { showInline(overviewWork, "工作摘要讀取失敗：" + e.getMessage(), true); }
            try {
                String nearby = CollectionMonitorService.displayNearby(this,NearbyGateway.read());
                runOnUiThread(() -> {
                    if (pageRevision == revision && overviewNearby != null) overviewNearby.setText(nearby);
                });
            } catch (Exception e) { showInline(overviewNearby, "附近派遣讀取失敗：" + e.getMessage(), true); }
            try {
                PlanterState p = PlanterGateway.read();
                String planter = UiSummary.planter(p);
                runOnUiThread(() -> {
                    if (pageRevision == revision && overviewPlanter != null) {
                        overviewPlanter.setText(planter);
                        overviewPlanter.setTextColor(planter.contains("暫停") || planter.contains("不相容") ? danger() : primary());
                        overviewPlanterDetail.setText(UiSummary.planterDetail(p));
                    }
                });
            } catch (Exception e) { showInline(overviewPlanter, "育苗摘要讀取失敗：" + e.getMessage(), true); }
        });
        worker.execute(() -> {
            try {
                NectarModuleGateway.Snapshot n = NectarModuleGateway.read();
                runOnUiThread(() -> {
                    if (pageRevision != revision || overviewText == null) return;
                    overviewText.setText(CollectionMonitorService.display(this,"nectar",
                        !n.automatic()?"等待同步設定":UiSummary.nectar(n).replace("已開啟 · ","")));
                });
            } catch (Exception e) {
                showInline(overviewText, "⚠ 精華狀態讀取失敗：" + e.getMessage(), true);
            }
            try {
                boolean compatible = ReturnRewardModuleGateway.compatible();
                String mode = ReturnRewardModuleGateway.mode();
                String returnState = CollectionMonitorService.display(this,"reward",
                        !compatible?"等待遊戲相容性資訊":("all".equals(mode)||"batch".equals(mode))?"持續監控":"等待同步設定");
                runOnUiThread(() -> {
                    if (pageRevision != revision || overviewText == null) return;
                    overviewReturn.setText(returnState);
                    overviewReturn.setTextColor(compatible ? secondary() : danger());
                });
            } catch (Exception e) {
                showInline(overviewReturn, "領取狀態讀取失敗：" + e.getMessage(), true);
            }
            String nectarStats,dispatchSummary;
            try { nectarStats=NectarModuleGateway.claimStats(); }
            catch(Exception e) { nectarStats="統計讀取失敗（不代表自動收取失敗）："+e.getMessage(); }
            try { dispatchSummary=DispatchProbeGateway.dispatchStats(); }
            catch(Exception e) { dispatchSummary="統計讀取失敗："+e.getMessage(); }
            final String stats="大花精華\n"+nectarStats+"\n\n批次探險\n"+dispatchSummary;
            runOnUiThread(() -> {
                if(pageRevision==revision&&overviewStats!=null)overviewStats.setText(stats);
            });
        });
    }

    private void refreshNectar() {
        if (feedingPanel != null) feedingPanel.refresh();
        if (nectarStatus == null) return;
        final int revision = pageRevision;
        final DisplayRefreshGate.Ticket ticket=displayRefresh.begin(DisplayReadCoalescer.Key.NECTAR,ReadOnlyQueryPolicy.enabled(this));
        if(ticket==null)return;
        worker.execute(() -> {
            try {
                if(!displayRefresh.current(ticket))return;
                NectarModuleGateway.Snapshot n; String log;
                if(ReadOnlyQueryPolicy.enabled(this)) {
                    NectarModuleGateway.Display display=NectarModuleGateway.display();
                    n=display.snapshot;log=display.history;
                } else { n=NectarModuleGateway.read();log=NectarModuleGateway.recentClaimsFormatted(); }
                runOnUiThread(() -> {
                    if (nectarStatus == null || revision != pageRevision || !displayRefresh.current(ticket)) return;
                    setChecked(nectarSwitch, "on".equals(AutomationPreferences.value(this,"nectar",n.automatic()?"on":"off")));
                    nectarSwitch.setEnabled(true);
                    nectarStatus.setText(CollectionMonitorService.display(this,"nectar",
                        !n.automatic()?"等待同步設定":UiSummary.nectar(n).replace("已開啟 · ","")));
                    nectarDetail.setText("上次完整回報\n" + n.summary());
                    nectarStatus.setTextColor(n.modulePresent ? secondary() : danger());
                    nectarLog.setText(log.isEmpty() ? "尚無精華收取紀錄。" : log);
                });
            } catch (Exception e) {
                runOnUiThread(()->{if(revision!=pageRevision||nectarSwitch==null||!displayRefresh.current(ticket))return;
                    setChecked(nectarSwitch,CollectionMonitorService.desired(this,"nectar"));nectarSwitch.setEnabled(true);
                    nectarStatus.setText(CollectionPreference.summary(CollectionMonitorService.desired(this,"nectar"),"暫時讀不到核心資料，持續等待",""));});
            } finally {displayRefresh.finish(ticket);}
        });
    }

    private void refreshReturn() {
        if (returnStatus == null) return;
        final int revision = pageRevision;
        final DisplayRefreshGate.Ticket ticket=displayRefresh.begin(DisplayReadCoalescer.Key.RETURN,ReadOnlyQueryPolicy.enabled(this));
        if(ticket==null)return;
        refreshNearby();
        refreshPlanter();
        worker.execute(() -> {
            try {
                if(!displayRefresh.current(ticket))return;
                String mode,detail,statusSummary; boolean compatible,discard;
                if(ReadOnlyQueryPolicy.enabled(this)) {
                    ReturnRewardModuleGateway.Display display=ReturnRewardModuleGateway.display();
                    mode=display.mode;compatible=display.compatible;discard=display.discard;
                    detail=display.history;statusSummary=display.status;
                } else {
                    mode=ReturnRewardModuleGateway.mode();compatible=ReturnRewardModuleGateway.compatible();
                    discard="discard".equals(ReturnRewardModuleGateway.postcardPolicy());
                    detail=ReturnRewardModuleGateway.recentDetailedEvents();statusSummary=ReturnRewardModuleGateway.statusSummary();
                }
                String status = (compatible ? "✓ 遊戲版本相容" : "等待遊戲相容性資訊，設定保留")
                        + "\n自動領取：" + ("all".equals(mode) || "batch".equals(mode) ? "已開啟" : "已關閉")
                        + "\n" + statusSummary.replace("；明信片：keep", "").replace("；明信片：discard", "");
                runOnUiThread(() -> {
                    if (returnStatus == null || revision != pageRevision || !displayRefresh.current(ticket)) return;
                    boolean active = "all".equals(mode) || "batch".equals(mode);
                    setChecked(returnSwitch, "all".equals(AutomationPreferences.value(this,"reward",active?"all":"off")));
                    setChecked(postcardSwitch, "discard".equals(AutomationPreferences.value(this,"postcard",discard?"discard":"keep")));
                    returnSwitch.setEnabled(true);
                    postcardSwitch.setEnabled(compatible);
                    returnStatus.setText(CollectionMonitorService.display(this,"reward",
                        !compatible?"等待遊戲相容性資訊":!active?"等待同步設定":UiSummary.reward(true,true,status).replace("已開啟 · ","")));
                    returnDetail.setText(status);
                    returnStatus.setTextColor(compatible ? primary() : danger());
                    returnLog.setText(detail.isEmpty() ? "尚無返程收取紀錄。" : detail);
                });
            } catch (Exception e) {
                runOnUiThread(()->{if(revision!=pageRevision||returnSwitch==null||!displayRefresh.current(ticket))return;
                    setChecked(returnSwitch,CollectionMonitorService.desired(this,"reward"));returnSwitch.setEnabled(true);
                    returnStatus.setText(CollectionPreference.summary(CollectionMonitorService.desired(this,"reward"),"暫時讀不到核心資料，持續等待",""));});
            } finally {displayRefresh.finish(ticket);}
        });
    }

    private void openPlanter() {
        showPage(RETURN);
        page.post(() -> {
            if (planterSwitch != null && page.getParent() instanceof ScrollView) {
                View card = (View) planterSwitch.getParent();
                ((ScrollView) page.getParent()).smoothScrollTo(0, Math.max(0, card.getTop() - dp(40)));
            }
        });
    }

    private void togglePlanter(CompoundButton view, boolean enabled) {
        if (refreshing) return;
        final String mode = view == planterSwitch && !enabled ? "off"
                : (view == planterRefillSwitch ? enabled : planterRefillSwitch.isChecked()) ? "auto" : "pluck";
        planterSwitch.setEnabled(false);
        planterRefillSwitch.setEnabled(false);
        worker.execute(() -> {
            try { PlanterSettings.choose(this,mode); PlanterSettings.reconcile(this); }
            catch (Exception e) { toast("育苗設定未更新：" + e.getMessage()); }
            finally { refreshPlanter(); }
        });
    }

    private void togglePlanterSteps(CompoundButton view, boolean enabled) {
        if (refreshing || planterStepBoxes == null) return;
        int selectedSteps = 0;
        for (int i = 0; i < planterStepBoxes.length; i++) {
            if (planterStepBoxes[i].isChecked()) selectedSteps |= 1 << i;
            planterStepBoxes[i].setEnabled(false);
        }
        final int mask = selectedSteps;
        worker.execute(() -> {
            try { PlanterSettings.chooseSteps(this,mask); PlanterGateway.setSteps(mask); }
            catch (Exception e) { toast("補種種類未更新：" + e.getMessage()); }
            finally { refreshPlanter(); }
        });
    }

    private void editPlanterSteps() {
        if(planterStepBoxes==null)return;
        boolean[] draft=new boolean[planterStepBoxes.length];
        for(int i=0;i<draft.length;i++)draft[i]=planterStepBoxes[i].isChecked();
        new AlertDialog.Builder(this).setTitle("自動補種種類")
                .setMultiChoiceItems(PlanterSteps.LABELS,draft,(d,i,on)->draft[i]=on)
                .setNegativeButton("取消",null).setPositiveButton("儲存",(d,w)->{
                    int chosen=0;for(int i=0;i<draft.length;i++)if(draft[i])chosen|=1<<i;
                    final int mask=chosen;
                    worker.execute(()->{
                        try{PlanterSettings.chooseSteps(this,mask);PlanterGateway.setSteps(mask);}
                        catch(Exception e){toast("設定尚未套用："+e.getMessage());}
                        finally{refreshPlanter();}
                    });
                }).show();
    }

    private void refreshPlanter() {
        for(int group=0;group<DisplayOptionsGateway.COUNT;group++)refreshDisplayOption(group);
        if (planterStatus == null) return;
        final int revision = pageRevision;
        worker.execute(() -> {
            try {
                PlanterGateway.Snapshot observed = PlanterGateway.snapshot();
                PlanterState state = observed.state;
                if (state.fresh && !"off".equals(PlanterSettings.desired(this)) && state.anomaly)
                    AutomationAlerts.record(this,"nursery","nursery");
                else if (state.fresh) AutomationAlerts.resolve(this,"nursery");
                int steps = PlanterSettings.steps(this,PlanterGateway.steps());
                runOnUiThread(() -> {
                    if (planterStatus == null || revision != pageRevision) return;
                    String desired=PlanterSettings.desired(this);
                    boolean active = !"off".equals(desired);
                    setChecked(planterSwitch, active);
                    setChecked(planterRefillSwitch, "auto".equals(desired));
                    planterSwitch.setEnabled(true);
                    planterRefillSwitch.setEnabled(true);
                    for (int i = 0; i < planterStepBoxes.length; i++) {
                        setChecked(planterStepBoxes[i], (steps & (1 << i)) != 0);
                        planterStepBoxes[i].setEnabled(true);
                    }
                    planterStatus.setText(PlanterSettings.message(this));
                    planterDetail.setText(state.summary + (state.fresh && state.supportsSteps && state.observedSteps != steps
                            ? "\n補種種類已儲存，等待遊戲套用。" : ""));
                    renderPlanterSlots(state);
                    planterLog.setText(state.history);
                });
            } catch (Exception e) {
                showInline(planterStatus, "等待新的育苗回報，稍後自動重試：" + e.getMessage(), false);
                runOnUiThread(() -> {
                    if(revision!=pageRevision)return;
                    if (planterSwitch != null) planterSwitch.setEnabled(true);
                    if (planterRefillSwitch != null) planterRefillSwitch.setEnabled(true);
                    if (planterStepBoxes != null) for(CheckBox box:planterStepBoxes)box.setEnabled(true);
                });
            }
        });
    }

    private void toggleDisplayOption(int group,CompoundButton view,boolean enabled) {
        if(refreshing||displayBusy[group])return;
        displayBusy[group]=true;++displayGenerations[group];view.setEnabled(false);
        worker.execute(()->{
            String failure="";
            try{DisplayOptionsGateway.set(group,enabled);}
            catch(Exception e){failure="顯示設定未確認："+e.getMessage();}
            final String message=failure;
            runOnUiThread(()->{
                displayBusy[group]=false;
                if(!message.isEmpty())toast(message);
                refreshDisplayOption(group);
            });
        });
    }

    private void refreshDisplayOption(int group) {
        if(displayStatuses[group]==null||displayBusy[group])return;
        final int revision=pageRevision,generation=++displayGenerations[group];
        worker.execute(()->{
            try{
                DisplayOptionsState state=DisplayOptionsGateway.read(group);
                runOnUiThread(()->{
                    if(isDestroyed()||revision!=pageRevision||generation!=displayGenerations[group]
                            ||displaySwitches[group]==null||displayBusy[group])return;
                    setChecked(displaySwitches[group],state.desired);
                    displaySwitches[group].setEnabled(true);
                    displayStatuses[group].setText(state.message);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(isDestroyed()||revision!=pageRevision||generation!=displayGenerations[group]
                            ||displaySwitches[group]==null||displayBusy[group])return;
                    displaySwitches[group].setEnabled(false);
                    displayStatuses[group].setText("顯示設定無法核對，稍後重試："+e.getMessage());
                });
            }
        });
    }

    private void refreshSystem() {
        refreshOverlaySetting();
        refreshBootJoystickSetting();
        if(gameGuardSwitch!=null){setChecked(gameGuardSwitch,ControlSettings.restartGameOnPidLoss(this));gameGuardSwitch.setEnabled(true);}
        if(gameGuardStatus!=null)gameGuardStatus.setText(GamePidRecoveryService.status(this));
        refreshGps();
        refreshHealth();
    }

    private String appVersion() {
        try {
            android.content.pm.PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + "（" + info.getLongVersionCode() + "）";
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private void refreshBootJoystickSetting() {
        if (bootJoystickSwitch == null) return;
        setChecked(bootJoystickSwitch, ControlSettings.startJoystickOnBoot(this));
        bootJoystickSwitch.setEnabled(true);
    }

    private void updateJoystickStatus() {
        if (joystickStartupStatus == null) return;
        String report = JoystickStartupJob.status(this);
        joystickStartupStatus.setText(report);
        if (joystickStartupSummary != null) joystickStartupSummary.setText(UiSummary.startup(report));
    }

    private void refreshOverlaySetting() {
        if (overlaySwitch == null || pendingOverlayEnable) return;
        boolean granted = Settings.canDrawOverlays(this);
        boolean enabled = ControlSettings.dispatchOverlayEnabled(this) && granted;
        if (!granted && ControlSettings.dispatchOverlayEnabled(this))
            ControlSettings.setDispatchOverlayEnabled(this, false);
        setChecked(overlaySwitch, enabled);
        overlaySwitch.setEnabled(true);
        if (overlayStatus != null) {
            overlayStatus.setText(enabled ? "批次／花田執行時顯示"
                    : granted ? "已關閉" : "啟用時需允許懸浮權限");
            overlayStatus.setTextColor(secondary());
        }
    }

    private void refreshGps() {
        if (gpsStatus == null) return;
        final int revision = pageRevision;
        worker.execute(() -> {
            try {
                GpsModuleGateway.Snapshot s = GpsModuleGateway.read(this);
                String readiness = s.joystickInstalled ? GpsModuleGateway.readiness(this) : "尚未安裝相容工具";
                String owner = gpsOwnerSummary();
                runOnUiThread(() -> {
                    if (gpsStatus == null || revision != pageRevision) return;
                    setChecked(gpsSwitch, "on".equals(AutomationPreferences.value(this,"copy",s.autoCopy?"on":"off")));
                    gpsSwitch.setEnabled(true);
                    gpsStatus.setText((s.joystickInstalled ? "JoyStick 已安裝" : "未安裝相容 JoyStick")
                            + " · " + gpsStatusLabel(s.status) + "\n定位：" + readiness + "\n" + owner);
                    gpsStatus.setTextColor(primary());
                });
            } catch (Exception e) {
                showInline(gpsStatus, "⚠ 無法讀取 GPS 模組：" + e.getMessage(), true);
            }
        });
    }

    private String gpsStatusLabel(String status) {
        if ("copied".equalsIgnoreCase(status)) return "已取得座標";
        if ("waiting".equalsIgnoreCase(status) || "idle".equalsIgnoreCase(status)) return "等待探險項目";
        if ("disabled".equalsIgnoreCase(status) || "off".equalsIgnoreCase(status)) return "已關閉";
        if (status == null || status.trim().isEmpty()) return "等待模組回報";
        return status;
    }

    private void refreshHealth() {
        if (healthText == null) return;
        final int revision = pageRevision;
        worker.execute(() -> {
            try {
                EnvironmentReport report = EnvironmentGateway.read(this);
                runOnUiThread(() -> {
                    if (revision != pageRevision || healthStatus == null || healthText == null) return;
                    healthStatus.setText(report.summary);
                    healthStatus.setTextColor(report.warning ? danger() : primary());
                    healthText.setText(report.details);
                });
            } catch (Exception e) {
                showInline(healthStatus, "⚠ 無法讀取健康資訊：" + e.getMessage(), true);
            }
        });
    }

    private synchronized void refreshDispatch() {
        if (flowerFarmStatus != null && dispatchStatus == null) {
            final int revision = pageRevision;
            worker.execute(() -> {
                String current = WorkStatus.summary(this, GpsWorkLock.FLOWER_FARM, FlowerFarmService.isRunning());
                String history = current.isEmpty() ? "上次回報（非即時狀態）\n" + FlowerFarmService.status(this) : current;
                runOnUiThread(() -> {
                    if (revision == pageRevision && flowerFarmStatus != null) {
                        flowerFarmHistory.setText(history);
                        renderFlowerFarm();
                    }
                });
            });
            return;
        }
        if (dispatchStatus == null || dispatchReadPending) return;
        dispatchReadPending = true;
        final int revision = pageRevision;
        final boolean readHistory = expandedSections.getBoolean("3:0:派遣歷史（最近 24 小時）", false);
        final boolean readMetrics = false; // Explicit System diagnostics only, never hidden-page polling.
        final boolean readStatusHistory = readMetrics;
        worker.execute(() -> {
            try {
                String rawStatus = readHistory ? DispatchProbeGateway.batchStatus() : "";
                String history = readHistory ? DispatchProbeGateway.historyFormatted() : "展開後讀取紀錄。";
                String metrics = readMetrics ? DispatchProbeGateway.metricsFormatted() : "展開後讀取量測。";
                String statusHistory = readStatusHistory ? DispatchProbeGateway.batchStatusHistoryFormatted() : "展開後讀取歷程。";
                runOnUiThread(() -> {
                    if (dispatchStatus == null || revision != pageRevision) return;
                    updateBatchStatus(rawStatus);
                    if (dispatchHistory != null) dispatchHistory.setText(
                            WorkStatus.summary(this, GpsWorkLock.BATCH, DispatchBatchService.isRunning())
                                    + "\n上次原生回報：" + rawStatus + "\n\n" + history);
                    if (dispatchMetrics != null) dispatchMetrics.setText(metrics);
                    if (dispatchStatusHistory != null) dispatchStatusHistory.setText(statusHistory);
                    updateQueueControls();
                });
            } catch (Exception e) {
                showInline(dispatchStatus, "⚠ 無法讀取派遣狀態：" + e.getMessage(), true);
            } finally { dispatchReadPending = false; }
        });
    }

    private void updateBatchStatus(String raw) {
        boolean active = DispatchBatchService.isRunning();
        dispatchStatus.setText(WorkStatus.compact(this, GpsWorkLock.BATCH, active));
        dispatchStatus.setTextColor(workColor(GpsWorkLock.BATCH, active));
        updateSafetyBar(active, GpsWorkLock.BATCH, batchStop);
        if(batchRecover!=null) {
            boolean recovery=BatchRecoveryGateway.needed(this);
            batchRecover.setText(recovery?"診斷並恢復批次":"重新核對批次收據");
            batchRecover.setVisibility(!active&&(recovery||WorkStatus.hasBatchReconciliation(this))?View.VISIBLE:View.GONE);
            setButtonEnabled(batchRecover,!BatchRecoveryGateway.checking()&&GpsWorkLock.batchRecoveryAvailable(this));
        }
    }

    private void invalidateQueue() {
        ++scanGeneration;
        scanning = false;
        setButtonEnabled(scanButton, true);
        scannedKey = "";
        queuePage = 0;
        batchQueue = new ArrayList<>();
        batchScanned = false;
        if (dispatchLog != null) dispatchLog.setText("條件已變更，請重新掃描。");
        showDispatchFeedback("條件已變更，請重新掃描探險清單。", false);
        updateQueueControls();
        renderQueue();
    }

    private void updateQueueControls() {
        boolean ready = !BatchRecoveryGateway.needed(this) && !BatchRecoveryGateway.checking()
                && !gpsWorkBusy() && !scanning && batchScanned && !batchQueue.isEmpty() && batchSelection().scanKey().equals(scannedKey);
        boolean busy = gpsWorkBusy();
        setButtonEnabled(scanButton, !scanning && !busy);
        if (batchSeed != null) batchSeed.setEnabled(!busy && !scanning);
        if (batchFruit != null) batchFruit.setEnabled(!busy && !scanning);
        if (batchGift != null) batchGift.setEnabled(!busy && !scanning);
        if (batchRange != null) batchRange.setEnabled(!busy && !scanning);
        if (batchNearestFirst != null) batchNearestFirst.setEnabled(!busy && !scanning);
        setButtonEnabled(batchTest, ready);
        setButtonEnabled(batchStart, ready);
        if (dispatchQueueSummary == null) return;
        if (scanning) dispatchQueueSummary.setText("正在讀取符合條件的清單…");
        else if (!batchScanned) dispatchQueueSummary.setText("尚未掃描 · 先開啟遊戲探險頁。");
        else if (batchQueue.isEmpty()) dispatchQueueSummary.setText("沒有符合目前類型與距離條件的項目。");
        else dispatchQueueSummary.setText(queueSummary(batchQueue));
    }

    private String queueSummary(List<DispatchProbeGateway.Candidate> queue) {
        int seed = 0, fruit = 0, gift = 0;
        for (DispatchProbeGateway.Candidate c : queue) {
            if ("seed".equals(c.kind)) seed++;
            else if ("fruit".equals(c.kind)) fruit++;
            else if ("gift".equals(c.kind)) gift++;
        }
        return "共 " + queue.size() + " 筆：花苗 " + seed + "、水果 " + fruit + "、禮物盒 " + gift
                + (sortByDistance ? "\n順序：距離較近優先" : "\n順序：較早到期優先");
    }

    private String formatQueue(List<DispatchProbeGateway.Candidate> queue) {
        if (queue.isEmpty()) return "沒有符合條件的項目。";
        java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.TAIWAN);
        StringBuilder out = new StringBuilder();
        int start = BatchUiSelection.page(queuePage, queue.size()) * 50;
        int end = Math.min(start + 50, queue.size());
        for (int index = start; index < end; index++) {
            DispatchProbeGateway.Candidate c = queue.get(index);
            if (out.length() > 0) out.append('\n');
            out.append(index + 1).append(". ").append(c.label());
            if (c.distanceMetres >= 0) out.append(" · ").append(c.distanceMetres < 1000
                    ? String.format(Locale.TAIWAN, "%.0f m", c.distanceMetres)
                    : String.format(Locale.TAIWAN, "%.1f km", c.distanceMetres / 1000));
            if (c.expirationMs > 0) out.append(" · 到期 ").append(time.format(new java.util.Date(c.expirationMs)));
            out.append("\n   ").append(String.format(Locale.TAIWAN, "%.7f, %.7f", c.latitude, c.longitude));
        }
        return out.toString();
    }

    private void moveQueuePage(int change) {
        queuePage += change;
        renderQueue();
        final int revision = pageRevision;
        page.post(() -> {
            if (revision != pageRevision || queuePageLabel == null) return;
            android.graphics.Rect bounds = new android.graphics.Rect();
            queuePageLabel.getDrawingRect(bounds);
            page.offsetDescendantRectToMyCoords(queuePageLabel, bounds);
            pageScroll.smoothScrollTo(0, Math.max(0, bounds.top - dp(12)));
        });
    }

    private boolean gpsWorkBusy() {
        return UpdateInstaller.blocking(this) || WorkStatus.stoppingAll || WorkStatus.blocked(this) || DispatchBatchService.isRunning()
                || FlowerFarmService.isRunning() || GpsWorkLock.observedHeldBy(this, GpsWorkLock.BATCH)
                || GpsWorkLock.observedHeldBy(this, GpsWorkLock.FLOWER_FARM)
                || GpsWorkLock.observedHeldBy(this, GpsWorkLock.JOYSTICK_STARTUP);
    }

    private void hcCompatCard() {
        sectionLabel("HC 相容");
        LinearLayout box=cardBox();
        Switch toggle=settingSwitch("HC 相容",null);box.addView(toggle);
        TextView status=liveBody("正在核對 HC 相容狀態");box.addView(status);
        View refresh=secondaryButton("重新核對 HC 狀態",null);box.addView(refresh);
        optionExplanation(box,"HC 相容說明","僅 Android 13 以下可啟用；Android 14 以上不需要。由核心提供，仍需真正的 Health Connect 服務與授權；不寫入步數。獨立舊套件須先備份移除，再重開遊戲。");
        addCard(box);HcCompatCard.bind(this,toggle,status,refresh,worker);
    }

    private void updatesCard() {
        sectionLabel("版本更新");
        LinearLayout box=cardBox();
        updateVersion=body("目前 "+appVersion());box.addView(updateVersion);
        automaticUpdates=settingSwitch("自動檢查更新",null);
        automaticUpdates.setChecked(UpdateStore.automatic(this));
        automaticUpdates.setEnabled(true); // Local preference is already loaded; no native readback is required.
        automaticUpdates.setOnCheckedChangeListener((v,on)->{UpdateStore.automatic(this,on);if(on)UpdateController.check(this,false);});
        box.addView(automaticUpdates);
        optionExplanation(box, "更新檢查說明", "開啟App時每日檢查 · 不自動下載");
        updateStatus=liveBody("");updateStatus.setTextSize(14);box.addView(updateStatus);
        updateCheck=secondaryButton("檢查更新",v->{UpdateController.check(this,true);renderUpdateStatus();});box.addView(updateCheck);
        updateSummary=body("");disclosure(box,"更新說明",updateSummary);
        updateConsent=body("");box.addView(updateConsent);
        updateInstall=primaryButton("立即更新",v->continuePhoneUpdate());box.addView(updateInstall);
        updateCancel=secondaryButton("取消下載",v->{
            if(PhoneUpdateFlow.active(this)){
                if(UpdateController.downloading()){UpdateController.cancelDownload();return;}
                new AlertDialog.Builder(this).setTitle("取消本次更新？").setMessage("只可取消尚未完成的 APK 更新；保留原設定及操作紀錄。")
                    .setNegativeButton("返回",null).setPositiveButton("取消更新",(d,w)->worker.execute(()->{
                        try{PhoneUpdateFlow.cancel(this);}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
                    })).show();
            }else if(UpdateInstaller.pending(this)||UpdatePreparation.blocking(this))new AlertDialog.Builder(this).setTitle("取消安裝或更新準備？")
                    .setMessage("原版本與設定會保留。").setNegativeButton("返回",null)
                    .setPositiveButton("取消並核對恢復",(d,w)->worker.execute(()->{
                        try{UpdatePreparation.cancel(this);}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
                        SettingsRestoreJob.restore(this);runOnUiThread(()->{StepService.resumeFromVisibleApp(this);renderUpdateStatus();});
                    })).show();
            else UpdateController.cancelDownload();
        });box.addView(updateCancel);
        TextView coreStatus=body("核心版本尚未核對");box.addView(coreStatus);
        LinearLayout recovery=new LinearLayout(this);recovery.setOrientation(LinearLayout.VERTICAL);
        disclosure(box,"進階核心維修（一般更新不需操作）",recovery);
        renderCoreRecovery(recovery,coreStatus,"");
        readinessStatus=liveBody("功能就緒：尚未檢查");readinessStatus.setTextSize(14);
        statusWithRefresh(box,readinessStatus,v->refreshReadiness());
        readinessDetail=mono("按 ↻ 讀取。就緒只表示資料來源與入口存在且新鮮，不代表功能已實測成功。");
        disclosure(box,"功能就緒詳情",readinessDetail);
        addCard(box);renderUpdateStatus();refreshReadiness();
    }
    private int coreRenderGeneration,corePollGeneration;
    private AlertDialog coreDialog;
    private TextView readinessStatus, readinessDetail;
    private ReadinessGateway.Coordinator readiness;
    /** Read-only per-feature readiness; one bounded shell per round, coalesced 5 s, results bound to the page revision. */
    private ReadinessGateway.Coordinator readiness() {
        if (readiness == null) readiness = new ReadinessGateway.Coordinator(ReadinessGateway.SYSTEM_CLOCK, worker, () -> ReadinessGateway.sample(this));
        return readiness;
    }
    private void refreshReadiness() {
        if (readinessStatus == null) return;
        final int revision = pageRevision;
        readinessStatus.setText("功能就緒：檢查中…");
        readiness().refresh((generation, result, cached) -> runOnUiThread(() -> {
            if (isDestroyed() || revision != pageRevision || readinessStatus == null || readinessDetail == null) return;
            readinessStatus.setText(result.oneLine() + (cached ? "（快取）" : ""));
            readinessStatus.setTextColor(result.warning() ? danger() : primary());
            readinessDetail.setText(ReadinessGateway.details(result, cached));
        }));
    }
    /** State-driven rendering; stale page results cannot replace a newer snapshot. */
    private void renderCoreRecovery(LinearLayout outer,TextView coreStatus,String notice) {
        if(isDestroyed())return;
        final int generation=++coreRenderGeneration, revision=pageRevision;
        coreStatus.setText(notice.isEmpty()?"正在讀取核心狀態…":notice);outer.removeAllViews();
        worker.execute(()->{
            String action,versions,detail="";
            try{
                action=CoreInstallWizard.action(this);
                versions=CoreUpdater.inspect(this).split("\n",2)[0];
            }catch(Exception e){action="error";versions="核心狀態待核對";detail=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
            android.content.SharedPreferences p=CoreUpdater.prefs(this);
            boolean fresh=p.getBoolean("fresh",false);
            String phase=fresh?p.getString("fresh_phase",""):p.getBoolean("migration",false)?p.getString("migration_phase",""):"";
            CorePanelState model=new CorePanelState(action,CoreInstallWizard.preparing(this),CoreUpdater.pending(this),fresh,phase);
            final String shown=versions+"\n"+model.title;
            final String error=detail;
            runOnUiThread(()->{
                if(isDestroyed()||revision!=pageRevision||generation!=coreRenderGeneration)return;
                coreStatus.setText(shown);outer.removeAllViews();
                if(!notice.isEmpty())outer.addView(noteBody(notice));
                if(!error.isEmpty())outer.addView(noteBody(error));
                boolean manualCore=!PhoneUpdateFlow.active(this)||PhoneUpdateFlow.phase(this).equals("error");
                if(!model.primary.isEmpty()&&manualCore)outer.addView(primaryButton(model.primary,v->{
                    if(model.action.equals("error"))renderCoreRecovery(outer,coreStatus,"");
                    else smartCoreAction(outer,coreStatus);
                }));
                if(model.cancel&&manualCore)outer.addView(secondaryButton("取消升級準備",v->confirmCoreAction(outer,coreStatus,
                    "取消升級準備？","不刪除安裝紀錄；已停止的工作不會自動重播。",()->{
                        worker.execute(()->{
                            String result;
                            try{CoreInstallWizard.cancelPreparation(this);result="已取消準備";}
                            catch(Exception e){result=e.getMessage();}
                            final String text=result;runOnUiThread(()->{if(revision==pageRevision)renderCoreRecovery(outer,coreStatus,text);});
                        });
                    })));
                final LinearLayout advanced=new LinearLayout(this);advanced.setOrientation(LinearLayout.VERTICAL);
                advanced.addView(secondaryButton("重新檢查核心狀態",v->renderCoreRecovery(outer,coreStatus,"")));
                String history=CoreUpdater.recoveryReport(this);
                if(!history.isEmpty())advanced.addView(noteBody("歷史紀錄（不代表目前狀態）"+history));
                if(!phase.isEmpty())advanced.addView(noteBody("交易紀錄："+phase));
                if(model.rollback&&manualCore)advanced.addView(secondaryButton(fresh?"回復未安裝核心":"回退至備份核心",
                    v->confirmCoreRollback(outer,coreStatus,false,fresh)));
                coreAdvancedTools=advanced;
            });
        });
    }
    private void confirmCoreAction(LinearLayout outer,TextView status,String title,String message,Runnable confirmed){
        if(PhoneUpdateFlow.active(this)&&(!PhoneUpdateFlow.phase(this).equals("error")||PhoneUpdateFlow.busy())){toast("請先完成目前更新步驟");return;}
        if(coreDialog!=null&&coreDialog.isShowing())return;
        final int revision=pageRevision;
        coreDialog=new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("取消",null).setPositiveButton("確認",(d,w)->{
                if(!isDestroyed()&&revision==pageRevision&&(!PhoneUpdateFlow.active(this)||(PhoneUpdateFlow.phase(this).equals("error")&&!PhoneUpdateFlow.busy())))confirmed.run();
            }).create();
        coreDialog.show();
    }
    private void confirmCoreRollback(LinearLayout outer,TextView status,boolean finish,boolean fresh){
        final int revision=pageRevision;
        confirmCoreAction(outer,status,finish?"完成回退並恢復GPS？":fresh?"回復首次安裝前狀態？":"回退至備份核心？",
            "請先停止自動化並關閉遊戲。只使用已校驗的備份，保留所有紀錄；操作前會再次核對條件，完成後可能需要重開手機。",
            ()->{
                outer.removeAllViews();status.setText("正在核對回退條件…");
                worker.execute(()->{
                    String result;
                    try{CoreUpdater.rollback(this,finish);result="回退步驟完成，請依目前狀態繼續";}
                    catch(Exception e){result="回退未完成："+e.getMessage();}
                    final String resultText=result;
                    runOnUiThread(()->{if(revision==pageRevision)renderCoreRecovery(outer,status,resultText);});
                });
            });
    }
    private void smartCoreAction(LinearLayout recovery,TextView status) {
        if(PhoneUpdateFlow.active(this)&&(!PhoneUpdateFlow.phase(this).equals("error")||PhoneUpdateFlow.busy())){toast("請由上方更新進度繼續");return;}
        if(coreDialog!=null&&coreDialog.isShowing())return;
        final int revision=pageRevision;
        ++corePollGeneration;
        ++coreRenderGeneration;
        recovery.removeAllViews();status.setText("正在核對目前核心及交易狀態…");
        worker.execute(()->{
            try {
                String action=CoreInstallWizard.action(this);
                runOnUiThread(()->{
                    if(isDestroyed()||revision!=pageRevision)return;
                    if(action.equals("install")||action.equals("successor")){
                        renderCoreRecovery(recovery,status,"");
                        confirmCoreAction(recovery,status,"停止自動化並更新核心？",
                            "將先停止自動化，再關閉Pikmin並安裝配對核心。不清除遊戲資料。保留已儲存設定，安裝核對完成後恢復；清理未完成則維持暫停。",
                            ()->{
                                try{CoreInstallWizard.preparing(this,true);}catch(Exception e){renderCoreRecovery(recovery,status,e.getMessage());return;}
                                ++coreRenderGeneration;
                                recovery.removeAllViews();status.setText("正在停止自動化；完成後關閉遊戲並安裝…");
                                stopAll(()->worker.execute(()->{
                                    String result;
                                    try{CoreInstallWizard.closeGameAndInstall(this);result="安裝指令完成，請依目前狀態繼續";}
                                    catch(Exception e){result="升級未完成："+e.getMessage()+"；不要重複安裝";}
                                    final String text=result;runOnUiThread(()->{if(revision==pageRevision)renderCoreRecovery(recovery,status,text);});
                                }),()->{if(revision==pageRevision)renderCoreRecovery(recovery,status,"停止清理尚未完成；未關閉遊戲或安裝核心，請重試或取消準備");});
                            });
                    }else if(action.equals("reboot")){
                        renderCoreRecovery(recovery,status,"");
                        confirmCoreAction(recovery,status,"重新啟動手機？","重開後請解鎖、開啟Pikmin，再回控制中心核對。",
                            ()->worker.execute(()->{
                                try{CoreUpdater.root("reboot");}catch(Exception e){runOnUiThread(()->renderCoreRecovery(recovery,status,"重開指令未確認，請手動重開；不要再次安裝"));}
                            }));
                    }else if(action.equals("finish")){
                        renderCoreRecovery(recovery,status,"");
                        confirmCoreRollback(recovery,status,true,false);
                    }else if(action.equals("open-game")||action.equals("review")){
                        if(action.equals("open-game")){
                            android.content.Intent game=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                            if(game==null){renderCoreRecovery(recovery,status,"找不到Pikmin，請先安裝遊戲");return;}
                            startActivity(game);
                        }
                        status.setText("正在核對核心與GPS載入；請讓遊戲進入地圖");
                        pollCoreReview(recovery,status,24,corePollGeneration,revision);
                    }else if(action.equals("ready")){
                        try{CoreInstallWizard.preparing(this,false);JoystickStartup.afterCoreReady(this);}catch(Exception e){toast(e.getMessage());}
                        renderCoreRecovery(recovery,status,"");
                    }else renderCoreRecovery(recovery,status,"");
                });
            }catch(Exception e){runOnUiThread(()->{
                if(!isDestroyed()&&revision==pageRevision)renderCoreRecovery(recovery,status,"檢查未完成："+e.getMessage());
            });}
        });
    }
    private void pollCoreReview(LinearLayout recovery,TextView status,int remaining,int token,int revision){
        if(isDestroyed()||remaining<=0||token!=corePollGeneration||revision!=pageRevision)return;
        mainHandler.postDelayed(()->{
            if(isDestroyed()||token!=corePollGeneration||revision!=pageRevision)return;
            worker.execute(()->{
                try{
                    if(CoreUpdater.pending(this))CoreUpdater.review(this);
                    String next=CoreInstallWizard.action(this);
                    if(next.equals("finish")||next.equals("install")||next.equals("successor")||next.equals("reboot")){
                        runOnUiThread(()->{if(token==corePollGeneration&&revision==pageRevision)renderCoreRecovery(recovery,status,"");});return;
                    }
                    CoreUpdater.requireFiles(this,CoreUpdater.pair(this));
                    if(CoreUpdater.runtimePid(this,CoreUpdater.pair(this)).isEmpty())throw new IllegalStateException("尚未確認核心與GPS同程序載入");
                    CoreInstallWizard.preparing(this,false);
                    JoystickStartup.afterCoreReady(this);
                    runOnUiThread(()->{if(token==corePollGeneration&&revision==pageRevision)renderCoreRecovery(recovery,status,"");});
                }catch(Exception e){
                    runOnUiThread(()->{
                        if(isDestroyed()||token!=corePollGeneration||revision!=pageRevision)return;
                        if(remaining==1)renderCoreRecovery(recovery,status,"核對尚未完成："+e.getMessage());
                        else {status.setText("等待遊戲載入："+e.getMessage());pollCoreReview(recovery,status,remaining-1,token,revision);}
                    });
                }
            });
        },5000);
    }
    private void renderUpdateStatus() {
        UpdateInfo info=UpdateStore.latest(this);
        boolean newer=info!=null && info.newerThan(UpdateStore.installed(this));
        boolean flow=PhoneUpdateFlow.active(this);
        if(updateNotice!=null) {
            updateNotice.setVisibility(newer||flow?View.VISIBLE:View.GONE);
            if(flow)updateNotice.setText(PhoneUpdateFlow.setup(this)?"裝置設定尚未完成 · 查看進度 ›":"更新尚未完成 · 查看進度 ›");
            else if(newer)updateNotice.setText("新版 "+info.version+" 可更新 · 查看更新 ›");
        }
        if(updateStatus==null)return;
        long time=UpdateStore.prefs(this).getLong("last_success",0);
        updateStatus.setText(("complete".equals(PhoneUpdateFlow.phase(this))?PhoneUpdateFlow.status(this)+"\n":"")+UpdateController.status(this)+(time>0?"\n上次成功檢查 "+new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.TAIWAN).format(new java.util.Date(time)):""));
        boolean cached=UpdateStore.prefs(this).getInt("check_failures",0)>0;
        updateVersion.setText("目前 "+appVersion()+(info==null?"":cached?" · 上次取得版本 "+info.version:newer?" · 新版 "+info.version:""));
        updateSummary.setText(info==null?"正式版資訊由公開GitHub發布庫提供。":info.summary+"\n"+info.compatibility);
        boolean busy=UpdateController.busy(),pending=UpdateInstaller.pending(this);
        displayedUpdate=newer&&!flow?info:null;
        updateConsent.setVisibility(displayedUpdate!=null?View.VISIBLE:View.GONE);
        if(displayedUpdate!=null)updateConsent.setText("同意後會下載並驗證，安裝前暫停自動功能。需要換核心時會先關閉 Pikmin 並備份，重開時機由你決定。");
        setButtonEnabled(updateCheck,!busy&&!pending&&!flow);
        updateInstall.setVisibility(newer||flow?View.VISIBLE:View.GONE);
        String action=PhoneUpdateFlow.action(this);
        updateInstall.setText(!flow?(displayedUpdate==null?"立即更新":"同意更新至 "+displayedUpdate.version):action.equals("permission")?"允許安裝並繼續":action.equals("reboot")?"重新啟動手機":action.equals("open-game")?"開啟 Pikmin 驗證":action.equals("retry")?"重新核對並繼續":"更新進行中…");
        setButtonEnabled(updateInstall,!busy&&!pending&&!PhoneUpdateFlow.busy()&&(flow||(info!=null&&info.minSdk<=Build.VERSION.SDK_INT))&&(!flow||!action.isEmpty()));
        updateCancel.setVisibility(flow||UpdateController.downloading()||pending||UpdatePreparation.blocking(this)?View.VISIBLE:View.GONE);
        setButtonEnabled(updateCancel,!UpdateInstaller.preparing&&!UpdatePreparation.running()&&!PhoneUpdateFlow.busy()&&(!flow||newer||PhoneUpdateFlow.setup(this)));
        updateCancel.setText(pending?"取消安裝":UpdatePreparation.blocking(this)?"取消更新準備":"取消下載");
    }
    private void continuePhoneUpdate() {
        if(PhoneUpdateFlow.active(this)){
            String action=PhoneUpdateFlow.action(this);
            if(action.equals("permission")){
                try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
                catch(Exception e){toast("無法開啟安裝來源設定");}
            }else if(action.equals("reboot")){
                new AlertDialog.Builder(this).setTitle("重新啟動手機？").setMessage("現在會中斷手機使用。重開後解鎖並開啟控制中心，即可接續驗證。也可稍後自行重開。")
                    .setNegativeButton("稍後",null).setPositiveButton("立即重開",(d,w)->worker.execute(()->{
                        try{PhoneUpdateFlow.requireReboot(this);
                            CoreUpdater.root("reboot");}
                        catch(Exception e){runOnUiThread(()->toast("重開未確認："+e.getMessage()));}
                    })).show();
            }else if(action.equals("open-game")){
                Intent game=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");
                if(game==null)toast("找不到 Pikmin，請先安裝遊戲");else {
                    try{PhoneConnectionService.start(this);}catch(RuntimeException e){toast("背景核對未啟動，可返回控制中心核對");}
                    try{startActivity(game);}catch(RuntimeException e){toast("無法開啟 Pikmin："+e.getMessage());}
                }
            }else if(action.equals("retry"))worker.execute(()->{
                try{PhoneUpdateFlow.retry(this);}catch(Exception e){runOnUiThread(()->toast(e.getMessage()));}
            });
            return;
        }
        UpdateInfo consent=displayedUpdate;
        if(consent==null)return;
        try {
            PhoneUpdateFlow.start(this,consent);
            PhoneUpdateFlow.requestAdvance(getApplicationContext());
        } catch(Exception e) {
            toast("無法開始："+e.getMessage());
        }
        renderUpdateStatus();
    }

    private void renderQueue() {
        if (dispatchLog == null || queuePageLabel == null) return;
        queuePage = BatchUiSelection.page(queuePage, batchQueue.size());
        dispatchLog.setText(batchScanned ? formatQueue(batchQueue) : "尚未建立批次清單。");
        int count = batchQueue.size();
        queuePageLabel.setText(count == 0 ? "0 筆" : "第 " + (queuePage * 50 + 1) + "–"
                + Math.min(count, (queuePage + 1) * 50) + " 筆／共 " + count + " 筆");
        setButtonEnabled(queuePrevious, queuePage > 0);
        setButtonEnabled(queueNext, queuePage + 1 < BatchUiSelection.pages(count));
    }

    private CheckBox batchType(String label, int bit) {
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setTextSize(16);
        view.setTextColor(primary());
        view.setMinHeight(dp(48));
        styleChoice(view);
        view.setChecked((batchKindMask & bit) != 0);
        view.setOnCheckedChangeListener((button, checked) -> {
            batchKindMask = checked ? batchKindMask | bit : batchKindMask & ~bit;
            saveBatchSelection();
            invalidateQueue();
        });
        return view;
    }

    private Switch settingSwitch(String label, CompoundButton.OnCheckedChangeListener listener) {
        Switch view = new Switch(this);
        view.setText(label);
        view.setTextSize(17);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setSwitchPadding(dp(12));
        view.setThumbTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent(), secondary()}));
        view.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{withAlpha(accent(), 90), outline()}));
        view.setSingleLine(false);
        view.setTextColor(primary());
        view.setMinHeight(dp(52));
        view.setEnabled(false); // Waiting for readback is not an unchecked, loaded setting.
        view.setOnCheckedChangeListener(listener);
        return view;
    }

    private void styleChoice(CheckBox box) {
        box.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{accent(), secondary()}));
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_checked}, rounded(selectedSurface(), accent(), 1, dp(12)));
        states.addState(new int[]{}, rounded(surface(), outline(), 1, dp(12)));
        box.setBackground(states);
        box.setPadding(dp(3), dp(4), dp(5), dp(4));
        box.setMinHeight(dp(48));
    }

    private void renderPlanterSlots(PlanterState state) {
        if (planterSlots == null) return;
        planterSlots.removeAllViews();
        if (state.slots.isEmpty()) {
            planterSlots.addView(noteBody("等待遊戲回報育苗槽位"));
            return;
        }
        for (PlanterState.Slot slot : state.slots) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(10), dp(12), dp(10), dp(12));
            tile.setBackground(rounded(surfaceVariant(), outline(), 1, dp(18)));
            TextView icon = body(slot.empty ? "🪴" : "🌱");
            icon.setTextSize(30);
            tile.addView(icon);
            TextView label = body("槽 " + (slot.index + 1) + " · " + (slot.empty ? "空槽" : slot.ready ? "可拔苗" : "成長中"));
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            tile.addView(label);
            tile.addView(noteBody(state.fresh ? "目前回報" : "上次回報 · 待更新"));
            if (!slot.empty) {
                tile.addView(noteBody(slot.steps + " / " + (slot.target > 0 ? slot.target : "?") + " 步"));
                ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                progress.setMax(1000);
                progress.setProgress(slot.target > 0 ? (int) Math.min(1000, slot.steps * 1000.0 / slot.target) : 0);
                progress.setProgressTintList(ColorStateList.valueOf(accent()));
                progress.setContentDescription("槽 " + (slot.index + 1) + " 成長進度" + (state.fresh ? "" : "，資料未更新"));
                tile.addView(progress, new LinearLayout.LayoutParams(-1, dp(12)));
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(2), dp(8), dp(2), dp(8));
            planterSlots.addView(tile, params);
        }
    }

    private Spinner spinner(String[] values, int selectedIndex) {
        Spinner view = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(adapter);
        view.setSelection(selectedIndex);
        view.setMinimumHeight(dp(48));
        return view;
    }

    private String gpsOwnerSummary() {
        if (GpsWorkLock.observedHeldBy(this, GpsWorkLock.BATCH)) return "批次探險 · GPS 使用中";
        if (GpsWorkLock.observedHeldBy(this, GpsWorkLock.FLOWER_FARM)) return "花田採果 · GPS 使用中";
        if (GpsWorkLock.observedHeldBy(this, GpsWorkLock.JOYSTICK_STARTUP)) return "GPS 啟動檢查中";
        return "控制中心閒置"; // Does not assert anything about external JoyStick routes.
    }

    private int workColor(String owner, boolean live) {
        return UiSummary.workWarning(WorkStatus.state(this, owner), live, GpsWorkLock.observedHeldBy(this, owner))
                ? danger() : secondary();
    }

    private void updateSafetyBar(boolean active, String owner, Button stop) {
        // Own farm cleanup has a consented recovery CTA; global/other-owner blocks keep emergency cleanup.
        boolean farmRecovery = GpsWorkLock.FLOWER_FARM.equals(owner) && !active
                && WorkStatus.farmNeedsRecovery(this) && GpsWorkLock.farmRecoveryAvailable(this);
        boolean recovery = !active && (WorkStatus.blocked(this) || WorkProgress.active(WorkStatus.state(this, owner))
                || GpsWorkLock.observedHeldBy(this, owner));
        safetyBar.setVisibility(!farmRecovery && (active || recovery) ? View.VISIBLE : View.GONE);
        setButtonEnabled(stop, active || recovery);
        stop.setText(recovery ? "重試停止全部" : "安全停止");
        stop.setOnClickListener(v -> {
            if (recovery) confirmStopAll();
            else if (GpsWorkLock.BATCH.equals(owner)) stopBatch();
            else stopFlowerFarm();
        });
    }

    private void statusWithRefresh(LinearLayout parent, TextView status, View.OnClickListener refresh) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button action = secondaryButton("↻", refresh);
        action.setContentDescription("更新狀態");
        row.addView(action, new LinearLayout.LayoutParams(dp(52), dp(48)));
        parent.addView(row);
    }

    /** One large navigation target; summary wraps naturally at enlarged font sizes. */
    private TextView summaryRow(LinearLayout parent, String label, String iconName, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(2), dp(4), dp(2), dp(4));
        row.setMinimumHeight(dp(52));
        row.setBackground(ripple(Color.TRANSPARENT, Color.TRANSPARENT, dp(12)));
        row.setFocusable(true);
        row.setOnClickListener(click);
        ImageView icon = new ImageView(this);
        icon.setImageResource(getResources().getIdentifier(iconName, "drawable", getPackageName()));
        icon.setImageTintList(ColorStateList.valueOf(accent()));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, dp(8), 0);
        TextView title = body(label);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.addView(title);
        TextView summary = liveBody("讀取中…");
        summary.setTextSize(13);
        summary.setTextColor(secondary());
        text.addView(summary);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = body("›");
        arrow.setTextColor(secondary());
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(arrow);
        parent.addView(row);
        return summary;
    }

    private void heading(String heading, String subheading) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView brand = gardenCaption("Pikmin 控制中心");
        brand.setTextColor(accent());
        copy.addView(brand);
        copy.addView(title(heading));
        if (!subheading.isEmpty()) copy.addView(subtitle(subheading));
        row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView garden = new ImageView(this);
        try (java.io.InputStream input = getAssets().open("garden-pikmin.png")) {
            garden.setImageDrawable(Drawable.createFromStream(input, "garden-pikmin"));
        } catch (java.io.IOException ignored) { /* Text layout remains usable without decoration. */ }
        garden.setScaleType(ImageView.ScaleType.FIT_CENTER);
        garden.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Decoration shrinks for large type; functional text is never clipped.
        int width = getResources().getConfiguration().fontScale > 1.2f ? 64 : 108;
        row.addView(garden, new LinearLayout.LayoutParams(dp(width), dp(76)));
        Button system = new Button(this);
        int systemIcon = getResources().getIdentifier("nav_system", "drawable", getPackageName());
        system.setCompoundDrawablesWithIntrinsicBounds(0, systemIcon, 0, 0);
        system.setCompoundDrawableTintList(ColorStateList.valueOf(accent()));
        system.setText("系統");
        system.setTextSize(10);
        system.setMinWidth(0);
        system.setMinimumWidth(0);
        system.setPadding(0, dp(4), 0, dp(4));
        system.setTextColor(accent());
        system.setBackground(ripple(selected == SYSTEM ? selectedSurface() : background(),
                selected == SYSTEM ? accent() : Color.TRANSPARENT, dp(14)));
        system.setContentDescription(selected == SYSTEM ? "系統，目前頁面" : "開啟系統設定");
        system.setOnClickListener(v -> showPage(SYSTEM));
        row.addView(system, new LinearLayout.LayoutParams(dp(48), dp(56)));
        row.setPadding(dp(20), dp(6), dp(20), dp(6));
        page.addView(row);
    }

    private void sectionLabel(String value) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setTextColor(secondary());
        label.setPadding(dp(18), dp(12), dp(18), dp(2));
        page.addView(label);
    }

    private void card(View child) {
        LinearLayout box = cardBox();
        box.addView(child);
        addCard(box);
    }

    private void cardWithNote(View child, String message, boolean warning) {
        LinearLayout box = cardBox();
        box.addView(child);
        TextView note = noteBody(message);
        note.setTextColor(warning ? danger() : secondary());
        box.addView(note);
        addCard(box);
    }

    private LinearLayout cardBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(16), dp(12));
        box.setBackground(rounded(surface(), Color.TRANSPARENT, 0, dp(20)));
        box.setElevation(0);
        return box;
    }

    private void addCard(LinearLayout box) { page.addView(box, margin(20, 10, 20, 0)); }

    private TextView gardenCaption(String value) {
        TextView text = liveBody(value);
        text.setTextSize(12);
        text.setTextColor(secondary());
        text.setPadding(0, dp(2), 0, dp(2));
        return text;
    }

    private TextView gardenState(String value) {
        TextView text = liveBody(value);
        text.setTextSize(18);
        text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        text.setPadding(0, dp(2), 0, dp(2));
        return text;
    }

    private TextView gardenSummary(LinearLayout parent, String label, View.OnClickListener click) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setMinimumHeight(dp(48));
        content.setFocusable(true);
        content.setBackground(ripple(Color.TRANSPARENT, Color.TRANSPARENT, dp(12)));
        content.setOnClickListener(click);
        content.addView(gardenCaption(label));
        TextView state = gardenState("讀取中…");
        content.addView(state);
        parent.addView(content);
        return state;
    }

    private TextView gardenAutomationRow(LinearLayout parent, String label, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(44));
        row.setFocusable(true);
        row.setBackground(ripple(Color.TRANSPARENT, Color.TRANSPARENT, dp(12)));
        row.setOnClickListener(click);
        TextView name = liveBody(label);
        name.setTextSize(14);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(primary());
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView state = liveBody("讀取中…");
        state.setTextSize(14);
        state.setTextColor(accent());
        state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(state, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row);
        return state;
    }

    private void pairedButtons(LinearLayout parent, Button first, Button second) {
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, -2, 1);
        left.setMarginEnd(dp(8));
        parent.addView(first, left);
        parent.addView(second, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void compactDisclosure(String label, View content) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        disclosure(container, label, content);
        page.addView(container, insetMargin(8));
    }

    private void optionExplanation(LinearLayout parent, String label, String... paragraphs) {
        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        for (String paragraph : paragraphs) details.addView(noteBody(paragraph));
        disclosure(parent, label, details);
    }

    private void disclosure(LinearLayout parent, String label, View content) {
        final String key = selected + ":" + expeditionTab + ":inline:" + label;
        boolean expanded = expandedSections.getBoolean(key, false);
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        Button link = styledButton(label + (expanded ? "  ▴" : "  ▾"), Color.TRANSPARENT, accent(), Color.TRANSPARENT, null);
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        link.setPadding(0, dp(4), 0, dp(4));
        link.setElevation(0);
        link.setStateListAnimator(null);
        link.setContentDescription(label + (expanded ? "，已展開" : "，已收合"));
        link.setOnClickListener(v -> {
            boolean open = content.getVisibility() != View.VISIBLE;
            expandedSections.putBoolean(key, open);
            content.setVisibility(open ? View.VISIBLE : View.GONE);
            link.setText(label + (open ? "  ▴" : "  ▾"));
            link.setContentDescription(label + (open ? "，已展開" : "，已收合"));
        });
        parent.addView(link);
        parent.addView(content);
    }

    private void collapsibleSection(String label, View content) {
        final String key = selected + ":" + (selected == DISPATCH ? expeditionTab : 0) + ":" + label;
        boolean wasExpanded = expandedSections.getBoolean(key, false);
        LinearLayout box = cardBox();
        box.addView(content);
        box.setVisibility(wasExpanded ? View.VISIBLE : View.GONE);
        Button header = secondaryButton(label + (wasExpanded ? "  ▴" : "  ▾"), null);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setContentDescription(label + (wasExpanded ? "，已展開" : "，已收合"));
        if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(wasExpanded ? "已展開" : "已收合");
        header.setOnClickListener(v -> {
            boolean expand = box.getVisibility() != View.VISIBLE;
            expandedSections.putBoolean(key, expand);
            box.setVisibility(expand ? View.VISIBLE : View.GONE);
            header.setText(label + (expand ? "  ▴" : "  ▾"));
            header.setContentDescription(label + (expand ? "，已展開" : "，已收合"));
            if (Build.VERSION.SDK_INT >= 30) header.setStateDescription(expand ? "已展開" : "已收合");
            if (selected == DISPATCH && expeditionTab == 0) refreshDispatch();
        });
        page.addView(header, insetMargin(8));
        page.addView(box, margin(18, 6, 18, 0));
    }

    private TextView title(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(28);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(primary());
        view.setPadding(0, dp(4), dp(8), 0);
        return view;
    }

    private TextView subtitle(String value) {
        TextView view = body(value);
        view.setTextColor(secondary());
        view.setPadding(dp(18), dp(5), dp(18), dp(4));
        return view;
    }

    private TextView note(String value) {
        TextView view = noteBody(value);
        view.setPadding(dp(18), dp(8), dp(18), dp(4));
        return view;
    }

    private TextView noteBody(String value) {
        TextView view = body(value);
        view.setTextSize(14);
        view.setTextColor(secondary());
        return view;
    }

    private TextView fieldLabel(String value) {
        TextView view = body(value);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setTextColor(secondary());
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView body(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(16);
        view.setTextColor(primary());
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private TextView liveBody(String value) {
        TextView view = body(value);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return view;
    }

    private TextView mono(String value) {
        TextView view = body(value);
        view.setTextSize(13);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(secondary());
        return view;
    }

    private TextView feedbackText() {
        TextView view = liveBody("");
        view.setTextSize(14);
        view.setVisibility(View.GONE);
        view.setPadding(0, dp(8), 0, dp(2));
        return view;
    }

    private Button primaryButton(String text, View.OnClickListener click) {
        return styledButton(text, accent(), onAccent(), accent(), click);
    }

    private Button secondaryButton(String text, View.OnClickListener click) {
        return styledButton(text, selectedSurface(), accent(), Color.TRANSPARENT, click);
    }

    private Button dangerButton(String text, View.OnClickListener click) {
        return styledButton(text, surface(), danger(), danger(), click);
    }

    private Button styledButton(String text, int fill, int textColor, int stroke, View.OnClickListener click) {
        return AppButtonStyle.custom(this, text, fill, textColor, stroke, click);
    }

    private Drawable ripple(int fill, int stroke, int radius) {
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

    private void showDispatchFeedback(String text, boolean error) {
        runOnUiThread(() -> showFeedback(dispatchFeedback, text, error));
    }

    private void showFlowerFeedback(String text, boolean error) {
        runOnUiThread(() -> showFeedback(flowerFarmFeedback, text, error));
    }

    private void showExpeditionFeedback(String text, boolean error) {
        if (expeditionTab == 1) showFlowerFeedback(text, error);
        else showDispatchFeedback(text, error);
    }

    private void showSystemFeedback(String text, boolean error) {
        runOnUiThread(() -> showFeedback(systemFeedback, text, error));
    }

    private void showFeedback(TextView view, String text, boolean error) {
        if (view == null) return;
        view.setText(text);
        view.setTextColor(error ? danger() : secondary());
        view.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showInline(TextView view, String text, boolean error) {
        runOnUiThread(() -> {
            if (view == null) return;
            view.setText(text);
            view.setTextColor(error ? danger() : primary());
        });
    }

    private void setButtonEnabled(Button button, boolean enabled) {
        AppButtonStyle.setEnabled(button, enabled);
    }

    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams insetMargin(int top) { return margin(18, top, 18, 0); }
    private LinearLayout.LayoutParams sideMargin() { return margin(18, 0, 18, 0); }

    private int color(String name, int fallback) {
        int id = getResources().getIdentifier(name, "color", getPackageName());
        return id == 0 ? fallback : getColor(id);
    }

    private int background() { return color("app_background", Color.rgb(247, 248, 252)); }
    private int surface() { return color("app_surface", Color.WHITE); }
    private int surfaceVariant() { return color("app_surface_variant", Color.rgb(237, 241, 246)); }
    private int primary() { return color("app_text_primary", Color.rgb(25, 30, 40)); }
    private int secondary() { return color("app_text_secondary", Color.rgb(83, 96, 114)); }
    private int accent() { return color("app_accent", Color.rgb(0, 101, 184)); }
    private int onAccent() { return color("app_on_accent", Color.WHITE); }
    private int danger() { return color("app_danger", Color.rgb(179, 38, 30)); }
    private int onDanger() { return color("app_on_danger", Color.WHITE); }
    private int outline() { return color("app_outline", Color.rgb(203, 212, 223)); }
    private int selectedSurface() { return color("app_selected", Color.rgb(221, 238, 255)); }
    private int withAlpha(int value, int alpha) { return Color.argb(alpha, Color.red(value), Color.green(value), Color.blue(value)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void setChecked(CompoundButton view, boolean checked) {
        if (view == null) return;
        refreshing = true;
        try { view.setChecked(checked); }
        finally { refreshing = false; }
    }

    private void toast(String text) {
        runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_LONG).show());
    }
}

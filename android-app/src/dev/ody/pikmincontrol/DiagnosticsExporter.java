package dev.ody.pikmincontrol;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Collects everything needed to diagnose a batch/dispatch problem into one
 * plain-text file.
 *
 * Every investigation this project has needed so far (the CRLF service.sh
 * failure, the stale GPS fallback, the native confirmation lock, the RPC
 * faults) started by pulling the same handful of TSVs and hashes over ADB by
 * hand. This does that collection on-device so a report can be produced
 * without a laptop, and so the exact module/app versions are captured
 * alongside the logs rather than reconstructed afterwards.
 *
 * Read-only: it never changes a control file, and it deliberately captures
 * dispatch mode rather than touching it.
 */
final class DiagnosticsExporter {
    private static final String GAME_FILES = "/data/user/0/com.nianticlabs.pikmin/files";
    private static final String MODULE = "/data/adb/modules/pikmin_nectar_rpc";

    private DiagnosticsExporter() {}

    /** Returns the written file so the caller can show its path. */
    static File export(Context context) throws Exception {
        return export(context, false);
    }

    static File export(Context context, boolean includeRaw) throws Exception {
        File out = File.createTempFile("pikmin-diagnostics-", ".txt", context.getCacheDir());
        DiagnosticsExporter collector = new DiagnosticsExporter();
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8")) {
            w.write("Pikmin Control Center 診斷包 / schema 2\n");
            w.write("產生時間：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(new Date()) + "\n");
            w.write(includeRaw ? "包含原始座標、任務與日誌，分享前請檢查。\n" : "僅環境摘要，不含座標、任務內容與原始日誌。\n");
            section(w, "環境", EnvironmentGateway.read(context).details);
            section(w, "唯讀查詢統計", (ReadOnlyQueryPolicy.enabled(context)?"唯讀合併已開啟\n":"原始查詢模式\n")+RootQueryStats.summary());
            section(w, "最近育苗診斷", PlanterReportRepairGateway.last(context)+"\n"+PlanterReportRepairGateway.history(context));
            java.util.List<FarmRunReport> reports = FarmReports.list(context);
            if (!reports.isEmpty()) section(w, "最近花田結果摘要", reports.get(0).formatted(FlowerFarmService.isRunning(), System.currentTimeMillis()));
            if (!includeRaw) return out;
            collector.deadline = android.os.SystemClock.elapsedRealtime() + 25000;

            section(w, "版本與雜湊", collector.root(
                    "echo '--- control center ---'; dumpsys package dev.ody.pikmincontrol | grep -E 'versionName|versionCode' | head -2; "
                            + "echo '--- nectar module ---'; cat " + MODULE + "/module.prop 2>/dev/null; "
                            + "sha256sum " + MODULE + "/zygisk/arm64-v8a.so 2>/dev/null; "
                            + "echo '--- game ---'; dumpsys package com.nianticlabs.pikmin | grep -E 'versionName' | head -1"));

            // The safety-critical state: what mode the dispatcher is in, and
            // whether its mode file still has the 0644 the injected game
            // process needs to be able to read it at all.
            section(w, "控制檔狀態", collector.root(
                    "for f in /data/local/tmp/pikmin-dispatch-mode.txt /data/local/tmp/pikmin-dispatch-target.txt "
                            + "/data/local/tmp/pikmin-dispatch-ready.tsv " + GAME_FILES + "/nectar_rpc_mode.txt "
                            + GAME_FILES + "/return_rpc_mode.txt " + GAME_FILES + "/gps_copy_mode.txt "
                            + MODULE + "/nectar_control_mode.txt " + MODULE + "/return_control_mode.txt "
                            + MODULE + "/return_postcard_policy.txt " + MODULE + "/return_batch_limit.txt; do "
                            + "printf '%s: ' \"$f\"; if [ -f \"$f\" ]; then printf '%s ' \"$(stat -c %a \"$f\")\"; cat \"$f\"; else echo '(不存在)'; fi; done"));

            // A stale GPS file silently mis-sizes every distance gate, so its
            // age matters as much as its contents.
            section(w, "GPS 供給新鮮度", collector.root(
                    "f=" + GAME_FILES + "/nectar_system_gps.tsv; "
                            + "if [ -f $f ]; then echo \"age=$(( $(date +%s) - $(stat -c %Y $f) ))s\"; cat $f; else echo '(不存在)'; fi; "
                            // Match the script by its full installed path, and
                            // exclude anything whose command line merely
                            // mentions it -- this very export command contains
                            // the string, so a bare grep also matched the su/sh
                            // processes running it and buried the real loop.
                            + "echo '--- service.sh 迴圈 ---'; ps -A -o PID,ARGS 2>/dev/null "
                            + "| grep '" + MODULE + "/[s]ervice.sh' | grep -v 'com.android.commands' | grep -v ' -c ' || echo '(未執行)'; "
                            + "echo '--- 相關 App ---'; "
                            + "printf 'pikmin: '; pidof com.nianticlabs.pikmin || echo '(未執行)'; "
                            + "printf 'joystick: '; pidof " + JoystickAdapter.PACKAGE + " || echo '(未執行)'"));

            section(w, "育苗狀態／庫存／待確認／歷史", collector.root(planterFilesCommand()));
            section(w, "育苗程序與核心證據", collector.root(PlanterReportRepairGateway.readCommand()));
            section(w, "派遣歷史（最近 200 筆）", collector.root("tail -n 200 " + GAME_FILES + "/dispatch_history.tsv 2>/dev/null"));
            section(w, "沿路派遣診斷", collector.root("head -c 2049 " + GAME_FILES + "/route_dispatch_diagnostics.tsv 2>/dev/null"));
            section(w, "返程領取（最近 200 筆）", collector.root("tail -n 200 " + GAME_FILES + "/return_reward_history.tsv 2>/dev/null"));
            section(w, "批次狀態歷程（最近 200 筆）", collector.root("tail -n 200 /data/local/tmp/pikmin-dispatch-batch-status-history.tsv 2>/dev/null"));
            section(w, "到點距離量測（最近 100 筆）", collector.root("tail -n 100 /data/local/tmp/pikmin-dispatch-metrics.tsv 2>/dev/null"));
            section(w, "精華收取（最近 100 筆）", collector.root("tail -n 100 " + GAME_FILES + "/nectar_claims.tsv 2>/dev/null"));
            section(w, "候選清單快照", collector.root("cat " + GAME_FILES + "/dispatch_candidates.tsv 2>/dev/null"));
            // The native log is the only place RPC faults and hook-install
            // failures show up; without it a report can't distinguish "never
            // dispatched" from "dispatched and the game refused".
            section(w, "原生模組 log（最近 300 行）", collector.root("logcat -d -t 1500 -s PikminNectarRPC:* PikminRouteDispatch:* 2>/dev/null | tail -n 300"));
        }
        return out;
    }

    private static void section(Writer w, String title, String body) throws Exception {
        w.write("\n\n===== " + title + " =====\n");
        w.write(body == null || body.trim().isEmpty() ? "(無資料)\n" : body);
    }

    static String planterFilesCommand(){
        // Name each file: an archived .recovered ledger must never look like an active request.
        return "for f in /data/local/tmp/pikmin-planter-mode.txt /data/local/tmp/pikmin-planter-steps.txt "
            +GAME_FILES+"/planter_status.tsv "+GAME_FILES+"/planter_inventory.tsv "+GAME_FILES+"/planter_pending.tsv "
            +GAME_FILES+"/planter_history.tsv "+GAME_FILES+"/planter_recovery.tsv "+GAME_FILES+"/planter_pending.tsv.recovered "
            +"/data/local/tmp/pikmin-planter-recover.txt; do printf '\\n--- %s ---\\n' \"$f\"; "
            +"if test -e \"$f\"; then test -f \"$f\" && test -r \"$f\" && head -c 16385 \"$f\" 2>/dev/null"
            +" || echo '(讀取失敗)'; else echo '(不存在)'; fi; done";
    }

    /** Failures are captured into the report rather than aborting the export. */
    private long deadline;
    private String root(String command) {
        if (android.os.SystemClock.elapsedRealtime() >= deadline) return "(已達總讀取時限，略過)\n";
        try {
            BoundedCommand.Result r = BoundedCommand.root(command, 2, 32768);
            return r.text + (r.timedOut ? "\n(讀取逾時)" : !r.ok ? "\n(讀取不完整或檔案不存在)" : "")
                    + (r.truncated ? "\n(已截斷至 32 KB)" : "");
        } catch (Exception e) {
            return "(讀取失敗：" + e.getMessage() + ")\n";
        }
    }

    static String preview(File f) throws java.io.IOException {
        try (java.io.Reader r = new InputStreamReader(new java.io.FileInputStream(f), "UTF-8")) {
            char[] text = new char[4000]; int n = r.read(text);
            return n < 0 ? "(無資料)" : new String(text, 0, n) + (r.read() >= 0 ? "\n…預覽已截短，完整內容請另存。" : "");
        }
    }
}

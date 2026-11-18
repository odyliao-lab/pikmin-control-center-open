package dev.ody.pikmincontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Control and status bridge for native expedition dispatch. */
final class DispatchProbeGateway {
    private static final String CONTROL = "/data/local/tmp/pikmin-dispatch-mode.txt";
    private static final String KINDS = "/data/local/tmp/pikmin-dispatch-kinds.txt";
    private static final String STATUS = "/data/user/0/com.nianticlabs.pikmin/files/dispatch_probe_status.tsv";
    private static final String CANDIDATES = "/data/user/0/com.nianticlabs.pikmin/files/dispatch_candidates.tsv";
    private static final String TARGET_CANDIDATE = "/data/user/0/com.nianticlabs.pikmin/files/dispatch_target_candidate.tsv";
    private static final String HISTORY = "/data/user/0/com.nianticlabs.pikmin/files/dispatch_history.tsv";
    private static final String TARGET = "/data/local/tmp/pikmin-dispatch-target.txt";
    private static final String READY = "/data/local/tmp/pikmin-dispatch-ready.tsv";
    private static final String BATCH_STATUS = "/data/local/tmp/pikmin-dispatch-batch-status.tsv";
    private static final String BATCH_STATUS_HISTORY = "/data/local/tmp/pikmin-dispatch-batch-status-history.tsv";
    private static final int BATCH_STATUS_HISTORY_MAX_LINES = 800;
    private static final String METRICS = "/data/local/tmp/pikmin-dispatch-metrics.tsv";

    private DispatchProbeGateway() {}

    static DispatchPreflight.Observation preflight(Candidate candidate) throws Exception {
        String raw = root(ProbeSnapshot.command("pidof com.nianticlabs.pikmin || true",
                "cat /data/user/0/com.nianticlabs.pikmin/files/dispatch_availability.tsv 2>/dev/null || true"));
        String[] parts = ProbeSnapshot.sections(raw, 2);
        return DispatchPreflight.parse(parts[1], parts[0], candidate.taskId, candidate.kind, System.currentTimeMillis());
    }

    static boolean armed() throws Exception { return "armed".equals(root("cat " + CONTROL + " 2>/dev/null || printf off").trim()); }
    static void setArmed(boolean armed) throws Exception {
        root("printf '" + (armed ? "armed" : "off") + "\\n' > " + CONTROL + "; chmod 0644 " + CONTROL);
    }
    /** Flower-farm mode admits fruit and seed but never gifts. */
    static void setFarmArmed(boolean enabled) throws Exception {
        if (enabled) {
            GpsModuleGateway.farmRead("printf 'farm\\n' > " + KINDS + " && printf 'armed\\n' > " + CONTROL
                    + " && chmod 0644 " + KINDS + " " + CONTROL);
        } else {
            GpsModuleGateway.farmRead("printf 'off\\n' > " + CONTROL + " && printf 'all\\n' > " + KINDS
                    + " && chmod 0644 " + CONTROL + " " + KINDS);
        }
    }
    static void beginBatch(String taskId) throws Exception {
        String id = safeTaskId(taskId);
        root("printf 'batch\\n' > " + CONTROL + "; printf '" + id + "\\n' > " + TARGET
                + "; rm -f " + READY + "; chmod 0644 " + CONTROL + " " + TARGET);
    }
    static void markArrivalReady(String taskId, long readyMs) throws Exception {
        String id = safeTaskId(taskId);
        root("printf '" + id + "\\t" + readyMs + "\\n' > " + READY + "; chmod 0644 " + READY);
    }
    static void stopBatch() throws Exception {
        root("printf 'off\\n' > " + CONTROL + "; rm -f " + TARGET + " " + READY + "; chmod 0644 " + CONTROL);
    }
    /**
     * writeBatchStatus() is called roughly once a second throughout an arrival
     * wait, but only ever overwrote a single-row file — so once an item moved
     * on, whatever state it had been stuck in was gone. On 2026-08-29 a batch
     * left unsupervised produced one item with a 251947 ms duration reading
     * and five straight arrival-timeouts, and it was impossible to tell
     * afterward which of "settle window too short" / "native scan never
     * arrived" / "game stopped running" actually happened. This appends every
     * transition to a rolling log as well, trimmed so an unattended run of
     * many items cannot grow it without bound.
     */
    static void writeBatchStatus(String phase, int position, int total, String taskId, String detail) throws Exception {
        String row = System.currentTimeMillis() + "\\t" + safeWord(phase) + "\\t" + position + "\\t" + total + "\\t"
                + safeTaskId(taskId) + "\\t" + safeWord(detail);
        root("printf '" + row + "\\n' > " + BATCH_STATUS + "; printf '" + row + "\\n' >> " + BATCH_STATUS_HISTORY
                + "; tail -n " + BATCH_STATUS_HISTORY_MAX_LINES + " " + BATCH_STATUS_HISTORY + " > " + BATCH_STATUS_HISTORY + ".tmp"
                + " && mv " + BATCH_STATUS_HISTORY + ".tmp " + BATCH_STATUS_HISTORY
                + "; chmod 0644 " + BATCH_STATUS + " " + BATCH_STATUS_HISTORY);
    }
    static void appendControllerHistory(String event, Candidate candidate, long durationMs, int pickedCount) throws Exception {
        root("printf '" + System.currentTimeMillis() + "\\t" + safeWord(event) + "\\t" + safeWord(candidate.kind) + "\\t"
                + safeTaskId(candidate.taskId) + "\\t" + durationMs + "\\t" + pickedCount + "\\n' >> " + HISTORY + "; chmod 0644 " + HISTORY);
    }

    static String status() throws Exception {
        String row = root("cat " + STATUS + " 2>/dev/null || true").trim();
        String[] f = row.split("\\t", 4);
        if (f.length < 4) return "等待遊戲載入探險清單。";
        String mode = f[1], result = f[3];
        if ("observed".equals(mode)) return "自動派遣已關閉；已掃描 " + f[2] + " 筆未出發的花苗／水果／禮物盒任務。";
        if ("start-requested".equals(result)) return "已向遊戲送出派遣請求；等待探險清單同步。";
        if ("selection-applied".equals(result)) return "自動派遣已啟用；已掃描 " + f[2] + " 筆任務，最近一筆已套用最快隊伍並等待遊戲狀態更新。";
        return "自動派遣已啟用；已掃描 " + f[2] + " 筆未出發的花苗／水果／禮物盒任務。";
    }

    static String candidates() throws Exception {
        String raw = root("cat " + CANDIDATES + " 2>/dev/null || true").trim();
        if (raw.isEmpty()) return "目前沒有未出發的水果或花苗候選。";
        StringBuilder out = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm", Locale.TAIWAN);
        for (String line : raw.split("\\n")) {
            String[] f = line.split("\\t", 12);
            if (f.length < 8) continue;
            long seen = number(f[0]), expiration = number(f[4]);
            if (out.length() > 0) out.append('\n');
            out.append(seen > 0 ? time.format(new Date(seen)) + " " : "")
                    .append("seed".equals(f[1]) ? "［盆］花苗" : "［果］水果");
            boolean hasLocation = !"0".equals(f[5]) || !"0".equals(f[6]);
            if (hasLocation) out.append(" · ").append(f[5]).append(',').append(f[6]);
            if (expiration > 0) out.append(" · 到期 ").append(time.format(new Date(expiration)));
            if (f.length >= 9 && number(f[8]) >= 0) out.append(" · 距離 ").append(String.format(Locale.TAIWAN, "%.0f", Double.parseDouble(f[8]))).append("m");
            long duration = f.length >= 10 ? number(f[9]) : 0;
            double candidateDistance = f.length >= 9 ? decimal(f[8]) : -1;
            if (duration > 0) out.append("\n  遊戲預估往返：")
                    .append(String.format(Locale.TAIWAN, "%d分%02d秒", duration / 60000, (duration / 1000) % 60))
                    .append(duration <= 300000 ? "（5分鐘內；派遣前仍重驗）" : "（超過5分鐘）");
            else out.append(hasLocation ? "\n  預估派遣時間：尚未開啟該任務的皮克敏選擇器" : "\n  座標、到期時間：待下一階段擷取");
            if (f.length >= 11) out.append(" · ").append("1".equals(f[10]) ? "可由遊戲出發" : "目前不可出發");
            if (f.length >= 12 && number(f[11]) > 0) out.append(" · 最快隊伍 ").append(f[11]).append(" 隻");
        }
        return out.length() == 0 ? "候選資料格式尚未辨識。" : out.toString();
    }

    /**
     * @param maxMetres      0 or less means no distance limit
     * @param sortByDistance nearest first instead of soonest-to-expire
     *
     * The scanned list mixes friends' gift boxes thousands of kilometres away
     * with fruit a few metres off, and expiry order interleaves them, so
     * picking out "the nearby ones" meant reading the whole list. Distance
     * comes from column 8, which the native module writes as -1 when the game
     * has not resolved one yet; those sort last and survive any filter rather
     * than being silently dropped, since an unknown distance is not the same
     * as a far one.
     */
    static List<Candidate> selectedCandidates(boolean includeSeed, boolean includeFruit, boolean includeGift,
                                              double maxMetres, boolean sortByDistance) throws Exception {
        String raw = root("cat " + CANDIDATES + " 2>/dev/null || true").trim();
        String[] availability = null;
        if (includeGift) {
            availability = ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(
                    "pidof com.nianticlabs.pikmin || true",
                    "cat /data/user/0/com.nianticlabs.pikmin/files/dispatch_availability.tsv 2>/dev/null || true"), 6, 32768), 2);
        }
        ArrayList<Candidate> result = new ArrayList<>();
        for (String line : raw.split("\\n")) {
            String[] f = line.split("\\t", 12);
            if (f.length < 8) continue;
            String kind = f[1].trim(); boolean seed = "seed".equals(kind), fruit = "fruit".equals(kind), gift = "gift".equals(kind);
            if ((!seed && !fruit && !gift) || (seed && !includeSeed) || (fruit && !includeFruit) || (gift && !includeGift)) continue;
            long expiration = number(f[4]);
            double lat = decimal(f[5]), lng = decimal(f[6]);
            if (f[2].isEmpty() || lat == 0.0 && lng == 0.0) continue;
            double metres = f.length >= 9 ? decimal(f[8]) : -1.0;
            if (maxMetres > 0 && metres >= 0 && metres > maxMetres) continue;
            Candidate candidate = new Candidate(f[2], kind, expiration, lat, lng, metres);
            DispatchPreflight.Observation state = gift && availability != null
                    ? DispatchPreflight.parse(availability[1], availability[0], candidate.taskId, candidate.kind, System.currentTimeMillis()) : null;
            if (state != null && state.alreadyDispatched()) continue;
            result.add(candidate);
        }
        if (sortByDistance) result.sort((a, b) -> Double.compare(sortableDistance(a.distanceMetres), sortableDistance(b.distanceMetres)));
        else result.sort((a, b) -> Long.compare(normalizedExpiry(a.expirationMs), normalizedExpiry(b.expirationMs)));
        return result;
    }
    private static double sortableDistance(double metres) { return metres < 0 ? Double.MAX_VALUE : metres; }

    static boolean historyContainsSince(String event, String taskId, long notBeforeMs) throws Exception {
        String raw = root("tail -n 200 " + HISTORY + " 2>/dev/null || true");
        return DispatchConfirmation.historyContainsSince(raw, event, taskId, notBeforeMs);
    }

    static DispatchConfirmation.Evidence evidenceSince(String taskId, long notBeforeMs, boolean includeRewards) throws Exception {
        String raw = root(ProbeSnapshot.command("tail -n 200 " + HISTORY + " 2>/dev/null || true",
                includeRewards ? ReturnRewardModuleGateway.historyReadCommand() : "true"));
        String[] parts = ProbeSnapshot.sections(raw, 2);
        return new DispatchConfirmation.Evidence(parts[0], parts[1], taskId, notBeforeMs);
    }

    /**
     * One candidate row exactly as the native scanner last saw it.  Column 8
     * is the game's own distance to the task and column 9 its predicted round
     * trip.  The server derives travel time from the location the game itself
     * reports, so this — not Android's mock provider — is the view that
     * decides how long an expedition takes.
     */
    static GameSide gameSide(String taskId) throws Exception {
        return parseGameSide(root(candidateReadCommand()), taskId);
    }
    static String candidateReadCommand() {
        // The optional short-lived target projection supplements the complete
        // list. An old core simply has no target file and keeps its old pace.
        return "cat " + TARGET_CANDIDATE + " 2>/dev/null || true; cat " + CANDIDATES + " 2>/dev/null || true";
    }
    static GameSide parseGameSide(String raw, String taskId) {
        GameSide latest = null;
        boolean conflict = false;
        for (String line : raw.split("\\n")) {
            String[] f = line.split("\\t", 12);
            if (f.length < 12 || !taskId.equals(f[2])) continue;
            long observed = number(f[0]);
            if (observed <= 0) continue;
            GameSide row = new GameSide(observed, decimal(f[8]), number(f[9]), "1".equals(f[10]));
            if (latest == null || observed > latest.observedMs) {
                latest = row;
                conflict = false;
            } else if (observed == latest.observedMs &&
                    (Double.compare(row.metres, latest.metres) != 0 || row.durationMs != latest.durationMs ||
                            row.canStart != latest.canStart)) {
                conflict = true;
            }
        }
        // Two incompatible observations with the same timestamp cannot be
        // ordered safely; wait for a later native projection instead.
        return conflict ? null : latest;
    }

    /** Records the game-side distance actually achieved against the duration the game predicted there. */
    static void appendMetric(String phase, Candidate candidate, double metres, long durationMs) throws Exception {
        root("printf '" + System.currentTimeMillis() + "\\t" + safeWord(phase) + "\\t" + safeWord(candidate.kind) + "\\t"
                + safeTaskId(candidate.taskId) + "\\t" + String.format(Locale.US, "%.1f", metres) + "\\t" + durationMs
                + "\\n' >> " + METRICS + "; chmod 0644 " + METRICS);
    }

    /**
     * The "N 秒前" figure is the point: a live batch keeps this low (writes
     * happen every 1-5 s during any active phase), so a number that only
     * climbs is a stuck batch, not just an inactive one -- distinguishing
     * those two was previously impossible without switching tabs away and
     * back to force a re-read.
     */
    static String batchStatus() throws Exception {
        String raw = root("cat " + BATCH_STATUS + " 2>/dev/null || true").trim();
        if (raw.isEmpty()) return "尚未建立批次。";
        String[] f = raw.split("\\t", 6);
        if (f.length < 6) return "批次狀態格式尚未辨識。";
        long ageSeconds = Math.max(0, (System.currentTimeMillis() - number(f[0])) / 1000);
        return "批次 " + f[2] + "/" + f[3] + "：" + f[1] + " · " + f[5] + "（更新於 " + ageSeconds + " 秒前）";
    }

    /**
     * Rolls the dispatch history up into totals. Counts distinct task ids
     * rather than raw events, since one task can appear several times across
     * retries -- an outcome is attributed by the strongest event seen for
     * that task, so a task that faulted twice and then confirmed counts once,
     * as a success.
     */
    static String dispatchStats() throws Exception {
        return formatStats(root(statsCommand()).trim(),System.currentTimeMillis());
    }
    static String statsCommand() { return "tail -n 2000 " + HISTORY + " 2>/dev/null || true"; }
    static String formatStats(String raw,long now) {
        if (raw.isEmpty()) return "尚無派遣紀錄。";
        long dayCutoff = now - 86_400_000L;
        HashMap<String, String> outcome = new HashMap<>();
        HashMap<String, String> kinds = new HashMap<>();
        HashMap<String, Long> durations = new HashMap<>();
        int rpcFaults = 0, startRequests = 0;
        for (String line : raw.split("\\n")) {
            String[] f = line.split("\\t", 6);
            if (f.length < 6 || number(f[0]) < dayCutoff) continue;
            String id = f[3], event = f[1];
            if (!"unknown".equals(f[2])) kinds.put(id, f[2]);
            if ("start-requested".equals(event)) {
                ++startRequests;
                long duration = number(f[4]);
                // Keep the successful attempt's duration, not a failed one's.
                if (duration > 0) durations.put(id, duration);
            }
            if ("start-rpc-faulted".equals(event)) ++rpcFaults;
            // Strongest-outcome wins: confirmed beats skipped beats timed out.
            boolean confirmed = "inventory-confirmed".equals(event) || "inventory-confirmed-absent".equals(event);
            if (confirmed) outcome.put(id, "confirmed");
            else if ("gift-pikmin-unavailable".equals(event) && !"confirmed".equals(outcome.get(id))) outcome.put(id, "skipped");
            else if (!outcome.containsKey(id)) outcome.put(id, "pending");
        }
        if (outcome.isEmpty()) return "最近2000筆範圍內 · 24小時沒有派遣紀錄。";
        int confirmed = 0, skipped = 0, unfinished = 0;
        HashMap<String, Integer> confirmedByKind = new HashMap<>();
        for (java.util.Map.Entry<String, String> e : outcome.entrySet()) {
            if ("confirmed".equals(e.getValue())) {
                ++confirmed;
                String kind = kinds.getOrDefault(e.getKey(), "unknown");
                confirmedByKind.merge(kind, 1, Integer::sum);
            } else if ("skipped".equals(e.getValue())) ++skipped;
            else ++unfinished;
        }
        StringBuilder out = new StringBuilder("最近2000筆範圍內 · 24小時\n派遣成功 ").append(confirmed).append(" 筆");
        if (!confirmedByKind.isEmpty()) {
            out.append("（");
            boolean first = true;
            for (String kind : new String[]{"seed", "fruit", "gift"}) {
                Integer n = confirmedByKind.get(kind);
                if (n == null) continue;
                if (!first) out.append(' ');
                out.append("seed".equals(kind) ? "花苗" : "fruit".equals(kind) ? "水果" : "禮物盒").append(n);
                first = false;
            }
            out.append("）");
        }
        if (skipped > 0) out.append("\n指定皮克敏不可用略過 ").append(skipped).append(" 筆");
        if (unfinished > 0) out.append("\n未完成 ").append(unfinished).append(" 筆");
        if (startRequests > 0) {
            out.append("\n送出請求 ").append(startRequests).append(" 次");
            if (rpcFaults > 0) {
                out.append("、其中 RPC 失敗 ").append(rpcFaults).append(" 次（")
                        .append(Math.round(rpcFaults * 100.0 / startRequests)).append("%，已自動重試）");
            }
        }
        if (!durations.isEmpty()) {
            long sum = 0, max = 0;
            for (long d : durations.values()) { sum += d; if (d > max) max = d; }
            out.append("\n遊戲往返時間：平均 ").append(sum / durations.size()).append(" ms、最長 ").append(max).append(" ms");
        }
        return out.toString();
    }

    /** Every status transition this session, newest first — see writeBatchStatus() for why this exists. */
    static String batchStatusHistoryFormatted() throws Exception {
        String raw = root("tail -n 200 " + BATCH_STATUS_HISTORY + " 2>/dev/null || true").trim();
        if (raw.isEmpty()) return "尚無批次狀態歷程。";
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN);
        String[] lines = raw.split("\\n");
        StringBuilder out = new StringBuilder();
        for (int index = lines.length - 1; index >= 0; index--) {
            String[] f = lines[index].split("\\t", 6);
            if (f.length < 6) continue;
            long when = number(f[0]);
            if (out.length() > 0) out.append('\n');
            String detail = detailLabel(f[5]);
            out.append(when > 0 ? time.format(new Date(when)) : f[0]).append("  ")
                    .append(f[2]).append('/').append(f[3]).append(' ')
                    .append(phaseLabel(f[1]));
            if (!detail.isEmpty() && !detail.equals(phaseLabel(f[1]))) out.append(' ').append(detail);
        }
        return out.toString();
    }

    static String historyFormatted() throws Exception {
        String raw = root("tail -n 240 " + HISTORY + " 2>/dev/null || true").trim();
        if (raw.isEmpty()) return "尚無批次派遣紀錄。";
        String[] lines = raw.split("\\n");
        HashMap<String, String[]> starts = new HashMap<>();
        for (String line : lines) {
            String[] f = line.split("\\t", 6);
            if (f.length >= 6 && "start-requested".equals(f[1])) starts.put(f[3], f);
        }
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN);
        StringBuilder out = new StringBuilder(); int shown = 0;
        for (int index = lines.length - 1; index >= 0 && shown < 100; index--) {
            String[] f = lines[index].split("\\t", 6); if (f.length < 6) continue;
            String[] start = starts.get(f[3]); String kind = "unknown".equals(f[2]) && start != null ? start[2] : f[2];
            long duration = number(f[4]); int picked = (int) number(f[5]);
            if (("inventory-confirmed".equals(f[1]) || "inventory-confirmed-absent".equals(f[1])) && start != null) {
                duration = number(start[4]); picked = (int) number(start[5]);
            }
            if (out.length() > 0) out.append('\n');
            long when = number(f[0]); out.append(when > 0 ? time.format(new Date(when)) : f[0]).append(' ')
                    .append("seed".equals(kind) ? "［盆］" : "fruit".equals(kind) ? "［果］" : "gift".equals(kind) ? "［禮］" : "［？］")
                    .append(' ').append(historyEvent(f[1]));
            if (duration > 0) out.append(" · 原生 ").append(durationText(duration));
            if (picked > 0) out.append(" · 最快 ").append(picked).append(" 隻");
            ++shown;
        }
        return out.toString();
    }

    /** Must match DispatchBatchService.ARRIVAL_TARGET_METRES -- appendMetric is only ever
     *  called after waitForArrival() already succeeded, so this reflects that same gate
     *  rather than a phase label (nothing currently records a missed attempt). */
    private static final double METRIC_ARRIVAL_TARGET_METRES = 4.0;

    static String metricsFormatted() throws Exception {
        String raw = root("tail -n 60 " + METRICS + " 2>/dev/null || true").trim();
        if (raw.isEmpty()) return "尚無到點距離量測紀錄。";
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN);
        String[] lines = raw.split("\\n");
        StringBuilder out = new StringBuilder();
        for (int index = lines.length - 1; index >= 0; index--) {
            String[] f = lines[index].split("\\t", 6);
            if (f.length < 6) continue;
            long when = number(f[0]), duration = number(f[5]);
            double metres = decimal(f[4]);
            if (out.length() > 0) out.append('\n');
            out.append(when > 0 ? time.format(new Date(when)) : f[0]).append(' ')
                    .append("seed".equals(f[2]) ? "［盆］" : "fruit".equals(f[2]) ? "［果］" : "［禮］")
                    .append(metres >= 0.0 && metres <= METRIC_ARRIVAL_TARGET_METRES ? " 已到目標半徑" : " 未達目標半徑")
                    .append(" · 遊戲端 ").append(f[4]).append("m")
                    .append(duration > 0 ? " · 遊戲預估往返 " + duration + "ms" : " · 遊戲尚未給出預估");
        }
        return out.toString();
    }

    private static long number(String value) { try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; } }
    private static double decimal(String value) { try { return Double.parseDouble(value); } catch (NumberFormatException ignored) { return 0.0; } }
    private static long normalizedExpiry(long value) { return value > 0 ? value : Long.MAX_VALUE; }
    private static String safeTaskId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{1,200}")) throw new IllegalArgumentException("任務 ID 格式不正確");
        return value;
    }
    private static String safeWord(String value) {
        if (value == null) return "";
        return value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }
    private static String historyEvent(String event) {
        if ("preflight-skipped".equals(event)) return "可用性複查確認，起飛前略過";
        if ("gps-requested".equals(event)) return "已請 GPS 飛往目標";
        if ("arrival-ready".equals(event)) return "已確認到點，準備派遣";
        if ("start-requested".equals(event)) return "已通知遊戲開始派遣";
        if ("start-rpc-completed".equals(event)) return "遊戲已接受派遣請求";
        if ("start-rpc-faulted".equals(event)) return "遊戲拒絕了這次派遣，準備重試";
        if ("inventory-confirmed".equals(event) || "inventory-confirmed-absent".equals(event)) return "已由庫存確認出發";
        if ("arrival-timeout".equals(event)) return "到點逾時，已略過";
        if ("game-refresh-timeout".equals(event)) return "等待遊戲更新清單逾時，已略過";
        if ("inventory-confirm-timeout".equals(event)) return "庫存一直沒確認出發，已略過";
        if ("gift-pikmin-unavailable".equals(event)) return "指定皮克敏仍不可用，已略過";
        return event;
    }
    private static String durationText(long durationMs) {
        long seconds = Math.max(0, durationMs / 1000); return (seconds / 60) + "分" + String.format(Locale.TAIWAN, "%02d", seconds % 60) + "秒";
    }

    /** Friendly label for a batch/probe phase name; the canonical copy used by the raw-history view. */
    static String phaseLabel(String phase) {
        switch (phase) {
            case "preflight": return "起飛前複查可用性";
            case "preflight-skipped": return "已略過，未起飛";
            case "handoff": return "準備派遣";
            case "awaiting-game-list": return "等待原生清單";
            case "arriving": return "移動到點中";
            case "gps-drift": return "位置飄移，重新校正";
            case "start-requested": return "已送出派遣，等待確認";
            case "gift-sync-grace": return "禮物盒同步緩衝中";
            case "gift-pikmin-unavailable": return "指定皮克敏不可用，已略過";
            case "rpc-faulted": return "派遣失敗，準備重試";
            case "confirmation-timeout": return "確認逾時";
            case "inventory-confirmed": return "已確認出發";
            case "post-dispatch-wait": return "短暫等待後前往下一筆";
            case "released": return "批次已結束";
            case "stopped": return "已安全停止";
            case "failed": return "發生錯誤";
            default: return phase;
        }
    }

    /**
     * Friendly Traditional-Chinese text for the machine-readable `detail`
     * string DispatchBatchService.status() writes alongside every phase --
     * things like "settling_game_cursor_9s" or "agreed_0.3m_0.3m_2_of_2".
     * Falls back to the raw string for anything unrecognised (e.g. an
     * exception class name under the "failed" phase) rather than hiding it.
     */
    static String detailLabel(String detail) {
        if ("confirming_availability".equals(detail)) return "等待第二次原生掃描確認";
        if ("gift-owner-busy".equals(detail)) return "指定皮克敏正在其他任務，起飛前略過";
        if ("no-available-team".equals(detail)) return "目前無可用隊員，起飛前略過";
        if (detail == null || detail.isEmpty()) return "";
        switch (detail) {
            case "next_task": return "準備前往下一筆";
            case "settle": return "稍微停留後再出發";
            case "no_queue": return "清單是空的";
            case "gps_control_released": return "已釋放 GPS 控制權";
            case "native_gate": return "等待遊戲確認可以出發";
            case "await_inventory_confirmation": return "等待庫存確認已出發";
            case "awaiting_game_list": return "等待遊戲更新清單";
            case "confirmation_impossible": return "遊戲拒絕了這次派遣";
            case "arrival_timeout": return "未能穩定到達目標";
            case "game_refresh_timeout": return "遊戲未更新可派遣任務";
            case "gift_pikmin_unavailable": return "指定皮克敏不可用";
            case "confirmation_timeout": return "庫存未確認出發";
            case "retrying": return "準備重試";
            case "giving_up": return "已達重試上限，略過這筆";
            case "still_rejected_after_sync_grace": return "指定皮克敏仍不可用";
            case "awaiting_native_scan": return "等待遊戲重新確認位置";
            case "arrival_read_error": return "定位讀取暫時失敗，重新確認";
            case "awaiting_post_settle_scan": return "等待穩定後的下一次確認";
            case "settling_game_cursor_0s": return "已到點，等遊戲穩定位置";
        }
        if (detail.startsWith("retrying_until_")) return "等待禮物盒同步中";
        if (detail.startsWith("awaiting_native_scan_")) {
            String reason = detail.contains("candidate_missing") ? "遊戲清單暫缺"
                    : detail.contains("distance_unknown") ? "遊戲距離未解析" : "遊戲掃描暫時過期";
            return reason + (detail.endsWith("_1_of_2") ? "，保留首次確認" : "，等待重新確認");
        }
        if (detail.startsWith("settling_game_cursor_")) {
            String seconds = detail.substring("settling_game_cursor_".length(), Math.max(0, detail.length() - 1));
            return "已到點，還要等約 " + seconds + " 秒讓遊戲穩定位置";
        }
        if (detail.startsWith("awaiting_distinct_native_scan_")) {
            String n = detail.substring("awaiting_distinct_native_scan_".length()).split("_of_")[0];
            return "位置已對上，等第 " + n + " / 2 次確認";
        }
        if (detail.startsWith("agreed_")) {
            String[] parts = detail.split("_");
            return parts.length >= 4 ? "位置已確認（第 " + parts[3] + " / 2 次）" : "位置已確認";
        }
        if (detail.startsWith("provider_") && detail.endsWith("m"))
            return "距離目標還有約 " + formatMetres(detail.substring("provider_".length(), detail.length() - 1));
        if (detail.startsWith("game_") && detail.endsWith("m"))
            return "遊戲回報距離還有約 " + formatMetres(detail.substring("game_".length(), detail.length() - 1));
        if (detail.startsWith("attempt_")) {
            String[] parts = detail.split("_");
            if (parts.length >= 4) return "第 " + parts[1] + " 次嘗試（最多 " + parts[3] + " 次）";
        }
        return detail;
    }
    private static String formatMetres(String raw) {
        try {
            double metres = Double.parseDouble(raw);
            return metres < 1000 ? String.format(Locale.TAIWAN, "%.0f 公尺", metres) : String.format(Locale.TAIWAN, "%.1f 公里", metres / 1000);
        } catch (NumberFormatException e) { return raw + " 公尺"; }
    }
    /** Game-side distance and predicted duration for one task, with the scan timestamp. */
    static final class GameSide {
        final long observedMs, durationMs; final double metres; final boolean canStart;
        GameSide(long observedMs, double metres, long durationMs, boolean canStart) {
            this.observedMs = observedMs; this.metres = metres; this.durationMs = durationMs; this.canStart = canStart;
        }
        /** The native module writes -1 when the game has not resolved a distance yet. */
        boolean known() { return metres >= 0.0; }
        /** Full scans remain at five seconds; batch target observations may arrive sooner. */
        boolean fresh(long nowMs, long maxAgeMs) { return observedMs > 0 && nowMs - observedMs <= maxAgeMs; }
    }

    static final class Candidate {
        final String taskId, kind; final long expirationMs; final double latitude, longitude;
        /** Negative when the game has not resolved a distance for this task yet. */
        final double distanceMetres;
        Candidate(String taskId, String kind, long expirationMs, double latitude, double longitude) {
            this(taskId, kind, expirationMs, latitude, longitude, -1.0);
        }
        Candidate(String taskId, String kind, long expirationMs, double latitude, double longitude, double distanceMetres) {
            this.taskId = taskId; this.kind = kind; this.expirationMs = expirationMs;
            this.latitude = latitude; this.longitude = longitude; this.distanceMetres = distanceMetres;
        }
        String label() { return "seed".equals(kind) ? "［盆］花苗" : "fruit".equals(kind) ? "［果］水果" : "［禮］禮物盒"; }
    }
    private static String root(String command) throws Exception {
        return BoundedCommand.checkedRoot(command,6,1048576);
    }
}

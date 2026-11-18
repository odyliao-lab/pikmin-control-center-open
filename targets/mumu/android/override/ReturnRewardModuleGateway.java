package dev.ody.pikmincontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Root bridge for the native return-reward Zygisk engine. */
final class ReturnRewardModuleGateway {
    private static final String CONTROL = "/data/local/tmp/pikmin-return-mode.txt";
    private static final String TRACE = "/data/adb/modules/pcc_emulator_bridge_canary/return_rpc_trace.tsv";
    // The native engine writes controller-visible audit data in Pikmin's
    // app-private files directory; the former module path was retired.
    private static final String HISTORY = "/data/user/0/com.nianticlabs.pikmin/files/return_reward_history.tsv";
    private static final String POSTCARD_POLICY = "/data/local/tmp/pikmin-return-postcard-policy.txt";
    private static final String STATUS = "/data/user/0/com.nianticlabs.pikmin/files/return_rpc_status.tsv";
    private static final String BATCH_LIMIT = "/data/local/tmp/pikmin-return-batch-limit.txt";
    private static final String COMPATIBILITY = "/data/user/0/com.nianticlabs.pikmin/files/compatibility_status.tsv";
    private static final Pattern RETURN_HONEY = Pattern.compile(
            "([a-z0-9-]+) x\\d+ honey=([a-z0-9:;-]+)");

    private ReturnRewardModuleGateway() {}

    static String historyReadCommand() { return "tail -n 200 " + HISTORY + " 2>/dev/null || true"; }
    static boolean collectedSince(String taskId, long notBeforeMs) throws Exception {
        return DispatchConfirmation.rewardCollectedSince(
                root("tail -n 200 " + HISTORY + " 2>/dev/null || true"), taskId, notBeforeMs);
    }

    static String mode() throws Exception {
        return root("cat " + CONTROL + " 2>/dev/null || printf dry-run").trim();
    }

    static void setMode(String mode) throws Exception {
        root(modeCommand(mode));
    }
    static String modeCommand(String mode) {
        if (!"dry-run".equals(mode) && !"one".equals(mode) && !"batch".equals(mode) && !"all".equals(mode) && !"off".equals(mode)) {
            throw new IllegalArgumentException("Unsupported return-reward mode");
        }
        return "printf '" + mode + "\\n' > " + CONTROL + "; chmod 0644 " + CONTROL;
    }

    static String postcardPolicy() throws Exception {
        String policy = root("cat " + POSTCARD_POLICY + " 2>/dev/null || printf keep").trim();
        return "discard".equals(policy) ? "discard" : "keep";
    }

    static void setPostcardPolicy(String policy) throws Exception {
        root(postcardCommand(policy));
    }
    static String postcardCommand(String policy) {
        if (!"keep".equals(policy) && !"discard".equals(policy)) {
            throw new IllegalArgumentException("Unsupported postcard policy");
        }
        return "printf '" + policy + "\\n' > " + POSTCARD_POLICY + "; chmod 0644 " + POSTCARD_POLICY;
    }

    static int batchLimit() throws Exception {
        String value = root("cat " + BATCH_LIMIT + " 2>/dev/null || printf 5").trim();
        try {
            int limit = Integer.parseInt(value);
            return limit >= 1 && limit <= 5 ? limit : 5;
        } catch (NumberFormatException ignored) {
            return 5;
        }
    }

    static void setBatchLimit(int limit) throws Exception {
        if (limit < 1 || limit > 5) throw new IllegalArgumentException("Batch limit must be 1 through 5");
        root("printf '" + limit + "\\n' > " + BATCH_LIMIT + "; chmod 0644 " + BATCH_LIMIT);
    }

    static boolean compatible() throws Exception {
        String row = root("cat " + COMPATIBILITY + " 2>/dev/null || true").trim();
        String[] fields = row.split("\\t", 4);
        return fields.length >= 2 && "compatible".equals(fields[1]);
    }

    static String compatibilitySummary() throws Exception {
        String row = root("cat " + COMPATIBILITY + " 2>/dev/null || true").trim();
        String[] fields = row.split("\\t", 4);
        if (fields.length < 4) return "相容性狀態尚未產生；收取控制已鎖定。";
        return "compatible".equals(fields[1])
                ? "原生相容性：已驗證 " + fields[0]
                : "原生相容性：不相容，收取控制已鎖定。";
    }

    static String healthSummary() throws Exception {
        String compatibility = compatibilitySummary();
        String policy = postcardPolicy();
        return compatibility + "\n返程模式：" + mode() + " ・ 明信片：" + policy
                + " ・ 批次上限：" + batchLimit() + " 筆\n" + statusSummary();
    }

    static String recentDetailedEvents() throws Exception {
        String history = root("tail -n 500 " + HISTORY + " 2>/dev/null || true").trim();
        if (history.isEmpty()) return root("tail -n 24 " + TRACE + " 2>/dev/null || true").trim();
        return formatDetailedEvents(history);
    }

    static String formatDetailedEvents(String history) {
        StringBuilder shown = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.TAIWAN);
        String[] lines = history.split("\\n");
        for (int index = lines.length - 1; index >= 0; --index) {
            String line = lines[index];
            String[] f = line.split("\\t", 9);
            if (f.length < 9 || (!"batch-confirmed".equals(f[1]) && !"one-confirmed".equals(f[1]))) continue;
            long epoch;
            try { epoch = Long.parseLong(f[0]); }
            catch (NumberFormatException ignored) { continue; }
            int before = shown.length();
            appendReadableRewards(shown, time.format(new Date(epoch)), f[8]);
            if (shown.length() == before) {
                if (shown.length() > 0) shown.append('\n');
                shown.append(time.format(new Date(epoch))).append(' ').append(taskLabel(f[8]));
            }
        }
        return shown.length() == 0 ? "尚無含獎勵內容的已確認收取紀錄。" : shown.toString();
    }

    private static void appendReadableRewards(StringBuilder shown, String when, String raw) {
        String rewards;
        if (raw.startsWith("bloomed-poi:")) rewards = raw.substring("bloomed-poi:".length());
        else if (raw.startsWith("expedition:fruit:")) rewards = raw.substring("expedition:fruit:".length());
        else if (raw.startsWith("carry:mushroom-fruit:")) rewards = raw.substring("carry:mushroom-fruit:".length());
        else if (raw.startsWith("carry:fruit:")) rewards = raw.substring("carry:fruit:".length());
        else return;
        for (String reward : rewards.split(",")) {
            Matcher match = RETURN_HONEY.matcher(reward.trim());
            if (!match.find()) continue;
            String fruit = fruitName(match.group(1));
            for (String honey : match.group(2).split(";")) {
                String[] fields = honey.split(":", 3);
                if (fields.length != 3) continue;
                if (shown.length() > 0) shown.append('\n');
                shown.append(when).append(" ［果］").append(flowerName(fields[0])).append('-').append(honeyColorName(fields[1]))
                        .append("精華 +").append(fields[2]).append('（').append(fruit).append('）');
                if (raw.startsWith("carry:mushroom-fruit:")) shown.append("・蘑菇獎勵");
                else if (raw.startsWith("carry:fruit:")) shown.append("・一般拾取");
            }
        }
    }

    private static String taskLabel(String raw) {
        if (raw.startsWith("carry:seed:") || raw.startsWith("expedition:seed:")) return seedLabel(raw);
        if (raw.startsWith("carry:mushroom-fruit:")) return "［果］蘑菇獎勵水果（精華細節未提供）";
        if (raw.startsWith("carry:fruit:")) return "［果］拾取水果（精華細節未提供）";
        if ("carry:resource".equals(raw)) return "［果］搬運資源（舊紀錄未解析）";
        if (raw.startsWith("expedition:gift:")) return giftLabel(raw);
        if ("expedition:postcard".equals(raw)) return "［禮］明信片";
        if (raw.startsWith("expedition:postcard-with-items:")) return "［禮］明信片禮物（內容未提供）";
        if (raw.startsWith("gift-task:")) return "［禮］禮物任務（內容未提供）";
        if (raw.startsWith("poi-challenge:")) return "［禮］挑戰獎勵（內容未提供）";
        if ("task-type-non-expedition".equals(raw)) return "［禮］一般返程任務（內容待解析）";
        if ("expedition-target-non-bloomed-poi".equals(raw)) return "［禮］非大花探險（內容待解析）";
        if ("bloomed-poi:no-fruit".equals(raw)) return "［果］無精華資料";
        // targetcase:9 is a live expedition flower-seed reward.  Older native
        // builds only exposed its case number, so it must not be presented as
        // an old record (or imply a colour/decor type we did not receive).
        if (raw.startsWith("expedition:targetcase:9:")) return "［盆］花苗（細節未擷取）";
        if (raw.startsWith("bloomed-poi:") || raw.startsWith("expedition:fruit:")) return "［果］果實（舊紀錄無精華細節）";
        return "［禮］返程獎勵（內容待解析）";
    }

    static String seedLabel(String raw) {
        int type = fieldAfter(raw, "seed:");
        int pikmin = fieldAfter(raw, "pikmin:");
        int asset = fieldAfter(raw, "asset:");
        int category = fieldAfter(raw, "category:");
        String color = pikminColor(pikmin);
        if ("未知".equals(color)) color = seedTypeColor(type);
        String label = "［盆］" + color + "花苗";
        String decoration = assetName(asset);
        if (!decoration.isEmpty()) return label + "－" + decoration;
        String categoryName = categoryName(category);
        return categoryName.isEmpty() || category == 1 ? label : label + "（" + categoryName + "）";
    }

    private static String giftLabel(String raw) {
        int category = fieldAfter(raw, "category:");
        String decoration = assetName(fieldAfter(raw, "asset:"));
        String categoryName = categoryName(category);
        if (raw.startsWith("expedition:gift:deco:")) {
            String color = pikminColor(fieldAfter(raw, "pikmin:"));
            String detail = !decoration.isEmpty() ? decoration
                    : (categoryName.isEmpty() || category == 1 ? "飾品" : categoryName);
            return "［禮］" + color + "皮克敏－" + detail;
        }
        if (raw.startsWith("expedition:gift:rare:")) {
            return "［禮］稀有飾品－" + (categoryName.isEmpty() ? "分類待確認" : categoryName);
        }
        return "［禮］禮物盒（內容待解析）";
    }

    private static int fieldAfter(String raw, String marker) {
        int start = raw.indexOf(marker);
        if (start < 0) return 0;
        start += marker.length();
        int end = raw.indexOf(':', start);
        try { return Integer.parseInt(end < 0 ? raw.substring(start) : raw.substring(start, end)); }
        catch (NumberFormatException ignored) { return 0; }
    }

    private static String pikminColor(int type) {
        switch (type) {
            case 1: return "紅色"; case 2: return "藍色"; case 3: return "黃色";
            case 4: return "白色"; case 5: return "紫色"; case 6: return "岩石";
            case 7: return "羽翼"; default: return "未知";
        }
    }

    private static String seedTypeColor(int type) {
        switch (type) {
            case 23: return "紅色"; case 24: return "藍色"; case 25: return "黃色";
            case 26: return "白色"; case 27: return "紫色"; case 28: return "岩石";
            case 29: return "羽翼"; default: return "";
        }
    }

    private static String categoryName(int category) {
        switch (category) {
            case 1: return "一般"; case 2: return "餐廳"; case 3: return "咖啡廳"; case 4: return "甜點";
            case 5: return "電影院"; case 6: return "藥局"; case 7: return "動物園"; case 8: return "森林";
            case 9: return "水邊"; case 10: return "郵局"; case 11: return "美術館"; case 12: return "機場";
            case 13: return "車站"; case 14: return "海灘"; case 15: return "漢堡店"; case 16: return "便利商店";
            case 17: return "超市"; case 18: return "麵包店"; case 19: return "美髮店"; case 20: return "服飾店";
            case 21: return "公園"; case 22: return "圖書館"; case 23: return "特別"; case 24: return "路邊";
            case 25: return "壽司店"; case 26: return "山岳"; case 27: return "體育場"; case 28: return "天氣";
            case 29: return "遊樂園"; case 30: return "公車站"; case 31: return "雪景"; case 32: return "義式餐廳";
            case 33: return "拉麵店"; case 34: return "橋樑"; case 35: return "飯店"; case 36: return "化妝品";
            case 37: return "神社"; case 38: return "電器行"; case 39: return "咖哩店"; case 40: return "五金行";
            case 41: return "大學"; case 42: return "墨西哥餐廳"; case 43: return "義大利麵店";
            case 44: return "洗衣店"; case 45: return "韓式餐廳"; case 46: return "文具";
            default: return "";
        }
    }

    // When a future native record includes FullAssetId, it is the actual
    // decoration; CategoryId DEFAULT merely means the general category.
    private static String assetName(int asset) {
        switch (asset) {
            case 2: return "餐廳"; case 3: return "咖啡廳"; case 4: return "甜點";
            case 5: return "電影院"; case 6: return "藥局"; case 7: return "動物園";
            case 8: return "森林"; case 10: return "海邊"; case 11: return "郵局";
            case 13: return "玩具飛機"; case 14: return "紙模型火車"; case 15: return "貝殼";
            case 16: return "漢堡"; case 17: return "零食"; case 18: return "瓶蓋";
            case 19: return "蘑菇"; case 20: return "香蕉"; case 21: return "水桶";
            case 22: return "剪刀"; case 23: return "髮夾"; case 24: return "四葉草";
            case 25: return "迷你書"; case 26: return "瑪利歐帽"; case 27: return "票券";
            case 28: case 29: case 30: case 31: return "貼紙"; case 32: return "農曆新年";
            case 33: return "四葉草（稀有）"; case 34: return "壽司"; case 35: return "西洋棋";
            case 39: return "山岳"; case 40: return "體育場"; case 41: return "雨滴";
            case 44: return "滑板"; case 45: case 46: return "遊樂園"; case 47: return "公車玩具";
            case 48: return "花札"; case 49: return "萬聖節"; case 51: return "安全帽";
            case 52: return "雪景"; case 53: return "手套"; case 54: return "新年";
            case 56: case 57: return "禮物貼紙"; case 58: return "披薩"; case 59: return "金幣";
            case 60: return "復活節"; case 61: return "拉麵"; case 62: return "球鞋";
            case 63: return "橋樑"; case 64: return "甜甜圈"; case 67: return "火箭";
            case 68: return "麻將"; case 69: return "飯店"; case 70: return "化妝品";
            case 71: return "冰淇淋"; default: return "";
        }
    }

    private static String fruitName(String value) {
        if (value == null) return "未知果實";
        switch (value) {
            case "strawberry": return "草莓"; case "orange": return "橘子"; case "cherry": return "櫻桃";
            case "melon": return "哈密瓜"; case "peach": return "桃子"; case "lemon": return "檸檬";
            case "blueberry": return "藍莓"; case "watermelon": return "西瓜"; case "lime": return "萊姆";
            case "muscat-one": case "muscat": return "麝香葡萄"; case "kiwi": case "cut-kiwi": return "奇異果";
            case "green-apple": return "青蘋果"; case "kumquat": return "金桔"; case "banana": return "香蕉";
            case "grapefruit": return "葡萄柚"; case "apple": return "蘋果"; case "prune": return "梅子";
            case "grapes": return "葡萄"; case "happy-fruit": return "幸運果實"; case "fig": return "無花果";
            case "loquat": return "枇杷"; case "mangosteen": return "山竹"; default: return "未知果實";
        }
    }

    private static String flowerName(String value) {
        if (value == null || value.isEmpty() || "unknown-flower".equals(value)) return "";
        switch (value) {
            case "sunflower": return "向日葵"; case "tulip": case "tulip2026": return "鬱金香";
            case "pansy": return "三色堇"; case "rose": case "rose2025": return "玫瑰"; case "common": return "普通花";
            case "poinsettia": return "聖誕紅"; case "camellia": return "山茶花"; case "plumblossom": return "梅花";
            case "narcissus": return "水仙"; case "cherryblossom": return "櫻花"; case "nemophila": return "粉蝶花";
            case "carnation": return "康乃馨"; case "callalily": return "海芋"; case "hydrangea": return "繡球花";
            case "lilium": return "百合"; case "hibiscus": return "扶桑花"; case "plumeria": return "雞蛋花";
            case "clusteramaryllis": return "彼岸花"; case "cosmos": return "大波斯菊"; case "cyclamen": return "仙客來";
            case "anemone": return "銀蓮花"; case "dianthus": return "石竹"; case "gentian": return "龍膽";
            case "chrysanthemum": return "菊花"; case "hellebore": return "聖誕玫瑰"; case "cattleya": return "嘉德麗雅蘭";
            case "hyacinth": return "風信子"; case "sweetpea": return "香豌豆"; case "convallaria": return "鈴蘭";
            case "peony": return "牡丹"; case "nymphaea": return "睡蓮"; case "morningglory": return "牽牛花";
            case "bougainvillea": return "九重葛"; case "dahlia": return "大麗花"; case "clematis": return "鐵線蓮";
            case "snowdrop": return "雪花蓮"; case "freesia": return "小蒼蘭"; case "canola": return "油菜花";
            case "iris": return "鳶尾花"; case "strelitzia": return "天堂鳥"; case "celosia": return "雞冠花";
            case "marigold": return "萬壽菊"; case "salvia": return "鼠尾草"; case "primula": return "報春花";
            case "mothorchid": return "蝴蝶蘭"; case "snapdragon": return "金魚草"; case "petunia": return "矮牽牛";
            case "forgetmenot": return "勿忘我"; case "poppy": return "罌粟"; case "bellflower": return "風鈴草";
            case "epiphyllum": return "曇花"; case "canna": return "美人蕉"; case "lisianthus": return "洋桔梗";
            case "thistle": return "薊花"; case "geranium": return "天竺葵"; case "oxalis": return "酢漿草";
            default: return "";
        }
    }

    private static String honeyColorName(String value) {
        switch (value) {
            case "white": return "白色"; case "red": return "紅色"; case "blue": return "藍色";
            case "yellow": return "黃色"; case "happy": return "特殊"; default: return "未知";
        }
    }

    static String recentTraceSummary() throws Exception {
        String trace = root("tail -n 4 " + TRACE + " 2>/dev/null || true").trim();
        if (trace.isEmpty()) return "尚無原生事件紀錄。";
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : trace.split("\\n")) {
            String[] fields = line.split("\\t", 4);
            if (fields.length < 2) continue;
            String event = fields[1];
            counts.put(event, counts.containsKey(event) ? counts.get(event) + 1 : 1);
        }
        if (counts.isEmpty()) return "尚無可辨識的原生事件。";
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (summary.length() > 0) summary.append("\n");
            summary.append(label(entry.getKey())).append("：").append(entry.getValue()).append(" 筆");
        }
        return summary.toString();
    }

    static String statusSummary() throws Exception {
        String row = root("cat " + STATUS + " 2>/dev/null || true").trim();
        return formatStatus(row);
    }

    static String formatStatus(String row) {
        if (row.isEmpty()) return "尚無本輪批次執行結果。";
        String[] fields = row.split("\\t", 6);
        if (fields.length < 6) return "原生狀態格式無法辨識。";
        String event = fields[1];
        String completed = fields[3];
        String waiting = fields[4];
        String policy = fields[5];
        if ("batch-confirmed".equals(event)) {
            return "本批已確認完成：" + completed + " 筆\n目前沒有等待中的請求；明信片：" + policy;
        }
        if ("batch-dispatched".equals(event)) {
            return "已派送請求，等待庫存確認。\n本批已確認完成：" + completed + " 筆；明信片：" + policy;
        }
        if ("batch-stopped-timeout".equals(event)) {
            return "安全停止：20 秒內未確認庫存更新。\n本批已確認完成：" + completed + " 筆；明信片：" + policy;
        }
        if ("batch-paused-limit".equals(event)) {
            return "已達本批上限並暫停。\n本批已確認完成：" + completed + " 筆；明信片：" + policy;
        }
        return "原生事件：" + event + "；本批已確認完成：" + completed + " 筆"
                + ("1".equals(waiting) ? "（等待確認）" : "");
    }

    private static String label(String event) {
        if ("dry-run-finish-due".equals(event)) return "偵測到已到期任務";
        if ("native-dispatch-one".equals(event)) return "已派送單筆收取";
        if ("one-dispatched".equals(event)) return "已派送單筆收取";
        if ("one-confirmed".equals(event)) return "已確認單筆完成";
        if ("native-dispatch-batch".equals(event)) return "已派送批次收取";
        if ("batch-confirmed".equals(event)) return "已確認批次完成";
        if ("batch-stopped".equals(event)) return "批次已安全停止";
        if ("batch-stopped-timeout".equals(event)) return "批次安全停止（未確認）";
        if ("batch-paused-limit".equals(event)) return "批次已達上限";
        if ("all-complete-no-due".equals(event)) return "全部可領取項目已完成";
        return event;
    }

    /** Only MainActivity display uses this aggregate; no control or confirmation caller. */
    static Display display() throws Exception {
        String[] s=ProbeSnapshot.checkedSections(ReadOnlyQueryPolicy.DISPLAY.read(DisplayReadCoalescer.Key.RETURN,
            ()->BoundedCommand.root(displayCommand(),6,262144)),6);
        return display(s);
    }
    static String displayCommand() {
        return ProbeSnapshot.command("cat "+CONTROL+" 2>/dev/null || printf dry-run",
            "cat "+COMPATIBILITY+" 2>/dev/null || true",
            "cat "+POSTCARD_POLICY+" 2>/dev/null || printf keep",
            "tail -n 500 "+HISTORY+" 2>/dev/null || true",
            "tail -n 24 "+TRACE+" 2>/dev/null || true",
            "cat "+STATUS+" 2>/dev/null || true");
    }
    static Display display(String[] s) {
        if(s.length!=6)throw new IllegalArgumentException("display sections");
        String[] compatibility=s[1].trim().split("\\t",4);
        return new Display(s[0].trim(),compatibility.length>=2&&"compatible".equals(compatibility[1]),
            "discard".equals(s[2].trim()),s[3].trim().isEmpty()?s[4].trim():formatDetailedEvents(s[3].trim()),formatStatus(s[5].trim()));
    }
    static final class Display {
        final String mode,history,status; final boolean compatible,discard;
        Display(String mode,boolean compatible,boolean discard,String history,String status) {
            this.mode=mode;this.compatible=compatible;this.discard=discard;this.history=history;this.status=status;
        }
    }
    private static String root(String command) throws Exception {
        ReadOnlyQueryPolicy.DISPLAY.invalidate();
        return BoundedCommand.checkedRoot(command,6,1048576);
    }
}

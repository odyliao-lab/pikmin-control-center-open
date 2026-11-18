package dev.ody.pikmincontrol;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Controls the separately installed nectar Zygisk module through its documented local files. */
final class NectarModuleGateway {
    private static final String MODULE = "/data/adb/modules/pcc_emulator_bridge_canary";
    private static final String GAME_FILES = "/data/user/0/com.nianticlabs.pikmin/files";
    // Native v151 writes its live control files in Pikmin's app-private
    // directory, not in the Magisk module directory.
    private static final String MODE = GAME_FILES + "/nectar_rpc_mode.txt";
    // service.sh mirrors this authoritative file into MODE every two seconds.
    private static final String CONTROL_MODE = MODULE + "/nectar_control_mode.txt";
    private static final String STATUS = GAME_FILES + "/nectar_status.tsv";
    private static final String MODULE_PROP = MODULE + "/module.prop";
    private static final String CLAIMS = GAME_FILES + "/nectar_claims.tsv";

    private NectarModuleGateway() {}

    /** Legacy rollback cores cannot interpret a pending nectar receipt. Never remove it. */
    static boolean rollbackReceiptAllows(String wire) {
        if(wire==null||wire.length()>1024||!wire.endsWith("\n")||wire.indexOf('\0')>=0)return false;
        String body=wire.substring(0,wire.length()-1);String[] f=body.split("\t",-1);
        if(f.length!=10||!f[0].equals("v1")||!f[1].equals("success"))return false;
        for(int n:new int[]{2,3,4,9})if(!f[n].matches("[a-f0-9]{64}"))return false;
        for(int n=5;n<=8;n++)try {
            long value=Long.parseLong(f[n]);
            if(value<=0||!Long.toString(value).equals(f[n])||(n==7&&value>Integer.MAX_VALUE))return false;
        }catch(NumberFormatException invalid){return false;}
        return rollbackSha(body.substring(0,body.lastIndexOf('\t'))).equals(f[9]);
    }
    private static String rollbackSha(String value) {
        try {
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return out.toString();
        }catch(java.security.NoSuchAlgorithmException unavailable){throw new IllegalStateException(unavailable);}
    }
    private static String rollbackDirectory(String directory) {
        if(directory==null||!directory.matches("/[A-Za-z0-9_./-]+")||directory.contains("/../")||directory.endsWith("/.."))throw new IllegalArgumentException("Private receipt directory required");
        return directory;
    }
    private static String rollbackIdleGuard() {
        // pidof exit 1 is explicit absence; missing tool/read failure is not absence.
        return "pcc_nr_rc=0; pcc_nr_pid=$(pidof com.nianticlabs.pikmin 2>/dev/null) || pcc_nr_rc=$?; "
            +"test \"$pcc_nr_rc\" = 1 && test -z \"$pcc_nr_pid\" || exit 1; ";
    }
    private static String rollbackDirectoryGuard(String directory) {
        return rollbackIdleGuard()+"test -d '"+directory+"' || exit 1; test ! -L '"+directory+"' || exit 1; "
            +"test -r '"+directory+"' && test -x '"+directory+"' || exit 1; ";
    }
    private static String rollbackFileGuard(String directory) {
        String p=directory+"/nectar_intent.tsv";
        return "test ! -L '"+p+"' || exit 1; test -f '"+p+"' || exit 1; test -r '"+p+"' || exit 1; "
            +"test \"$(stat -c %h '"+p+"')\" = 1 || exit 1; "
            +"test \"$(stat -c %u '"+p+"')\" = \"$(stat -c %u '"+directory+"')\" || exit 1; "
            +"pcc_nr_size=$(stat -c %s '"+p+"') || exit 1; test \"$pcc_nr_size\" -gt 0 && test \"$pcc_nr_size\" -le 1024 || exit 1; ";
    }
    private static String rollbackAbsentGuard(String directory) {
        String p=directory+"/nectar_intent.tsv";
        return "pcc_nr_entries=$(ls -a1 '"+directory+"') || exit 1; pcc_nr_match=0; "
            +"printf '%s\\n' \"$pcc_nr_entries\" | grep -Fx 'nectar_intent.tsv' >/dev/null || pcc_nr_match=$?; "
            +"test \"$pcc_nr_match\" = 1 || exit 1; test ! -e '"+p+"' || exit 1; test ! -L '"+p+"' || exit 1; ";
    }
    static String rollbackReadCommand(String directory) {
        directory=rollbackDirectory(directory);String p=directory+"/nectar_intent.tsv";
        return rollbackDirectoryGuard(directory)+"pcc_nr_dir=$(stat -c '%d:%i:%u' '"+directory+"') || exit 1; "
            +"if test ! -e '"+p+"'; then "+rollbackAbsentGuard(directory)+"printf 'ABSENT\\t%s\\n' \"$pcc_nr_dir\"; else "
            +rollbackFileGuard(directory)+"pcc_nr_file=$(stat -c '%d:%i:%s' '"+p+"') || exit 1; "
            +"printf 'PRESENT\\t%s\\t%s\\n' \"$pcc_nr_dir\" \"$pcc_nr_file\"; cat '"+p+"' || exit 1; "
            +rollbackFileGuard(directory)+"test \"$(stat -c '%d:%i:%s' '"+p+"')\" = \"$pcc_nr_file\" || exit 1; fi; "
            +"test ! -L '"+directory+"' || exit 1; test \"$(stat -c '%d:%i:%u' '"+directory+"')\" = \"$pcc_nr_dir\" || exit 1; "+rollbackIdleGuard();
    }
    static String rollbackProofFromRead(String output,String directory)throws java.io.IOException {
        directory=rollbackDirectory(directory);int end=output==null?-1:output.indexOf('\n');
        if(end<0)throw new java.io.IOException("精華收取紀錄讀取不完整，拒絕回退");
        String[] h=output.substring(0,end).split("\t",-1);String wire=output.substring(end+1),p=directory+"/nectar_intent.tsv";
        if(h.length<2||!h[1].matches("[0-9]+:[0-9]+:[0-9]+"))throw new java.io.IOException("精華收取紀錄身份不明，拒絕回退");
        String guard=rollbackDirectoryGuard(directory)+"test \"$(stat -c '%d:%i:%u' '"+directory+"')\" = '"+h[1]+"' || exit 1; ";
        if(h.length==2&&h[0].equals("ABSENT")&&wire.isEmpty())
            return guard+rollbackAbsentGuard(directory)+rollbackIdleGuard();
        if(h.length!=3||!h[0].equals("PRESENT")||!h[2].matches("[0-9]+:[0-9]+:[1-9][0-9]{0,3}")||!rollbackReceiptAllows(wire))
            throw new java.io.IOException("精華收取仍待確認或紀錄無法核對；舊核心無法保留防重送保護，拒絕回退");
        String[] identity=h[2].split(":",-1);
        if(Integer.parseInt(identity[2])!=wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)throw new java.io.IOException("精華收取紀錄長度不同，拒絕回退");
        return guard+rollbackFileGuard(directory)+"test \"$(stat -c '%d:%i:%s' '"+p+"')\" = '"+h[2]+"' || exit 1; "
            +"test \"$(sha256sum '"+p+"' | cut -d ' ' -f1)\" = '"+rollbackSha(wire)+"' || exit 1; "+rollbackIdleGuard();
    }
    static String rollbackProof()throws Exception {
        String observed=BoundedCommand.checkedRoot(rollbackReadCommand(GAME_FILES),6,4096);
        return rollbackProofFromRead(observed,GAME_FILES);
    }
    static void requireRollbackSafe()throws Exception {
        BoundedCommand.checkedRoot(rollbackProof(),6,4096);
    }

    static Snapshot read() throws Exception {
        String output = root("printf '%s\\n' '---MODULE---'; test -f " + MODULE_PROP
                + " && echo present; printf '%s\\n' '---MODE---'; cat " + MODE
                + " 2>/dev/null; printf '%s\\n' '---STATUS---'; cat " + STATUS + " 2>/dev/null; true");
        return parseSnapshot(output);
    }
    static Snapshot parseSnapshot(String output) {
        String mode = "";
        String[] status = new String[0];
        boolean modulePresent = false;
        boolean statusSection = false;
        for (String line : output.split("\\r?\\n")) {
            if ("---MODULE---".equals(line)) { statusSection = false; continue; }
            if ("---MODE---".equals(line)) { statusSection = false; continue; }
            if ("---STATUS---".equals(line)) { statusSection = true; continue; }
            if (!statusSection && "present".equals(line.trim())) { modulePresent = true; continue; }
            if (statusSection && !line.trim().isEmpty()) status = line.split("\\t", -1);
            else if (!statusSection && !line.trim().isEmpty()) mode = line.trim();
        }
        return new Snapshot(mode, status, modulePresent);
    }

    static void setAutomatic(boolean enabled) throws Exception {
        root(controlCommand(enabled));
    }

    static String controlCommand(boolean enabled) {
        String mode = enabled ? "auto" : "diag";
        return "test -f " + MODULE_PROP + " -a -f " + MODE
                + " || { echo 'Pikmin getNectar Magisk 模組尚未就緒'; exit 1; }; "
                + "printf '" + mode + "\\n' > " + CONTROL_MODE + ".tmp && chmod 0644 " + CONTROL_MODE
                + ".tmp && mv " + CONTROL_MODE + ".tmp " + CONTROL_MODE
                + " && printf '" + mode + "\\n' > " + MODE + " && chmod 0644 " + MODE;
    }

    static String recentClaims() throws Exception {
        return root("tail -n 100 " + CLAIMS + " 2>/dev/null || true").trim();
    }

    static String recentClaimsFormatted() throws Exception {
        String raw = recentClaims();
        return formatClaims(raw);
    }
    static String formatClaims(String raw) {
        if (raw.isEmpty()) return "";
        StringBuilder shown = new StringBuilder();
        SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        String[] lines = raw.split("\\n");
        for (int index = lines.length - 1; index >= 0; --index) {
            String line = lines[index];
            String[] f = line.split("\\t", -1);
            if (f.length < 12) continue;
            if (shown.length() > 0) shown.append("\n");
            String when;
            try { when = time.format(new Date(Long.parseLong(f[0]))); }
            catch (NumberFormatException ignored) { when = f[0]; }
            if ("3".equals(f[8])) {
                shown.append(when).append(" ").append(flowerName(f[11])).append("-").append(nectarName(f[10])).append("精華 +").append(f[9]);
            } else shown.append(when).append(" 收取完成 +").append(f[9]);
        }
        return shown.toString();
    }

    /**
     * Rolls the raw claim log up into totals. Column order comes from
     * append_claim_log() in nectar_rpc.cpp:
     *   0 when · 1 result · 2 id · 3 lat · 4 lng · 5 distance ·
     *   6 failedReason · 7 failedReasonName · 8 rewardCase · 9 amount ·
     *   10 honeyType · 11 flowerKind
     * Reward case 3 marks a nectar reward, and only those carry a meaningful
     * amount/colour. REJECTED rows carry a readable reason in column 7 (not
     * 6, which is the numeric code) worth surfacing rather than counting as
     * a silent zero.
     */
    static String claimStats() throws Exception {
        return formatStats(root(statsCommand()).trim(),System.currentTimeMillis());
    }
    static String statsCommand() { return "tail -n 2000 " + CLAIMS + " 2>/dev/null || true"; }
    static String formatStats(String raw,long now) {
        if (raw.isEmpty()) return "尚無精華收取紀錄。";
        long dayCutoff = now - 86_400_000L;
        long weekCutoff = now - 7L * 86_400_000L;
        int[] dayByColour = new int[6], weekByColour = new int[6];
        int dayClaims = 0, weekClaims = 0, totalClaims = 0, rejected = 0;
        java.util.TreeMap<String, Integer> rejectReasons = new java.util.TreeMap<>();
        for (String line : raw.split("\\n")) {
            String[] f = line.split("\\t", -1);
            if (f.length < 12) continue;
            long when; try { when = Long.parseLong(f[0]); } catch (NumberFormatException ignored) { continue; }
            if (!"SUCCESS".equals(f[1])) {
                ++rejected;
                String reason = f[7].isEmpty() ? f[1] : f[7];
                rejectReasons.merge(reason, 1, Integer::sum);
                continue;
            }
            // Only reward case 3 is a nectar payout; other successes (e.g. an
            // empty claim) have no colour and would otherwise inflate "其他".
            boolean nectarReward = "3".equals(f[8]);
            int amount = 0, colour = 0;
            if (nectarReward) {
                try { amount = Integer.parseInt(f[9]); } catch (NumberFormatException ignored) { amount = 0; }
                try { colour = Integer.parseInt(f[10]); } catch (NumberFormatException ignored) { colour = 0; }
                if (colour < 0 || colour > 5) colour = 0;
            }
            ++totalClaims;
            if (when >= weekCutoff) { ++weekClaims; weekByColour[colour] += amount; }
            if (when >= dayCutoff) { ++dayClaims; dayByColour[colour] += amount; }
        }
        StringBuilder out = new StringBuilder();
        out.append("最近2000筆範圍內 · 24小時：成功 ").append(dayClaims).append(" 次").append(colourBreakdown(dayByColour));
        out.append("\n範圍內 · 7天：成功 ").append(weekClaims).append(" 次").append(colourBreakdown(weekByColour));
        out.append("\n範圍內紀錄：成功 ").append(totalClaims).append(" 次");
        if (rejected > 0) {
            out.append("、遭拒 ").append(rejected).append(" 次");
            for (java.util.Map.Entry<String, Integer> e : rejectReasons.entrySet())
                out.append("\n  · ").append(e.getKey()).append(" ×").append(e.getValue());
        }
        return out.toString();
    }

    private static String colourBreakdown(int[] byColour) {
        StringBuilder out = new StringBuilder(); int total = 0;
        for (int amount : byColour) total += amount;
        if (total == 0) return "，未取得精華";
        out.append("，精華 +").append(total).append("（");
        boolean first = true;
        for (int colour = 1; colour < byColour.length; ++colour) {
            if (byColour[colour] == 0) continue;
            if (!first) out.append(' ');
            out.append(nectarName(String.valueOf(colour))).append(byColour[colour]);
            first = false;
        }
        if (byColour[0] > 0) { if (!first) out.append(' '); out.append("其他").append(byColour[0]); }
        return out.append("）").toString();
    }

    private static String nectarName(String value) {
        switch (value) { case "1": return "白色"; case "2": return "紅色"; case "3": return "藍色"; case "4": return "黃色"; case "5": return "特殊"; default: return "未知"; }
    }
    private static String flowerName(String value) {
        switch (value) {
            case "1": return "向日葵"; case "2": return "鬱金香"; case "3": return "三色堇"; case "4": return "玫瑰"; case "5": return "普通花";
            case "6": return "聖誕紅"; case "7": return "山茶花"; case "8": return "梅花"; case "9": return "水仙"; case "10": return "櫻花";
            case "11": return "粉蝶花"; case "12": return "康乃馨"; case "13": return "海芋"; case "14": return "繡球花"; case "15": return "百合";
            case "16": return "扶桑花"; case "17": return "雞蛋花"; case "18": return "彼岸花"; case "19": return "大波斯菊"; case "20": return "仙客來";
            case "21": return "銀蓮花"; case "22": return "石竹"; case "23": return "龍膽"; case "24": return "菊花"; case "25": return "聖誕玫瑰";
            case "26": return "嘉德麗雅蘭"; case "27": return "風信子"; case "28": return "香豌豆"; case "29": return "鈴蘭"; case "30": return "牡丹";
            case "31": return "睡蓮"; case "32": return "牽牛花"; case "33": return "九重葛"; case "34": return "大麗花"; case "35": return "鐵線蓮";
            case "36": return "雪花蓮"; case "37": return "小蒼蘭"; case "38": return "油菜花"; case "39": return "玫瑰"; case "40": return "鳶尾花";
            case "41": return "天堂鳥"; case "42": return "雞冠花"; case "43": return "萬壽菊"; case "44": return "鼠尾草"; case "45": return "報春花";
            case "46": return "蝴蝶蘭"; case "47": return "金魚草"; case "48": return "矮牽牛"; case "49": return "鬱金香"; case "50": return "勿忘我";
            case "51": return "罌粟"; case "52": return "風鈴草"; case "53": return "曇花"; case "54": return "美人蕉"; case "55": return "洋桔梗";
            case "56": return "薊花"; case "57": return "天竺葵"; case "58": return "酢漿草"; default: return "未知花種";
        }
    }

    static Display display() throws Exception {
        String[] s=ProbeSnapshot.checkedSections(ReadOnlyQueryPolicy.DISPLAY.read(DisplayReadCoalescer.Key.NECTAR,
            ()->BoundedCommand.root(displayCommand(),6,65536)),2);
        return display(s);
    }
    static String displayCommand() {
        return ProbeSnapshot.command("printf '%s\\n' '---MODULE---'; test -f "+MODULE_PROP
            +" && echo present; printf '%s\\n' '---MODE---'; cat "+MODE
            +" 2>/dev/null; printf '%s\\n' '---STATUS---'; cat "+STATUS+" 2>/dev/null; true",
            "tail -n 100 "+CLAIMS+" 2>/dev/null || true");
    }
    static Display display(String[] s) {
        if(s.length!=2)throw new IllegalArgumentException("display sections");
        return new Display(parseSnapshot(s[0]),formatClaims(s[1].trim()));
    }
    static final class Display {
        final Snapshot snapshot; final String history;
        Display(Snapshot snapshot,String history) { this.snapshot=snapshot;this.history=history; }
    }
    private static String root(String command) throws Exception {
        ReadOnlyQueryPolicy.DISPLAY.invalidate();
        return BoundedCommand.checkedRoot(command,6,1048576);
    }

    static final class Snapshot {
        final String mode;
        final String[] status;
        final boolean modulePresent;

        Snapshot(String mode, String[] status, boolean modulePresent) {
            this.mode = mode;
            this.status = status;
            this.modulePresent = modulePresent;
        }
        boolean automatic() { return "auto".equals(mode); }
        private static String pendingDescription(String result) {
            String[] fields = result.split(":", -1);
            boolean unconfirmed = fields[0].equals("PENDING_UNCONFIRMED");
            String text = unconfirmed
                    ? "收取結果待核對，已暫停自動收取；不自動重送"
                    : "等待原請求確認，不重送";
            if (fields.length > 1 && fields[1].matches("[0-9]{1,19}")) {
                try { text += "（" + (Long.parseLong(fields[1]) / 1000L) + " 秒）"; }
                catch (NumberFormatException ignored) { }
            }
            if (fields.length == 5) {
                if ("restart".equals(fields[4])) text += "\n前次程序留下待確認請求";
                if ("storage".equals(fields[4])) text += "\n收取紀錄無法確認";
                if (fields[3].matches("[a-f0-9]{12}")) text += "\n請求 " + fields[3];
                if (fields[2].matches("[0-9]{1,19}")) {
                    try {
                        long intentAt = Long.parseLong(fields[2]);
                        if (intentAt > 0) text += " · " + new java.text.SimpleDateFormat(
                                "MM/dd HH:mm:ss", java.util.Locale.TAIWAN).format(new java.util.Date(intentAt));
                    } catch (NumberFormatException ignored) { }
                }
            }
            return text;
        }

        String summary() {
            if (status.length < 10) {
                return modulePresent
                        ? "等待 Pikmin 開啟地圖並回報狀態。"
                        : "⚠ 尚未偵測到精華模組，請確認安裝後已重新開機。";
            }
            String gps = "1".equals(status[4]) ? "位置已就緒" : "等待位置資訊";
            String planting = "1".equals(status[2]) ? "種花中" : "未種花";
            String connection = "1".equals(status[8]) ? "遊戲連線正常" : "等待遊戲連線";
            String result = status[9];
            if ("SUCCESS:NONE".equals(result)) result = "上次檢查正常，目前沒有新的精華";
            else if (result.startsWith("SUCCESS")) result = "上次收取成功";
            else if ("PENDING".equals(result)) result = "等待原請求確認，不重送";
            else if (result.startsWith("PENDING:") || result.startsWith("PENDING_UNCONFIRMED:")) result = pendingDescription(result);
            else if (result.isEmpty()) result = "尚無執行結果";
            else result = "上次結果可在進階紀錄查看";
            return gps + " · " + planting + "\n" + connection + "\n" + result;
        }
    }
}

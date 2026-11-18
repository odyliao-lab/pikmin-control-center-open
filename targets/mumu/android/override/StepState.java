package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;

final class StepLogEntry {
    final long atMs;
    final long count;
    final String source;
    final String result;

    StepLogEntry(long atMs, long count, String source, String result) {
        this.atMs = atMs;
        this.count = count;
        this.source = source == null ? "" : source;
        this.result = result == null ? "" : result;
    }

    String encode() { return atMs + "\t" + count + "\t" + source + "\t" + result; }

    static StepLogEntry decode(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 4) throw new IllegalArgumentException("log");
        return new StepLogEntry(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3]);
    }
}

final class StepState {
    static final int MAX_LOGS = 40;
    boolean disclosed;
    boolean desiredRunning;
    StepSettings settings;
    StepLedger ledger;
    final List<StepLogEntry> logs;
    String writerOwner;
    String lockId;
    String lockRole;
    long lockGeneration;
    long cancelNonce;
    long runStartWall;
    long runStartElapsed;
    long nextDueElapsed;
    long elapsedBasis;
    long lastConfirmedEndMs;
    long runCursorMs;
    long lastSuccessElapsed;
    int restStreak;
    int backoffMs;
    long waitUntilElapsed;
    String dayKey;
    String zoneId;
    long sessionConfirmed;
    String sessionId;
    Long lastFreshAllSources;
    long lastFreshAllSourcesAt;
    Long lastFreshThisApp;
    long lastFreshThisAppAt;
    String lastOpPhase;
    String lastOpKind;
    String lastOpDetail;
    int clockEpoch;
    long commandEpoch;
    long settingsRevision;
    String processId;
    String bootId;
    String savedOwnerRun;
    String savedStartReceipt;

    StepState() {
        disclosed = false;
        desiredRunning = false;
        settings = StepSettings.defaults();
        ledger = StepLedger.empty();
        logs = new ArrayList<StepLogEntry>();
        writerOwner = "";
        lockId = "";
        lockRole = "";
        lockGeneration = 0L;
        cancelNonce = 0L;
        runStartWall = 0L;
        runStartElapsed = 0L;
        nextDueElapsed = 0L;
        elapsedBasis = 0L;
        lastConfirmedEndMs = 0L;
        runCursorMs = 0L;
        lastSuccessElapsed = 0L;
        restStreak = 0;
        backoffMs = 0;
        waitUntilElapsed = 0L;
        dayKey = "";
        zoneId = "";
        sessionConfirmed = 0L;
        sessionId = "";
        lastFreshAllSources = null;
        lastFreshAllSourcesAt = 0L;
        lastFreshThisApp = null;
        lastFreshThisAppAt = 0L;
        lastOpPhase = "";
        lastOpKind = "";
        lastOpDetail = "";
        clockEpoch = 0;
        commandEpoch = 0L;
        settingsRevision = 0L;
        processId = "";
        bootId = "";
        savedOwnerRun = "";
        savedStartReceipt = "";
    }

    void addLog(StepLogEntry entry) {
        logs.add(0, entry);
        while (logs.size() > MAX_LOGS) logs.remove(logs.size() - 1);
    }

    String encode() {
        StringBuilder out = new StringBuilder();
        out.append("PCCSTEP/3\n");
        field(out, "disclosed", disclosed ? "1" : "0");
        field(out, "desiredRunning", desiredRunning ? "1" : "0");
        field(out, "target", Integer.toString(settings.target));
        field(out, "windowStart", Integer.toString(settings.windowStartMin));
        field(out, "windowEnd", Integer.toString(settings.windowEndMin));
        field(out, "rate", Integer.toString(settings.ratePerMinute));
        field(out, "preset", settings.ratePreset);
        field(out, "intervalMs", Long.toString(settings.intervalMs));
        field(out, "writerOwner", lockRole.length() > 0 ? lockRole : writerOwner);
        field(out, "lockId", lockId);
        field(out, "lockRole", lockRole);
        field(out, "lockGeneration", Long.toString(lockGeneration));
        field(out, "cancelNonce", Long.toString(cancelNonce));
        field(out, "runStartWall", Long.toString(runStartWall));
        field(out, "runStartElapsed", Long.toString(runStartElapsed));
        field(out, "nextDueElapsed", Long.toString(nextDueElapsed));
        field(out, "elapsedBasis", Long.toString(elapsedBasis));
        field(out, "lastConfirmedEndMs", Long.toString(lastConfirmedEndMs));
        field(out, "runCursorMs", Long.toString(runCursorMs));
        field(out, "lastSuccessElapsed", Long.toString(lastSuccessElapsed));
        field(out, "restStreak", Integer.toString(restStreak));
        field(out, "backoffMs", Integer.toString(backoffMs));
        field(out, "waitUntilElapsed", Long.toString(waitUntilElapsed));
        field(out, "dayKey", dayKey);
        field(out, "zoneId", zoneId);
        field(out, "sessionConfirmed", Long.toString(sessionConfirmed));
        field(out, "sessionId", sessionId);
        field(out, "clockEpoch", Integer.toString(clockEpoch));
        field(out, "commandEpoch", Long.toString(commandEpoch));
        field(out, "settingsRevision", Long.toString(settingsRevision));
        field(out, "processId", processId);
        field(out, "bootId", bootId);
        field(out, "savedOwnerRun", savedOwnerRun);
        field(out, "savedStartReceipt", savedStartReceipt);
        field(out, "lastFreshAllSources", lastFreshAllSources == null ? "" : Long.toString(lastFreshAllSources.longValue()));
        field(out, "lastFreshAllSourcesAt", Long.toString(lastFreshAllSourcesAt));
        field(out, "lastFreshThisApp", lastFreshThisApp == null ? "" : Long.toString(lastFreshThisApp.longValue()));
        field(out, "lastFreshThisAppAt", Long.toString(lastFreshThisAppAt));
        field(out, "lastOpPhase", lastOpPhase);
        field(out, "lastOpKind", lastOpKind);
        field(out, "lastOpDetail", lastOpDetail);
        List<StepPlan> plans = ledger.all();
        for (int i = 0; i < plans.size(); i++) out.append("PLAN\t").append(plans.get(i).encode()).append('\n');
        List<long[]> spans = ledger.occupiedSpans();
        for (int i = 0; i < spans.size(); i++)
            out.append("SPAN\t").append(spans.get(i)[0]).append('\t').append(spans.get(i)[1]).append('\n');
        for (int i = 0; i < logs.size(); i++) out.append("LOG\t").append(logs.get(i).encode()).append('\n');
        out.append("FOOTER\tplans=").append(plans.size()).append("\tspans=").append(spans.size())
                .append("\tlogs=").append(logs.size()).append('\n');
        String body = out.toString();
        out.append("CHECKSUM\t").append(Long.toHexString(checksumOf(body))).append('\n');
        return out.toString();
    }

    static long checksumOf(String body) {
        CRC32 crc = new CRC32();
        crc.update((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        return crc.getValue();
    }

    static StepState decode(String raw) {
        if (raw == null || raw.isEmpty()) throw new StepStoreException("corrupt:empty");
        String[] lines = raw.split("\n", -1);
        if (lines.length == 0 || !lines[0].startsWith("PCCSTEP/")) throw new StepStoreException("corrupt:header");
        String version = lines[0].trim();
        boolean v3 = "PCCSTEP/3".equals(version);
        if (!"PCCSTEP/1".equals(version) && !"PCCSTEP/2".equals(version) && !v3)
            throw new StepStoreException("corrupt:schema");
        if (!v3) throw new StepStoreException("corrupt:legacy-incomplete");
        verifyV3Integrity(raw, lines);
        StepState state = new StepState();
        boolean sawDisclosed = false, sawDesired = false, sawTarget = false, sawWindowStart = false,
                sawWindowEnd = false, sawRate = false, sawInterval = false, sawLockGen = false,
                sawCancel = false, sawCommandEpoch = false;
        Set<String> seen = new HashSet<String>();
        List<StepPlan> plans = new ArrayList<StepPlan>();
        List<long[]> spans = new ArrayList<long[]>();
        int footerPlans = -1, footerSpans = -1, footerLogs = -1;
        try {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.isEmpty()) continue;
                if (line.startsWith("CHECKSUM\t")) continue;
                if (line.startsWith("FOOTER\t")) {
                    String[] p = line.substring(7).split("\t", -1);
                    if (p.length < 3) throw new StepStoreException("corrupt:footer");
                    footerPlans = intSuffix(p[0], "plans=");
                    footerSpans = intSuffix(p[1], "spans=");
                    footerLogs = intSuffix(p[2], "logs=");
                    continue;
                }
                if (line.startsWith("PLAN\t")) {
                    if (line.split("\t", -1).length < 13) throw new StepStoreException("corrupt:plan");
                    StepPlan plan = StepPlan.decode(line.substring(5));
                    validatePlan(plan);
                    plans.add(plan);
                } else if (line.startsWith("SPAN\t")) {
                    String[] p = line.substring(5).split("\t", -1);
                    if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) throw new StepStoreException("corrupt:span");
                    long start = Long.parseLong(p[0]);
                    long end = Long.parseLong(p[1]);
                    if (start < 0L || end <= start) throw new StepStoreException("corrupt:interval");
                    spans.add(new long[] { start, end });
                } else if (line.startsWith("LOG\t")) {
                    state.logs.add(StepLogEntry.decode(line.substring(4)));
                } else {
                    int eq = line.indexOf('=');
                    if (eq <= 0) throw new StepStoreException("corrupt:field");
                    String key = line.substring(0, eq);
                    String value = line.substring(eq + 1);
                    if (!seen.add(key)) throw new StepStoreException("corrupt:duplicate");
                    if ("disclosed".equals(key)) { state.disclosed = "1".equals(value); sawDisclosed = true; }
                    else if ("desiredRunning".equals(key)) { state.desiredRunning = "1".equals(value); sawDesired = true; }
                    else if ("target".equals(key)) { state.settings = withTarget(state.settings, Integer.parseInt(value)); sawTarget = true; }
                    else if ("windowStart".equals(key)) { state.settings = withWindow(state.settings, Integer.parseInt(value), state.settings.windowEndMin); sawWindowStart = true; }
                    else if ("windowEnd".equals(key)) { state.settings = withWindow(state.settings, state.settings.windowStartMin, Integer.parseInt(value)); sawWindowEnd = true; }
                    else if ("rate".equals(key)) { state.settings = withRate(state.settings, Integer.parseInt(value), state.settings.ratePreset); sawRate = true; }
                    else if ("preset".equals(key)) state.settings = withRate(state.settings, state.settings.ratePerMinute, value);
                    else if ("intervalMs".equals(key)) { state.settings = withInterval(state.settings, Long.parseLong(value)); sawInterval = true; }
                    else if ("writerOwner".equals(key)) state.writerOwner = value;
                    else if ("lockId".equals(key)) state.lockId = value;
                    else if ("lockRole".equals(key)) state.lockRole = value;
                    else if ("lockGeneration".equals(key)) {
                        state.lockGeneration = Long.parseLong(value);
                        if (state.lockGeneration < 0L) throw new StepStoreException("corrupt:generation");
                        sawLockGen = true;
                    }
                    else if ("cancelNonce".equals(key)) {
                        state.cancelNonce = Long.parseLong(value);
                        if (state.cancelNonce < 0L) throw new StepStoreException("corrupt:generation");
                        sawCancel = true;
                    }
                    else if ("runStartWall".equals(key)) state.runStartWall = Long.parseLong(value);
                    else if ("runStartElapsed".equals(key)) state.runStartElapsed = Long.parseLong(value);
                    else if ("nextDueElapsed".equals(key)) state.nextDueElapsed = Long.parseLong(value);
                    else if ("elapsedBasis".equals(key)) state.elapsedBasis = Long.parseLong(value);
                    else if ("lastConfirmedEndMs".equals(key)) state.lastConfirmedEndMs = Long.parseLong(value);
                    else if ("runCursorMs".equals(key)) state.runCursorMs = Long.parseLong(value);
                    else if ("lastSuccessElapsed".equals(key)) state.lastSuccessElapsed = Long.parseLong(value);
                    else if ("restStreak".equals(key)) state.restStreak = Integer.parseInt(value);
                    else if ("backoffMs".equals(key)) state.backoffMs = Integer.parseInt(value);
                    else if ("waitUntilElapsed".equals(key)) state.waitUntilElapsed = Long.parseLong(value);
                    else if ("dayKey".equals(key)) state.dayKey = value;
                    else if ("zoneId".equals(key)) state.zoneId = value;
                    else if ("sessionConfirmed".equals(key)) state.sessionConfirmed = Long.parseLong(value);
                    else if ("sessionId".equals(key)) state.sessionId = value;
                    else if ("clockEpoch".equals(key)) state.clockEpoch = Integer.parseInt(value);
                    else if ("commandEpoch".equals(key)) {
                        state.commandEpoch = Long.parseLong(value);
                        if (state.commandEpoch < 0L) throw new StepStoreException("corrupt:generation");
                        sawCommandEpoch = true;
                    }
                    else if ("settingsRevision".equals(key)) {
                        state.settingsRevision = Long.parseLong(value);
                        if (state.settingsRevision < 0L) throw new StepStoreException("corrupt:generation");
                    }
                    else if ("processId".equals(key)) state.processId = value;
                    else if ("bootId".equals(key)) state.bootId = value;
                    else if ("savedOwnerRun".equals(key)) {
                        if (!value.isEmpty() && !java.util.UUID.fromString(value).toString().equals(value))
                            throw new StepStoreException("corrupt:saved-owner");
                        state.savedOwnerRun = value;
                    }
                    else if ("savedStartReceipt".equals(key)) {
                        if (!value.isEmpty()) {
                            String[] receipt=value.split("\\|",-1);
                            if (receipt.length!=5 || !receipt[0].equals("v1") ||
                                    !java.util.UUID.fromString(receipt[1]).toString().equals(receipt[1]) ||
                                    !java.util.UUID.fromString(receipt[2]).toString().equals(receipt[2]) ||
                                    !(receipt[3].equals("ACCEPTED") && receipt[4].matches("[0-9]+") ||
                                      receipt[3].equals("REJECTED") && receipt[4].equals("-1")))
                                throw new StepStoreException("corrupt:saved-receipt");
                            Long.parseLong(receipt[4]);
                        }
                        state.savedStartReceipt=value;
                    }
                    else if ("lastFreshAllSources".equals(key))
                        state.lastFreshAllSources = value.isEmpty() ? null : Long.valueOf(Long.parseLong(value));
                    else if ("lastFreshAllSourcesAt".equals(key)) state.lastFreshAllSourcesAt = Long.parseLong(value);
                    else if ("lastFreshThisApp".equals(key))
                        state.lastFreshThisApp = value.isEmpty() ? null : Long.valueOf(Long.parseLong(value));
                    else if ("lastFreshThisAppAt".equals(key)) state.lastFreshThisAppAt = Long.parseLong(value);
                    else if ("lastOpPhase".equals(key)) state.lastOpPhase = value;
                    else if ("lastOpKind".equals(key)) state.lastOpKind = value;
                    else if ("lastOpDetail".equals(key)) state.lastOpDetail = value;
                }
            }
        } catch (StepStoreException direct) {
            throw direct;
        } catch (RuntimeException invalid) {
            throw new StepStoreException("corrupt:parse", invalid);
        }
        if (!sawDisclosed || !sawDesired || !sawTarget || !sawWindowStart || !sawWindowEnd || !sawRate || !sawInterval
                || !sawLockGen || !sawCancel)
            throw new StepStoreException("corrupt:required");
        if (v3 && !sawCommandEpoch) throw new StepStoreException("corrupt:required");
        if (footerPlans != plans.size() || footerSpans != spans.size() || footerLogs != state.logs.size())
            throw new StepStoreException("corrupt:footer");
        boolean locked = state.lockId.length() > 0;
        boolean role = state.lockRole.length() > 0 || state.writerOwner.length() > 0;
        if (locked != role) throw new StepStoreException("corrupt:lock");
        if (state.lockRole.length() == 0) state.lockRole = state.writerOwner;
        state.writerOwner = state.lockRole;
        state.ledger = new StepLedger(plans, spans);
        return state;
    }

    private static void verifyV3Integrity(String raw, String[] lines) {
        int checksumAt = -1;
        int footerAt = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].startsWith("FOOTER\t")) footerAt = i;
            if (lines[i].startsWith("CHECKSUM\t")) checksumAt = i;
        }
        if (footerAt < 0 || checksumAt < 0 || checksumAt < footerAt) throw new StepStoreException("corrupt:footer");
        String checksumLine = lines[checksumAt];
        int tab = checksumLine.indexOf('\t');
        if (tab <= 0) throw new StepStoreException("corrupt:checksum");
        String expected = checksumLine.substring(tab + 1).trim();
        int bodyEnd = raw.indexOf(checksumLine);
        if (bodyEnd < 0) throw new StepStoreException("corrupt:checksum");
        String body = raw.substring(0, bodyEnd);
        if (!expected.equals(Long.toHexString(checksumOf(body)))) throw new StepStoreException("corrupt:checksum");
    }

    private static int intSuffix(String raw, String prefix) {
        if (raw == null || !raw.startsWith(prefix)) throw new StepStoreException("corrupt:footer");
        return Integer.parseInt(raw.substring(prefix.length()));
    }

    private static void validatePlan(StepPlan plan) {
        if (!StepPlan.PREPARED.equals(plan.status) && !StepPlan.CONFIRMED.equals(plan.status)
                && !StepPlan.UNCERTAIN.equals(plan.status) && !StepPlan.FAILED.equals(plan.status)
                && !StepPlan.REJECTED.equals(plan.status))
            throw new StepStoreException("corrupt:status");
        if (plan.count < 0L || plan.startMs < 0L || plan.endMs <= plan.startMs)
            throw new StepStoreException("corrupt:interval");
    }

    static StepState load(StepStore store) {
        StepLoadResult loaded = store.load();
        if (loaded.isUnreadable()) throw new StepStoreException("unreadable:" + loaded.error);
        if (loaded.isMissing()) return new StepState();
        return decode(loaded.raw);
    }

    static void save(StepStore store, StepState state) { store.save(state.encode()); }

    private static void field(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(value == null ? "" : value).append('\n');
    }

    private static StepSettings withTarget(StepSettings s, int target) {
        return new StepSettings(target, s.windowStartMin, s.windowEndMin, s.ratePerMinute, s.ratePreset, s.intervalMs);
    }
    private static StepSettings withWindow(StepSettings s, int start, int end) {
        return new StepSettings(s.target, start, end, s.ratePerMinute, s.ratePreset, s.intervalMs);
    }
    private static StepSettings withRate(StepSettings s, int rate, String preset) {
        return new StepSettings(s.target, s.windowStartMin, s.windowEndMin, rate, preset, s.intervalMs);
    }
    private static StepSettings withInterval(StepSettings s, long interval) {
        return new StepSettings(s.target, s.windowStartMin, s.windowEndMin, s.ratePerMinute, s.ratePreset, interval);
    }
}

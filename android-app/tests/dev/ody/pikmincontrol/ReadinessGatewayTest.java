package dev.ody.pikmincontrol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adapter contract: the shell is read-only and bounded, framing survives
 * missing/denied/truncated sources, the round deadline is a real budget
 * (facts + shell + parse), unreadable app facts never look like "no pending",
 * and the coordinator is single-flight, coalescing, cancellable and follows
 * up a page that re-entered mid-round. Includes a fixture executed by a real
 * POSIX shell against a temp directory when one is available (T08, T12, R4/R5, S2).
 */
public final class ReadinessGatewayTest {
    static final long NOW = 1_800_000_000_000L;
    static final ReadinessSample.AppFacts APP = ReadinessModelTest.APP;
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }

    public static void main(String[] args) throws Exception {
        commandIsReadOnlyAndBounded();
        framingAndMarkers();
        roundFailures();
        roundBudgetIsEnforcedBeforeRoot();
        unreadableFactsNeverMeanNoPending();
        coordinatorSingleFlightAndCoalesce();
        coordinatorCancelDropsStaleResult();
        coordinatorLeaveAndReenterBeforeCompletion();
        coordinatorSamplerExceptionAndRejectedExecutor();
        coordinatorPartialFailureIsolated();
        realShellFixture();
        System.out.println("PASS ReadinessGateway: read-only bounded command, 25 sources (core_capabilities optional), no pipeline, owner UID/exact-cmdline process evidence, real budget, three-state facts, single-flight/coalesce/cancel/follow-up, shell fixture");
    }

    static void commandIsReadOnlyAndBounded() {
        String fullCommand=ReadinessGateway.command();
        String ownerRead=ReadinessGateway.pidRead(ReadinessGateway.Paths.DEVICE);
        check(fullCommand.split(Pattern.quote(ownerRead),-1).length==3,"owner-aware PID sandwiches every round");
        // Owner PID block has dedicated real-shell failure fixtures; audit all other producers below.
        String cmd = fullCommand.replace(ownerRead,"echo owner_pid");
        Matcher m = Pattern.compile("__PCC_READ_(\\d+)__").matcher(cmd);
        int count = 0; while (m.find()) count++;
        check(count == ReadinessSample.ORDER.length && cmd.contains("__PCC_READ_END__"), "one frame per source: " + count);
        // Read-only: no file writes, no control-file mutation, no request file, no process control.
        for (String forbidden : new String[]{" > /", ">> /", " >/data", "mv ", "rm ", "chmod", "touch ", "tee ", "feeding-read-request", "am ", "start-foreground-service", "magisk", "cp ", "kill", "| head", "| sed", "| tr", "/proc"}) {
            check(!cmd.contains(forbidden), "forbidden token in read-only shell: " + forbidden);
        }
        // The only redirects are to /dev/null; there is no pipeline at all (case patterns `''|*[!0-9]*)` are not pipelines).
        Matcher redirect = Pattern.compile(">\\s*([^&\\s;)]+)").matcher(cmd);
        while (redirect.find()) check(redirect.group(1).equals("/dev/null") || redirect.group(1).equals("&1"), "unexpected redirect target: " + redirect.group(1));
        int pipes = 0; Matcher pipe = Pattern.compile("(?<!\\|)\\|(?!\\|)").matcher(cmd); while (pipe.find()) pipes++;
        int casePipes = cmd.split("''\\|\\*", -1).length - 1;
        check(pipes - casePipes == 0, "no pipeline in the command, got " + (pipes - casePipes));
        // 21 direct dumps are byte-capped by head; module.prop and feeding config are stat-guarded then filtered by sed directly.
        Matcher caps = Pattern.compile("head -c (\\d+) /").matcher(cmd);
        int capped = 0; while (caps.find()) { check(Integer.parseInt(caps.group(1)) == ReadinessSample.PER_READ_LIMIT + 1, "cap"); capped++; }
        check(capped == 21, "21 head-capped reads, got " + capped);
        check(cmd.split("stat -c %s", -1).length == 3 && cmd.split("test -f /data/adb", -1).length == 2, "two stat-guarded regular-file reads");
        check(cmd.split(Pattern.quote("rc=$?; if test $rc -ne 0; then echo " + ReadinessSample.MARK_ERROR), -1).length == 3, "stat exit status checked before its output");
        check(cmd.split(Pattern.quote("case \"$sz\" in ''|*[!0-9]*)"), -1).length == 3, "stat output must be numeric, else error");
        check(cmd.split(ReadinessSample.MARK_TOOLARGE, -1).length == 3, "only a numeric size above the limit is too-large");
        check(!cmd.contains(ReadinessSample.MARK_NOPROC), "the adapter never claims 'no process'");
        check(ownerRead.contains("OwnerGameProcess")==false && ownerRead.contains("/proc/$pcc_pid/status"),"only bounded candidate /proc reads, no process-table enumeration");
        check(ReadinessGateway.INNER_TIMEOUT_S == 8 && ReadinessGateway.TOTAL_LIMIT == 262144 && ReadinessGateway.COALESCE_MS == 5000 && ReadinessSample.ROUND_DEADLINE_MS == 10000, "limits");
        // Every simple command starts with an allowed program or shell keyword.
        for (String stmt : cmd.split("(?<!\\|)\\|(?!\\|)|;|&&|\\|\\||\\bthen\\b|\\belse\\b|\\bdo\\b")) {
            String t = stmt.trim();
            while (t.startsWith("(") || t.endsWith(")") || t.startsWith("elif ") || t.startsWith("if ") || t.startsWith("! ")) t = t.replaceFirst("^(\\(|elif |if |! )", "").replaceFirst("\\)$", "").trim();
            if (t.isEmpty() || t.equals(")") || t.equals("fi") || t.equals("done") || t.equals("esac") || t.equals(":") || t.startsWith("'__PCC") || t.startsWith("*)") || t.startsWith("*[") || t.startsWith("''")) continue;
            check(t.matches("(printf |echo |test |command -v pidof|pidof |sed |head |stat |case |pid=\\$|rc=\\$\\?|sz=\\$|\"\\$pid\").*"),
                    "unexpected shell statement: " + t);
        }
    }


    static String frame(String... payloads) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < payloads.length; i++) b.append("\n__PCC_READ_").append(i).append("__\n").append(payloads[i]);
        return b.append("\n__PCC_READ_END__\n").toString();
    }
    static final class Clock implements ReadinessGateway.Clock {
        long wall = NOW, mono = 1_000_000L;
        @Override public long wallNow() { return wall; }
        @Override public long monoNow() { return mono; }
    }
    static ReadinessGateway.Facts facts() { return () -> APP; }
    static ReadinessGateway.Runner fixed(final BoundedCommand.Result r) { return (cmd, inner) -> r; }
    static String[] allMissing() { String[] p = new String[ReadinessSample.ORDER.length]; for (int i = 0; i < p.length; i++) p[i] = "__PCC_MISSING__\n"; p[0] = p[p.length - 1] = ReadinessSample.MARK_NOPROC + "\n"; return p; }

    static void framingAndMarkers() {
        String[] p = new String[ReadinessSample.ORDER.length];
        for (int i = 0; i < p.length; i++) p[i] = "__PCC_MISSING__\n";
        p[0] = "4242\n"; p[p.length - 1] = "4242\n";
        p[ReadinessSample.index(ReadinessSample.CORE_RUNTIME)] = "v1\t" + (NOW - 1000) + "\t4242\t1.5.22\t90\t157.0\n";
        p[ReadinessSample.index(ReadinessSample.DISPATCH_PROBE_STATUS)] = "__PCC_DENIED__\n";
        p[1] = "disabled=no\nstaged=no\n" + ReadinessSample.MARK_TOOLARGE + "\n";
        StringBuilder big = new StringBuilder(); while (big.length() < ReadinessSample.PER_READ_LIMIT + 1) big.append("x");
        p[ReadinessSample.index(ReadinessSample.RETURN_CANDIDATES)] = big.toString();
        final String raw = frame(p);
        ReadinessSample s = ReadinessGateway.sample(fixed(new BoundedCommand.Result(raw, true, false, false)), facts(), new Clock());
        check(s.roundOk && s.stablePid().equals("4242"), "framed round ok");
        check(s.read(ReadinessSample.CORE_RUNTIME).ok(), "runtime read ok");
        check(s.read(ReadinessSample.DISPATCH_PROBE_STATUS).state == ReadinessSample.ReadState.DENIED, "denied marker");
        check(s.read(ReadinessSample.RETURN_CANDIDATES).state == ReadinessSample.ReadState.TRUNCATED, "overflow marks truncated");
        check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.TRUNCATED, "too-large marker after partial lines is truncated");
        check(ReadinessModel.core(s).moduleReadFailed, "too-large module.prop is a read failure, not 'not installed'");
        ReadinessSample broken = ReadinessGateway.sample(fixed(new BoundedCommand.Result(raw.replace("__PCC_READ_10__", "__PCC_READ_XX__"), true, false, false)), facts(), new Clock());
        check(!broken.roundOk, "broken framing invalidates round");
    }

    static void roundFailures() {
        ReadinessSample timedOut = ReadinessGateway.sample(fixed(new BoundedCommand.Result("", false, true, false)), facts(), new Clock());
        check(!timedOut.roundOk && timedOut.roundTimedOut, "timeout recorded");
        ReadinessSample truncated = ReadinessGateway.sample(fixed(new BoundedCommand.Result("x", false, false, true)), facts(), new Clock());
        check(!truncated.roundOk, "truncated round invalid");
        ReadinessSample thrown = ReadinessGateway.sample((cmd, inner) -> { throw new IllegalStateException("su denied"); }, facts(), new Clock());
        check(!thrown.roundOk && thrown.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR, "exception becomes error reads");
        for (ReadinessModel.FeatureReadiness f : ReadinessModel.evaluate(thrown).features) check(f.readiness == ReadinessModel.Readiness.UNKNOWN, "no judgement after su failure");
        ReadinessSample noFacts = ReadinessGateway.sample(fixed(new BoundedCommand.Result(frame(allMissing()), true, false, false)), () -> { throw new IllegalStateException("prefs"); }, new Clock());
        check(noFacts.roundOk && noFacts.app.unreadable.contains("app_facts:IllegalStateException"), "facts failure recorded as unreadable, round kept");
    }

    /** S2: the 10 s deadline is a budget. Slow facts shrink the inner timeout; when nothing is left no root shell is started. */
    static void roundBudgetIsEnforcedBeforeRoot() {
        final Clock clock = new Clock();
        final int[] calls = {0}; final int[] innerSeen = {-1};
        ReadinessGateway.Runner counting = (cmd, inner) -> { calls[0]++; innerSeen[0] = inner; return new BoundedCommand.Result(frame(allMissing()), true, false, false); };
        // Facts take 9.5 s: only 0.5 s left, below outer slack + 1 s → no root process.
        ReadinessGateway.Facts slow = () -> { clock.mono += 9500; clock.wall += 9500; return APP; };
        ReadinessSample s = ReadinessGateway.sample(counting, slow, clock);
        check(calls[0] == 0 && s.roundTimedOut && !s.roundOk && !s.skipReason.isEmpty(), "budget exhausted → runner never called: calls=" + calls[0]);
        ReadinessModel.Result r = ReadinessModel.evaluate(s);
        ReadinessModelTest.all(r, ReadinessModel.Readiness.UNKNOWN, "budget exhausted round is not judged");
        check(ReadinessModelTest.f(r, "scan").codes.contains("round_budget_exhausted") && r.coreSummary.contains("逾時"), "reason names the budget");
        // Facts take 5 s: 5 s left → inner timeout 3 s (5 − 2 s outer slack).
        Clock c2 = new Clock(); calls[0] = 0;
        ReadinessGateway.Facts medium = () -> { c2.mono += 5000; c2.wall += 5000; return APP; };
        ReadinessGateway.sample(counting, medium, c2);
        check(calls[0] == 1 && innerSeen[0] == 3, "remaining budget shrinks inner timeout: inner=" + innerSeen[0]);
        // Facts instant: full 8 s inner.
        Clock c3 = new Clock(); calls[0] = 0;
        ReadinessGateway.sample(counting, facts(), c3);
        check(calls[0] == 1 && innerSeen[0] == 8, "full budget gives 8 s inner");
        // Shell itself exceeds the budget even though it returned: still timed out.
        final Clock c4 = new Clock();
        ReadinessSample late = ReadinessGateway.sample((cmd, inner) -> { c4.mono += 11_000; c4.wall += 11_000; return new BoundedCommand.Result(frame(allMissing()), true, false, false); }, facts(), c4);
        check(late.roundTimedOut && !late.roundOk && late.elapsedMs == 11_000 && late.skipReason.isEmpty(), "late shell result is not judged");
    }

    /** S2: a fact whose read threw is UNKNOWN, not false; GPS-work features refuse to claim "no blocker". */
    static void unreadableFactsNeverMeanNoPending() {
        ReadinessSample.AppFacts facts = new ReadinessSample.AppFacts("1.5.22", 90, true, false, false, false, false, "", false, true, "", "", -1, java.util.Arrays.asList("core_pending", "stop_incomplete"));
        ReadinessModel.Result r = ReadinessModel.evaluate(ReadinessModelTest.sample(ReadinessModelTest.healthy(), facts));
        for (String id : new String[]{"batch", "farm_point", "farm_route"}) {
            ReadinessModel.FeatureReadiness f = ReadinessModelTest.f(r, id);
            check(f.readiness == ReadinessModel.Readiness.UNKNOWN && f.codes.contains("pending_state_unreadable") && f.reason.contains("不能當作沒有阻擋"), id + " must not assume no blocker");
        }
        ReadinessModel.FeatureReadiness ret = ReadinessModelTest.f(r, "return");
        check(ret.readiness == ReadinessModel.Readiness.READY && ret.codes.contains("pending_state_unreadable:core_pending"), "non-GPS feature keeps its own evidence but carries the flag");
        check(r.coreSummary.contains("阻擋狀態讀取失敗") && r.coreSummary.contains("core_pending"), "summary names unreadable facts");
    }

    static final class Manual implements Executor {
        final List<Runnable> queue = new ArrayList<>(); boolean reject;
        @Override public void execute(Runnable r) { if (reject) throw new RejectedExecutionException(); queue.add(r); }
        void runAll() { List<Runnable> now = new ArrayList<>(queue); queue.clear(); for (Runnable r : now) r.run(); }
    }
    static ReadinessSample healthySample() { return ReadinessModelTest.sample(ReadinessModelTest.healthy()); }
    static final class Got implements ReadinessGateway.Listener {
        final List<Integer> gens = new ArrayList<>(); final List<Boolean> cached = new ArrayList<>(); ReadinessModel.Result lastResult;
        @Override public void onResult(int generation, ReadinessModel.Result result, boolean fromCache) { gens.add(generation); cached.add(fromCache); lastResult = result; }
    }

    static void coordinatorSingleFlightAndCoalesce() {
        Clock clock = new Clock(); Manual exec = new Manual();
        final int[] samples = {0};
        ReadinessGateway.Coordinator c = new ReadinessGateway.Coordinator(clock, exec, () -> { samples[0]++; return healthySample(); });
        Got got = new Got();
        check(c.refresh(got), "first refresh starts");
        check(!c.refresh(got) && !c.refresh(got), "repeat while in flight coalesces");
        check(samples[0] == 0 && exec.queue.size() == 1, "exactly one round queued");
        exec.runAll();
        check(samples[0] == 1 && got.gens.size() == 1 && !got.cached.get(0) && c.delivered() == 1, "one delivery, not cached");
        clock.mono += 2000;
        check(!c.refresh(got), "within 5 s re-delivers cache");
        check(got.gens.size() == 2 && got.cached.get(1) && samples[0] == 1, "cached result flagged, no new shell");
        clock.mono += 4000;
        check(c.refresh(got), "after 5 s a new round starts");
        exec.runAll();
        check(samples[0] == 2 && c.started() == 2, "second round ran");
    }

    static void coordinatorCancelDropsStaleResult() {
        Clock clock = new Clock(); Manual exec = new Manual();
        ReadinessGateway.Coordinator c = new ReadinessGateway.Coordinator(clock, exec, ReadinessGatewayTest::healthySample);
        Got got = new Got();
        c.refresh(got);
        c.cancel();
        exec.runAll();
        check(got.gens.isEmpty() && c.dropped() == 1 && c.last() == null, "stale result dropped, nothing cached");
        check(!c.inFlight() && exec.queue.isEmpty(), "no follow-up when nobody re-asked");
        clock.mono += 6000;
        check(c.refresh(got), "refresh after cancel");
        exec.runAll();
        check(got.gens.size() == 1 && got.gens.get(0) == 1, "delivered with new generation");
    }

    static void coordinatorLeaveAndReenterBeforeCompletion() {
        Clock clock = new Clock(); Manual exec = new Manual();
        final int[] samples = {0};
        ReadinessGateway.Coordinator c = new ReadinessGateway.Coordinator(clock, exec, () -> { samples[0]++; return healthySample(); });
        Got first = new Got(), second = new Got();
        c.refresh(first);
        c.cancel();
        check(!c.refresh(second), "re-entered page asks while old round still runs");
        exec.runAll();
        check(first.gens.isEmpty() && c.dropped() == 1 && c.followUps() == 1, "old listener never hears; follow-up scheduled");
        check(exec.queue.size() == 1 && c.inFlight(), "follow-up round queued");
        exec.runAll();
        check(second.gens.size() == 1 && second.gens.get(0) == 1 && !second.cached.get(0) && samples[0] == 2, "new page gets a fresh result with the new generation");
        clock.mono += 6000;
        Got a = new Got(), b = new Got();
        c.refresh(a); c.refresh(b);
        exec.runAll();
        check(a.gens.isEmpty() && b.gens.size() == 1 && c.followUps() == 1, "newest listener wins, no extra round");
    }

    static void coordinatorSamplerExceptionAndRejectedExecutor() {
        Clock clock = new Clock(); Manual exec = new Manual();
        ReadinessGateway.Coordinator c = new ReadinessGateway.Coordinator(clock, exec, () -> { throw new IllegalStateException("boom"); });
        Got got = new Got();
        c.refresh(got);
        exec.runAll();
        check(got.gens.size() == 1 && got.lastResult != null && !c.inFlight(), "sampler failure still delivers a result");
        for (ReadinessModel.FeatureReadiness f : got.lastResult.features) check(f.readiness == ReadinessModel.Readiness.UNKNOWN, "unavailable result is all unknown");
        check(got.lastResult.coreSummary.contains("未執行") && got.lastResult.coreSummary.contains("IllegalStateException"), "summary names the failure");
        clock.mono += 6000; exec.reject = true;
        Got g2 = new Got();
        check(c.refresh(g2), "refresh attempted");
        check(g2.gens.size() == 1 && g2.lastResult.coreSummary.contains("執行緒不可用") && !c.inFlight(), "rejection delivers an explicit result");
        exec.reject = false; clock.mono += 6000;
        check(c.refresh(g2), "can refresh again after rejection");
    }

    static void coordinatorPartialFailureIsolated() {
        Clock clock = new Clock(); Manual exec = new Manual();
        java.util.Map<String, ReadinessSample.Read> m = ReadinessModelTest.healthy();
        m.put(ReadinessSample.PLANTER_INVENTORY, ReadinessSample.Read.error());
        final ReadinessSample partial = ReadinessModelTest.sample(m);
        ReadinessGateway.Coordinator c2 = new ReadinessGateway.Coordinator(clock, exec, () -> partial);
        Got got = new Got();
        c2.refresh(got);
        exec.runAll();
        check(got.lastResult != null, "delivered");
        boolean planterUnknown = false, returnReady = false;
        for (ReadinessModel.FeatureReadiness f : got.lastResult.features) {
            if (f.id.equals("planter")) planterUnknown = f.readiness == ReadinessModel.Readiness.UNKNOWN;
            if (f.id.equals("return")) returnReady = f.readiness == ReadinessModel.Readiness.READY;
        }
        check(planterUnknown && returnReady, "partial failure isolated to its feature");
        String details = ReadinessGateway.details(got.lastResult, false);
        check(details.contains("育苗") && details.contains("待核對") && !details.contains("taskA") && !details.contains("121.0") && !details.contains("快取"), "details hide ids/coordinates and say not cached");
        check(got.lastResult.oneLine().startsWith("功能就緒："), "one-line summary available");
    }

    // ------------------------------------------------------------ real shell

    static final boolean CI = "true".equalsIgnoreCase(System.getenv("CI"));

    /** Skips are visible; under CI a skip is a failure so a green job cannot hide an unexecuted fixture. */
    static void skip(String why) {
        if (CI) throw new AssertionError("real shell fixture must run under CI: " + why);
        System.out.println("SKIP real shell fixture: " + why);
    }

    /** Bounded probe: never an unbounded readAll/waitFor. */
    static String probe(String[] argv, java.util.Map<String, String> env, int limit) {
        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            if (env != null) pb.environment().putAll(env);
            BoundedCommand.Result r = BoundedCommand.run(pb, 5_000, limit);
            return r.ok ? r.text.trim() : null;
        } catch (Exception e) { return null; }
    }

    /** Resolves the shell to an absolute host path so its own tool directory can be put on PATH. */
    static String findSh() {
        String configured=System.getenv("PCC_TEST_SH");
        if(configured!=null)return configuredSh(configured);
        for (String candidate : new String[]{"sh", "/bin/sh", "C:/Program Files/Git/usr/bin/sh.exe", "C:/Program Files/Git/bin/sh.exe", "C:/Program Files (x86)/Git/usr/bin/sh.exe"}) {
            String ok = probe(new String[]{candidate, "-c", "printf ok"}, null, 64);
            if (!"ok".equals(ok)) continue;
            String abs = probe(new String[]{candidate, "-c", "p=$(command -v sh); if command -v cygpath >/dev/null 2>&1; then cygpath -m \"$p\"; else printf '%s' \"$p\"; fi"}, null, 1024);
            if (abs != null && !abs.isEmpty() && new java.io.File(abs).isFile()) return abs;
            if (new java.io.File(candidate).isFile()) return candidate;
        }
        return null;
    }

    /** An explicitly selected test shell is authoritative: never silently switch or skip after a failed probe. */
    static String configuredSh(String configured) {
        if(configured==null||configured.trim().isEmpty())throw new AssertionError("PCC_TEST_SH must name an absolute shell executable");
        java.io.File executable=new java.io.File(configured);
        if(!executable.isAbsolute()||!executable.isFile())throw new AssertionError("PCC_TEST_SH must name an existing absolute shell executable");
        String absolute=executable.getAbsolutePath();
        if(!"ok".equals(probe(new String[]{absolute,"-c","printf ok"},null,64)))
            throw new AssertionError("PCC_TEST_SH failed its bounded printf probe; no shell fallback");
        return absolute;
    }

    /** PATH with the shell's own directory first; handles the Windows "Path" spelling and the current-process PATH being empty. */
    static java.util.Map<String, String> toolEnv(String sh) {
        String dir = new java.io.File(sh).getAbsoluteFile().getParent();
        String key = "PATH"; String current = "";
        for (java.util.Map.Entry<String, String> e : System.getenv().entrySet()) if (e.getKey().equalsIgnoreCase("PATH")) { key = e.getKey(); current = e.getValue(); }
        java.util.Map<String, String> env = new java.util.HashMap<>();
        env.put(key, dir + java.io.File.pathSeparator + current);
        if (!key.equals("PATH")) env.put("PATH", dir + java.io.File.pathSeparator + current);
        return env;
    }

    static java.util.List<String> missingTools(String sh, java.util.Map<String, String> env) {
        String out = probe(new String[]{sh, "-c", "for t in sed stat head tr test; do command -v $t >/dev/null 2>&1 || printf 'MISSING:%s\\n' $t; done; printf done"}, env, 4096);
        java.util.List<String> missing = new ArrayList<>();
        if (out == null) { missing.add("(probe failed)"); return missing; }
        for (String line : out.split("\\n")) if (line.startsWith("MISSING:")) missing.add(line.substring(8));
        return missing;
    }

    static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) >= 0) b.write(buf, 0, n); return b.toByteArray();
    }
    static void write(Path p, String text) throws IOException { Files.createDirectories(p.getParent()); Files.write(p, text.getBytes(StandardCharsets.UTF_8)); }

    /**
     * The generated command is executed by a real POSIX shell against a temp
     * directory. Tool overrides (pidof / stat) are injected as shell functions
     * in a prelude, so the fixture does not depend on executable bits or on
     * whatever pidof the host has. Under CI a skip fails the test.
     */
    static void realShellFixture() throws Exception {
        String sh = findSh();
        if (sh == null) { skip("no POSIX sh on this host"); return; }
        java.util.Map<String, String> env = toolEnv(sh);
        java.util.List<String> missing = missingTools(sh, env);
        if (!missing.isEmpty()) { skip("shell " + sh + " lacks tools " + missing); return; }
        Path dir = Files.createTempDirectory("pcc readiness ~");
        String base = dir.toAbsolutePath().toString().replace('\\', '/');
        try {
            write(dir.resolve("mod/module.prop"), "id=pikmin_nectar_rpc\nversion=1.5.22\nversionCode=90\n");
            write(dir.resolve("tmp/pikmin-feeding-config.tsv"), "v2\tabc\t1\t4\t20\t100\t1\t1\t3\nrule\tx\ty\nrule\tz\tw\n");
            write(dir.resolve("game/core_runtime.tsv"), "v1\t" + (NOW - 1000) + "\t4242\t1.5.22\t90\t157.0\n");
            write(dir.resolve("game/proc/4242/status"),"Uid:\t10276\t10276\t10276\t10276\n");
            write(dir.resolve("game/proc/4242/cmdline"),"com.nianticlabs.pikmin\u0000");
            StringBuilder big = new StringBuilder(); while (big.length() < 40_000) big.append("r\n");
            write(dir.resolve("game/return_candidates.tsv"), big.toString());
            ReadinessGateway.Paths p = new ReadinessGateway.Paths(base + "/game/", base + "/tmp/", base + "/mod", base + "/staged");

            // 1. pidof exists, reports the PID.
            ReadinessSample s = run(sh, env, p, "pidof() { echo 4242; }");
            check(s.roundOk, "shell round framed and complete");
            check(s.stablePid().equals("4242"), "pidof success → PID: " + s.read(ReadinessSample.PID_BEFORE).text);
            ReadinessSample.Read mod = s.read(ReadinessSample.MODULE);
            check(mod.ok() && mod.text.contains("version=1.5.22") && mod.text.contains("versionCode=90") && mod.text.contains("disabled=no") && mod.text.contains("staged=no") && !mod.text.contains("id="), "module.prop filtered: state=" + mod.state + " text=" + mod.text);
            check(s.read(ReadinessSample.CTRL_FEEDING_CONFIG).ok() && s.read(ReadinessSample.CTRL_FEEDING_CONFIG).text.equals("v2\tabc\t1\t4\t20\t100\t1\t1\t3"), "config first line only");
            check(s.read(ReadinessSample.RETURN_CANDIDATES).state == ReadinessSample.ReadState.TRUNCATED, "40 KB file is truncated by head");
            check(s.read(ReadinessSample.COMPATIBILITY).state == ReadinessSample.ReadState.MISSING, "missing file marker");
            check(ReadinessModel.core(s).coreLoaded, "model: loaded with pid 4242");

            // 2. pidof exit 1 ("not found"): NOT trusted as "game closed" → ERROR → model unknown, never WAITING_GAME.
            s = run(sh, env, p, "pidof() { return 1; }");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR, "pidof 1 → ERROR: " + s.read(ReadinessSample.PID_BEFORE).state);
            ReadinessModel.Result r = ReadinessModel.evaluate(s);
            check(r.core.pidReadFailed && ReadinessModelTest.f(r, "return").readiness == ReadinessModel.Readiness.UNKNOWN
                    && !ReadinessModelTest.f(r, "return").codes.contains("game_not_running") && ReadinessModelTest.f(r, "return").reason.contains("無法確認"), "model: unknown, not game closed");

            // 3. pidof exists but fails with exit 2 and no stderr: ERROR.
            s = run(sh, env, p, "pidof() { return 2; }");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR, "pidof exit 2 → ERROR");

            // 4/5 (R4-1 counterexamples): failing head or tr can no longer influence the verdict — there is no fallback to mask.
            s = run(sh, env, p, "pidof() { return 1; }; head() { return 2; }");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR && ReadinessModelTest.f(ReadinessModel.evaluate(s), "return").readiness == ReadinessModel.Readiness.UNKNOWN, "pidof 1 + head failure → unknown, not game closed");
            s = run(sh, env, p, "pidof() { return 1; }; tr() { return 2; }");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR && ReadinessModelTest.f(ReadinessModel.evaluate(s), "return").readiness == ReadinessModel.Readiness.UNKNOWN, "pidof 1 + tr failure → unknown, not game closed");
            // pidof exit 0 but empty output is not a PID either.
            s = run(sh, env, p, "pidof() { return 0; }");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR, "pidof 0 with empty output → ERROR");

            // 6. pidof missing entirely.
            s = run(sh, env, p, "pidof() { return 127; }; unset -f pidof 2>/dev/null; PATH=/nonexistent");
            check(s.read(ReadinessSample.PID_BEFORE).state == ReadinessSample.ReadState.ERROR, "no pidof → ERROR");

            // 7. stat failures are read errors, never too-large or not-installed.
            s = run(sh, env, p, "pidof() { echo 4242; }; stat() { return 1; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.ERROR && s.read(ReadinessSample.CTRL_FEEDING_CONFIG).state == ReadinessSample.ReadState.ERROR, "stat exit 1 → ERROR: " + s.read(ReadinessSample.MODULE).state);
            s = run(sh, env, p, "pidof() { echo 4242; }; stat() { echo abc; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.ERROR, "non-numeric stat → ERROR");
            // R4-2 counterexample: numeric output but non-zero exit must not be trusted.
            s = run(sh, env, p, "pidof() { echo 4242; }; stat() { echo 10; return 1; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.ERROR && s.read(ReadinessSample.CTRL_FEEDING_CONFIG).state == ReadinessSample.ReadState.ERROR, "stat numeric + exit 1 → ERROR: " + s.read(ReadinessSample.MODULE).state);
            check(ReadinessModel.core(s).moduleReadFailed && !ReadinessModelTest.f(ReadinessModel.evaluate(s), "return").codes.contains("core_not_installed"), "read failure is not 'not installed'");

            // 8. module.prop is a directory: regular-file test fails → DENIED-class error, not a sed crash.
            Files.delete(dir.resolve("mod/module.prop")); Files.createDirectories(dir.resolve("mod/module.prop"));
            s = run(sh, env, p, "pidof() { echo 4242; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.DENIED || s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.ERROR, "directory in place of module.prop → not readable as a file: " + s.read(ReadinessSample.MODULE).state);
            check(ReadinessModel.core(s).moduleReadFailed, "…and a read failure for the model");
            deleteTree(dir.resolve("mod/module.prop"));

            // 9. Oversized module.prop → stat guard → too large → TRUNCATED.
            StringBuilder huge = new StringBuilder("version=1.5.22\n"); while (huge.length() < 40_000) huge.append("junk=x\n");
            write(dir.resolve("mod/module.prop"), huge.toString());
            s = run(sh, env, p, "pidof() { echo 4242; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.TRUNCATED, "oversized module.prop → too large");

            // 10. Producer (sed) failure after the flags were printed → tail marker → ERROR.
            write(dir.resolve("mod/module.prop"), "id=pikmin_nectar_rpc\nversion=1.5.22\nversionCode=90\n");
            s = run(sh, env, p, "pidof() { echo 4242; }; sed() { return 2; }");
            check(s.read(ReadinessSample.MODULE).state == ReadinessSample.ReadState.ERROR, "sed failure after flags → ERROR");
            System.out.println("PASS real shell fixture (13 cases) via " + sh + (CI ? " (CI)" : ""));
        } finally { deleteTree(dir); }
    }
    /** The command is written to a script file (Windows ProcessBuilder would re-quote a single -c argument) with a prelude that overrides tools via shell functions. */
    static ReadinessSample run(String sh, java.util.Map<String, String> env, ReadinessGateway.Paths p, String prelude) throws Exception {
        Path dir=java.nio.file.Paths.get(p.game).toAbsolutePath().getParent();
        ReadinessGateway.Paths local=new ReadinessGateway.Paths(relative(dir,p.game),relative(dir,p.tmp),relative(dir,p.mod),
            relative(dir,p.staged),relative(dir,p.owner),relative(dir,p.proc));
        final Path script = Files.createTempFile(dir,"pcc-readiness-", ".sh");
        try {
            return ReadinessGateway.sample((cmd, inner) -> {
                String ownerStat="stat() { if test \"$2\" = '%u'; then echo 10276; else command stat \"$@\"; fi; }; ";
                Files.write(script, (ownerStat+prelude + "\n" + cmd).getBytes(StandardCharsets.UTF_8));
                ProcessBuilder pb = new ProcessBuilder(sh, script.getFileName().toString()).directory(dir.toFile());
                pb.environment().putAll(env);
                BoundedCommand.Result read=BoundedCommand.run(pb,20_000,ReadinessGateway.TOTAL_LIMIT);
                if(!read.ok)throw new AssertionError("real shell execution failed: "+read.failureSummary()+"; "+read.text.substring(0,Math.min(1200,read.text.length())));
                return read;
            }, facts(), new Clock(), local);
        } finally { Files.deleteIfExists(script); }
    }
    static String relative(Path dir,String path){
        String local=dir.relativize(java.nio.file.Paths.get(path).toAbsolutePath()).toString().replace('\\','/');
        return path.endsWith("/")?local+"/":local;
    }
    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        if (Files.isDirectory(p)) try (java.util.stream.Stream<Path> children = Files.list(p)) { for (Path c : (Iterable<Path>) children::iterator) deleteTree(c); }
        Files.delete(p);
    }

}

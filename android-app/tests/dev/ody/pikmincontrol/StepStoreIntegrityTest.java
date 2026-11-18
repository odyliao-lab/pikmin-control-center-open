package dev.ody.pikmincontrol;

public final class StepStoreIntegrityTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static void expectCorrupt(String raw, String needle) {
        try {
            StepState.decode(raw);
            check(false, "expected corrupt: " + needle);
        } catch (StepStoreException error) {
            checks++;
            if (!error.getMessage().contains("corrupt") && !error.getMessage().contains(needle))
                throw new AssertionError("wanted " + needle + " got " + error.getMessage());
        }
    }

    public static void main(String[] args) {
        roundTripV3();
        truncateAfterSettings();
        truncateAroundPlan();
        truncateMidSpan();
        emptyExistingFileNotMissing();
        unknownStatus();
        badIntervals();
        negativeGeneration();
        uiAdapterStoreError();
        truncatedEnabledLegacyRefused();
        completeHistoricalLegacyRefused();
        System.out.println("PASS StepStoreIntegrityTest " + checks + " checks; S3 store integrity");
    }

    static String complete() {
        StepState state = new StepState();
        state.disclosed = true;
        state.desiredRunning = true;
        state.lockId = "lock-1";
        state.lockRole = "background";
        state.lockGeneration = 2L;
        state.cancelNonce = 3L;
        state.commandEpoch = 4L;
        state.ledger.add(new StepPlan("p1", StepPlan.MANUAL, StepPlan.PREPARED, 10, 1000L, 2000L,
                28800, 28800, "c1", 0L, "", ""));
        return state.encode();
    }

    static void roundTripV3() {
        String raw = complete();
        check(raw.startsWith("PCCSTEP/3"), "v3 header");
        check(raw.contains("FOOTER\t"), "footer");
        check(raw.contains("CHECKSUM\t"), "checksum");
        StepState loaded = StepState.decode(raw);
        check(loaded.desiredRunning, "roundtrip running");
        check(loaded.ledger.pending() != null, "pending kept");
        check(loaded.commandEpoch == 4L, "epoch kept");
    }

    static int indexAfter(String raw, String marker) {
        int at = raw.indexOf(marker);
        if (at < 0) throw new AssertionError("missing " + marker);
        return at + marker.length();
    }

    static void truncateAfterSettings() {
        String raw = complete();
        int cut = raw.indexOf("writerOwner=");
        check(cut > 0, "has writerOwner");
        expectCorrupt(raw.substring(0, cut), "required");
    }

    static void truncateAroundPlan() {
        String raw = complete();
        int plan = raw.indexOf("PLAN\t");
        expectCorrupt(raw.substring(0, plan), "footer");
        String v2 = "PCCSTEP/2\ndisclosed=1\ndesiredRunning=0\ntarget=8000\nwindowStart=480\nwindowEnd=1320\n"
                + "rate=130\nintervalMs=60000\nlockGeneration=1\ncancelNonce=0\nPLAN\tp1\tmanual";
        expectCorrupt(v2, "plan");
        expectCorrupt(raw.substring(0, plan + 10), "corrupt");
        check(plan > 0, "complete has PLAN");
    }

    static void truncateMidSpan() {
        StepState state = new StepState();
        state.disclosed = true;
        state.lockGeneration = 1L;
        state.cancelNonce = 1L;
        state.ledger.remember(new StepPlan("s", StepPlan.MANUAL, StepPlan.CONFIRMED, 1, 10L, 20L,
                0, 0, "cs", 0L, "h", ""));
        String raw = state.encode();
        int span = raw.indexOf("SPAN\t");
        check(span > 0, "has span");
        expectCorrupt(raw.substring(0, span + 6), "span");
    }

    static void emptyExistingFileNotMissing() {
        MemoryStepStore store = new MemoryStepStore();
        check(store.load().isMissing(), "never written is missing");
        store.save("");
        StepLoadResult empty = store.load();
        check(empty.isUnreadable(), "empty file is not missing");
        check(!empty.isMissing(), "empty ≠ MISSING");
        FilteringStepStore unread = new FilteringStepStore(store);
        unread.unreadable = true;
        MutableStepClock clock = new MutableStepClock(1L, 1L);
        StepEngine engine = new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d),
                unread, new FakeStepHealth(), new StepWorkLock(unread));
        StepView view = engine.snapshot();
        check(StepView.STORE_ERROR.equals(view.phase), "unreadable snapshot");
        unread.unreadable = false;
        check(store.load().isUnreadable(), "did not auto-heal empty ledger");
    }

    static void unknownStatus() {
        String raw = rechecksum(complete().replace("\tPREPARED\t", "\tWEIRD\t"));
        expectCorrupt(raw, "status");
    }

    static void badIntervals() {
        StepState state = new StepState();
        state.disclosed = true;
        state.lockGeneration = 1L;
        state.cancelNonce = 0L;
        String good = state.encode();
        String badPlan = good.replace("FOOTER\tplans=0", "PLAN\tbad\tmanual\tCONFIRMED\t1\t50\t10\t0\t0\tc\t0\th\t\nFOOTER\tplans=1");
        expectCorrupt(rechecksum(badPlan), "interval");
        String badSpan = good.replace("FOOTER\tplans=0\tspans=0", "SPAN\t80\t40\nFOOTER\tplans=0\tspans=1");
        expectCorrupt(rechecksum(badSpan), "interval");
    }

    static String rechecksum(String raw) {
        int at = raw.indexOf("CHECKSUM\t");
        if (at < 0) return raw;
        String body = raw.substring(0, at);
        return body + "CHECKSUM\t" + Long.toHexString(StepState.checksumOf(body)) + "\n";
    }

    static void negativeGeneration() {
        String raw = complete().replace("lockGeneration=2", "lockGeneration=-1");
        expectCorrupt(rechecksum(raw), "generation");
    }

    // Fixed example of the actual v2 encoder at 0a4f1d7, not a v3 header substitution.
    // No FOOTER or CHECKSUM existed. Preserve both complete and truncated bytes.
    static final String HISTORICAL_V2 =
            "PCCSTEP/2\ndisclosed=1\ndesiredRunning=1\ntarget=8000\nwindowStart=480\nwindowEnd=1320\n"
            + "rate=130\npreset=normal\nintervalMs=60000\nwriterOwner=\nlockId=\nlockRole=\n"
            + "lockGeneration=2\ncancelNonce=3\nrunStartWall=1000\nrunStartElapsed=0\nnextDueElapsed=0\n"
            + "elapsedBasis=0\nlastConfirmedEndMs=0\nlastSuccessElapsed=0\nrestStreak=0\nbackoffMs=0\n"
            + "waitUntilElapsed=0\ndayKey=\nzoneId=\nsessionConfirmed=0\nsessionId=sess\nclockEpoch=0\n"
            + "PLAN\tp1\tmanual\tPREPARED\t10\t1000\t2000\t28800\t28800\tc1\t0\t\t\n"
            + "SPAN\t1000\t2000\n";
    static void assertLegacyPreserved(String raw) {
        MemoryStepStore store = new MemoryStepStore();
        store.save(raw);
        FakeStepHealth health = new FakeStepHealth();
        StepEngine engine = new StepEngine(new MutableStepClock(1_700_000_000_000L, 10_000L),
                new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
        check(StepView.STORE_ERROR.equals(engine.start().phase), "legacy rejected");
        check(StepView.STORE_ERROR.equals(engine.stop().phase), "stop cannot erase legacy");
        check(health.writes == 0, "legacy zero writes");
        check(raw.equals(store.load().raw), "legacy original preserved");
    }
    static void truncatedEnabledLegacyRefused() {
        int plan = HISTORICAL_V2.indexOf("PLAN\t");
        assertLegacyPreserved(HISTORICAL_V2.substring(0, plan));
        assertLegacyPreserved(HISTORICAL_V2.substring(0, plan + 8));
        int cut = HISTORICAL_V2.indexOf("runStartWall=");
        assertLegacyPreserved(HISTORICAL_V2.substring(0, cut));
    }
    static void completeHistoricalLegacyRefused() {
        check(!HISTORICAL_V2.contains("FOOTER"), "historical format has no footer");
        assertLegacyPreserved(HISTORICAL_V2);
        // v1 rejection contract only; not claiming this is an authentic v1 encoder output.
        assertLegacyPreserved(HISTORICAL_V2.replace("PCCSTEP/2", "PCCSTEP/1"));
        assertLegacyPreserved(HISTORICAL_V2 + "FOOTER\tplans=1\tspans=0\tlogs=0\n");
    }

    static void uiAdapterStoreError() {
        FilteringStepStore unread = new FilteringStepStore(new MemoryStepStore());
        unread.unreadable = true;
        StepEngine engine = new StepEngine(new MutableStepClock(1L, 1L), new StepSystemZone("Asia/Taipei"),
                new FixedStepRng(0.5d), unread, new FakeStepHealth(), new StepWorkLock(unread));
        StepView view = engine.snapshot();
        check(StepUiAdapter.writesDisabled(view), "adapter disables writes");
        check(StepUiAdapter.statusLine(view).contains(StepView.phaseLabel(StepView.STORE_ERROR)), "adapter shows STORE_ERROR");
        check(engine.safeSettings() == null, "no throwing settings load");
        check(StepUiAdapter.settingsNote(view, null).contains("儲存失敗"), "settings stay unused");
    }
}

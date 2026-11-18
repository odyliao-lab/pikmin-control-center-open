package dev.ody.pikmincontrol;

/** Two distinct, fresh game scans at one settled GPS stop. Never authorises from a missing scan. */
final class BatchArrivalProof {
    // The game normally scans every ~5 s; allow one missed scan, not an old arrival proof.
    static final long FIRST_SCAN_MAX_GAP_MS = 15_000L;
    private long firstScanMs;
    private long firstAcceptedElapsedMs;

    int count(long nowElapsedMs) {
        if (firstScanMs != 0L && (nowElapsedMs < firstAcceptedElapsedMs
                || nowElapsedMs - firstAcceptedElapsedMs > FIRST_SCAN_MAX_GAP_MS)) reset();
        return firstScanMs == 0L ? 0 : 1;
    }

    /** A temporarily absent candidate may not erase an in-range first scan. */
    int missing(long nowElapsedMs) { return count(nowElapsedMs); }

    /** Caller must prove provider in range, post-settle timestamp, and fresh in-range game distance. */
    int accept(long nowElapsedMs, long observedMs) {
        count(nowElapsedMs);
        if (observedMs <= 0L) { reset(); return 0; }
        if (firstScanMs == 0L) {
            firstScanMs = observedMs;
            firstAcceptedElapsedMs = nowElapsedMs;
            return 1;
        }
        return observedMs > firstScanMs ? 2 : 1;
    }

    void reset() {
        firstScanMs = 0L;
        firstAcceptedElapsedMs = 0L;
    }
}

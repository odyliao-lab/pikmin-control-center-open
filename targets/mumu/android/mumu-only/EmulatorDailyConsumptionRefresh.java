package dev.ody.pikmincontrol;

/** Eligibility for one passive replacement frame, NEVER acceptance of the old
 * frame. RouteSession independently enforces the same STATUS's one-shot permit
 * and original 2s reply budget before and after complete snapshot I/O. */
final class EmulatorDailyConsumptionRefresh {
    static boolean eligible(boolean daily, boolean refreshed, RouteObservation observation,
                            String pid, String account, long requested, long wall, long elapsed) {
        if (!daily || refreshed || observation == null) return false;
        // These are the original snapshot's captured validity/binding gates;
        // an invalid first snapshot is never rehabilitated by a retry.
        observation.require(pid, account, requested);
        FarmLocation fix = observation.location;
        if (!fix.valid || !pid.equals(fix.pid) || requested <= 0 || fix.at <= 0 || wall < fix.at)
            return false;
        // A captured-valid frame may lose either native heartbeat freshness or
        // the STRICT 2s progress-provider budget before adoption. Replace it,
        // never submit it to sticky progress failure or accept an aged sample.
        // Raw-game expiry, unowned/>5s provider and clock regression still fail.
        return fix.rawAt > 0 && elapsed >= fix.rawAt && elapsed-fix.rawAt <= 5000
                && fix.provider != null && fix.provider.fresh(elapsed)
                && elapsed >= fix.provider.fixMs
                && (wall-fix.at > 2000 || elapsed-fix.provider.fixMs > HarvestRouteProgress.SAMPLE_AGE_MS)
                && FarmLocation.point(fix.provider.latitude, fix.provider.longitude);
    }
}

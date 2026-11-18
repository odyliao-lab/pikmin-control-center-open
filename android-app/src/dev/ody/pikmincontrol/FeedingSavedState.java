package dev.ody.pikmincontrol;

/** Durable preferences are distinct from a live game snapshot. No RPC or Android dependency. */
final class FeedingSavedState {
    final FeedingConfig config;
    final boolean present, recovered, damaged, capacityMigrated;
    FeedingSavedState(String primary, String backup) {
        FeedingConfig parsed = parse(primary);
        present = !primary.isEmpty() || !backup.isEmpty();
        recovered = parsed == null && parse(backup) != null;
        if (parsed == null) parsed = parse(backup);
        damaged = present && parsed == null;
        if (parsed == null) {
            parsed = new FeedingConfig();
            parsed.revision = "00000000-0000-0000-0000-000000000000";
        }
        capacityMigrated = !damaged && parsed.useGameCapacity();
        config = parsed;
    }
    private static FeedingConfig parse(String raw) {
        if (raw.isEmpty()) return null;
        try { return FeedingConfig.parse(raw); } catch (Exception invalid) { return null; }
    }
}

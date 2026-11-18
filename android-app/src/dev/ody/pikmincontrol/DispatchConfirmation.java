package dev.ody.pikmincontrol;

/** Pure parser for the native reward-receipt evidence, independent of Android. */
final class DispatchConfirmation {
    private DispatchConfirmation() {}

    static boolean historyContainsSince(String history, String event, String taskId, long notBeforeMs) {
        if (history == null || event == null || taskId == null || taskId.isEmpty() || notBeforeMs <= 0) return false;
        for (String line : history.split("\n")) {
            String[] f = line.replace("\r", "").split("\t", -1);
            if (f.length < 6 || !event.equals(f[1]) || !taskId.equals(f[3])) continue;
            try { if (Long.parseLong(f[0]) >= notBeforeMs) return true; }
            catch (NumberFormatException ignored) { }
        }
        return false;
    }

    static final class Evidence {
        final boolean started, giftUnavailable, collected, inventoryConfirmed, faulted;
        final boolean windowCovered;
        boolean safelyRejected() { return windowCovered && giftUnavailable && !started && !faulted && !inventoryConfirmed && !collected; }
        Evidence(String history, String rewards, String taskId, long notBeforeMs) {
            boolean covered = false;
            if (history != null) for (String line : history.split("\n")) {
                String[] row = line.replace("\r", "").split("\t", -1);
                if (row.length < 6) continue;
                try { long at = Long.parseLong(row[0]); if (at > 0 && at <= notBeforeMs) covered = true; }
                catch (NumberFormatException ignored) { }
            }
            windowCovered = covered;
            started = historyContainsSince(history, "start-requested", taskId, notBeforeMs);
            giftUnavailable = historyContainsSince(history, "gift-pikmin-unavailable", taskId, notBeforeMs);
            collected = rewardCollectedSince(rewards, taskId, notBeforeMs);
            inventoryConfirmed = historyContainsSince(history, "inventory-confirmed", taskId, notBeforeMs)
                    || historyContainsSince(history, "inventory-confirmed-absent", taskId, notBeforeMs);
            faulted = historyContainsSince(history, "start-rpc-faulted", taskId, notBeforeMs);
        }
    }

    static boolean rewardCollectedSince(String history, String taskId, long notBeforeMs) {
        if (history == null || taskId == null || taskId.isEmpty() || notBeforeMs <= 0) return false;
        for (String line : history.split("\n")) {
            String[] fields = line.replace("\r", "").split("\t", -1);
            if (fields.length < 9 || !taskId.equals(fields[2])) continue;
            if (!"batch-confirmed".equals(fields[1]) && !"one-confirmed".equals(fields[1])) continue;
            try {
                if (Long.parseLong(fields[0]) >= notBeforeMs && Integer.parseInt(fields[4]) > 0
                        && "0".equals(fields[5])) return true;
            } catch (NumberFormatException ignored) { }
        }
        return false;
    }
}

package dev.ody.pikmincontrol;

/** Desired choice survives missing runtime; waiting is never an implicit OFF. */
final class CollectionPreference {
    static String summary(boolean desired, String waiting, String observation) {
        if (!desired) return "已關閉";
        if (waiting != null && !waiting.isEmpty()) return "已開啟 · " + waiting;
        return "已開啟 · " + (observation == null || observation.isEmpty() ? "持續等待遊戲資料" : observation);
    }
    static boolean needsWrite(String actual, String desired) { return !desired.equals(actual.trim()); }
}

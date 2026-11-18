package dev.ody.pikmincontrol;

/** Stable page ids: preserve saved System=4 and flower-route scroll slot=5. */
final class MainNavigation {
    static final int STEPS = 6;
    static final int SCROLL_SLOTS = 7;
    static boolean validPage(int page) { return page >= 0 && page <= 4 || page == STEPS; }
    static int scrollKey(int page, int expeditionTab) {
        return page == 3 && expeditionTab == 1 ? 5 : page;
    }
    static int[] restoreScrolls(int[] saved) {
        int[] result = new int[SCROLL_SLOTS];
        if (saved != null && (saved.length == 6 || saved.length == SCROLL_SLOTS))
            System.arraycopy(saved, 0, result, 0, saved.length);
        return result;
    }
}

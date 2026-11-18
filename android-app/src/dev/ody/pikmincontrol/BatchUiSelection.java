package dev.ody.pikmincontrol;

/** Saved UI choices are not a running service's queue. Seed=1, fruit=2, gift=4. */
final class BatchUiSelection {
    final int kinds, range, test;
    final boolean nearest;
    BatchUiSelection(int kinds, int range, boolean nearest, int test) {
        this.kinds = kinds >= 0 && kinds <= 7 ? kinds : 7;
        this.range = range >= 0 && range < 4 ? range : 0;
        this.nearest = nearest;
        this.test = test >= 0 && test < 3 ? test : 0;
    }
    String scanKey() { return kinds + ":" + range + ":" + nearest; }
    boolean acceptsScan(long requested, long current, String key) {
        return requested == current && scanKey().equals(key);
    }
    static int pages(int count) { return Math.max(1, (Math.max(0, count) + 49) / 50); }
    static int page(int requested, int count) { return Math.max(0, Math.min(requested, pages(count) - 1)); }
}

package dev.ody.pikmincontrol;

import java.util.HashSet;

public final class UiDraftTest {
    static int checks;
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        String raw = "-12.12345678, 47.23456789\r\n\r\ninvalid\r\n-12.12345678,47.23456789\n91,2\r0,0";
        FlowerFarmDraft draft = new FlowerFarmDraft(raw);
        check(draft.points.size() == 2 && draft.skipped == 2 && draft.duplicates == 1);
        check(draft.issues.contains("第 3 行") && draft.issues.contains("第 4 行") && draft.issues.contains("第 5 行"));
        check(draft.normalized.equals("-12.1234568, 47.2345679\n0.0000000, 0.0000000"));
        check(raw.contains("invalid")); // Preview keeps original text immutable.
        for (String invalid : new String[]{"NaN,0", "0,Infinity", "-91,0", "0,181", "1,2,3", "1", "foo,bar"})
            check(new FlowerFarmDraft(invalid).skipped == 1);
        check(new FlowerFarmDraft("-90,-180\n90,180\n1 2").points.size() == 3);
        check(new FlowerFarmDraft("\r\n\n \r").points.isEmpty());
        FlowerFarmDraft exact = new FlowerFarmDraft("48.34567890,-10.34567890");
        check(exact.points.get(0)[0] == 48.34567890); // Display rounding must not change route precision.
        check(new FlowerFarmDraft(exact.normalized).points.size() == 1);
        HashSet<String> keys = new HashSet<>();
        for (int kinds = 0; kinds < 8; kinds++) for (int range = 0; range < 4; range++)
            for (boolean nearest : new boolean[]{false, true}) for (int test = 0; test < 3; test++) {
                BatchUiSelection saved = new BatchUiSelection(kinds, range, nearest, test);
                check(saved.kinds == kinds && saved.range == range && saved.nearest == nearest && saved.test == test);
                check(saved.acceptsScan(2, 2, saved.scanKey()));
                check(!saved.acceptsScan(1, 2, saved.scanKey()));
                check(!saved.acceptsScan(2, 2, "different-filter"));
                keys.add(saved.scanKey());
            }
        check(keys.size() == 64); // Test size doesn't alter scan conditions.
        BatchUiSelection invalid = new BatchUiSelection(9, -1, false, 9);
        check(invalid.kinds == 7 && invalid.range == 0 && invalid.test == 0);
        for (int count : new int[]{0, 1, 49, 50, 51, 100, 101, 138, 512}) {
            HashSet<Integer> visited = new HashSet<>();
            for (int page = 0; page < BatchUiSelection.pages(count); page++) {
                int start = BatchUiSelection.page(page, count) * 50;
                for (int i = start; i < Math.min(start + 50, count); i++) check(visited.add(i));
            }
            check(visited.size() == count);
            check(BatchUiSelection.page(-1, count) == 0);
            check(BatchUiSelection.page(999, count) == BatchUiSelection.pages(count) - 1);
        }
        System.out.println("PASS: " + checks + " UI draft, scan-key and complete-pagination checks");
    }
}

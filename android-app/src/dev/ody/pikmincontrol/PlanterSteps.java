package dev.ody.pikmincontrol;

/** Same bit/token protocol as native planter_policy.h. null means missing file. */
final class PlanterSteps {
    static final int DEFAULT = 2 | 4 | 8;
    static final String[] TOKENS = {"100", "1000", "3000", "5000", "10000", "other"};
    static final String[] LABELS = {"100", "1000", "3000", "5000", "10000", "其他"};

    static int parse(String value) {
        if (value == null) return DEFAULT;
        if (value.length() > 128 || value.trim().isEmpty()) return 0;
        int mask = 0;
        for (String word : value.trim().split("\\s+")) {
            int bit = 0;
            for (int i = 0; i < TOKENS.length; i++) if (TOKENS[i].equals(word)) bit = 1 << i;
            if (bit == 0) return 0; // Includes explicit "none"; never default malformed data.
            mask |= bit;
        }
        return mask;
    }

    static String encode(int mask) {
        if (mask < 0 || mask > 63) throw new IllegalArgumentException("Unsupported seed selection");
        if (mask == 0) return "none";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < TOKENS.length; i++) if ((mask & (1 << i)) != 0) {
            if (result.length() > 0) result.append(' ');
            result.append(TOKENS[i]);
        }
        return result.toString();
    }
}

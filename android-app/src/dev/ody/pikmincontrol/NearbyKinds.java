package dev.ody.pikmincontrol;

/** Native nearby_policy.h protocol; never shared with batch or flower-farm choices. */
final class NearbyKinds {
    static final int DEFAULT = 3;
    static final String[] TOKENS = {"fruit", "seed", "gift"};
    static final String[] LABELS = {"水果", "花苗", "禮物盒"};

    static int parse(String value) {
        if (value == null) return DEFAULT;
        if (value.length() > 128 || value.trim().isEmpty()) return 0;
        int mask = 0;
        for (String word : value.trim().split("\\s+")) {
            int bit = 0;
            for (int i = 0; i < TOKENS.length; i++) if (TOKENS[i].equals(word)) bit = 1 << i;
            if (bit == 0) return 0;
            mask |= bit;
        }
        return mask;
    }
    static String encode(int mask) {
        if (mask < 0 || mask > 7) throw new IllegalArgumentException("Unsupported nearby selection");
        if (mask == 0) return "none";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < TOKENS.length; i++) if ((mask & (1 << i)) != 0) {
            if (result.length() > 0) result.append(' ');
            result.append(TOKENS[i]);
        }
        return result.toString();
    }
}

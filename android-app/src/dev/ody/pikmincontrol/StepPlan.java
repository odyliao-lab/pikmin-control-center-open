package dev.ody.pikmincontrol;

final class StepPlan {
    static final String PREPARED = "PREPARED";
    static final String CONFIRMED = "CONFIRMED";
    static final String UNCERTAIN = "UNCERTAIN";
    static final String FAILED = "FAILED";
    static final String REJECTED = "REJECTED";
    static final String BACKGROUND = "background";
    static final String MANUAL = "manual";
    static final String CATCH_UP = "catch-up";

    final String id;
    final String source;
    final String status;
    final long count;
    final long startMs;
    final long endMs;
    final int startOffsetSeconds;
    final int endOffsetSeconds;
    final String clientRecordId;
    final long clientRecordVersion;
    final String healthId;
    final String errorKind;

    StepPlan(String id, String source, String status, long count, long startMs, long endMs,
            int startOffsetSeconds, int endOffsetSeconds, String clientRecordId, long clientRecordVersion,
            String healthId, String errorKind) {
        this.id = id;
        this.source = source;
        this.status = status;
        this.count = count;
        this.startMs = startMs;
        this.endMs = endMs;
        this.startOffsetSeconds = startOffsetSeconds;
        this.endOffsetSeconds = endOffsetSeconds;
        this.clientRecordId = clientRecordId == null ? "" : clientRecordId;
        this.clientRecordVersion = clientRecordVersion;
        this.healthId = healthId == null ? "" : healthId;
        this.errorKind = errorKind == null ? "" : errorKind;
    }

    boolean occupies() {
        return PREPARED.equals(status) || CONFIRMED.equals(status) || UNCERTAIN.equals(status);
    }

    boolean pending() {
        return PREPARED.equals(status) || UNCERTAIN.equals(status);
    }

    boolean overlaps(long otherStart, long otherEnd) {
        return startMs < otherEnd && otherStart < endMs;
    }

    StepPlan withStatus(String next, String nextHealthId, String nextError) {
        return new StepPlan(id, source, next, count, startMs, endMs, startOffsetSeconds, endOffsetSeconds,
                clientRecordId, clientRecordVersion, nextHealthId, nextError);
    }

    String encode() {
        return id + "\t" + source + "\t" + status + "\t" + count + "\t" + startMs + "\t" + endMs + "\t"
                + startOffsetSeconds + "\t" + endOffsetSeconds + "\t" + clientRecordId + "\t"
                + clientRecordVersion + "\t" + healthId + "\t" + errorKind;
    }

    static StepPlan decode(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 12) throw new IllegalArgumentException("plan");
        return new StepPlan(p[0], p[1], p[2], Long.parseLong(p[3]), Long.parseLong(p[4]), Long.parseLong(p[5]),
                Integer.parseInt(p[6]), Integer.parseInt(p[7]), p[8], Long.parseLong(p[9]), p[10], p[11]);
    }
}

package dev.ody.pikmincontrol;

import java.util.Collections;
import java.util.List;

interface StepClock {
    long wallMillis();
    long elapsedMillis();
}

interface StepZone {
    String zoneId();
    int offsetSecondsAt(long epochMillis);
    long dayStartMillis(long epochMillis);
    long nextMidnightMillis(long epochMillis);
    int minuteOfDay(long epochMillis);
    String dayKey(long epochMillis);
    boolean localTimeExists(int year, int month, int day, int hour, int minute);
    List<Integer> offsetsAtLocal(int year, int month, int day, int hour, int minute);
    long toEpochMillis(int year, int month, int day, int hour, int minute, int offsetSeconds);
    int clockEpoch(long epochMillis);
}

interface StepRng {
    double nextUnit();
    String newId();
}

interface StepStore {
    Object transactionLock();
    StepLoadResult load();
    void save(String snapshot);
}

final class StepLoadResult {
    static final int MISSING = 0;
    static final int VALID = 1;
    static final int UNREADABLE = 2;
    final int kind;
    final String raw;
    final String error;
    private StepLoadResult(int kind, String raw, String error) {
        this.kind = kind;
        this.raw = raw == null ? "" : raw;
        this.error = error == null ? "" : error;
    }
    static StepLoadResult missing() { return new StepLoadResult(MISSING, "", ""); }
    static StepLoadResult valid(String raw) { return new StepLoadResult(VALID, raw, ""); }
    static StepLoadResult unreadable(String error) { return new StepLoadResult(UNREADABLE, "", error); }
    boolean isMissing() { return kind == MISSING; }
    boolean isValid() { return kind == VALID; }
    boolean isUnreadable() { return kind == UNREADABLE; }
}

final class StepStoreException extends RuntimeException {
    StepStoreException(String message) { super(message); }
    StepStoreException(String message, Throwable cause) { super(message, cause); }
}

interface StepHealthPort {
    boolean available();
    boolean canRead();
    boolean canWrite();
    boolean canReadInBackground();
    WriteResult write(StepPlan plan, long timeoutMs);
    LookupResult findByClientRecordId(String clientRecordId, long rangeStartMs, long rangeEndMs, long timeoutMs);
    AggregateResult aggregate(long rangeStartMs, long rangeEndMs, boolean thisAppOnly, long timeoutMs);
    PageResult readThisApp(long rangeStartMs, long rangeEndMs, long pageToken, int pageSize, long timeoutMs);

    final class WriteResult {
        final boolean timeout;
        final boolean failed;
        final String healthId;
        final String kind;
        final String message;
        WriteResult(boolean timeout, boolean failed, String healthId, String kind, String message) {
            this.timeout = timeout;
            this.failed = failed;
            this.healthId = healthId == null ? "" : healthId;
            this.kind = kind == null ? "" : kind;
            this.message = message == null ? "" : message;
        }
        static WriteResult ok(String healthId) { return new WriteResult(false, false, healthId, "ok", ""); }
        static WriteResult timeout() { return new WriteResult(true, false, "", "timeout", "寫入回覆逾時"); }
        static WriteResult fail(String kind, String message) { return new WriteResult(false, true, "", kind, message); }
    }

    final class LookupResult {
        final boolean timeout;
        final boolean failed;
        final boolean found;
        final boolean hasPayload;
        final String healthId;
        final String kind;
        final long count;
        final long startMs;
        final long endMs;
        final long clientRecordVersion;
        LookupResult(boolean timeout, boolean failed, boolean found, boolean hasPayload, String healthId, String kind,
                long count, long startMs, long endMs, long clientRecordVersion) {
            this.timeout = timeout;
            this.failed = failed;
            this.found = found;
            this.hasPayload = hasPayload;
            this.healthId = healthId == null ? "" : healthId;
            this.kind = kind == null ? "" : kind;
            this.count = count;
            this.startMs = startMs;
            this.endMs = endMs;
            this.clientRecordVersion = clientRecordVersion;
        }
        static LookupResult hit(String healthId) {
            return new LookupResult(false, false, true, false, healthId, "ok", 0L, 0L, 0L, 0L);
        }
        static LookupResult hit(StepPlan record) {
            return new LookupResult(false, false, true, true, record.healthId, "ok", record.count, record.startMs,
                    record.endMs, record.clientRecordVersion);
        }
        static LookupResult miss() { return new LookupResult(false, false, false, false, "", "ok", 0L, 0L, 0L, 0L); }
        static LookupResult timeout() { return new LookupResult(true, false, false, false, "", "timeout", 0L, 0L, 0L, 0L); }
        static LookupResult fail(String kind) { return new LookupResult(false, true, false, false, "", kind, 0L, 0L, 0L, 0L); }
        boolean payloadMatches(StepPlan pending) {
            if (pending == null || !hasPayload) return false;
            return pending.count == count && pending.startMs == startMs && pending.endMs == endMs
                    && pending.clientRecordVersion == clientRecordVersion;
        }
    }

    final class AggregateResult {
        final boolean ok;
        final Long total;
        final String kind;
        AggregateResult(boolean ok, Long total, String kind) {
            this.ok = ok;
            this.total = total;
            this.kind = kind == null ? "" : kind;
        }
        static AggregateResult fresh(long total) { return new AggregateResult(true, Long.valueOf(total), "ok"); }
        static AggregateResult unknown(String kind) { return new AggregateResult(false, null, kind); }
    }

    final class PageResult {
        final boolean ok;
        final boolean timeout;
        final List<StepPlan> records;
        final long nextPageToken;
        final String kind;
        PageResult(boolean ok, boolean timeout, List<StepPlan> records, long nextPageToken, String kind) {
            this.ok = ok;
            this.timeout = timeout;
            this.records = records == null ? Collections.<StepPlan>emptyList() : records;
            this.nextPageToken = nextPageToken;
            this.kind = kind == null ? "" : kind;
        }
    }
}

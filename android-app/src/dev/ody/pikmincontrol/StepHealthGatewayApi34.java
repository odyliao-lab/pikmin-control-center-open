package dev.ody.pikmincontrol;

import android.content.Context;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

final class StepHealthGatewayApi34 implements StepHealthPort {
    private static final int MAX_PAGES = 20;
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private final Context context;

    StepHealthGatewayApi34(Context context) {
        this.context = context.getApplicationContext();
    }

    public boolean available() {
        return StepHealthConnectProbe.frameworkPresent() && manager() != null;
    }

    public boolean canRead() { return StepPermissions.healthRead(context); }
    public boolean canWrite() { return StepPermissions.healthWrite(context); }
    public boolean canReadInBackground() { return StepPermissions.healthBackground(context); }

    public WriteResult write(StepPlan plan, long timeoutMs) {
        if (!available()) return WriteResult.fail("unavailable", "不可用");
        if (!canWrite()) return WriteResult.fail("permission", "沒有寫入權限");
        HealthConnectManager manager = manager();
        if (manager == null) return WriteResult.fail("unavailable", "不可用");
        List<Record> records = new ArrayList<Record>();
        int[] offsets = StepHealthConnectProbe.writeZoneOffsets(plan);
        records.add(StepHealthConnectProbe.sampleRecord(plan.clientRecordId, plan.startMs, plan.endMs, plan.count,
                offsets[0], offsets[1]));
        Bounded<InsertRecordsResponse> result = bounded(timeoutMs, new BoundCall<InsertRecordsResponse>() {
            public void enqueue(OutcomeReceiver<InsertRecordsResponse, HealthConnectException> callback) {
                manager.insertRecords(records, EXECUTOR, callback);
            }
        });
        if (result.timeout) return WriteResult.timeout();
        if (result.error != null) return WriteResult.fail(kind(result.error), "寫入失敗");
        String healthId = "";
        if (result.value != null && result.value.getRecords() != null && !result.value.getRecords().isEmpty()) {
            Metadata metadata = result.value.getRecords().get(0).getMetadata();
            if (metadata != null) healthId = metadata.getId();
        }
        return WriteResult.ok(healthId);
    }

    public LookupResult findByClientRecordId(String clientRecordId, long rangeStartMs, long rangeEndMs, long timeoutMs) {
        if (!available() || !canRead()) return LookupResult.fail("permission");
        long deadlineNs = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        long token = -1L;
        long previous = Long.MIN_VALUE;
        for (int page = 0; page < MAX_PAGES; page++) {
            long remaining = remainingMs(deadlineNs);
            if (remaining <= 0L) return LookupResult.timeout();
            if (stalledPage(page, previous, token)) return LookupResult.fail("repeat-token");
            previous = token;
            PageResult slice = readThisApp(rangeStartMs, rangeEndMs, token, StepEngine.PAGE_SIZE, remaining);
            if (slice.timeout) return LookupResult.timeout();
            if (!slice.ok) return LookupResult.fail(slice.kind);
            for (int i = 0; i < slice.records.size(); i++) {
                StepPlan record = slice.records.get(i);
                if (clientRecordId.equals(record.clientRecordId))
                    return LookupResult.hit(record);
            }
            if (slice.nextPageToken < 0L) return LookupResult.miss();
            token = slice.nextPageToken;
        }
        return LookupResult.fail("page-limit");
    }

    public AggregateResult aggregate(long rangeStartMs, long rangeEndMs, boolean thisAppOnly, long timeoutMs) {
        if (!available() || !canRead()) return AggregateResult.unknown("permission");
        HealthConnectManager manager = manager();
        if (manager == null) return AggregateResult.unknown("unavailable");
        final android.health.connect.AggregateRecordsRequest<Long> request = thisAppOnly
                ? StepHealthConnectProbe.thisAppAggregate(rangeStartMs, rangeEndMs, context.getPackageName())
                : StepHealthConnectProbe.allSourcesAggregate(rangeStartMs, rangeEndMs);
        Bounded<AggregateRecordsResponse<Long>> result = bounded(timeoutMs, new BoundCall<AggregateRecordsResponse<Long>>() {
            public void enqueue(OutcomeReceiver<AggregateRecordsResponse<Long>, HealthConnectException> callback) {
                manager.aggregate(request, EXECUTOR, callback);
            }
        });
        if (result.timeout) return AggregateResult.unknown("timeout");
        if (result.error != null) return AggregateResult.unknown(kind(result.error));
        Long total = result.value == null ? null : result.value.get(StepsRecord.STEPS_COUNT_TOTAL);
        if (total == null) return AggregateResult.fresh(0L);
        return AggregateResult.fresh(total.longValue());
    }

    public PageResult readThisApp(long rangeStartMs, long rangeEndMs, long pageToken, int pageSize, long timeoutMs) {
        if (!available() || !canRead()) return new PageResult(false, false, null, -1L, "permission");
        HealthConnectManager manager = manager();
        if (manager == null) return new PageResult(false, false, null, -1L, "unavailable");
        final android.health.connect.ReadRecordsRequestUsingFilters<StepsRecord> request =
                StepHealthConnectProbe.thisAppPage(rangeStartMs, rangeEndMs, context.getPackageName(), pageSize, pageToken);
        Bounded<ReadRecordsResponse<StepsRecord>> result = bounded(timeoutMs, new BoundCall<ReadRecordsResponse<StepsRecord>>() {
            public void enqueue(OutcomeReceiver<ReadRecordsResponse<StepsRecord>, HealthConnectException> callback) {
                manager.readRecords(request, EXECUTOR, callback);
            }
        });
        if (result.timeout) return new PageResult(false, true, null, -1L, "timeout");
        if (result.error != null) return new PageResult(false, false, null, -1L, kind(result.error));
        List<StepPlan> plans = new ArrayList<StepPlan>();
        List<StepsRecord> records = result.value.getRecords();
        for (int i = 0; i < records.size(); i++) {
            StepsRecord record = records.get(i);
            Metadata metadata = record.getMetadata();
            plans.add(new StepPlan(metadata.getId(), StepPlan.BACKGROUND, StepPlan.CONFIRMED, record.getCount(),
                    record.getStartTime().toEpochMilli(), record.getEndTime().toEpochMilli(),
                    record.getStartZoneOffset().getTotalSeconds(), record.getEndZoneOffset().getTotalSeconds(),
                    metadata.getClientRecordId() == null ? "" : metadata.getClientRecordId(),
                    metadata.getClientRecordVersion(), metadata.getId(), ""));
        }
        return new PageResult(true, false, plans, result.value.getNextPageToken(), "ok");
    }

    static long remainingMs(long deadlineNs) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
    }

    static boolean stalledPage(int page, long previousToken, long token) {
        return page > 0 && token == previousToken;
    }

    private HealthConnectManager manager() { return StepHealthConnectProbe.manager(context); }

    private static String kind(HealthConnectException error) {
        int code = StepHealthConnectProbe.classify(error);
        if (code == HealthConnectException.ERROR_SECURITY) return "permission";
        if (code == HealthConnectException.ERROR_RATE_LIMIT_EXCEEDED) return "rate-limit";
        if (code == HealthConnectException.ERROR_UNSUPPORTED_OPERATION) return "unsupported";
        if (code == HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS) return "sync";
        if (code == HealthConnectException.ERROR_REMOTE || code == HealthConnectException.ERROR_IO) return "io";
        return "error";
    }

    private interface BoundCall<T> {
        void enqueue(OutcomeReceiver<T, HealthConnectException> callback);
    }

    private static final class Bounded<T> {
        final T value;
        final HealthConnectException error;
        final boolean timeout;
        Bounded(T value, HealthConnectException error, boolean timeout) {
            this.value = value;
            this.error = error;
            this.timeout = timeout;
        }
    }

    private <T> Bounded<T> bounded(long timeoutMs, BoundCall<T> call) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<T> value = new AtomicReference<T>();
        final AtomicReference<HealthConnectException> error = new AtomicReference<HealthConnectException>();
        call.enqueue(new OutcomeReceiver<T, HealthConnectException>() {
            public void onResult(T result) { value.set(result); latch.countDown(); }
            public void onError(HealthConnectException exception) { error.set(exception); latch.countDown(); }
        });
        try {
            if (!latch.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)) return new Bounded<T>(null, null, true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Bounded<T>(null, null, true);
        }
        return new Bounded<T>(value.get(), error.get(), false);
    }
}

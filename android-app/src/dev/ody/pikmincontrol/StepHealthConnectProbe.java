package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.Intent;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissions;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.os.ext.SdkExtensions;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * S00 compile probe against SDK 35 {@code android.health.connect.HealthConnectManager}.
 * Constructs records and request objects only. Does not insert, update, or delete
 * Health Connect data. Loaded only from API-34 isolated callers.
 */
final class StepHealthConnectProbe {
    static final int MIN_SDK = 34;
    static final String READ_STEPS = HealthPermissions.READ_STEPS;
    static final String WRITE_STEPS = HealthPermissions.WRITE_STEPS;
    static final String READ_BACKGROUND = HealthPermissions.READ_HEALTH_DATA_IN_BACKGROUND;
    static final String MANAGE_ACTION = HealthConnectManager.ACTION_MANAGE_HEALTH_PERMISSIONS;
    static final String RATIONALE_CATEGORY = HealthConnectManager.CATEGORY_HEALTH_PERMISSIONS;
    static final String HEALTH_DATA_PACKAGE = "com.google.android.apps.healthdata";

    private StepHealthConnectProbe() { }

    static boolean frameworkPresent() {
        return Build.VERSION.SDK_INT >= MIN_SDK;
    }

    static int udeExtensionVersion() {
        if (Build.VERSION.SDK_INT < MIN_SDK) return 0;
        return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.UPSIDE_DOWN_CAKE);
    }

    static int[] writeZoneOffsets(StepPlan plan) {
        ZoneOffset[] offsets = recordOffsets(plan.startOffsetSeconds, plan.endOffsetSeconds);
        return new int[] { offsets[0].getTotalSeconds(), offsets[1].getTotalSeconds() };
    }

    static ZoneOffset[] recordOffsets(int startOffsetSeconds, int endOffsetSeconds) {
        return new ZoneOffset[] {
            ZoneOffset.ofTotalSeconds(startOffsetSeconds),
            ZoneOffset.ofTotalSeconds(endOffsetSeconds)
        };
    }

    static StepsRecord sampleRecord(String clientRecordId, long startMs, long endMs, long count,
            int startOffsetSeconds, int endOffsetSeconds) {
        ZoneOffset[] offsets = recordOffsets(startOffsetSeconds, endOffsetSeconds);
        Metadata metadata = new Metadata.Builder()
                .setClientRecordId(clientRecordId)
                .setClientRecordVersion(0L)
                .setRecordingMethod(Metadata.RECORDING_METHOD_MANUAL_ENTRY)
                .setDevice(new Device.Builder().setType(Device.DEVICE_TYPE_UNKNOWN).build())
                .build();
        return new StepsRecord.Builder(metadata, Instant.ofEpochMilli(startMs), Instant.ofEpochMilli(endMs), count)
                .setStartZoneOffset(offsets[0])
                .setEndZoneOffset(offsets[1])
                .build();
    }

    static TimeInstantRangeFilter between(long startMs, long endMs) {
        return new TimeInstantRangeFilter.Builder()
                .setStartTime(Instant.ofEpochMilli(startMs))
                .setEndTime(Instant.ofEpochMilli(endMs))
                .build();
    }

    static AggregateRecordsRequest<Long> allSourcesAggregate(long startMs, long endMs) {
        return new AggregateRecordsRequest.Builder<Long>(between(startMs, endMs))
                .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
                .build();
    }

    static AggregateRecordsRequest<Long> thisAppAggregate(long startMs, long endMs, String packageName) {
        return new AggregateRecordsRequest.Builder<Long>(between(startMs, endMs))
                .addAggregationType(StepsRecord.STEPS_COUNT_TOTAL)
                .addDataOriginsFilter(new DataOrigin.Builder().setPackageName(packageName).build())
                .build();
    }

    static ReadRecordsRequestUsingFilters<StepsRecord> thisAppPage(long startMs, long endMs, String packageName, int pageSize, long pageToken) {
        ReadRecordsRequestUsingFilters.Builder<StepsRecord> builder = new ReadRecordsRequestUsingFilters.Builder<StepsRecord>(StepsRecord.class)
                .setTimeRangeFilter(between(startMs, endMs))
                .setPageSize(pageSize)
                .addDataOrigins(new DataOrigin.Builder().setPackageName(packageName).build());
        if (pageToken >= 0L) builder.setPageToken(pageToken);
        return builder.build();
    }

    static Intent managePermissionsIntent(String packageName) {
        return new Intent(MANAGE_ACTION).putExtra(Intent.EXTRA_PACKAGE_NAME, packageName);
    }

    static Intent rationaleFilter() {
        return new Intent(Intent.ACTION_VIEW_PERMISSION_USAGE).addCategory(RATIONALE_CATEGORY);
    }

    static String[] requestedPermissions() {
        return new String[] { READ_STEPS, WRITE_STEPS, READ_BACKGROUND };
    }

    static int classify(HealthConnectException error) {
        return error == null ? HealthConnectException.ERROR_UNKNOWN : error.getErrorCode();
    }

    /** Compile-time wiring of callback signatures. Never invoked by production start-up. */
    static void bindSignatures(HealthConnectManager manager, Executor executor,
            OutcomeReceiver<InsertRecordsResponse, HealthConnectException> insert,
            OutcomeReceiver<ReadRecordsResponse<StepsRecord>, HealthConnectException> read) {
        if (manager == null || executor == null) return;
        List<Record> records = new ArrayList<Record>();
        records.add(sampleRecord("probe-never-sent", 1L, 2L, 1L, 0, 0));
        manager.insertRecords(records, executor, insert);
        manager.readRecords(thisAppPage(1L, 2L, "dev.ody.pikmincontrol", 32, -1L), executor, read);
        manager.aggregate(allSourcesAggregate(1L, 2L), executor,
                new OutcomeReceiver<android.health.connect.AggregateRecordsResponse<Long>, HealthConnectException>() {
                    public void onResult(android.health.connect.AggregateRecordsResponse<Long> result) { }
                    public void onError(HealthConnectException error) { }
                });
    }

    static HealthConnectManager manager(Context context) {
        if (context == null || Build.VERSION.SDK_INT < MIN_SDK) return null;
        return context.getSystemService(HealthConnectManager.class);
    }
}

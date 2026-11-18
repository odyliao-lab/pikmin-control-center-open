package dev.ody.pikmincontrol;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.records.StepsRecord;
import androidx.health.connect.client.records.metadata.Metadata;
import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.request.ReadRecordsRequest;
import androidx.health.connect.client.request.AggregateRequest;
import androidx.health.connect.client.response.ReadRecordsResponse;
import androidx.health.connect.client.response.InsertRecordsResponse;
import androidx.health.connect.client.aggregate.AggregationResult;
import androidx.health.connect.client.time.TimeRangeFilter;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.GlobalScope;
import kotlinx.coroutines.future.FutureKt;

/** Isolated API33 APK client. No root, fake grant, alternate origin, retry or new ledger. */
final class StepHealthGatewayApi33 implements StepHealthPort {
    private final Context context;
    private static volatile HealthConnectClient sharedClient;
    private final StepPaging.Tokens tokens=new StepPaging.Tokens();
    private static final ExecutorService PERMISSION_READER=Executors.newSingleThreadExecutor();
    private static volatile Set<String> permissions=Collections.emptySet();
    private static volatile long observedAt=-1;
    private static long requestedAt=-10000;
    private static boolean reading;
    private static long permissionEpoch;
    StepHealthGatewayApi33(Context context){this.context=context.getApplicationContext();}
    public boolean available(){try{return HealthConnectClient.getSdkStatus(context)==HealthConnectClient.SDK_AVAILABLE;}catch(RuntimeException e){return false;}}
    private static synchronized HealthConnectClient shared(Context context){
        if(sharedClient==null)sharedClient=HealthConnectClient.getOrCreate(context.getApplicationContext());
        return sharedClient;
    }
    private HealthConnectClient client(){return shared(context);}
    static synchronized boolean permission(Context context,String name){
        long now=SystemClock.elapsedRealtime();
        if(!reading && now-requestedAt>=5000){
            requestedAt=now; reading=true; Context app=context.getApplicationContext();
            final long epoch=permissionEpoch;
            PERMISSION_READER.execute(() -> {
                Set<String> result=Collections.emptySet(); long at=-1;
                try{result=realPermissions(shared(app),8000); at=SystemClock.elapsedRealtime();}catch(Exception e){/* unknown is never a grant */}
                synchronized(StepHealthGatewayApi33.class){if(epoch==permissionEpoch){permissions=Collections.unmodifiableSet(new HashSet<String>(result)); observedAt=at;} reading=false;}
            });
        }
        // Recent observation is only for presentation/preflight. Every insert rechecks real grants below.
        return observedAt>=0 && now>=observedAt && now-observedAt<=120000 && permissions.contains(name);
    }
    static synchronized void invalidatePermissions(){permissionEpoch++; observedAt=-1; requestedAt=-10000; permissions=Collections.emptySet();}
    static boolean backgroundAvailable(Context context){try{return shared(context).getFeatures().getFeatureStatus(1)==2;}catch(RuntimeException e){return false;}}
    static void requestPermissions(Activity activity){
        Set<String> requested=new HashSet<String>(); requested.add(StepPermissions.READ_STEPS); requested.add(StepPermissions.WRITE_STEPS);
        if(backgroundAvailable(activity)) requested.add(StepPermissions.READ_BACKGROUND);
        invalidatePermissions();
        activity.startActivityForResult(PermissionController.createRequestPermissionResultContract().createIntent(activity,requested),162);
    }
    public boolean canRead(){return permission(context,StepPermissions.READ_STEPS);}
    public boolean canWrite(){return permission(context,StepPermissions.WRITE_STEPS);}
    public boolean canReadInBackground(){return backgroundAvailable(context) && permission(context,StepPermissions.READ_BACKGROUND);}
    private interface Call {Object invoke(Continuation continuation);}
    @SuppressWarnings({"rawtypes","unchecked"}) private static Object await(Call call,long timeoutMs) throws Exception {
        CompletableFuture<Object> future=FutureKt.future(GlobalScope.INSTANCE,EmptyCoroutineContext.INSTANCE,CoroutineStart.DEFAULT,
            new Function2<CoroutineScope,Continuation<? super Object>,Object>() {
                public Object invoke(CoroutineScope scope,Continuation<? super Object> c){return call.invoke(c);}
            });
        try{return future.get(Math.max(1,Math.min(10000,timeoutMs)),TimeUnit.MILLISECONDS);}
        finally{if(!future.isDone()) future.cancel(true);}
    }
    @SuppressWarnings("unchecked") private static Set<String> realPermissions(HealthConnectClient client,long timeoutMs) throws Exception {
        Object response=await(c -> client.getPermissionController().getGrantedPermissions(c),timeoutMs);
        if(!(response instanceof Set)) throw new IllegalStateException("permission-response");
        return (Set<String>)response;
    }
    private static String kind(Exception error){
        Throwable t=error; while(t.getCause()!=null && t.getCause()!=t)t=t.getCause();
        if(t instanceof java.util.concurrent.TimeoutException || t instanceof InterruptedException)return "timeout";
        if(t instanceof SecurityException)return "permission";
        if(t instanceof java.io.IOException || t instanceof android.os.RemoteException)return "io";
        return "error"; // Unrecognized provider errors stop the batch; never turn them into missing data.
    }
    public WriteResult write(StepPlan plan,long timeoutMs){
        if(!available())return WriteResult.fail("unavailable","HC不可用");
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,Math.min(10000,timeoutMs)));
        boolean submitted=false;
        try{
            HealthConnectClient hc=client();
            Set<String> grants=realPermissions(hc,StepPaging.remainingMs(deadline));
            if(!grants.contains(StepPermissions.WRITE_STEPS) || !grants.contains(StepPermissions.READ_STEPS))return WriteResult.fail("permission","HC尚未授權");
            long remaining=StepPaging.remainingMs(deadline); if(remaining<=0)return WriteResult.fail("preflight-timeout","尚未送出");
            StepsRecord record=new StepsRecord(Instant.ofEpochMilli(plan.startMs),ZoneOffset.ofTotalSeconds(plan.startOffsetSeconds),
                Instant.ofEpochMilli(plan.endMs),ZoneOffset.ofTotalSeconds(plan.endOffsetSeconds),plan.count,Metadata.manualEntry(plan.clientRecordId,plan.clientRecordVersion));
            submitted=true;
            InsertRecordsResponse response=(InsertRecordsResponse)await(c -> hc.insertRecords(Collections.singletonList(record),c),remaining);
            if(response==null || response.getRecordIdsList().isEmpty())return WriteResult.timeout();
            return WriteResult.ok(response.getRecordIdsList().get(0));
        }catch(Exception e){
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();
            String reason=kind(e);
            if(submitted && !"permission".equals(reason))return WriteResult.timeout();
            return WriteResult.fail("timeout".equals(reason)?"preflight-timeout":reason,"HC尚未確認送出");
        }
    }
    public LookupResult findByClientRecordId(String id,long start,long end,long timeoutMs){
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(Math.max(1,Math.min(10000,timeoutMs)));
        long token=-1, previous=Long.MIN_VALUE;
        for(int page=0;page<20;page++){
            long left=StepPaging.remainingMs(deadline); if(left<=0)return LookupResult.timeout();
            if(StepPaging.stalledPage(page,previous,token))return LookupResult.fail("repeat-token"); previous=token;
            PageResult result=readThisApp(start,end,token,StepEngine.PAGE_SIZE,left);
            if(result.timeout)return LookupResult.timeout(); if(!result.ok)return LookupResult.fail(result.kind);
            for(StepPlan record:result.records)if(id.equals(record.clientRecordId))return LookupResult.hit(record);
            if(result.nextPageToken<0)return LookupResult.miss(); token=result.nextPageToken;
        }
        return LookupResult.fail("page-limit");
    }
    public AggregateResult aggregate(long start,long end,boolean own,long timeoutMs){
        if(!available())return AggregateResult.unknown("unavailable");
        try{
            HealthConnectClient hc=client();
            AggregateRequest request=new AggregateRequest(Collections.singleton(StepsRecord.COUNT_TOTAL),TimeRangeFilter.between(Instant.ofEpochMilli(start),Instant.ofEpochMilli(end)),
                own?Collections.singleton(new DataOrigin(context.getPackageName())):Collections.emptySet());
            AggregationResult response=(AggregationResult)await(c -> hc.aggregate(request,c),timeoutMs);
            if(response==null)return AggregateResult.unknown("malformed");
            Long total=response.get(StepsRecord.COUNT_TOTAL);
            return total==null?AggregateResult.fresh(0):AggregateResult.fresh(total.longValue());
        }catch(Exception e){return AggregateResult.unknown(kind(e));}
    }
    @SuppressWarnings("unchecked") public PageResult readThisApp(long start,long end,long pageToken,int pageSize,long timeoutMs){
        if(!available())return new PageResult(false,false,null,-1,"unavailable");
        try{
            String raw=pageToken<0?null:tokens.resolve(pageToken); if(pageToken>=0 && raw==null)return new PageResult(false,false,null,-1,"unknown-token");
            HealthConnectClient hc=client();
            ReadRecordsRequest<StepsRecord> request=new ReadRecordsRequest<StepsRecord>(JvmClassMappingKt.getKotlinClass(StepsRecord.class),TimeRangeFilter.between(Instant.ofEpochMilli(start),Instant.ofEpochMilli(end)),
                Collections.singleton(new DataOrigin(context.getPackageName())),true,Math.max(1,Math.min(1000,pageSize)),raw);
            ReadRecordsResponse<StepsRecord> response=(ReadRecordsResponse<StepsRecord>)await(c -> hc.readRecords(request,c),timeoutMs);
            if(response==null)return new PageResult(false,false,null,-1,"malformed");
            ArrayList<StepPlan> plans=new ArrayList<StepPlan>();
            for(StepsRecord record:response.getRecords()){
                Metadata m=record.getMetadata();
                if(!context.getPackageName().equals(m.getDataOrigin().getPackageName()) || record.getStartZoneOffset()==null || record.getEndZoneOffset()==null)return new PageResult(false,false,null,-1,"unattributed");
                plans.add(new StepPlan(m.getId(),StepPlan.BACKGROUND,StepPlan.CONFIRMED,record.getCount(),record.getStartTime().toEpochMilli(),record.getEndTime().toEpochMilli(),
                    record.getStartZoneOffset().getTotalSeconds(),record.getEndZoneOffset().getTotalSeconds(),m.getClientRecordId()==null?"":m.getClientRecordId(),m.getClientRecordVersion(),m.getId(),""));
            }
            return new PageResult(true,false,plans,tokens.remember(StepPaging.sdkNextToken(response.getPageToken())),"ok");
        }catch(Exception e){String reason=kind(e);return new PageResult(false,"timeout".equals(reason),null,-1,reason);}
    }
}

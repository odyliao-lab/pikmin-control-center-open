package dev.ody.pikmincontrol;

import java.util.ArrayList;
import java.util.List;

/** Actual callback samples only; no interpolation or nearest-node adoption.
 * Android integration must supply Location.isMock/from GPS and validate the
 * full original native/owned-provider snapshot before draining. HOST tests
 * exercise retention only, not callback provenance or Android permissions.
 */
final class EmulatorRouteFixBuffer {
    static final int LIMIT=8;
    static final class Fix {
        final double lat,lng; final long at;
        Fix(double lat,double lng,long at){this.lat=lat;this.lng=lng;this.at=at;}
    }
    private final List<Fix> pending=new ArrayList<>();
    private long lastAt=-1, adoptedAt;
    private Fix last;
    private Fix adopted;
    private String failure="";
    EmulatorRouteFixBuffer(long baselineAt){
        if(baselineAt<0)throw new IllegalArgumentException("baseline clock");
        adoptedAt=baselineAt;
    }
    EmulatorRouteFixBuffer(long baselineAt,double lat,double lng){
        this(baselineAt);
        new HarvestRoute.Point(lat,lng); // Same legal-coordinate check as progress.
        adopted=new Fix(lat,lng,baselineAt);
    }
    synchronized void offer(double lat,double lng,long fixAt,long receiptAt,boolean gps,boolean mock){
        if(!failure.isEmpty())return;
        if(!gps||!mock||!Double.isFinite(lat)||!Double.isFinite(lng)||Math.abs(lat)>90||Math.abs(lng)>180||fixAt<0||receiptAt<fixAt){reject("invalid-callback");return;}
        if(receiptAt-fixAt>HarvestRouteProgress.SAMPLE_AGE_MS){reject("stale-callback");return;}
        Fix fix=new Fix(lat,lng,fixAt);
        if(fixAt<lastAt){reject("callback-clock-regression");return;}
        if(fixAt==lastAt){
            if(HarvestRoute.distance(new HarvestRoute.Point(last.lat,last.lng),new HarvestRoute.Point(lat,lng))>0.5)reject("conflicting-callback");
            return;
        }
        lastAt=fixAt;last=fix;
        if(fixAt<=adoptedAt)return; // Already observed at original WALK baseline.
        if(pending.size()==LIMIT){reject("callback-overflow");return;}
        pending.add(fix);
    }
    synchronized List<Fix> drain(double anchorLat,double anchorLng,long anchorAt,long now,boolean ownedMockFresh){
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(!ownedMockFresh||!Double.isFinite(anchorLat)||!Double.isFinite(anchorLng)||Math.abs(anchorLat)>90||Math.abs(anchorLng)>180||anchorAt<adoptedAt||now<anchorAt||now-anchorAt>HarvestRouteProgress.SAMPLE_AGE_MS)
            throw new IllegalStateException("provider-anchor-unready");
        if(anchorAt==adoptedAt&&adopted!=null){
            if(HarvestRoute.distance(new HarvestRoute.Point(adopted.lat,adopted.lng),new HarvestRoute.Point(anchorLat,anchorLng))>0.5)
                throw new IllegalStateException("callback-anchor-mismatch");
            return new ArrayList<>(); // Repeated actual anchor never advances.
        }
        int count=0;Fix tail=null;
        for(Fix fix:pending){if(fix.at>anchorAt)break;count++;tail=fix;}
        if(tail==null||tail.at!=anchorAt||HarvestRoute.distance(new HarvestRoute.Point(tail.lat,tail.lng),new HarvestRoute.Point(anchorLat,anchorLng))>0.5)
            throw new IllegalStateException("callback-anchor-mismatch");
        // No old samples accepted merely because the newest anchor is fresh.
        for(int i=0;i<count;i++)if(now-pending.get(i).at>HarvestRouteProgress.SAMPLE_AGE_MS)
            throw new IllegalStateException("callback-batch-stale");
        List<Fix> result=new ArrayList<>(pending.subList(0,count));
        pending.subList(0,count).clear();adoptedAt=anchorAt;adopted=tail;
        return result; // Future callback races remain queued, never fabricated.
    }
    private void reject(String code){failure=code;}
}

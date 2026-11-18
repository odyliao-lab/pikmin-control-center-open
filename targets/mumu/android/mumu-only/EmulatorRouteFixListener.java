package dev.ody.pikmincontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.SystemClock;

/** MuMu-only actual GPS callback source. Never enables a provider or moves GPS.
 * Caller must retain the original native/session/stock gates BEFORE adopt().
 * Registration failure is a stop, not permission to synthesize missed fixes.
 */
final class EmulatorRouteFixListener implements AutoCloseable {
    private final LocationManager manager;
    private final HandlerThread thread=new HandlerThread("pcc-route-fixes");
    private final EmulatorRouteFixBuffer buffer;
    private boolean closed;
    private final LocationListener listener=new LocationListener(){
        @Override public void onLocationChanged(Location location){
            synchronized(EmulatorRouteFixListener.this){
                if(closed)return;
                buffer.offer(location.getLatitude(),location.getLongitude(),location.getElapsedRealtimeNanos()/1000000,
                    SystemClock.elapsedRealtime(),LocationManager.GPS_PROVIDER.equals(location.getProvider()),location.isFromMockProvider());
            }
        }
        @Override public void onProviderDisabled(String provider){
            synchronized(EmulatorRouteFixListener.this){if(!closed)buffer.offer(0,0,0,0,false,false);}
        }
        @Override public void onProviderEnabled(String provider){}
        @Override public void onStatusChanged(String provider,int status,Bundle extras){}
    };
    EmulatorRouteFixListener(Context context,JoystickReadiness baseline,long now){
        if(baseline==null||!baseline.mock||!baseline.own||!baseline.fresh(now)||now-baseline.fixMs>HarvestRouteProgress.SAMPLE_AGE_MS)
            throw new IllegalStateException("callback-baseline-unready");
        if(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("callback-location-permission-required");
        manager=context.getSystemService(LocationManager.class);
        if(manager==null)throw new IllegalStateException("callback-provider-unavailable");
        buffer=new EmulatorRouteFixBuffer(baseline.fixMs,baseline.latitude,baseline.longitude);
        thread.start();
        try{manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,0,0,listener,thread.getLooper());}
        catch(RuntimeException failed){thread.quitSafely();throw failed;}
    }
    /** Only callbacks matching the actual same-provider root anchor are adopted.
     * No dump coordinate is inserted as a replacement for a missed callback.
     */
    synchronized boolean adopt(HarvestRouteProgress progress,JoystickReadiness anchor,long now){
        if(closed)throw new IllegalStateException("callback-listener-closed");
        if(anchor==null)throw new IllegalStateException("callback-anchor-unready");
        for(EmulatorRouteFixBuffer.Fix fix:buffer.drain(anchor.latitude,anchor.longitude,anchor.fixMs,now,
            anchor.mock&&anchor.own&&anchor.fresh(now))){
            progress.observeCallback(fix.lat,fix.lng,fix.at,now);
            if(!progress.failure().isEmpty())throw new IllegalStateException(progress.failure());
        }
        return progress.finished();
    }
    @Override public synchronized void close(){
        if(closed)return;
        closed=true;
        try{manager.removeUpdates(listener);}finally{thread.quitSafely();}
    }
}

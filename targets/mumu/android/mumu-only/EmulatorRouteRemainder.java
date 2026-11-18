package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** Private ordered geometry only, never a saved location/execution authority.
 * The artificial paused anchor is NOT counted as an original waypoint. */
final class EmulatorRouteRemainder {
    final HarvestRoute original;
    final int reached;
    final double latitude,longitude;
    final long fixMs;
    private EmulatorRouteRemainder(HarvestRoute route,int reached,double lat,double lng,long fixMs){
        if(route==null||reached<1||reached>=route.points.size()||fixMs<0)throw new IllegalArgumentException("Unfinished ordered route required");
        new HarvestRoute.Point(lat,lng);original=route;this.reached=reached;latitude=lat;longitude=lng;this.fixMs=fixMs;
    }
    static EmulatorRouteRemainder capture(HarvestRoute route,HarvestRouteProgress progress,JoystickReadiness held,long elapsed,boolean paused){
        if(!paused||route==null||progress==null||!progress.failure().isEmpty())throw new IllegalArgumentException("Confirmed pause and valid progress required");
        // Adopt the actual post-PAUSE provider fix through the unchanged speed,
        // gap and ordered reach checks. No nearest-node or backfilled progress.
        progress.observeProvider(held,elapsed);
        // Keep the original moving-route checks. Distinguish their rejection
        // from a finished route without exposing private coordinates.
        if(!progress.failure().isEmpty())throw new IllegalArgumentException("Paused progress rejected: "+progress.failure()+"; "+progress.detail());
        if(!progress.fresh(elapsed))throw new IllegalArgumentException("Paused progress not fresh");
        if(progress.finished())throw new IllegalArgumentException("Paused progress already finished");
        return new EmulatorRouteRemainder(route,progress.reached(),held.latitude,held.longitude,held.fixMs);
    }
    static EmulatorRouteRemainder captureNext(EmulatorRouteRemainder prior,HarvestRoute segment,HarvestRouteProgress progress,JoystickReadiness held,long elapsed,boolean paused){
        if(prior==null||segment==null||segment.points.size()!=prior.original.points.size()-prior.reached+1||
           HarvestRoute.distance(segment.points.get(0),new HarvestRoute.Point(prior.latitude,prior.longitude))>2)
            throw new IllegalArgumentException("Exact prior ordered segment required");
        for(int i=1;i<segment.points.size();i++){
            HarvestRoute.Point a=segment.points.get(i),b=prior.original.points.get(prior.reached+i-1);
            if(Double.compare(a.lat,b.lat)!=0||Double.compare(a.lng,b.lng)!=0)throw new IllegalArgumentException("Remaining route changed");
        }
        EmulatorRouteRemainder part=capture(segment,progress,held,elapsed,paused);
        return new EmulatorRouteRemainder(prior.original,prior.originalReached(part.reached),part.latitude,part.longitude,part.fixMs);
    }
    HarvestRoute resume(JoystickReadiness current,long elapsed,boolean paused){
        if(!paused||current==null||!current.fresh(elapsed)||elapsed-current.fixMs>HarvestRouteProgress.SAMPLE_AGE_MS||elapsed<fixMs||current.fixMs<fixMs||
           HarvestRoute.distance(new HarvestRoute.Point(latitude,longitude),new HarvestRoute.Point(current.latitude,current.longitude))>2)
            throw new IllegalArgumentException("Actual held provider changed; no synthetic resume");
        StringBuilder route=new StringBuilder();route.append(current.latitude).append(',').append(current.longitude).append('\n');
        for(int i=reached;i<original.points.size();i++){HarvestRoute.Point p=original.points.get(i);route.append(p.lat).append(',').append(p.lng).append('\n');}
        return HarvestRoute.coordinates(route.toString());
    }
    int originalReached(int segmentReached){
        if(segmentReached<0||segmentReached>original.points.size()-reached+1)throw new IllegalArgumentException("Segment progress out of range");
        return reached+Math.max(0,segmentReached-1);
    }
    String encode(){
        StringBuilder route=new StringBuilder();for(HarvestRoute.Point p:original.points)route.append(p.lat).append(',').append(p.lng).append('\n');
        String body="v1\t"+Base64.getEncoder().encodeToString(route.toString().getBytes(StandardCharsets.UTF_8))+"\t"+reached+"\t"+latitude+"\t"+longitude+"\t"+fixMs+"\tend\n";
        return body+"sha256\t"+digest(body)+"\n";
    }
    static EmulatorRouteRemainder decode(String raw){
        if(raw==null||raw.length()>HarvestRoute.MAX_CHARS*2||raw.indexOf('\r')>=0)throw new IllegalArgumentException("Remainder framing");
        String[] rows=raw.split("\n",-1);if(rows.length!=3||!rows[2].isEmpty()||!rows[1].equals("sha256\t"+digest(rows[0]+"\n")))throw new IllegalArgumentException("Remainder checksum");
        String[] f=rows[0].split("\t",-1);if(f.length!=7||!f[0].equals("v1")||!f[6].equals("end"))throw new IllegalArgumentException("Remainder schema");
        HarvestRoute route=HarvestRoute.coordinates(new String(Base64.getDecoder().decode(f[1]),StandardCharsets.UTF_8));
        EmulatorRouteRemainder s=new EmulatorRouteRemainder(route,Integer.parseInt(f[2]),Double.parseDouble(f[3]),Double.parseDouble(f[4]),Long.parseLong(f[5]));
        if(!raw.equals(s.encode()))throw new IllegalArgumentException("Noncanonical remainder");return s;
    }
    private static String digest(String body){
        try{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}

package dev.ody.pikmincontrol;

/** Eligibility for a PASSIVE new frame only. The captured-invalid frame never
 * passes require(), supplies progress, or authorizes a gameplay effect. The
 * original same-STATUS one-use permit and two-second I/O budget still apply. */
final class EmulatorDailyCapturedExpiry {
    static boolean eligible(boolean daily, boolean refreshed, RouteObservation o,
                            String pid,String account,long requested,long wall,long elapsed) {
        if(!daily||refreshed||o==null||requested<=0||pid==null||account==null||
           !pid.matches("[1-9][0-9]{0,8}")||!account.matches("[a-f0-9]{64}")||!account.equals(o.account)||
           !pid.equals(o.snapshotBefore.trim())||!pid.equals(o.snapshotAfter.trim())||
           (o.location.valid&&o.planting.valid))return false;
        try {
            FarmLocation f=o.location;PlantingState p=o.planting;
            String[] n=line(o.nativeRaw,12),plant=line(o.plantingRaw,6);
            // Existing ten-second diagnostic protocol check is NOT the two-
            // second gameplay gate: it only identifies a complete old record.
            if(!new NearbyGuard(o.nativeRaw,pid,wall).supported||!pid.equals(f.pid)||
               !pid.equals(n[2])||num(n[1])!=f.at||num(n[4])!=f.epoch||num(n[5])!=f.samples||
               num(n[10])!=f.rawAt||f.samples>3||
               !FarmLocation.point(f.rawLat,f.rawLng)||!FarmLocation.point(f.lat,f.lng)||
               // A complete old raw fix may explain this captured-invalid
               // frame, but can NEVER pass FarmLocation/require/progress.
               // This diagnostic envelope matches NearbyGuard's existing
               // ten seconds. Only ONE NEW full STATUS/frame read is eligible;
               // that new frame still has the original 2s/5s/8s gameplay gates.
               f.rawAt<=0||elapsed<f.rawAt||elapsed-f.rawAt>10000||
               f.provider==null||!f.provider.own||!f.provider.fresh(elapsed)||
               elapsed<f.provider.fixMs||elapsed-f.provider.fixMs>HarvestRouteProgress.SAMPLE_AGE_MS)
                return false;
            // Reject OFF/unknown/unowned/pre-start/future/malformed records.
            // No clock substitution or reparsing at an invented earlier time.
            if(num(plant[0])!=p.at||p.at<=requested||p.at>wall||
               !"on".equals(plant[1])||!"1".equals(plant[2])||!"1".equals(plant[3])||
               !"active-confirmed".equals(plant[4])||!"active".equals(plant[5])||
               !p.planting||!p.owned||!"on".equals(p.mode)||!"active-confirmed".equals(p.action))
                return false;
            return wall-f.at>2000||wall-p.at>2000||elapsed-f.rawAt>5000;
        }catch(RuntimeException invalid){return false;}
    }
    private static long num(String s){
        long v=Long.parseLong(s);
        if(v<0||!Long.toString(v).equals(s))throw new IllegalArgumentException("Canonical number required");
        return v;
    }
    private static String[] line(String raw,int count){
        if(raw==null||raw.length()>2048)throw new IllegalArgumentException("Complete bounded record required");
        String row=raw.endsWith("\n")?raw.substring(0,raw.length()-1):raw;
        for(int i=0;i<row.length();i++){
            char c=row.charAt(i);if(c!='\t'&&(c<32||c>126))throw new IllegalArgumentException("Record framing");
        }
        String[] f=row.split("\t",-1);
        if(f.length!=count)throw new IllegalArgumentException("Complete record columns required");
        return f;
    }
}

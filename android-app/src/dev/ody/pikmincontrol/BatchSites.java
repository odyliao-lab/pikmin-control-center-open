package dev.ody.pikmincontrol;

import java.util.*;

/** Stable 100 m anchor groups, never transitive chains or dispatch permission. */
final class BatchSites {
    static final double AREA_METRES = 100.0;
    static double supportedRadius(String raw, String before, String after, long now) {
        try {
            String[] f=raw.trim().split("\t",-1);
            if(f.length!=6 || !f[0].equals("v1") || !before.matches("[1-9][0-9]*") || !before.equals(after)
                    || !f[2].equals(before) || !f[3].equals("100") || !f[4].equals("2000") || !f[5].equals("end"))return 4;
            long at=Long.parseLong(f[1]);return at>0 && now>=at && now-at<=12000 ? AREA_METRES : 4;
        }catch(RuntimeException invalid){return 4;}
    }
    static boolean valid(DispatchProbeGateway.Candidate c) {
        return c != null && Double.isFinite(c.latitude) && Double.isFinite(c.longitude)
                && Math.abs(c.latitude) <= 90 && Math.abs(c.longitude) <= 180;
    }
    static boolean harvest(DispatchProbeGateway.Candidate c) {
        return valid(c) && ("seed".equals(c.kind) || "fruit".equals(c.kind));
    }
    static double metres(DispatchProbeGateway.Candidate a, DispatchProbeGateway.Candidate b) {
        if (!valid(a) || !valid(b)) return Double.POSITIVE_INFINITY;
        double dlat=Math.toRadians(b.latitude-a.latitude), dlon=Math.toRadians(b.longitude-a.longitude);
        double h=Math.pow(Math.sin(dlat/2),2)+Math.cos(Math.toRadians(a.latitude))
                *Math.cos(Math.toRadians(b.latitude))*Math.pow(Math.sin(dlon/2),2);
        return 6371000.0*2*Math.asin(Math.sqrt(Math.max(0,Math.min(1,h))));
    }
    static boolean nearby(DispatchProbeGateway.Candidate anchor, DispatchProbeGateway.Candidate next) {
        return harvest(anchor) && harvest(next) && metres(anchor,next) <= AREA_METRES;
    }
    static boolean same(DispatchProbeGateway.Candidate a, DispatchProbeGateway.Candidate b) {
        return a != null && b != null && Double.isFinite(a.latitude) && Double.isFinite(a.longitude)
                && a.latitude == b.latitude && a.longitude == b.longitude;
    }
    static List<DispatchProbeGateway.Candidate> group(List<DispatchProbeGateway.Candidate> source) {
        List<DispatchProbeGateway.Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DispatchProbeGateway.Candidate anchor : source) {
            if (seen.contains(anchor.taskId)) continue;
            result.add(anchor); seen.add(anchor.taskId);
            for (DispatchProbeGateway.Candidate candidate : source)
                if (!seen.contains(candidate.taskId) && nearby(anchor, candidate)) {
                    result.add(candidate); seen.add(candidate.taskId);
                }
        }
        return result;
    }
}

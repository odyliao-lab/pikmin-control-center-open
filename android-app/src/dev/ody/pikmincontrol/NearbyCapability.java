package dev.ody.pikmincontrol;

/** A stale heartbeat can prove loaded capability, never current dispatch readiness. */
final class NearbyCapability {
    /** Engine identity is independent of a GPS fix. Old cores retain their stricter v2 fallback. */
    static boolean verifiedEngine(String capability,String gate,String pid,String stat,String hz,String afterPid,long wall,long boot) {
        if(capability==null)return false;
        if(capability.trim().isEmpty())return verified(gate,pid,stat,hz,afterPid,wall,boot);
        try {
            String[] row=capability.trim().split("\t",-1);
            if(row.length!=6||!"v1".equals(row[0])||!"nearby-v2-time-only".equals(row[4])||!"300000".equals(row[5]))return false;
            pid=pid.trim();
            if(!pid.matches("[1-9][0-9]*")||!pid.equals(afterPid.trim())||!pid.equals(row[2]))return false;
            long at=Long.parseLong(row[1]),elapsed=Long.parseLong(row[3]);
            stat=stat.trim();if(!stat.startsWith(pid+" ("))return false;
            String[] fields=stat.substring(stat.lastIndexOf(')')+2).split("\\s+");
            long ticks=Long.parseLong(fields[19]),frequency=Long.parseLong(hz.trim());
            if(ticks<=0||frequency<=0||frequency>1000000)return false;
            long start=Math.multiplyExact(ticks,1000L)/frequency;
            return start>0&&start<=boot&&at>0&&at<=wall&&at>=wall-(boot-start)&&elapsed>=start&&elapsed<=boot;
        }catch(RuntimeException invalid){return false;}
    }
    static boolean verified(String raw, String pid, String stat, String hz, String afterPid,
                            long wall, long boot) {
        try {
            pid = pid.trim();
            if (!pid.matches("[1-9][0-9]*") || !pid.equals(afterPid.trim())) return false;
            String[] row = raw.trim().split("\t", -1);
            if (row.length != 12) return false;
            long at = Long.parseLong(row[1]);
            // Reuse the strict existing protocol/state/limit checks at its own
            // timestamp; this does NOT alter NearbyGuard's freshness contract.
            NearbyGuard guard = new NearbyGuard(raw, pid, at);
            if (!guard.supported || !guard.timeOnly || at > wall) return false;
            stat = stat.trim();
            if (!stat.startsWith(pid + " (")) return false;
            String[] fields = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+");
            long ticks = Long.parseLong(fields[19]); // /proc/PID/stat field22
            long frequency = Long.parseLong(hz.trim());
            if (ticks <= 0 || frequency <= 0 || frequency > 1000000) return false;
            long start = Math.multiplyExact(ticks, 1000L) / frequency;
            long rawAt = Long.parseLong(row[10]);
            // Both clocks must place this evidence inside the CURRENT process
            // lifetime. Reject PID reuse across process restarts and reboots.
            return start > 0 && start <= boot && at >= wall - (boot - start)
                    && rawAt >= start && rawAt <= boot;
        } catch (RuntimeException invalid) { return false; }
    }
    private NearbyCapability() { }
}

package dev.ody.pikmincontrol;

/** A replacement process is healthy only when all fresh native reports agree on its PID. */
final class GamePidRecoveryProof {
    private GamePidRecoveryProof() {}
    static boolean terminal(CorePair pair,String raw,String pid,long now) {
        if(pair==null)return false;
        CoreCapabilities caps=CoreCapabilities.parse(ReadinessSample.Read.ok(raw),pid,pair.version,pair.code,now);
        // Not stale heartbeat, missing map, logout, or incompatible game. Only
        // explicit exhausted bootstrap failures permit same-PID recovery.
        return caps.ok() && ("lib_timeout".equals(caps.stage) || "symbols_missing".equals(caps.stage)
            // Late bootstrap may miss RpcManager construction/login entirely.
            // Actual map callbacks after hook installation, but no login ever
            // captured, prove a missing session bridge rather than a login UI.
            // A normal login finishes within policy's sustained 45-second grace.
            || ("hooks_called".equals(caps.stage) && caps.domainReady()
                && "unknown".equals(caps.identity) && caps.lastLoginMs==0 && caps.lastMapMs>0));
    }
    static boolean healthy(CorePair pair,String[] sections,long now) {
        if(pair==null || sections==null || sections.length!=6)return false;
        String before=sections[1].trim(),after=sections[5].trim();
        return !before.isEmpty()
                && !SessionIdentity.parse(sections[2],before,after,now).isEmpty()
                && pair.runtime(sections[3],before,after,now)
                && pair.gpsRuntime(sections[4],before,after,now);
    }
}

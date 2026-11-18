package dev.ody.pikmincontrol;

/** Explicit daily candidate protocol target, not a version inferred from a
 * packet. Context callers separately prove installed pair/APK bytes and live
 * identity before/after reads. Existing 209/core23 cannot enable this daily. */
final class EmulatorDailyCore {
    static final String VERSION="0.20.3";
    static final int CODE=900178;
    static CoreCapabilities capabilities(String raw,EmulatorDailyRunner.Binding held,long now) {
        if(held==null)throw new IllegalArgumentException("Held daily binding required");
        CoreCapabilities c=CoreCapabilities.parse(ReadinessSample.Read.ok(raw),Integer.toString(held.pid),VERSION,CODE,now);
        if(!c.ok()||!c.identityReady()||!c.domainReady()||c.sessionEpoch!=held.sessionEpoch)
            throw new IllegalArgumentException("Exact daily candidate capabilities required; never adopt packet version");
        return c;
    }
}

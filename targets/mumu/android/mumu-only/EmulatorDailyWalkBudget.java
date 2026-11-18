package dev.ody.pikmincontrol;

/** MuMu daily route only. Distance/speed estimates omit discrete waypoint
 * transitions. This is a conservative allowance, not measured provider timing.
 * The original live work's 15/25-minute/global window is checked every loop;
 * no retained claim, task deadline, GPS speed or freshness is changed. */
final class EmulatorDailyWalkBudget {
    static final long MAX_SECONDS=25*60;
    static long seconds(long nominalSeconds,int points){
        if(nominalSeconds<=0||points<2||points>2000)
            throw new IllegalArgumentException("Bounded existing daily geometry required");
        long allowance=points+90L;
        // Saturate before addition, including adversarial/oversized estimates.
        return nominalSeconds>=MAX_SECONDS-allowance?MAX_SECONDS:nominalSeconds+allowance;
    }
}

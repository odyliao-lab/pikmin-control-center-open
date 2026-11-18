package dev.ody.pikmincontrol;
import android.content.Context;
/** Exact native reward observed since this work began, NOT our dispatch.
 * Existing no-admission, account/session and immutable proof guards remain. */
final class EmulatorSavedBatchObservation {
    static boolean observe(Context c,EmulatorDailyBatchWork work,DispatchProbeGateway.Candidate candidate)throws Exception{
        if(work==null)return false;
        if(!ReturnRewardModuleGateway.collectedSince(candidate.taskId,work.created))return false;
        work.observeCollected(c,candidate,work.created);
        return true;
    }
}

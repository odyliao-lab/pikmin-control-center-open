package dev.ody.pikmincontrol;
/** Read-only preparation before the durable service-start intent. The created
 * time is the actual preparation event, not a later post-fsync replacement. */
final class EmulatorSavedBatchPreparation {
    static EmulatorDailyBatchInventory prepare(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,
            EmulatorDailyBatchScan.Snapshot scan,EmulatorDevelopmentBatch.Window window,long now){
        scan=EmulatorSavedBatchOrder.balanced(scan);
        if(window==null)return new EmulatorDailyBatchInventory(held,key,epoch,deadline,now,scan);
        if(!window.key.equals(key)||deadline!=window.deadline)throw new IllegalArgumentException("Exact prepared window required");
        return EmulatorDailyBatchInventory.development(held,window,epoch,now,scan);
    }
}

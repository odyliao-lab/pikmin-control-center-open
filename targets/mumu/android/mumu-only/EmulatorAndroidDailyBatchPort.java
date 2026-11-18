package dev.ody.pikmincontrol;

import android.content.Context;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/** MuMu-only adapter, not scheduling or readiness authority. Complete inventory
 * is persisted before passing its located three-type queue to the owned service.
 * Missing service / empty inventory / unlocated-only NEVER imply completion. */
final class EmulatorAndroidDailyBatchPort implements EmulatorAndroidDailyPort.BatchPort {
    private final Context context;
    EmulatorAndroidDailyBatchPort(Context c){
        if(c==null||!"dev.ody.pikmincontrol.mumu".equals(c.getPackageName()))throw new IllegalArgumentException("Isolated MuMu caller required");context=c;
    }
    private Path root()throws Exception{return EmulatorAndroidDailyStore.files(context).resolve("daily-batch-inventory");}
    public void start(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline)throws Exception {
        EmulatorAndroidDailyPort.requireKey(held,key,true);
        EmulatorDailyBatchScan.Snapshot scan=EmulatorDailyBatchScan.read(context,held);
        EmulatorDailyBatchInventory inventory=new EmulatorDailyBatchInventory(held,key,epoch,deadline,System.currentTimeMillis(),scan);
        startInventory(held,key,epoch,deadline,inventory,null);
    }
    void startDevelopment(EmulatorDailyRunner.Binding held,EmulatorDevelopmentBatch.Window window,long epoch)throws Exception {
        if(window==null)throw new IllegalArgumentException("Explicit development window required");
        EmulatorDevelopmentBatch.requireBatchKey(held,window.key);
        EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.read(EmulatorAndroidDailyStore.files(context),window.sweep(1).key);
        if(!run.binding.same(held)||!run.window.sameRun(window))throw new IllegalStateException("Exact explicit run required");
        run.requireReservation(window.key,EmulatorDailyBatchOutcome.readPrivate(EmulatorAndroidDailyStore.files(context).resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.reservationLeaf(window.key)+".tsv"),4096));
        EmulatorDailyBatchScan.Snapshot scan=EmulatorDailyBatchScan.read(context,held);
        EmulatorDailyBatchInventory inventory=EmulatorDailyBatchInventory.development(held,window,epoch,System.currentTimeMillis(),scan);
        startInventory(held,window.key,epoch,window.deadline,inventory,window);
    }
    /** Saved scheduler already captured the complete read-only snapshot before
     * its durable gameplay intent. No second scan, no relaxed inventory guard. */
    void startPrepared(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,EmulatorDailyBatchScan.Snapshot scan,EmulatorDevelopmentBatch.Window window)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        scan=EmulatorSavedBatchOrder.balanced(scan);
        EmulatorDailyBatchInventory inventory;
        if(window==null){
            if(EmulatorDevelopmentBatch.development(key))throw new IllegalArgumentException("Explicit development window required");
            inventory=new EmulatorDailyBatchInventory(held,key,epoch,deadline,System.currentTimeMillis(),scan);
        }else{
            if(!window.key.equals(key)||deadline!=window.deadline)throw new IllegalArgumentException("Exact prepared window required");
            EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.read(EmulatorAndroidDailyStore.files(context),window.sweep(1).key);
            if(!run.binding.same(held)||!run.window.sameRun(window))throw new IllegalStateException("Exact explicit prepared run required");
            run.requireReservation(key,EmulatorDailyBatchOutcome.readPrivate(EmulatorAndroidDailyStore.files(context).resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.reservationLeaf(key)+".tsv"),4096),epoch);
            inventory=EmulatorDailyBatchInventory.development(held,window,epoch,System.currentTimeMillis(),scan);
        }
        startInventory(held,key,epoch,deadline,inventory,window);
    }
    private void startInventory(EmulatorDailyRunner.Binding held,String key,long epoch,long deadline,EmulatorDailyBatchInventory inventory,EmulatorDevelopmentBatch.Window window)throws Exception {
        String stage="inventory-publication";
        try {
        inventory.persist(root(),EmulatorAndroidDailyStore::syncDirectory,p->java.nio.file.Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        stage="inventory-identity";
        EmulatorDailyRouteWork.requireIdentity(context,held);
        stage="inventory-readback";
        if(!inventory.encode().equals(EmulatorDailyBatchInventory.read(root(),held,key).encode()))throw new IllegalStateException("Inventory durable readback differs");
        stage="selection-build";
        EmulatorDailyBatchSelection selection=EmulatorDailyBatchSelection.create(EmulatorAndroidDailyStore.files(context),inventory,EmulatorAndroidDailyStore::syncDirectory);
        stage="selection-publication";
        selection.persist(EmulatorAndroidDailyStore.files(context),EmulatorAndroidDailyStore::syncDirectory,p->java.nio.file.Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
        stage="selection-identity";
        EmulatorDailyRouteWork.requireIdentity(context,held);
        stage="selection-readback";
        if(!selection.encode().equals(EmulatorDailyBatchSelection.read(EmulatorAndroidDailyStore.files(context),inventory,EmulatorAndroidDailyStore::syncDirectory).encode()))throw new IllegalStateException("Selection durable readback differs");
        if(selection.queue.isEmpty()){
            stage="empty-outcome";
            if(DispatchBatchService.isRunning()||GpsWorkLock.heldBy(context,GpsWorkLock.BATCH)||GpsWorkLock.heldBy(context,GpsWorkLock.FLOWER_FARM))throw new IllegalStateException("External GPS work preserved");
            EmulatorDailyBatchOutcome empty=new EmulatorDailyBatchOutcome(inventory,"-","-",System.currentTimeMillis(),true,java.util.Collections.emptyMap(),java.util.Collections.emptyMap(),java.util.Collections.emptyMap(),java.util.Collections.emptyMap(),selection);
            empty.persist(EmulatorAndroidDailyStore.files(context).resolve("daily-batch-outcomes"),EmulatorAndroidDailyStore::syncDirectory,p->java.nio.file.Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")));
            EmulatorDailyRouteWork.requireIdentity(context,held);return; // No native/GPS request; completion still requires independent observation.
        }
        stage="service-start";
        if(window==null)DispatchBatchService.startDailyBatch(context,selection.queue,held,key,epoch,deadline);
        else DispatchBatchService.startDevelopmentBatch(context,selection.queue,held,window,epoch);
        }catch(Exception rejected){
            // Fixed vocabulary only; never log the private exception message,
            // binding, coordinates, task ID or raw inventory. Preserve the
            // original exception and unknown-start/no-replay behavior.
            android.util.Log.w("PccDailyBatchStart","Original batch start refused: "+stage);
            throw rejected;
        }
    }
    /** Validated at the actual pre-intent preparation event. Original immutable
     * inventory, reservation correlation and all start guards remain mandatory. */
    void startPreparedInventory(EmulatorDailyRunner.Binding held,EmulatorDailyBatchInventory inventory)throws Exception{
        if(inventory==null||!held.same(inventory.binding))throw new IllegalArgumentException("Exact prepared binding required");
        EmulatorDevelopmentBatch.Window window=inventory.developmentWindow;
        if(window!=null){
            EmulatorDevelopmentBatchRun run=EmulatorDevelopmentBatchRun.read(EmulatorAndroidDailyStore.files(context),window.sweep(1).key);
            if(!run.binding.same(held)||!run.window.sameRun(window))throw new IllegalStateException("Exact prepared run required");
            run.requireReservation(inventory.key,EmulatorDailyBatchOutcome.readPrivate(EmulatorAndroidDailyStore.files(context).resolve("daily-batch-links").resolve(EmulatorDevelopmentBatchRun.reservationLeaf(inventory.key)+".tsv"),4096),inventory.locationEpoch);
        }
        startInventory(held,inventory.key,inventory.locationEpoch,inventory.deadline,inventory,window);
    }
    public void closeAdmissions(EmulatorDailyRunner.Binding held,String key)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        DispatchBatchService.closeDailyBatchAdmissions(context,held,key);
    }
    public EmulatorDailyRunner.Observation observe(EmulatorDailyRunner.Binding held,String key)throws Exception {
        EmulatorDevelopmentBatch.requireBatchKey(held,key);
        EmulatorDailyBatchOutcome.retainedIdentity(context,held);
        EmulatorDailyBatchInventory.read(root(),held,key); // Full retained todo, no missing-file adoption.
        return DispatchBatchService.observeDailyBatch(context,held,key);
    }
}

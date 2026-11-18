package dev.ody.pikmincontrol;

import android.content.Context;

/** Actual Acceptance consumer and controlled read-only step observer.
 * The observer NEVER writes HC or starts gameplay. The existing automatic writer
 * must produce confirmed entries and actual game adoption between begin/finish.
 * Special harvesting is not a prerequisite; it is never certified by this observer. */
final class EmulatorAndroidDailyAcceptance implements EmulatorAndroidDailyFacts.Acceptance {
    private final Context context;
    // Fixed diagnostic vocabulary only. This is not readiness, a resumable
    // capture, or authority to retry a publication/write.
    enum Phase { INITIAL, CAPTURE_OWNER, CAPTURE_WINDOW, BEGIN_IDENTITY,
        RETAINED_SLOT, ROUTE_ORIGINAL, FRAME_QUIET, FRAME_IDENTITY,
        FRAME_LEDGER, FRAME_COUNTER, FRAME_RECHECK, EVIDENCE,
        IDLE_IDENTITY, LEDGER_RECHECK, PRIVATE_ROOT, PUBLISH, READBACK, COMPLETE }
    private volatile Phase phase=Phase.INITIAL;
    String failureStage(){return phase.name();}
    EmulatorAndroidDailyAcceptance(Context context){
        if(context==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))throw new IllegalArgumentException("MuMu acceptance context required");
        this.context=context;
    }
    public void require(EmulatorDailyRunner.Binding held)throws Exception{
        EmulatorDailyRouteWork.requireIdentity(context,held);
        java.nio.file.Path root=EmulatorAndroidDailyStore.files(context).resolve("daily-acceptance");
        EmulatorHcCertificate.Retained retained=EmulatorHcCertificate.read(root,held,System.currentTimeMillis(),context.getApplicationInfo().uid);
        EmulatorDailyAcceptanceEvidence evidence=retained.evidence;
        if(!evidence.automatic)throw new IllegalStateException("Continuous automatic step acceptance required; legacy manual proof retained only");
        java.nio.file.Path leaf=retained.leaf;
        try(java.nio.channels.FileChannel in=java.nio.channels.FileChannel.open(leaf,java.nio.file.StandardOpenOption.WRITE,java.nio.file.LinkOption.NOFOLLOW_LINKS)){in.force(true);}
        EmulatorAndroidDailyStore.syncDirectory(root.getParent());EmulatorAndroidDailyStore.syncDirectory(root);
        if(!evidence.encode().equals(EmulatorHcCertificate.readLeaf(root,leaf,System.currentTimeMillis(),context.getApplicationInfo().uid).evidence.encode()))throw new IllegalStateException("Acceptance changed during durability reconciliation");
        EmulatorDailyRouteWork.requireIdentity(context,held);
        // Recheck final wall time after root/identity reads. Never cache a ready
        // boolean across process, account, route, APK/core, or expiry changes.
        if(!EmulatorHcCertificate.compatible(EmulatorDailyAcceptanceEvidence.decode(evidence.encode(),evidence.binding,System.currentTimeMillis()),held))
            throw new IllegalStateException("HC certificate scope changed");
    }
    static final class Capture {
        private final EmulatorAndroidDailyAcceptance owner;
        private final EmulatorDailyRunner.Binding held;
        private final Frame before;
        private boolean attempted;
        private Capture(EmulatorAndroidDailyAcceptance owner,EmulatorDailyRunner.Binding held,Frame before){this.owner=owner;this.held=held;this.before=before;}
    }
    private static final class Frame {
        final String ledger,counter;final long at;
        Frame(String ledger,String counter,long at){this.ledger=ledger;this.counter=counter;this.at=at;}
    }
    /** Caller retains this live object while using the EXISTING explicit manual
     * preview/confirmation UI. There is no serialized replay/adoption token. */
    Capture begin(EmulatorDailyRunner.Binding held,String routeKey)throws Exception{
        phase=Phase.BEGIN_IDENTITY;
        EmulatorDailyRouteWork.requireIdentity(context,held);quiet();
        phase=Phase.RETAINED_SLOT;
        java.nio.file.Path root=EmulatorAndroidDailyStore.files(context).resolve("daily-acceptance");
        if(java.nio.file.Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS)){
            requirePrivateRoot(root);
            if(java.nio.file.Files.exists(root.resolve(EmulatorDailyRunner.digest(held.encode())+".tsv"),java.nio.file.LinkOption.NOFOLLOW_LINKS))
                throw new IllegalStateException("Retained acceptance exists; no replacement or replay");
        }
        // routeKey is retained only for the legacy caller signature. It is not
        // read, adopted or certified by the automatic-step-only v3 observer.
        Capture result=new Capture(this,held,frame(held));
        phase=Phase.COMPLETE;return result;
    }
    /** May be called again ONLY to observe after a pending/not-yet-adopted
     * manual record. Publication is attempted once; no HC write or new plan. */
    synchronized void finish(Capture capture)throws Exception{
        phase=Phase.CAPTURE_OWNER;
        if(capture==null||capture.owner!=this||capture.attempted)throw new IllegalStateException("Live original controlled capture required; no publication replay");
        phase=Phase.CAPTURE_WINDOW;
        if(System.currentTimeMillis()<capture.before.at||System.currentTimeMillis()-capture.before.at>EmulatorDailyAcceptanceEvidence.AUTOMATIC_WINDOW)throw new IllegalStateException("Controlled capture expired; no automatic new step write");
        Frame after=frame(capture.held);
        String[] payload={capture.before.ledger,after.ledger,capture.before.counter,after.counter};
        phase=Phase.EVIDENCE;
        EmulatorDailyAcceptanceEvidence evidence=EmulatorDailyAcceptanceEvidence.createAutomaticSteps(capture.held,payload,capture.before.at,after.at,System.currentTimeMillis());
        phase=Phase.IDLE_IDENTITY;
        quiet();EmulatorDailyRouteWork.requireIdentity(context,capture.held);
        phase=Phase.LEDGER_RECHECK;
        if(!after.ledger.equals(ledger()))throw new IllegalStateException("Step ledger changed before acceptance publication");
        phase=Phase.PRIVATE_ROOT;
        java.nio.file.Path root=EmulatorAndroidDailyStore.files(context).resolve("daily-acceptance");
        if(java.nio.file.Files.exists(root,java.nio.file.LinkOption.NOFOLLOW_LINKS))requirePrivateRoot(root);
        capture.attempted=true; // Even a lost/failed fsync must not re-publish.
        phase=Phase.PUBLISH;
        EmulatorDailyAcceptanceEvidence.persist(root,evidence,EmulatorAndroidDailyStore::syncDirectory,
            (path,directory)->java.nio.file.Files.setPosixFilePermissions(path,java.nio.file.attribute.PosixFilePermissions.fromString(directory?"rwx------":"rw-------")));
        phase=Phase.READBACK;
        require(capture.held); // Actual private readback/durability/identity, never a Boolean ready flag.
        phase=Phase.COMPLETE;
    }
    synchronized void abandon(Capture capture){
        if(capture==null||capture.owner!=this||capture.attempted)throw new IllegalStateException("Cannot discard an attempted publication");
        capture.attempted=true; // Invalidates this read-only object; never removes a ledger or private leaf.
    }
    private Frame frame(EmulatorDailyRunner.Binding held)throws Exception{
        phase=Phase.FRAME_QUIET;quiet();
        phase=Phase.FRAME_IDENTITY;EmulatorDailyRouteWork.requireIdentity(context,held);
        phase=Phase.FRAME_LEDGER;
        String before=ledger();
        phase=Phase.FRAME_COUNTER;
        EmulatorRunnerCounterGateway.Captured counter=EmulatorRunnerCounterGateway.capture(context,held,CoreUpdater.pair(context));
        long at=System.currentTimeMillis();
        phase=Phase.FRAME_RECHECK;
        if(!before.equals(ledger()))throw new IllegalStateException("Step ledger changed during counter capture");
        EmulatorDailyRouteWork.requireIdentity(context,held);quiet();
        EmulatorGameDailySteps.read(counter.packet,held,System.currentTimeMillis());
        return new Frame(before,counter.packet,at);
    }
    private void quiet(){
        if(FlowerFarmService.isRunning()||DispatchBatchService.isRunning()||GpsWorkLock.anyHeld(context)||RouteSession.pending(context)||
            WorkStatus.stoppingAll||WorkStatus.blocked(context)||SetupDeployment.pending(context)||UpdateInstaller.pending(context)||CoreUpdater.pending(context))
            throw new IllegalStateException("Controlled step observation requires actual idle work/deployment");
    }
    private String ledger()throws Exception{
        StepStore store=new StepAndroidStore(context);StepCoordinator coordinator=StepCoordinator.of(store);
        synchronized(coordinator.txnLock()){
            if(!coordinator.storageFailure().isEmpty())throw new IllegalStateException("Step storage has an unresolved failure");
            java.nio.file.Path root=EmulatorAndroidDailyStore.files(context),leaf=root.resolve("step-ledger.properties");
            if(java.nio.file.Files.exists(root.resolve("step-ledger.properties.bak"),java.nio.file.LinkOption.NOFOLLOW_LINKS)||
                java.nio.file.Files.exists(root.resolve("step-ledger.properties.new"),java.nio.file.LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Pending step storage preserved; no capture repair");
            privateLedger(leaf);
            StepLoadResult loaded=store.load();if(!loaded.isValid())throw new IllegalStateException("Original retained ledger required");
            StepState state=StepState.decode(loaded.raw);
            if(!loaded.raw.equals(state.encode())||state.desiredRunning||state.ledger.pending()!=null||!state.writerOwner.isEmpty()||!state.lockId.isEmpty()||!state.lockRole.isEmpty())
                throw new IllegalStateException("Canonical idle original ledger required");
            String raw=EmulatorDailyAcceptanceEvidence.ledgerOriginal(leaf);
            privateLedger(leaf);if(!raw.equals(loaded.raw))throw new IllegalStateException("Ledger bytes changed or were normalized");return raw;
        }
    }
    private void privateLedger(java.nio.file.Path leaf)throws Exception{
        if(java.nio.file.Files.isSymbolicLink(leaf)||!java.nio.file.Files.isRegularFile(leaf,java.nio.file.LinkOption.NOFOLLOW_LINKS)||
           ((Number)java.nio.file.Files.getAttribute(leaf,"unix:uid",java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid||
           ((Number)java.nio.file.Files.getAttribute(leaf,"unix:nlink",java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue()!=1||
           !java.nio.file.Files.getPosixFilePermissions(leaf,java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
            throw new IllegalStateException("Single-link private original step ledger required");
    }
    private void requirePrivateRoot(java.nio.file.Path root)throws Exception{
        if(java.nio.file.Files.isSymbolicLink(root)||!java.nio.file.Files.isDirectory(root,java.nio.file.LinkOption.NOFOLLOW_LINKS)||
           ((Number)java.nio.file.Files.getAttribute(root,"unix:uid",java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid||
           !java.nio.file.Files.getPosixFilePermissions(root,java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            throw new IllegalStateException("Private acceptance directory required; no permission repair");
    }
    private void requirePrivate(java.nio.file.Path root,EmulatorDailyRunner.Binding held)throws Exception{
        java.nio.file.Path leaf=root.resolve(EmulatorDailyRunner.digest(held.encode())+".tsv");
        for(java.nio.file.Path path:new java.nio.file.Path[]{root,leaf}){
            if(java.nio.file.Files.isSymbolicLink(path)||((Number)java.nio.file.Files.getAttribute(path,"unix:uid",java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid)
                throw new IllegalStateException("Private App UID acceptance evidence required");
        }
        if(((Number)java.nio.file.Files.getAttribute(leaf,"unix:nlink",java.nio.file.LinkOption.NOFOLLOW_LINKS)).intValue()!=1||
           !java.nio.file.Files.getPosixFilePermissions(leaf,java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))||
           !java.nio.file.Files.getPosixFilePermissions(root,java.nio.file.LinkOption.NOFOLLOW_LINKS).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")))
            throw new IllegalStateException("Single-link private acceptance evidence required");
    }
}

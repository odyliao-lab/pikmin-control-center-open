package dev.ody.pikmincontrol;

import android.content.Context;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** Private configured entry. It does not schedule ticks or load the core.
 * Configuration is not evidence of acceptance: runtime rereads all live gates. */
final class EmulatorAndroidDailyConfigured {
    static EmulatorAndroidDailyRuntime.Report run(Context context)throws Exception{
        return run(context,null);
    }
    static EmulatorAndroidDailyRuntime.Report run(Context context,String expectedConfigSha)throws Exception{
        EmulatorDailyConfiguration config=read(context);
        if(expectedConfigSha!=null&&!expectedConfigSha.equals(EmulatorDailyRunner.digest(config.encode())))
            throw new IllegalStateException("Schedule configuration changed; never rebind/adopt");
        return EmulatorAndroidDailyRuntime.run(context,config);
    }
    static EmulatorDailyConfiguration read(Context context)throws Exception{
        Path root=root(context);requirePrivate(context,root);
        EmulatorDailyConfiguration config=EmulatorDailyConfiguration.read(root,System.currentTimeMillis());
        // A complete visible leaf after setup's sync failure is not durable
        // approval. Reconcile only durability; never rewrite contents or replay
        // setup/game effects. Partial/malformed leaves failed the read above.
        try(java.nio.channels.FileChannel in=java.nio.channels.FileChannel.open(root.resolve("config.tsv"),StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){in.force(true);}
        EmulatorAndroidDailyStore.syncDirectory(root.getParent());EmulatorAndroidDailyStore.syncDirectory(root);
        if(!config.encode().equals(EmulatorDailyConfiguration.read(root,System.currentTimeMillis()).encode()))throw new IllegalStateException("Configuration changed during durability reconciliation");
        requirePrivate(context,root);
        return config;
    }
    /** Explicit setup only; does not run a tick. Ready proof plus the same daily
     * lock is required before writing; existing journal and config are retained. */
    static void configure(Context context,EmulatorDailyRunner.Binding held,HarvestRoute route)throws Exception{
        configure(context,new EmulatorDailyConfiguration(held,route,System.currentTimeMillis()));
    }
    static void configure(Context context,EmulatorDailyConfiguration config)throws Exception{
        if(config==null)throw new IllegalArgumentException("Complete immutable itinerary required");
        EmulatorDailyRunner.Binding held=config.binding;
        Path root=root(context);
        // Setup cannot demand a persisted leaf before CREATE_NEW. Runtime/enable
        // use the full itinerary facts overload after persistence.
        EmulatorAndroidDailyFacts source=new EmulatorAndroidDailyFacts(context,held,config.route,new EmulatorAndroidDailyAcceptance(context));
        try(EmulatorAndroidDailyStore store=EmulatorAndroidDailyStore.open(context)){
            EmulatorDailyRunner.State state=store.read();String before=state==null?null:state.encode();
            if(state!=null&&(!held.same(state.binding)||state.journal.failed||state.journal.pending!=EmulatorDailyPlan.Pending.NONE))
                throw new IllegalStateException("Existing daily work/binding needs separate review; never adopt");
            EmulatorDailyRunner.Facts fresh=source.read(state==null?null:EmulatorDailyRunner.State.decode(before));
            EmulatorDailyRunner.State after=store.read();
            if(!java.util.Objects.equals(before,after==null?null:after.encode())||!fresh.ready||fresh.externalBusy||fresh.ownedRouteBusy||fresh.ownedBatchBusy||!fresh.routeConfigured){
                // Fixed reason only: no private route/account/data disclosure.
                // Keep the original refusal; make its next diagnosis possible.
                android.util.Log.w("PccDailyConfigure","Fresh idle setup refused: "+source.reason());
                throw new IllegalStateException("Fresh verified idle configuration setup required");
            }
            EmulatorDailyConfiguration.persist(root,config,EmulatorAndroidDailyStore::syncDirectory,
                (path,dir)->Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(dir?"rwx------":"rw-------")));
            requirePrivate(context,root);
        }
        // Any sync/close error propagates. No effects, cleanup, retry or overwrite.
    }
    private static Path root(Context context)throws Exception{
        EmulatorRuntime.require();
        if(context==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))throw new IllegalArgumentException("MuMu context required");
        return EmulatorAndroidDailyStore.files(context).resolve("daily-configuration");
    }
    private static void requirePrivate(Context context,Path root)throws Exception{
        Path leaf=root.resolve("config.tsv");
        for(Path p:new Path[]{root,leaf})if(Files.isSymbolicLink(p)||((Number)Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=context.getApplicationInfo().uid)
            throw new IllegalStateException("Private App UID configuration required");
        if(((Number)Files.getAttribute(leaf,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1||
            !Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rwx------"))||
            !Files.getPosixFilePermissions(leaf,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString("rw-------")))
            throw new IllegalStateException("Single-link private configuration required");
    }
}

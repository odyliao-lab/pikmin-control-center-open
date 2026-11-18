package dev.ody.pikmincontrol;

import android.content.Context;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;

/** MuMu-only durable opt-in/tick/stop composition. No service launch or UI
 * registration here. Enabled ticket NEVER bypasses the actual fresh gates. */
final class EmulatorAndroidDailySchedule {
    static EmulatorDailySchedule.Ticket enable(Context context)throws Exception{
        Context app=app(context);EmulatorDailyConfiguration config=EmulatorAndroidDailyConfigured.read(app);
        String sha=EmulatorDailyRunner.digest(config.encode());
        EmulatorAndroidDailyFacts source=new EmulatorAndroidDailyFacts(app,config,new EmulatorAndroidDailyAcceptance(app));
        try(EmulatorAndroidDailyStore daily=EmulatorAndroidDailyStore.open(app)){
            EmulatorDailyRunner.State state=daily.read();String before=state==null?null:state.encode();
            if(state!=null&&(!config.binding.same(state.binding)||state.journal.failed||state.journal.pending!=EmulatorDailyPlan.Pending.NONE))
                throw new IllegalStateException("Existing daily work/binding requires review, never adopt");
            // This explicit opt-in must observe the existing game, not stale
            // background producer files. Reorder ONLY the bound live task;
            // no restart, login/core load, cached permit or freshness widening.
            EmulatorDailyRouteWork.foregroundExistingGame(app,config.binding);
            EmulatorDailyRunner.Facts fresh=source.read(state==null?null:EmulatorDailyRunner.State.decode(before));
            EmulatorDailyRunner.State after=daily.read();
            if(!java.util.Objects.equals(before,after==null?null:after.encode())||!fresh.ready||fresh.externalBusy||fresh.ownedRouteBusy||fresh.ownedBatchBusy||!fresh.routeConfigured||
                    !sha.equals(EmulatorDailyRunner.digest(EmulatorAndroidDailyConfigured.read(app).encode()))){
                android.util.Log.w("PccDailyEnable","Fresh idle opt-in refused: "+source.reason()); // fixed vocabulary only
                throw new IllegalStateException("Fresh accepted idle opt-in required");
            }
            try(EmulatorDailySchedule schedule=open(app,null)){
                EmulatorDailySupportWindow.configure(app,config); // explicit fresh opt-in; desired helper choices preserved
                EmulatorDailySchedule.Ticket prior=schedule.read(System.currentTimeMillis());
                if(prior!=null)requirePrivate(root(app),prior);
                EmulatorDailySchedule.Ticket ticket=schedule.enable(sha,System.currentTimeMillis());
                requirePrivate(root(app),ticket);return ticket;
            }
        }
    }
    static EmulatorDailySchedule.Ticket enabled(Context context)throws Exception{
        Context app=app(context);Path root=root(app);
        if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return null; // default OFF, no namespace mutation
        try(EmulatorDailySchedule store=open(app,null)){
            EmulatorDailySchedule.Ticket t=store.read(System.currentTimeMillis());
            if(t==null)return null;requirePrivate(root,t);
            return store.enabled(t,System.currentTimeMillis())?t:null;
        }
    }
    static EmulatorDailyLoop create(Context context,EmulatorDailySchedule.Ticket held){
        Context app=app(context);if(held==null)throw new IllegalArgumentException("Caller-held schedule ticket required");
        return new EmulatorDailyLoop(()->{
            try(EmulatorDailySchedule store=open(app,held)){
                if(!store.enabled(held,System.currentTimeMillis()))throw new IllegalStateException("Schedule stopped/changed; no tick");
                // Hold schedule's nonblocking cross-process lock through the
                // ORIGINAL daily lock/tick; a stop marker may still be written
                // concurrently, but this one tick was already admitted.
                return EmulatorAndroidDailyConfigured.run(app,held.configSha);
            }
        },60000);
    }
    static void stop(Context context,EmulatorDailySchedule.Ticket held)throws Exception{
        Context app=app(context);Path root=root(app);requirePrivate(root,held);
        EmulatorDailySchedule.stop(root,held,System.currentTimeMillis(),EmulatorAndroidDailyStore::syncDirectory,EmulatorAndroidDailySchedule::mode);
        requirePrivate(root,held);
        EmulatorDailySupportWindow.release(app,held); // stale STOP cannot release another ticket's helper ownership
    }
    private static EmulatorDailySchedule open(Context app,EmulatorDailySchedule.Ticket held)throws Exception{
        Path root=root(app);if(Files.exists(root,LinkOption.NOFOLLOW_LINKS))requirePrivate(root,held);
        EmulatorDailySchedule s=new EmulatorDailySchedule(root,EmulatorAndroidDailyStore::syncDirectory,EmulatorAndroidDailySchedule::mode);
        try{requirePrivate(root,held);return s;}catch(Exception e){s.close();throw e;}
    }
    private static Context app(Context context){
        EmulatorRuntime.require();if(context==null||!"dev.ody.pikmincontrol.mumu".equals(context.getPackageName()))throw new IllegalArgumentException("MuMu context required");
        Context app=context.getApplicationContext();if(app==null||!"dev.ody.pikmincontrol.mumu".equals(app.getPackageName()))throw new IllegalArgumentException("MuMu application context required");return app;
    }
    private static Path root(Context app)throws Exception{return EmulatorAndroidDailyStore.files(app).resolve("daily-schedule");}
    private static void mode(Path path,boolean directory)throws Exception{Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));}
    private static void requirePrivate(Path root,EmulatorDailySchedule.Ticket held)throws Exception{
        int uid=android.os.Process.myUid();
        Path[] paths=held==null?new Path[]{root,root.resolve("schedule.lock"),root.resolve("ticket.tsv"),root.resolve("ticket.tsv.tmp")}:
            new Path[]{root,root.resolve("schedule.lock"),root.resolve("ticket.tsv"),root.resolve("ticket.tsv.tmp"),root.resolve(held.id+".stop")};
        for(Path p:paths){
            if(!Files.exists(p,LinkOption.NOFOLLOW_LINKS)){if(p.equals(root))throw new IllegalStateException("Private schedule root required");continue;}
            if(Files.isSymbolicLink(p)||((Number)Files.getAttribute(p,"unix:uid",LinkOption.NOFOLLOW_LINKS)).intValue()!=uid||
                !Files.getPosixFilePermissions(p,LinkOption.NOFOLLOW_LINKS).equals(PosixFilePermissions.fromString(p.equals(root)?"rwx------":"rw-------"))||
                (p.equals(root)?!Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS):(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)||((Number)Files.getAttribute(p,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).intValue()!=1)))
                throw new IllegalStateException("Private App UID/single-link schedule required");
        }
    }
}

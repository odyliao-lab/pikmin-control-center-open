package dev.ody.pikmincontrol;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
/** Wiring boundaries only; not an Android execution fixture. */
public final class FarmResetSourceTest {
    static int checks;
    static void check(boolean ok){checks++;if(!ok)throw new AssertionError("source check "+checks);}
    static String source(String name)throws Exception {
        Path app=Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent();
        return new String(Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/"+name+".java")),StandardCharsets.UTF_8).replace("\r\n","\n");
    }
    public static void main(String[] args)throws Exception {
        String service=source("FlowerFarmService"), ui=source("MainActivity"), status=source("WorkStatus");
        String reset=service.substring(service.indexOf("static synchronized void checkAndReset"),service.indexOf("private static synchronized void start(Context context, List<Point> points, int dwellSeconds, boolean recover,WorkCheckpoint resume,boolean route)"));
        check(reset.contains("acquireFarmRecovery"));check(reset.contains("putExtra(\"reset_only\",true)"));
        for(String unsafe:new String[]{"save(context","flyTo","walk(","setEnabled(true)","CheckpointStore.save","FarmReports.save"})check(!reset.contains(unsafe));
        check(service.contains("if(!resetOnly) {\n")||service.contains("if(!resetOnly) {\r\n"));
        check(service.indexOf("if(resetOnly)return;")<service.indexOf("setFlowerFarmSession(true)"));
        check(service.indexOf("if(resetOnly)return;")<service.indexOf("GpsModuleGateway.flyTo"));
        check(service.contains("if(!resetOnly)ResourceGuard.check(this,true,true)"));
        check(service.contains("runReport=null;checkpoint=null;routeSession=null"));
        check(service.contains("boolean routePending=RouteSession.pending(this)")
                &&service.contains("boolean nearbyPending=NearbyWorkState.ownedBy(this,GpsWorkLock.FLOWER_FARM)")
                &&service.contains("!routePending && !nearbyPending"));
        check(service.indexOf("NearbyWorkState.restore(this")<service.indexOf("boolean cleanupConfirmed="));
        check(service.indexOf("GpsWorkLock.releaseFarmChecked(this)")<service.indexOf("boolean cleanupConfirmed="));
        check(service.indexOf("boolean cleanupConfirmed=")<service.indexOf("WorkStatus.writeFarm(this,terminal"));
        String locks=source("GpsWorkLock");
        check(locks.contains("!FLOWER_FARM.equals(held)"));
        check(locks.contains("FarmRecovery.persist(()->p.edit().remove(OWNER).remove(TOUCHED_MS).commit())"));
        check(service.contains("catch(RuntimeException storage)"));
        check(status.contains("FarmRecovery.persist(e::commit)"));
        check(status.indexOf("putString(\"flower-farm.state\"")<status.indexOf("FarmRecovery.persist(e::commit)"));
        check(status.indexOf("putBoolean(\"flower-farm.cleanup_confirmed\"")<status.indexOf("FarmRecovery.persist(e::commit)"));
        check(status.contains("farmStorageFailed = true")&&status.contains("farmStorageFailed = false"));
        check(ui.contains("secondaryButton(\"檢查並重置\""));
        check(ui.contains("if(!farmPresentation().canReset)throw"));
        check(ui.contains("FlowerFarmService.checkAndReset(this)"));
        check(!ui.contains("FarmRecovery.suggested(WorkStatus.state"));
        System.out.println("PASS FarmResetSource: "+checks+" source boundaries; NOT device recovery validation");
    }
}

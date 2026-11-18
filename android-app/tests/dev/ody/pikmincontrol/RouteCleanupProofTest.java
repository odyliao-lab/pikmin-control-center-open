package dev.ody.pikmincontrol;

public final class RouteCleanupProofTest {
    static int checks;
    static void check(boolean value){++checks;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception{
        check(RouteCleanupProof.ready(true,false,0));
        check(!RouteCleanupProof.ready(false,false,0));
        check(!RouteCleanupProof.ready(true,true,0));
        check(RouteCleanupProof.ready(true,false,28));
        check(!RouteCleanupProof.ready(true,false,-1));
        check(RouteCleanupProof.speed(19,19));
        check(RouteCleanupProof.speed(18,18.01));
        check(!RouteCleanupProof.speed(18,19));
        check(!RouteCleanupProof.speed(18,Double.NaN));
        check(!RouteCleanupProof.speed(Double.POSITIVE_INFINITY,19));
        check(!RouteCleanupProof.speed(0,0));
        check(RouteCleanupProof.response("theappninjas.gpsjoystick.STATUS_RESPONSE"));
        check(!RouteCleanupProof.response("theappninjas.gpsjoystick.ERROR"));
        check(!RouteCleanupProof.response(null));
        String record="405a2e2 u0 "+RouteCleanupProof.SERVICE;
        check(RouteCleanupProof.serviceIdentity("* ServiceRecord{"+record+"}\n").equals(record));
        check(RouteCleanupProof.serviceIdentity("nothing").isEmpty());
        check(RouteCleanupProof.serviceIdentity("* ServiceRecord{other u0 unrelated/Service}").isEmpty());
        check(RouteCleanupProof.SERVICE.startsWith(JoystickAdapter.PACKAGE+"/"));
        java.nio.file.Path app=java.nio.file.Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent();
        String watchdog=new String(java.nio.file.Files.readAllBytes(app.resolve("assets/route-watchdog.sh")),java.nio.charset.StandardCharsets.UTF_8);
        check(watchdog.contains("gpsjoystick.ROUTE_PAUSE"));
        check(!watchdog.contains("gpsjoystick.STOP"));
        check(!watchdog.contains("stop-service"));
        check(watchdog.contains("dumpsys activity services"));
        check(watchdog.contains("$marker.done.$token"));
        check(watchdog.contains("trap "));
        String session=new String(java.nio.file.Files.readAllBytes(app.resolve("src/dev/ody/pikmincontrol/RouteSession.java")),java.nio.charset.StandardCharsets.UTF_8);
        int retire=session.indexOf("retireWatchdog();",session.indexOf("public void close()"));
        check(retire>0&&retire<session.indexOf("awaitActualProvider(at);"));
        check(session.contains("GPS監護退出未確認，保留待收尾狀態"));
        check(session.indexOf("awaitActualProvider(at);")<session.indexOf("putBoolean(\"pending\",false)"));
        check(session.contains("fix.fixMs>previous"));
        String close=session.substring(session.indexOf("public void close()"));
        check(!close.contains("stop-service"));
        check(!close.contains("observedStopCommand"));
        check(!close.contains("PREFIX+\"STOP\""));
        check(!close.contains("PREFIX+\"TELEPORT\""));
        check(!close.contains("root(\"am start-foreground-service"));
        check(close.contains("requireSameService();"));
        check(close.contains("provider-preserved; route-paused"));
        check(session.indexOf("providerService=serviceIdentity();")<session.indexOf("Intent before=status();"));
        System.out.println("PASS RouteCleanupProof: "+checks+" checks");
    }
}

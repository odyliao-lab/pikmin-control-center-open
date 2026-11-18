package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Wiring complements BoundedCommandTest's real child-process timeout/error tests. */
public final class LegacyRootBoundaryTest {
    public static void main(String[] args) throws Exception {
        int count=0;
        for(String dir:new String[]{"android-app/src/dev/ody/pikmincontrol/","targets/mumu/android/override/"})
            for(String name:new String[]{"NectarModuleGateway","ReturnRewardModuleGateway","DispatchProbeGateway","GpsModuleGateway"}){
                String text=new String(Files.readAllBytes(Paths.get(dir+name+".java")),StandardCharsets.UTF_8);
                int start=text.indexOf("private static String root(String command)");
                int end=text.indexOf("\n    }",start);
                String root=text.substring(start,end);
                if(!root.contains("BoundedCommand.checkedRoot(command,6,1048576)")||text.contains("legacyRoot(")||text.contains("process.waitFor()"))
                    throw new AssertionError("Unbounded control root: "+dir+name);
                if((name.equals("NectarModuleGateway")||name.equals("ReturnRewardModuleGateway"))&&!root.contains("ReadOnlyQueryPolicy.DISPLAY.invalidate()"))
                    throw new AssertionError("Write must invalidate display cache");
                if(root.contains("RootQueryStats.completed"))throw new AssertionError("Root counted twice");
                count++;
            }
        System.out.println("PASS LegacyRootBoundary: "+count+" phone/MuMu gateway boundaries; no device calls");
    }
}

package dev.ody.pikmincontrol;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs the real coordinator against isolated Android/I/O doubles, never a connected device. */
public final class PhoneUpdateFlowHostTest {
    public static void main(String[] args)throws Exception {
        Path stage=Files.createTempDirectory("phone-update-host-");
        List<String> compile=new ArrayList<>(Arrays.asList("-encoding","UTF-8","-d",stage.toString()));
        String fixture=new String(Files.readAllBytes(Paths.get("android-app/tests/fixtures/phone-update.txt")),StandardCharsets.UTF_8);
        for(String section:fixture.split("(?m)^// FILE ")){
            if(section.trim().isEmpty())continue;
            int split=section.indexOf('\n');Path file=stage.resolve(section.substring(0,split).trim());
            Files.createDirectories(file.getParent());Files.write(file,section.substring(split+1).getBytes(StandardCharsets.UTF_8));compile.add(file.toString());
        }
        for(String name:new String[]{"PhoneUpdateFlow","PhoneUpdatePolicy"}){
            Path file=stage.resolve("dev/ody/pikmincontrol/"+name+".java");
            Files.copy(Paths.get("android-app/src/dev/ody/pikmincontrol/"+name+".java"),file);compile.add(file.toString());
        }
        if(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,compile.toArray(new String[0]))!=0)
            throw new AssertionError("production flow host compilation failed");
        Process p=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin","java").toString(),"-cp",stage.toString(),"dev.ody.pikmincontrol.FlowFixture").inheritIO().start();
        if(!p.waitFor(40,java.util.concurrent.TimeUnit.SECONDS)){p.destroyForcibly();throw new AssertionError("fixture timeout");}
        if(p.exitValue()!=0)throw new AssertionError("production flow host fixture failed; retained "+stage);
    }
}

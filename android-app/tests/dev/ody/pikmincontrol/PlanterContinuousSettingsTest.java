package dev.ody.pikmincontrol;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;

/** Compile the actual reconciler with isolated Android/root boundary doubles. */
public final class PlanterContinuousSettingsTest {
    public static void main(String[] args)throws Exception{
        Path fixtures=Paths.get(System.getProperty("pcc.fixtures"));
        Path app=fixtures.getParent().getParent(),source=app.resolve("src/dev/ody/pikmincontrol");
        String requested=System.getProperty("pcc.testWork");
        Path out=requested==null?Files.createTempDirectory("planter-continuous-"):
            Files.createTempDirectory(Paths.get(requested),"planter-continuous-");
        List<String> options=new ArrayList<>(Arrays.asList("-encoding","UTF-8","-source","8","-target","8","-classpath","","-d",out.toString()));
        for(String name:new String[]{"PlanterSettings","PlanterSteps","PlanterState"})options.add(source.resolve(name+".java").toString());
        try(java.util.stream.Stream<Path> paths=Files.walk(fixtures.resolve("planter-continuous"))){
            paths.filter(p->p.toString().endsWith(".java")).sorted().forEach(p->options.add(p.toString()));
        }
        javax.tools.JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null||compiler.run(null,null,null,options.toArray(new String[0]))!=0)throw new AssertionError("production reconciler fixture compilation failed");
        String executable=System.getProperty("os.name").startsWith("Windows")?"java.exe":"java";
        Path log=out.resolve("result.log");
        Process process=new ProcessBuilder(Paths.get(System.getProperty("java.home"),"bin",executable).toString(),"-cp",out.toString(),
            "dev.ody.pikmincontrol.PlanterContinuousSettingsHarness").redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if(!process.waitFor(20,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("reconciler HOST fixture timed out");}
        String result=new String(Files.readAllBytes(log),StandardCharsets.UTF_8);System.out.print(result);
        if(process.exitValue()!=0)throw new AssertionError("production reconciler regression failed");
    }
}

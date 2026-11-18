package dev.ody.pikmincontrol;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
public final class StepApkCompatibilityTest {
    static int n;
    static void check(boolean v){n++;if(!v)throw new AssertionError("HC check "+n);}
    static String read(Path p)throws Exception{return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception {
        StepPaging.Tokens t=new StepPaging.Tokens();
        check(t.remember(null)==-1); long a=t.remember("opaque/AA=="),b=t.remember("opaque/BB==");
        check(t.remember(StepPaging.sdkNextToken(""))==-1);
        check(t.remember(StepPaging.sdkNextToken(null))==-1);
        check("opaque/AA==".equals(StepPaging.sdkNextToken("opaque/AA==")));
        check(a!=b && "opaque/AA==".equals(t.resolve(a))); check(t.remember("opaque/AA==")==a);
        check(t.resolve(9999)==null); check(StepPaging.stalledPage(1,a,a)); check(!StepPaging.stalledPage(0,a,a));
        for(int i=0;i<70;i++) t.remember("token"+i); check(t.resolve(a)==null);
        boolean rejected=false;try{t.remember("");}catch(IllegalArgumentException e){rejected=true;}check(rejected);
        Path app=Paths.get(System.getProperty("pcc.fixtures")).getParent().getParent();
        String adapter=read(app.resolve("src/dev/ody/pikmincontrol/StepHealthGatewayApi33.java"));
        check(adapter.contains("Metadata.manualEntry(plan.clientRecordId,plan.clientRecordVersion)"));
        check(adapter.contains("realPermissions(hc,StepPaging.remainingMs(deadline))"));
        check(adapter.contains("submitted=true;"));check(adapter.contains("submitted && !\"permission\".equals(reason)"));
        check(!adapter.contains("parseLong") && !adapter.contains("deleteRecords") && !adapter.contains("updateRecords"));
        String engine=read(app.resolve("src/dev/ody/pikmincontrol/StepEngine.java"));
        check(!engine.contains("StepHealthGatewayApi34."));
        String gateway=read(app.resolve("src/dev/ody/pikmincontrol/StepHealthGateway.java"));
        check(gateway.contains("SDK_INT == 33") && gateway.contains("new StepHealthGatewayApi34(context)"));
        String manifest=read(app.resolve("AndroidManifest.xml"));
        check(manifest.contains("com.google.android.apps.healthdata"));
        check(manifest.contains("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"));
        check(manifest.contains("androidx.health.platform.client.ACTION_BIND_SDK_SERVICE"));
        System.out.println("PASS Android13 HC: "+n+" opaque paging/epoch isolation/source checks; NOT device HC I/O");
    }
}

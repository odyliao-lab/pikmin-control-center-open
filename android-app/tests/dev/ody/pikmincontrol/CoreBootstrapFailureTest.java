package dev.ody.pikmincontrol;
public final class CoreBootstrapFailureTest {
    public static void main(String[] args)throws Exception {
        for(String name:new String[]{"boot_lib_wait","lib_timeout","symbols_missing","incompatible"}) {
            CoreCapabilities k=CoreCapabilities.parse(ReadinessSample.Read.ok(ReadinessCapabilitiesTest.golden(name)),
                ReadinessCapabilitiesTest.PID,"1.5.23",91,ReadinessCapabilitiesTest.NOW);
            if(CoreBootstrapFailure.explain(k).isEmpty())throw new AssertionError(name);
        }
        if(!CoreBootstrapFailure.explain(null).isEmpty())throw new AssertionError("unknown is not failure");
        System.out.println("PASS CoreBootstrapFailure: bounded startup explanations; not service/device validation");
    }
}

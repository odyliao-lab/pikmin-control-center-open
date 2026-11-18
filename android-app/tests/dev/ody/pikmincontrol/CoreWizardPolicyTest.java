package dev.ody.pikmincontrol;
public final class CoreWizardPolicyTest {
    static void check(boolean ok){if(!ok)throw new AssertionError();}
    public static void main(String[] args){
        check(CoreWizardPolicy.pending(false,true,"staged",83,84).equals("reboot"));
        check(CoreWizardPolicy.pending(true,true,"staged",83,84).equals("successor"));
        check(CoreWizardPolicy.pending(true,true,"staged",84,84).equals("review"));
        check(CoreWizardPolicy.pending(true,true,"staged",85,84).equals("review"));
        for(String phase:new String[]{"install-intent","backed-up","rollback-staged","accepted",""})
            check(CoreWizardPolicy.pending(true,true,phase,83,84).equals("review"));
        check(CoreWizardPolicy.pending(true,false,"staged",83,84).equals("review"));
        check(CoreWizardPolicy.idleFailure(10).contains("Pikmin"));check(CoreWizardPolicy.idleFailure(11).contains("停止"));
        check(CoreWizardPolicy.idleFailure(null).isEmpty());check(CoreWizardPolicy.idleFailure(1).isEmpty());
        System.out.println("PASS CoreWizardPolicy: pending phase/version routing and actionable errors; not device proof");
    }
}

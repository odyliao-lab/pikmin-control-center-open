package dev.ody.pikmincontrol;

public final class PlanterManualRecoveryTest {
    static int checks;
    static void check(boolean b){checks++;if(!b)throw new AssertionError("manual recovery "+checks);}
    static final class Port implements PlanterManualRecovery.Port {
        int submitted,reads,guards;long time;String result="result-unknown",pid="42";boolean oldOnly,changePid,fail;
        public void guard(){guards++;if(fail)throw new IllegalStateException("busy");}
        public PlanterRecovery submit(){submitted++;return ack("new-request","checking-inventory");}
        PlanterRecovery ack(String nonce,String value){return new PlanterRecovery("v1\t2000000\t"+pid+"\t"+nonce+"\t"+value,pid,2000000);}
        public PlanterRecovery read(){reads++;if(changePid)pid="43";boolean old=reads==1||oldOnly;
            return ack(old?"old-request":"new-request",old?"result-unknown":reads<3?"checking-inventory":result);}
        public void waitForUpdate(){time+=2000;}
        public long elapsed(){return time;}
    }
    public static void main(String[] args)throws Exception{
        Port p=new Port();check(PlanterManualRecovery.run(p).result.equals("result-unknown"));check(p.reads==3&&p.submitted==1);
        Port confirmed=new Port();confirmed.result="recovered-pluck-target-retired";
        check(PlanterManualRecovery.run(confirmed).result.equals(confirmed.result));check(confirmed.submitted==1);
        Port pending=new Port();pending.result="rpc-in-flight";check(PlanterManualRecovery.run(pending).result.equals("rpc-in-flight"));
        for(String kind:new String[]{"old","changed","busy","checking"}){
            Port bad=new Port();bad.oldOnly=kind.equals("old");bad.changePid=kind.equals("changed");bad.fail=kind.equals("busy");
            if(kind.equals("checking"))bad.result="checking-inventory";
            try{PlanterManualRecovery.run(bad);throw new AssertionError("unsafe result accepted");}catch(IllegalStateException expected){checks++;}
            check(bad.submitted<=1&&bad.reads<=10);
        }
        String gateway=FarmResetSourceTest.source("PlanterManualRecoveryGateway"),settings=FarmResetSourceTest.source("PlanterSettings");
        check(gateway.indexOf("foregroundCurrent(c)")<gateway.indexOf("PlanterGateway.requestRecovery()"));
        check(gateway.contains("requireSession(c,fresh.session(),fresh.account)")&&gateway.contains("session.equals(sample.session())")&&gateway.contains("account.equals(sample.account)"));
        check(gateway.indexOf("PlanterRetirementGateway.preview(c)")<gateway.indexOf("PlanterSettings.manualRecoveryBusy=false"));
        check(gateway.contains("PlanterRecoveryFlow.requireSame(consent,expected,fresh.session())"));
        check(gateway.contains("requestRecovery(expected,fresh.caps.sessionEpoch,fresh.caps.lastLoginMs)"));
        check(settings.contains("manualRecoveryBusy")&&!settings.contains("PlanterGateway.recover(")&&!settings.contains("PlanterGateway.recovery("));
        check(!gateway.contains("force-stop")&&!gateway.contains("setMode(\"auto\")"));
        System.out.println("PASS "+checks+" manual recovery ACK/foreground/fencing HOST checks; NOT device acceptance");
    }
}

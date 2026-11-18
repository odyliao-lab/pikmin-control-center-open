package dev.ody.pikmincontrol;

import java.nio.file.*;

public final class PlanterRetirementBoundaryTest {
    static int checks;static final long ASKED=1999000;
    static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    static PlanterRetirement.Proof proof()throws Exception {
        long observed=ASKED+85000;String[] rows=PlanterRetirementTest.rows(observed);
        return new PlanterRetirement.Proof(rows,observed);
    }
    static void seed(Path dir,long now)throws Exception{
        ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"v1\trequest-1\t42\t"+ASKED+"\t1000\tplant\told-seed\t1\n");
        ReadinessGatewayTest.write(dir.resolve("game/planter_recovery.tsv"),"v1\t"+now+"\t42\trequest-1\tresult-unknown\n");
        Files.deleteIfExists(dir.resolve("stop.marker"));
    }
    static BoundedCommand.Result shell(String sh,Path dir,long now)throws Exception{
        String cmd="date(){ echo "+now+"; }; set -eu; "+PlanterRetirementBoundary.guard(proof())
            .replace("/data/local/tmp/","tmp/").replace("/data/user/0/com.nianticlabs.pikmin/files/","game/")
            +"printf stop > stop.marker; printf ACTION_ALLOWED";
        ReadinessGatewayTest.write(dir.resolve("run.sh"),cmd);
        ProcessBuilder pb=new ProcessBuilder(sh,"run.sh").directory(dir.toFile());pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
        return BoundedCommand.run(pb,5000,4096);
    }
    static void blocked(String sh,Path dir,long now,String why)throws Exception{
        BoundedCommand.Result result=shell(sh,dir,now);
        check(!result.ok,"guard must reject: "+why);check(!Files.exists(dir.resolve("stop.marker")),"must not stop: "+why);
    }
    public static void main(String[] args)throws Exception{
        String guard=PlanterRetirementBoundary.guard(proof());
        check(guard.contains("date +%s%3N")&&guard.contains("now-$4<=90000")&&guard.contains("now-$2<=60000"),"current shell clock and original TTLs");
        check(guard.contains("requests==1 && acks==1")&&guard.contains("acknowledged>=asked"),"both strict complete records");
        String gateway=FarmResetSourceTest.source("PlanterRetirementGateway");
        int boundary=gateway.indexOf("PlanterRetirementBoundary.guard(p)"),stop=gateway.indexOf("am force-stop --user 0");
        check(boundary>=0&&stop>boundary,"boundary directly before game stop");
        check(gateway.contains("BoundedCommand.root(cmd,25,4096)"),"multi-check archive stays bounded without a false 6s timeout");
        check(gateway.contains("restart-pending-review-required")&&gateway.contains("rpc-failed-review-required")
            &&gateway.contains("server-rejected-review-required"),"only fresh review-required planter state can be archived");
        check(gateway.contains("planter_inventory.tsv")&&gateway.contains("now-$2<=12000")
            &&gateway.contains("= \"+p.inventorySha"),"latest inventory must be fresh and exactly match confirmed proof at stop");
        check(gateway.contains("sha256sum")&&gateway.contains("PlanterRetirementBoundary.guard(p)"),
            "exact pending bytes and scoped UNKNOWN ACK remain mandatory");
        check(gateway.contains("manual-retired-")&&gateway.contains("p.sha")&&gateway.contains("result-unknown"),
            "archive preserves exact bytes and UNKNOWN disposition");
        String sh=ReadinessGatewayTest.findSh();if(sh==null){ReadinessGatewayTest.skip("retirement boundary needs POSIX shell");return;}
        Path dir=Files.createTempDirectory("pcc-retirement-boundary-");
        try{
            long now=ASKED+85000;seed(dir,now);
            check(shell(sh,dir,now).ok&&Files.exists(dir.resolve("stop.marker")),"85s request still valid at action");
            // Reuse the exact Java proof from 85s; saving/guard work advances time to 95s.
            seed(dir,now);blocked(sh,dir,ASKED+95000,"proof aged from 85 to 95 seconds before action");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("game/planter_recovery.tsv"),"v1\t"+now+"\t42\trequest-2\tresult-unknown\n");
            blocked(sh,dir,now,"new ACK nonce cannot authorize old consent");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("game/planter_recovery.tsv"),"v1\t"+(now-60001)+"\t42\trequest-1\tresult-unknown\n");
            blocked(sh,dir,now,"old ACK");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("game/planter_recovery.tsv"),"v1\t"+now+"\t42\trequest-1\tchecking-inventory\n");
            blocked(sh,dir,now,"checking is not UNKNOWN");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"v1\trequest-2\t42\t"+ASKED+"\t1000\tplant\told-seed\t1\n");
            blocked(sh,dir,now,"changed request nonce");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"v1\trequest-1\t42\t"+ASKED+"\t1000\tplant\tother-seed\t1\n");
            blocked(sh,dir,now,"changed request original");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"v1\trequest-1\t43\t"+ASKED+"\t1000\tplant\told-seed\t1\n");
            blocked(sh,dir,now,"changed request PID");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"v1\trequest-1\t42\t999\t1000\tplant\told-seed\t1\n");
            blocked(sh,dir,now,"request predates original");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("tmp/pikmin-planter-recover.txt"),"");
            blocked(sh,dir,now,"empty request is not ACK input");
            seed(dir,now);ReadinessGatewayTest.write(dir.resolve("game/planter_recovery.tsv"),"v1\t"+now+"\t42\trequest-1\tresult-unknown\nextra\n");
            blocked(sh,dir,now,"extra ACK rows");
            System.out.println("PASS real retirement boundary shell: 11 cases; synthetic stop marker only");
        }finally{ReadinessGatewayTest.deleteTree(dir);}
        System.out.println("PASS PlanterRetirementBoundary: "+checks+" HOST checks; no Android stop/archive validation");
    }
}

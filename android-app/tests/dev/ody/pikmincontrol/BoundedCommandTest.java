package dev.ody.pikmincontrol;
public final class BoundedCommandTest {
    public static void main(String[] args) throws Exception {
        if(args.length>0){if(args[0].equals("fail")){System.out.print("private-output");System.exit(7);}if(args[0].equals("sleep")){Thread.sleep(4000);return;}for(int i=0;i<50000;i++)System.out.print('x');return;}
        String java=new java.io.File(System.getProperty("java.home"),"bin/java").getPath();
        String cp=System.getProperty("java.class.path"),name=BoundedCommandTest.class.getName();
        BoundedCommand.Result full=BoundedCommand.run(new ProcessBuilder(java,"-cp",cp,name,"output"),10000,1024);
        if(!full.ok||!full.truncated||full.text.length()!=1024)throw new AssertionError("bounded output");
        long at=System.nanoTime();BoundedCommand.Result timeout=BoundedCommand.run(new ProcessBuilder(java,"-cp",cp,name,"sleep"),100,1024);
        if(!timeout.timedOut||timeout.ok||(System.nanoTime()-at)/1000000>3000)throw new AssertionError("deadline");
        BoundedCommand.Result fail=BoundedCommand.run(new ProcessBuilder(java,"-cp",cp,name,"fail"),10000,1024);
        if(fail.ok||fail.timedOut||fail.exitCode==null||fail.exitCode!=7||!fail.failureSummary().contains("退出碼 7")||fail.failureSummary().contains("private-output"))
            throw new AssertionError("Exit failure must not become timeout or leak raw output");
        if(!timeout.failureSummary().contains("逾時")||!full.failureSummary().contains("超過上限"))throw new AssertionError("Distinct outcomes");
        if(!new BoundedCommand.Result("",false,false,false,124).failureSummary().contains("內層逾時"))throw new AssertionError("Inner timeout status");
        for(BoundedCommand.Result rejected:new BoundedCommand.Result[]{full,timeout,fail,new BoundedCommand.Result("private-output",false,false,false)}){
            try{BoundedCommand.checkedText(rejected);throw new AssertionError("unconfirmed output accepted");}
            catch(java.io.IOException expected){if(expected.getMessage().contains("private-output"))throw new AssertionError("private output leaked");}
        }
        if(!"complete".equals(BoundedCommand.checkedText(new BoundedCommand.Result("complete",true,false,false,0))))throw new AssertionError("valid output rejected");
        System.out.println("PASS BoundedCommand truncation and deadline");
    }
}

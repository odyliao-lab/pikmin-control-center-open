package dev.ody.pikmincontrol;

public final class OverviewHistoryBoundaryTest {
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception {
        String nectar=FarmResetSourceTest.source("NectarModuleGateway"),dispatch=FarmResetSourceTest.source("DispatchProbeGateway"),ui=FarmResetSourceTest.source("MainActivity");
        check(nectar.contains("tail -n 2000 \" + CLAIMS")&&!nectar.contains("root(\"cat \" + CLAIMS"),"nectar stats no longer copies whole history");
        check(dispatch.contains("tail -n 2000 \" + HISTORY")&&!dispatch.contains("root(\"cat \" + HISTORY"),"dispatch stats no longer copies whole history");
        String claims="2000000\tSUCCESS\tid\t0\t0\t0\t0\tNONE\t3\t5\t1\t5\n";
        String summary=NectarModuleGateway.formatStats(claims,2000000);
        check(summary.contains("2000筆範圍")&&summary.contains("精華 +5")&&!summary.contains("全部紀錄"),"sample scope and amounts explicit");
        check(DispatchProbeGateway.formatStats("2000000\tstart-requested\tfruit\tid\t1200\tx\n2000001\tinventory-confirmed\tfruit\tid\t0\tx\n",2000001).contains("派遣成功 1 筆"),"strongest outcome retained");
        int statusRead=ui.indexOf("NectarModuleGateway.Snapshot n = NectarModuleGateway.read();");
        String overview=ui.substring(statusRead,ui.indexOf("private void refreshNectar()",statusRead));
        check(overview.indexOf("overviewText.setText")<overview.indexOf("NectarModuleGateway.claimStats()"),"live nectar state delivered before statistics");
        check(overview.contains("showInline(overviewReturn")&&overview.contains("統計讀取失敗（不代表自動收取失敗）"),"errors stay in their own fields");
        String sh=ReadinessGatewayTest.findSh();
        if(sh==null)ReadinessGatewayTest.skip("oversized history requires POSIX shell");
        else {
            java.nio.file.Path d=java.nio.file.Files.createTempDirectory("pcc-large-history-");
            try {
                StringBuilder history=new StringBuilder();for(int i=0;i<40000;i++)history.append(claims);
                ReadinessGatewayTest.write(d.resolve("history.tsv"),history.toString());
                check(java.nio.file.Files.size(d.resolve("history.tsv"))>1048576,"fixture exceeds old root output ceiling");
                for(String command:new String[]{"cat history.tsv",NectarModuleGateway.statsCommand(),DispatchProbeGateway.statsCommand()}) {
                    command=command.replaceAll("/data/[^ ]+", "history.tsv");
                    ProcessBuilder pb=new ProcessBuilder(sh,"-c",command).directory(d.toFile());pb.environment().putAll(ReadinessGatewayTest.toolEnv(sh));
                    BoundedCommand.Result read=BoundedCommand.run(pb,5000,1048576);
                    if(command.startsWith("cat"))check(read.truncated,"old full-history read reproduces output ceiling");
                    else {
                        check(read.ok&&!read.truncated&&!read.timedOut,"production range read completes under original bound");
                        check(read.text.split("\n").length==2000,"both production commands return only declared window");
                    }
                }
            } finally {ReadinessGatewayTest.deleteTree(d);}
        }
        System.out.println("PASS overview history scope/isolation and oversized-history production shell; no root/device calls");
    }
}

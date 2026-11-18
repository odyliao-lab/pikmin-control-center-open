package dev.ody.pikmincontrol;
final class AutoPetalGateway {
    static final String CONTROL="/data/local/tmp/pikmin-auto-petals.tsv";
    static String readConfig()throws Exception{return BoundedCommand.checkedRoot("set -eu; test ! -L "+CONTROL+"; if test -e "+CONTROL+"; then test -f "+CONTROL+"; head -c 8193 "+CONTROL+"; fi",4,9000);}
    static AutoPetalConfig load()throws Exception{String raw=readConfig();return raw.isEmpty()?new AutoPetalConfig():AutoPetalConfig.parse(raw);}
    static void save(AutoPetalConfig c,String expected)throws Exception{
        String raw=c.encode();if(expected==null||expected.indexOf('\'')>=0)throw new IllegalArgumentException();
        String script="set -eu; umask 077; test ! -L "+CONTROL+"; before=''; if test -e "+CONTROL+"; then test -f "+CONTROL+"; before=$(cat "+CONTROL+"); fi; test \"$before\" = '"+expected.trim()+"'; "
            +"tmp=$(mktemp /data/local/tmp/pcc-auto-petals.XXXXXX); trap 'rm -f -- \"$tmp\"' EXIT; printf '%s' '"+raw+"' > \"$tmp\"; chown 0:0 \"$tmp\"; chmod 0644 \"$tmp\"; mv -fT \"$tmp\" "+CONTROL+"; cat "+CONTROL;
        if(!raw.equals(BoundedCommand.checkedRoot(script,4,9000)))throw new IllegalStateException("設定保存尚待核對");
    }
    static synchronized void disable()throws Exception{String raw=readConfig();if(raw.isEmpty())return;AutoPetalConfig c=AutoPetalConfig.parse(raw);if(!c.enabled)return;c.enabled=false;c.revision=java.util.UUID.randomUUID().toString();save(c,raw);}
    static void request(String path,String raw)throws Exception{
        if(!path.matches("/data/local/tmp/pikmin-auto-petals-(query|review)\\.tsv")||raw.indexOf('\'')>=0||raw.length()>2200)throw new IllegalArgumentException();
        String script="set -eu; umask 077; test ! -L "+path+"; t=$(mktemp /data/local/tmp/pcc-auto-query.XXXXXX); trap 'rm -f -- \"$t\"' EXIT; printf '%s' '"+raw+"' > \"$t\"; chown 0:0 \"$t\"; chmod 0644 \"$t\"; mv -fT \"$t\" "+path;
        BoundedCommand.checkedRoot(script,4,256);
    }
    static void review(AutoPetalConfig c)throws Exception{
        if(c.enabled||!readConfig().equals(c.encode()))throw new IllegalStateException("請先關閉並重新核對");
        String raw=BoundedCommand.checkedRoot("set -eu; p=/data/user/0/com.nianticlabs.pikmin/files/auto_petal_pending.tsv; test ! -L $p; test -f $p; head -c 2049 $p",4,2200);
        if(!raw.matches("v1\\t[0-9a-f-]{36}\\t[0-9a-f]{64}\\t[1-9][0-9]*\\t[1-9][0-9]*\\t[A-Za-z0-9._:-]{1,80}\\t(?:STOP|[A-Za-z0-9._:-]{1,80})\\t[0-9]+\\nend\\n"))throw new IllegalStateException("原紀錄格式無法核對");
        request("/data/local/tmp/pikmin-auto-petals-review.tsv","review\t"+c.revision+"\n"+raw);
    }
    static String[] snapshot(String revision)throws Exception{
        query();
        return awaitSnapshot(revision,AutoPetalGateway::readSnapshot,()->System.nanoTime()/1_000_000L,
                System::currentTimeMillis,Thread::sleep);
    }
    static void query()throws Exception{request("/data/local/tmp/pikmin-auto-petals-query.tsv",System.currentTimeMillis()+"\n");}
    /** One read-only root batch. Visible UI polling never writes a query or an action file. */
    static String[] displaySnapshot()throws Exception{
        return ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(OwnerGameProcess.command(),
            displayFile("automation_session.tsv",2048),displayFile("core_capabilities.tsv",8192),
            displayFile("auto_petal_inventory.tsv",131073),displayFile("auto_petal_status.tsv",131073),
            displayFile("core_capabilities.tsv",8192),displayFile("automation_session.tsv",2048),OwnerGameProcess.command()),2,290000),8);
    }
    private static String displayFile(String leaf,int limit){return "p=/data/user/0/com.nianticlabs.pikmin/files/"+leaf+"; if test -e $p; then test ! -L $p && test -f $p && head -c "+limit+" $p; fi";}
    interface Wait {void sleep(long millis)throws InterruptedException;}
    /** One query, bounded read-only acknowledgement polling; injectable clocks for HOST regression. */
    static String[] awaitSnapshot(String revision,java.util.concurrent.Callable<String[]> reader,
            java.util.function.LongSupplier elapsed,java.util.function.LongSupplier wall,Wait wait)throws Exception{
        long deadline=elapsed.getAsLong()+4000;String[] last;
        do{
            last=reader.call();
            AutoPetalState state=AutoPetalState.parse(last[1],last[0].trim(),last[2].trim(),revision,wall.getAsLong());
            if(state.valid&&state.complete)return last;
            long remaining=deadline-elapsed.getAsLong();
            if(remaining<=0)return last;
            wait.sleep(Math.min(600L,remaining));
        }while(elapsed.getAsLong()<deadline);
        return last;
    }
    private static String[] readSnapshot()throws Exception{
        return ProbeSnapshot.checkedSections(BoundedCommand.root(ProbeSnapshot.command(OwnerGameProcess.command(),
            "p=/data/user/0/com.nianticlabs.pikmin/files/auto_petal_status.tsv; if test -e $p; then test ! -L $p && test -f $p && head -c 131073 $p; fi",OwnerGameProcess.command()),2,140000),3);
    }
}

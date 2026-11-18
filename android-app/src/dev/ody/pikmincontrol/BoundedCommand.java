package dev.ody.pikmincontrol;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded background reads; never blocks the UI or accumulates an unbounded log. */
final class BoundedCommand {
    static final class Result {
        final String text; final boolean ok, timedOut, truncated;
        final Integer exitCode;
        Result(String text,boolean ok,boolean timedOut,boolean truncated){this(text,ok,timedOut,truncated,null);}
        Result(String text,boolean ok,boolean timedOut,boolean truncated,Integer exitCode){this.text=text;this.ok=ok;this.timedOut=timedOut;this.truncated=truncated;this.exitCode=exitCode;}
        String failureSummary(){
            if(timedOut)return "命令逾時或輸出管道未結束";
            if(truncated)return "命令輸出超過上限，無法完整核對";
            if(exitCode!=null&&exitCode!=0)return "命令未通過（退出碼 "+exitCode+"）"+((exitCode==124||exitCode==137)?"；可能由內層逾時終止":"");
            return "命令輸出讀取未完成";
        }
    }
    static Result run(ProcessBuilder builder,long milliseconds,int limit) throws Exception {
        if(milliseconds<1||milliseconds>30000||limit<1||limit>1048576)throw new IllegalArgumentException("invalid read bounds");
        Process process=builder.redirectErrorStream(true).start();
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();boolean[] clipped={false},readError={false};
        Thread reader=new Thread(()->{
            try(InputStream in=process.getInputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1)synchronized(bytes){int keep=Math.min(n,limit-bytes.size());if(keep>0)bytes.write(buffer,0,keep);if(keep<n)clipped[0]=true;}
            }catch(Exception ignored){synchronized(bytes){readError[0]=true;}}
        },"bounded-diagnostic-read");reader.setDaemon(true);reader.start();
        try {
            boolean finished=process.waitFor(milliseconds,TimeUnit.MILLISECONDS);
            if(!finished)process.destroyForcibly();
            reader.join(250);
            boolean timeout=!finished||reader.isAlive();
            synchronized(bytes){return new Result(new String(bytes.toByteArray(),StandardCharsets.UTF_8),finished&&!timeout&&!readError[0]&&process.exitValue()==0,timeout,clipped[0],finished?process.exitValue():null);}
        }finally{process.destroy();}
    }
    static Result root(String command,int seconds,int limit) throws Exception {
        // Inner timeout also terminates the command when the root broker outlives its caller.
        String quoted="'"+command.replace("'","'\\''")+"'";
        long start=System.nanoTime(); boolean ok=false;
        try {
            Result result=run(new ProcessBuilder("su","-mm","-c","timeout "+seconds+" sh -c "+quoted),(seconds+2L)*1000,limit);
            ok=result.ok&&!result.timedOut&&!result.truncated; return result;
        } finally { RootQueryStats.completed(System.nanoTime()-start,ok); }
    }
    /** Control callers must never accept partial output as a successful readback. */
    static String checkedRoot(String command,int seconds,int limit) throws Exception {
        return checkedText(root(command,seconds,limit));
    }
    static String checkedText(Result result) throws java.io.IOException {
        if(!result.ok||result.timedOut||result.truncated)throw new java.io.IOException(result.failureSummary());
        return result.text;
    }
}

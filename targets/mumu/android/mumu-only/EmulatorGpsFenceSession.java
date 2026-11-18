package dev.ody.pikmincontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One authenticated Root GPS fence actor's lifetime pipe. Not ALL-writer
 * exclusion, queued-provider completion, or permission to run a game command.
 * Caller retains this object on timeout and additionally verifies Root exit. */
final class EmulatorGpsFenceSession {
    private final Process process;
    private final String nonce;
    private final int gamePid;
    private int rootPid,requested,acknowledged;
    private boolean ready,failed,released,stopSent,outDone,errorDone;
    EmulatorGpsFenceSession(Process process,String nonce,int gamePid){
        if(process==null||nonce==null||!nonce.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")||gamePid<=0)
            throw new IllegalArgumentException("GPS fence process identity");
        this.process=process;this.nonce=nonce;this.gamePid=gamePid;
        drain(process.getInputStream(),false);drain(process.getErrorStream(),true);
    }
    private void drain(InputStream input,boolean error){
        Thread reader=new Thread(()->{
            try(InputStream in=input){
                ByteArrayOutputStream line=new ByteArrayOutputStream();int total=0,b;
                while((b=in.read())!=-1){
                    if(error||++total>4096||b==0||b=='\r'){fail();continue;}
                    if(b=='\n'){
                        accept(new String(line.toByteArray(),StandardCharsets.UTF_8));line.reset();
                    }else if(line.size()<160)line.write(b);else fail();
                }
                if(line.size()!=0)fail();
            }catch(Exception errorRead){fail();}
            finally{synchronized(this){
                if(error)errorDone=true;else{outDone=true;if(!released)fail();}
                notifyAll();
            }}
        },error?"gps-fence-stderr":"gps-fence-stdout");reader.setDaemon(true);reader.start();
    }
    private synchronized void accept(String line){
        if(failed)return;
        try{
            if(!ready){
                String[] f=line.split("\t",-1);
                if(f.length!=3||!"@gps-fence".equals(f[0])||!f[1].matches("[1-9][0-9]{0,8}")||!nonce.equals(f[2]))throw new IOException();
                rootPid=Integer.parseInt(f[1]);if(rootPid==gamePid)throw new IOException();ready=true;
            }else if(released)throw new IOException();
            else if(line.equals("@gps-released\t"+nonce)){
                if(!stopSent)throw new IOException();released=true;
            }else{
                if(requested<=acknowledged||!line.equals("@gps-held\t"+requested+"\t"+nonce))throw new IOException();
                acknowledged=requested;
            }
            notifyAll();
        }catch(Exception malformed){fail();}
    }
    private synchronized void fail(){failed=true;requestStop();notifyAll();}
    synchronized void requestStop(){
        if(stopSent)return;stopSent=true;
        try{process.getOutputStream().close();}catch(IOException failure){failed=true;}
    }
    private boolean live(){return ready&&!failed&&!released&&!stopSent&&!outDone&&process.isAlive();}
    private void boundedWait(long end)throws InterruptedException {
        long remaining=end-System.nanoTime();if(remaining>0)wait(Math.max(1,Math.min(100,remaining/1_000_000L)));
    }
    synchronized boolean awaitReady(long milliseconds)throws InterruptedException {
        if(milliseconds<1||milliseconds>12000)throw new IllegalArgumentException("Readiness bound");
        long end=System.nanoTime()+milliseconds*1_000_000L;
        while(!ready&&!failed&&!outDone&&process.isAlive()&&System.nanoTime()-end<0)boundedWait(end);
        return live();
    }
    synchronized boolean confirmHeld(long milliseconds)throws InterruptedException {
        if(milliseconds<1||milliseconds>3000)throw new IllegalArgumentException("Held check bound");
        if(!live()||requested!=acknowledged||requested>=16)return false;
        ++requested; // persist in this retained live session BEFORE any write
        try{
            process.getOutputStream().write(("Q\t"+requested+"\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }catch(IOException unknown){fail();return false;}
        long end=System.nanoTime()+milliseconds*1_000_000L;
        while(live()&&acknowledged!=requested&&System.nanoTime()-end<0)boundedWait(end);
        if(!live()||acknowledged!=requested){fail();return false;}return true;
    }
    boolean awaitLocalExit(long milliseconds)throws InterruptedException {
        if(milliseconds<1||milliseconds>12000)throw new IllegalArgumentException("Exit bound");
        if(!process.waitFor(milliseconds,TimeUnit.MILLISECONDS))return false;
        synchronized(this){
            long end=System.nanoTime()+500_000_000L;
            while((!outDone||!errorDone)&&System.nanoTime()-end<0)wait(10);
            return outDone&&errorDone;
        }
    }
    synchronized int pid(){return rootPid;}
    synchronized boolean normalLocalExit(){
        return !process.isAlive()&&outDone&&errorDone&&!failed&&ready&&released&&stopSent&&process.exitValue()==0;
    }
    // No destroy(), signals, automatic restart, command replay or ownership TSV.
}

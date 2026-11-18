package dev.ody.pikmincontrol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Holds a launched observer's lifetime pipe, not gameplay or GPS ownership.
 * Local process exit alone is NOT proof that a Root child has exited. */
final class EmulatorObserverSession {
    private final Process process;
    private final int gamePid;
    private int observerPid,lines;
    private boolean ready,failed,completed,outDone,errorDone,stopSent;
    EmulatorObserverSession(Process process,int gamePid){
        if(process==null||gamePid<=0)throw new IllegalArgumentException("Missing observer process");
        this.process=process;this.gamePid=gamePid;
        drain(process.getInputStream(),false);drain(process.getErrorStream(),true);
    }
    private void drain(InputStream input,boolean error){
        Thread reader=new Thread(()->{
            try(InputStream in=input){
                ByteArrayOutputStream line=new ByteArrayOutputStream();int total=0,b;
                while((b=in.read())!=-1){
                    if(++total>2048||b==0||b=='\r'){fail();continue;}
                    if(b=='\n'){
                        String text=new String(line.toByteArray(),StandardCharsets.UTF_8);line.reset();
                        if(error)fail();else accept(text);
                    }else if(line.size()<512)line.write(b);else fail();
                }
                if(line.size()!=0)fail();
            }catch(Exception errorRead){fail();}
            finally{synchronized(this){if(error)errorDone=true;else outDone=true;notifyAll();}}
        },error?"observer-stderr":"observer-stdout");reader.setDaemon(true);reader.start();
    }
    private synchronized void accept(String line){
        if(failed)return;
        try{
            if(lines==0){
                if(!line.matches("@root-observer-pid\t[1-9][0-9]*"))throw new IOException();
                observerPid=Integer.parseInt(line.substring(line.indexOf('\t')+1));
                if(observerPid<=0||observerPid==gamePid)throw new IOException();
            }else if(lines==1){if(!line.equals("@root-observer-game\t"+gamePid))throw new IOException();}
            else if(lines==2){if(!line.equals("@root-observer-control\tstdin-owner"))throw new IOException();ready=true;}
            else if(lines==3){
                if(!line.equals("PASS Root observation producer completed bounded lifetime; NOT OwnerEnvironment or gameplay"))throw new IOException();
                completed=true;
            }else throw new IOException();
            lines++;notifyAll();
        }catch(Exception malformed){fail();}
    }
    private synchronized void fail(){failed=true;requestStop();notifyAll();}
    synchronized void requestStop(){
        if(stopSent)return;stopSent=true;
        try{process.getOutputStream().close();}catch(IOException failure){failed=true;}
    }
    synchronized boolean awaitReady(long milliseconds)throws InterruptedException {
        if(milliseconds<1||milliseconds>12000)throw new IllegalArgumentException("Readiness bound");
        long end=System.nanoTime()+milliseconds*1_000_000L;
        while(!ready&&!failed&&!outDone&&process.isAlive()){
            long remaining=end-System.nanoTime();if(remaining<=0)break;
            wait(Math.max(1,Math.min(100,remaining/1_000_000L)));
        }
        return ready&&!failed&&process.isAlive()&&!stopSent;
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
    synchronized int pid(){return observerPid;}
    synchronized boolean normalLocalExit(){return !process.isAlive()&&outDone&&errorDone&&!failed&&ready&&completed&&lines==4&&process.exitValue()==0;}
    // A caller must additionally prove the exact reported Root PID absent.
    // No destroy(), destroyForcibly(), signal, automatic restart or file replay.
}

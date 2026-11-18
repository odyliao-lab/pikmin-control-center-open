package dev.ody.pikmincontrol;
import java.io.IOException;

/** One process-local owner; daily/checkpoint outcome recovery stays independent.
 * Unknown launch/exit keeps the original handle; never starts another actor. */
final class EmulatorGpsFenceCoordinator {
    interface Ports { Process launch(String nonce)throws Exception;boolean rootAbsent(int pid)throws Exception; }
    interface Effect { void run()throws Exception; }
    private Process pending;private EmulatorGpsFenceSession current;private boolean executing,launchIntent;
    synchronized void execute(Ports ports,String nonce,int gamePid,Effect effect)throws Exception{
        if(ports==null||effect==null||executing||launchIntent||pending!=null||current!=null)throw new IOException("GPS fence session retained/busy");
        if(nonce==null||!nonce.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")||gamePid<=0)throw new IllegalArgumentException("GPS identity");
        executing=true;
        try{
            launchIntent=true;pending=ports.launch(nonce);if(pending==null)throw new IOException("GPS launch handle unavailable");
            current=new EmulatorGpsFenceSession(pending,nonce,gamePid);pending=null;
            if(!current.awaitReady(12000)||!current.confirmHeld(3000))throw new IOException("GPS fence unconfirmed; no effect/retry");
            effect.run(); // trusted callsite must also check account/PID/namespace
        }finally{
            try{stop(ports);}finally{executing=false;}
        }
    }
    synchronized void stop(Ports ports)throws Exception{
        if(current==null){
            if(pending!=null){try{pending.getOutputStream().close();}catch(IOException ignored){}
                throw new IOException("GPS launch handle retained without identity");}
            if(launchIntent)throw new IOException("GPS launch intent retained without a process handle");
            return;
        }
        current.requestStop();
        if(!current.awaitLocalExit(6000))throw new IOException("GPS actor exit pending; same handle retained");
        int pid=current.pid();
        if(pid<=0||!ports.rootAbsent(pid))throw new IOException("Root GPS actor exit unconfirmed; retained session");
        boolean normal=current.normalLocalExit();current=null;launchIntent=false;
        if(!normal)throw new IOException("GPS actor failed; exit observed, no automatic retry");
    }
    synchronized boolean retained(){return launchIntent||current!=null||pending!=null;}
}

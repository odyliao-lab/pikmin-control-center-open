package dev.ody.pccbridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;

/** Lifetime connection only; input bytes are NOT gameplay commands. Any byte,
 * EOF or I/O failure ends observation. Never acquires/replays owner/GPS work. */
final class ObserverControl {
    private final AtomicInteger state=new AtomicInteger(); // 0 open, 1 closed, 2 I/O failure
    private final InputStream input;
    private boolean started;
    ObserverControl(InputStream input){
        if(input==null)throw new IllegalArgumentException("Missing observation lifetime pipe");
        this.input=input;
    }
    synchronized void start(){
        if(started)throw new IllegalStateException("Lifetime reader already started");
        started=true;
        Thread reader=new Thread(()->{
            try { input.read();state.set(1); }
            catch(IOException failure){state.set(2);}
            catch(RuntimeException failure){state.set(2);}
        },"root-observer-lifetime");
        reader.setDaemon(true);
        reader.start();
    }
    boolean open()throws IOException {
        int current=state.get();
        if(current==2)throw new IOException("Observation lifetime pipe failed");
        return current==0;
    }
}

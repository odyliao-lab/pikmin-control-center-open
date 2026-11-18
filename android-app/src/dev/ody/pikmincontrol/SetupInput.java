package dev.ody.pikmincontrol;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Bounds SAF provider latency as well as bytes; a stuck provider cannot spawn unlimited readers. */
final class SetupInput {
    private static final AtomicBoolean reading=new AtomicBoolean();
    static byte[] read(Callable<InputStream> open,int limit,long timeout) throws Exception {
        if(!reading.compareAndSet(false,true))throw new IOException("上次檔案來源仍未回應，請稍後或改用本機檔案");
        AtomicReference<InputStream> stream=new AtomicReference<>();
        AtomicBoolean entered=new AtomicBoolean();
        FutureTask<byte[]> task=new FutureTask<>(()->{
            entered.set(true);
            try(InputStream in=open.call()){
                stream.set(in);if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("已取消");
                if(in==null)throw new IOException("無法開啟部署包");return SetupBundle.read(in,limit);
            }finally{stream.set(null);reading.set(false);}
        });
        Thread reader=new Thread(()->{try{task.run();}finally{if(!entered.get())reading.set(false);}},"setup-document-read");reader.setDaemon(true);reader.start();
        try{return task.get(timeout,TimeUnit.MILLISECONDS);}
        catch(TimeoutException|InterruptedException e){
            task.cancel(true);Thread closer=new Thread(()->{try{InputStream in=stream.get();if(in!=null)in.close();}catch(IOException ignored){}},"setup-document-close");closer.setDaemon(true);closer.start();
            if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new IOException("讀取部署包逾時／取消，未安裝；請改用已下載到手機的檔案");
        }catch(ExecutionException e){throw new IOException("讀取部署包失敗："+e.getCause().getMessage(),e.getCause());}
    }
}

package dev.ody.pikmincontrol;

import java.io.*;
import java.util.concurrent.*;

public final class SetupInputTest {
    public static void main(String[] args) throws Exception {
        if(SetupInput.read(()->new ByteArrayInputStream(new byte[]{4}),10,1000)[0]!=4)throw new AssertionError();
        boolean rejected=false;try{SetupInput.read(()->new ByteArrayInputStream(new byte[11]),10,1000);}catch(IOException e){rejected=true;}if(!rejected)throw new AssertionError();
        CountDownLatch opened=new CountDownLatch(1),closed=new CountDownLatch(1);
        long start=System.nanoTime();rejected=false;
        try{SetupInput.read(()->new InputStream(){public int read() throws IOException{opened.countDown();try{closed.await();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}return -1;}public void close(){closed.countDown();}},10,100);}catch(IOException e){rejected=true;}
        if(!rejected||(System.nanoTime()-start)/1000000>1500)throw new AssertionError("deadline");
        if(!closed.await(1,TimeUnit.SECONDS))throw new AssertionError("stream not closed");
        boolean retried=false;
        for(int i=0;i<100&&!retried;i++){try{retried=SetupInput.read(()->new ByteArrayInputStream(new byte[0]),10,1000).length==0;}catch(IOException e){Thread.sleep(10);}}
        if(!retried)throw new AssertionError("retry still blocked");
        System.out.println("PASS SetupInput: 5 size, deadline, close and retry checks");
    }
}

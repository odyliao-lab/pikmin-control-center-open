package dev.ody.pikmincontrol;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory URL transport: never connects to the update service. */
public final class UpdateClientDeadlineTest {
    static final AtomicLong now=new AtomicLong();
    static long headerMs,bodyMs,eofMs;
    static boolean cancel,disconnected,redirect;
    static int connects,connectTimeout,readTimeout;
    static final UpdateClient.Observer observer=new UpdateClient.Observer(){
        public boolean cancelled(){return cancel;}
        public void progress(long received,long total){}
    };
    static class Connection extends HttpURLConnection {
        Connection(URL url){super(url);}
        public void connect(){connects++;}
        public void disconnect(){disconnected=true;}
        public boolean usingProxy(){return false;}
        public int getResponseCode(){connect();connectTimeout=getConnectTimeout();now.addAndGet(headerMs*1_000_000);return redirect?302:200;}
        public String getHeaderField(String key){return "Location".equals(key)?"https://github.com/next":null;}
        public long getContentLengthLong(){return 1;}
        public InputStream getInputStream(){return new InputStream(){
            boolean sent;
            public int read(){throw new AssertionError("bulk read expected");}
            public int read(byte[] b,int off,int len){
                readTimeout=getReadTimeout();
                now.addAndGet((sent?eofMs:bodyMs)*1_000_000);
                if(sent)return -1;
                sent=true;b[off]=7;return 1;
            }
        };}
    }
    static void reset(){now.set(0);headerMs=bodyMs=eofMs=0;cancel=redirect=disconnected=false;connects=connectTimeout=readTimeout=0;}
    static void run(long budget) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        UpdateClient.read("https://github.com/test",out,1,1,observer,budget,now::get);
        if(out.size()!=1||!disconnected)throw new AssertionError("payload or cleanup");
    }
    static void timeout() throws Exception {
        try{run(20);throw new AssertionError("late response accepted");}
        catch(SocketTimeoutException expected){if(!disconnected)throw new AssertionError("not disconnected");}
    }
    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol)?new URLStreamHandler(){
            protected URLConnection openConnection(URL u){return new Connection(u);}
        }:null);
        reset();run(20);if(connectTimeout!=20||readTimeout!=20)throw new AssertionError("budget not applied");
        reset();headerMs=5;bodyMs=5;run(20);if(readTimeout!=10)throw new AssertionError("remaining budget not applied");
        reset();headerMs=20;timeout();
        reset();bodyMs=20;timeout();
        reset();eofMs=100;timeout();
        reset();headerMs=5;bodyMs=5;eofMs=10;timeout();
        reset();redirect=true;headerMs=11;timeout();if(connects!=2)throw new AssertionError("redirect resets deadline");
        reset();cancel=true;
        try{run(20);throw new AssertionError("cancel ignored");}catch(InterruptedIOException expected){if(connects!=0)throw new AssertionError("connected after cancel");}
        reset();Thread.currentThread().interrupt();
        try{run(20);throw new AssertionError("interrupt ignored");}catch(InterruptedIOException expected){}finally{Thread.interrupted();}
        System.out.println("PASS UpdateClientDeadline: 9 offline response/body/EOF/redirect/cancellation cases");
    }
}

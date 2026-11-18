package dev.ody.pikmincontrol;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class DisplayReadCoalescerTest {
    private static void check(boolean ok) {if(!ok)throw new AssertionError();}
    private static BoundedCommand.Result result(String text) {return new BoundedCommand.Result(text,true,false,false);}
    private static <T> T get(Future<T> f)throws Exception{return f.get(3,TimeUnit.SECONDS);}
    public static void main(String[] args)throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(4);
        try {
            DisplayReadCoalescer c=new DisplayReadCoalescer();AtomicInteger calls=new AtomicInteger();
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),waiting=new CountDownLatch(1);
            Future<BoundedCommand.Result> owner=pool.submit(()->c.read(DisplayReadCoalescer.Key.RETURN,()->{
                calls.incrementAndGet();entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new IOException();return result("first");}));
            check(entered.await(3,TimeUnit.SECONDS));
            Future<BoundedCommand.Result> join=pool.submit(()->{waiting.countDown();return c.read(DisplayReadCoalescer.Key.RETURN,()->{
                calls.incrementAndGet();return result("unexpected");});});
            check(waiting.await(3,TimeUnit.SECONDS));
            // A second key must finish even while the first root read is still blocked.
            Future<BoundedCommand.Result> other=pool.submit(()->c.read(DisplayReadCoalescer.Key.NECTAR,()->result("other")));
            check(get(other).text.equals("other"));
            awaitShared(calls,join);
            release.countDown();check(get(owner)==get(join));check(calls.get()==1);
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->{calls.incrementAndGet();return result("new");}).text.equals("new"));
            check(calls.get()==2); // Completed observations are never cached.

            CountDownLatch in2=new CountDownLatch(1),out2=new CountDownLatch(1);
            Future<BoundedCommand.Result> old=pool.submit(()->c.read(DisplayReadCoalescer.Key.RETURN,()->{
                in2.countDown();check(out2.await(3,TimeUnit.SECONDS));return result("old PID");}));
            check(in2.await(3,TimeUnit.SECONDS));c.invalidate();
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->result("new PID")).text.equals("new PID"));
            out2.countDown();expectInvalid(old);
            try {c.read(DisplayReadCoalescer.Key.RETURN,()->{throw new IOException("read failed");});throw new AssertionError();}
            catch(IOException expected){check(expected.getMessage().equals("read failed"));}
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->result("retry explicit")).ok);
            BoundedCommand.Result truncated=new BoundedCommand.Result("partial",true,false,true);
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->truncated)==truncated);
            try {ProbeSnapshot.checkedSections(truncated,1);throw new AssertionError();}catch(IllegalStateException expected){}
            c.enabled(false);
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->result("bypass")).text.equals("bypass"));
            check(c.read(DisplayReadCoalescer.Key.RETURN,()->result("bypass2")).text.equals("bypass2"));
            System.out.println("PASS DisplayReadCoalescer: in-flight only, independent keys, invalidation, error and rollback; no Android/root performance claim");
        } finally {pool.shutdownNow();}
    }
    private static void awaitShared(AtomicInteger calls,Future<?> waiter)throws Exception {
        // Counter is published exactly after the coalescer has joined the flight.
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(System.nanoTime()<end) {
            if(RootQueryStats.summary().contains("重疊畫面查詢合併：1 次"))return;
            if(waiter.isDone()||calls.get()!=1)throw new AssertionError("did not join");
            Thread.yield();
        }
        throw new AssertionError("join deadline");
    }
    private static void expectInvalid(Future<?> f)throws Exception {
        try {get(f);throw new AssertionError();}catch(ExecutionException e){check(e.getCause() instanceof IOException);}
    }
}

package dev.ody.pikmincontrol;

import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/**
 * Tests the actual selected production GpsWorkLock.java against atomic preferences.
 * Holding its class monitor models an admitted operation, not real root latency.
 */
public final class GpsOwnerObservationHarness {
    private static final long WAIT_SECONDS = 5;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("pikmin_gps_work_lock", Context.MODE_PRIVATE);
    }
    private static void seed(Context c, String owner, long touched) {
        prefs(c).edit().putString("owner", owner).putLong("touched_ms", touched).commit();
    }
    private static void denied(Runnable action, String message) {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError(message);
    }
    private static Thread thread(String name, AtomicReference<Throwable> failure, Runnable action) {
        Thread result = new Thread(() -> {
            try { action.run(); } catch (Throwable e) { failure.compareAndSet(null, e); }
        }, name);
        result.setDaemon(true);
        return result;
    }
    private static void await(CountDownLatch latch, String message) {
        try { check(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), message); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    private static void blocked(Thread thread) {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (thread.getState() != Thread.State.BLOCKED && thread.isAlive() && System.nanoTime() < end) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        check(thread.getState() == Thread.State.BLOCKED, thread.getName() + " must retain monitor barrier");
    }
    private static void joined(Thread... threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            check(!thread.isAlive(), thread.getName() + " did not finish");
        }
    }
    private static void noFailure(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
    }
    private static void modifier(String name, boolean synchronizedExpected, Class<?>... args) throws Exception {
        boolean actual = Modifier.isSynchronized(GpsWorkLock.class.getDeclaredMethod(name, args).getModifiers());
        check(actual == synchronizedExpected, name + " synchronized modifier changed");
    }
    private static void monitorAndAdmission() throws Exception {
        Context c = new Context();
        GpsWorkLock.acquire(c, GpsWorkLock.BATCH);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), observed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread holder = thread("fixture-monitor-holder", failure, () -> {
            synchronized (GpsWorkLock.class) { entered.countDown(); await(release, "holder release timed out"); }
        });
        Thread display = thread("actual-advisory-reader", failure, () -> {
            try {
                check(GpsWorkLock.observedHeldBy(c, GpsWorkLock.BATCH), "display must see current owner");
                check(GpsWorkLock.observedAnyHeld(c), "display must see held lease");
            } finally { observed.countDown(); }
        });
        Thread safety = thread("actual-safety-reader", failure, () ->
            check(GpsWorkLock.heldBy(c, GpsWorkLock.BATCH), "safety reader lost owner"));
        Thread contender = thread("actual-competing-acquire", failure, () ->
            denied(() -> GpsWorkLock.acquire(c, GpsWorkLock.FLOWER_FARM), "other owner acquired fresh lease"));
        Thread installer = thread("actual-installer-admission", failure, () ->
            denied(() -> GpsWorkLock.requireInstallIdle(c), "installer admitted while owner active"));
        try {
            holder.start(); await(entered, "monitor holder did not start");
            display.start(); safety.start(); contender.start(); installer.start();
            await(observed, "advisory read waited for class monitor");
            check(release.getCount() == 1, "fixture released before advisory completion");
            noFailure(failure);
            blocked(safety); blocked(contender); blocked(installer);
        } finally {
            release.countDown();
            joined(holder, display, safety, contender, installer);
        }
        noFailure(failure);
        GpsWorkLock.release(c, GpsWorkLock.FLOWER_FARM);
        check(GpsWorkLock.observedHeldBy(c, GpsWorkLock.BATCH), "wrong-owner release cleared lease");
        GpsWorkLock.release(c, GpsWorkLock.BATCH);
        check(!GpsWorkLock.observedAnyHeld(c), "owner release did not clear lease");
        GpsWorkLock.requireInstallIdle(c);
        GpsWorkLock.acquire(c, GpsWorkLock.FLOWER_FARM);
        GpsWorkLock.release(c, GpsWorkLock.FLOWER_FARM);
    }
    private static void snapshotAndTimeSemantics() {
        Context c = new Context(); SharedPreferences p = prefs(c);
        check(!GpsWorkLock.observedAnyHeld(c), "empty preferences appear occupied");
        long now = System.currentTimeMillis();
        for (long touched : new long[] { now, now - 600_000L, now + 600_000L }) {
            seed(c, GpsWorkLock.BATCH, touched);
            boolean held = GpsWorkLock.heldBy(c, GpsWorkLock.BATCH);
            boolean any = GpsWorkLock.anyHeld(c);
            p.resetReads();
            check(GpsWorkLock.observedHeldBy(c, GpsWorkLock.BATCH) == held, "heldBy time semantics changed");
            check(p.allReads() == 1 && p.scalarReads() == 0, "heldBy must use exactly one getAll snapshot");
            p.resetReads();
            check(GpsWorkLock.observedAnyHeld(c) == any, "anyHeld time semantics changed");
            check(p.allReads() == 1 && p.scalarReads() == 0, "anyHeld must use exactly one getAll snapshot");
        }
        seed(c, "old", 1);
        SharedPreferences.Editor edit = p.edit().putString("owner", "new").putLong("touched_ms", 2);
        check("old".equals(p.getAll().get("owner")), "fixture editor mutated before commit");
        edit.commit();
        Map<String, ?> snapshot = p.getAll();
        check("new".equals(snapshot.get("owner")) && Long.valueOf(2).equals(snapshot.get("touched_ms")), "fixture commit was incomplete");
        seed(c, "later", 3);
        check("new".equals(snapshot.get("owner")), "getAll returned live map");
    }
    private static void atomicFixture() throws Exception {
        Context c = new Context(); SharedPreferences p = prefs(c); seed(c, "a", 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = thread("atomic-writer-a", failure, () -> {
            await(start, "start"); for (int i=0; i<2000; i++) seed(c, "a", 1);
        });
        Thread second = thread("atomic-writer-b", failure, () -> {
            await(start, "start"); for (int i=0; i<2000; i++) seed(c, "b", 2);
        });
        Thread reader = thread("atomic-snapshot-reader", failure, () -> {
            await(start, "start");
            for (int i=0; i<4000; i++) {
                Map<String, ?> snapshot = p.getAll();
                check(("a".equals(snapshot.get("owner")) && Long.valueOf(1).equals(snapshot.get("touched_ms")))
                    || ("b".equals(snapshot.get("owner")) && Long.valueOf(2).equals(snapshot.get("touched_ms"))),
                    "snapshot mixed two preference transactions");
            }
        });
        first.start(); second.start(); reader.start(); start.countDown();
        joined(first, second, reader); noFailure(failure);
    }
    public static void main(String[] args) throws Exception {
        modifier("observedHeldBy", false, Context.class, String.class);
        modifier("observedAnyHeld", false, Context.class);
        modifier("heldBy", true, Context.class, String.class);
        modifier("anyHeld", true, Context.class);
        modifier("acquire", true, Context.class, String.class);
        modifier("acquireFarmRecovery", true, Context.class);
        modifier("touch", true, Context.class, String.class);
        modifier("release", true, Context.class, String.class);
        modifier("requireInstallIdle", true, Context.class);
        modifier("requireCoreRecoveryIdle", true, Context.class);
        monitorAndAdmission(); snapshotAndTimeSemantics(); atomicFixture();
        System.out.println("PASS actual GpsWorkLock advisory read completes while class monitor held");
        System.out.println("PASS actual safety reader/acquire/install barriers and wrong-owner release preserved");
        System.out.println("PASS single atomic preference snapshot; existing fresh/stale/future semantics preserved");
        System.out.println("scope=HOST monitor and preference contract only; no Android UI, root, device, or latency claim");
    }
}

// Stub dependencies only; GpsWorkLock itself is compiled from the selected product source.
final class CoreInstallWizard { static boolean preparing(Context c) { return false; } }
final class UpdatePreparation { static boolean blocking(Context c) { return false; } }
final class RouteSession { static boolean pending(Context c) { return false; } }
final class CoreUpdater { static boolean running; static boolean pending(Context c) { return false; } }
final class SetupDeployment { static boolean pending(Context c) { return false; } }
final class UpdateInstaller { static boolean pending(Context c) { return false; } }
final class WorkStatus {
    static boolean stoppingAll;
    static boolean blocked(Context c) { return false; }
    static String allError(Context c) { return ""; }
    static String state(Context c, String owner) { return ""; }
}
final class WorkProgress { static boolean active(String state) { return false; } }
final class DispatchBatchService { static boolean isRunning() { return false; } }
final class FlowerFarmService { static boolean isRunning() { return false; } }
final class NearbyWorkState { static boolean ownedBy(Context c, String owner) { return false; } }
final class FarmRecovery {
    static void persist(BooleanSupplier save) {
        if (!save.getAsBoolean()) throw new IllegalStateException("fixture persistence failed");
    }
}

package dev.ody.pikmincontrol;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded single-worker queue with coalescing. UI reads last completed snapshot only. */
final class StepOperations {
    static final int CAPACITY = 8;

    private final StepEngine engine;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Object gate = new Object();
    private final ArrayDeque<Item> pending = new ArrayDeque<Item>();
    private boolean draining;
    private volatile StepView last;
    private volatile long generation;
    private volatile long lastSubmittedEpoch;

    StepOperations(StepEngine engine) {
        this(engine, Executors.newSingleThreadExecutor(new ThreadFactory() {
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "step-ops");
                thread.setDaemon(true);
                return thread;
            }
        }), true);
    }

    StepOperations(StepEngine engine, ExecutorService executor, boolean ownsExecutor) {
        this.engine = engine;
        this.executor = executor;
        this.ownsExecutor = ownsExecutor;
        this.last = engine.snapshot();
    }

    StepView latest() { return last; }

    long generation() { return generation; }

    long lastSubmittedEpoch() { return lastSubmittedEpoch; }

    int queued() {
        synchronized (gate) { return pending.size(); }
    }

    boolean enqueue(final Runnable task) {
        return enqueue("op", task);
    }

    boolean enqueue(String kind, final Runnable task) {
        if (closed.get() || task == null) return false;
        String key = kind == null || kind.length() == 0 ? "op" : kind;
        synchronized (gate) {
            if (closed.get()) return false;
            long epoch = engine.commandEpoch();
            lastSubmittedEpoch = epoch;
            Iterator<Item> it = pending.iterator();
            while (it.hasNext()) {
                if (key.equals(it.next().kind)) it.remove();
            }
            if (pending.size() >= CAPACITY) return false;
            pending.addLast(new Item(key, epoch, ++generation, task));
            if (!draining) {
                draining = true;
                try {
                    executor.execute(new Runnable() {
                        public void run() { drain(); }
                    });
                } catch (RejectedExecutionException rejected) {
                    pending.removeLast();
                    draining = false;
                    return false;
                }
            }
            return true;
        }
    }

    private void drain() {
        while (true) {
            Item item;
            synchronized (gate) {
                item = pending.pollFirst();
                if (item == null) {
                    draining = false;
                    return;
                }
            }
            if (closed.get()) continue;
            if (!engine.epochCurrent(item.epoch)) {
                last = engine.snapshot();
                continue;
            }
            try { item.task.run(); }
            finally {
                StepView snap = engine.snapshot();
                if (snap.detail.length() == 0 && last != null && last.detail.length() > 0
                        && last.opKind.length() > 0 && !StepView.STORE_ERROR.equals(snap.phase)) {
                    last = new StepView(snap.phase, last.detail, snap.allSourcesToday, snap.thisAppToday,
                            snap.sessionConfirmed, snap.target, snap.desiredRunning, snap.disclosed, snap.extremeRate,
                            snap.pending, snap.writerOwner, snap.logs, snap.previewPlans, snap.previewWarning, last.opKind,
                            last.lastOpPhase, last.lastOpKind, last.lastOpDetail);
                } else last = snap;
            }
        }
    }

    void close() {
        closed.set(true);
        generation++;
        synchronized (gate) { pending.clear(); }
        if (ownsExecutor) {
            executor.shutdownNow();
            try { executor.awaitTermination(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class Item {
        final String kind;
        final long epoch;
        final long generation;
        final Runnable task;
        Item(String kind, long epoch, long generation, Runnable task) {
            this.kind = kind;
            this.epoch = epoch;
            this.generation = generation;
            this.task = task;
        }
    }
}

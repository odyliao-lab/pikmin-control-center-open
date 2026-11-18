package dev.ody.pikmincontrol;

final class StepWorkLock {
    static final String BACKGROUND = "background";
    static final String MANUAL = "manual";
    static final String CATCH_UP = "catch-up";

    private final StepCoordinator coordinator;

    StepWorkLock(StepStore store) { this.coordinator = StepCoordinator.of(store); }

    boolean tryAcquire(String owner) { return coordinator.tryAcquire(owner) != null; }

    StepLockToken acquireToken(String role) { return coordinator.tryAcquire(role); }

    void release(String owner) { coordinator.releaseIfRole(owner); }

    void release(StepLockToken token) { coordinator.release(token); }

    boolean heldBy(String owner) { return coordinator.heldBy(owner); }

    String owner() { return coordinator.currentRole(); }

    boolean held() { return coordinator.held(); }
}

final class MemoryStepStore implements StepStore {
    private String snapshot;
    int saves;

    public Object transactionLock() { return this; }

    public synchronized StepLoadResult load() {
        if (snapshot == null) return StepLoadResult.missing();
        if (snapshot.isEmpty()) return StepLoadResult.unreadable("empty-file");
        return StepLoadResult.valid(snapshot);
    }

    public synchronized void save(String snapshot) {
        String next = snapshot == null ? "" : snapshot;
        if (next.length() > StepCoordinator.MAX_SNAPSHOT_CHARS)
            throw new StepStoreException("snapshot-too-large");
        this.snapshot = next;
        saves++;
    }
}

/** Two factory-shaped store views share one backing snapshot and one transaction lock. */
final class SharedMemoryStore {
    private final Object lock = new Object();
    private String snapshot;

    StepStore view() { return new View(); }

    private final class View implements StepStore {
        public Object transactionLock() { return SharedMemoryStore.this.lock; }

        public StepLoadResult load() {
            synchronized (SharedMemoryStore.this.lock) {
                if (snapshot == null) return StepLoadResult.missing();
                if (snapshot.isEmpty()) return StepLoadResult.unreadable("empty-file");
                return StepLoadResult.valid(snapshot);
            }
        }

        public void save(String next) {
            synchronized (SharedMemoryStore.this.lock) {
                String value = next == null ? "" : next;
                if (value.length() > StepCoordinator.MAX_SNAPSHOT_CHARS)
                    throw new StepStoreException("snapshot-too-large");
                snapshot = value;
            }
        }
    }
}

final class FilteringStepStore implements StepStore {
    private final StepStore inner;
    boolean failPrepared;
    boolean failConfirmed;
    boolean failStop;
    boolean unreadable;
    boolean failAll;

    FilteringStepStore(StepStore inner) { this.inner = inner; }

    public Object transactionLock() { return inner.transactionLock(); }

    public StepLoadResult load() {
        if (unreadable) return StepLoadResult.unreadable("io");
        return inner.load();
    }

    public void save(String snapshot) {
        if (failAll) throw new StepStoreException("save-failed");
        if (failPrepared && snapshot != null && snapshot.contains("\tPREPARED\t"))
            throw new StepStoreException("prepared-save");
        if (failConfirmed && snapshot != null && snapshot.contains("\tCONFIRMED\t") && !snapshot.contains("\tPREPARED\t"))
            throw new StepStoreException("confirmed-save");
        if (failStop && snapshot != null && snapshot.contains("desiredRunning=0"))
            throw new StepStoreException("stop-save");
        inner.save(snapshot);
    }
}

package dev.ody.pikmincontrol;

import java.util.Collections;
import java.util.WeakHashMap;

/**
 * Process-wide (per backing lock) step repository and writer coordinator.
 * Transaction lock is only for short durable state changes; callers must not
 * hold it across Health Connect I/O.
 */
final class StepCoordinator {
    private static final WeakHashMap<Object, StepCoordinator> BY_LOCK = new WeakHashMap<Object, StepCoordinator>();

    private final Object txn;
    private final StepStore store;
    private volatile String storageFailure = "";

    String storageFailure() { return storageFailure; }
    void recordStorageFailure(String detail) { storageFailure = detail; }
    void clearStorageFailureAfterStop() { storageFailure = ""; }

    static StepCoordinator of(StepStore store) {
        Object key = store.transactionLock();
        synchronized (BY_LOCK) {
            StepCoordinator existing = BY_LOCK.get(key);
            if (existing != null) return existing;
            StepCoordinator created = new StepCoordinator(store);
            BY_LOCK.put(key, created);
            return created;
        }
    }

    private StepCoordinator(StepStore store) {
        this.store = store;
        this.txn = store.transactionLock();
    }

    Object txnLock() { return txn; }

    StepState read() {
        StepLoadResult loaded = store.load();
        if (loaded.isUnreadable()) throw new StepStoreException("unreadable:" + loaded.error);
        if (loaded.isMissing()) return new StepState();
        return StepState.decode(loaded.raw);
    }

    static final int MAX_SNAPSHOT_CHARS = 900_000;

    void persist(StepState state) {
        try { persistChecked(state); }
        catch (StepStoreException error) {
            recordStorageFailure("儲存失敗；請重試停止以核對狀態。 " + error.getMessage());
            throw error;
        }
    }

    private void persistChecked(StepState state) {
        String encoded = state.encode();
        if (encoded.length() > MAX_SNAPSHOT_CHARS)
            throw new StepStoreException("snapshot-too-large");
        store.save(encoded);
        StepLoadResult roundtrip = store.load();
        if (!roundtrip.isValid() || !encoded.equals(roundtrip.raw))
            throw new StepStoreException("durable-verify");
    }

    StepLockToken tryAcquire(String role) {
        synchronized (txn) {
            StepState state = read();
            if (state.lockId.length() > 0) return null;
            StepLockToken token = StepLockToken.next(role, state.lockGeneration + 1L);
            state.lockId = token.id;
            state.lockRole = token.role;
            state.lockGeneration = token.generation;
            persist(state);
            return token;
        }
    }

    void release(StepLockToken token) {
        if (token == null) return;
        synchronized (txn) {
            StepState state = read();
            if (token.matches(state.lockId, state.lockGeneration)) {
                state.lockId = "";
                state.lockRole = "";
                state.writerOwner = "";
                persist(state);
            }
        }
    }

    void releaseIfRole(String role) {
        synchronized (txn) {
            StepState state = read();
            if (role != null && role.equals(state.lockRole) && state.lockId.length() > 0) {
                state.lockId = "";
                state.lockRole = "";
                state.writerOwner = "";
                persist(state);
            }
        }
    }

    String currentRole() {
        synchronized (txn) {
            return read().lockRole;
        }
    }

    boolean held() {
        synchronized (txn) {
            return read().lockId.length() > 0;
        }
    }

    boolean heldBy(String role) {
        synchronized (txn) {
            StepState state = read();
            return role != null && role.equals(state.lockRole) && state.lockId.length() > 0;
        }
    }

    StepState applyWriteOutcome(StepLockToken token, StepPlan prepared, StepHealthPort.WriteResult written,
            long wallMs, long elapsedMs) {
        synchronized (txn) {
            StepState state = read();
            StepPlan existing = state.ledger.byIdOrClient(prepared.id, prepared.clientRecordId);
            if (existing == null || !existing.pending()) return state;
            if (written.timeout) {
                state.ledger.replace(existing.withStatus(StepPlan.UNCERTAIN, "", "timeout"));
                state.addLog(new StepLogEntry(wallMs, existing.count, existing.source, "待核對"));
                persist(state);
                return state;
            }
            if (written.failed) {
                if (uncertainWrite(written.kind)) {
                    state.ledger.replace(existing.withStatus(StepPlan.UNCERTAIN, "", written.kind));
                    state.addLog(new StepLogEntry(wallMs, existing.count, existing.source, "待核對"));
                    persist(state);
                    return state;
                }
                state.ledger.replace(existing.withStatus(StepPlan.FAILED, "", written.kind));
                state.addLog(new StepLogEntry(wallMs, existing.count, existing.source, "失敗：" + safeKind(written.kind)));
                persist(state);
                return state;
            }
            state.ledger.replace(existing.withStatus(StepPlan.CONFIRMED, written.healthId, ""));
            state.lastConfirmedEndMs = existing.endMs;
            if (token != null && StepWorkLock.BACKGROUND.equals(token.role))
                state.runCursorMs = Math.max(state.runCursorMs, existing.endMs);
            state.sessionConfirmed += existing.count;
            state.addLog(new StepLogEntry(wallMs, existing.count, existing.source, "Health Connect 寫入成功"));
            state.backoffMs = 0;
            if (state.restStreak < 3)
                state.waitUntilElapsed = Math.min(state.waitUntilElapsed, elapsedMs);
            if (token != null && token.matches(state.lockId, state.lockGeneration)
                    && StepWorkLock.BACKGROUND.equals(token.role) && state.desiredRunning) {
                state.lastSuccessElapsed = elapsedMs;
                state.restStreak++;
            }
            persist(state);
            return state;
        }
    }

    StepState applyReconcile(StepPlan pending, StepHealthPort.LookupResult found, long wallMs, long elapsedMs) {
        synchronized (txn) {
            StepState state = read();
            StepPlan existing = state.ledger.byIdOrClient(pending.id, pending.clientRecordId);
            if (existing == null || !existing.pending()) return state;
            if (found.timeout || found.failed) return state;
            if (found.found) {
                if (!found.payloadMatches(existing)) {
                    state.ledger.replace(existing.withStatus(StepPlan.UNCERTAIN, found.healthId, "payload"));
                    persist(state);
                    return state;
                }
                state.ledger.replace(existing.withStatus(StepPlan.CONFIRMED, found.healthId, ""));
                state.lastConfirmedEndMs = existing.endMs;
                if (state.desiredRunning && StepWorkLock.BACKGROUND.equals(state.lockRole))
                    state.runCursorMs = Math.max(state.runCursorMs, existing.endMs);
                state.sessionConfirmed += existing.count;
                state.addLog(new StepLogEntry(wallMs, existing.count, existing.source, "同 ID 核對已確認"));
                if (state.desiredRunning && StepWorkLock.BACKGROUND.equals(state.lockRole)) {
                    state.lastSuccessElapsed = elapsedMs;
                    state.restStreak++;
                }
                persist(state);
                return state;
            }
            if (StepPlan.PREPARED.equals(existing.status)) {
                state.ledger.replace(existing.withStatus(StepPlan.UNCERTAIN, "", "unconfirmed"));
                persist(state);
            }
            return state;
        }
    }

    private static boolean uncertainWrite(String kind) {
        return "io".equals(kind) || "remote".equals(kind) || "sync".equals(kind);
    }

    private static String safeKind(String kind) {
        if (kind == null || kind.isEmpty()) return "錯誤";
        if (kind.length() > 24) return kind.substring(0, 24);
        return kind;
    }
}

final class StepLockToken {
    final String role;
    final String id;
    final long generation;

    StepLockToken(String role, String id, long generation) {
        this.role = role == null ? "" : role;
        this.id = id == null ? "" : id;
        this.generation = generation;
    }

    static StepLockToken next(String role, long generation) {
        return new StepLockToken(role, "lock-" + generation + "-" + Long.toString(System.nanoTime(), 36), generation);
    }

    boolean matches(String lockId, long generation) {
        return id.length() > 0 && id.equals(lockId) && this.generation == generation;
    }
}

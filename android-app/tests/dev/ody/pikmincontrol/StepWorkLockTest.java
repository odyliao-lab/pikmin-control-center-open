package dev.ody.pikmincontrol;

public final class StepWorkLockTest {
    public static void main(String[] args) {
        MemoryStepStore store = new MemoryStepStore();
        StepWorkLock a = new StepWorkLock(store);
        StepWorkLock b = new StepWorkLock(store);
        if (!a.tryAcquire(StepWorkLock.BACKGROUND)) throw new AssertionError("first acquire");
        if (b.tryAcquire(StepWorkLock.MANUAL)) throw new AssertionError("second writer");
        if (!a.heldBy(StepWorkLock.BACKGROUND)) throw new AssertionError("holder");
        a.release(StepWorkLock.MANUAL);
        if (!a.held()) throw new AssertionError("wrong owner cannot release");
        a.release(StepWorkLock.BACKGROUND);
        if (a.held()) throw new AssertionError("released");
        if (!b.tryAcquire(StepWorkLock.CATCH_UP)) throw new AssertionError("reacquire");
        System.out.println("PASS StepWorkLockTest dedicated lock; not GPS lock");
    }
}

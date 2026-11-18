package dev.ody.pikmincontrol;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StepQueuePreflightTest {
    static int checks;
    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError(label + " (#" + checks + ")");
    }

    static StepEngine engine(MutableStepClock clock, StepStore store, FakeStepHealth health) {
        return new StepEngine(clock, new StepSystemZone("Asia/Taipei"), new FixedStepRng(0.5d), store, health, new StepWorkLock(store));
    }

    public static void main(String[] args) throws Exception {
        undisclosedManualDoesNotWrite();
        unreadManualDoesNotWrite();
        rateLimitStopsCatchUpBatch();
        queueDoesNotBlockCaller();
        System.out.println("PASS StepQueuePreflightTest " + checks + " checks; R3/R5 queue and preflight");
    }

    static void undisclosedManualDoesNotWrite() {
        FakeStepHealth health = new FakeStepHealth();
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = engine(clock, new MemoryStepStore(), health);
        StepView view = StepTestConfirm.confirmManual(engine, 10);
        check(StepView.REJECTED.equals(view.phase), "undisclosed rejected");
        check(health.writes == 0, "no write without disclose");
    }

    static void unreadManualDoesNotWrite() {
        FakeStepHealth health = new FakeStepHealth();
        health.canRead = false;
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = engine(clock, new MemoryStepStore(), health);
        engine.disclose();
        StepView view = StepTestConfirm.confirmManual(engine, 10);
        check(StepView.WAITING_PERMISSION.equals(view.phase) || StepView.REJECTED.equals(view.phase), "unread blocked");
        check(health.writes == 0, "no write without read");
    }

    static void rateLimitStopsCatchUpBatch() {
        FakeStepHealth health = new FakeStepHealth();
        health.maxWrites = 0;
        StepZone zone = new StepSystemZone("Asia/Taipei");
        MutableStepClock clock = new MutableStepClock(zone.toEpochMillis(2024, 1, 1, 12, 0, 8 * 3600), 50_000L);
        StepEngine engine = engine(clock, new MemoryStepStore(), health);
        engine.disclose();
        StepCatchUpRequest request = new StepCatchUpRequest("window", 30, 10, 2024, 1, 1, 10, 0, 10, 9, null, null);
        StepView preview = StepTestConfirm.previewCatchUp(engine, request);
        check(preview.previewPlans.size() >= 2, "batch has multiple plans " + preview.previewPlans.size());
        StepView view = StepTestConfirm.confirmCatchUp(engine, request);
        check("rate-limit".equals(view.opKind) || health.writes <= 1, "rate-limit kind");
        check(health.writes == 1, "did not flush remaining batch writes=" + health.writes);
    }

    static void queueDoesNotBlockCaller() throws Exception {
        FakeStepHealth health = new FakeStepHealth();
        health.writeEntered = new CountDownLatch(1);
        health.writeContinue = new CountDownLatch(1);
        MutableStepClock clock = new MutableStepClock(new StepSystemZone("Asia/Taipei").toEpochMillis(2024, 1, 1, 10, 0, 8 * 3600), 10_000L);
        StepEngine engine = engine(clock, new MemoryStepStore(), health);
        engine.disclose();
        StepOperations ops = new StepOperations(engine);
        final AtomicBoolean callerBlocked = new AtomicBoolean(true);
        long started = System.currentTimeMillis();
        check(!StepView.REJECTED.equals(StepTestConfirm.previewManual(engine, 5).phase), "queue preview");
        boolean accepted = ops.enqueue(new Runnable() {
            public void run() { StepTestConfirm.confirmManual(engine, 5); }
        });
        check(accepted, "enqueued");
        callerBlocked.set(System.currentTimeMillis() - started > 200);
        check(!callerBlocked.get(), "enqueue returned without waiting for HC");
        check(health.writeEntered.await(5, TimeUnit.SECONDS), "write started on worker");
        health.writeContinue.countDown();
        ops.close();
    }
}

package dev.ody.pikmincontrol;

import android.content.Intent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared Activity ↔ Service command identity. Service never invents START;
 * it only continues an already-authorized run bound to epoch + run token.
 */
final class StepWriterControl {
    static final String EXTRA_COMMAND_EPOCH = "dev.ody.pikmincontrol.STEP_COMMAND_EPOCH";
    static final String EXTRA_RUN_TOKEN = "dev.ody.pikmincontrol.STEP_RUN_TOKEN";

    private static final AtomicLong RUN_TOKENS = new AtomicLong();

    static long nextRunToken() {
        return RUN_TOKENS.incrementAndGet();
    }

    static boolean mayLaunchService(StepView afterAuthorize) {
        if (afterAuthorize == null || !afterAuthorize.desiredRunning) return false;
        String phase = afterAuthorize.phase;
        if (StepView.STOPPED.equals(phase)) return false;
        if (StepView.REJECTED.equals(phase)) return false;
        if (StepView.UNSUPPORTED.equals(phase)) return false;
        if (StepView.WAITING_PERMISSION.equals(phase)) return false;
        if (StepView.STORE_ERROR.equals(phase)) return false;
        return true;
    }

    static StepView authorizeStart(StepEngine engine, long boundEpoch) {
        return engine.startBound(boundEpoch);
    }

    static StepView admitWorker(StepEngine engine, Command start) {
        if (start == null || !StepService.START.equals(start.action))
            return engine.snapshot();
        return engine.continueAuthorizedRun(start.commandEpoch);
    }

    static boolean shouldApplyStop(Command stop, long liveEpoch, long liveRun) {
        if (stop == null || !StepService.STOP.equals(stop.action)) return false;
        if (liveEpoch < 0L || liveRun == 0L) return false;
        return stop.commandEpoch == liveEpoch && stop.runToken == liveRun;
    }

    static boolean shouldStopEngine(StepEngine engine, Command stop) {
        return engine != null && stop != null && engine.epochCurrent(stop.commandEpoch)
                && engine.snapshot().desiredRunning;
    }

    static Command startCommand(long epoch, long runToken) {
        return new Command(StepService.START, epoch, runToken);
    }

    static Command stopCommand(long epoch, long runToken) {
        return new Command(StepService.STOP, epoch, runToken);
    }

    static Command fromIntent(Intent intent) {
        if (intent == null) return new Command("", -1L, 0L);
        String action = intent.getAction();
        return new Command(action == null ? "" : action,
                intent.getLongExtra(EXTRA_COMMAND_EPOCH, -1L),
                intent.getLongExtra(EXTRA_RUN_TOKEN, 0L));
    }

    static void applyTo(Intent intent, Command command) {
        if (intent == null || command == null) return;
        intent.setAction(command.action);
        intent.putExtra(EXTRA_COMMAND_EPOCH, command.commandEpoch);
        intent.putExtra(EXTRA_RUN_TOKEN, command.runToken);
    }

    /** Shared by the Android Service and JVM host; never invents a START. */
    static final class Controller {
        private Command current;
        synchronized boolean accept(StepEngine engine, Command command) {
            if (command == null || command.runToken <= 0 || command.commandEpoch < 0
                    || !StepService.START.equals(command.action)
                    || !engine.epochCurrent(command.commandEpoch)
                    || !mayLaunchService(engine.continueAuthorizedRun(command.commandEpoch))) return false;
            // Same epoch is already served; only a new STOP/START generation replaces it.
            if (current != null && current.commandEpoch == command.commandEpoch) return false;
            current = command;
            return true;
        }
        synchronized boolean matches(Command command) {
            return current != null && command != null
                    && current.commandEpoch == command.commandEpoch && current.runToken == command.runToken;
        }
        synchronized boolean finish(Command command, Runnable cleanup) {
            if (!matches(command)) return false;
            current = null;
            if (cleanup != null) cleanup.run();
            return true;
        }
        synchronized StepView launchFailed(StepEngine engine, Command command) {
            return matches(command) ? engine.launchFailed(command.commandEpoch) : engine.snapshot();
        }
        boolean admitted(StepEngine engine, Command command) {
            return matches(command) && mayLaunchService(admitWorker(engine, command))
                    && engine.epochCurrent(command.commandEpoch);
        }
    }

    static final class Command {
        final String action;
        final long commandEpoch;
        final long runToken;

        Command(String action, long commandEpoch, long runToken) {
            this.action = action == null ? "" : action;
            this.commandEpoch = commandEpoch;
            this.runToken = runToken;
        }
    }

    /**
     * JVM host of the Activity START task + Service worker admit/STOP contract.
     * Production Activity/Service call the same static methods.
     */
    static final class Host {
        final StepEngine engine;
        final StepOperations ops;
        final Controller controller = new Controller();
        volatile long liveEpoch = -1L;
        volatile long liveRun;
        volatile long pendingEpoch = -1L;
        volatile long pendingRun;
        volatile boolean running;
        volatile long stoppingRun;
        volatile CountDownLatch afterQueueEpochCheck;
        volatile CountDownLatch afterActivityStart;
        volatile CountDownLatch afterServiceLaunch;
        volatile CountDownLatch beforeWorkerEntry;
        volatile CountDownLatch holdAfterQueue;
        volatile CountDownLatch holdAfterActivity;
        volatile CountDownLatch holdBeforeWorker;

        Host(StepEngine engine, StepOperations ops) {
            this.engine = engine;
            this.ops = ops;
        }

        boolean enqueueUserStart() {
            final long epoch = engine.commandEpoch();
            final long runToken = nextRunToken();
            pendingEpoch = epoch;
            pendingRun = runToken;
            return ops.enqueue("start", new Runnable() {
                public void run() {
                    signal(afterQueueEpochCheck);
                    await(holdAfterQueue);
                    StepView view = authorizeStart(engine, epoch);
                    signal(afterActivityStart);
                    await(holdAfterActivity);
                    if (mayLaunchService(view)) launchWorker(startCommand(epoch, runToken));
                    signal(afterServiceLaunch);
                }
            });
        }

        StepView requestStop() {
            long epoch = liveEpoch >= 0L ? liveEpoch : pendingEpoch;
            if (epoch < 0L) epoch = engine.commandEpoch();
            long run = liveRun != 0L ? liveRun : pendingRun;
            StepView view = engine.stop();
            applyStop(stopCommand(epoch, run));
            return view;
        }

        void launchWorker(final Command start) {
            if (!controller.accept(engine, start)) return;
            liveEpoch = start.commandEpoch;
            liveRun = start.runToken;
            running = true;
            stoppingRun = 0L;
            Thread worker = new Thread(new Runnable() {
                public void run() { workerLoop(start); }
            }, "step-writer-host");
            worker.start();
        }

        void applyStop(Command stop) {
            long epoch = liveEpoch >= 0L ? liveEpoch : pendingEpoch;
            long run = liveRun != 0L ? liveRun : pendingRun;
            if (!shouldApplyStop(stop, epoch, run)) return;
            stoppingRun = stop.runToken;
            if (shouldStopEngine(engine, stop)) engine.stop();
        }

        private void workerLoop(Command start) {
            try {
                signal(beforeWorkerEntry);
                await(holdBeforeWorker);
                if (stoppingRun == start.runToken) return;
                StepView admitted = admitWorker(engine, start);
                if (!mayLaunchService(admitted) || !controller.admitted(engine, start)) return;
                while (stoppingRun != start.runToken && liveRun == start.runToken
                        && controller.matches(start) && engine.epochCurrent(start.commandEpoch)
                        && engine.snapshot().desiredRunning) {
                    engine.tickBound(start.commandEpoch);
                    if (stoppingRun == start.runToken) break;
                    break;
                }
            } finally {
                controller.finish(start, new Runnable() {
                    public void run() { running = false; }
                });
            }
        }

        private static void signal(CountDownLatch latch) {
            if (latch != null) latch.countDown();
        }

        private static void await(CountDownLatch latch) {
            if (latch == null) return;
            try {
                if (!latch.await(20, TimeUnit.SECONDS))
                    throw new IllegalStateException("step writer barrier timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

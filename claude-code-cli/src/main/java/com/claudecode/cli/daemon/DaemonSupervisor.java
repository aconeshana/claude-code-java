package com.claudecode.cli.daemon;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;

final class DaemonSupervisor implements AutoCloseable {

    record LaunchRequest(String kind, String config) {}

    private static final Duration STABLE_RUNTIME = Duration.ofSeconds(60);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
    private static final long MAX_BACKOFF_MS = Duration.ofMinutes(5).toMillis();

    private final Function<LaunchRequest, DaemonWorkerProcess> launcher;
    private final LongConsumer sleeper;
    private final DoubleSupplier jitter;
    private final Consumer<String> log;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile DaemonWorkerProcess currentProcess;
    private volatile Thread supervisorThread;

    DaemonSupervisor() {
        this(request -> {
                try { return JvmDaemonWorkerProcess.launch(request.kind(), request.config()); }
                catch (Exception failure) { throw new IllegalStateException(failure); }
            }, DaemonSupervisor::sleep, Math::random, _ -> {});
    }

    DaemonSupervisor(Function<LaunchRequest, DaemonWorkerProcess> launcher,
                     LongConsumer sleeper, DoubleSupplier jitter,
                     Consumer<String> log) {
        this.launcher = launcher;
        this.sleeper = sleeper;
        this.jitter = jitter;
        this.log = log == null ? _ -> {} : log;
    }

    synchronized void start(String kind, String config) {
        if (supervisorThread != null) return;
        supervisorThread = Thread.startVirtualThread(() -> run(kind, config));
    }

    void run(String kind, String config) {
        int consecutiveCrashes = 0;
        while (!closed.get()) {
            long startedAt = System.nanoTime();
            DaemonWorkerProcess process;
            try {
                process = launcher.apply(new LaunchRequest(kind, config));
            } catch (RuntimeException failure) {
                log.accept("daemon worker launch failed: " + failure.getMessage());
                return;
            }
            currentProcess = process;
            int exitCode;
            try {
                exitCode = process.awaitExit();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            } finally {
                currentProcess = null;
            }
            if (closed.get() || exitCode == 0 || isPermanent(exitCode)) return;
            long runtimeNanos = System.nanoTime() - startedAt;
            if (runtimeNanos >= STABLE_RUNTIME.toNanos()) consecutiveCrashes = 0;
            long delay = backoffMillis(consecutiveCrashes++, jitter.getAsDouble());
            log.accept("daemon worker exited code=" + exitCode + "; retrying in " + delay + "ms");
            sleeper.accept(delay);
        }
    }

    static long backoffMillis(int consecutiveCrashes, double random) {
        int exponent = Math.max(0, Math.min(30, consecutiveCrashes));
        long base = exponent >= 19 ? MAX_BACKOFF_MS
            : Math.min(1_000L << exponent, MAX_BACKOFF_MS);
        double bounded = Math.max(0d, Math.min(1d, random));
        return Math.round(base * (0.5d + bounded));
    }

    private static boolean isPermanent(int exitCode) {
        return exitCode == 1 || exitCode == 2 || exitCode == 78;
    }

    DaemonWorkerProcess currentProcessForTest() {
        return currentProcess;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        DaemonWorkerProcess process = currentProcess;
        if (process != null && process.isAlive()) stopProcess(process);
        Thread thread = supervisorThread;
        if (thread != null) thread.interrupt();
    }

    private void stopProcess(DaemonWorkerProcess process) {
        try {
            if (process.sendShutdown() && process.awaitExit(SHUTDOWN_GRACE)) return;
            process.terminate();
            if (process.awaitExit(SHUTDOWN_GRACE)) return;
            process.kill();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.kill();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}

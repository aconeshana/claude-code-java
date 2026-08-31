package com.claudecode.ui.lanterna.suggest;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/**
 * Single-flight, latest-value-wins runner for input-side background work.
 */
final class LatestTaskRunner implements AutoCloseable {

    @FunctionalInterface
    interface Task {
        void run(BooleanSupplier cancelled);
    }

    private final ExecutorService executor;
    private final long debounceMillis;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Future<?>> current = new AtomicReference<>();

    LatestTaskRunner(String threadName, Duration debounce) {
        debounceMillis = Math.max(0, debounce.toMillis());
        executor = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name(threadName + "-", 0).factory());
    }

    void submit(Task task) {
        long submittedGeneration = generation.incrementAndGet();
        Future<?> previous = current.getAndSet(executor.submit(() -> {
            BooleanSupplier cancelled = () -> Thread.currentThread().isInterrupted()
                || generation.get() != submittedGeneration;
            try {
                if (debounceMillis > 0) Thread.sleep(debounceMillis);
                if (!cancelled.getAsBoolean()) task.run(cancelled);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }));
        if (previous != null) previous.cancel(true);
    }

    /** Invalidates queued/running work without scheduling a replacement. */
    void cancel() {
        generation.incrementAndGet();
        Future<?> running = current.getAndSet(null);
        if (running != null) running.cancel(true);
    }

    boolean awaitIdle(Duration timeout) throws InterruptedException {
        Future<?> snapshot = current.get();
        if (snapshot == null) return true;
        try {
            snapshot.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
            return true;
        } catch (CancellationException | ExecutionException _) {
            return true;
        } catch (TimeoutException _) {
            return false;
        }
    }

    @Override public void close() {
        cancel();
        executor.shutdownNow();
    }
}

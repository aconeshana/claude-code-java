package com.claudecode.ui.lanterna.repl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * One-shot-per-turn interactive idle notification timer.
 */
final class IdlePromptNotifier implements AutoCloseable {
    private final long thresholdMs;
    private final Runnable notification;
    private final BooleanSupplier eligible;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("idle-prompt-hook-", 0).factory());
    private ScheduledFuture<?> pending;
    private boolean userRequestStarted;

    IdlePromptNotifier(long thresholdMs, Runnable notification, BooleanSupplier eligible) {
        this.thresholdMs = Math.max(1, thresholdMs);
        this.notification = notification;
        this.eligible = eligible;
    }

    synchronized void userInteracted() {
        userRequestStarted = true;
        cancelPending();
    }

    synchronized void turnCompleted() {
        cancelPending();
        if (!userRequestStarted) return;
        pending = scheduler.schedule(() -> {
            synchronized (IdlePromptNotifier.this) {
                pending = null;
            }
            if (eligible.getAsBoolean()) notification.run();
        }, thresholdMs, TimeUnit.MILLISECONDS);
    }

    synchronized void cancel() {
        cancelPending();
    }

    private void cancelPending() {
        if (pending != null) pending.cancel(false);
        pending = null;
    }

    @Override
    public synchronized void close() {
        cancelPending();
        scheduler.shutdownNow();
    }
}

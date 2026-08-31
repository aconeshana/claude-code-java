package com.claudecode.tools.cron;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Waits for the scheduled-task file to remain unchanged before reloading it. */
final class CronFileReloadDebouncer implements AutoCloseable {

    private final long stabilityMillis;
    private final Runnable reload;
    private final ScheduledExecutorService executor;
    private ScheduledFuture<?> pending;
    private boolean closed;

    CronFileReloadDebouncer(Duration stability, Runnable reload) {
        this.stabilityMillis = Math.max(0L, stability.toMillis());
        this.reload = reload;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = Thread.ofVirtual().unstarted(runnable);
            thread.setName("cron-file-stability");
            return thread;
        });
    }

    synchronized void changed() {
        if (closed) return;
        if (pending != null) pending.cancel(false);
        pending = executor.schedule(this::runReload,
            stabilityMillis, TimeUnit.MILLISECONDS);
    }

    private void runReload() {
        synchronized (this) {
            if (closed) return;
            pending = null;
        }
        reload.run();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pending != null) pending.cancel(false);
        pending = null;
        executor.shutdownNow();
    }
}

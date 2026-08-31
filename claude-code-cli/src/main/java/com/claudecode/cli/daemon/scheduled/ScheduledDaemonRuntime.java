package com.claudecode.cli.daemon.scheduled;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class ScheduledDaemonRuntime implements AutoCloseable {

    private final ScheduledWorker worker;
    private final ScheduledWorkerStatusWriter statusWriter;
    private final ScheduledExecutorService ticker;
    private final ScheduledExecutorService parentWatchdog;
    private final Consumer<String> errorLog;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    ScheduledDaemonRuntime(
            ScheduledWorker worker,
            ScheduledWorkerStatusWriter statusWriter,
            ScheduledExecutorService ticker,
            ScheduledExecutorService parentWatchdog,
            Consumer<String> errorLog) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.statusWriter = Objects.requireNonNull(statusWriter, "statusWriter");
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        this.parentWatchdog = Objects.requireNonNull(parentWatchdog, "parentWatchdog");
        this.errorLog = errorLog == null ? _ -> { } : errorLog;
    }

    void start(ProcessHandle parent, Duration checkInterval, Duration parentCheckInterval)
            throws IOException {
        statusWriter.write(worker.snapshot());
        ticker.scheduleAtFixedRate(() -> {
            try {
                worker.checkNow(System.currentTimeMillis());
                statusWriter.write(worker.snapshot());
            } catch (Exception failure) {
                errorLog.accept("scheduled worker tick failed: " + failure.getMessage());
            }
        }, checkInterval.toMillis(), checkInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (parent != null) {
            parentWatchdog.scheduleAtFixedRate(() -> {
                if (parent.isAlive()) return;
                errorLog.accept("parent supervisor gone — exiting");
                close();
            }, parentCheckInterval.toMillis(), parentCheckInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        }
    }

    void awaitStop() throws InterruptedException {
        stopped.await();
    }

    boolean isStopped() {
        return stopped.getCount() == 0;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        ticker.shutdownNow();
        parentWatchdog.shutdownNow();
        worker.close();
        writeStatus("scheduled worker status write failed: ");
        stopped.countDown();
    }

    private void writeStatus(String prefix) {
        try {
            statusWriter.write(worker.snapshot());
        } catch (Exception failure) {
            errorLog.accept(prefix + failure.getMessage());
        }
    }
}

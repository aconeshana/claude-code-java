package com.claudecode.tools.tasks;

import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Batches and rate-limits Monitor events before they enter the session queue.
 */
public final class MonitorEventDispatcher implements AutoCloseable {

    private static final int MAX_LINE_CHARS = 500;
    private static final int MAX_BATCH_CHARS = 3_000;
    private static final long BATCH_DELAY_MS = 200;
    private static final int TOKEN_CAPACITY = 10;
    private static final long TOKEN_REFILL_MS = 2_000;
    private static final long FIREHOSE_STOP_MS = 30_000;
    private static final String TRUNCATED = "...(truncated)";
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final String taskId;
    private final String description;
    private final Consumer<String> emitter;
    private final LongSupplier nowMillis;
    private final Runnable stopMonitor;
    private final StringBuilder pending = new StringBuilder();

    private ScheduledFuture<?> pendingFlush;
    private boolean closed;
    private int tokens = TOKEN_CAPACITY;
    private long lastRefillAt;
    private int suppressed;
    private long suppressionStartedAt = -1;
    private long lastSuppressedAt = -1;

    private MonitorEventDispatcher(String taskId, String description,
                                   Consumer<String> emitter, LongSupplier nowMillis,
                                   Runnable stopMonitor) {
        this.taskId = Objects.requireNonNull(taskId);
        this.description = Objects.requireNonNullElse(description, "");
        this.emitter = Objects.requireNonNull(emitter);
        this.nowMillis = Objects.requireNonNull(nowMillis);
        this.stopMonitor = Objects.requireNonNull(stopMonitor);
        this.lastRefillAt = nowMillis.getAsLong();
    }

    public static MonitorEventDispatcher forQueue(String taskId, String description,
                                                   String agentId, MessageQueueManager queue,
                                                   Runnable stopMonitor) {
        Objects.requireNonNull(queue, "queue");
        Consumer<String> emitter = text -> queue.enqueuePendingNotification(new QueuedCommand(
            text, null, "task-notification", QueuePriority.NEXT,
            true, null, false, false, null, null, agentId));
        return new MonitorEventDispatcher(taskId, description, emitter,
            System::currentTimeMillis, stopMonitor);
    }

    static MonitorEventDispatcher forTest(String taskId, String description,
                                          Consumer<String> emitter, LongSupplier nowMillis,
                                          Runnable stopMonitor) {
        return new MonitorEventDispatcher(taskId, description, emitter, nowMillis, stopMonitor);
    }

    public synchronized void accept(String event) {
        if (closed) return;
        String value = Objects.requireNonNullElse(event, "");
        for (String rawLine : value.split("\\n", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (!pending.isEmpty()) pending.append('\n');
            pending.append(truncateLine(line));
        }
        if (!pending.isEmpty() && (pendingFlush == null || pendingFlush.isDone())) {
            pendingFlush = TIMER.schedule(this::flushNow, BATCH_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    synchronized void flushNow() {
        if (pending.isEmpty() || closed) return;
        String event = truncateBatch(pending.toString());
        pending.setLength(0);
        pendingFlush = null;
        emitRateLimited(event);
    }

    public synchronized void emitHousekeeping(String event) {
        if (closed) return;
        flushNow();
        emitter.accept(notification(event));
    }

    private void emitRateLimited(String event) {
        long now = nowMillis.getAsLong();
        refill(now);
        if (tokens > 0) {
            tokens--;
            if (suppressed > 0) {
                emitter.accept(notification("[" + suppressed
                    + " events suppressed — output rate too high. Consider using TaskStop "
                    + "to restart this monitor with a more selective filter.]"));
                suppressed = 0;

                // after six quiet seconds (three refill periods). A merely
                // successful token refill is not enough to forgive sustained
                // output pressure.
                if (lastSuppressedAt >= 0
                        && now - lastSuppressedAt > TOKEN_REFILL_MS * 3) {
                    suppressionStartedAt = -1;
                }
            }
            emitter.accept(notification(event));
            return;
        }

        suppressed++;
        lastSuppressedAt = now;
        if (suppressionStartedAt < 0) suppressionStartedAt = now;
        if (now - suppressionStartedAt > FIREHOSE_STOP_MS) {
            long seconds = Math.max(1, Math.round((now - suppressionStartedAt) / 1_000.0));
            emitter.accept(notification("[Monitor stopped — too much output (" + suppressed
                + " events suppressed over " + seconds
                + "s). Restart with a more selective source.]"));
            closed = true;
            stopMonitor.run();
        }
    }

    private void refill(long now) {
        long elapsed = now - lastRefillAt;
        if (elapsed < TOKEN_REFILL_MS) return;
        long gained = elapsed / TOKEN_REFILL_MS;
        tokens = (int) Math.min(TOKEN_CAPACITY, tokens + gained);
        lastRefillAt += gained * TOKEN_REFILL_MS;
    }

    private String notification(String event) {
        return "<task-notification>\n"
            + "<task-id>" + escape(taskId) + "</task-id>\n"
            + "<summary>Monitor event: \"" + escape(description) + "\"</summary>\n"
            + "<event>" + escape(event) + "</event>\n"
            + "</task-notification>";
    }

    private static String truncateLine(String line) {
        return line.length() <= MAX_LINE_CHARS
            ? line : line.substring(0, MAX_LINE_CHARS) + TRUNCATED;
    }

    private static String truncateBatch(String batch) {
        return batch.length() <= MAX_BATCH_CHARS
            ? batch : batch.substring(0, MAX_BATCH_CHARS) + "\n" + TRUNCATED;
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        flushNow();
        closed = true;
        if (pendingFlush != null) pendingFlush.cancel(false);
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("monitor-events-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}

package com.claudecode.ui.lanterna.repl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
/**
 * Coalesces adjacent streamed text deltas into one Lanterna GUI task per frame.
 */
final class UiStreamDeltaBatcher {

    private static final long FRAME_DELAY_MS = 16L;
    private static final ScheduledExecutorService FRAME_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ui-stream-frame");
            thread.setDaemon(true);
            return thread;
        });

    private final Object monitor = new Object();
    private final Object transformMonitor = new Object();
    private final Consumer<Runnable> onUi;
    private final Consumer<String> renderer;
    private final BiFunction<String, Boolean, String> transformer;
    private final ScheduledExecutorService scheduler;
    private final long frameDelayMs;
    private StringBuilder pending = new StringBuilder();
    private long generation;
    private boolean flushScheduled;

    UiStreamDeltaBatcher(Consumer<Runnable> onUi, Consumer<String> renderer) {
        this(onUi, renderer, (text, _) -> text, FRAME_SCHEDULER, FRAME_DELAY_MS);
    }

    UiStreamDeltaBatcher(Consumer<Runnable> onUi, Consumer<String> renderer,
                         BiFunction<String, Boolean, String> transformer) {
        this(onUi, renderer, transformer, FRAME_SCHEDULER, FRAME_DELAY_MS);
    }

    UiStreamDeltaBatcher(Consumer<Runnable> onUi, Consumer<String> renderer,
                         ScheduledExecutorService scheduler, long frameDelayMs) {
        this(onUi, renderer, (text, _) -> text, scheduler, frameDelayMs);
    }

    UiStreamDeltaBatcher(Consumer<Runnable> onUi, Consumer<String> renderer,
                         BiFunction<String, Boolean, String> transformer,
                         ScheduledExecutorService scheduler, long frameDelayMs) {
        this.onUi = onUi;
        this.renderer = renderer;
        this.transformer = transformer;
        this.scheduler = scheduler;
        this.frameDelayMs = Math.max(0L, frameDelayMs);
    }

    void append(String delta) {
        if (StringUtils.isEmpty(delta)) return;
        long ticket = -1L;
        synchronized (monitor) {
            pending.append(delta);
            if (!flushScheduled) {
                flushScheduled = true;
                ticket = ++generation;
            }
        }
        if (ticket < 0L) return;
        long scheduledTicket = ticket;
        scheduler.schedule(
            () -> Thread.ofVirtual().name("ui-stream-transform").start(
                () -> flushScheduled(scheduledTicket)),
            frameDelayMs, TimeUnit.MILLISECONDS);
    }

    /** Queues {@code next} after an immediate flush of all preceding text. */
    void runAfterPending(Runnable next) {
        runAfterPending(false, next);
    }

    /** Queues {@code next} after flushing preceding text with its finality marker. */
    void runAfterPending(boolean finalDelta, Runnable next) {
        String text;
        synchronized (monitor) {
            generation++;
            flushScheduled = false;
            text = drainLocked();
        }
        if (!text.isEmpty() || finalDelta) {
            String displayed = transform(text, finalDelta);
            if (!displayed.isEmpty()) onUi.accept(() -> renderer.accept(displayed));
        }
        onUi.accept(next);
    }

    private void flushScheduled(long ticket) {
        String text;
        synchronized (monitor) {
            if (!flushScheduled || ticket != generation) return;
            flushScheduled = false;
            text = drainLocked();
        }
        if (!text.isEmpty()) {
            String displayed = transform(text, false);
            if (!displayed.isEmpty()) onUi.accept(() -> renderer.accept(displayed));
        }
    }

    private String transform(String text, boolean finalDelta) {
        synchronized (transformMonitor) {
            try {
                String transformed = transformer.apply(text, finalDelta);
                return transformed != null ? transformed : text;
            } catch (Throwable _) {
                return text;
            }
        }
    }

    private String drainLocked() {
        if (pending.isEmpty()) return "";
        String text = pending.toString();
        pending = new StringBuilder();
        return text;
    }
}

package com.claudecode.ui.lanterna.repl;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/**
 * Coalesces adjacent streamed text deltas into one Lanterna GUI task per frame.
 */
final class UiStreamDeltaBatcher {

    private static final Logger log = LoggerFactory.getLogger(UiStreamDeltaBatcher.class);

    private static final long FRAME_DELAY_MS = 16L;
    private static final ScheduledExecutorService FRAME_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "ui-stream-frame");
            thread.setDaemon(true);
            return thread;
        });

    private final Object monitor = new Object();
    private final Object transformMonitor = new Object();
    private final AtomicLong appendCount = new AtomicLong();
    private final AtomicLong lastFlushAppend = new AtomicLong();
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
        int pendingLen;
        synchronized (monitor) {
            pending.append(delta);
            pendingLen = pending.length();
            if (!flushScheduled) {
                flushScheduled = true;
                ticket = ++generation;
            }
        }
        long appends = appendCount.incrementAndGet();
        if (ticket < 0L) {
            // Coalesced into an already-scheduled frame. A long run of these with no
            // matching flush means the frame never fired and flushScheduled is stuck.
            if (appends - lastFlushAppend.get() > 200) {
                log.warn("{} appends since the last flush, pending={} chars"
                    + " — the frame task appears stuck", appends - lastFlushAppend.get(), pendingLen);
                lastFlushAppend.set(appends);
            }
            return;
        }
        long scheduledTicket = ticket;
        try {
            scheduler.schedule(
                () -> Thread.ofVirtual().name("ui-stream-transform").start(
                    () -> flushScheduled(scheduledTicket)),
                frameDelayMs, TimeUnit.MILLISECONDS);
        } catch (RuntimeException | Error e) {
            // flushScheduled is already true; leaving it set would strand every later
            // delta in `pending` forever. Clear it so the next append can re-arm.
            synchronized (monitor) {
                if (flushScheduled && generation == scheduledTicket) flushScheduled = false;
            }
            log.error("Frame scheduling failed; streamed text would have stalled", e);
            throw e;
        }
    }

    /** Queues {@code next} after an immediate flush of all preceding text. */
    void runAfterPending(Runnable next) {
        runAfterPending(false, next);
    }

    /** Queues {@code next} after flushing preceding text with its finality marker. */
    void runAfterPending(boolean finalDelta, Runnable next) {
        String text;
        long lockStart = System.nanoTime();
        synchronized (monitor) {
            generation++;
            flushScheduled = false;
            text = drainLocked();
        }
        long lockMs = (System.nanoTime() - lockStart) / 1_000_000L;
        if (lockMs > 500L) log.warn("runAfterPending waited {}ms for the batch monitor", lockMs);
        if (!text.isEmpty() || finalDelta) {
            long transformStart = System.nanoTime();
            String displayed = transform(text, finalDelta);
            long transformMs = (System.nanoTime() - transformStart) / 1_000_000L;
            if (transformMs > 500L) {
                log.warn("MessageDisplay transform blocked {}ms ({} chars)", transformMs,
                    text.length());
            }
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
        lastFlushAppend.set(appendCount.get());
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

package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.message.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Focus-driven away-summary trigger, mirroring the 2.1.236 bundle's
 * {@code bQg} hook.
 *
 * <p>Authority: 236 generates on the <b>blur event itself</b> (not a fixed
 * delay timer) once the conversation has been quiet for at least
 * {@link #CACHE_AGE_THRESHOLD_MS} since the last generation, and retries on
 * turn completion while still blurred. Regaining focus aborts any in-flight
 * generation. Conversation gates (minimum user turns, turns since the last
 * recap, last message already a recap) are evaluated by the generation port,
 * which shares the 236 {@code et0}/{@code hQg} logic with {@code /recap}.
 *
 * <p>236 gates that are server-side experiments (rate-limit probes, cache-age
 * probes, GrowthBook flags, telemetry) are intentionally not reproduced.
 *
 * <p>TS coverage:
 * <ul>
 *   <li>{@code src/hooks/useAwaySummary.ts} — blur/focus scheduling, abort on
 *       focus, disable-hint counter (236 binary {@code bQg}/{@code I()}; the
 *       weflow tree predates the 236 cache-age rework, the binary is
 *       authoritative)</li>
 * </ul>
 */
final class AwaySummaryTrigger implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AwaySummaryTrigger.class);

    /** 236 {@code Iqc}: default quiet period before a blur may generate. */
    private static final long CACHE_AGE_THRESHOLD_MS = 180_000;

    private final Supplier<List<Message>> messagesSupplier;
    private final RecapGeneration generation;
    private final Consumer<String> sink;
    private final BooleanSupplier turnInFlight;

    private final AtomicReference<Thread> inFlight = new AtomicReference<>();
    private final AtomicBoolean generationAborted = new AtomicBoolean(false);
    private final AtomicBoolean blurred = new AtomicBoolean(false);
    private final AtomicReference<Long> lastGenerationMs = new AtomicReference<>();
    private final AtomicInteger publishedCount = new AtomicInteger(0);
    private volatile boolean closed;

    AwaySummaryTrigger(Supplier<List<Message>> messagesSupplier,
                       RecapGeneration generation,
                       Consumer<String> sink,
                       BooleanSupplier turnInFlight) {
        this.messagesSupplier = messagesSupplier;
        this.generation = generation;
        this.sink = sink;
        this.turnInFlight = turnInFlight;
    }

    /**
     * DEC 1004 focus change. On blur: generate immediately when the conversation
     * has been quiet long enough (236 {@code I()} blurred branch); the
     * turn-in-flight case defers to {@link #turnCompleted()}.
     */
    synchronized void focusChanged(boolean focused) {
        if (closed) return;
        if (!focused) {
            blurred.set(true);
            if (turnInFlight.getAsBoolean()) return;
            maybeGenerate();
        } else {
            blurred.set(false);
            abortInFlight();
        }
    }

    /**
     * 236 second effect: on turn completion, generate if the terminal is still
     * blurred and the quiet period has since elapsed.
     */
    synchronized void turnCompleted() {
        if (closed || !blurred.get()) return;
        maybeGenerate();
    }

    private void maybeGenerate() {
        Long last = lastGenerationMs.get();
        if (last == null) {
            // First generation of the session: the 236 cache-age gate starts
            // from the first cached query completion, so only generate once the
            // conversation has some history.
            if (messagesSupplier.get().isEmpty()) return;
        } else if (System.currentTimeMillis() - last < CACHE_AGE_THRESHOLD_MS) {
            return;
        }
        if (inFlight.get() != null) return;
        List<Message> messages = messagesSupplier.get();
        generationAborted.set(false);
        Thread worker = Thread.ofVirtual().name("away-summary-generate").start(() -> {
            try {
                String text = generation.generate(messages);
                if (generationAborted.get()) return;
                if (text != null) {
                    lastGenerationMs.set(System.currentTimeMillis());
                    int published = publishedCount.getAndIncrement();
                    sink.accept(withDisableHint(text, published));
                }
            } catch (RuntimeException e) {
                log.debug("[awaySummary] generation failed: {}", e.getMessage());
            } finally {
                inFlight.compareAndSet(Thread.currentThread(), null);
            }
        });
        inFlight.set(worker);
    }

    private void abortInFlight() {
        generationAborted.set(true);
        Thread worker = inFlight.getAndSet(null);
        if (worker != null) worker.interrupt();
    }

    /** 236 appends the disable hint to the first three recaps. */
    static String withDisableHint(String text, int publishedCount) {
        return publishedCount < 3 ? text + " (disable recaps in /config)" : text;
    }

    @Override
    public synchronized void close() {
        closed = true;
        abortInFlight();
    }
}
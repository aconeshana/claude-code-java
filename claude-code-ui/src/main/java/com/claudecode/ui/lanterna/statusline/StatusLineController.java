package com.claudecode.ui.lanterna.statusline;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.message.Message;
import com.claudecode.runtime.statusline.StatusLinePort;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;


public final class StatusLineController implements AutoCloseable {

    static final long DEBOUNCE_MS = 300;

    private static final ScheduledExecutorService SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "statusline-debounce");
            t.setDaemon(true);
            return t;
        });

    private final StatusLinePort statusLine;
    private final Supplier<StatusLineInputBuilder.Ingredients> ingredients;
    private final Supplier<List<Message>> messages;
    private final Consumer<Runnable> guiThread;
    private final BiConsumer<String, Integer> onRender;  // (text, padding)
    private final Runnable onClear;
    private final BooleanSupplier builtInHudEnabled;
    private final IntSupplier terminalWidth;
    @Explanation("Supplies the built-in HUD effort badge without changing the statusLine JSON contract")
    private final Supplier<String> builtInHudEffort;

    private final AtomicLong generation = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Thread> activeExecution = new AtomicReference<>();
    private volatile ScheduledFuture<?> pending;

    /** Production wiring: command/config resolution stays behind {@link StatusLinePort}. */
    public StatusLineController(StatusLinePort statusLine,
                                Supplier<StatusLineInputBuilder.Ingredients> ingredients,
                                Supplier<List<Message>> messages,
                                Consumer<Runnable> guiThread,
                                BiConsumer<String, Integer> onRender,
                                Runnable onClear,
                                BooleanSupplier builtInHudEnabled,
                                IntSupplier terminalWidth,
                                Supplier<String> builtInHudEffort) {
        this.statusLine = statusLine != null ? statusLine : StatusLinePort.disabled();
        this.ingredients = ingredients;
        this.messages = messages;
        this.guiThread = guiThread;
        this.onRender = onRender;
        this.onClear = onClear;
        this.builtInHudEnabled = builtInHudEnabled != null ? builtInHudEnabled : () -> false;
        this.terminalWidth = terminalWidth != null ? terminalWidth : () -> 0;
        this.builtInHudEffort = builtInHudEffort != null ? builtInHudEffort : () -> null;
    }

    /** Compatibility constructor for callers that do not provide the Java HUD effort badge. */
    public StatusLineController(StatusLinePort statusLine,
                                Supplier<StatusLineInputBuilder.Ingredients> ingredients,
                                Supplier<List<Message>> messages,
                                Consumer<Runnable> guiThread,
                                BiConsumer<String, Integer> onRender,
                                Runnable onClear,
                                BooleanSupplier builtInHudEnabled,
                                IntSupplier terminalWidth) {
        this(statusLine, ingredients, messages, guiThread, onRender, onClear,
            builtInHudEnabled, terminalWidth, () -> null);
    }

    /**
     * Debounced refresh. Coalesces bursts and supersedes any pending run; the
     * newest call wins. Safe to call from any thread.
     */
    public synchronized void scheduleUpdate() {
        if (closed.get()) return;
        long gen = generation.incrementAndGet();
        ScheduledFuture<?> prev = pending;
        if (prev != null) prev.cancel(false);
        pending = SCHEDULER.schedule(() -> runIfCurrent(gen), DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Mount-time refresh without the 300ms interaction debounce. The work is
     * still asynchronous, but it can settle before the first user keystroke
     * instead of racing that keystroke with a multi-line footer repaint.
     */
    public synchronized void scheduleInitialUpdate() {
        if (closed.get()) return;
        long gen = generation.incrementAndGet();
        ScheduledFuture<?> prev = pending;
        if (prev != null) prev.cancel(false);
        pending = SCHEDULER.schedule(() -> runIfCurrent(gen), 0, TimeUnit.MILLISECONDS);
    }

/** Fires the debounced task only if no newer {@link #scheduleUpdate} superseded it. */
    private void runIfCurrent(long gen) {
        if (closed.get() || gen != generation.get()) return;
        // Snapshot the message list on the GUI thread — it is not thread-safe
        // and is mutated there. Everything else (config file read, ingredient
        // gathering, and the command itself) runs off-thread so the GUI never
        // blocks on I/O.
        guiThread.accept(() -> {
            if (closed.get() || gen != generation.get()) return;
            List<Message> snapshot = List.copyOf(messages.get());
            Thread.ofVirtual().name("statusline-cmd").start(() -> execute(gen, snapshot));
        });
    }

    private void execute(long gen, List<Message> snapshot) {
        Thread current = Thread.currentThread();
        if (!activateExecution(gen, current)) return;
        try {
            if (closed.get() || gen != generation.get()) return;
            StatusLineInput input = StatusLineInputBuilder.build(ingredients.get(), snapshot);
            boolean useBuiltInHud = builtInHudEnabled.getAsBoolean();
            String builtInOutput = useBuiltInHud
                ? BuiltInClaudeHudRenderer.render(
                    input, terminalWidth.getAsInt(), builtInHudEffort.get())
                : null;
            var output = useBuiltInHud
                ? Optional.<StatusLinePort.Output>empty()
                : statusLine.render(input.toJson());
            guiThread.accept(() -> {
                if (closed.get() || gen != generation.get()) return;
                if (output.isPresent()) {
                    StatusLinePort.Output rendered = output.get();
                    onRender.accept(rendered.text(), rendered.padding());
                } else if (builtInOutput != null) {
                    onRender.accept(builtInOutput, 0);
                } else {
                    onClear.run();
                }
            });
        } finally {
            activeExecution.compareAndSet(current, null);
        }
    }

    /** Atomically rejects stale runs and aborts the command replaced by this run. */
    private synchronized boolean activateExecution(long gen, Thread current) {
        if (closed.get() || gen != generation.get()) return false;
        Thread previous = activeExecution.getAndSet(current);
        if (previous != null && previous != current) previous.interrupt();
        return true;
    }


    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) return;
        generation.incrementAndGet();
        ScheduledFuture<?> prev = pending;
        if (prev != null) prev.cancel(false);
        pending = null;
        Thread running = activeExecution.getAndSet(null);
        if (running != null) running.interrupt();
    }
}

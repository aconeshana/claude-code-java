package com.claudecode.ui.lanterna.theme;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Background watcher that periodically re-queries the system theme and notifies listeners when it
 * changes.
 */
public final class SystemThemeWatcher {

    private static final long DEFAULT_POLL_SECONDS = 5;

    private final ScheduledExecutorService scheduler;
    private final long pollSeconds;
    private ScheduledFuture<?> task;
    private SystemTheme.Mode lastSeen;
    private Consumer<SystemTheme.Mode> onChange;

    public SystemThemeWatcher() {
        this(DEFAULT_POLL_SECONDS);
    }

    public SystemThemeWatcher(long pollSeconds) {
        this.pollSeconds = pollSeconds;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "system-theme-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Start polling. {@code onChange} is invoked the first time a delta is
     * detected and on every subsequent change. Safe to call multiple times —
     * a second call replaces the listener and resets the schedule.
     */
    public synchronized void start(Consumer<SystemTheme.Mode> onChange) {
        this.onChange = onChange;
        if (task != null) task.cancel(false);
        // Seed lastSeen from whatever the cache already has so the first
        // tick doesn't fire spuriously.
        lastSeen = SystemTheme.getSystemTheme();
        task = scheduler.scheduleAtFixedRate(this::tick,
            pollSeconds, pollSeconds, TimeUnit.SECONDS);
    }

    /** Stop polling. Watcher is no-op after stop; create a new one to restart. */
    public synchronized void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        scheduler.shutdown();
    }

    private void tick() {
        try {
            // Re-detect from scratch (bypass cache) so the watcher actually
            // notices changes the cache otherwise hides.
            SystemTheme.Mode fresh = SystemTheme.detectFromColorFgBg();
            if (fresh == null) fresh = SystemTheme.detectFromPlatform();
            if (fresh == null) fresh = SystemTheme.Mode.DARK;

            if (fresh != lastSeen) {
                lastSeen = fresh;
                SystemTheme.setCachedSystemTheme(fresh);
                Consumer<SystemTheme.Mode> cb = onChange;
                if (cb != null) cb.accept(fresh);
            }
        } catch (Throwable _) {
            // Watcher must never die — swallow and try again next tick.
        }
    }
}

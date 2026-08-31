package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.core.message.SystemMessage;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.lang3.Strings;


public final class AutoModeEntryWarningController implements AutoCloseable {

    static final long DELAY_MS = 800L;
    public static final String DESCRIPTION = "Auto mode lets Claude handle permission prompts automatically — "
        + "Claude checks each tool call for risky actions and prompt injection before executing. "
        + "Actions Claude identifies as safe are executed, while actions Claude identifies as risky "
        + "are blocked and Claude may try a different approach. Ideal for long-running tasks. "
        + "Sessions are slightly more expensive. Claude can make mistakes that allow harmful commands "
        + "to run, it's recommended to only use in isolated environments. Shift+Tab to change mode.";

    @FunctionalInterface
    interface Cancellable {
        void cancel();
    }

    @FunctionalInterface
    interface Scheduler {
        Cancellable schedule(Runnable task, long delayMs);
    }

    private static final ScheduledExecutorService TIMER =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "auto-mode-entry-warning");
            thread.setDaemon(true);
            return thread;
        });

    private final BooleanSupplier hasSeen;
    private final BooleanSupplier skipPrompt;
    private final Runnable markSeen;
    private final Consumer<SystemMessage> append;
    private final Scheduler scheduler;

    private Cancellable pending;
    private long generation;
    private boolean resolved;

    public static AutoModeEntryWarningController standard(Consumer<SystemMessage> append) {
        return new AutoModeEntryWarningController(
            () -> UiSettings.readGlobalBoolean("hasSeenAutoModeEntryWarning", false),
            UiSettings::readSkipAutoPermissionPrompt,
            () -> UiSettings.writeGlobal("hasSeenAutoModeEntryWarning", true),
            append,
            (task, delayMs) -> {
                var future = TIMER.schedule(task, delayMs, TimeUnit.MILLISECONDS);
                return () -> future.cancel(false);
            });
    }

    AutoModeEntryWarningController(BooleanSupplier hasSeen,
                                   BooleanSupplier skipPrompt,
                                   Runnable markSeen,
                                   Consumer<SystemMessage> append,
                                   Scheduler scheduler) {
        this.hasSeen = Objects.requireNonNull(hasSeen);
        this.skipPrompt = Objects.requireNonNull(skipPrompt);
        this.markSeen = Objects.requireNonNull(markSeen);
        this.append = Objects.requireNonNull(append);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    public synchronized void onPermissionModeChanged(String permissionMode) {
        generation++;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
        if (resolved || !Strings.CS.equals("auto", permissionMode)) return;
        long scheduledGeneration = generation;
        pending = scheduler.schedule(() -> fire(scheduledGeneration), DELAY_MS);
    }

    private void fire(long scheduledGeneration) {
        synchronized (this) {
            if (resolved || generation != scheduledGeneration) return;
            pending = null;
            resolved = true;
        }
        if (skipPrompt.getAsBoolean() || hasSeen.getAsBoolean()) return;
        try {
            markSeen.run();
        } catch (RuntimeException _) {
            // Persistence is best-effort; never hide a safety notice because the
            // global config file happened to be temporarily unwritable.
        }
        append.accept(new SystemMessage(
            UUID.randomUUID().toString(), "informational", "notice", DESCRIPTION));
    }

    @Override
    public synchronized void close() {
        generation++;
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}

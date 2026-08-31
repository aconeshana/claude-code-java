package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Live handle for a shell command that started synchronously and may be detached in place.
 */
public final class ForegroundShellTask {
    private static final long STALL_CHECK_INTERVAL_MS = 5_000;
    private static final long STALL_THRESHOLD_MS = 45_000;
    private static final int STALL_TAIL_BYTES = 1_024;
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(
        Thread.ofVirtual().name("fg-shell-watchdog-", 0).factory());
    private static final List<Pattern> PROMPT_PATTERNS = List.of(
        Pattern.compile("\\(y/n\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[y/n\\]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\(yes/no\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(?:Do you|Would you|Shall I|Are you sure|Ready to)\\b.*\\? *$",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("Press (any key|Enter)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Continue\\?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Overwrite\\?", Pattern.CASE_INSENSITIVE));
    private final TaskState taskState;
    private final TaskStore taskStore;
    private final Path outputPath;
    private final Runnable activateOutput;
    private final AtomicBoolean backgroundRequested = new AtomicBoolean();
    private final AtomicBoolean killed = new AtomicBoolean();
    private final CompletableFuture<Void> backgroundSignal = new CompletableFuture<>();
    private volatile ScheduledFuture<?> stallWatchdog;
    private volatile long lastOutputSize;
    private volatile long lastOutputGrowth;
    private volatile Process process;

    public ForegroundShellTask(TaskState taskState, TaskStore taskStore, Path outputPath,
                               Runnable activateOutput) {
        this.taskState = taskState;
        this.taskStore = taskStore;
        this.outputPath = outputPath;
        this.activateOutput = activateOutput;
    }

    public String getTaskId() { return taskState.id(); }
    public Path getOutputPath() { return outputPath; }
    public CompletableFuture<Void> backgroundSignal() { return backgroundSignal; }
    public boolean isBackgrounded() { return backgroundRequested.get(); }
    public void setProcess(Process process) { this.process = process; }

    public boolean requestBackground() {
        if (!backgroundRequested.compareAndSet(false, true)) return false;
        try {
            activateOutput.run();
        } catch (RuntimeException error) {
            backgroundRequested.set(false);
            throw error;
        }
        startStallWatchdog();
        backgroundSignal.complete(null);
        return true;
    }

    public void complete(int exitCode) {
        stopStallWatchdog();
        if (!isBackgrounded() || killed.get()) return;
        taskStore.updateExitCode(getTaskId(), exitCode);
        taskStore.updateStatus(getTaskId(), exitCode == 0
            ? TaskStatus.COMPLETED : TaskStatus.FAILED);
    }

    public void fail(String message) {
        stopStallWatchdog();
        if (!isBackgrounded() || killed.get()) return;
        taskStore.updateError(getTaskId(), message);
        taskStore.updateStatus(getTaskId(), TaskStatus.FAILED);
    }

    public boolean kill() {
        TaskState current = taskStore.get(getTaskId()).orElse(null);
        if (current == null || current.status() != TaskStatus.RUNNING
                || !killed.compareAndSet(false, true)) return false;
        TaskState after = taskStore.updateStatusAndMarkNotified(
            getTaskId(), TaskStatus.KILLED);
        if (after.status() != TaskStatus.KILLED) return false;
        stopStallWatchdog();
        Process live = process;
        if (live != null && live.isAlive()) {
            Thread.ofVirtual().name("fg-shell-kill-" + getTaskId()).start(() -> {
                live.destroy();
                try {
                    if (!live.waitFor(3, TimeUnit.SECONDS)) live.destroyForcibly();
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    live.destroyForcibly();
                }
            });
        }
        return true;
    }

    private void startStallWatchdog() {
        MessageQueueManager queue = TaskRegistry.global().messageQueue();
        if (queue == null) return;
        lastOutputSize = 0L;
        lastOutputGrowth = System.currentTimeMillis();
        stallWatchdog = TIMER.scheduleWithFixedDelay(() -> checkForInteractiveStall(queue),
            STALL_CHECK_INTERVAL_MS, STALL_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void checkForInteractiveStall(MessageQueueManager queue) {
        if (!isBackgrounded() || taskStore.get(getTaskId()).map(TaskState::status)
                .map(TaskStatus::isTerminal).orElse(true)) {
            stopStallWatchdog();
            return;
        }
        try {
            long size = Files.size(outputPath);
            long now = System.currentTimeMillis();
            if (size > lastOutputSize) {
                lastOutputSize = size;
                lastOutputGrowth = now;
                return;
            }
            if (now - lastOutputGrowth < STALL_THRESHOLD_MS) return;
            String tail = FileUtils.tailFile(outputPath, STALL_TAIL_BYTES).content();
            if (!looksLikePrompt(tail)) {
                lastOutputGrowth = now;
                return;
            }
            stopStallWatchdog();
            String body = TaskNotificationBuilder.buildStallNotification(taskState, tail);
            queue.enqueuePendingNotification(new QueuedCommand(
                body, null, "task-notification", QueuePriority.NEXT,
                true, null, false, false, null, null, taskState.agentId().orElse(null), null));
        } catch (IOException _) {
            // Output file may not exist yet; the next 5s tick retries.
        }
    }

    private static boolean looksLikePrompt(String tail) {
        if (StringUtils.isBlank(tail)) return false;
        String stripped = tail.stripTrailing();
        int newline = stripped.lastIndexOf('\n');
        String lastLine = newline >= 0 ? stripped.substring(newline + 1) : stripped;
        return PROMPT_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(lastLine).find());
    }

    private void stopStallWatchdog() {
        ScheduledFuture<?> future = stallWatchdog;
        if (future != null) future.cancel(false);
        stallWatchdog = null;
    }
}

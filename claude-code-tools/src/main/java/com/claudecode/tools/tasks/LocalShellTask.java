package com.claudecode.tools.tasks;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.engine.ToolExecutionContext.ProgressSink;
import com.claudecode.core.engine.ToolExecutionContext.ProgressUpdate;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.queue.QueuePriority;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.tools.sandbox.NoopSandboxBackend;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.core.text.StringUtils;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.ExecutableFinder;
import com.claudecode.core.process.ProcessTreeTerminator;
import com.claudecode.core.process.SubprocessEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live handle for a backgrounded shell command: owns the child {@link Process}, streams its
 * combined stdout/stderr to the task's {@code.output} file that {@link TaskOutputTool} reads back,
 * and drives the backing {@link TaskState} through {@link TaskStore} on completion or kill.
 */
public class LocalShellTask {

    private static final Logger log = LoggerFactory.getLogger(LocalShellTask.class);

    /** Shared timer pool; cancelled futures are removed immediately from its queue. */
    private static final ScheduledThreadPoolExecutor TASK_TIMERS = createTaskTimerPool();

    private final TaskState taskState;
    private final String command;
    private final TaskStore taskStore;
    private final Path outputPath;
    private final AtomicBoolean killed = new AtomicBoolean(false);
    private volatile Process process;

    /** Native sandbox backend used to wrap the command (NoopSandboxBackend by default). */
    private final SandboxManager sandboxManager;
    /** Resolved sandbox decision for this command (unsandboxed by default). */
    private final SandboxDecision sandboxDecision;
    /** Resolved sandbox config snapshot (disabled by default). */
    private final SandboxConfig sandboxConfig;

    /** Working directory the command runs in (set in {@link #start}). */
    private Path cwd;
    /** Bare-repo files present before the command, for post-command scrub. */
    private Set<Path> bareGitBefore = Set.of();

    // ── Stall watchdog (interactive-prompt-blocked notification) ─────────

    // if it stops growing for STALL_THRESHOLD_MS while the tail looks like a
    // prompt, fire a one-shot statusless task-notification (priority NEXT).
    private static final long STALL_CHECK_INTERVAL_MS = 5_000;
    private static final long STALL_THRESHOLD_MS = 45_000;
    private static final int STALL_TAIL_BYTES = 1_024;


    private static final long PROGRESS_TICK_INTERVAL_MS = 1_000;

    private final AtomicBoolean stallNotified = new AtomicBoolean(false);
    private final AtomicBoolean scheduledTasksStopped = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> stallWatchdogFuture;
    private volatile ScheduledFuture<?> progressTickerFuture;
    private volatile long stallLastSize;
    private volatile long stallLastGrowth;
    private final Object progressEmissionLock = new Object();

/**
     * Progress sink for live output updates.
     */
    private final ProgressSink progressSink;

    /** Epoch the task started, for the progress {@code elapsedSeconds} field. */
    private long startEpoch;


    private long lastTotalLines = 0;

    /**
     * Optional pre-built argv. When non-null it bypasses {@link #resolveCommandLine}
     * (which would otherwise wrap the command in {@code bash -c}); used by
     * {@code PowerShellTool}, whose background command must exec the PowerShell
     * interpreter directly (optionally sandbox-wrapped) rather than via bash.
     */
    private final List<String> explicitArgv;

    private static ScheduledThreadPoolExecutor createTaskTimerPool() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            2, Thread.ofVirtual().name("bg-task-timer-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }






    private static final List<Pattern> PROMPT_PATTERNS = List.of(
        Pattern.compile("\\(y/n\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\[y/n\\]", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\(yes/no\\)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\b(?:Do you|Would you|Shall I|Are you sure|Ready to)\\b.*\\? *$",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("Press (any key|Enter)", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Continue\\?", Pattern.CASE_INSENSITIVE),
        Pattern.compile("Overwrite\\?", Pattern.CASE_INSENSITIVE));


    static boolean looksLikePrompt(String tail) {
        if (org.apache.commons.lang3.StringUtils.isBlank(tail)) return false;
        String t = tail.stripTrailing();
        int idx = t.lastIndexOf('\n');
        String lastLine = idx >= 0 ? t.substring(idx + 1) : t;
        return PROMPT_PATTERNS.stream().anyMatch(p -> p.matcher(lastLine).find());
    }

    /** Uses the canonical session-scoped {@link TaskOutputPaths#outputPath(String)} location (unsandboxed). */
    public LocalShellTask(TaskState taskState, String command, TaskStore taskStore) {
        this(taskState, command, taskStore, TaskOutputPaths.outputPath(taskState.id()),
            new NoopSandboxBackend(), SandboxDecision.unsandboxed(), SandboxConfig.disabled(),
            ProgressSink.NOOP);
    }

    /**
     * Test/advanced-caller constructor: writes output to an explicit path
     * instead of the real session-scoped temp location.
     * Runs unsandboxed by default.
     */
    public LocalShellTask(TaskState taskState, String command, TaskStore taskStore, Path outputPath) {
        this(taskState, command, taskStore, outputPath,
            new NoopSandboxBackend(), SandboxDecision.unsandboxed(), SandboxConfig.disabled(),
            ProgressSink.NOOP);
    }

    /**
     * Full constructor: carries the sandbox backend, resolved decision, and config used to wrap the
     * command.
     */
    public LocalShellTask(TaskState taskState, String command, TaskStore taskStore, Path outputPath,
                          SandboxManager sandboxManager, SandboxDecision sandboxDecision,
                          SandboxConfig sandboxConfig, ProgressSink progressSink) {
        this(taskState, command, taskStore, outputPath, sandboxManager, sandboxDecision,
            sandboxConfig, progressSink, null);
    }

    /**
     * Full constructor with an optional explicit argv. When {@code explicitArgv}
     * is non-null it is executed verbatim (no {@code bash -c} wrapping), so a
     * PowerShell background command can run the pwsh/powershell interpreter
     * directly (still sandbox-wrapped if {@code sandboxDecision.isSandboxed}).
     */
    public LocalShellTask(TaskState taskState, String command, TaskStore taskStore, Path outputPath,
                          SandboxManager sandboxManager, SandboxDecision sandboxDecision,
                          SandboxConfig sandboxConfig, ProgressSink progressSink,
                          List<String> explicitArgv) {
        this.taskState = taskState;
        this.command = command;
        this.taskStore = taskStore;
        this.outputPath = outputPath;
        this.sandboxManager = sandboxManager;
        this.sandboxDecision = sandboxDecision;
        this.sandboxConfig = sandboxConfig;
        this.progressSink = progressSink;
        this.explicitArgv = explicitArgv;
    }

    public String getTaskId() {
        return taskState.id();
    }

    public String getCommand() {
        return command;
    }

    /** The path this handle streams combined stdout/stderr to. */
    public Path getOutputPath() {
        return outputPath;
    }

    /**
     * Resolve the argv to exec for a command, wrapping it in the native sandbox
     * when {@code decision} says sandboxed (otherwise a plain {@code bash -c}).
     * Pure (no process launch) so it is unit-testable without spawning processes.
     */
    static List<String> resolveCommandLine(String command, Path cwd,
                                                   SandboxManager manager, SandboxDecision decision,
                                                   SandboxConfig cfg) {
        if (decision.isSandboxed()) {
            return manager.wrap(command, cwd, cfg);
        }
        return List.of(ExecutableFinder.bashExecutable(), "-c", command);
    }

    /**
     * Starts the child process, transitions the backing task to {@code RUNNING},
     * and spawns a virtual thread that streams output to disk and updates the
     * task's terminal status when the process exits. Returns immediately — this
     * method does not block on the process finishing.
     */
    public void start(String workingDirectory) throws IOException {
        Path cwd = workingDirectory != null ? Path.of(workingDirectory) : Path.of(".");
        this.cwd = cwd;
        this.startEpoch = System.currentTimeMillis();
        // Snapshot bare-repo files so we can scrub any planted during the
        // (sandboxed) command — prevents a later unsandboxed git escape.
        this.bareGitBefore = sandboxDecision.isSandboxed()
            ? SandboxManager.bareGitFilesSnapshot(cwd) : Set.of();
        List<String> argv = (explicitArgv != null)
            ? explicitArgv
            : resolveCommandLine(command, cwd, sandboxManager, sandboxDecision, sandboxConfig);
        ProcessBuilder pb = new ProcessBuilder(argv);
        SubprocessEnvironment.applyTo(pb.environment());
        if (Platform.IS_WINDOWS) {
            pb.environment().put("SHELL", ExecutableFinder.bashExecutable());
        }
        if (workingDirectory != null) {
            pb.directory(Path.of(workingDirectory).toFile());
        }
        // Domain-allowlist proxy env (sandbox.network.allowedDomains) — routes
        // network through the parent proxy that enforces the allowlist. Only when
        // actually sandboxed; otherwise there is nothing to filter.
        if (sandboxDecision.isSandboxed()) {
            pb.environment().putAll(sandboxManager.sandboxEnvironment(sandboxConfig));
        }
        pb.redirectErrorStream(true);

        Path output = outputPath;
        Files.createDirectories(output.getParent());

        process = pb.start();
        try {
            process.getOutputStream().close();
        } catch (IOException _) {
            // Nothing written to stdin; closing it just unblocks commands that probe it.
        }

        taskStore.updateStatus(getTaskId(), TaskStatus.RUNNING);
        log.info("Started background shell task {} with command: {}", getTaskId(), command);


        startProgressTicker();
        Thread.ofVirtual().name("bg-shell-" + getTaskId()).start(() -> runAndAwait(output));
        startStallWatchdog();
    }

    /**
     * Polls the output file for an interactive-prompt stall.
     */
    private void startStallWatchdog() {
        if (taskState.type() == TaskType.MONITOR_MCP) return;
        if (TaskRegistry.global() == null || TaskRegistry.global().messageQueue() == null) return;
        if (scheduledTasksStopped.get()) return;

        stallLastSize = 0;
        stallLastGrowth = System.currentTimeMillis();
        ScheduledFuture<?> future = TASK_TIMERS.scheduleWithFixedDelay(
            this::runStallWatchdogTick,
            STALL_CHECK_INTERVAL_MS,
            STALL_CHECK_INTERVAL_MS,
            TimeUnit.MILLISECONDS);
        stallWatchdogFuture = future;
        if (scheduledTasksStopped.get()) {
            future.cancel(false);
        }
    }

    private void runStallWatchdogTick() {
        if (shouldStopScheduledTasks()) {
            stopScheduledTasks();
            return;
        }
        if (stallNotified.get()) {
            cancelStallWatchdog();
            return;
        }

        try {
            long size = Files.size(outputPath);
            long now = System.currentTimeMillis();
            if (size > stallLastSize) {
                stallLastSize = size;
                stallLastGrowth = now;
                return;
            }
            if (now - stallLastGrowth < STALL_THRESHOLD_MS) {
                return;
            }

            String tail = tailBytes(outputPath, STALL_TAIL_BYTES);
            if (looksLikePrompt(tail)) {
                if (stallNotified.compareAndSet(false, true)) {
                    enqueueStallNotification(tail);
                }
                cancelStallWatchdog();
                return;
            }
            // Not a prompt — another full quiet window must elapse before
            // reading the tail again.
            stallLastGrowth = now;
        } catch (IOException _) {
            // output file not yet flushed / missing — keep watching
        }
    }

    /** Testable core of the watchdog: tail + prompt check + one-shot enqueue. */
    void checkAndEmitStallIfBlocked() {
        if (stallNotified.get() || taskState.type() == TaskType.MONITOR_MCP) return;
        try {
            String tail = tailBytes(outputPath, STALL_TAIL_BYTES);
            if (looksLikePrompt(tail) && stallNotified.compareAndSet(false, true)) {
                enqueueStallNotification(tail);
            }
        } catch (IOException _) {
            // output file not readable yet — nothing to emit
        }
    }

    private void enqueueStallNotification(String tail) {
        MessageQueueManager queue =
            TaskRegistry.global() == null ? null : TaskRegistry.global().messageQueue();
        if (queue == null) return; // not wired (e.g. tests) — no-op
        String body = TaskNotificationBuilder.buildStallNotification(taskState, tail);
// Route the stall warning to the owning agent (a sub-agent's background bash carries its
// agentId) so only that agent's engine loop drains it; a main-thread bash has agentId null
// → drained by the coordinator.
        queue.enqueuePendingNotification(new QueuedCommand(
            body, null, "task-notification", QueuePriority.NEXT,
            true, null, false, false, null, null, taskState.agentId().orElse(null)));
    }

    /** Reads the last {@code maxBytes} of a file as UTF-8. */
    private static String tailBytes(Path path, int maxBytes) throws IOException {
        return FileUtils.tailFile(path, maxBytes).content();
    }


    private void emitProgress(boolean complete) {
        long totalBytes = 0;
        String tail = "";
        try {
            totalBytes = Files.size(outputPath);
            tail = tailBytes(outputPath, StringUtils.PROGRESS_TAIL_CHARS);
        } catch (IOException _) {

            // yet still produces an empty progress update so the UI wakes up.
        }

        try {
            var t = StringUtils.progressTail(tail);
            int lineCount = StringUtils.countChar(tail, '\n');
            long totalLines;
            if (tail.length() >= totalBytes) {
                totalLines = lineCount;
            } else {
                double ratio = totalBytes / (double) Math.max(1, tail.length());
                totalLines = Math.max(lastTotalLines, Math.round(ratio * lineCount));
            }
            lastTotalLines = totalLines;
            long elapsedSec = (System.currentTimeMillis() - startEpoch) / 1000;
            String summary = String.format(
                "bash · %d lines · %d bytes · %ds", totalLines, totalBytes, elapsedSec);
            progressSink.accept(ProgressUpdate.of(
                0.0, summary, "bash_progress", null, null,
                t.last5(), t.last100(), totalLines, totalBytes,
                elapsedSec, 0L, complete));
        } catch (RuntimeException e) {
            log.debug("Progress sink failed for background task {}: {}", getTaskId(), e.getMessage());
        }
    }

    private void emitScheduledProgress() {
        synchronized (progressEmissionLock) {
            if (scheduledTasksStopped.get() || killed.get()) return;
            emitProgress(false);
        }
    }

    private void emitFinalProgress() {
        synchronized (progressEmissionLock) {
            emitProgress(true);
        }
    }


    private void startProgressTicker() {
        if (taskState.type() == TaskType.MONITOR_MCP) return;
        if (progressSink == ProgressSink.NOOP) return;
        if (scheduledTasksStopped.get()) return;

        emitScheduledProgress();
        ScheduledFuture<?> future = TASK_TIMERS.scheduleAtFixedRate(() -> {
            if (shouldStopScheduledTasks()) {
                stopScheduledTasks();
                return;
            }
            emitScheduledProgress();
        }, PROGRESS_TICK_INTERVAL_MS, PROGRESS_TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
        progressTickerFuture = future;
        if (scheduledTasksStopped.get()) {
            future.cancel(false);
        }
    }

    private boolean shouldStopScheduledTasks() {
        if (scheduledTasksStopped.get() || killed.get()) return true;
        var current = taskStore.get(getTaskId());
        return current.isEmpty() || current.get().status().isTerminal();
    }

    private void cancelStallWatchdog() {
        ScheduledFuture<?> future = stallWatchdogFuture;
        if (future != null) future.cancel(false);
    }

    private void stopScheduledTasks() {
        scheduledTasksStopped.set(true);
        cancelStallWatchdog();
        ScheduledFuture<?> progress = progressTickerFuture;
        if (progress != null) progress.cancel(false);
    }

    private void runAndAwait(Path output) {
        try (var out = Files.newOutputStream(output,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.write('\n');
            }
        } catch (IOException e) {
            log.warn("Failed streaming output for background task {}: {}", getTaskId(), e.getMessage());
        }

        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }

        stopScheduledTasks();


        // isIncomplete=false), so the sink clears/replaces the live indicator.
        // Skip when killed — the KILLED transition owns the terminal
        // notification and we must not override it with a completed tick.
        if (!killed.get()) {
            emitFinalProgress();
        }

        // Scrub bare-repo files planted during the sandboxed command before any
        // unsandboxed git call can see them (anthropics/claude-code#29316).
        if (sandboxDecision.isSandboxed() && this.cwd != null) {
            SandboxManager.scrubBareGitRepoFiles(this.cwd, this.bareGitBefore);
        }

// If kill already raced us to a terminal state, don't try to
        // transition again.
        if (killed.get()) {
            return;
        }
        TaskStatus finalStatus = exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED;
        // Persist exit code BEFORE the terminal transition — the completion
        // listener (TaskNotificationBridge) fires on updateStatus and builds
        // the task-notification XML, which appends "(exit code N)". Must be
        // visible first.
        taskStore.updateExitCode(getTaskId(), exitCode);
        // updateStatus is compute-atomic: if kill() wins the race between the
        // killed.get() check and here, this returns the KILLED state silently
        // instead of throwing — no try/catch needed, and KILLED can never be
        // overwritten by COMPLETED.
        taskStore.updateStatus(getTaskId(), finalStatus);
        log.info("Background shell task {} finished with exit code {}", getTaskId(), exitCode);
    }

    /**
     * Kills the running task.
     */
    public boolean kill() {
        var current = taskStore.get(getTaskId());
        if (current.isEmpty() || current.get().status() != TaskStatus.RUNNING) {
            return false;
        }
        if (!killed.compareAndSet(false, true)) {
            return false;
        }

        TaskState after = taskStore.updateStatusAndMarkNotified(getTaskId(), TaskStatus.KILLED);
        if (after.status() != TaskStatus.KILLED) {
            // The completion watcher won the race — the process already
            // exited, nothing to destroy.
            stopScheduledTasks();
            return false;
        }
        stopScheduledTasks();
        Process p = process;
        if (p != null && p.isAlive()) {
            Thread.ofVirtual().name("bg-shell-kill-" + getTaskId())
                .start(() -> destroyProcess(p));
        }
        log.info("Killed background shell task {}", getTaskId());
        return true;
    }

    /** Terminates the full process tree off the caller's thread (see {@link #kill()}). */
    private static void destroyProcess(Process p) {
        ProcessTreeTerminator.terminate(p, Duration.ofSeconds(3));
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

}

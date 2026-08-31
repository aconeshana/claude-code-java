package com.claudecode.tools.tasks;

import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SandboxDecision;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.core.process.SubprocessEnvironment;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Live shell-backed Monitor task with stdout-only event delivery.
 */
public final class MonitorCommandTask implements MonitorTaskHandle {

    private static final Logger LOG = LoggerFactory.getLogger(MonitorCommandTask.class);
    private static final ScheduledThreadPoolExecutor TIMER = createTimer();

    private final TaskState task;
    private final String command;
    private final TaskStore store;
    private final Path outputPath;
    private final Path cwd;
    private final SandboxManager sandboxManager;
    private final SandboxDecision sandboxDecision;
    private final SandboxConfig sandboxConfig;
    private final MonitorEventDispatcher events;
    private final long timeoutMs;
    private final boolean persistent;
    private final AtomicBoolean killed = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object outputLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter output;
    private volatile ScheduledFuture<?> timeoutFuture;
    private Set<Path> bareGitBefore = Set.of();

    public MonitorCommandTask(TaskState task, String command, TaskStore store,
                              Path outputPath, Path cwd, SandboxManager sandboxManager,
                              SandboxDecision sandboxDecision, SandboxConfig sandboxConfig,
                              MessageQueueManager queue, long timeoutMs, boolean persistent) {
        this.task = task;
        this.command = command;
        this.store = store;
        this.outputPath = outputPath;
        this.cwd = cwd;
        this.sandboxManager = sandboxManager;
        this.sandboxDecision = sandboxDecision;
        this.sandboxConfig = sandboxConfig != null ? sandboxConfig : SandboxConfig.disabled();
        this.timeoutMs = timeoutMs;
        this.persistent = persistent;
        this.events = MonitorEventDispatcher.forQueue(task.id(), task.description(),
            task.agentId().orElse(null), queue, this::kill);
    }

    @Override public String getTaskId() { return task.id(); }
    @Override public Path getOutputPath() { return outputPath; }
    @Override public String displaySource() { return command; }

    public void start() throws IOException {
        Files.createDirectories(outputPath.getParent());
        output = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
        bareGitBefore = sandboxDecision.isSandboxed()
            ? SandboxManager.bareGitFilesSnapshot(cwd) : Set.of();
        List<String> argv = LocalShellTask.resolveCommandLine(command, cwd,
            sandboxManager, sandboxDecision, sandboxConfig);
        ProcessBuilder builder = new ProcessBuilder(argv);
        SubprocessEnvironment.applyTo(builder.environment());
        builder.directory(cwd.toFile());
        builder.redirectErrorStream(false);
        if (sandboxDecision.isSandboxed()) {
            builder.environment().putAll(sandboxManager.sandboxEnvironment(sandboxConfig));
        }
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            closeOutput();
            throw e;
        }
        try { process.getOutputStream().close(); } catch (IOException _) { }
        store.updateStatus(task.id(), TaskStatus.RUNNING);

        Thread stdout = Thread.ofVirtual().name("monitor-stdout-" + task.id())
            .start(() -> drain(process.getInputStream(), true));
        Thread stderr = Thread.ofVirtual().name("monitor-stderr-" + task.id())
            .start(() -> drain(process.getErrorStream(), false));
        Thread.ofVirtual().name("monitor-wait-" + task.id())
            .start(() -> await(stdout, stderr));
        if (!persistent) {
            timeoutFuture = TIMER.schedule(this::timeout, timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    private void drain(InputStream stream, boolean notify) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendOutput(line);
                if (notify) events.accept(line);
            }
        } catch (IOException e) {
            if (!killed.get()) LOG.debug("Monitor stream read failed for {}: {}",
                task.id(), e.getMessage());
        }
    }

    private void appendOutput(String line) throws IOException {
        synchronized (outputLock) {
            if (output == null) return;
            output.write(line);
            output.newLine();
            output.flush();
        }
    }

    private void await(Thread stdout, Thread stderr) {
        int exitCode = -1;
        try {
            exitCode = process.waitFor();
            stdout.join();
            stderr.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            finish(exitCode);
        }
    }

    private void finish(int exitCode) {
        if (!finished.compareAndSet(false, true)) return;
        cancelTimeout();
        events.close();
        closeOutput();
        scrubSandboxArtifacts();
        if (killed.get()) return;
        store.updateExitCode(task.id(), exitCode);
        store.updateStatus(task.id(), exitCode == 0 ? TaskStatus.COMPLETED : TaskStatus.FAILED);
    }

    private void timeout() {
        if (finished.get() || killed.get()) return;
        events.emitHousekeeping("[Monitor timed out — re-arm if needed.]");
        kill();
    }

    @Override
    public boolean kill() {
        var current = store.get(task.id());
        if (current.isEmpty() || current.get().status() != TaskStatus.RUNNING) return false;
        if (!killed.compareAndSet(false, true)) return false;
        TaskState after = store.updateStatusAndMarkNotified(task.id(), TaskStatus.KILLED);
        if (after.status() != TaskStatus.KILLED) return false;
        cancelTimeout();
        events.close();
        Process live = process;
        if (live != null && live.isAlive()) {
            Thread.ofVirtual().name("monitor-kill-" + task.id())
                .start(() -> destroy(live));
        }
        return true;
    }

    private void cancelTimeout() {
        ScheduledFuture<?> future = timeoutFuture;
        if (future != null) future.cancel(false);
    }

    private void closeOutput() {
        synchronized (outputLock) {
            if (output == null) return;
            try { output.close(); } catch (IOException _) { }
            output = null;
        }
    }

    private void scrubSandboxArtifacts() {
        if (sandboxDecision.isSandboxed()) {
            SandboxManager.scrubBareGitRepoFiles(cwd, bareGitBefore);
        }
    }

    private static void destroy(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static ScheduledThreadPoolExecutor createTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("monitor-timeout-", 0).factory());
        timer.setRemoveOnCancelPolicy(true);
        return timer;
    }
}

package com.claudecode.tools.tasks;

import com.claudecode.core.engine.AbortController;
import com.claudecode.tools.agent.SubAgentResult;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

/**
 * Live handle for a backgrounded sub-agent run: tracks coarse progress and drives the backing
 * {@link TaskState} through {@link TaskStore} on completion or kill.
 */
public class LocalAgentTask {

    private static final Logger log = LoggerFactory.getLogger(LocalAgentTask.class);


    public static final Duration PANEL_GRACE = Duration.ofSeconds(30);

    private final TaskState taskState;
    private final TaskStore taskStore;
    private final AtomicBoolean killed = new AtomicBoolean(false);
    private final AtomicBoolean backgroundRequested = new AtomicBoolean(false);
    private final AtomicLong progressTokens = new AtomicLong();
    private final AtomicInteger progressToolUses = new AtomicInteger();
    private final CompletableFuture<Void> backgroundSignal = new CompletableFuture<>();
    private volatile double progress;
    private volatile String currentStep;
    private volatile Thread runnerThread;
    /** Sub-engine cancellation handle — aborts the agent's query loop on kill. */
    private volatile AbortController abortController;
    private volatile Runnable stoppedByUserPersister;

    public LocalAgentTask(TaskState taskState) {
        this(taskState, null);
    }

    public LocalAgentTask(TaskState taskState, TaskStore taskStore) {
        this.taskState = taskState;
        this.taskStore = taskStore;
        this.progress = 0.0;
        this.currentStep = "initializing";
    }

    public String getTaskId() {
        return taskState.id();
    }

    public double getProgress() {
        return progress;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    /**
     * Records the virtual thread executing the sub-agent, so {@link #kill}
     * has something to interrupt in addition to the cooperative abort signal.
     */
    public void setRunnerThread(Thread thread) {
        this.runnerThread = thread;
    }

    /** True while the background virtual thread is still unwinding after completion/kill. */
    public boolean isRunnerAlive() {
        Thread thread = runnerThread;
        return thread != null && thread.isAlive();
    }

/** Wires the sub-engine's AbortController so {@link #kill} truly cancels. */
    public void setAbortController(AbortController controller) {
        this.abortController = controller;
    }

    /** Installs the sidecar writer used only for an explicit human stop. */
    public void setStoppedByUserPersister(Runnable persister) {
        this.stoppedByUserPersister = persister;
    }

    /** One-shot signal used by Ctrl+B/auto-background; never cancels the child. */
    public boolean requestBackground() {
        if (!backgroundRequested.compareAndSet(false, true)) return false;
        backgroundSignal.complete(null);
        return true;
    }

    public CompletableFuture<Void> backgroundSignal() {
        return backgroundSignal;
    }


    public void updateProgress(double progress, String step) {
        if (progress < 0 || progress > 1.0) {
            throw new IllegalArgumentException("Progress must be between 0 and 1.0");
        }
        this.progress = progress;
        this.currentStep = step;
        if (taskStore != null) {
            taskStore.updateProgressSummary(getTaskId(), step);
        }
        log.debug("Agent task {} progress: {}% - {}", taskState.id(), (int) (progress * 100), step);
    }

/**
 * Persists live Agent progress so the coordinator selector can render.
 */
    public void updateUsage(long totalTokens, int toolUses, long durationMs) {
        long normalizedTokens = Math.max(0L, totalTokens);
        int normalizedTools = Math.max(0, toolUses);
        long previousTokens = progressTokens.getAndSet(normalizedTokens);
        int previousTools = progressToolUses.getAndSet(normalizedTools);
        if (previousTokens == normalizedTokens && previousTools == normalizedTools) return;
        if (taskStore != null) {
            taskStore.updateUsage(getTaskId(), new TaskUsage(
                normalizedTokens, normalizedTools, Math.max(0L, durationMs)));
        }
    }

    /**
     * Marks the agent task as complete, persisting the sub-agent's result, aggregated usage and
     * worktree path.
     */
    public void complete(SubAgentResult result) {
        this.progress = 1.0;
        this.currentStep = "completed";
        if (taskStore != null) {
            taskStore.updateFinalMessage(getTaskId(), result.output());
            long completedTokens = Math.max(progressTokens.get(), result.progressTokens());
            int completedTools = Math.max(progressToolUses.get(), result.toolUseCount());
            if (completedTokens > 0 || completedTools > 0 || result.durationMs() > 0) {
                taskStore.updateUsage(getTaskId(), new TaskUsage(
                    completedTokens, completedTools, result.durationMs()));
            }
            result.worktreePath().ifPresent(wp -> taskStore.updateWorktree(getTaskId(), wp));
        }
        transitionIfRunning(TaskStatus.COMPLETED);
        log.info("Agent task {} completed: {}", taskState.id(), result.output());
    }

    /**
     * Marks the agent task as failed, transitioning the backing {@link TaskStore} entry to {@code
     * FAILED} if one is wired in.
     */
    public void fail(String error) {
        this.currentStep = "failed";
        if (taskStore != null) {
            taskStore.updateError(getTaskId(), error);
        }
        transitionIfRunning(TaskStatus.FAILED);
        log.info("Agent task {} failed: {}", taskState.id(), error);
    }

    /**
     * Kills the agent task.
     */
    public boolean kill() {
        return kill(false);
    }




    public boolean kill(boolean userInitiated) {
        if (taskStore == null) {
            return false;
        }
        var current = taskStore.get(getTaskId());
        if (current.isEmpty() || current.get().status() != TaskStatus.RUNNING) {
            return false;
        }
        if (!killed.compareAndSet(false, true)) {
            return false;
        }

// Compute-atomic: if complete/fail wins the race, this returns
        // the winner's terminal state instead of throwing — report no kill.
        TaskState after = taskStore.updateStatusAndMarkNotified(getTaskId(), TaskStatus.KILLED);
        if (after.status() != TaskStatus.KILLED) {
            return false;
        }
        if (userInitiated && stoppedByUserPersister != null) {
            try {
                stoppedByUserPersister.run();
            } catch (RuntimeException error) {
                log.warn("Unable to persist stoppedByUser for agent {}", getTaskId(), error);
            }
        }
        scheduleEviction();

// abortController.abort); the thread interrupt below then breaks any
        // blocking I/O the loop is parked on. Interrupt alone was best-effort —
        // the loop only observes it at blocking points, so an in-flight API
        // stream could run to completion before dying.
        AbortController controller = abortController;
        if (controller != null) {
            controller.abort("killed via /tasks");
        }
        Thread thread = runnerThread;
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        log.info("Killed background agent task {}", getTaskId());
        return true;
    }

    private void transitionIfRunning(TaskStatus target) {
        if (taskStore == null || killed.get()) {
            return;
        }
        var current = taskStore.get(getTaskId());
        if (current.isPresent() && current.get().status() == TaskStatus.RUNNING) {
// Compute-atomic: a racing kill can't be overwritten and can't
            // make this throw — a lost race just returns KILLED silently.
            TaskState after = taskStore.updateStatus(getTaskId(), target);
            if (after.status().isTerminal()) {
                scheduleEviction();
            }
        }
    }


    private void scheduleEviction() {
        if (taskStore != null) {
            taskStore.setEvictAfter(getTaskId(), Instant.now().plus(PANEL_GRACE));
        }
    }
}

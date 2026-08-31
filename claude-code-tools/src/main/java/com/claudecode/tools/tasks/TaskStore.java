package com.claudecode.tools.tasks;

import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.annotation.Explanation;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Task store, either file-backed (task-list tooling) or purely in-memory (background tasks — see
 * {@link TaskRegistry#global}).
 */
@SuppressWarnings("UnusedReturnValue")
public class TaskStore {

    private static final Logger LOG = LoggerFactory.getLogger(TaskStore.class);

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    /** Original agent prompt for TaskOutput's local_agent structured result. */
    private final Map<String, String> prompts = new ConcurrentHashMap<>();
    /** Selected local-agent definition type, e.g. general-purpose or Explore. */
    private final Map<String, String> agentTypes = new ConcurrentHashMap<>();

    private final Map<String, Instant> evictAfter = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Optional<TaskState>>> terminalWaiters =
        new ConcurrentHashMap<>();
    private final List<Consumer<TaskState>> completionListeners = new CopyOnWriteArrayList<>();

    private final Path tasksDir;
    private final boolean persistent;

    /** Default store backed by {@code <CLAUDE_CONFIG_DIR>/tasks/default/}. */
    public TaskStore() {
        this(defaultBaseDir(), "default");
    }

    /** Persistent store. {@code baseDir} is the parent of the per-list dirs. */
    public TaskStore(Path baseDir, String taskListId) {
        this(baseDir.resolve(TaskPersistence.sanitizePathComponent(taskListId)), true);
        loadFromDisk();
    }

    /** Test factory: in-memory only, never touches the filesystem. */
    public static TaskStore inMemory() {
        return new TaskStore(null, false);
    }

    private TaskStore(Path tasksDir, boolean persistent) {
        this.tasksDir = tasksDir;
        this.persistent = persistent;
    }

    /**
     * Creates a new PENDING task.
     */
    public TaskState create(TaskType type, String description) {
        return create(type, description, null);
    }

    /**
     * Creates a task owned by a specific agent.
     */
    public TaskState create(TaskType type, String description, String agentId) {
        return createWithPrefix(type, description, agentId, type.prefix());
    }

    public TaskState createWithPrefix(TaskType type, String description,
                                      String agentId, String prefix) {
        if (!persistent || tasksDir == null) {
            while (true) {
                TaskState task = TaskState.withId(
                    TaskIdGenerator.generate(prefix), type, description, agentId);
                if (tasks.putIfAbsent(task.id(), task) == null) {
                    return task;
                }
            }
        }
        String id = nextId();
        TaskState task = TaskState.withId(id, type, description, agentId);
        tasks.put(task.id(), task);
        save(task);
        return task;
    }

    /**
     * Registers a task under an already-minted runtime id.
     */
    public TaskState createWithId(String id, TaskType type, String description,
                                  String agentId) {
        Objects.requireNonNull(id, "id");
        if (StringUtils.isBlank(id)) throw new IllegalArgumentException("id must not be blank");
        TaskState task = TaskState.withId(id, type, description, agentId);
        if (tasks.putIfAbsent(id, task) != null) {
            throw new IllegalArgumentException("Task already exists: " + id);
        }
        save(task);
        return task;
    }

    /**
     * Removes a task outright — used to evict tasks whose backing process
     * never started (e.g. {@code BashTool} background spawn failing with
     * {@code IOException} after the task was already created), which would
     * otherwise linger forever as un-killable phantom rows in {@code /tasks}.
     */
    public Optional<TaskState> remove(String taskId) {
        TaskState removed = tasks.remove(taskId);
        prompts.remove(taskId);
        agentTypes.remove(taskId);
        evictAfter.remove(taskId);
        completeMissingTerminalWaiters(taskId);
        if (removed != null && persistent && tasksDir != null) {
            try {
                Files.deleteIfExists(TaskPersistence.taskPath(tasksDir, taskId));
            } catch (IOException e) {
                LOG.warn("Failed to delete task file for {}: {}", taskId, e.getMessage());
            }
        }
        return Optional.ofNullable(removed);
    }

    public Optional<TaskState> get(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** Records the original prompt separately from the short task description. */
    public void updatePrompt(String taskId, String prompt) {
        if (prompt == null) {
            prompts.remove(taskId);
        } else {
            prompts.put(taskId, prompt);
        }
    }

    /** Returns the original prompt when the task was created by an Agent tool. */
    public Optional<String> prompt(String taskId) {
        return Optional.ofNullable(prompts.get(taskId));
    }




    public void updateAgentType(String taskId, String agentType) {
        if (StringUtils.isBlank(agentType)) {
            agentTypes.remove(taskId);
        } else {
            agentTypes.put(taskId, agentType);
        }
    }

    /** Returns the selected Agent definition type for a local-agent task. */
    public Optional<String> agentType(String taskId) {
        return Optional.ofNullable(agentTypes.get(taskId));
    }


    public void setEvictAfter(String taskId, Instant deadline) {
        if (deadline == null) {
            evictAfter.remove(taskId);
        } else {
            evictAfter.put(taskId, deadline);
        }
    }

    /** Returns the panel eviction deadline for a task, if one is scheduled. */
    public Optional<Instant> evictAfter(String taskId) {
        return Optional.ofNullable(evictAfter.get(taskId));
    }

    public List<TaskState> list() {
        return List.copyOf(tasks.values());
    }

    public List<TaskState> listByStatus(TaskStatus status) {
        return tasks.values().stream()
            .filter(t -> t.status() == status)
            .toList();
    }

    /**
     * Waits until a task reaches a terminal state, disappears, or the timeout expires.
     */
    public Optional<TaskState> awaitTerminal(String taskId, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(timeout, "timeout");

        TaskState current = tasks.get(taskId);
        if (current == null || current.status().isTerminal()) {
            return Optional.ofNullable(current);
        }

        CompletableFuture<Optional<TaskState>> waiter = terminalWaiters.computeIfAbsent(
            taskId, _ -> new CompletableFuture<>());

        // Close the race between the first state read and waiter registration.
        current = tasks.get(taskId);
        if (current == null) {
            completeMissingTerminalWaiters(taskId);
            return Optional.empty();
        }
        if (current.status().isTerminal()) {
            Optional<TaskState> terminal = Optional.of(current);
            completeTerminalWaiters(taskId, current);
            return terminal;
        }

        try {
            return waiter.get(Math.max(0L, timeout.toNanos()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException _) {
            return Optional.ofNullable(tasks.get(taskId));
        } catch (ExecutionException e) {
            throw new IllegalStateException("Task completion signal failed for " + taskId,
                e.getCause());
        }
    }

    /**
     * Atomically transitions a task's status.
     */
    public TaskState updateStatus(String taskId, TaskStatus newStatus) {
        return updateStatus(taskId, newStatus, false);
    }

    /**
     * Atomically transitions to a terminal state already owned by an explicit
     * cancel flow. Setting {@code notified} in the same map compute prevents a
     * completion listener from observing the intermediate KILLED/unnotified
     * state and emitting a duplicate per-task notification.
     */
    @Explanation("atomic status+notified transition for Java listener safety")
    public TaskState updateStatusAndMarkNotified(String taskId, TaskStatus newStatus) {
        if (!newStatus.isTerminal()) {
            throw new IllegalArgumentException("notified status must be terminal: " + newStatus);
        }
        return updateStatus(taskId, newStatus, true);
    }

    private TaskState updateStatus(String taskId, TaskStatus newStatus, boolean markNotified) {
        TaskState[] transitioned = new TaskState[1];
        TaskState result = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            if (!TaskStateMachine.isValidTransition(current.status(), newStatus)) {
                if (current.status().isTerminal()) {
                    return current; // benign lost race — a terminal transition already won
                }
                throw new IllegalStateException(
                    "Invalid task state transition: " + current.status() + " → " + newStatus);
            }
            TaskState updated = current.withStatus(newStatus);
            if (markNotified) updated = updated.withNotified(true);
            transitioned[0] = updated;
            return updated;
        });
        if (transitioned[0] != null) {
            save(transitioned[0]);
            if (newStatus.isTerminal()) {
                completeTerminalWaiters(taskId, transitioned[0]);
            }
// Only an outcome is announced.
            if (newStatus.hasResult()) {
                notifyCompletion(transitioned[0]);
            }
        }
        return result;
    }

    /**
     * Records a background-agent progress summary.
     */
    public TaskState updateProgressSummary(String taskId, String summary) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.status() == TaskStatus.RUNNING
                ? current.withProgressSummary(summary) : current;
        });
        save(updated);
        return updated;
    }

    /** Stamps the model-emitted tool_use id used by completion notifications. */
    public TaskState updateToolUseId(String taskId, String toolUseId) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withToolUseId(toolUseId);
        });
        save(updated);
        return updated;
    }


    public TaskState initializeDream(String taskId, int sessionsReviewing) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            if (current.type() != TaskType.DREAM) {
                throw new IllegalArgumentException("Task is not a dream: " + taskId);
            }
            return current.withDreamDetails(DreamTaskDetails.starting(sessionsReviewing));
        });
        save(updated);
        return updated;
    }


    public TaskState addDreamTurn(String taskId, DreamTaskDetails.DreamTurn turn,
            List<String> touchedPaths) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            if (current.type() != TaskType.DREAM || current.dreamDetails().isEmpty()) {
                throw new IllegalStateException("Dream task is not initialized: " + taskId);
            }
            if (current.status().isTerminal()) return current;
            return current.withDreamDetails(current.dreamDetails().get().addTurn(turn, touchedPaths));
        });
        save(updated);
        return updated;
    }

    /**
     * Records a background-agent failure message.
     */
    public TaskState updateError(String taskId, String errorMessage) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withErrorMessage(errorMessage);
        });
        save(updated);
        return updated;
    }

    /**
     * Records a background-agent completion result.
     */
    public TaskState updateFinalMessage(String taskId, String finalMessage) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withFinalMessage(finalMessage);
        });
        save(updated);
        return updated;
    }

    /**
     * Records a background-agent aggregated usage.
     */
    public TaskState updateUsage(String taskId, TaskUsage usage) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withUsage(usage);
        });
        save(updated);
        return updated;
    }

    /**
     * Records a background-agent worktree path.
     */
    public TaskState updateWorktree(String taskId, String worktreePath) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withWorktreePath(worktreePath);
        });
        save(updated);
        return updated;
    }

    /**
     * Records a background bash task's exit code.
     */
    public TaskState updateExitCode(String taskId, int exitCode) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withExitCode(exitCode);
        });
        save(updated);
        return updated;
    }

    /**
     * Marks a task as notified without touching its status.
     */
    public TaskState markNotified(String taskId) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.notified() ? current : current.withNotified(true);
        });
        save(updated);
        return updated;
    }

    /**
     * Marks a task claimed by the given agent/leader (or unclaimed when {@code
     * agentId} is {@code null}). Atomic per-task (same compute-based contract as
     * {@link #updateStatus}). Used by the agent-teams subsystem: a teammate's
     * {@code idle_notification} clears the claim (available) and a leader dispatch
     * sets it, so two leaders cannot grab the same teammate.
     */
    public TaskState claim(String taskId, String agentId) {
        TaskState updated = tasks.compute(taskId, (_, current) -> {
            if (current == null) {
                throw new NoSuchElementException("Task not found: " + taskId);
            }
            return current.withClaimedBy(agentId);
        });
        save(updated);
        return updated;
    }

    /**
     * Subscribes to terminal transitions and returns ownership of that
     * subscription.  The copy-on-write list keeps registration/removal safe
     * while virtual-thread task completions are dispatching concurrently.
     */
    @Explanation("explicit lifecycle for Java completion-listener subscriptions")
    public AutoCloseable onCompletion(Consumer<TaskState> listener) {
        Objects.requireNonNull(listener, "listener");
        completionListeners.add(listener);
        return () -> completionListeners.remove(listener);
    }

    private void notifyCompletion(TaskState task) {
        for (Consumer<TaskState> listener : completionListeners) {
            try {
                listener.accept(task);
            } catch (Exception _) {
                // Don't let listener failures propagate
            }
        }
    }

    private void completeTerminalWaiters(String taskId, TaskState result) {
        CompletableFuture<Optional<TaskState>> waiter = terminalWaiters.remove(taskId);
        if (waiter != null) {
            waiter.complete(Optional.of(result));
        }
    }

    private void completeMissingTerminalWaiters(String taskId) {
        CompletableFuture<Optional<TaskState>> waiter = terminalWaiters.remove(taskId);
        if (waiter != null) {
            waiter.complete(Optional.empty());
        }
    }

    public int size() {
        return tasks.size();
    }



    






    private synchronized String nextId() {
        // Only reachable for persistent stores — in-memory (background) stores
        // take the TaskIdGenerator path in create(); see class Javadoc.
        try {
            return TaskPersistence.nextSequentialId(tasksDir);
        } catch (Exception e) {
            LOG.warn("Failed to generate sequential task ID: {}", e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void save(TaskState task) {
        if (!persistent || tasksDir == null) return;
        try {
            TaskPersistence.save(tasksDir, task.id(), task);
        } catch (Exception e) {
            LOG.warn("Failed to persist task {}: {}", task.id(), e.getMessage());
        }
    }

    /**
     * Loads tasks from disk on construction. Malformed files are skipped with
     * a warning — one bad file should not block the whole store.
     */
    private void loadFromDisk() {
        if (!persistent || tasksDir == null || !Files.isDirectory(tasksDir)) return;
        try {
            for (TaskState task : TaskPersistence.loadAll(tasksDir, TaskState.class,
                    (file, error) -> LOG.warn(
                            "Skipping malformed task file {}: {}", file, error.getMessage()))) {
                tasks.put(task.id(), task);
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan tasks directory {}: {}", tasksDir, e.getMessage());
        }
    }

    private static Path defaultBaseDir() {
        return ClaudePaths.TASKS_DIR;
    }
}

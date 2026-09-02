package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.config.ClaudePaths;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.UnaryOperator;

/**
 * Persistent store for the model-facing to-do list (TaskCreate / TaskGet / TaskList / TaskUpdate
 * tools) — a separate system from the background-task store ({@link TaskStore}/{@link
 * TaskRegistry}) and its transient output files (see {@link TaskOutputPaths}).
 */
public class TodoStore {

    private static final Logger LOG = LoggerFactory.getLogger(TodoStore.class);

    /**
     * Optional user-configured {@code TaskCreated}/{@code TaskCompleted} hook seam.
     * Null when unwired (tests, or a build without hook wiring) → hooks are a no-op.
     * Injected by the composition root ({@code ClaudeCodeCli}). See {@link TaskLifecycleHooks}.
     */
    private static volatile TaskLifecycleHooks taskLifecycleHooks;

    /** Wires the {@code TaskCreated}/{@code TaskCompleted} hook seam (composition root only). */
    public static void setTaskLifecycleHooks(TaskLifecycleHooks hooks) {
        taskLifecycleHooks = hooks;
    }

    /** Exposes the currently wired hook seam (may be {@code null}) to the model-facing tools. */
    public static TaskLifecycleHooks getTaskLifecycleHooks() {
        return taskLifecycleHooks;
    }

    private final Path tasksDir;

/** In-memory match keyed by task ID — read path stays O(1), like {@link TaskStore}. */
    private Map<String, Task> tasks = new LinkedHashMap<>();

    public TodoStore(String taskListId) {
        this(ClaudePaths.TASKS_DIR, taskListId);
    }

    TodoStore(Path tasksBaseDir, String taskListId) {
        this.tasksDir = tasksBaseDir.resolve(TaskPersistence.sanitizePathComponent(taskListId));
        loadFromDisk();
    }

    private TodoStore() {
        this.tasksDir = null;
    }

    /** Test factory: in-memory only, never touches the filesystem. */
    public static TodoStore inMemory() {
        return new TodoStore();
    }


    public synchronized Task create(String subject, String description, String activeForm, Map<String, Object> metadata) {
        if (tasksDir == null) {
            Task task = newTask(nextId(), subject, description, activeForm, metadata);
            tasks.put(task.id(), task);
            return task;
        }
        try {
            Task task = TaskPersistence.createSequential(tasksDir,
                id -> newTask(id, subject, description, activeForm, metadata), true);
            tasks.put(task.id(), task);
            return task;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist new task: " + e.getMessage(), e);
        }
    }

    private static Task newTask(
            String id, String subject, String description, String activeForm,
            Map<String, Object> metadata) {
        return new Task(
            id, subject, description == null ? "" : description,
            Optional.ofNullable(activeForm), Optional.empty(), TodoStatus.PENDING,
            List.of(), List.of(), Optional.ofNullable(metadata));
    }

    public synchronized Optional<Task> get(String taskId) {
        return Optional.ofNullable(tasks.get(storageKey(taskId)));
    }

    public synchronized List<Task> list() {
        return TaskPersistence.sortLikeReleasedTaskList(List.copyOf(tasks.values()));
    }

/** Replaces the in-memory snapshot with the current on-disk task files. */
    public synchronized void reload() {
        if (tasksDir == null) return;
        Map<String, Task> reloaded = new LinkedHashMap<>();
        if (Files.isDirectory(tasksDir)) {
            try {
                for (TaskPersistence.StoredTask stored : TaskPersistence.loadStoredTasks(tasksDir,
                        (file, error) -> LOG.warn(
                            "Skipping malformed task file {} during reload: {}", file,
                            error.getMessage()))) {
                    reloaded.put(stored.storageId(), stored.task());
                }
            } catch (IOException e) {
                LOG.warn("Failed to reload tasks directory {}: {}", tasksDir, e.getMessage());
                return;
            }
        }
        tasks = reloaded;
    }

/** Clears all task files while preserving the sequential-ID highwatermark. */
    public synchronized boolean reset() {
        if (tasksDir == null) {
            tasks = new LinkedHashMap<>();
            return true;
        }
        try {
            TaskPersistence.resetTaskList(tasksDir);
            tasks = new LinkedHashMap<>();
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to reset tasks directory {}: {}", tasksDir, e.getMessage());
            return false;
        }
    }

    Path tasksDir() {
        return tasksDir;
    }

    String taskListId() {
        return tasksDir == null ? "" : tasksDir.getFileName().toString();
    }

    /**
     * Applies a partial update to an existing task. Returns empty if the task
     * doesn't exist. Callers pass only the fields they want changed —
     * {@code null} means "leave unchanged" for every parameter except
     * {@code metadata}, which uses its own null-deletes-key merge semantics
     * (see {@code TaskUpdateTool}).
     */
    public synchronized Optional<Task> update(String taskId, Task updated) {
        return updateAtomically(taskId, _ -> updated);
    }

    synchronized Optional<Task> updateAtomically(
            String taskId, UnaryOperator<Task> updater) {
        if (tasksDir == null) {
            String storageId = storageKey(taskId);
            Task current = tasks.get(storageId);
            if (current == null) return Optional.empty();
            Task updated = updater.apply(current).withId(taskId);
            tasks.put(storageId, updated);
            return Optional.of(updated);
        }
        try {
            Optional<Task> updated = TaskPersistence.updateTask(tasksDir, taskId, updater);
            updated.ifPresent(task -> tasks.put(storageKey(taskId), task));
            return updated;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to persist task " + taskId + ": " + e.getMessage(), e);
        }
    }

    synchronized Optional<Task> claim(String taskId, String owner) {
        String storageId = storageKey(taskId);
        if (tasksDir == null) {
            Task current = tasks.get(storageId);
            if (current == null) return Optional.empty();
            String currentOwner = current.owner().orElse(null);
            if (StringUtils.isNotEmpty(currentOwner)
                    && !currentOwner.equals(owner)) {
                return Optional.empty();
            }
            if (current.status() == TodoStatus.COMPLETED) return Optional.empty();
            Set<String> unresolved = tasks.values().stream()
                .filter(task -> task.status() != TodoStatus.COMPLETED)
                .map(Task::id)
                .collect(Collectors.toSet());
            if (current.blockedBy().stream().anyMatch(unresolved::contains)) {
                return Optional.empty();
            }
            Task claimed = current.withOwner(owner).withId(taskId);
            tasks.put(storageId, claimed);
            return Optional.of(claimed);
        }
        try {
            Optional<Task> claimed = TaskPersistence.claimTask(
                tasksDir, taskId, owner,
                (file, error) -> LOG.warn(
                    "Skipping malformed task file {} while claiming task {}: {}", file,
                    taskId, error.getMessage()));
            claimed.ifPresent(task -> tasks.put(storageId, task));
            return claimed;
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to claim task " + taskId + ": " + e.getMessage(), e);
        }
    }

    /**
     * Deletes a task and strips references to it from every other task's {@code blocks}/{@code
     * blockedBy} lists.
     */
    public synchronized boolean delete(String taskId) {
        String storageId = storageKey(taskId);
        Task removed = tasks.get(storageId);
        if (removed == null) {
            return false;
        }
        if (!bumpHighWaterMarkPast(taskId)) {
            return false;
        }
        tasks.remove(storageId);
        if (tasksDir != null) {
            try {
                boolean deleted = Files.deleteIfExists(TaskPersistence.taskPath(tasksDir, taskId));
                if (!deleted) {
                    reload();
                    return false;
                }
            } catch (IOException e) {
                tasks.put(storageId, removed);
                LOG.warn("Failed to delete task file for {}: {}", taskId, e.getMessage());
                return false;
            }
            reload();
        }
        try {
            for (Task other : list()) {
                if (!other.blocks().contains(taskId) && !other.blockedBy().contains(taskId)) {
                    continue;
                }
                updateAtomically(other.id(), current -> {
                    List<String> newBlocks = current.blocks().stream()
                        .filter(id -> !id.equals(taskId)).toList();
                    List<String> newBlockedBy = current.blockedBy().stream()
                        .filter(id -> !id.equals(taskId)).toList();
                    return current.withBlocks(newBlocks).withBlockedBy(newBlockedBy);
                });
            }
        } catch (RuntimeException e) {
            if (tasksDir != null) reload();
            LOG.warn("Failed to clean task relationships after deleting {}: {}",
                taskId, e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Records a bidirectional block relationship: {@code fromTaskId} blocks {@code toTaskId}
     * (equivalently, {@code toTaskId} is blockedBy {@code fromTaskId}).
     */
    public synchronized boolean block(String fromTaskId, String toTaskId) {
        if (!tasks.containsKey(storageKey(fromTaskId))
                || !tasks.containsKey(storageKey(toTaskId))) {
            return false;
        }
        Optional<Task> from = updateAtomically(fromTaskId, current -> {
            if (current.blocks().contains(toTaskId)) return current;
            List<String> blocks = new ArrayList<>(current.blocks());
            blocks.add(toTaskId);
            return current.withBlocks(blocks);
        });
        Optional<Task> to = updateAtomically(toTaskId, current -> {
            if (current.blockedBy().contains(fromTaskId)) return current;
            List<String> blockedBy = new ArrayList<>(current.blockedBy());
            blockedBy.add(fromTaskId);
            return current.withBlockedBy(blockedBy);
        });
        return from.isPresent() && to.isPresent();
    }



    private String nextId() {
        if (tasksDir == null) {
            // In-memory store: highest in-memory numeric ID + 1.
            double highest = tasks.keySet().stream()
                .mapToDouble(TodoStore::parseIntOrZero).max().orElse(0);
            return TaskPersistence.javaScriptNumberToString(highest + 1);
        }
        try {
            return TaskPersistence.nextSequentialId(tasksDir);
        } catch (Exception e) {
            LOG.warn("Failed to generate sequential task ID: {}", e.getMessage());
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private boolean bumpHighWaterMarkPast(String taskId) {
        if (tasksDir == null) return true;
        double numericId = parseIntOrZero(taskId);
        if (numericId <= 0) return true;
        try {
            TaskPersistence.ensureHighWaterMarkAtLeast(tasksDir, numericId);
            return true;
        } catch (IOException e) {
            LOG.warn("Failed to write highwatermark: {}", e.getMessage());
            return false;
        }
    }

    private static double parseIntOrZero(String value) {
        return TaskPersistence.parseIntOrZero(value);
    }

    private void loadFromDisk() {
        if (tasksDir == null || !Files.isDirectory(tasksDir)) return;
        try {
            for (TaskPersistence.StoredTask stored : TaskPersistence.loadStoredTasks(tasksDir,
                    (file, error) -> LOG.warn(
                            "Skipping malformed task file {} while loading store: {}",
                            file, error.getMessage()))) {
                tasks.put(stored.storageId(), stored.task());
            }
        } catch (IOException e) {
            LOG.warn("Failed to scan tasks directory {}: {}", tasksDir, e.getMessage());
        }
    }

    private String storageKey(String taskId) {
        return tasksDir == null ? taskId : TaskPersistence.sanitizePathComponent(taskId);
    }

}

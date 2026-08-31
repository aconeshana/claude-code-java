package com.claudecode.tools.tasks;

import org.apache.commons.lang3.StringUtils;

import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.tasks.teammate.TeammateContext;
import com.claudecode.tools.tasks.teammate.TeammateContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Session-aware task-board service shared by tools, attachments, and UI. */
public final class TaskBoardService implements TaskBoardPort, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TaskBoardService.class);
    private static final long FILE_DEBOUNCE_MS = 50L;
    private static final long FALLBACK_POLL_SECONDS = 5L;
    private final TodoStore fallbackStore;
    private final Supplier<String> currentSessionId;
    private final BooleanSupplier taskToolsEnabled;
    private final ConcurrentHashMap<String, TodoStore> sessionStores = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> snapshotListeners =
        new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<Intent>> intentListeners =
        new CopyOnWriteArrayList<>();
    private final AtomicLong revision = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
        runnable -> Thread.ofVirtual().name("task-board-monitor").unstarted(runnable));
    private volatile Snapshot latest = Snapshot.EMPTY;
    private volatile ScheduledFuture<?> pollFuture;
    private volatile ScheduledFuture<?> hideFuture;
    private volatile ScheduledFuture<?> fileDebounceFuture;
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile Path watchedTasksDir;
    private volatile boolean watchedTaskDirectoryRegistered;
    private volatile AutoCloseable sessionChangeSubscription = () -> { };
    private volatile boolean closed;

    public TaskBoardService(TodoStore fallbackStore, Supplier<String> currentSessionId) {
        this(fallbackStore, currentSessionId, TaskToolsGate::isEnabled);
    }

    TaskBoardService(
            TodoStore fallbackStore,
            Supplier<String> currentSessionId,
            BooleanSupplier taskToolsEnabled) {
        this.fallbackStore = Objects.requireNonNull(fallbackStore, "fallbackStore");
        this.currentSessionId = Objects.requireNonNull(currentSessionId, "currentSessionId");
        this.taskToolsEnabled = Objects.requireNonNull(taskToolsEnabled, "taskToolsEnabled");
    }

    public TaskBoardService(TodoStore fallbackStore, SessionIdentity sessionIdentity) {
        this(fallbackStore, sessionIdentity, TaskToolsGate::isEnabled);
    }

    TaskBoardService(
            TodoStore fallbackStore,
            SessionIdentity sessionIdentity,
            BooleanSupplier taskToolsEnabled) {
        this(fallbackStore, Objects.requireNonNull(sessionIdentity, "sessionIdentity")::get,
            taskToolsEnabled);
        sessionChangeSubscription = sessionIdentity.subscribeChanges(this::sessionChanged);
    }

    TodoStore resolveStore(String sessionId) {
        String configuredListId = SubprocessEnvironment.get("CLAUDE_CODE_TASK_LIST_ID");
        if (fallbackStore.tasksDir() != null && StringUtils.isNotEmpty(configuredListId)) {
            return storeForListId(configuredListId);
        }
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null && StringUtils.isNotBlank(teammate.teamId())) {
            return TeamTaskListRegistry.instance().get(teammate.teamId())
                .orElseGet(() -> fallbackStore.tasksDir() == null
                    ? TeamTaskListRegistry.instance().getOrCreate(teammate.teamId())
                    : storeForListId(teammate.teamId()));
        }
        TodoStore resolved = TeamTaskListRegistry.instance()
            .resolveForSession(sessionId, fallbackStore);
        if (resolved != fallbackStore || fallbackStore.tasksDir() == null
                || StringUtils.isBlank(sessionId)) {
            return resolved;
        }
        return storeForListId(sessionId);
    }

    private TodoStore storeForListId(String listId) {
        String sanitized = TaskPersistence.sanitizePathComponent(listId);
        if (sanitized.equals(fallbackStore.taskListId())) return fallbackStore;
        return sessionStores.computeIfAbsent(sanitized, id ->
            new TodoStore(fallbackStore.tasksDir().getParent(), id));
    }

    List<Task> tasksForSession(String sessionId) {
        TodoStore store = resolveStore(sessionId);
        store.reload();
        return visibleTasks(store);
    }

    List<Task> currentTasks() {
        TodoStore store = resolveStore(currentSessionId.get());
        store.reload();
        return store.list();
    }

    String currentTeammateName() {
        TeammateContext teammate = TeammateContextHolder.get();
        return teammate == null || StringUtils.isBlank(teammate.name()) ? null : teammate.name();
    }

    String currentTeamName(String sessionId) {
        TeammateContext teammate = TeammateContextHolder.get();
        if (teammate != null && StringUtils.isNotBlank(teammate.teamId())) {
            return teammate.teamId();
        }
        return TeamTaskListRegistry.instance().teamIdForSession(sessionId).orElse(null);
    }

    @Override
    public Snapshot snapshot() {
        return refreshSnapshot(currentSessionId.get()).snapshot();
    }

    void publishChanged(String sessionId) {
        if (closed || !Objects.equals(sessionId, currentSessionId.get())) return;
        publishCurrentSnapshot();
    }

    void publishExpand(String sessionId) {
        if (closed || !Objects.equals(sessionId, currentSessionId.get())) return;
        notifyListeners(intentListeners, Intent.EXPAND_TASKS, "intent");
    }

    @Override
    public AutoCloseable subscribe(Consumer<Snapshot> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) return () -> { };
        snapshotListeners.addIfAbsent(listener);
        if (snapshotListeners.size() == 1) {
            updateLifecycle(readSnapshot(currentSessionId.get()));
        }
        return () -> {
            snapshotListeners.remove(listener);
            if (snapshotListeners.isEmpty()) cancelMonitoring();
        };
    }

    @Override
    public AutoCloseable subscribeIntents(Consumer<Intent> listener) {
        Objects.requireNonNull(listener, "listener");
        if (closed) return () -> { };
        intentListeners.addIfAbsent(listener);
        return () -> intentListeners.remove(listener);
    }

    @Override
    public void close() {
        closed = true;
        cancelMonitoring();
        closeQuietly(sessionChangeSubscription);
        scheduler.shutdownNow();
        snapshotListeners.clear();
        intentListeners.clear();
    }

    private void sessionChanged(String newSessionId) {
        if (closed || snapshotListeners.isEmpty()
                || !Objects.equals(newSessionId, currentSessionId.get())) {
            return;
        }
        cancelHide();
        restartWatcher();
        publishCurrentSnapshot();
    }

    private void publishCurrentSnapshot() {
        SnapshotRefresh refresh = refreshSnapshot(currentSessionId.get());
        updateLifecycle(refresh.snapshot());
        if (refresh.changed()) {
            notifyListeners(snapshotListeners, refresh.snapshot(), "snapshot");
        }
    }

    private synchronized void updateLifecycle(Snapshot snapshot) {
        if (closed || snapshotListeners.isEmpty()) return;
        if (!taskToolsEnabled.getAsBoolean()) {
            cancelPoll();
            cancelHide();
            stopWatcher();
            return;
        }
        ensureWatcher();
        boolean incomplete = snapshot.tasks().stream()
            .anyMatch(task -> task.status() != Status.COMPLETED);
        if (incomplete) schedulePoll();
        else cancelPoll();
        if (incomplete || snapshot.tasks().isEmpty()) {
            cancelHide();
            return;
        }
        if (hideFuture != null) return;
        String expectedListId = snapshot.listId();
        hideFuture = scheduler.schedule(
            () -> clearIfStillCompleted(expectedListId), 5, TimeUnit.SECONDS);
    }

    private synchronized void schedulePoll() {
        if (pollFuture != null) return;
        pollFuture = scheduler.schedule(() -> {
            pollFuture = null;
            if (closed || snapshotListeners.isEmpty()) return;
            publishCurrentSnapshot();
        }, FALLBACK_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void clearIfStillCompleted(String expectedListId) {
        hideFuture = null;
        if (closed || snapshotListeners.isEmpty() || !taskToolsEnabled.getAsBoolean()) return;
        String sessionId = currentSessionId.get();
        TodoStore store = resolveStore(sessionId);
        if (!Objects.equals(expectedListId, effectiveListId(store, sessionId))) return;
        store.reload();
        List<Task> tasks = store.list();
        if (!tasks.isEmpty() && tasks.stream().allMatch(task -> task.status() == TodoStatus.COMPLETED)
                && store.reset()) {
            publishCurrentSnapshot();
        }
    }

    private synchronized void ensureWatcher() {
        if (closed || snapshotListeners.isEmpty() || !taskToolsEnabled.getAsBoolean()) return;
        TodoStore store = resolveStore(currentSessionId.get());
        Path tasksDir = store.tasksDir();
        if (tasksDir == null) return;
        boolean directoryNowExists = Files.isDirectory(tasksDir);
        if (watchService == null || !Objects.equals(tasksDir, watchedTasksDir)
                || (directoryNowExists && !watchedTaskDirectoryRegistered)) {
            restartWatcher();
        }
    }

    private synchronized void restartWatcher() {
        stopWatcher();
        if (closed || snapshotListeners.isEmpty() || !taskToolsEnabled.getAsBoolean()) return;
        TodoStore store = resolveStore(currentSessionId.get());
        Path tasksDir = store.tasksDir();
        if (tasksDir == null || tasksDir.getParent() == null) return;
        Path baseDir = tasksDir.getParent();
        try {
            Files.createDirectories(baseDir);
            WatchService next = FileSystems.getDefault().newWatchService();
            baseDir.register(next, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE);
            boolean taskDirectoryRegistered = Files.isDirectory(tasksDir);
            if (taskDirectoryRegistered) {
                tasksDir.register(next, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            }
            watchService = next;
            watchedTasksDir = tasksDir;
            watchedTaskDirectoryRegistered = taskDirectoryRegistered;
            watchThread = Thread.ofVirtual().name("task-board-watch").start(
                () -> watchLoop(next, baseDir, tasksDir));
        } catch (IOException e) {
            LOG.debug("Unable to watch task list {}: {}", tasksDir, e.getMessage());
        }
    }

    private void watchLoop(WatchService watcher, Path baseDir, Path tasksDir) {
        try {
            while (!closed && watcher == watchService) {
                WatchKey key = watcher.take();
                Path watched = (Path) key.watchable();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        relevant = true;
                        continue;
                    }
                    if (!(event.context() instanceof Path changed)) continue;
                    if (Objects.equals(watched, tasksDir)) {
                        relevant = true;
                    } else if (Objects.equals(watched, baseDir)
                            && Objects.equals(changed.getFileName(), tasksDir.getFileName())) {
                        relevant = true;
                    }
                }
                if (!key.reset()) relevant = true;
                if (relevant) scheduleFileRefresh();
            }
        } catch (ClosedWatchServiceException _) {
            // Normal monitor reconfiguration or shutdown.
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.debug("Task-board watcher stopped: {}", e.getMessage());
        }
    }

    private synchronized void scheduleFileRefresh() {
        if (closed || snapshotListeners.isEmpty()) return;
        ScheduledFuture<?> current = fileDebounceFuture;
        if (current != null) current.cancel(false);
        fileDebounceFuture = scheduler.schedule(() -> {
            fileDebounceFuture = null;
            if (closed || snapshotListeners.isEmpty()) return;
            ensureWatcher();
            publishCurrentSnapshot();
        }, FILE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelMonitoring() {
        cancelPoll();
        cancelHide();
        ScheduledFuture<?> debounce = fileDebounceFuture;
        fileDebounceFuture = null;
        if (debounce != null) debounce.cancel(false);
        stopWatcher();
    }

    private void cancelPoll() {
        ScheduledFuture<?> current = pollFuture;
        pollFuture = null;
        if (current != null) current.cancel(false);
    }

    private void cancelHide() {
        ScheduledFuture<?> current = hideFuture;
        hideFuture = null;
        if (current != null) current.cancel(false);
    }

    private synchronized void stopWatcher() {
        WatchService current = watchService;
        watchService = null;
        watchedTasksDir = null;
        watchedTaskDirectoryRegistered = false;
        if (current != null) {
            try {
                current.close();
            } catch (IOException _) {
                // Best-effort monitor teardown.
            }
        }
        Thread currentThread = watchThread;
        watchThread = null;
        if (currentThread != null && currentThread != Thread.currentThread()) {
            currentThread.interrupt();
        }
    }

    private synchronized SnapshotRefresh refreshSnapshot(String sessionId) {
        Snapshot candidate = readSnapshot(sessionId);
        if (sameContent(latest, candidate)) return new SnapshotRefresh(latest, false);
        Snapshot next = new Snapshot(candidate.listId(), revision.incrementAndGet(),
            candidate.tasks(), candidate.hidden());
        latest = next;
        return new SnapshotRefresh(next, true);
    }

    private Snapshot readSnapshot(String sessionId) {
        if (!taskToolsEnabled.getAsBoolean()) return Snapshot.EMPTY;
        TodoStore store = resolveStore(sessionId);
        store.reload();
        List<TaskItem> items = visibleTasks(store).stream().map(TaskBoardService::toItem).toList();
        return new Snapshot(effectiveListId(store, sessionId), 0L, items, items.isEmpty());
    }

    private static boolean sameContent(Snapshot left, Snapshot right) {
        return Objects.equals(left.listId(), right.listId())
            && left.hidden() == right.hidden()
            && left.tasks().equals(right.tasks());
    }

    private static String effectiveListId(TodoStore store, String sessionId) {
        String listId = store.taskListId();
        return listId.isEmpty() ? Objects.requireNonNullElse(sessionId, "") : listId;
    }

    private static List<Task> visibleTasks(TodoStore store) {
        return store.list().stream()
            .filter(task -> !isJavaScriptTruthy(task.metadata().get("_internal")))
            .toList();
    }

    private static boolean isJavaScriptTruthy(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) {
            double numeric = number.doubleValue();
            return numeric != 0.0d && !Double.isNaN(numeric);
        }
        if (value instanceof CharSequence text) return !text.isEmpty();
        return true;
    }

    private static TaskItem toItem(Task task) {
        Status status = switch (task.status()) {
            case PENDING -> Status.PENDING;
            case IN_PROGRESS -> Status.IN_PROGRESS;
            case COMPLETED -> Status.COMPLETED;
        };
        return new TaskItem(
            task.id(), task.subject(), task.description(), task.activeForm().orElse(null),
            task.owner().orElse(null), status, task.blocks(), task.blockedBy());
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception _) {
            // Best-effort lifecycle teardown.
        }
    }

    private static <T> void notifyListeners(
            List<Consumer<T>> listeners, T value, String channel) {
        for (Consumer<T> listener : listeners) {
            try {
                listener.accept(value);
            } catch (RuntimeException e) {
                LOG.warn("Task board {} listener failed: {}", channel, e.getMessage());
            }
        }
    }

    private record SnapshotRefresh(Snapshot snapshot, boolean changed) { }
}

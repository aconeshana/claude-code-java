package com.claudecode.tools.tasks;


import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.core.annotation.Explanation;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.tools.workflows.WorkflowRun;
import com.claudecode.tools.workflows.WorkflowTask;

import java.util.Comparator;
import java.util.ArrayList;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.Set;


public final class TaskRegistry {

    private static volatile TaskRegistry GLOBAL;


    private static final long TERMINAL_EVICTION_GRACE_MS = 30_000L;
    private static final ScheduledThreadPoolExecutor TERMINAL_EVICTION_TIMER =
        createTerminalEvictionTimer();

    private final TaskStore taskStore;
    private final Map<String, LocalShellTask> shellHandles = new ConcurrentHashMap<>();
    private final Map<String, ForegroundShellTask> foregroundShellHandles = new ConcurrentHashMap<>();
    private final Map<String, LocalAgentTask> agentHandles = new ConcurrentHashMap<>();

    private final Map<String, String> agentNames = new ConcurrentHashMap<>();
    /** Local-agent ids registered as foreground and therefore hidden from /tasks. */
    private final Set<String> foregroundAgentIds = ConcurrentHashMap.newKeySet();
    private final Map<String, InProcessTeammateTask> teammateHandles = new ConcurrentHashMap<>();

    private final Map<String, ConcurrentLinkedQueue<PendingAgentMessage>> pendingAgentMessages =
        new ConcurrentHashMap<>();
    private final Map<String, AutoDreamHandle> dreamHandles = new ConcurrentHashMap<>();
    private final Map<String, WorkflowTask> workflowHandles = new ConcurrentHashMap<>();
    private final Map<String, MonitorTaskHandle> monitorHandles = new ConcurrentHashMap<>();
    private final Map<String, Long> terminalEvictionDeadlines = new ConcurrentHashMap<>();
    private final Set<String> notificationClaims = ConcurrentHashMap.newKeySet();
    private final Object notificationBindingLock = new Object();
    private volatile AutoCloseable completionSubscription;
    // Session message queue, wired once at the composition root (UI) so the
    // stall watchdog in LocalShellTask can enqueue an interactive-prompt
    // notification without a tool-level reference to the engine. Null until
    // wired — the watchdog is a no-op when unset (e.g. in tests).
    private volatile MessageQueueManager messageQueue;

    public TaskRegistry(TaskStore taskStore) {
        this.taskStore = taskStore;
    }

    private static ScheduledThreadPoolExecutor createTerminalEvictionTimer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(
            1, Thread.ofVirtual().name("task-eviction-", 0).factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** Wires the session {@link MessageQueueManager} (composition root only). */
    public void setMessageQueue(MessageQueueManager messageQueue) {
        this.messageQueue = messageQueue;
    }

    /** The wired session queue, or {@code null} if not yet configured. */
    public MessageQueueManager messageQueue() {
        return messageQueue;
    }

    /**
     * Rebinds delivery to the latest session and installs exactly one store
     * listener. The reconciliation pass closes the race where a task reaches a
     * terminal state immediately before the listener is installed.
     */
    @Explanation("single owned listener replacing per-session Java subscriptions")
    void bindTaskNotifications() {
        synchronized (notificationBindingLock) {
            if (messageQueue == null) {
                throw new IllegalStateException("message queue must be wired before registration");
            }
            if (completionSubscription == null) {
                completionSubscription = taskStore.onCompletion(this::handleTerminalCompletion);
            }
        }
        taskStore.list().stream()
            .filter(task -> task.status().hasResult())
            .forEach(this::handleTerminalCompletion);
    }

    private void handleTerminalCompletion(TaskState task) {
        if (task == null || !task.status().hasResult()) return;
        if (foregroundAgentIds.contains(task.id())) {
            // A synchronous Agent tool call owns this result until Ctrl+B has
            // successfully removed the foreground marker. Its finally block
            // unregisters the terminal task, so no background notification or
            // eviction deadline belongs here.
            return;
        }
        if (task.notified()) {
            releaseTerminalHandles(task.id());
            scheduleTerminalEviction(task.id());
            return;
        }
        MessageQueueManager queue = messageQueue;
        if (queue == null || !notificationClaims.add(task.id())) return;
        try {
            TaskState latest = taskStore.get(task.id()).orElse(null);
            if (latest == null || !latest.status().hasResult()) return;
            if (!latest.notified()) {
                TaskNotificationBridge.deliver(queue, this, latest);
                taskStore.markNotified(task.id());
            }
            releaseTerminalHandles(task.id());
            scheduleTerminalEviction(task.id());
        } finally {
            notificationClaims.remove(task.id());
        }
    }

    /** Release objects that own processes, threads, queues, or large transcripts. */
    private void releaseTerminalHandles(String taskId) {
        shellHandles.remove(taskId);
        foregroundShellHandles.remove(taskId);
        agentHandles.remove(taskId);
        pendingAgentMessages.remove(taskId);
        dreamHandles.remove(taskId);
        monitorHandles.remove(taskId);

    }

    private void scheduleTerminalEviction(String taskId) {
        TaskState task = taskStore.get(taskId).orElse(null);
        if (task == null || !task.status().hasResult() || !task.notified()) return;
        long deadline = System.currentTimeMillis() + TERMINAL_EVICTION_GRACE_MS;
        if (terminalEvictionDeadlines.putIfAbsent(taskId, deadline) != null) return;
        TERMINAL_EVICTION_TIMER.schedule(
            () -> evictEligibleTerminalTasks(System.currentTimeMillis()),
            TERMINAL_EVICTION_GRACE_MS, TimeUnit.MILLISECONDS);
    }


    @Explanation("Keeps a 30-second safety grace before terminal task cleanup")
    void evictEligibleTerminalTasks(long nowMillis) {
        for (Map.Entry<String, Long> entry : terminalEvictionDeadlines.entrySet()) {
            String taskId = entry.getKey();
            if (entry.getValue() > nowMillis) continue;
            TaskState task = taskStore.get(taskId).orElse(null);
            if (task == null) {
                terminalEvictionDeadlines.remove(taskId, entry.getValue());
                releaseTerminalHandles(taskId);
                continue;
            }
            if (!task.status().hasResult() || !task.notified()) {
                terminalEvictionDeadlines.remove(taskId, entry.getValue());
                continue;
            }
            InProcessTeammateTask teammate = teammateHandles.get(taskId);
            if (teammate != null && teammate.hasTranscriptListener()) {
                long next = System.currentTimeMillis() + TERMINAL_EVICTION_GRACE_MS;
                if (terminalEvictionDeadlines.replace(taskId, entry.getValue(), next)) {
                    TERMINAL_EVICTION_TIMER.schedule(
                        () -> evictEligibleTerminalTasks(System.currentTimeMillis()),
                        TERMINAL_EVICTION_GRACE_MS, TimeUnit.MILLISECONDS);
                }
                continue;
            }
            if (terminalEvictionDeadlines.remove(taskId, entry.getValue())) {
                releaseTerminalHandles(taskId);
                teammateHandles.remove(taskId);
                workflowHandles.remove(taskId);
                taskStore.remove(taskId);
            }
        }
    }

    /**
     * Process-wide singleton, backed by an <b>in-memory</b> {@link TaskStore}.
     */
    public static TaskRegistry global() {
        TaskRegistry instance = GLOBAL;
        if (instance == null) {
            synchronized (TaskRegistry.class) {
                instance = GLOBAL;
                if (instance == null) {
                    instance = new TaskRegistry(TaskStore.inMemory());
                    GLOBAL = instance;
                }
            }
        }
        return instance;
    }

    /** Test hook: replace the global singleton (e.g. with an in-memory store). */
    public static void setGlobalForTest(TaskRegistry registry) {
        closeQuietly(GLOBAL);
        GLOBAL = registry;
    }

/** Test hook: drop the global singleton so the next {@link #global} rebuilds it. */
    public static void resetGlobalForTest() {
        closeQuietly(GLOBAL);
        GLOBAL = null;
    }

    private static void closeQuietly(TaskRegistry registry) {
        if (registry == null) return;
        AutoCloseable subscription = registry.completionSubscription;
        registry.completionSubscription = null;
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception _) {
                // Test/session teardown is best-effort.
            }
        }
        registry.messageQueue = null;
    }

    public TaskStore store() {
        return taskStore;
    }

    /**
     * Registers a live shell handle for a task already created in {@link #store}.
     */
    public void registerShell(LocalShellTask handle) {
        shellHandles.put(handle.getTaskId(), handle);
    }

    /** Registers a running synchronous shell so Ctrl+B can detach it in place. */
    public void registerShellForeground(ForegroundShellTask handle) {
        foregroundShellHandles.put(handle.getTaskId(), handle);
    }

    /** Registers a command- or WebSocket-backed Monitor handle for TaskStop. */
    public void registerMonitor(MonitorTaskHandle handle) {
        monitorHandles.put(handle.getTaskId(), handle);
    }

    public Optional<MonitorTaskHandle> getMonitorHandle(String taskId) {
        return Optional.ofNullable(monitorHandles.get(taskId));
    }

    public boolean isMonitorTask(String taskId) {
        return monitorHandles.containsKey(taskId);
    }

    public void unregisterMonitor(String taskId) {
        monitorHandles.remove(taskId);
    }

    public boolean killMonitor(String taskId) {
        MonitorTaskHandle handle = monitorHandles.get(taskId);
        return handle != null && handle.kill();
    }

    /**
     * Stops every running shell or Monitor task owned by {@code agentId}, then removes already-queued
     * messages for that agent.
     */
    public void killShellAndMonitorTasksForAgent(String agentId) {
        if (agentId == null) return;
        for (TaskState task : taskStore.list()) {
            if (task.status() != TaskStatus.RUNNING
                    || task.agentId().filter(agentId::equals).isEmpty()) {
                continue;
            }
            if (monitorHandles.containsKey(task.id())) {
                killMonitor(task.id());
            } else if (task.type() == TaskType.LOCAL_BASH) {
                killShell(task.id());
            } else if (task.type() == TaskType.MONITOR_MCP
                    || task.type() == TaskType.MONITOR_WS) {
                killMonitor(task.id());
            }
        }
        MessageQueueManager queue = messageQueue;
        if (queue != null) {
            queue.dequeueAllMatching(command -> agentId.equals(command.agentId()));
        }
    }

    /**
     * Registers a live agent handle for a task already created in {@link #store}.
     */
    public void registerAgent(LocalAgentTask handle) {
        foregroundAgentIds.remove(handle.getTaskId());
        agentHandles.put(handle.getTaskId(), handle);
    }

    /** Registers the optional Agent {@code name} used by SendMessage routing. */
    public void registerAgentName(String name, String agentId) {
        if (StringUtils.isNotBlank(name) && agentId != null && !StringUtils.isBlank(agentId)) {
            agentNames.put(name, agentId);
        }
    }

    /** Resolves a model-visible Agent name, falling back to an already-opaque id. */
    public String resolveAgentId(String nameOrId) {
        return nameOrId == null ? null : agentNames.getOrDefault(nameOrId, nameOrId);
    }

    /** Resolves the stable model-visible name registered for an opaque agent id. */
    public String resolveAgentName(String agentId) {
        if (agentId == null) return null;
        return agentNames.entrySet().stream()
            .filter(entry -> agentId.equals(entry.getValue()))
            .map(Map.Entry::getKey)
            .findFirst()
            .orElse(agentId);
    }

    /** Registers a synchronous local agent that may later be backgrounded in place. */
    public void registerAgentForeground(LocalAgentTask handle) {
        agentHandles.put(handle.getTaskId(), handle);
        foregroundAgentIds.add(handle.getTaskId());
    }

    /**
     * Atomically claims the foreground-to-background transition. The task and
     * its live handle remain registered under the same id. A terminal task
     * cannot be resurrected into the background list.
     */
    public boolean backgroundAgent(String taskId) {
        LocalAgentTask handle = agentHandles.get(taskId);
        if (handle == null) return false;
        synchronized (handle) {
            Optional<TaskState> task = taskStore.get(taskId);
            if (task.isEmpty() || task.get().status().isTerminal()) return false;
            if (!foregroundAgentIds.remove(taskId)) return false;
            if (!handle.requestBackground()) {
                foregroundAgentIds.add(taskId);
                return false;
            }
            return true;
        }
    }

    /** Foreground Agent/Bash tasks that Ctrl+B may background, in task-store order. */
    public List<TaskState> listForegroundBackgroundable() {
        return taskStore.list().stream()
            .filter(t -> foregroundAgentIds.contains(t.id())
                || foregroundShellHandles.containsKey(t.id()))
            .filter(t -> t.status() == TaskStatus.RUNNING || t.status() == TaskStatus.PENDING)
            .toList();
    }


    public int backgroundAllForegroundTasks() {
        int transitioned = 0;
        for (TaskState task : listForegroundBackgroundable()) {
            boolean changed = task.type() == TaskType.LOCAL_BASH
                ? backgroundShell(task.id()) : backgroundAgent(task.id());
            if (changed) transitioned++;
        }
        return transitioned;
    }

    /** Backgrounds the foreground task created by the specified model tool use. */
    public boolean backgroundByToolUseId(String toolUseId) {
        if (StringUtils.isBlank(toolUseId)) return false;
        TaskState task = listForegroundBackgroundable().stream()
            .filter(candidate -> candidate.toolUseId().filter(toolUseId::equals).isPresent())
            .findFirst().orElse(null);
        if (task == null) return false;
        return task.type() == TaskType.LOCAL_BASH
            ? backgroundShell(task.id()) : backgroundAgent(task.id());
    }

    public boolean backgroundShell(String taskId) {
        ForegroundShellTask handle = foregroundShellHandles.get(taskId);
        if (handle == null) return false;
        synchronized (handle) {
            TaskState task = taskStore.get(taskId).orElse(null);
            if (task == null || task.status().isTerminal()) return false;
            try {
                return handle.requestBackground();
            } catch (RuntimeException _) {
                // If the output file cannot be activated, keep the command in
                // foreground and let Ctrl+B fall back instead of crashing UI key dispatch.
                return false;
            }
        }
    }

    public boolean unregisterForegroundShell(String taskId) {
        ForegroundShellTask handle = foregroundShellHandles.get(taskId);
        if (handle == null) return false;
        synchronized (handle) {
            if (handle.isBackgrounded()) return false;
            foregroundShellHandles.remove(taskId, handle);
            taskStore.remove(taskId);
            return true;
        }
    }

    /**
     * Removes a foreground agent that completed before it was backgrounded.
     * Returns false after another actor has already claimed the transition.
     */
    public boolean unregisterForegroundAgent(String taskId) {
        LocalAgentTask handle = agentHandles.get(taskId);
        if (handle == null) return false;
        synchronized (handle) {
            if (!foregroundAgentIds.remove(taskId)) return false;
            agentHandles.remove(taskId, handle);
            pendingAgentMessages.remove(taskId);
            taskStore.remove(taskId);
            return true;
        }
    }

    /**
     * Queues a plain-text continuation for a running local agent.
     */
    public boolean queueAgentMessage(String agentId, String message) {
        return queueAgentMessage(agentId, message, null);
    }

    /**
     * Queues a continuation together with its model-visible sender.
     */
    public boolean queueAgentMessage(String agentId, String message, String from) {
        if (agentId == null || message == null || StringUtils.isBlank(message)) return false;
        Optional<TaskState> task = taskStore.get(agentId);
        if (task.isEmpty() || task.get().status() != TaskStatus.RUNNING
            || !agentHandles.containsKey(agentId)) {
            return false;
        }
        pendingAgentMessages.computeIfAbsent(agentId, _ -> new ConcurrentLinkedQueue<>())
            .offer(new PendingAgentMessage(message, from));
        return true;
    }

    /**
     * Re-registers a terminal/evicted local agent under icompatibility baseline id before replaying its
     * sidechain transcript.
     */
    public synchronized TaskState prepareAgentResume(String agentId, String description,
                                                     String prompt, String agentType,
                                                     String toolUseId) {
        Optional<TaskState> existing = taskStore.get(agentId);
        if (existing.isPresent() && !existing.get().status().isTerminal()) {
            throw new IllegalStateException("Agent " + agentId + " is already running or being resumed");
        }
        terminalEvictionDeadlines.remove(agentId);
        notificationClaims.remove(agentId);
        releaseTerminalHandles(agentId);
        taskStore.remove(agentId);
        TaskState task = taskStore.createWithId(
            agentId, TaskType.LOCAL_AGENT, description, null);
        taskStore.updatePrompt(agentId, prompt);
        taskStore.updateAgentType(agentId, agentType);
        if (StringUtils.isNotBlank(toolUseId)) {
            taskStore.updateToolUseId(agentId, toolUseId);
        }
        taskStore.updateStatus(agentId, TaskStatus.RUNNING);
        return taskStore.get(agentId).orElse(task);
    }

    /** Drains all continuation prompts currently queued for an agent. */
    public List<String> drainAgentMessages(String agentId) {
        return drainAgentMessageEnvelopes(agentId).stream()
            .map(PendingAgentMessage::text)
            .toList();
    }

    /** Drains pending continuations without discarding their sender metadata. */
    public List<PendingAgentMessage> drainAgentMessageEnvelopes(String agentId) {
        ConcurrentLinkedQueue<PendingAgentMessage> queue = pendingAgentMessages.get(agentId);
        if (queue == null) return List.of();
        List<PendingAgentMessage> messages = new ArrayList<>();
        PendingAgentMessage message;
        while ((message = queue.poll()) != null) messages.add(message);
        if (queue.isEmpty()) pendingAgentMessages.remove(agentId, queue);
        return messages;
    }

    /** Number of continuations waiting for this local agent's next turn. */
    public int pendingAgentMessageCount(String agentId) {
        ConcurrentLinkedQueue<PendingAgentMessage> queue = pendingAgentMessages.get(agentId);
        return queue == null ? 0 : queue.size();
    }

    /** One queued local-agent continuation and the session name that sent it. */
    public record PendingAgentMessage(String text, String from) {
        public PendingAgentMessage {
            Objects.requireNonNull(text, "text");
        }
    }

    /** Removes queued continuations when a background agent terminates. */
    public void clearAgentMessages(String agentId) {
        pendingAgentMessages.remove(agentId);
    }

    public Optional<TaskState> get(String taskId) {
        return taskStore.get(taskId);
    }

    public Optional<LocalShellTask> getShellHandle(String taskId) {
        return Optional.ofNullable(shellHandles.get(taskId));
    }

    public Optional<ForegroundShellTask> getForegroundShellHandle(String taskId) {
        return Optional.ofNullable(foregroundShellHandles.get(taskId));
    }

    public Optional<LocalAgentTask> getAgentHandle(String taskId) {
        return Optional.ofNullable(agentHandles.get(taskId));
    }

    /** Registers a live local-workflow handle for TaskStop and /workflows. */
    public void registerWorkflow(WorkflowTask handle) {
        workflowHandles.put(handle.getTaskId(), handle);
    }

    public void unregisterWorkflow(String taskId) {
        workflowHandles.remove(taskId);
    }

    Optional<WorkflowRun> workflowRun(String taskId) {
        WorkflowTask handle = workflowHandles.get(taskId);
        return handle == null ? Optional.empty() : Optional.of(handle.snapshot());
    }

    public boolean killWorkflow(String taskId) {
        WorkflowTask handle = workflowHandles.get(taskId);
        return handle != null && handle.kill();
    }

    /**
     * Workflow-control dispatchers report whether the action reached a live
     * workflow handle. Current UI callers (WorkflowsDialog menu actions) are
     * fire-and-forget, so the flag may legitimately go unconsumed — suppressed
     * per-method rather than class-wide because every other boolean method on
     * this class has real consumers.
     */
    @SuppressWarnings("UnusedReturnValue")
    public boolean pauseWorkflow(String taskId) {
        WorkflowTask handle = workflowHandles.get(taskId);
        return handle != null && handle.pause();
    }

    /** {@link #pauseWorkflow} semantics for skipping one agent run. */
    @SuppressWarnings("UnusedReturnValue")
    public boolean skipWorkflowAgent(String taskId, String agentId) {
        WorkflowTask handle = workflowHandles.get(taskId);
        return handle != null && handle.skipAgent(agentId);
    }

    /** {@link #pauseWorkflow} semantics for retrying one agent run. */
    @SuppressWarnings("UnusedReturnValue")
    public boolean retryWorkflowAgent(String taskId, String agentId) {
        WorkflowTask handle = workflowHandles.get(taskId);
        return handle != null && handle.retryAgent(agentId);
    }

    /**
     * Registers a live in-process teammate handle for a task already created in
     * {@link #store()}.
     */
    public void registerTeammate(InProcessTeammateTask handle) {
        teammateHandles.put(handle.getTaskId(), handle);
    }

    public Optional<InProcessTeammateTask> getTeammateHandle(String taskId) {
        return Optional.ofNullable(teammateHandles.get(taskId));
    }

    /**
     * Delivers a scheduled prompt to the live teammate identified by its agent id.
     */
    public boolean injectUserMessageToActiveTeammate(String agentId, String message) {
        InProcessTeammateTask handle = teammateHandles.get(agentId);
        if (handle == null || !handle.isActive()) return false;
        handle.injectUserMessage(message);
        return true;
    }

    /**
     * Lists live in-process teammates still running, sorted alphabetically by display name then task
     * id.
     */
    public List<InProcessTeammateTask> listRunningTeammates() {
        return teammateHandles.values().stream()
            .filter(InProcessTeammateTask::isActive)
            .sorted(Comparator.comparing((InProcessTeammateTask t) -> t.name() == null ? "" : t.name())
                .thenComparing(InProcessTeammateTask::getTaskId))
            .toList();
    }

    /**
     * Registers a live auto-dream handle for a task already created in
     * {@link #store()}. matches {@link #registerAgent}.
     */
    public void registerDream(String taskId, AutoDreamHandle handle) {
        dreamHandles.put(taskId, handle);
    }

    /** Removes a finished/cancelled dream's live handle. */
    public void unregisterDream(String taskId) {
        dreamHandles.remove(taskId);
    }

    /**
     * Kills a running auto-dream task. No-op (returns {@code false}) if the
     * task is unknown, has no live handle, or is not currently {@code RUNNING}
     * — matches {@link #killAgent}.
     */
    public boolean killDream(String taskId) {
        AutoDreamHandle handle = dreamHandles.get(taskId);
        if (handle == null) {
            return false;
        }
        return handle.kill();
    }

    /**
     * Lists tasks eligible for the background-tasks indicator: status is {@code RUNNING} or {@code
     * PENDING}.
     */
    public List<TaskState> listBackground() {
        return taskStore.list().stream()
            .filter(t -> !foregroundAgentIds.contains(t.id()))
            .filter(t -> !foregroundShellHandles.containsKey(t.id())
                || foregroundShellHandles.get(t.id()).isBackgrounded())
            .filter(t -> t.status() == TaskStatus.RUNNING || t.status() == TaskStatus.PENDING)
            .toList();
    }


    public static final String MAIN_SESSION_AGENT_TYPE = "main-session";


    public List<TaskState> listPanelAgentTasks(Instant now) {
        return taskStore.list().stream()
            .filter(this::isPanelAgentTask)
            .filter(t -> isPanelVisible(t, now))
            .sorted(Comparator.comparing(TaskState::startTime))
            .toList();
    }


    public List<TaskState> listPanelWorkflowTasks(Instant now) {
        return taskStore.list().stream()
            .filter(task -> task.type() == TaskType.LOCAL_WORKFLOW)
            .filter(task -> isPanelVisible(task, now))
            .sorted(Comparator.comparing(TaskState::startTime))
            .toList();
    }


    public boolean isPanelAgentTask(TaskState task) {
        return task.type() == TaskType.LOCAL_AGENT
            && !MAIN_SESSION_AGENT_TYPE.equals(taskStore.agentType(task.id()).orElse(null));
    }


    public boolean isBackgroundedAgent(String taskId) {
        return !foregroundAgentIds.contains(taskId);
    }

    /**
     * The background tasks eligible for the ordinary footer summary pill, excluding coordinator-panel
     * agents and dynamic workflows.
     */
    public List<TaskState> listBackgroundExcludingPanelAgents() {
        return listBackground().stream()
            .filter(t -> !isPanelAgentTask(t))

            // vEc/ETf coordinator rows and footerSelection="workflows". It is
            // not also an ordinary BackgroundTaskStatus pill.
            .filter(t -> t.type() != TaskType.LOCAL_WORKFLOW)
            .toList();
    }

    private boolean isPanelVisible(TaskState task, Instant now) {
        Optional<Instant> deadline = taskStore.evictAfter(task.id());
        if (deadline.isEmpty()) {
            // No eviction scheduled: live task, or a viewed terminal task whose
            // deadline was cleared (retain). Always visible.
            return true;
        }
        Instant when = deadline.get();
        if (when.equals(Instant.EPOCH)) {
            return false;
        }
        return when.isAfter(now); // still within the grace window
    }


    public void dismissAgent(String taskId) {
        taskStore.setEvictAfter(taskId, Instant.EPOCH);
    }


    public void dismissWorkflow(String taskId) {
        taskStore.setEvictAfter(taskId, Instant.EPOCH);
    }


    public List<String> evictExpiredPanelTasks(Instant now, String retainedTaskId) {
        List<String> evicted = new ArrayList<>();
        for (TaskState task : taskStore.list()) {
            if (task.type() != TaskType.LOCAL_AGENT
                    && task.type() != TaskType.LOCAL_WORKFLOW) continue;
            Optional<Instant> deadline = taskStore.evictAfter(task.id());
            if (deadline.isEmpty()) continue;
            if (task.id().equals(retainedTaskId)) {
                // Protect the viewed row: clear its deadline so it survives the
                // sweep. Re-stamped by the caller when the viewer leaves.
                taskStore.setEvictAfter(task.id(), null);
                continue;
            }
            Instant when = deadline.get();
            if (when.equals(Instant.EPOCH) || !when.isAfter(now)) {
                taskStore.remove(task.id());
                if (task.type() == TaskType.LOCAL_AGENT) agentHandles.remove(task.id());
                else workflowHandles.remove(task.id());
                evicted.add(task.id());
            }
        }
        return evicted;
    }

    /**
     * Kills a running shell task.
     */
    public boolean killShell(String taskId) {
        LocalShellTask handle = shellHandles.get(taskId);
        if (handle != null) return handle.kill();
        ForegroundShellTask foreground = foregroundShellHandles.get(taskId);
        return foreground != null && foreground.kill();
    }

    /**
     * Kills a running agent task.
     */
    public boolean killAgent(String taskId) {
        return killAgent(taskId, false);
    }

    /** Explicit UI stop; unlike model/system cleanup, this persists the resume guard. */
    public boolean killAgentByUser(String taskId) {
        return killAgent(taskId, true);
    }

    private boolean killAgent(String taskId, boolean userInitiated) {
        LocalAgentTask handle = agentHandles.get(taskId);
        if (handle == null) {
            return false;
        }
        boolean killed = handle.kill(userInitiated);
        if (killed) clearAgentMessages(taskId);
        return killed;
    }

    /**
     * Kills a running in-process teammate.
     */
    public boolean killTeammate(String taskId) {
        InProcessTeammateTask handle = teammateHandles.get(taskId);
        if (handle == null) {
            return false;
        }
        return handle.kill();
    }

    /**
     * Kills a running task of any supported type by dispatching to the type-specific live handle.
     */
    public boolean killTask(String taskId) {
        Optional<TaskState> taskOpt = taskStore.get(taskId);
        if (taskOpt.isEmpty()) {
            return false;
        }
        if (monitorHandles.containsKey(taskId)) {
            return killMonitor(taskId);
        }
        return switch (taskOpt.get().type()) {
            case LOCAL_BASH         -> killShell(taskId);
            case LOCAL_AGENT        -> killAgent(taskId);
            case IN_PROCESS_TEAMMATE -> killTeammate(taskId);
            case LOCAL_WORKFLOW     -> killWorkflow(taskId);
            case MONITOR_MCP, MONITOR_WS -> killMonitor(taskId);
            case DREAM              -> killDream(taskId);
            default                 -> false;
        };
    }
}

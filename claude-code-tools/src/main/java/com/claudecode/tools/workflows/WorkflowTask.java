package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskUsage;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Live, cancellable handle for one background dynamic-workflow execution.
 */
public final class WorkflowTask {

    private static final Logger LOG = LoggerFactory.getLogger(WorkflowTask.class);
    private static final DateTimeFormatter JAVASCRIPT_DATE_FORMAT =
        new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private final WorkflowRuntime runtime;
    private final WorkflowDefinition definition;
    private final JsonNode args;
    private final ToolExecutionContext context;
    private final AbortController abortController;
    private final TaskRegistry registry;
    private final WorkflowRunStore runStore;
    private final AtomicBoolean started = new AtomicBoolean();
    private final Map<String, AbortController> agentControllers = new ConcurrentHashMap<>();
    /** Immutable snapshots; all reads and replacements are guarded by this task monitor. */
    private WorkflowRun run;
    private volatile Thread thread;

    public WorkflowTask(WorkflowRuntime runtime, WorkflowDefinition definition, JsonNode args,
                        ToolExecutionContext context, TaskRegistry registry,
                        WorkflowRunStore runStore, WorkflowRun run) {
        this.runtime = runtime;
        this.definition = definition;
        this.args = args;
        this.abortController = new AbortController();
        this.context = context.withAbortController(abortController);
        this.registry = registry;
        this.runStore = runStore;
        this.run = run;
    }

    public synchronized String getTaskId() {
        return run.taskId();
    }

    public synchronized String getRunId() {
        return run.runId();
    }

    public synchronized WorkflowRun snapshot() {
        return run;
    }

    public synchronized void start() {
        if (!started.compareAndSet(false, true)) return;
        registry.store().updateStatus(getTaskId(), TaskStatus.RUNNING);
        run = withStatus(run, TaskStatus.RUNNING);
        runStore.put(run);
        thread = Thread.startVirtualThread(this::execute);
    }

    public synchronized boolean kill() {
        var state = registry.store().get(getTaskId()).orElse(null);
        if (state == null || state.status().isTerminal()) return false;
        abortController.abort("task_stop");
        Thread active = thread;
        if (active != null) active.interrupt();
        long ended = System.currentTimeMillis();
        WorkflowRun killed = run.killed(ended, Math.max(0L, ended - run.startTime()));
        if (!persistTerminal(killed)) return false;
        registry.store().updateUsage(getTaskId(), new TaskUsage(
            killed.totalTokens(), killed.totalToolCalls(), killed.durationMs()));
        registry.store().updateStatusAndMarkNotified(getTaskId(), TaskStatus.KILLED);
        registry.unregisterWorkflow(getTaskId());
        return true;
    }




    public synchronized boolean pause() {
        var state = registry.store().get(getTaskId()).orElse(null);
        if (state == null || state.status() != TaskStatus.RUNNING) return false;
        long now = System.currentTimeMillis();
        WorkflowRun paused = run.paused(now);
        run = paused;
        runStore.put(paused);
        registry.store().updateUsage(getTaskId(), new TaskUsage(
            paused.totalTokens(), paused.totalToolCalls(), paused.durationMs()));

        registry.store().updateStatusAndMarkNotified(getTaskId(), TaskStatus.PAUSED);
        agentControllers.values().forEach(controller -> controller.abort("background"));
        abortController.abort("background");
        Thread active = thread;
        if (active != null) active.interrupt();
        registry.unregisterWorkflow(getTaskId());
        return true;
    }

    public boolean skipAgent(String agentId) {
        AbortController controller = agentControllers.get(agentId);
        if (controller == null) return false;
        controller.abort("user-skip");
        return true;
    }

    public boolean retryAgent(String agentId) {
        AbortController controller = agentControllers.get(agentId);
        if (controller == null) return false;
        controller.abort("user-retry");
        return true;
    }

    private void execute() {
        WorkflowRun initialRun;
        synchronized (this) {
            initialRun = run;
        }
        try {
            WorkflowExecutionResult execution = runtime.execute(definition, args, context,
                initialRun.agentCache(), new WorkflowJournal(initialRun.transcriptDir()), listener());
            if (abortController.isAborted()) return;
            long ended = System.currentTimeMillis();
            WorkflowRun completed;
            synchronized (this) {
                completed = run.completed(execution, ended);
            }
            if (!persistTerminal(completed)) return;
            if (completed.result() != null) {
                registry.store().updateFinalMessage(getTaskId(),
                    JsonUtils.toJson(completed.result()));
            }
            registry.store().updateUsage(getTaskId(), new TaskUsage(
                completed.totalTokens(), completed.totalToolCalls(), completed.durationMs()));
            registry.store().updateStatus(getTaskId(), TaskStatus.COMPLETED);
        } catch (RuntimeException error) {
            if (abortController.isAborted()
                    || registry.store().get(getTaskId())
                        .map(state -> state.status() == TaskStatus.KILLED
                            || state.status() == TaskStatus.PAUSED).orElse(false)) {
                return;
            }
            long ended = System.currentTimeMillis();
            String message = error.getMessage() == null ? error.toString() : error.getMessage();
            WorkflowRun failed;
            synchronized (this) {
                failed = run.failed(message, ended,
                    Math.max(0L, ended - run.startTime()));
            }
            if (!persistTerminal(failed)) return;
            registry.store().updateError(getTaskId(), message);
            registry.store().updateUsage(getTaskId(), new TaskUsage(
                failed.totalTokens(), failed.totalToolCalls(), failed.durationMs()));
            registry.store().updateStatus(getTaskId(), TaskStatus.FAILED);
        } finally {
            registry.unregisterWorkflow(getTaskId());
        }
    }

    private synchronized boolean persistTerminal(WorkflowRun terminal) {
        if (run.status().isTerminal()) return false;
        run = terminal;
        runStore.put(terminal);
        try {
            persistRun(terminal);
        } catch (RuntimeException error) {
            LOG.warn("Failed to persist workflow run {}: {}",
                terminal.runId(), error.getMessage());
        }
        return true;
    }

    private void persistRun(WorkflowRun persistedRun) {
        try {
            Files.createDirectories(persistedRun.runFile().getParent());
            if (persistedRun.status() == TaskStatus.COMPLETED) {
                Files.createDirectories(persistedRun.outputFile().getParent());
                Files.writeString(persistedRun.outputFile(), pretty(outputJson(persistedRun)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
            }
            Files.writeString(persistedRun.runFile(), JsonUtils.toJson(runJson(persistedRun)),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new WorkflowRuntimeException("Failed to persist workflow result: " + e.getMessage(), e);
        }
    }

    private static ObjectNode outputJson(WorkflowRun run) {
        ObjectNode output = JsonUtils.getMapper().createObjectNode();
        output.put("summary", run.summary());
        output.put("agentCount", run.agentCount());
        ArrayNode logs = output.putArray("logs");
        run.logs().forEach(logs::add);
        ArrayNode failures = output.putArray("failures");
        run.failures().forEach(failures::add);
        if (run.result() == null) output.putNull("result");
        else output.set("result", run.result());
        ArrayNode progress = output.putArray("workflowProgress");
        run.workflowProgress().stream()
            .filter(item -> !Strings.CS.equals("workflow_log", item.path("type").asText()))
            .forEach(progress::add);
        output.put("totalTokens", run.totalTokens());
        output.put("totalToolCalls", run.totalToolCalls());
        if (run.error() != null) output.put("error", run.error());
        return output;
    }

    private static ObjectNode runJson(WorkflowRun run) {
        ObjectNode persisted = JsonUtils.getMapper().createObjectNode();
        persisted.put("runId", run.runId());
        persisted.put("timestamp", JAVASCRIPT_DATE_FORMAT.format(run.timestamp()));
        persisted.put("taskId", run.taskId());
        persisted.put("script", run.script());
        persisted.put("scriptPath", run.scriptPath().toString());
        if (run.args() != null) persisted.set("args", run.args());
        if (run.result() == null) persisted.putNull("result");
        else persisted.set("result", run.result());
        persisted.put("agentCount", run.agentCount());
        ArrayNode logs = persisted.putArray("logs");
        run.logs().forEach(logs::add);
        ArrayNode failures = persisted.putArray("failures");
        run.failures().forEach(failures::add);
        persisted.put("durationMs", run.durationMs());
        persisted.put("summary", run.summary());
        persisted.put("workflowName", run.workflowName());
        persisted.put("status", run.status().name().toLowerCase(Locale.ROOT));
        persisted.put("startTime", run.startTime());
        if (run.title() != null) persisted.put("title", run.title());
        if (!run.phases().isEmpty()) {
            ArrayNode phases = persisted.putArray("phases");
            for (WorkflowPhase phase : run.phases()) {
                ObjectNode item = phases.addObject();
                item.put("title", phase.title());
                if (phase.detail() != null) item.put("detail", phase.detail());
                if (phase.model() != null) item.put("model", phase.model());
            }
        }
        if (run.defaultModel() != null) persisted.put("defaultModel", run.defaultModel());
        ArrayNode progress = persisted.putArray("workflowProgress");
        run.workflowProgress().stream()
            .filter(item -> !Strings.CS.equals("workflow_log", item.path("type").asText()))
            .forEach(progress::add);
        persisted.put("totalTokens", run.totalTokens());
        persisted.put("totalToolCalls", run.totalToolCalls());
        if (run.error() != null) persisted.put("error", run.error());
        return persisted;
    }

    private static String pretty(JsonNode node) {
        try {
            return JsonUtils.getMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (IOException e) {
            throw new WorkflowRuntimeException("Failed to serialize workflow output", e);
        }
    }

    private WorkflowRuntime.Listener listener() {
        return new WorkflowRuntime.Listener() {
            @Override public void onProgress(JsonNode progress) {
                synchronized (WorkflowTask.this) {
                    if (run.status().isTerminal()) return;
                    run = run.withProgress(progress, System.currentTimeMillis());
                    runStore.put(run);
                    registry.store().updateUsage(getTaskId(), new TaskUsage(
                        run.totalTokens(), run.totalToolCalls(), run.durationMs()));
                }
            }

            @Override public void onFailure(String failure) {
                synchronized (WorkflowTask.this) {
                    if (run.status().isTerminal()) return;
                    run = run.withFailure(failure);
                    runStore.put(run);
                }
            }

            @Override public void onAgentController(String agentId,
                                                    AbortController controller) {
                if (controller == null) agentControllers.remove(agentId);
                else agentControllers.put(agentId, controller);
            }
        };
    }

    private static WorkflowRun withStatus(WorkflowRun source, TaskStatus status) {
        return source.toBuilder().status(status).build();
    }
}

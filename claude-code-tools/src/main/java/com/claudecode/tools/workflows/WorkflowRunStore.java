package com.claudecode.tools.workflows;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Shared live run index backing the Workflow tool and {@code /workflows}.
 */
public final class WorkflowRunStore {

    private static final WorkflowRunStore GLOBAL = new WorkflowRunStore();
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();

    public static WorkflowRunStore global() {
        return GLOBAL;
    }

    public void put(WorkflowRun run) {
        runs.put(run.runId(), run);
    }

    public Optional<WorkflowRun> get(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    public List<WorkflowRun> list() {
        return runs.values().stream()
            .sorted(Comparator.comparingLong(WorkflowRun::startTime).reversed())
            .toList();
    }

    public Optional<WorkflowRun> remove(String runId) {
        return Optional.ofNullable(runs.remove(runId));
    }

    /**
     * Loads every persisted run snapshot in one session's workflows directory into
     * the live index. The load is a side effect on {@link #runs}; callers read the
     * result back via {@link #list}, matching how {@code /workflows} re-queries
     * the index after populating it.
     */
    public void loadDirectory(Path workflowsDir) {
        if (workflowsDir == null || !Files.isDirectory(workflowsDir)) return;
        try (Stream<Path> files = Files.list(workflowsDir)) {
            files.filter(path -> Strings.CS.startsWith(path.getFileName().toString(), "wf_")
                    && Strings.CS.endsWith(path.getFileName().toString(), ".json"))
                .forEach(this::load);
        } catch (Exception _) {
            // A stale/unreadable history directory must not break /workflows.
        }
    }

    /** Restores a completed run journal so resume and /workflows survive a restart. */
    public Optional<WorkflowRun> load(Path runFile) {
        try {
            if (runFile == null || !Files.isRegularFile(runFile)) return Optional.empty();
            JsonNode node = JsonUtils.parseTree(Files.readString(runFile));
            String runId = text(node, "runId");
            String taskId = text(node, "taskId");
            String script = text(node, "script");
            String scriptPath = text(node, "scriptPath");
            String status = text(node, "status");
            String timestamp = text(node, "timestamp");
            if (runId == null || taskId == null || script == null || scriptPath == null
                    || status == null || StringUtils.isBlank(status)
                    || timestamp == null || StringUtils.isBlank(timestamp)) {
                return Optional.empty();
            }
            Path sessionDir = runFile.getParent().getParent();
            WorkflowRun run = WorkflowRun.builder(runId, taskId,
                    TaskStatus.valueOf(status.toUpperCase(Locale.ROOT)))
                .workflowName(text(node, "workflowName"))
                .summary(text(node, "summary"))
                .script(script)
                .scriptPath(Path.of(scriptPath))
                .transcriptDir(sessionDir.resolve("subagents").resolve("workflows").resolve(runId))
                .outputFile(runFile.getParent().resolve("outputs").resolve(taskId + ".json"))
                .runFile(runFile)
                .result(node.get("result"))
                .agentCount(node.path("agentCount").asInt())
                .logs(strings(node.path("logs")))
                .failures(strings(node.path("failures")))
                .totalTokens(node.path("totalTokens").asLong())
                .totalToolCalls(node.path("totalToolCalls").asInt())
                .durationMs(node.path("durationMs").asLong())
                .timestamp(Instant.parse(timestamp))
                .startTime(node.path("startTime").asLong())
                .endTime(node.hasNonNull("endTime") ? node.get("endTime").asLong() : null)
                .defaultModel(text(node, "defaultModel"))
                .workflowProgress(nodes(node.path("workflowProgress")))
                .error(text(node, "error"))
                .agentCache(cache(node.path("agentCache")))
                .args(node.get("args"))
                .phases(phases(node.path("phases")))
                .title(text(node, "title"))
                .build();
            runs.put(runId, run);
            return Optional.of(run);
        } catch (Exception _) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    private static List<String> strings(JsonNode values) {
        if (!values.isArray()) return List.of();
        return StreamSupport.stream(values.spliterator(), false)
            .map(JsonNode::asText).toList();
    }

    private static List<JsonNode> nodes(JsonNode values) {
        if (!values.isArray()) return List.of();
        return StreamSupport.stream(values.spliterator(), false)
            .map(value -> (JsonNode) value.deepCopy()).toList();
    }

    private static List<WorkflowPhase> phases(JsonNode values) {
        if (!values.isArray()) return List.of();
        return StreamSupport.stream(values.spliterator(), false)
            .filter(JsonNode::isObject)
            .map(value -> new WorkflowPhase(text(value, "title"),
                text(value, "detail"), text(value, "model")))
            .filter(phase -> phase.title() != null)
            .toList();
    }

    private static List<WorkflowAgentCacheEntry> cache(JsonNode values) {
        if (!values.isArray()) return List.of();
        return StreamSupport.stream(values.spliterator(), false)
            .filter(value -> value.hasNonNull("prompt") && value.hasNonNull("output"))
            .map(value -> new WorkflowAgentCacheEntry(value.path("prompt").asText(),
                value.path("optionsJson").asText("{}"), value.path("output").asText()))
            .toList();
    }
}

package com.claudecode.tools.workflows;

import org.apache.commons.lang3.Strings;

import com.claudecode.tools.tasks.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Process-visible state for one dynamic-workflow invocation.
 */
public record WorkflowRun(
    String runId,
    String taskId,
    TaskStatus status,
    String workflowName,
    String summary,
    String script,
    Path scriptPath,
    Path transcriptDir,
    Path outputFile,
    Path runFile,
    JsonNode result,
    int agentCount,
    List<String> logs,
    List<String> failures,
    long totalTokens,
    int totalToolCalls,
    long durationMs,
    Instant timestamp,
    long startTime,
    Long endTime,
    String defaultModel,
    List<JsonNode> workflowProgress,
    String error,
    List<WorkflowAgentCacheEntry> agentCache,
    JsonNode args,
    List<WorkflowPhase> phases,
    String title) {

    public WorkflowRun {
        logs = logs == null ? List.of() : List.copyOf(logs);
        failures = failures == null ? List.of() : List.copyOf(failures);
        workflowProgress = workflowProgress == null ? List.of() : List.copyOf(workflowProgress);
        agentCache = agentCache == null ? List.of() : List.copyOf(agentCache);
        phases = phases == null ? List.of() : List.copyOf(phases);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public static Builder builder(String runId, String taskId, TaskStatus status) {
        return new Builder(runId, taskId, status);
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public WorkflowRun completed(WorkflowExecutionResult execution, long endTime) {
        return terminal(TaskStatus.COMPLETED, execution.value(), execution.agentCalls(),
            execution.logs(), execution.failures(), execution.tokensUsed(), execution.toolUseCount(),
            execution.durationMs(), endTime, null, execution.agentCache());
    }

    public WorkflowRun failed(String message, long endTime, long durationMs) {
        return terminal(TaskStatus.FAILED, null, agentCount, logs, failures, totalTokens,
            totalToolCalls, durationMs, endTime, message, agentCache);
    }

    public WorkflowRun killed(long endTime, long durationMs) {
        return terminal(TaskStatus.KILLED, result, agentCount, logs, failures, totalTokens,
            totalToolCalls, durationMs, endTime, "Workflow was stopped", agentCache);
    }

    public WorkflowRun paused(long now) {
        return toBuilder()
            .status(TaskStatus.PAUSED)
            .durationMs(Math.max(0L, now - startTime))
            .endTime(null)
            .error(null)
            .build();
    }


    public WorkflowRun withProgress(JsonNode update, long now) {
        if (update == null || !update.isObject()) return this;
        Map<String, JsonNode> indexed = new LinkedHashMap<>();
        List<JsonNode> unindexed = new ArrayList<>();
        for (JsonNode item : workflowProgress) {
            String key = progressKey(item);
            if (key == null) unindexed.add(item);
            else indexed.put(key, item);
        }
        String key = progressKey(update);
        if (key == null) unindexed.add(update.deepCopy());
        else indexed.put(key, update.deepCopy());
        List<JsonNode> merged = new ArrayList<>(indexed.values());
        merged.addAll(unindexed);
        int agents = merged.stream()
            .filter(item -> Strings.CS.equals("workflow_agent", item.path("type").asText()))
            .mapToInt(item -> item.path("index").asInt()).max().orElse(0);
        long tokens = merged.stream()
            .filter(item -> Strings.CS.equals("workflow_agent", item.path("type").asText()))
            .mapToLong(item -> item.path("tokens").asLong()).sum();
        int calls = merged.stream()
            .filter(item -> Strings.CS.equals("workflow_agent", item.path("type").asText()))
            .mapToInt(item -> item.path("toolCalls").asInt()).sum();
        List<String> mergedLogs = merged.stream()
            .filter(item -> Strings.CS.equals("workflow_log", item.path("type").asText()))
            .map(item -> item.path("message").asText()).toList();
        return toBuilder()
            .agentCount(agents)
            .logs(mergedLogs)
            .totalTokens(tokens)
            .totalToolCalls(calls)
            .durationMs(Math.max(0L, now - startTime))
            .workflowProgress(merged)
            .build();
    }

    public WorkflowRun withFailure(String failure) {
        List<String> merged = new ArrayList<>(failures);
        merged.add(failure == null ? "" : failure);
        return toBuilder().failures(merged).build();
    }

    private static String progressKey(JsonNode node) {
        if (node == null || !node.hasNonNull("type") || !node.has("index")) return null;
        return node.path("type").asText() + ":" + node.path("index").asText();
    }

    private WorkflowRun terminal(TaskStatus terminalStatus, JsonNode terminalResult,
                                 int terminalAgentCount, List<String> terminalLogs,
                                 List<String> terminalFailures,
                                 long terminalTokens, int terminalToolCalls,
                                 long terminalDurationMs, long terminalEndTime,
                                 String terminalError,
                                 List<WorkflowAgentCacheEntry> terminalCache) {
        return toBuilder()
            .status(terminalStatus)
            .result(terminalResult)
            .agentCount(terminalAgentCount)
            .logs(terminalLogs)
            .failures(terminalFailures)
            .totalTokens(terminalTokens)
            .totalToolCalls(terminalToolCalls)
            .durationMs(terminalDurationMs)
            .endTime(terminalEndTime)
            .error(terminalError)
            .agentCache(terminalCache)
            .build();
    }

    /** Named construction for persisted workflow state with many optional lifecycle fields. */
    public static final class Builder {
        private final String runId;
        private final String taskId;
        private TaskStatus status;
        private String workflowName;
        private String summary;
        private String script;
        private Path scriptPath;
        private Path transcriptDir;
        private Path outputFile;
        private Path runFile;
        private JsonNode result;
        private int agentCount;
        private List<String> logs = List.of();
        private List<String> failures = List.of();
        private long totalTokens;
        private int totalToolCalls;
        private long durationMs;
        private Instant timestamp = Instant.now();
        private long startTime;
        private Long endTime;
        private String defaultModel;
        private List<JsonNode> workflowProgress = List.of();
        private String error;
        private List<WorkflowAgentCacheEntry> agentCache = List.of();
        private JsonNode args;
        private List<WorkflowPhase> phases = List.of();
        private String title;

        private Builder(String runId, String taskId, TaskStatus status) {
            this.runId = runId;
            this.taskId = taskId;
            this.status = status;
        }

        private Builder(WorkflowRun source) {
            runId = source.runId;
            taskId = source.taskId;
            status = source.status;
            workflowName = source.workflowName;
            summary = source.summary;
            script = source.script;
            scriptPath = source.scriptPath;
            transcriptDir = source.transcriptDir;
            outputFile = source.outputFile;
            runFile = source.runFile;
            result = source.result;
            agentCount = source.agentCount;
            logs = source.logs;
            failures = source.failures;
            totalTokens = source.totalTokens;
            totalToolCalls = source.totalToolCalls;
            durationMs = source.durationMs;
            timestamp = source.timestamp;
            startTime = source.startTime;
            endTime = source.endTime;
            defaultModel = source.defaultModel;
            workflowProgress = source.workflowProgress;
            error = source.error;
            agentCache = source.agentCache;
            args = source.args;
            phases = source.phases;
            title = source.title;
        }

        public Builder status(TaskStatus value) { status = value; return this; }
        public Builder workflowName(String value) { workflowName = value; return this; }
        public Builder summary(String value) { summary = value; return this; }
        public Builder script(String value) { script = value; return this; }
        public Builder scriptPath(Path value) { scriptPath = value; return this; }
        public Builder transcriptDir(Path value) { transcriptDir = value; return this; }
        public Builder outputFile(Path value) { outputFile = value; return this; }
        public Builder runFile(Path value) { runFile = value; return this; }
        public Builder result(JsonNode value) { result = value; return this; }
        public Builder agentCount(int value) { agentCount = value; return this; }
        public Builder logs(List<String> value) { logs = value; return this; }
        public Builder failures(List<String> value) { failures = value; return this; }
        public Builder totalTokens(long value) { totalTokens = value; return this; }
        public Builder totalToolCalls(int value) { totalToolCalls = value; return this; }
        public Builder durationMs(long value) { durationMs = value; return this; }
        public Builder timestamp(Instant value) { timestamp = value; return this; }
        public Builder startTime(long value) { startTime = value; return this; }
        public Builder endTime(Long value) { endTime = value; return this; }
        public Builder defaultModel(String value) { defaultModel = value; return this; }
        public Builder workflowProgress(List<JsonNode> value) { workflowProgress = value; return this; }
        public Builder error(String value) { error = value; return this; }
        public Builder agentCache(List<WorkflowAgentCacheEntry> value) { agentCache = value; return this; }
        public Builder args(JsonNode value) { args = value; return this; }
        public Builder phases(List<WorkflowPhase> value) { phases = value; return this; }
        public Builder title(String value) { title = value; return this; }

        public WorkflowRun build() {
            return new WorkflowRun(runId, taskId, status, workflowName, summary,
                script, scriptPath, transcriptDir, outputFile, runFile, result,
                agentCount, logs, failures, totalTokens, totalToolCalls, durationMs,
                timestamp, startTime, endTime, defaultModel, workflowProgress,
                error, agentCache, args, phases, title);
        }
    }
}

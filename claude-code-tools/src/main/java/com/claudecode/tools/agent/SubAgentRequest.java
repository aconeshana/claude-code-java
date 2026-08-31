package com.claudecode.tools.agent;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResultBudget;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.Usage;
import com.claudecode.core.queue.MessageQueueManager;
import com.claudecode.permissions.PermissionMode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Request parameters for creating a sub-agent.
 */
public record SubAgentRequest(
    String prompt,
    String subagentType,
    List<String> tools,
    List<String> disallowedTools,
    double budgetUsd,
    ToolExecutionContext parentContext,
    String model,
    PermissionMode permissionMode,
    String worktreeBranch,
    /**
     * True when the caller passed {@code isolation: "worktree"} — the subagent runs in its own
     * dedicated git worktree (created off the canonical repo root, cleaned up after if it made no
     * changes).
     */
    boolean worktreeIsolation,
    boolean async,
    /**
     * True when this request is for an in-process teammate (spawned via the agent-teams subsystem).
     */
    boolean teammate,
    String teamId,
    String remoteAgentId,
    boolean fork,
    List<String> mcpServerIds,
    ProgressCallback progressCallback,

    AbortController abortController,
    /**
     * Optional shared command queue for the sub-engine to adopt (see class Javadoc {@code
     * parentQueue}).
     */
    MessageQueueManager parentQueue,

    List<Message> priorMessages,
    List<ToolResultBudget.Replacement> contentReplacements,
    String description,
    String cwd,

    String criticalSystemReminder,
    String agentId,
    int agentDepth,
    Integer subagentMaxDepthSnapshot,
    Integer maxTurns,
    JsonNode jsonSchema,
    String effort,
    /** Optional grouping path below {@code subagents/}, e.g. workflows/&lt;runId&gt;. */
    String transcriptSubdir,
    String systemPromptOverride,
    Runnable beforeFirstModelRequest,
    Runnable awaitParentToolResultEmission
) {

    /** Returns a copy carrying a new {@code prompt} (records have no setters). */
    public SubAgentRequest withPrompt(String newPrompt) {
        return toBuilder().prompt(newPrompt).build();
    }

    /** Returns a copy carrying {@code controller} (records have no setters). */
    public SubAgentRequest withAbortController(AbortController controller) {
        return toBuilder().abortController(controller).build();
    }

    /** Returns a copy carrying {@code agentId} (records have no setters). */
    public SubAgentRequest withAgentId(String agentId) {
        return toBuilder().agentId(agentId).build();
    }

    /** Returns a copy carrying {@code parentCtx} (records have no setters). */
    public SubAgentRequest withParentContext(ToolExecutionContext parentCtx) {
        return toBuilder().parentContext(parentCtx).build();
    }

    /** Returns a copy carrying {@code newTools} (records have no setters). */
    public SubAgentRequest withTools(List<String> newTools) {
        return toBuilder().tools(newTools).build();
    }

    /** Returns a copy carrying {@code permissionMode} (records have no setters). */
    public SubAgentRequest withPermissionMode(PermissionMode permissionMode) {
        return toBuilder().permissionMode(permissionMode).build();
    }

    /** Returns a copy carrying {@code progressCallback} (records have no setters). */
    public SubAgentRequest withProgressCallback(ProgressCallback progressCallback) {
        return toBuilder().progressCallback(progressCallback).build();
    }

    /** Returns a copy carrying {@code priorMessages} (records have no setters). */
    public SubAgentRequest withPriorMessages(List<Message> messages) {
        return toBuilder().priorMessages(messages).build();
    }

    /** Returns a copy carrying persisted aggregate tool-result replacement decisions. */
    public SubAgentRequest withContentReplacements(List<ToolResultBudget.Replacement> replacements) {
        return toBuilder().contentReplacements(replacements).build();
    }

    /** Returns a copy carrying the async child-startup callback. */
    public SubAgentRequest withBeforeFirstModelRequest(Runnable callback) {
        return toBuilder().beforeFirstModelRequest(callback).build();
    }

    /** Returns a copy carrying the parent launch-result emission barrier. */
    public SubAgentRequest withAwaitParentToolResultEmission(Runnable callback) {
        return toBuilder().awaitParentToolResultEmission(callback).build();
    }

    /**
     * Callback for sub-agent progress updates.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String status, double progressPercent);




        default void onAgentMessage(Message message, String agentId) {}

        /**
         * Reports the latest usage snapshot for one assistant message. Repeated
         * calls with the same message id replace the provisional snapshot when
         * the streaming usage finalizer arrives.
         */
        default void onAgentUsage(String messageId, Usage usage) {}
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    /** Explicit sub-agent budget limit; {@code -1} means unlimited. */
    public static final double DEFAULT_BUDGET_FRACTION = -1.0;

    public static class Builder {
        private String prompt = "";
        private String subagentType;
        private List<String> tools = List.of();
        private List<String> disallowedTools = List.of();
        private double budgetUsd = DEFAULT_BUDGET_FRACTION;
        private ToolExecutionContext parentContext;
        private String model;
        private PermissionMode permissionMode;
        private String worktreeBranch;
        private boolean worktreeIsolation = false;
        private boolean async = false;
        private boolean teammate = false;
        private String teamId;
        private String remoteAgentId;
        private boolean fork = false;
        private List<String> mcpServerIds = List.of();
        private ProgressCallback progressCallback = null;
        private AbortController abortController;
        private MessageQueueManager parentQueue;
        private List<Message> priorMessages;
        private List<ToolResultBudget.Replacement> contentReplacements = List.of();
        private String description = "";
        private String cwd = null;
        private String criticalSystemReminder = null;
        private String agentId = null;
        private int agentDepth = 1;
        private Integer subagentMaxDepthSnapshot = null;
        private Integer maxTurns = null;
        private JsonNode jsonSchema = null;
        private String effort = null;
        private String transcriptSubdir = null;
        private String systemPromptOverride = null;
        private Runnable beforeFirstModelRequest = null;
        private Runnable awaitParentToolResultEmission = null;

        private Builder() {}

        private Builder(SubAgentRequest source) {
            prompt = source.prompt;
            subagentType = source.subagentType;
            tools = source.tools;
            disallowedTools = source.disallowedTools;
            budgetUsd = source.budgetUsd;
            parentContext = source.parentContext;
            model = source.model;
            permissionMode = source.permissionMode;
            worktreeBranch = source.worktreeBranch;
            worktreeIsolation = source.worktreeIsolation;
            async = source.async;
            teammate = source.teammate;
            teamId = source.teamId;
            remoteAgentId = source.remoteAgentId;
            fork = source.fork;
            mcpServerIds = source.mcpServerIds;
            progressCallback = source.progressCallback;
            abortController = source.abortController;
            parentQueue = source.parentQueue;
            priorMessages = source.priorMessages;
            contentReplacements = source.contentReplacements;
            description = source.description;
            cwd = source.cwd;
            criticalSystemReminder = source.criticalSystemReminder;
            agentId = source.agentId;
            agentDepth = source.agentDepth;
            subagentMaxDepthSnapshot = source.subagentMaxDepthSnapshot;
            maxTurns = source.maxTurns;
            jsonSchema = source.jsonSchema;
            effort = source.effort;
            transcriptSubdir = source.transcriptSubdir;
            systemPromptOverride = source.systemPromptOverride;
            beforeFirstModelRequest = source.beforeFirstModelRequest;
            awaitParentToolResultEmission = source.awaitParentToolResultEmission;
        }

        public Builder prompt(String prompt) { this.prompt = prompt; return this; }
        public Builder subagentType(String t) { this.subagentType = t; return this; }
        public Builder tools(List<String> tools) { this.tools = tools; return this; }
        public Builder disallowedTools(List<String> tools) { this.disallowedTools = tools; return this; }
        public Builder budgetUsd(double budgetUsd) { this.budgetUsd = budgetUsd; return this; }
        public Builder parentContext(ToolExecutionContext parentContext) { this.parentContext = parentContext; return this; }
        public Builder model(String model) { this.model = model; return this; }
        public Builder permissionMode(PermissionMode permissionMode) { this.permissionMode = permissionMode; return this; }
        public Builder worktreeBranch(String worktreeBranch) { this.worktreeBranch = worktreeBranch; return this; }
        public Builder worktreeIsolation(boolean worktreeIsolation) { this.worktreeIsolation = worktreeIsolation; return this; }
        public Builder async(boolean async) { this.async = async; return this; }
        public Builder teammate(boolean teammate) { this.teammate = teammate; return this; }
        public Builder teamId(String teamId) { this.teamId = teamId; return this; }
        public Builder remoteAgentId(String remoteAgentId) { this.remoteAgentId = remoteAgentId; return this; }
        public Builder fork(boolean fork) { this.fork = fork; return this; }
        public Builder mcpServerIds(List<String> mcpServerIds) { this.mcpServerIds = mcpServerIds; return this; }
        public Builder progressCallback(ProgressCallback progressCallback) { this.progressCallback = progressCallback; return this; }
        public Builder abortController(AbortController abortController) { this.abortController = abortController; return this; }
        public Builder parentQueue(MessageQueueManager parentQueue) { this.parentQueue = parentQueue; return this; }
        public Builder priorMessages(List<Message> priorMessages) { this.priorMessages = priorMessages; return this; }
        public Builder contentReplacements(List<ToolResultBudget.Replacement> replacements) {
            this.contentReplacements = replacements == null ? List.of() : List.copyOf(replacements);
            return this;
        }
        public Builder description(String description) { this.description = description; return this; }
        public Builder cwd(String cwd) { this.cwd = cwd; return this; }
        public Builder criticalSystemReminder(String criticalSystemReminder) { this.criticalSystemReminder = criticalSystemReminder; return this; }
        public Builder agentId(String agentId) { this.agentId = agentId; return this; }
        public Builder agentDepth(int agentDepth) { this.agentDepth = agentDepth; return this; }
        public Builder subagentMaxDepthSnapshot(Integer maxDepth) { this.subagentMaxDepthSnapshot = maxDepth; return this; }
        public Builder maxTurns(Integer maxTurns) { this.maxTurns = maxTurns; return this; }
        public Builder jsonSchema(JsonNode jsonSchema) { this.jsonSchema = jsonSchema; return this; }
        public Builder effort(String effort) { this.effort = effort; return this; }
        public Builder transcriptSubdir(String transcriptSubdir) { this.transcriptSubdir = transcriptSubdir; return this; }
        public Builder systemPromptOverride(String systemPromptOverride) { this.systemPromptOverride = systemPromptOverride; return this; }
        public Builder beforeFirstModelRequest(Runnable callback) { this.beforeFirstModelRequest = callback; return this; }
        public Builder awaitParentToolResultEmission(Runnable callback) { this.awaitParentToolResultEmission = callback; return this; }

        public SubAgentRequest build() {
            return new SubAgentRequest(
                prompt, subagentType, tools, disallowedTools, budgetUsd, parentContext,
                model, permissionMode, worktreeBranch, worktreeIsolation, async, teammate,
                teamId, remoteAgentId, fork, mcpServerIds, progressCallback,
                abortController, parentQueue, priorMessages, contentReplacements,
                description, this.cwd, this.criticalSystemReminder, this.agentId,
                this.agentDepth, this.subagentMaxDepthSnapshot, this.maxTurns,
                this.jsonSchema, this.effort, this.transcriptSubdir,
                this.systemPromptOverride, this.beforeFirstModelRequest,
                this.awaitParentToolResultEmission
            );
        }
    }
}

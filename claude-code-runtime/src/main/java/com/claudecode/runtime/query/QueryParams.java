package com.claudecode.runtime.query;


import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.Message;

import java.util.List;
import java.util.Map;

/**
 * Immutable parameters for a single query invocation.
 */
record QueryParams(
    List<Message> messages,
    String systemPrompt,
    Map<String, String> userContext,
    Map<String, String> systemContext,
    PermissionAskCallback canUseTool,
    ToolExecutionContext toolUseContext,
    String fallbackModel,
    String model,
    String querySource,
    Integer maxOutputTokensOverride,
    Integer maxTurns,
    boolean skipCacheWrite,
    TaskBudget taskBudget,
    QueryDeps deps) {

    /**
     * Per-query task budget — distinct from the {@code TOKEN_BUDGET} feature flag.
     */
    record TaskBudget(int total) {}

    /** Minimal builder for constructing QueryParams from a prompt string. */
    static Builder builder() { return new Builder(); }

    static final class Builder {
        private List<Message> messages = List.of();
        private String systemPrompt;
        private Map<String, String> userContext = Map.of();
        private Map<String, String> systemContext = Map.of();
        private PermissionAskCallback canUseTool;
        private ToolExecutionContext toolUseContext;
        private String fallbackModel;
        private String model;
        private String querySource = "user";
        private Integer maxOutputTokensOverride;
        private Integer maxTurns;
        private boolean skipCacheWrite;
        private TaskBudget taskBudget;
        private QueryDeps deps;

        Builder messages(List<Message> m) { this.messages = m; return this; }
        Builder systemPrompt(String s) { this.systemPrompt = s; return this; }
        Builder querySource(String s) { this.querySource = s; return this; }
        Builder maxOutputTokensOverride(Integer n) { this.maxOutputTokensOverride = n; return this; }
        Builder maxTurns(int n) { this.maxTurns = n; return this; }
        Builder skipCacheWrite(boolean skip) { this.skipCacheWrite = skip; return this; }
        Builder taskBudget(TaskBudget budget) { this.taskBudget = budget; return this; }
        Builder toolUseContext(ToolExecutionContext ctx) { this.toolUseContext = ctx; return this; }
        Builder fallbackModel(String s) { this.fallbackModel = s; return this; }
        Builder model(String s) { this.model = s; return this; }
        Builder canUseTool(PermissionAskCallback cb) { this.canUseTool = cb; return this; }
        Builder deps(QueryDeps d) { this.deps = d; return this; }

        QueryParams build() {
            return new QueryParams(messages, systemPrompt, userContext, systemContext,
                canUseTool, toolUseContext, fallbackModel, model, querySource,
                maxOutputTokensOverride, maxTurns, skipCacheWrite, taskBudget, deps);
        }
    }
}

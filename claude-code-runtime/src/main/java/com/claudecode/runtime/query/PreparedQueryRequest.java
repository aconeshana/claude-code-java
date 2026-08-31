package com.claudecode.runtime.query;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.Message;

import java.util.List;

/**
 * Neutral request for submitting an already assembled query.
 *
 * <ul>
 *   <li>prepared-message query inputs used by forked agents.</li>
 * </ul>
 */
public record PreparedQueryRequest(
    List<Message> messages,
    String systemPrompt,
    String model,
    String fallbackModel,
    String querySource,
    Integer maxOutputTokensOverride,
    Integer maxTurns,
    PermissionAskCallback canUseTool,
    ToolExecutionContext toolUseContext,
    boolean skipCacheWrite,
    SubmitOptions submitOptions) {

    /** Backward-compatible form for prepared queries that use normal cache writes. */
    public PreparedQueryRequest(
            List<Message> messages,
            String systemPrompt,
            String model,
            String fallbackModel,
            String querySource,
            Integer maxOutputTokensOverride,
            Integer maxTurns,
            PermissionAskCallback canUseTool,
            ToolExecutionContext toolUseContext,
            SubmitOptions submitOptions) {
        this(messages, systemPrompt, model, fallbackModel, querySource,
            maxOutputTokensOverride, maxTurns, canUseTool, toolUseContext,
            false, submitOptions);
    }

    public PreparedQueryRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        querySource = querySource == null ? "user" : querySource;
        submitOptions = submitOptions == null ? SubmitOptions.DEFAULT : submitOptions;
    }
}

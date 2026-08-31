package com.claudecode.core.engine;

import com.claudecode.core.message.Message;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.function.Supplier;

/**
 * Engine-lifecycle hook fired when a sub-agent finishes, so listeners in higher layers can release
 * per-agent resources.
 */
public interface SubAgentLifecycleListener {

    /**
     * Immutable child-hook scope assembled by the sub-agent runner after its
     * engine and sidechain transcript are available. The services layer uses
     * it to create an isolated dispatcher that shares global hook settings but
     * translates the child's natural {@code Stop} into {@code SubagentStop}.
     */
    record SubAgentHookContext(
        String agentId,
        String agentType,
        String workingDirectory,
        String agentTranscriptPath,
        String permissionMode,
        String effort,
        JsonNode frontmatterHooks,
        Supplier<List<Message>> messagesSupplier,
        Supplier<String> promptIdSupplier
    ) {}

    /**
     * Called before the first child request so global {@code SubagentStart}
     * hooks can add context to that request. The default keeps existing
     * listeners source-compatible.
     */
    default HookDispatcher.HookOutcome onSubAgentStart(
            String agentId, String agentType) {
        return HookDispatcher.HookOutcome.PROCEED;
    }

    /**
     * Creates the dispatcher installed on the child query engine. Returning
     * {@code null} preserves the legacy no-hook child path.
     */
    default HookDispatcher createSubAgentHookDispatcher(SubAgentHookContext context) {
        return null;
    }

    /**
     * Called once when a sub-agent invocation ends (success or error).
     *
     * @param agentId the sub-agent id that was minted for this invocation
     *                (non-null — the runner only calls this for real sub-agents).
     */
    void onSubAgentComplete(String agentId);
}

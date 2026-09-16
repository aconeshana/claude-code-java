package com.claudecode.ui.lanterna.repl;

import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.runtime.query.QuerySession;

/**
 * Builds the {@link ToolExecutionContext} a REPL-initiated agent run (a {@code /btw} side
 * question or a local-agent prompt from the coordinator panel) executes under: the live
 * session's cwd, permission callback, file-state caches, and current model snapshot.
 *
 * <p>TS coverage (paths relative to the claude-code repo root):
 * <ul>
 *   <li>{@code Tool.ts} — {@code ToolUseContext} assembled for tool calls that originate from
 *       the REPL rather than from a model turn.</li>
 * </ul>
 */
final class AgentToolExecutionContexts {

    private AgentToolExecutionContexts() {}

    static ToolExecutionContext current(QuerySession queryEngine) {
        var config = queryEngine.configuration().getConfig();
        var permissionMode = config.permissionModeSupplier() == null
            ? null : config.permissionModeSupplier().get();
        return ToolExecutionContext
            .builder(new AbortController(), queryEngine.conversation().getSessionId())
            .workingDirectory(config.workingDirectory())
            .permissionAskCallback(queryEngine.execution().getPermissionAskCallback())
            .fileStateCache(queryEngine.forks().getFileStateCache())
            .fileHistoryManager(queryEngine.conversation().getFileHistoryManager())
            .messageQueueManager(queryEngine.conversation().getMessageQueue())
            .agentId(config.agentId())
            .nestedMemoryAttachmentTriggers(queryEngine.forks().getNestedMemoryAttachmentTriggers())
            .loadedNestedMemoryPaths(queryEngine.forks().getLoadedNestedMemoryPaths())
            .teamMemoryEnabled(config.teamMemoryEnabledSupplier().get())
            .currentModel(config.model())
            .sandboxConfig(config.sandboxConfigSupplier().get())
            .readDenyIgnorePatterns(config.readDenyIgnorePatternsSupplier().get())
            .turnTokenBudget(queryEngine.execution().getTurnTokenBudget())
            .workingDirectoryController(queryEngine.configuration().workingDirectoryController())
            .enabledTools(config.tools())
            .currentPermissionMode(permissionMode)
            .conversationMessages(queryEngine.conversation().getMessages())
            .renderedSystemPrompt(queryEngine.configuration().fetchSystemPromptParts())
            .build();
    }
}

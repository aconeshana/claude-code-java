package com.claudecode.runtime.query;

import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.lang3.Strings;

/**
 * Builds cache-sharing fork requests ({@code compact}, {@code away_summary}, {@code /btw}, etc.)
 * from a live {@link DefaultQuerySession}, including compaction tool-catalog narrowing.
 */
final class QueryForkBuilder {

    private static final String COMPACT_QUERY_SOURCE = "compact";
    private static final String COMPACT_FORK_READ_TOOL = "Read";
    private static final String COMPACT_FORK_TOOL_SEARCH = "ToolSearch";
    private static final String COMPACT_FORK_MCP_PREFIX = "mcp__";

    private QueryForkBuilder() {}

    static StreamingClient.StreamRequest buildFork(
            DefaultQuerySession session,
            List<Message> messages,
            String forkPrompt,
            String querySource) {
        QuerySessionSpec config = session.getConfig();
        List<Message> forkMessages = new ArrayList<>(messages.size() + 1);
        forkMessages.addAll(messages);
        forkMessages.add(new UserMessage(
            UUID.randomUUID().toString(), MessageContent.ofText(forkPrompt)));
        String claudeMdContext = QueryHelpers.buildClaudeMdUserContext(session);
        String resolvedModel = ModelNames.parseUserSpecifiedModel(config.model());
        List<StreamingClient.StreamRequest.RequestMessage> requestMessages =
            QueryHelpers.buildRequestMessages(
                session, forkMessages, claudeMdContext, resolvedModel, List.of());
        ToolExecutionContext toolPromptContext = QueryHelpers.toolPromptContext(
            session, resolvedModel);
        List<StreamingClient.StreamRequest.ToolDef> toolDefs =
            forkToolDefs(config, forkMessages, resolvedModel, toolPromptContext, querySource);
        String effortValue = session.getEffortOverride() != null
            ? session.getEffortOverride() : config.effortValue();
        String resolvedEffort = EffortHelpers.resolveAppliedEffort(resolvedModel, effortValue);
        return new StreamingClient.StreamRequest(
            resolvedModel, config.maxTokens(), session.fetchSystemPromptParts(), requestMessages,
            true, toolDefs, null, resolvedEffort, config.fallbackModel(), null, null,
            null, null, config.isThinkingEnabled(), session.getSessionId(), config.agentId(),
            true, querySource, session.getAbortController(), config.thinkingBudgetTokens(),
            config.fastModeController().isFastRequest(resolvedModel),
            config.fastModeController().failureHandler());
    }

    private static List<StreamingClient.StreamRequest.ToolDef> forkToolDefs(
            QuerySessionSpec config,
            List<Message> forkMessages,
            String resolvedModel,
            ToolExecutionContext toolPromptContext,
            String querySource) {
        if (isCompactFork(querySource)) {
            return narrowCompactForkTools(config, resolvedModel, toolPromptContext);
        }
        return ToolSearchGate.isEnabled(resolvedModel)
            ? config.toolExecutor().getToolDefinitions(
                ToolSearchGate.extractDiscoveredToolNames(forkMessages), toolPromptContext)
            : config.toolExecutor().getToolDefinitions(toolPromptContext);
    }

    private static boolean isCompactFork(String querySource) {
        return Strings.CS.equals(COMPACT_QUERY_SOURCE, querySource);
    }

    private static List<StreamingClient.StreamRequest.ToolDef> narrowCompactForkTools(
            QuerySessionSpec config,
            String resolvedModel,
            ToolExecutionContext toolPromptContext) {
        boolean toolSearchEnabled = ToolSearchGate.isEnabled(resolvedModel);
        List<StreamingClient.StreamRequest.ToolDef> all =
            config.toolExecutor().getToolDefinitions(toolPromptContext);
        List<StreamingClient.StreamRequest.ToolDef> narrowed = new ArrayList<>();
        for (StreamingClient.StreamRequest.ToolDef def : all) {
            String name = def.name();
            if (name == null) continue;
            if (Strings.CS.equals(name, COMPACT_FORK_READ_TOOL)) {
                narrowed.add(def);
            } else if (toolSearchEnabled
                    && (Strings.CS.equals(name, COMPACT_FORK_TOOL_SEARCH)
                        || Strings.CS.startsWith(name, COMPACT_FORK_MCP_PREFIX))) {
                narrowed.add(def);
            }
        }
        return narrowed;
    }
}

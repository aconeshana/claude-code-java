package com.claudecode.cli;

import com.claudecode.core.message.SystemMessage;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.commands.Command;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SideQuestionContext;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.AbortController;
import com.claudecode.core.engine.FallbackTriggeredError;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.Usage;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.mcp.MCPTool;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskRegistry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Prepares the state that exists for exactly one interactive terminal session.
 */
final class CliInteractiveSessionLauncher {
    private CliInteractiveSessionLauncher() {}

    record Preparation(CommandRegistry commandRegistry, Function<String, String> sideQuestionRunner) {}

    static Preparation prepare(
            QuerySession engine,
            QuerySessionFactory querySessionFactory,
            StreamingClient client,
            String model,
            Supplier<Boolean> teamMemoryEnabled,
            PermissionGate permissionGate,
            McpRuntime mcpRuntime,
            ToolRegistry toolRegistry,
            CliOutput errorOutput) {
        CommandRegistry commandRegistry = CommandFactory.createDefault(
            new CliSettingsManagementAdapter(), CliToolingCommandAdapter.create(
                TaskRegistry.global(),
                InvokedSkillRegistry.global()));
        CliMcpPromptAdapter promptAdapter = new CliMcpPromptAdapter(mcpRuntime.clientRuntime());
        mcpRuntime.whenReady().thenRun(() ->
            replaceAllMcpPrompts(commandRegistry, mcpRuntime, promptAdapter));

        mcpRuntime.clientRuntime().setToolsChangedListener(serverName -> {
            String prefix = "mcp__" + serverName + "__";
            toolRegistry.unregisterMatching(name -> Strings.CS.startsWith(name, prefix));
            var refreshed = mcpRuntime.clientRuntime().listToolsForServer(serverName);
            mcpRuntime.refreshToolDisplays(serverName, refreshed);
            for (var info : refreshed) {
                toolRegistry.register(new MCPTool(info, mcpRuntime.clientRuntime()));
            }
        });
        mcpRuntime.clientRuntime().setPromptsChangedListener(serverName -> {
            String prefix = "mcp__" + serverName + "__";
            List<Command> commands = new ArrayList<>();
            for (var info : mcpRuntime.clientRuntime().listPromptsForServer(serverName)) {
                commands.add(new McpPromptCommand(
                    CliMcpPromptAdapter.definition(info), promptAdapter));
            }
            commandRegistry.replaceMatching(
                name -> Strings.CS.startsWith(name, prefix), commands);
        });
        return new Preparation(commandRegistry,
            wrappedQuestion -> runSideQuestion(engine, querySessionFactory, client, model,
                teamMemoryEnabled, permissionGate, errorOutput, wrappedQuestion));
    }

    private static void replaceAllMcpPrompts(
            CommandRegistry registry, McpRuntime runtime,
            CliMcpPromptAdapter promptAdapter) {
        List<Command> commands = new ArrayList<>();
        runtime.syncPromptsToRegistry(info -> commands.add(new McpPromptCommand(
            CliMcpPromptAdapter.definition(info), promptAdapter)));
        registry.replaceMatching(name -> Strings.CS.startsWith(name, "mcp__"), commands);
    }

    static String runSideQuestion(
            QuerySession engine,
            QuerySessionFactory querySessionFactory,
            StreamingClient client,
            String model,
            Supplier<Boolean> teamMemoryEnabled,
            PermissionGate permissionGate,
            CliOutput errorOutput,
            String wrappedQuestion) {
        try {
            StreamingClient.StreamRequest cacheSafe =
                engine.forks().getLastCacheSafeForkRequest();
            if (cacheSafe != null) {
                StreamingClient.StreamRequest fork =
                    forkSideQuestionRequest(cacheSafe, wrappedQuestion,
                        SideQuestionContext.history());
                long startedAt = System.currentTimeMillis();
                SideQuestionStreamResult result;
                try {
                    result = consumeSideQuestionStream(client.createStream(fork));
                } catch (FallbackTriggeredError fallback) {
                    if (StringUtils.isBlank(fork.fallbackModel())) throw fallback;
                    result = consumeSideQuestionStream(client.createStream(withModel(
                        fork, fork.fallbackModel())));
                }
                if (result.error() == null) {
                    long completedAt = System.currentTimeMillis();
                    SessionCostState.get().recordApiRequest(
                        StringUtils.defaultIfBlank(result.model(), fork.model()),
                        result.usage(),
                        Math.max(0L, completedAt - startedAt),
                        Math.max(0L, completedAt - (result.finalAttemptStartMs() > 0L
                            ? result.finalAttemptStartMs() : startedAt)));
                }
                return result.output();
            }
            List<Message> stripped = MessageConstants.getMessagesAfterCompactBoundary(engine.conversation().getMessages());
            if (!stripped.isEmpty()) {
                Message last = stripped.getLast();
                if (last instanceof AssistantMessage assistant && assistant.message() != null
                        && (assistant.message().content() == null || assistant.message().content().isEmpty())) {
                    stripped = stripped.subList(0, stripped.size() - 1);
                }
            }
            if (!SideQuestionContext.history().isEmpty()) {
                stripped = new ArrayList<>(stripped);
                for (SideQuestionContext.Exchange exchange : SideQuestionContext.history()) {
                    String userUuid = UUID.randomUUID().toString();
                    stripped.add(new UserMessage(userUuid,
                        MessageContent.ofText(exchange.question())));
                    stripped.add(new AssistantMessage(UUID.randomUUID().toString(),
                        AssistantContent.of(List.of(new TextBlock(exchange.response()))),
                        false, userUuid, Instant.now()));
                }
            }
            QuerySessionSpec subConfig = QuerySessionSpec.builder()
                .llmClient(client)
                .model(model)
                .initialMessages(stripped)
                .maxTurns(1)
                .tools(List.of())
                .teamMemoryEnabledSupplier(teamMemoryEnabled)
                .sandboxConfigSupplier(SandboxSettings::loadSandboxConfig)
                .readDenyIgnorePatternsSupplier(permissionGate::getFileReadIgnorePatterns)
                .abortController(SideQuestionContext.abortController())
                .build();
            CliHeadlessOutput.validateSandboxAtStartup(errorOutput);
            QuerySession sideSession = querySessionFactory.create(subConfig);
            List<SDKMessage> results = new ArrayList<>();
            sideSession.submission().submitMessage(wrappedQuestion, SubmitOptions.DEFAULT)
                .forEachRemaining(results::add);
            StringBuilder text = new StringBuilder();
            String toolUseName = null;
            for (SDKMessage result : results) {
                if (result instanceof SDKMessage.Assistant assistant) {
                    for (ContentBlock block : assistant.message().message().content()) {
                        if (block instanceof TextBlock(String text1)) {
                            if (!text.isEmpty()) text.append("\n\n");
                            text.append(text1);
                        } else if (block instanceof ToolUseBlock toolUse && toolUseName == null) {
                            toolUseName = toolUse.name();
                        }
                    }
                }
            }
            if (!text.toString().trim().isEmpty()) return text.toString().trim();
            if (toolUseName != null) {
                return "(The model tried to call " + toolUseName
                    + " instead of answering directly. Try rephrasing or ask in the main conversation.)";
            }
            for (SDKMessage result : results) {
                if (result instanceof SDKMessage.System(SystemMessage message) && message != null
                    && Strings.CS.equals("api_error", message.subtype())) {
                    String content = message.content();
                    return "(API error: " + (content == null ? "unknown" : content) + ")";
                }
            }
            return "";
        } catch (Exception e) {
            throw new IllegalStateException(
                StringUtils.defaultIfBlank(e.getMessage(), "Failed to get response"), e);
        }
    }

    static StreamingClient.StreamRequest forkSideQuestionRequest(
            StreamingClient.StreamRequest parent, String wrappedQuestion) {
        return forkSideQuestionRequest(parent, wrappedQuestion, List.of());
    }

    static StreamingClient.StreamRequest forkSideQuestionRequest(
            StreamingClient.StreamRequest parent, String wrappedQuestion,
            List<SideQuestionContext.Exchange> history) {
        var messages = new ArrayList<>(parent.messages());
        if (history != null) {
            for (SideQuestionContext.Exchange exchange : history) {
                messages.add(new StreamingClient.StreamRequest.RequestMessage(
                    "user", exchange.question()));
                messages.add(new StreamingClient.StreamRequest.RequestMessage(
                    "assistant", exchange.response()));
            }
        }
        messages.add(new StreamingClient.StreamRequest.RequestMessage("user", wrappedQuestion));
        return new StreamingClient.StreamRequest(
            parent.model(), parent.maxTokens(), parent.systemPrompt(), List.copyOf(messages), true,
            parent.tools(), null, parent.effort(), parent.fallbackModel(),
            parent.maxOutputTokensOverride(), parent.taskBudget(), parent.toolChoice(),
            parent.onStreamingFallback(), parent.thinkingEnabled(), parent.sessionId(),
            null, true, "side_question",
            SideQuestionContext.abortController() != null
                ? SideQuestionContext.abortController() : new AbortController(),
            parent.thinkingBudgetTokens());
    }

    private static StreamingClient.StreamRequest withModel(
            StreamingClient.StreamRequest request, String model) {
        return new StreamingClient.StreamRequest(
            model, request.maxTokens(), request.systemPrompt(), request.messages(), true,
            request.tools(), request.jsonSchema(), request.effort(), null,
            request.maxOutputTokensOverride(), request.taskBudget(), request.toolChoice(),
            request.onStreamingFallback(), request.thinkingEnabled(), request.sessionId(),
            request.agentId(), request.skipCacheWrite(), request.querySource(),
            request.abortController(), request.thinkingBudgetTokens());
    }

    private record SideQuestionStreamResult(String text, String toolUseName,
                                            Exception error, Usage usage, String model,
                                            long finalAttemptStartMs) {
        String output() {
            String normalized = text == null ? "" : text.trim();
            if (!normalized.isEmpty()) return normalized;
            if (toolUseName != null) {
                return "(The model tried to call " + toolUseName
                    + " instead of answering directly. Try rephrasing or ask in the main conversation.)";
            }
            if (error != null) return "(API error: " + error.getMessage() + ")";
            return "";
        }
    }

    private static SideQuestionStreamResult consumeSideQuestionStream(
            Iterator<StreamingClient.StreamingEvent> stream) {
        StringBuilder response = new StringBuilder();
        String toolUseName = null;
        Exception error = null;
        Usage usage = Usage.EMPTY;
        String model = null;
        while (stream.hasNext()) {
            switch (stream.next()) {
                case StreamingClient.StreamingEvent.MessageStartEvent start -> {
                    model = start.model();
                    usage = usage.updateCumulative(start.usage());
                }
                case StreamingClient.StreamingEvent.ContentBlockStartEvent start -> {
                    if (Strings.CS.equals("tool_use", start.type()) && toolUseName == null) {
                        toolUseName = start.name();
                    }
                }
                case StreamingClient.StreamingEvent.ContentBlockDeltaEvent delta -> {
                    if (Strings.CS.equals("text_delta", delta.deltaType()) && delta.deltaText() != null) {
                        response.append(delta.deltaText());
                    }
                }
                case StreamingClient.StreamingEvent.MessageDeltaEvent delta ->
                    usage = usage.updateCumulative(delta.usage());
                case StreamingClient.StreamingEvent.ErrorEvent event -> error = event.exception();
                default -> { }
            }
        }
        long finalAttemptStartMs = stream instanceof StreamingClient.TimedStreamingIterator timed
            && timed.lastAttemptStartMs() > 0L
                ? timed.lastAttemptStartMs() : 0L;
        return new SideQuestionStreamResult(response.toString(), toolUseName,
            error, usage, model, finalAttemptStartMs);
    }
}

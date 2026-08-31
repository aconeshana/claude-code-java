package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandOutputChannel;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.CommandResultDisplay;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.SdkEventSequencedIterator;
import com.claudecode.core.engine.SessionCostState;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.message.AssistantContent;
import com.claudecode.core.message.AssistantMessage;
import com.claudecode.core.message.CompactMetadata;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.FriendlyApiError;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.MessageFactory;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.ToolResultBlock;
import com.claudecode.core.message.ToolUseBlock;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.message.PreservedMessages;
import com.claudecode.core.message.PreservedSegment;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.session.SessionManager;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.PrintWriter;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Serializes the {@link SDKMessage} stream to stdout for {@code --output-format=json} / {@code
 * --output-format=stream-json} — structured output for external tooling/SDK consumers, as opposed
 * to {@link CliHeadlessOutput#processPrompt} which renders ANSI text for a human.
 */
final class StdoutMessageWriter {

    private static final ObjectMapper MAPPER = JsonUtils.getMapper();

    /**
     * Monitor guarding all writes to the shared stdout stream. Locking on a
     * dedicated object (rather than the {@code PrintWriter} parameter) guarantees
     * that the SDK-message writer and the SDK control channel serialize against
     * each other no matter which {@code PrintWriter} wrapper the caller passes.
     */
    private static final Object WRITE_LOCK = new Object();

    private StdoutMessageWriter() {}

    record McpServerStatus(String name, String status) {}

    record McpToolUseMetadata(
        String toolName,
        String displayName,
        String serverDisplayName
    ) {}

    record PluginInfo(String name, String path, String source) {}

    record SdkOutputState(
        SdkOutputMetadata metadata,
        SdkInboundControlHandler.ControlCatalog controlCatalog,
        boolean showBuiltInModelFamilies,
        List<String> customModelNames
    ) {
        SdkOutputState(SdkOutputMetadata metadata,
                       SdkInboundControlHandler.ControlCatalog controlCatalog) {
            this(metadata, controlCatalog, true, List.of());
        }

        SdkOutputState {
            controlCatalog = controlCatalog != null
                ? controlCatalog : SdkInboundControlHandler.ControlCatalog.empty();
            customModelNames = customModelNames != null ? List.copyOf(customModelNames) : List.of();
        }
    }

    record SdkOutputMetadata(
        String sessionId,
        String cwd,
        String model,
        String permissionMode,
        List<String> tools,
        List<McpServerStatus> mcpServers,
        List<String> slashCommands,
        String apiKeySource,
        String claudeCodeVersion,
        String outputStyle,
        List<String> agents,
        List<String> skills,
        List<PluginInfo> plugins,
        List<McpToolUseMetadata> mcpToolUseMetadata,
        String fastModeState
    ) {
        SdkOutputMetadata {
            tools = List.copyOf(tools != null ? tools : List.of());
            mcpServers = List.copyOf(mcpServers != null ? mcpServers : List.of());
            slashCommands = List.copyOf(slashCommands != null ? slashCommands : List.of());
            agents = List.copyOf(agents != null ? agents : List.of());
            skills = List.copyOf(skills != null ? skills : List.of());
            plugins = List.copyOf(plugins != null ? plugins : List.of());
            mcpToolUseMetadata = List.copyOf(
                mcpToolUseMetadata != null ? mcpToolUseMetadata : List.of());
            fastModeState = StringUtils.isNotBlank(fastModeState) ? fastModeState : "off";
        }

        SdkOutputMetadata(
            String sessionId, String cwd, String model, String permissionMode,
            List<String> tools, List<McpServerStatus> mcpServers,
            List<String> slashCommands, String apiKeySource, String claudeCodeVersion,
            String outputStyle, List<String> agents, List<String> skills,
            List<PluginInfo> plugins, List<McpToolUseMetadata> mcpToolUseMetadata
        ) {
            this(sessionId, cwd, model, permissionMode, tools, mcpServers,
                slashCommands, apiKeySource, claudeCodeVersion, outputStyle,
                agents, skills, plugins, mcpToolUseMetadata, "off");
        }

        SdkOutputMetadata(
            String sessionId,
            String cwd,
            String model,
            String permissionMode,
            List<String> tools,
            List<McpServerStatus> mcpServers,
            List<String> slashCommands,
            String apiKeySource,
            String claudeCodeVersion,
            String outputStyle,
            List<String> agents,
            List<String> skills,
            List<PluginInfo> plugins
        ) {
            this(sessionId, cwd, model, permissionMode, tools, mcpServers,
                slashCommands, apiKeySource, claudeCodeVersion, outputStyle,
                agents, skills, plugins, List.of(), "off");
        }

        static SdkOutputMetadata fromEngine(QuerySession engine) {
            String mode = "default";
            if (engine.configuration().getConfig().permissionModeSupplier() != null
                    && engine.configuration().getConfig().permissionModeSupplier().get() != null) {
                mode = switch (engine.configuration().getConfig().permissionModeSupplier().get()) {
                    case DEFAULT -> "default";
                    case PLAN -> "plan";
                    case ACCEPT_EDITS -> "acceptEdits";
                    case BYPASS_PERMISSIONS -> "bypassPermissions";
                    case DONT_ASK -> "dontAsk";
                    case AUTO -> "auto";
                };
            }
            List<String> tools = engine.configuration().getConfig().tools().stream()
                .map(name -> Strings.CS.equals("Agent", name) ? "Task" : name)
                .toList();
            if (engine.configuration().getConfig().toolExecutor() != null) {
                tools = engine.configuration().getConfig().toolExecutor().getToolDefinitions().stream()
                    .map(def -> Strings.CS.equals("Agent", def.name()) ? "Task" : def.name())
                    .toList();
            }
            List<McpServerStatus> servers = engine.configuration().getConfig().mcpServers().stream()
                .map(name -> new McpServerStatus(name, "connected"))
                .toList();
            String keySource = SubprocessEnvironment.get("ANTHROPIC_API_KEY") != null
                ? "ANTHROPIC_API_KEY" : "none";
            return new SdkOutputMetadata(
                engine.conversation().getSessionId(), engine.configuration().getConfig().workingDirectory(),
                engine.configuration().getConfig().model(), mode, tools, servers, List.of(),
                keySource, "2.1.197", currentOutputStyle(engine),
                List.of(), List.of(), List.of(), List.of(),
                engine.configuration().getFastModeState());
        }

        private static String currentOutputStyle(QuerySession engine) {
            if (engine.configuration().getConfig().outputStyleSupplier() == null) return "default";
            try {
                String style = engine.configuration().getConfig().outputStyleSupplier().get();
                return StringUtils.isBlank(style) ? "default" : style;
            } catch (RuntimeException _) {
                return "default";
            }
        }
    }

    static void run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, SdkOutputMetadata metadata,
                    boolean replayUserMessages) {
        run(engine, prompt, submitOptions, out, outputFormat, verbose,
            includePartialMessages,
            () -> new SdkOutputState(metadata, SdkInboundControlHandler.ControlCatalog.empty()),
            replayUserMessages);
    }

    static SDKMessage.Result run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages) {
        return run(engine, prompt, submitOptions, out, outputFormat, verbose,
            includePartialMessages, stateSupplier, replayUserMessages, null);
    }

    static SDKMessage.Result run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages, String originMode) {
        return run(engine, prompt, submitOptions, out, outputFormat, verbose,
            includePartialMessages, stateSupplier, replayUserMessages, originMode, null);
    }

    static SDKMessage.Result run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages, String originMode,
                    SdkControlBroker controlBroker) {
        return runInternal(engine, prompt, submitOptions, out, outputFormat, verbose,
            includePartialMessages, stateSupplier, replayUserMessages, originMode,
            controlBroker, () -> false).result();
    }

    static RunOutcome runDeferringOutcome(
                    QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages, String originMode,
                    BooleanSupplier deferResultSupplier) {
        return runInternal(engine, prompt, submitOptions, out, outputFormat, verbose,
            includePartialMessages, stateSupplier, replayUserMessages, originMode,
            null, deferResultSupplier);
    }

    private static RunOutcome runInternal(
                    QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    CliOutput out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages, String originMode,
                    SdkControlBroker controlBroker,
                    BooleanSupplier deferResultSupplier) {
        boolean streamJson = Strings.CS.equals("stream-json", outputFormat);

        boolean needsFullArray = Strings.CS.equals("json", outputFormat) && verbose;
        List<ObjectNode> collected = needsFullArray ? new ArrayList<>() : null;

        ObjectNode lastResultJson = null;
        boolean sawResult = false;
        boolean sawSystemInit = false;
        List<ObjectNode> pendingInitialUserReplays = new ArrayList<>();
        SdkOutputState outputState = stateSupplier.get();
        SdkOutputMetadata metadata = outputState.metadata();
        List<SdkInboundControlHandler.CommandInfo> currentCommands =
            outputState.controlCatalog().commands();
        DeferredResult deferredResult = null;
        SDKMessage.Result lastResult = null;

        Iterator<SDKMessage> messages = engine.submission().submitMessage(prompt, submitOptions);
        while (messages.hasNext()) {
            SDKMessage msg = messages.next();
            if (msg instanceof SDKMessage.Sentinel) continue;
            boolean emitCommandsAfterCurrentMessage = false;
            if (streamJson) {
                SdkOutputState refreshed = stateSupplier.get();
                List<SdkInboundControlHandler.CommandInfo> refreshedCommands =
                    refreshed.controlCatalog().commands();
                if (!refreshedCommands.equals(currentCommands)) {
                    emitCommandsAfterCurrentMessage = msg instanceof SDKMessage.User user
                        && isToolResultUser(user.message());
                    if (!emitCommandsAfterCurrentMessage) {
                        writeLine(writeJsonLine(commandsChangedMessage(refreshed)), out);
                    }
                }
                outputState = refreshed;
                metadata = refreshed.metadata();
                currentCommands = refreshedCommands;
            }
            if (msg instanceof SDKMessage.StreamEvent) continue;
            if ((msg instanceof SDKMessage.RawStreamEvent
                    || msg instanceof SDKMessage.StreamRequestStart)
                    && !(streamJson && includePartialMessages)) continue;
            boolean sequencedIterator = messages instanceof SdkEventSequencedIterator;
            if (streamJson && sequencedIterator) {
                long cutoff = ((SdkEventSequencedIterator) messages)
                    .sdkEventSequenceForLastMessage();
                writePendingSdkEvents(engine, out, metadata, cutoff);
            } else if (streamJson && (msg instanceof SDKMessage.User
                    || msg instanceof SDKMessage.Result)) {
                writePendingSdkEvents(engine, out, metadata);
            }
            if (streamJson && msg instanceof SDKMessage.Assistant assistant
                    && assistant.parentToolUseId() == null
                    && engine.conversation().getMessageQueue().awaitPendingTerminalAssistants(5_000L)) {
                // A background child final response is already observable. Let
                // its synchronous terminal transition publish task_updated, then
                // drain that patch before resuming parent assistant output.
                writePendingSdkEvents(engine, out, metadata);
            }
            if (streamJson && msg instanceof SDKMessage.Result result
                    && deferResultSupplier != null && deferResultSupplier.getAsBoolean()) {
                lastResult = result;
                deferredResult = new DeferredResult(
                    result, metadata, replayUserMessages, originMode);
                sawResult = true;
                continue;
            }
            ObjectNode json = toJson(msg, metadata, replayUserMessages);
            if (json == null) continue;
            if (msg instanceof SDKMessage.Result && Strings.CS.equals("task-notification", originMode)) {
                json.putObject("origin").put("kind", "task-notification");
            }

            if (streamJson && replayUserMessages && !sawSystemInit
                    && msg instanceof SDKMessage.User user && user.isReplay()) {
                pendingInitialUserReplays.add(json);
                continue;
            }

            if (msg instanceof SDKMessage.Result) {
                lastResult = (SDKMessage.Result) msg;
                lastResultJson = json;
                sawResult = true;
            }

            if (streamJson) {
                // Initial structured-input acknowledgements are deferred until
                // the first post-init SDK event. A concurrent control_response
                // can therefore overtake them while the model request is in

                if (sawSystemInit && !pendingInitialUserReplays.isEmpty()) {
                    for (ObjectNode replay : pendingInitialUserReplays) {
                        writeLine(writeJsonLine(replay), out);
                    }
                    pendingInitialUserReplays.clear();
                }
                writeLine(writeJsonLine(json), out);
                if (controlBroker != null) {
                    switch (msg) {
                        case SDKMessage.Assistant assistant ->
                            controlBroker.onAssistantMessageWritten(assistant.message());
                        case SDKMessage.User user ->
                            controlBroker.onUserMessageWritten(user.message());
                        default -> { }
                    }
                }
                // Legacy/custom iterators do not carry the producer-side SDK
                // event cutoff. Preserve their prior assistant-first fallback;
                // production QueryLoop drains through its exact emission
                // boundary before every main message instead.
                if (!sequencedIterator && msg instanceof SDKMessage.Assistant) {
                    writePendingSdkEvents(engine, out, metadata);
                }
                if (msg instanceof SDKMessage.System(SystemMessage message)
                    && Strings.CS.equals("system_init", message.subtype())) {
                    sawSystemInit = true;
                }
                if (emitCommandsAfterCurrentMessage) {
                    writeLine(writeJsonLine(commandsChangedMessage(outputState)), out);
                }
            } else if (needsFullArray) {
                collected.add(json);
            }
        }

        if (streamJson) {
            for (ObjectNode replay : pendingInitialUserReplays) {
                writeLine(writeJsonLine(replay), out);
            }
            return new RunOutcome(deferredResult, lastResult);
            // already written incrementally, one line per message
        }

        if (!sawResult) {
            out.println("Error: No messages returned");
            return new RunOutcome(null, null);
        }
        if (needsFullArray) {
            ArrayNode arr = MAPPER.createArrayNode();
            collected.forEach(arr::add);
            writeLine(writeJsonLine(arr), out);
        } else {
            writeLine(writeJsonLine(lastResultJson), out);
        }
        return new RunOutcome(null, lastResult);
    }

    static void writeDeferredResult(DeferredResult deferred, CliOutput out) {
        if (deferred == null) return;
        ObjectNode json = toJson(
            deferred.message(), deferred.metadata(), deferred.replayUserMessages());
        if (json == null) return;
        if (Strings.CS.equals("task-notification", deferred.originMode())) {
            json.putObject("origin").put("kind", "task-notification");
        }
        writeLine(writeJsonLine(json), out);
    }

    record DeferredResult(
        SDKMessage.Result message,
        SdkOutputMetadata metadata,
        boolean replayUserMessages,
        String originMode
    ) {}

    record RunOutcome(DeferredResult deferredResult, SDKMessage.Result result) {}

    /**
     * Writes the final SDK result envelope for a headless local slash command that completed without
     * entering the model query loop.
     */
    static void writeCommandResult(QuerySession engine, CommandResult completion,
                                   CliOutput out, String outputFormat,
                                   boolean verbose, long durationMs) {
        writeCommandResult(engine, completion, out, outputFormat, verbose, durationMs,
            SdkOutputMetadata.fromEngine(engine));
    }

    static void writeCommandResult(QuerySession engine, CommandResult completion,
                                   CliOutput out, String outputFormat,
                                   boolean verbose, long durationMs,
                                   SdkOutputMetadata metadata) {
        writeCommandResult(engine, completion, out, outputFormat, verbose, durationMs,
            metadata, List.of(), List.of());
    }

    static void writeCommandResult(QuerySession engine, CommandResult completion,
                                   CliOutput out, String outputFormat,
                                   boolean verbose, long durationMs,
                                   SdkOutputMetadata metadata,
                                   List<SDKMessage> beforeInit,
                                   List<SDKMessage> afterInit) {
        boolean manualCompact = afterInit != null && afterInit.stream()
            .anyMatch(SDKMessage.CompactBoundary.class::isInstance);
        Usage resultUsage = manualCompact ? Usage.EMPTY : engine.execution().getTotalUsage();
        String resultText = completion.headlessOutput();
        ObjectNode result = envelope("result", engine.conversation().getSessionId(), false);
        result.put("subtype", SDKMessage.Result.SUCCESS);
        result.put("is_error", false);
        result.put("duration_ms", durationMs);
        result.put("duration_api_ms", SessionCostState.get().apiDurationMs());
        result.put("num_turns", 0);
        result.put("result", resultText);
        result.putNull("stop_reason");
        result.put("total_cost_usd", manualCompact
            ? SessionCostState.get().totalCostUsd()
            : engine.execution().getCostCalculator().calculateCost(resultUsage));
        result.set("usage", MAPPER.valueToTree(resultUsage));
        if (manualCompact) {
            ObjectNode modelUsage = result.putObject("modelUsage");
            for (Map.Entry<String, Usage> entry
                    : SessionCostState.get().liveUsageByModel().entrySet()) {
                double modelCost = SessionCostState.get().liveCostByModel()
                    .getOrDefault(entry.getKey(), 0.0);
                modelUsage.set(entry.getKey(),
                    modelUsage(entry.getKey(), entry.getValue(), modelCost));
            }
        }
        result.put("fast_mode_state", engine.configuration().getFastModeState());
        result.set("permission_denials",
            MAPPER.valueToTree(engine.execution().getPermissionDenials()));

        ObjectNode init = initMessage(metadata);
        ObjectNode localOutput = localCommandOutput(completion, metadata);
        List<ObjectNode> beforeInitJson = sdkJson(beforeInit, metadata);
        List<ObjectNode> afterInitJson = sdkJson(afterInit, metadata);

        if (Strings.CS.equals("json", outputFormat) && verbose) {
            ArrayNode array = MAPPER.createArrayNode();
            beforeInitJson.forEach(array::add);
            array.add(init);
            afterInitJson.forEach(array::add);
            if (localOutput != null) array.add(localOutput);
            array.add(result);
            writeLine(writeJsonLine(array), out);
        } else if (Strings.CS.equals("stream-json", outputFormat)) {
            for (ObjectNode event : beforeInitJson) writeLine(writeJsonLine(event), out);
            writeLine(writeJsonLine(init), out);
            for (ObjectNode event : afterInitJson) writeLine(writeJsonLine(event), out);
            if (localOutput != null) writeLine(writeJsonLine(localOutput), out);
            writeLine(writeJsonLine(result), out);
        } else {

            writeLine(writeJsonLine(result), out);
        }
    }

    private static List<ObjectNode> sdkJson(
            List<SDKMessage> messages, SdkOutputMetadata metadata) {
        if (messages == null || messages.isEmpty()) return List.of();
        List<ObjectNode> result = new ArrayList<>();
        for (SDKMessage message : messages) {
            ObjectNode json = toJson(message, metadata, false);
            if (json != null) result.add(json);
        }
        return List.copyOf(result);
    }

    // PrintWriter is retained only as a compatibility input boundary. Runtime
    // orchestration uses the non-closeable CliOutput overloads above.
    static void run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    PrintWriter out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, SdkOutputMetadata metadata,
                    boolean replayUserMessages) {
        run(engine, prompt, submitOptions, CliOutput.borrowed(out), outputFormat, verbose,
            includePartialMessages, metadata, replayUserMessages);
    }

    static SDKMessage.Result run(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                    PrintWriter out, String outputFormat, boolean verbose,
                    boolean includePartialMessages, Supplier<SdkOutputState> stateSupplier,
                    boolean replayUserMessages) {
        return run(engine, prompt, submitOptions, CliOutput.borrowed(out), outputFormat, verbose,
            includePartialMessages, stateSupplier, replayUserMessages);
    }

    static void writeCommandResult(QuerySession engine, CommandResult completion,
                                   PrintWriter out, String outputFormat,
                                   boolean verbose, long durationMs) {
        writeCommandResult(engine, completion, CliOutput.borrowed(out), outputFormat, verbose, durationMs);
    }

    private static ObjectNode localCommandOutput(
            CommandResult completion, SdkOutputMetadata metadata) {
        if (completion.display() == CommandResultDisplay.SKIP
                || completion.outputChannel() == CommandOutputChannel.NONE
                || completion.output().isEmpty()) {
            return null;
        }
        if (completion.display() == CommandResultDisplay.USER) {
            String tagged = completion.outputChannel().wrap(completion.output());
            return toJson(new SDKMessage.User(
                MessageFactory.createUserMessage(tagged), true),
                metadata, true);
        }

        AssistantMessage synthetic = new AssistantMessage(
            UUID.randomUUID().toString(),
            AssistantContent.of(
                "msg_" + UUID.randomUUID().toString().replace("-", ""),
                List.of(new TextBlock(completion.output())),
                Usage.EMPTY));
        return toJson(new SDKMessage.Assistant(
            synthetic, Usage.EMPTY, MessageConstants.SYNTHETIC_MODEL), metadata, false);
    }

    /**
     * Writes a single NDJSON line, synchronized on {@link #WRITE_LOCK} so
     * SDK-message output and the SDK control channel ({@code control_request}) never
     * interleave byte-wise on the same stdout stream.
     */
    private static void writeLine(String line, CliOutput out) {
        synchronized (WRITE_LOCK) {
            out.println(line);
            out.flush();
        }
    }

    /**
     * Serializes and writes a control-channel message (e.g. {@code control_request} from
     * the SDK control protocol) to the shared stdout stream. Called by
     * {@link SdkControlBroker}; uses the same monitor as the SDK-message writer so the
     * two never interleave.
     */
    static void writeControlMessage(JsonNode node, CliOutput out) {
        writeLine(writeJsonLine(node), out);
    }

    /** Drains the session's SDK-only task lifecycle queue to stream-json. */
    static void writePendingSdkEvents(QuerySession engine, CliOutput out,
                                      SdkOutputMetadata metadata) {
        if (engine == null || engine.conversation().getMessageQueue() == null) return;
        for (SDKMessage event : engine.conversation().getMessageQueue().drainSdkEvents()) {
            ObjectNode json = toJson(event, metadata, false);
            if (json != null) writeLine(writeJsonLine(json), out);
        }
    }

    /** Drains only SDK lifecycle events visible at the main-message emission boundary. */
    static void writePendingSdkEvents(QuerySession engine, CliOutput out,
                                      SdkOutputMetadata metadata, long sequence) {
        if (engine == null || engine.conversation().getMessageQueue() == null) return;
        for (SDKMessage event : engine.conversation().getMessageQueue().drainSdkEventsThrough(sequence)) {
            ObjectNode json = toJson(event, metadata, false);
            if (json != null) writeLine(writeJsonLine(json), out);
        }
    }

    /** Emits the terminal SDK task_notification bookend before its model turn. */
    static void writeTaskNotificationEvent(QueuedCommand command, CliOutput out,
                                           SdkOutputMetadata metadata) {
        if (command == null || !Strings.CS.equals("task-notification", command.mode())) return;
        String xml = command.text();
        String rawStatus = xmlTag(xml, "status");
        if (rawStatus == null) return;
        String status = Strings.CS.equals("killed", rawStatus) ? "stopped" : rawStatus;
        ObjectNode event = envelope("system", metadata.sessionId(), false);
        event.put("subtype", "task_notification");
        event.put("task_id", valueOrEmpty(xmlTagEither(xml, "task-id", "task_id")));
        String toolUseId = xmlTagEither(xml, "tool-use-id", "tool_use_id");
        if (toolUseId != null) event.put("tool_use_id", toolUseId);
        event.put("status", status);
        event.put("output_file", valueOrEmpty(xmlTagEither(xml, "output-file", "output_file")));
        event.put("summary", valueOrEmpty(xmlTag(xml, "summary")));
        String totalTokens = xmlTagEither(xml, "subagent_tokens", "total_tokens");
        String toolUses = xmlTag(xml, "tool_uses");
        String durationMs = xmlTag(xml, "duration_ms");
        if (totalTokens != null || toolUses != null || durationMs != null) {
            ObjectNode usage = event.putObject("usage");
            if (totalTokens != null) usage.put("total_tokens", parseLong(totalTokens));
            if (toolUses != null) usage.put("tool_uses", parseLong(toolUses));
            if (durationMs != null) usage.put("duration_ms", parseLong(durationMs));
        }
        writeLine(writeJsonLine(event), out);
    }

    static void writeControlMessage(JsonNode node, PrintWriter out) {
        writeControlMessage(node, CliOutput.borrowed(out));
    }

    static void writeTaskNotificationEvent(QueuedCommand command, PrintWriter out,
                                           SdkOutputMetadata metadata) {
        writeTaskNotificationEvent(command, CliOutput.borrowed(out), metadata);
    }

    private static String xmlTagEither(String xml, String primary, String fallback) {
        String value = xmlTag(xml, primary);
        return value != null ? value : xmlTag(xml, fallback);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException _) {
            return 0L;
        }
    }

    private static String xmlTag(String xml, String tag) {
        Matcher matcher = Pattern.compile("<" + Pattern.quote(tag) + ">([^<]*)</"
            + Pattern.quote(tag) + ">").matcher(xml == null ? "" : xml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String writeJsonLine(Object node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"type\":\"error\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }

    static ObjectNode toJson(SDKMessage msg, SdkOutputMetadata metadata,
                             boolean replayUserMessages) {
        String sessionId = metadata.sessionId();
        return switch (msg) {
            case SDKMessage.Assistant a -> {
                ObjectNode node = envelope("assistant", sessionId, true);
                AssistantContent content = a.message().message();
                ObjectNode message = node.putObject("message");
                if (content != null && content.id() != null) message.put("id", content.id());
                else message.putNull("id");
                message.put("type", "message");
                message.put("role", "assistant");
                message.put("model", a.model() != null ? a.model() : metadata.model());
                List<ContentBlock> blocks = content != null && content.content() != null
                    ? content.content() : List.of();
                message.set("content", contentBlocksJson(blocks));
                message.putNull("stop_reason");
                message.putNull("stop_sequence");
                message.set("usage", baseUsage(a.usage()));
                message.putNull("context_management");
                ArrayNode toolUseMeta = toolUseMetadata(blocks, metadata);
                if (!toolUseMeta.isEmpty()) node.set("tool_use_meta", toolUseMeta);
                if (a.parentToolUseId() != null) {
                    node.put("parent_tool_use_id", a.parentToolUseId());
                }
                if (a.subagentType() != null) {
                    node.put("subagent_type", a.subagentType());
                }
                if (a.taskDescription() != null) {
                    node.put("task_description", a.taskDescription());
                }
                yield node;
            }
            case SDKMessage.User u -> {
                if (!replayUserMessages && u.isReplay()) yield null;
                ObjectNode node = envelope("user", sessionId, true);
                if (StringUtils.isNotBlank(u.message().uuid())) {
                    node.put("uuid", u.message().uuid());
                }
                ObjectNode message = node.putObject("message");
                message.put("role", "user");
                if ((!u.isSynthetic() || u.manualCompactSummary())
                        && u.message().message() != null && u.message().message().isText()) {
                    message.put("content", u.message().message().text());
                } else {
                    List<ContentBlock> blocks = u.message().message() != null
                        ? u.message().message().blocks() : List.of();
                    if (u.isSynthetic() && u.message().message() != null
                            && u.message().message().isText()) {
                        blocks = List.of(new TextBlock(u.message().message().text()));
                    }
                    message.set("content", userContentBlocksJson(
                        blocks,
                        u.message().toolUseResult()));
                }
                if (u.message().timestampValue() != null) {
                    node.put("timestamp", DateTimeFormatter.ISO_INSTANT.format(
                        u.message().timestampValue().truncatedTo(ChronoUnit.MILLIS)));
                }
                if (u.parentToolUseId() != null) {
                    node.put("parent_tool_use_id", u.parentToolUseId());
                }
                if (u.subagentType() != null) {
                    node.put("subagent_type", u.subagentType());
                }
                if (u.taskDescription() != null) {
                    node.put("task_description", u.taskDescription());
                }
                if (u.isReplay()) {
                    node.put("isReplay", true);
                } else if (u.manualCompactSummary()) {
                    node.put("isReplay", false);
                }
                if (u.isSynthetic()) {
                    node.put("isSynthetic", true);
                }
                if (u.message().toolUseResult() != null) {
                    node.set("tool_use_result", MAPPER.valueToTree(u.message().toolUseResult()));
                }
                yield node;
            }
            case SDKMessage.System s -> {
                if (Strings.CS.equals("system_init", s.message().subtype())) {
                    yield initMessage(metadata);
                }
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", s.message().subtype());
                node.put("content", s.message().content());
                if (Strings.CS.equals("model_fallback", s.message().subtype())) {
                    node.put("trigger", "overloaded");
                    Matcher matcher = Pattern.compile(
                        "^Switched to (.+) due to high demand for (.+)$")
                        .matcher(s.message().content());
                    if (matcher.matches()) {
                        node.put("original_model", modelIdForDisplay(matcher.group(2), metadata));
                        node.put("fallback_model", modelIdForDisplay(matcher.group(1), metadata));
                    }
                } else {
                    node.put("level", s.message().level());
                }
                yield node;
            }
            case SDKMessage.Notification notification -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "notification");
                node.put("key", notification.key());
                node.put("text", notification.text());
                node.put("priority", notification.priority());
                yield node;
            }
            case SDKMessage.ApiRetry retry -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "api_retry");
                node.put("attempt", retry.attempt());
                node.put("max_retries", retry.maxRetries());
                node.put("retry_delay_ms", retry.retryDelayMs());
                if (retry.errorStatus() == null) node.putNull("error_status");
                else node.put("error_status", retry.errorStatus());
                node.put("error", retry.error());
                yield node;
            }
            case SDKMessage.Result r -> {
                ObjectNode node = envelope("result", sessionId, false);
                String terminalReason = terminalReason(r);
                boolean aborted = Strings.CS.startsWith(terminalReason, "aborted_");
                node.put("subtype", r.resultType());
                node.put("is_error", r.isError());
                if (!aborted) node.putNull("api_error_status");
                node.put("duration_ms", r.durationMs());
                node.put("duration_api_ms", r.durationApiMs());
                if (!aborted) {
                    node.put("ttft_ms", r.ttftMs());
                    node.put("ttft_stream_ms", r.ttftStreamMs());
                    node.put("time_to_request_ms", r.timeToRequestMs());
                }
                node.put("num_turns", r.numTurns());
                if (!aborted) node.put("result", r.resultText());
                if (r.stopReason() != null) node.put("stop_reason", r.stopReason());
                else node.putNull("stop_reason");
                node.put("total_cost_usd", r.totalCost());
                node.set("usage", extendedUsage(r.totalUsage()));
                ObjectNode modelUsage = node.putObject("modelUsage");
                for (Map.Entry<String, Usage> entry : r.modelUsage().entrySet()) {
                    double modelCost = r.modelCosts().getOrDefault(
                        entry.getKey(), CostCalculator.forModel(entry.getKey())
                            .calculateCost(entry.getValue()));
                    modelUsage.set(entry.getKey(),
                        modelUsage(entry.getKey(), entry.getValue(), modelCost));
                }
                node.set("permission_denials", MAPPER.valueToTree(r.permissionDenials()));
                node.put("terminal_reason", terminalReason);
                node.put("fast_mode_state", r.fastModeState() != null ? r.fastModeState() : "off");
                if (!r.errors().isEmpty()) node.set("errors", MAPPER.valueToTree(r.errors()));
                if (r.structuredOutput() != null) node.set("structured_output", r.structuredOutput());
                yield node;
            }
            case SDKMessage.RawStreamEvent raw -> {
                ObjectNode node = envelope("stream_event", sessionId, true);
                node.set("event", raw.event());
                if (raw.ttftMs() != null) node.put("ttft_ms", raw.ttftMs());
                yield node;
            }
            case SDKMessage.StreamRequestStart _ -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "status");
                node.put("status", "requesting");
                yield node;
            }
            case SDKMessage.Status status -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "status");
                if (status.status() == null) node.putNull("status");
                else node.put("status", status.status());
                if (status.compactResult() != null) {
                    node.put("compact_result", status.compactResult());
                }
                if (status.compactError() != null) {
                    node.put("compact_error", status.compactError());
                }
                yield node;
            }
            case SDKMessage.CompactBoundary boundary -> compactBoundaryJson(boundary, sessionId);
            case SDKMessage.TaskStarted started -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "task_started");
                node.put("task_id", started.taskId());
                if (started.toolUseId() != null) node.put("tool_use_id", started.toolUseId());
                node.put("description", started.description());
                if (started.taskType() != null) node.put("task_type", started.taskType());
                if (started.workflowName() != null) {
                    node.put("workflow_name", started.workflowName());
                }
                if (started.prompt() != null) node.put("prompt", started.prompt());
                if (started.subagentType() != null) {
                    node.put("subagent_type", started.subagentType());
                }
                yield node;
            }
            case SDKMessage.TaskProgress progress -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "task_progress");
                node.put("task_id", progress.taskId());
                if (progress.toolUseId() != null) node.put("tool_use_id", progress.toolUseId());
                node.put("description", progress.description());
                if (progress.subagentType() != null) {
                    node.put("subagent_type", progress.subagentType());
                }
                if (!progress.usage().isEmpty()) {
                    node.set("usage", MAPPER.valueToTree(progress.usage()));
                }
                if (progress.lastToolName() != null) {
                    node.put("last_tool_name", progress.lastToolName());
                }
                yield node;
            }
            case SDKMessage.TaskUpdated updated -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "task_updated");
                node.put("task_id", updated.taskId());
                node.set("patch", MAPPER.valueToTree(updated.patch()));
                yield node;
            }
            case SDKMessage.TaskNotification notification -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "task_notification");
                node.put("task_id", notification.taskId());
                if (notification.toolUseId() != null) {
                    node.put("tool_use_id", notification.toolUseId());
                }
                node.put("status", notification.status());
                node.put("output_file", notification.outputFile());
                node.put("summary", notification.summary());
                if (!notification.usage().isEmpty()) {
                    node.set("usage", MAPPER.valueToTree(notification.usage()));
                }
                yield node;
            }
            case SDKMessage.StreamEvent _ -> null;
            case SDKMessage.Error e -> {
                ObjectNode node = envelope("system", sessionId, false);
                node.put("subtype", "error");
                String friendly = e.exception() instanceof FriendlyApiError fae ? fae.friendlyMessage() : null;
                node.put("content", friendly != null ? friendly
                    : e.exception() != null ? String.valueOf(e.exception().getMessage()) : "");
                yield node;
            }
            case SDKMessage.ToolUseSummary tus -> {
// plain object with
                // camelCase keys, serialized as-is (no snake_case remap for this type).
                ObjectNode node = envelope("tool_use_summary", sessionId, false);
                node.put("summary", tus.summary());
                node.set("precedingToolUseIds", MAPPER.valueToTree(tus.precedingToolUseIds()));
                yield node;
            }
            default -> null; // Progress/Attachment/Tombstone have no public wire shape.
        };
    }

    private static String modelIdForDisplay(String display, SdkOutputMetadata metadata) {
        if (metadata != null && ModelNames.displayName(metadata.model()).equals(display)) {
            return metadata.model();
        }
        return switch (display) {
            case "Opus 5" -> "claude-opus-5";
            case "Sonnet 4.5" -> "claude-sonnet-4-5";
            case "Sonnet 4.6" -> "claude-sonnet-4-6";
            case "Opus 4.6" -> "claude-opus-4-6";
            case "Haiku 4.5" -> "claude-haiku-4-5";
            default -> display;
        };
    }


    private static ObjectNode compactBoundaryJson(
            SDKMessage.CompactBoundary boundary, String sessionId) {
        SystemMessage message = boundary.boundaryMessage();
        if (message == null) return null;

        ObjectNode node = envelope("system", sessionId, false);
        node.put("subtype", "compact_boundary");
        if (message.uuid() != null) node.put("uuid", message.uuid());
        CompactMetadata metadata = message.compactMetadata();
        if (metadata != null) node.set("compact_metadata", compactMetadataJson(metadata));
        return node;
    }

    private static ObjectNode compactMetadataJson(CompactMetadata metadata) {
        ObjectNode node = MAPPER.createObjectNode();
        if (metadata.trigger() != null) node.put("trigger", metadata.trigger());
        if (metadata.preTokens() != null) node.put("pre_tokens", metadata.preTokens());
        if (metadata.postTokens() != null) node.put("post_tokens", metadata.postTokens());
        if (metadata.cumulativeDroppedTokens() != null) {
            node.put("cumulative_dropped_tokens", metadata.cumulativeDroppedTokens());
        }
        if (metadata.durationMs() != null) node.put("duration_ms", metadata.durationMs());
        PreservedSegment segment = metadata.preservedSegment();
        if (segment != null) {
            ObjectNode preserved = node.putObject("preserved_segment");
            if (segment.headUuid() != null) preserved.put("head_uuid", segment.headUuid());
            if (segment.anchorUuid() != null) preserved.put("anchor_uuid", segment.anchorUuid());
            if (segment.tailUuid() != null) preserved.put("tail_uuid", segment.tailUuid());
        }
        PreservedMessages messages = metadata.preservedMessages();
        if (messages != null) {
            ObjectNode preserved = node.putObject("preserved_messages");
            if (messages.anchorUuid() != null) preserved.put("anchor_uuid", messages.anchorUuid());
            if (messages.uuids() != null) preserved.set("uuids", MAPPER.valueToTree(messages.uuids()));
            if (messages.allUuids() != null) {
                preserved.set("all_uuids", MAPPER.valueToTree(messages.allUuids()));
            }
        }
        if (metadata.userContext() != null) node.put("user_context", metadata.userContext());
        if (metadata.messagesSummarized() != null) {
            node.put("messages_summarized", metadata.messagesSummarized());
        }
        if (metadata.precomputed() != null) node.put("precomputed", metadata.precomputed());
        if (metadata.preCompactDiscoveredTools() != null) {
            node.set("pre_compact_discovered_tools",
                MAPPER.valueToTree(metadata.preCompactDiscoveredTools()));
        }
        return node;
    }

    private static ArrayNode toolUseMetadata(
            List<ContentBlock> blocks, SdkOutputMetadata metadata) {
        ArrayNode out = MAPPER.createArrayNode();
        Map<String, McpToolUseMetadata> byTool = metadata.mcpToolUseMetadata().stream()
            .collect(Collectors.toMap(
                McpToolUseMetadata::toolName,
                value -> value,
                (first, _) -> first,
                LinkedHashMap::new));
        for (ContentBlock block : blocks) {
            if (!(block instanceof ToolUseBlock toolUse)) continue;
            McpToolUseMetadata info = byTool.get(toolUse.name());
            if (info == null) continue;
            ObjectNode item = out.addObject();
            item.put("id", toolUse.id());
            item.put("display_name", info.displayName());
            item.put("server_display_name", info.serverDisplayName());
        }
        return out;
    }

    private static String terminalReason(SDKMessage.Result result) {
        if (!SDKMessage.Result.ERROR_DURING_EXECUTION.equals(result.resultType())) {
            return result.isError() ? "error" : "completed";
        }
        List<Message> messages = result.messages();
        if (messages != null) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message message = messages.get(i);
                if (!(message instanceof UserMessage)
                        && !(message instanceof AssistantMessage)) {
                    continue;
                }
                String text = MessageConstants.getUserMessageText(message);
                if (MessageConstants.INTERRUPT_MESSAGE.equals(text)) {
                    return "aborted_streaming";
                }
                if (MessageConstants.INTERRUPT_MESSAGE_FOR_TOOL_USE.equals(text)) {
                    return "aborted_tools";
                }
                break;
            }
        }
        return result.isError() ? "error" : "completed";
    }

    private static boolean isToolResultUser(UserMessage message) {
        return message != null && message.message() != null
            && message.message().blocks() != null
            && !message.message().blocks().isEmpty()
            && message.message().blocks().getFirst() instanceof ToolResultBlock;
    }

    private static ObjectNode commandsChangedMessage(SdkOutputState state) {
        ObjectNode node = envelope("system", state.metadata().sessionId(), false);
        node.put("subtype", "commands_changed");
        ArrayNode commands = node.putArray("commands");
        for (SdkInboundControlHandler.CommandInfo info : state.controlCatalog().commands()) {
            ObjectNode command = commands.addObject();
            command.put("name", info.name());
            command.put("description", info.description());
            command.put("argumentHint", info.argumentHint());
            if (!info.aliases().isEmpty()) {
                command.set("aliases", MAPPER.valueToTree(info.aliases()));
            }
        }
        return node;
    }

    private static ObjectNode initMessage(SdkOutputMetadata metadata) {
        ObjectNode node = envelope("system", metadata.sessionId(), false);
        node.put("subtype", "init");
        node.put("cwd", metadata.cwd());
        node.set("tools", MAPPER.valueToTree(metadata.tools()));
        node.set("mcp_servers", MAPPER.valueToTree(metadata.mcpServers()));
        node.put("model", metadata.model());
        node.put("permissionMode", metadata.permissionMode());
        node.set("slash_commands", MAPPER.valueToTree(metadata.slashCommands()));
        node.put("apiKeySource", metadata.apiKeySource());
        node.put("claude_code_version", metadata.claudeCodeVersion());
        node.put("output_style", metadata.outputStyle());
        node.set("agents", MAPPER.valueToTree(metadata.agents()));
        node.set("skills", MAPPER.valueToTree(metadata.skills()));
        node.set("plugins", MAPPER.valueToTree(metadata.plugins()));
        boolean nonessentialTrafficDisabled = SubprocessEnvironment.get(
            "CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC") != null;
        node.put("analytics_disabled", nonessentialTrafficDisabled);
        node.put("product_feedback_disabled", nonessentialTrafficDisabled);
        ObjectNode memoryPaths = node.putObject("memory_paths");
        String sanitized = metadata.cwd() == null ? "" : SessionManager.sanitizePath(metadata.cwd());
        String configDir = System.getenv("CLAUDE_CONFIG_DIR");
        String claudeHome = StringUtils.isNotBlank(configDir)
            ? configDir : System.getProperty("user.home") + "/.claude";
        memoryPaths.put("auto", claudeHome + "/projects/" + sanitized + "/memory/");
        node.put("fast_mode_state", metadata.fastModeState());
        return node;
    }

    private static ObjectNode baseUsage(Usage usage) {
        Usage safe = usage != null ? usage : Usage.EMPTY;
        ObjectNode node = MAPPER.createObjectNode();
        node.put("input_tokens", safe.inputTokens());
        node.put("output_tokens", safe.outputTokens());
        if (safe.cacheCreationInputTokens() != 0) {
            node.put("cache_creation_input_tokens", safe.cacheCreationInputTokens());
        }
        if (safe.cacheReadInputTokens() != 0) {
            node.put("cache_read_input_tokens", safe.cacheReadInputTokens());
        }
        if (safe.serverToolUse() != null
                && (safe.serverToolUse().webSearchRequests() != 0
                    || safe.serverToolUse().webFetchRequests() != 0)) {
            node.set("server_tool_use", MAPPER.valueToTree(safe.serverToolUse()));
        }
        return node;
    }

    /** Serialize a polymorphic content-block list with its declared interface type. */
    private static JsonNode contentBlocksJson(List<ContentBlock> blocks) {
        try {
            var listType = MAPPER.getTypeFactory()
                .constructCollectionType(List.class, ContentBlock.class);
            byte[] encoded = MAPPER.writerFor(listType).writeValueAsBytes(blocks);
            return MAPPER.readTree(encoded);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize assistant content blocks", e);
        }
    }

    private static JsonNode userContentBlocksJson(
            List<ContentBlock> blocks, Object toolUseResult) {
        ArrayNode out = MAPPER.createArrayNode();
        boolean preserveToolResultContentBlocks =
            isContentBlockArrayToolUseResult(toolUseResult);
        for (ContentBlock block : blocks != null ? blocks : List.<ContentBlock>of()) {
            if (block instanceof ToolResultBlock result) {
                ObjectNode node = out.addObject();
                node.put("tool_use_id", result.toolUseId());
                node.put("type", "tool_result");
                List<ContentBlock> content = result.content();
                if (!preserveToolResultContentBlocks
                        && !result.preserveContentBlocks()
                        && content != null && content.size() == 1
                        && content.getFirst() instanceof TextBlock(String text1)) {
                    node.put("content", text1);
                } else {
                    node.set("content", contentBlocksJson(content != null ? content : List.of()));
                }
                if (result.serializedIsError() != null) {
                    node.put("is_error", result.serializedIsError());
                }
            } else {
                out.add(contentBlocksJson(List.of(block)).get(0));
            }
        }
        return out;
    }

    private static boolean isContentBlockArrayToolUseResult(Object toolUseResult) {
        JsonNode node = toolUseResult instanceof JsonNode json
            ? json : MAPPER.valueToTree(toolUseResult);
        if (node == null || !node.isArray() || node.isEmpty()) return false;
        for (JsonNode item : node) {
            if (!item.isObject() || !item.hasNonNull("type")) return false;
        }
        return true;
    }

    private static ObjectNode extendedUsage(Usage usage) {
        Usage safe = usage != null ? usage : Usage.EMPTY;
        ObjectNode node = baseUsage(usage);
        node.put("cache_creation_input_tokens", safe.cacheCreationInputTokens());
        node.put("cache_read_input_tokens", safe.cacheReadInputTokens());
        node.set("server_tool_use", MAPPER.valueToTree(safe.serverToolUse()));
        node.put("service_tier", "standard");
        ObjectNode cacheCreation = node.putObject("cache_creation");
        cacheCreation.put("ephemeral_1h_input_tokens", 0);
        cacheCreation.put("ephemeral_5m_input_tokens", 0);
        node.put("inference_geo", "");
        node.putArray("iterations");
        node.put("speed", "standard");
        return node;
    }

    private static ObjectNode modelUsage(String model, Usage usage, double cost) {
        Usage safe = usage != null ? usage : Usage.EMPTY;
        ObjectNode node = MAPPER.createObjectNode();
        node.put("inputTokens", safe.inputTokens());
        node.put("outputTokens", safe.outputTokens());
        node.put("cacheReadInputTokens", safe.cacheReadInputTokens());
        node.put("cacheCreationInputTokens", safe.cacheCreationInputTokens());
        node.put("webSearchRequests", safe.webSearchRequests());
        node.put("costUSD", cost);
        node.put("contextWindow", 200_000);
        node.put("maxOutputTokens",
            ModelOutputTokens.getModelMaxOutputTokens(model).defaultTokens());
        return node;
    }

    private static ObjectNode envelope(String type, String sessionId, boolean parentToolUseId) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("session_id", sessionId);
        node.put("uuid", UUID.randomUUID().toString());
        if (parentToolUseId) node.putNull("parent_tool_use_id");
        return node;
    }
}

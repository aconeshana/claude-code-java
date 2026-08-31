package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.commands.context.ContextData;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.constants.AnsiColor;
import com.claudecode.core.constants.AnsiStyle;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.cron.CronFeatureGate;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.loop.LoopFeatureGate;
import com.claudecode.tools.loop.LoopWakeupManager;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.MarkdownRenderer;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.lsp.LspToolUseSummary;
import com.claudecode.core.platform.Platform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Owns non-interactive stdout rendering and the stream-JSON SDK control protocol.
 */
final class CliHeadlessOutput {

    private CliHeadlessOutput() {}

    private static final String RST = "\u001B[0m";
    private static final String BLD = AnsiStyle.BOLD.on();
    private static final String DM = AnsiStyle.DIM.on();
    private static final String GRN = AnsiColor.GREEN.code();
    private static final String RD = AnsiColor.RED.code();
    private static final String CYN = AnsiColor.CYAN.code();

    /**
     * SDK control mode (--input-format=stream-json): reads NDJSON from stdin on an independent Virtual
     * Thread, dispatching {@code user} messages as prompts to the engine (each drained to stdout as
     * stream-json) and {@code control_response} messages to the {@link SdkControlBroker}.
     */
    static int runSdkControlMode(QuerySession engine, CliOutput out, CliOutput errorOutput,
                                         PermissionGate permissionGate, String cwd,
                                         boolean includePartialMessages, boolean replayUserMessages,
                                         JsonNode jsonSchema,
                                         Supplier<StdoutMessageWriter.SdkOutputState> stateSupplier,
                                         String restoredSessionMode,
                                         String permissionPromptToolName,
                                         Supplier<ContextData> contextUsageSupplier,
                                         Supplier<Map<String, String>> mcpStatusSupplier,
                                         String initialSessionName,
                                         TerminalSessionTitleGenerator titleGenerator,
                                         Function<String, String> sideQuestionRunner,
                                         CliPluginRuntimeView pluginRuntime,
                                         McpRuntime mcpRuntime,
                                         AtomicReference<SdkControlBroker> brokerRef,
                                         HookEngine hookEngine) {
        StdoutMessageWriter.SdkOutputState initialState = stateSupplier.get();
        SdkControlBroker broker = new SdkControlBroker(
            out, engine, permissionGate, cwd, true);
        if (brokerRef != null) brokerRef.set(broker);
        engine.execution().setRefusalFallbackPrompt(broker);
        if (Strings.CS.equals("stdio", permissionPromptToolName)) {
            engine.execution().setPermissionAskCallback(
                engine.execution().withDenialRecording(new ControlPermissionAskCallback(broker)));
        }
        BlockingQueue<SdkUserInput> userQueue = new LinkedBlockingQueue<>();
        Set<String> scheduledInputIds = ConcurrentHashMap.newKeySet();
        final SdkUserInput poison = new SdkUserInput(null, null, null);
        AtomicBoolean inputError = new AtomicBoolean(false);
        SdkControlRuntime controlRuntime = new DefaultSdkControlRuntime(
            engine, cwd, contextUsageSupplier, mcpStatusSupplier,
            uuid -> cancelQueuedSdkMessage(userQueue, uuid), titleGenerator, sideQuestionRunner,
            pluginRuntime, mcpRuntime, stateSupplier, hookEngine, broker);
        SdkInboundControlHandler inboundControl =
            new SdkInboundControlHandler(out, engine, permissionGate,
                initialState.metadata(), initialState.controlCatalog(), controlRuntime,
                initialState.showBuiltInModelFamilies(), initialState.customModelNames());
        TranscriptSink transcript = engine.execution().getTranscriptSink();
        String sessionId = engine.conversation().getSessionId();
        AtomicBoolean firstSdkInputAccepted = new AtomicBoolean(false);
        Set<String> preRecordedDequeues = ConcurrentHashMap.newKeySet();
        HeadlessSessionTitleCoordinator automaticTitles =
            HeadlessSessionTitleCoordinator.create(
                engine, titleGenerator, cwd, initialSessionName);


        CronScheduler scheduler = new CronScheduler(
            () -> false,
            fired -> enqueueScheduledSdkPrompt(
                fired, userQueue, scheduledInputIds, transcript, sessionId),
            Path.of(cwd), engine.conversation().getSessionId(),
            () -> !CronFeatureGate.system().cronEnabled());
        scheduler.start();

        Thread stdinThread = Thread.startVirtualThread(() -> {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (StringUtils.isBlank(line)) continue;
                    try {
                        JsonNode node = JsonUtils.getMapper().readTree(line);
                        String type = node.path("type").asText(null);
                        if (Strings.CS.equals("user", type)) {
                            SdkUserInput input = parseSdkUserInput(node, Instant.now());
                            if (input != null) {
                                automaticTitles.maybeGenerate(input.content());
                                if (transcript != null) {
                                    transcript.recordQueueOperation(
                                        sessionId, "enqueue", sdkQueueContent(input.content()));
                                }

                                if (transcript != null
                                        && firstSdkInputAccepted.compareAndSet(false, true)) {
                                    transcript.recordPromptStart(sessionId, "sdk");
                                    transcript.recordQueueOperation(sessionId, "dequeue", null);
                                    preRecordedDequeues.add(input.uuid());
                                }
                                // Publish only after the pre-recorded dequeue marker
                                // is visible. Otherwise, the consumer can win this race
                                // and persist the same prompt-start/dequeue twice.
                                userQueue.add(input);
                            }
                        } else if (Strings.CS.equals("control_response", type)) {
                            handleSdkControlResponse(
                                node, broker, out, replayUserMessages);
                        } else if (Strings.CS.equals("control_request", type)) {
                            JsonNode request = node.path("request");
                            String subtype = request.path("subtype").asText("");
                            if ((Strings.CS.equals("initialize", subtype)
                                    && !StringUtils.isBlank(request.path("title").asText("")))
                                    || (Strings.CS.equals("generate_session_title", subtype)
                                        && request.path("persist").asBoolean(false))) {
                                automaticTitles.markTitlePresent();
                            }
                            if (inboundControl.handle(node)
                                    == SdkInboundControlHandler.Action.END_SESSION) {
                                userQueue.add(poison);
                                break;
                            }
                        } else if (Strings.CS.equals("update_environment_variables", type)) {
                            controlRuntime.updateEnvironmentVariables(node.path("variables"));
                            String requestId = node.path("request_id").asText(null);
                            if (requestId != null) {
                                inboundControl.writeSuccess(requestId, null);
                            }
                        }
                        // other message types are ignored in SDK input mode
                    } catch (Exception error) {

                        inputError.set(true);
                        errorOutput.println(formatStreamingInputError(line, error));
                        userQueue.add(poison);
                        break;
                    }
                }
            } catch (IOException _) {
                // stdin closed
            }
            userQueue.add(poison);
        });

        String lastPrompt = null;
        SDKMessage.Result lastResult = null;
        boolean processedSdkTurn = false;
        try {
            while (true) {
                SdkUserInput input = userQueue.take();
                if (input == poison) break;
                boolean scheduled = scheduledInputIds.remove(input.uuid());
                List<SdkUserInput> batch = new ArrayList<>();
                batch.add(input);
                if (!scheduled && processedSdkTurn) {
                    while (true) {
                        SdkUserInput next = userQueue.peek();
                        if (next == null || next == poison
                                || scheduledInputIds.contains(next.uuid())) break;
                        batch.add(userQueue.remove());
                    }
                    if (batch.size() > 1) input = mergeSdkUserInputs(batch);
                }
                if (!scheduled) controlRuntime.prepareForTurn();
                if (transcript != null && !scheduled) {
                    // processTextPrompt creates a fresh prompt id when a turn is
                    // accepted for processing, not when stdin merely queues it.
                    // Multiple NDJSON users may be enqueued before the first
                    // dequeue; starting identity on enqueue would make them all
                    // inherit the last queued turn's promptId.
                    boolean promptStarted = false;
                    for (SdkUserInput accepted : batch) {
                        if (preRecordedDequeues.remove(accepted.uuid())) continue;
                        if (!promptStarted) {
                            transcript.recordPromptStart(sessionId, "sdk");
                            promptStarted = true;
                        }
                        transcript.recordQueueOperation(sessionId, "dequeue", null);
                    }
                    inboundControl.flushPendingTranscriptBreadcrumbs();
                }
                // Drain the engine turn, emitting SDKMessages as stream-json. Permission asks
                // inside block on the broker's control channel (resolved by stdinThread above).
                SubmitOptions submitOptions = jsonSchema != null
                    ? SubmitOptions.withSchema("user", jsonSchema)
                    : SubmitOptions.DEFAULT;
                if (scheduled) submitOptions = submitOptions.asSlashCommand();
                submitOptions = submitOptions.withPromptIdentity(input.uuid(), input.timestamp());
                Object prompt = input.content().isText()
                    ? input.content().text() : input.content();
                lastPrompt = sdkLastPrompt(input.content());
                lastResult = StdoutMessageWriter.run(engine, prompt, submitOptions, out,
                    "stream-json", true, includePartialMessages, stateSupplier,
                    replayUserMessages, scheduled ? "task-notification" : null, broker);
                processedSdkTurn = true;
                LoopWakeupManager.global().onTurnIdle();
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        } finally {
            scheduler.stop();
            broker.close();
        }
        try {
            stdinThread.join();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        inboundControl.awaitPendingResponses(120_000);
        if (transcript != null && lastPrompt != null) {
            transcript.recordLastPrompt(sessionId, lastPrompt);
            if (StringUtils.isNotBlank(restoredSessionMode)) {
                transcript.recordMode(sessionId, restoredSessionMode);
            }
            transcript.awaitPendingWrites(sessionId, 5_000);
        }
        if (transcript instanceof TranscriptRecorder recorder) {
            recorder.releaseSessionState(sessionId, 5_000);
        }
        return inputError.get() || (lastResult != null && lastResult.isError()) ? 1 : 0;
    }

    /** Compatibility bridge for tests and package-local callers that inject a writer. */
    static int runSdkControlMode(QuerySession engine, PrintWriter out, PermissionGate permissionGate, String cwd,
                                 boolean includePartialMessages, boolean replayUserMessages,
                                 JsonNode jsonSchema,
                                 Supplier<StdoutMessageWriter.SdkOutputState> stateSupplier,
                                 String restoredSessionMode, String permissionPromptToolName,
                                 Supplier<ContextData> contextUsageSupplier,
                                 Supplier<Map<String, String>> mcpStatusSupplier,
                                 TerminalSessionTitleGenerator titleGenerator,
                                 Function<String, String> sideQuestionRunner,
                                 CliPluginRuntimeView pluginRuntime, McpRuntime mcpRuntime,
                                 AtomicReference<SdkControlBroker> brokerRef,
                                 HookEngine hookEngine) {
        CliOutput borrowed = CliOutput.borrowed(out);
        return runSdkControlMode(engine, borrowed, borrowed, permissionGate, cwd,
            includePartialMessages, replayUserMessages, jsonSchema, stateSupplier,
            restoredSessionMode, permissionPromptToolName, contextUsageSupplier,
            mcpStatusSupplier, null, titleGenerator, sideQuestionRunner, pluginRuntime,
            mcpRuntime, brokerRef, hookEngine);
    }

    private static void enqueueScheduledSdkPrompt(
            CronScheduler.FiredTask fired,
            BlockingQueue<SdkUserInput> userQueue,
            Set<String> scheduledInputIds,
            TranscriptSink transcript,
            String sessionId) {
        String uuid = UUID.randomUUID().toString();
        Instant timestamp = Instant.now();
        SdkUserInput input = new SdkUserInput(
            MessageContent.ofText(fired.resolvedPrompt()), uuid, timestamp);
        scheduledInputIds.add(uuid);
        if (transcript != null) {
            transcript.recordQueueOperation(sessionId, "enqueue", fired.resolvedPrompt());
        }
        userQueue.add(input);
    }


    static void handleSdkControlResponse(JsonNode node, SdkControlBroker broker, PrintWriter out) {
        handleSdkControlResponse(node, broker, CliOutput.borrowed(out), true);
    }

    static void handleSdkControlResponse(JsonNode node, SdkControlBroker broker,
                                         CliOutput out, boolean replayUserMessages) {
        if (replayUserMessages && broker.hasPendingResponse(node)) {
            StdoutMessageWriter.writeControlMessage(node, out);
        }
        broker.onControlResponse(node);
    }

    static void handleSdkControlResponse(JsonNode node, SdkControlBroker broker,
                                         PrintWriter out, boolean replayUserMessages) {
        handleSdkControlResponse(node, broker, CliOutput.borrowed(out), replayUserMessages);
    }

    private static String sdkQueueContent(MessageContent content) {
        return content != null && content.isText() ? content.text() : null;
    }

    private static String sdkLastPrompt(MessageContent content) {
        if (content == null) return null;
        String text = content.isText()
            ? content.text()
            : content.blocks().stream()
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::text)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
        if (StringUtils.isBlank(text)) return null;
        String flattened = text.replace('\n', ' ').trim();
        return flattened.length() > 200
            ? flattened.substring(0, 200).trim() + "…"
            : flattened;
    }


    static StdoutMessageWriter.SdkOutputMetadata buildSdkOutputMetadata(
            QuerySession engine, SkillToolProvider skillToolProvider,
            CliPluginRuntimeView pluginRuntime) {
        StdoutMessageWriter.SdkOutputMetadata base =
            StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine);

        List<Skill> loadedSkills = skillToolProvider.getSkillLoader().loadAll();
        List<String> skills = sdkInvocableSkillNames(loadedSkills);



        // but exposing those in SDK init would advertise a different contract.
        List<String> slashCommands = sdkSlashCommandNames(loadedSkills);
        slashCommands.addAll(List.of(
            "clear", "compact", "config", "context", "heapdump", "init",
            "reload-skills", "review", "security-review", "usage", "insights",
            "goal", "team-onboarding"));
        // A user/project skill shadows the builtin command of the same name.
        slashCommands = new ArrayList<>(new LinkedHashSet<>(slashCommands));

        List<String> agents = sdkAgentNames(base.cwd());

        Map<String, StdoutMessageWriter.PluginInfo> pluginMap = new LinkedHashMap<>();
        if (pluginRuntime != null) {
            var snapshot = pluginRuntime.currentSnapshot();
            snapshot.commands().forEach(command -> pluginMap.putIfAbsent(command.pluginName(),
                new StdoutMessageWriter.PluginInfo(command.pluginName(),
                    command.loadedFrom() != null ? command.loadedFrom() : "",
                    command.source())));
            snapshot.skillDirs().forEach(skill -> pluginMap.putIfAbsent(skill.pluginName(),
                new StdoutMessageWriter.PluginInfo(skill.pluginName(),
                    skill.directory().toString(), "plugin")));
        }
        List<StdoutMessageWriter.PluginInfo> plugins = List.copyOf(pluginMap.values());

        return new StdoutMessageWriter.SdkOutputMetadata(
            base.sessionId(), base.cwd(), base.model(), base.permissionMode(),
            base.tools(), base.mcpServers(), slashCommands, base.apiKeySource(),
            base.claudeCodeVersion(), base.outputStyle(), agents, skills, plugins,
            List.of(), base.fastModeState());
    }

    static StdoutMessageWriter.SdkOutputMetadata withMcpRuntime(
            StdoutMessageWriter.SdkOutputMetadata base,
            McpRuntime mcpRuntime) {
        if (mcpRuntime == null) return base;

        List<StdoutMessageWriter.McpServerStatus> servers =
            mcpRuntime.snapshotServerStatuses().entrySet().stream()
                .map(entry -> new StdoutMessageWriter.McpServerStatus(
                    entry.getKey(), entry.getValue()))
                .toList();
        List<String> slashCommands = new ArrayList<>(base.slashCommands());
        for (String command : mcpRuntime.promptCommandNames()) {
            if (!slashCommands.contains(command)) slashCommands.add(command);
        }
        List<StdoutMessageWriter.McpToolUseMetadata> toolUseMetadata =
            mcpRuntime.snapshotToolDisplays().stream()
                .map(info -> new StdoutMessageWriter.McpToolUseMetadata(
                    info.toolName(), info.displayName(), info.serverDisplayName()))
                .toList();
        return new StdoutMessageWriter.SdkOutputMetadata(
            base.sessionId(), base.cwd(), base.model(), base.permissionMode(),
            base.tools(), servers, slashCommands, base.apiKeySource(),
            base.claudeCodeVersion(), base.outputStyle(), base.agents(),
            base.skills(), base.plugins(), toolUseMetadata, base.fastModeState());
    }

    /**
     * Builds the richer SDK {@code initialize} control response catalogue.
     */
    static SdkInboundControlHandler.ControlCatalog buildSdkControlCatalog(
            StdoutMessageWriter.SdkOutputMetadata metadata,
            SkillToolProvider skillToolProvider,
            CliPluginRuntimeView pluginRuntime) {
        List<Skill> loadedSkills = skillToolProvider.getSkillLoader().loadAll();
        Map<String, Skill> skillByName = new LinkedHashMap<>();
        for (Skill skill : loadedSkills) {
            if (skill != null && skill.name() != null) skillByName.put(skill.name(), skill);
        }
        Map<String, PluginCommandDefinition> pluginCommandByName =
            new LinkedHashMap<>();
        if (pluginRuntime != null) {
            for (PluginCommandDefinition command
                    : pluginRuntime.currentSnapshot().commands()) {
                pluginCommandByName.put(command.name(), command);
            }
        }

        List<SdkInboundControlHandler.CommandInfo> commands = metadata.slashCommands().stream()
            .map(name -> sdkCommandInfo(
                name, skillByName.get(name), pluginCommandByName.get(name)))
            .toList();

        Map<String, BuiltInAgentDefinitions.AgentDefinition> agentByName =
            AgentDefinitionLoader.getActive(metadata.cwd()).stream()
                .collect(Collectors.toMap(
                    BuiltInAgentDefinitions.AgentDefinition::agentType,
                    Function.identity(),
                    (_, right) -> right,
                    LinkedHashMap::new));
        List<SdkInboundControlHandler.AgentInfo> agents = metadata.agents().stream()
            .map(name -> sdkAgentInfo(name, agentByName.get(name)))
            .toList();

        return new SdkInboundControlHandler.ControlCatalog(
            commands, agents, List.of("default", "Proactive", "Explanatory", "Learning"));
    }

    static SdkInboundControlHandler.CommandInfo sdkCommandInfo(
            String name, Skill skill,
            PluginCommandDefinition pluginCommand) {
        if (pluginCommand != null) {
            String description = pluginCommand.description() != null
                ? pluginCommand.description() : "";
            if (StringUtils.isNotBlank(pluginCommand.pluginName())) {
                description = "(" + pluginCommand.pluginName() + ") " + description;
            } else {
                description += " (plugin)";
            }
            return sdkCommand(name, description, pluginCommand.argumentHint());
        }
        return switch (name) {
            case "deep-research" -> sdkCommand(name,
                "Deep research harness — fan-out web searches, fetch sources, adversarially "
                    + "verify claims, synthesize a cited report. (dynamic workflow)");
            case "debug" -> sdkCommand(name,
                "Enable debug logging for this session and help diagnose issues",
                "[issue description]");
            case "code-review" -> sdkCommand(name, skillDescription(skill),
                "[low|medium|high|xhigh|max] [--fix] [--comment] [<target>]");
            case "simplify" -> sdkCommand(name, skillDescription(skill), "[<target>]");
            case "batch" -> sdkCommand(name,
                "Research and plan a large-scale change, then execute it in parallel across "
                    + "5–30 isolated worktree agents that each open a PR.", "<instruction>");
            case "loop" -> new SdkInboundControlHandler.CommandInfo(name,
                skill != null ? skill.commandDescription()
                    : "Run a prompt or slash command on a recurring interval "
                        + "(e.g. /loop 5m /foo, defaults to 10m)",
                skill != null ? skill.argumentHint()
                    : LoopFeatureGate.system().defaultPromptEnabled()
                        ? "[interval] [prompt]" : "[interval] <prompt>",
                skill != null ? skill.aliases() : List.of("proactive"));
            case "run-skill-generator" -> sdkCommand(name,
                "Author or improve the run-<unit> skill — a per-project skill that tells agents "
                    + "how to build, launch, and drive this project's app. Use when the user asks "
                    + "to set up the project, get it running, write run instructions, or verify "
                    + "build/run steps work from a clean environment.");
            case "clear" -> new SdkInboundControlHandler.CommandInfo(name,
                "Start a new session with empty context; previous session stays on disk "
                    + "(resumable with /resume)", "[name]", List.of("reset", "new"));
            case "compact" -> sdkCommand(name,
                "Free up context by summarizing the conversation so far",
                "<optional custom summarization instructions>");
            case "config" -> new SdkInboundControlHandler.CommandInfo(name,
                "Set a setting by key", "key=value", List.of("settings"));
            case "context" -> sdkCommand(name, "Show current context usage");
            case "heapdump" -> sdkCommand(name, "Dump the JS heap to ~/Desktop");
            case "init" -> sdkCommand(name,
                "Initialize a new CLAUDE.md file with codebase documentation");
            case "reload-skills" -> sdkCommand(name,
                "Pick up skills added or changed on disk during this session");
            case "review" -> sdkCommand(name,
                "Review a GitHub pull request; for your working diff use /code-review",
                "[pr number]");
            case "security-review" -> sdkCommand(name,
                "Complete a security review of the pending changes on the current branch");
            case "usage" -> new SdkInboundControlHandler.CommandInfo(name,
                "Show session cost, plan usage, and what's contributing to your limits",
                "", List.of("cost", "stats"));
            case "insights" -> sdkCommand(name,
                "Generate a report analyzing your Claude Code sessions");
            case "goal" -> sdkCommand(name,
                "Set a goal — keep working until the condition is met");
            case "team-onboarding" -> sdkCommand(name,
                "Help teammates ramp on Claude Code with a guide from your usage");
            default -> sdkCommand(name, skillDescription(skill));
        };
    }

    private static SdkInboundControlHandler.CommandInfo sdkCommand(
            String name, String description) {
        return sdkCommand(name, description, "");
    }

    private static SdkInboundControlHandler.CommandInfo sdkCommand(
            String name, String description, String argumentHint) {
        return new SdkInboundControlHandler.CommandInfo(
            name, description, argumentHint, List.of());
    }

    private static String skillDescription(Skill skill) {
        if (skill == null || skill.description() == null) return "";
        return switch (skill.source()) {
            case PROJECT -> skill.description() + " (project)";
            case USER -> skill.description() + " (user)";
            case MANAGED -> skill.description() + " (managed)";
            case PLUGIN -> skill.description() + " (plugin)";
            default -> skill.description();
        };
    }

    private static SdkInboundControlHandler.AgentInfo sdkAgentInfo(
            String name, BuiltInAgentDefinitions.AgentDefinition definition) {
        String description;
        if (Strings.CS.equals("Explore", name)) {
            description = BuiltInAgentDefinitions.EXPLORE_SDK_DESCRIPTION;
        } else {
            description = definition != null && definition.whenToUse() != null
                ? definition.whenToUse() : "";
        }
        String model = definition != null ? definition.model() : null;
        return new SdkInboundControlHandler.AgentInfo(name, description, model);
    }

    /**
     * Projects the live Skill-tool inventory into the separate.
     */
    static List<String> sdkInvocableSkillNames(List<Skill> loadedSkills) {
        List<String> names = new ArrayList<>();
        Set<String> derivedNames = new LinkedHashSet<>();
        for (Skill skill : loadedSkills != null ? loadedSkills : List.<Skill>of()) {
            if (skill == null || skill.name() == null || StringUtils.isBlank(skill.name())) continue;
            if (skill.source() == Skill.SkillSource.BUILTIN) continue;

            if (skill.source() == Skill.SkillSource.PLUGIN
                    && Boolean.TRUE.equals(skill.frontmatter().get("pluginCommand"))) continue;
            if (skill.source() == Skill.SkillSource.BUNDLED
                    && Strings.CS.equals("keybindings-help", skill.name())) continue;
            names.add(skill.name());
            if (skill.source() == Skill.SkillSource.BUNDLED) {
                switch (skill.name()) {
                    case "verify" -> addSdkSkill(names, derivedNames, "debug");
                    case "simplify" -> addSdkSkill(names, derivedNames, "batch");
                    case "run" -> addSdkSkill(names, derivedNames, "run-skill-generator");
                    default -> { }
                }
            }
        }
        return List.copyOf(names);
    }

    /** Plugin commands are slash commands at their SkillLoader discovery position. */
    private static List<String> sdkSlashCommandNames(List<Skill> loadedSkills) {
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Skill skill : loadedSkills != null ? loadedSkills : List.<Skill>of()) {
            if (skill == null || skill.name() == null || StringUtils.isBlank(skill.name())) continue;
            if (skill.source() == Skill.SkillSource.BUILTIN) continue;
            if (skill.source() == Skill.SkillSource.BUNDLED
                    && Strings.CS.equals("keybindings-help", skill.name())) continue;
            if (seen.add(skill.name())) names.add(skill.name());
            if (skill.source() == Skill.SkillSource.BUNDLED) {
                switch (skill.name()) {
                    case "verify" -> addSdkSkill(names, seen, "debug");
                    case "simplify" -> addSdkSkill(names, seen, "batch");
                    case "run" -> addSdkSkill(names, seen, "run-skill-generator");
                    default -> { }
                }
            }
        }
        return names;
    }

    private static void addSdkSkill(List<String> names, Set<String> seen, String name) {
        if (seen.add(name)) names.add(name);
    }

    record SdkUserInput(MessageContent content, String uuid, Instant timestamp) {}


    private static SdkUserInput mergeSdkUserInputs(List<SdkUserInput> inputs) {
        if (inputs.size() == 1) return inputs.getFirst();
        boolean allText = inputs.stream().allMatch(input -> input.content().isText());
        MessageContent content;
        if (allText) {
            content = MessageContent.ofText(inputs.stream()
                .map(input -> input.content().text())
                .collect(Collectors.joining("\n")));
        } else {
            List<ContentBlock> blocks = new ArrayList<>();
            for (SdkUserInput input : inputs) {
                if (input.content().isText()) {
                    blocks.add(new TextBlock(input.content().text()));
                } else if (input.content().blocks() != null) {
                    blocks.addAll(input.content().blocks());
                }
            }
            content = MessageContent.ofBlocks(blocks);
        }
        SdkUserInput tail = inputs.getLast();
        return new SdkUserInput(content, tail.uuid(), tail.timestamp());
    }

    /** Drops only a still-pending SDK user message; an already-dequeued turn is untouched. */
    static boolean cancelQueuedSdkMessage(BlockingQueue<SdkUserInput> queue, String uuid) {
        if (queue == null || uuid == null) return false;
        return queue.removeIf(input -> uuid.equals(input.uuid()));
    }


    static SdkUserInput parseSdkUserInput(JsonNode node, Instant receivedAt) {
        if (node == null || !Strings.CS.equals("user", node.path("type").asText())) return null;

        MessageContent content = null;
        JsonNode message = node.get("message");
        if (message == null || !message.isObject()) {

            // role check; retain a stable marker so the caller can reproduce
            // its exact error envelope without leaking a Java stack trace.
            throw new IllegalArgumentException("__MISSING_USER_MESSAGE__");
        }
        String role = message.path("role").asText(null);
        if (!Strings.CS.equals("user", role)) {
            throw new IllegalArgumentException(
                "Error: Expected message role 'user', got '"
                    + (role == null ? "undefined" : role) + "'");
        }
        if (message.isObject()) {
            content = MessageContent.fromJson(message);
        } else if (node.has("content")) {
            ObjectNode wrapper = JsonUtils.getMapper().createObjectNode();
            wrapper.set("content", node.get("content"));
            content = MessageContent.fromJson(wrapper);
        }
        if (content == null || (content.text() == null && content.blocks() == null)) return null;

        String uuid = node.path("uuid").asText(null);
        return new SdkUserInput(content, uuid, receivedAt != null ? receivedAt : Instant.now());
    }


    private static String formatStreamingInputError(String line, Exception error) {
        if (error instanceof IllegalArgumentException
                && Strings.CS.equals("__MISSING_USER_MESSAGE__", error.getMessage())) {
            return "Error parsing streaming input line: " + line
                + ": TypeError: undefined is not an object (evaluating 't.message.role')";
        }
        String message = error.getMessage();
        if (message != null && Strings.CS.startsWith(message, "Error: ")) return message;
        return "Error parsing streaming input line: " + line + ": "
            + (message == null ? error.getClass().getSimpleName() : message);
    }

    static int processPrompt(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                              CliOutput out, boolean verbose) {
        return processPrompt(engine, prompt, submitOptions, out, "text", verbose, false,
            () -> new StdoutMessageWriter.SdkOutputState(
                StdoutMessageWriter.SdkOutputMetadata.fromEngine(engine),
                SdkInboundControlHandler.ControlCatalog.empty()));
    }

    static int processPrompt(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                             PrintWriter out, boolean verbose) {
        return processPrompt(engine, prompt, submitOptions, CliOutput.borrowed(out), verbose);
    }

    /**
     * Runs a one-shot headless prompt with the same live SDK catalogue used by stream-json stdin mode.
     */
    static int processPrompt(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                              CliOutput out, String outputFormat, boolean verbose,
                              boolean includePartialMessages,
                              Supplier<StdoutMessageWriter.SdkOutputState> outputStateSupplier) {
        if (Strings.CS.equals("json", outputFormat) || Strings.CS.equals("stream-json", outputFormat)) {
            Object currentPrompt = prompt;
            SubmitOptions currentOptions = submitOptions;
            QueuedCommand currentCommand = null;
            List<StdoutMessageWriter.DeferredResult> deferredResults = new ArrayList<>();
            SDKMessage.Result lastResult = null;
            while (true) {
                StdoutMessageWriter.writePendingSdkEvents(
                    engine, out, outputStateSupplier.get().metadata());
                if (currentCommand != null) {
                    StdoutMessageWriter.writeTaskNotificationEvent(
                        currentCommand, out, outputStateSupplier.get().metadata());
                    // A background task can transition between the preceding

                    // task_updated event before the notification-turn assistant.
                    StdoutMessageWriter.writePendingSdkEvents(
                        engine, out, outputStateSupplier.get().metadata());
                }
                StdoutMessageWriter.RunOutcome outcome =
                    StdoutMessageWriter.runDeferringOutcome(
                        engine, currentPrompt, currentOptions, out, outputFormat,
                        verbose, includePartialMessages, outputStateSupplier, false,
                        currentCommand != null ? currentCommand.mode() : null,
                        () -> !deferredResults.isEmpty() || hasFiniteBackgroundTasks());
                StdoutMessageWriter.DeferredResult deferred = outcome.deferredResult();
                if (deferred != null) deferredResults.add(deferred);
                if (outcome.result() != null) lastResult = outcome.result();
                LoopWakeupManager.global().onTurnIdle();
                HeadlessQueuedPrompt next = awaitNextHeadlessPrompt(engine, submitOptions);
                if (next == null) {
                    StdoutMessageWriter.writePendingSdkEvents(
                        engine, out, outputStateSupplier.get().metadata());
                    deferredResults.forEach(result ->
                        StdoutMessageWriter.writeDeferredResult(result, out));
                    break;
                }
                recordHeadlessCommandQueueOperations(engine, next.command());

                // background task is running. Once that task completes it
                // drains task_updated, releases the prior result, and only
                // then starts the queued task-notification turn. Keeping the
                // result until after that turn reverses the two result
                // envelopes and makes SDK consumers observe the wrong turn
                // boundary.
                if (!hasFiniteBackgroundTasks() && !deferredResults.isEmpty()) {
                    StdoutMessageWriter.writePendingSdkEvents(
                        engine, out, outputStateSupplier.get().metadata());
                    deferredResults.forEach(result ->
                        StdoutMessageWriter.writeDeferredResult(result, out));
                    deferredResults.clear();
                }
                currentPrompt = next.prompt();
                currentOptions = next.options();
                currentCommand = next.command();
            }
            return lastResult != null && lastResult.isError() ? 1 : 0;
        }
        // Track per-tool start times for execution duration display
        Map<String, Long> toolStartTimes = new HashMap<>();

        StringBuilder textBuffer = new StringBuilder();
        MarkdownRenderer markdown = MarkdownRenderer.shared();

        Object currentPrompt = prompt;
        SubmitOptions currentOptions = submitOptions;
        boolean streamedAny = false;
        boolean inThinking = false;
        SDKMessage.Result lastResult = null;
        while (true) {
            Iterator<SDKMessage> messages = engine.submission().submitMessage(currentPrompt, currentOptions);
            while (messages.hasNext()) {
                SDKMessage msg = messages.next();
            if (msg instanceof SDKMessage.StreamEvent(String et, Object data)) {
                switch (et) {
                    case "thinking_delta" -> {
                        if (data instanceof String t) {
                            if (!inThinking) {
                                out.print(DM + "💭 ");
                                inThinking = true;
                            }
                            out.print(t);
                            out.flush();
                        }
                    }
                    case "content_block_delta" -> {
                        if (data instanceof String t) {
                            if (inThinking) {
                                out.println(RST + "\n");
                                inThinking = false;
                            }
                            textBuffer.append(t);
                            streamedAny = true;
                        }
                    }
                    case "tool_call_start" -> {
                        if (inThinking) {
                            out.println(RST);
                            inThinking = false;
                        }
                        if (streamedAny) {
                            out.println();
                            streamedAny = false;
                        }
                        if (data instanceof String info) {
                            String[] p = info.split("\\|", 3);
                            String name = p.length > 0 ? p[0] : "?";
                            String rawInput = p.length > 2 ? p[2] : "";
                            String inputSummary = extractToolInputSummary(name, rawInput);
                            out.println(CYN + "⏺ " + BLD + name + RST +
                                (inputSummary.isEmpty() ? "" : "  " + inputSummary));
                            toolStartTimes.put(name, System.currentTimeMillis());
                        }
                    }
                    case "tool_result_success" -> {
                        if (data instanceof String info) {
                            String[] p = info.split("\\|", 2);
                            String toolName = p[0];
                            String output = p.length > 1 ? p[1] : "";
                            if (!output.isEmpty()) {
                                long lineCount = output.lines().count();
                                out.println(
                                    DM + "  stdout (" + lineCount + " line" + (lineCount != 1 ? "s"
                                        : "") + ")" + RST);
                            }
                            long elapsed =
                                System.currentTimeMillis() - toolStartTimes.getOrDefault(toolName,
                                    System.currentTimeMillis());
                            out.println(
                                GRN + "  ✓ " + FormatUtils.formatSecondsShort(elapsed) + RST);
                        }
                    }
                    case "tool_result_error" -> {
                        if (data instanceof String info) {
                            String[] p = info.split("\\|", 2);
                            String err = p.length > 1 ? p[1] : "Unknown error";
                            out.println(RD + "  ✗ " + p[0] + ": " + err + RST);
                        }
                    }
                    default -> {
                    }
                }
            } else if (msg instanceof SDKMessage.Assistant assistant) {
                if (inThinking) { out.println(RST); inThinking = false; }
                if (streamedAny) {

                    String fullText = textBuffer.toString();
                    textBuffer.setLength(0);
                    out.print(markdown.render(fullText));
                    out.println(); out.flush();
                } else { printAssistantMessage(assistant, out); }
                streamedAny = false;
            } else if (msg instanceof SDKMessage.Result result) {
                lastResult = result;
                if (!SDKMessage.Result.SUCCESS.equals(result.resultType())) {
                    if (inThinking) { out.println(RST); inThinking = false; }
                    if (streamedAny) { out.println(); streamedAny = false; }
                    out.println(RD + resultSubtypeMessage(result, engine) + RST);
                }
            } else if (msg instanceof SDKMessage.Error(Exception exception)) {
                if (inThinking) { out.println(RST); inThinking = false; }
                if (streamedAny) { out.println(); }
                out.println(RD + "Error: " + exception.getMessage() + RST);
                streamedAny = false;
            }
            }
            LoopWakeupManager.global().onTurnIdle();
            HeadlessQueuedPrompt next = awaitNextHeadlessPrompt(engine, submitOptions);
            if (next == null) break;
            recordHeadlessCommandQueueOperations(engine, next.command());
            currentPrompt = next.prompt();
            currentOptions = next.options();
        }
        return lastResult != null && lastResult.isError() ? 1 : 0;
    }

    static int processPrompt(QuerySession engine, Object prompt, SubmitOptions submitOptions,
                             PrintWriter out, String outputFormat, boolean verbose,
                             boolean includePartialMessages,
                             Supplier<StdoutMessageWriter.SdkOutputState> outputStateSupplier) {
        return processPrompt(engine, prompt, submitOptions, CliOutput.borrowed(out), outputFormat,
            verbose, includePartialMessages, outputStateSupplier);
    }


    static HeadlessQueuedPrompt awaitNextHeadlessPrompt(QuerySession engine,
                                                        SubmitOptions baseOptions) {
        while (true) {
            QueuedCommand command = engine.conversation().getMessageQueue().dequeue(cmd ->
                cmd.agentId() == null
                    && (Strings.CS.equals("prompt", cmd.mode())
                        || Strings.CS.equals("task-notification", cmd.mode())));
            if (command != null) {
                SubmitOptions options = Strings.CS.equals("task-notification", command.mode())
                    ? baseOptions.withQuerySource("task-notification")
                        .withPromptIdentity(UUID.randomUUID().toString(), Instant.now())
                    : command.isMeta() ? baseOptions.asSlashCommand() : baseOptions;
                return new HeadlessQueuedPrompt(command.text(), options, command);
            }
            boolean running = hasFiniteBackgroundTasks();
            if (!running) return null;
            try {
                //noinspection BusyWait
                Thread.sleep(100);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static boolean hasFiniteBackgroundTasks() {
        return TaskRegistry.global().listBackground().stream()
            .anyMatch(task -> task.type() != TaskType.IN_PROCESS_TEAMMATE);
    }

    record HeadlessQueuedPrompt(String prompt, SubmitOptions options,
                                QueuedCommand command) {}


    private static void recordHeadlessCommandQueueOperations(QuerySession engine,
                                                              QueuedCommand command) {
        if (command == null) return;
        TranscriptSink transcript = engine.execution().getTranscriptSink();
        if (transcript == null) return;
        transcript.recordQueueOperation(engine.conversation().getSessionId(), "enqueue", command.text());
        if (Strings.CS.equals("task-notification", command.mode())) {

            // processTextPrompt path. That path advances the global prompt id
            // before recordTranscript persists the synthetic user row, so the
            // notification starts a distinct transcript prompt lineage instead
            // of inheriting the human prompt that launched the background task.
            transcript.recordPromptStart(engine.conversation().getSessionId(), "sdk");
        }
        transcript.recordQueueOperation(engine.conversation().getSessionId(), "dequeue", null);
    }


    private static String resultSubtypeMessage(SDKMessage.Result result, QuerySession engine) {
        return switch (result.resultType()) {
            case SDKMessage.Result.ERROR_DURING_EXECUTION -> "Execution error";
            case SDKMessage.Result.ERROR_MAX_TURNS ->
                "Error: Reached max turns (" + engine.configuration().getConfig().maxTurns() + ")";
            case SDKMessage.Result.ERROR_MAX_BUDGET ->
                "Error: Exceeded USD budget (" + engine.configuration().getConfig().maxBudgetUsd() + ")";
            case SDKMessage.Result.ERROR_MAX_STRUCTURED_OUTPUT_RETRIES ->
                "Error: Failed to provide valid structured output after maximum retries";
            default -> "Error: " + result.resultType();
        };
    }

    /**
     * Extracts a human-readable input summary for a tool call,
     * showing the most relevant field instead of raw JSON.
     */
    static String extractToolInputSummary(String toolName, String rawInput) {
        if (StringUtils.isEmpty(rawInput)) return "";
        try {
            JsonNode node = JsonUtils.getMapper().readTree(rawInput);
            return switch (toolName) {
                case "Bash" -> getJsonField(node, "command");
                case "Read", "FileRead", "Write", "FileWrite", "Edit", "FileEdit"
                        -> getJsonField(node, "file_path");
                case "Grep", "GrepTool", "Glob", "GlobTool"
                        -> getJsonField(node, "pattern");
                case "LSP" -> LspToolUseSummary.format(node).orElse("");
                default -> truncateToolInput(rawInput);
            };
        } catch (Exception _) {
            return truncateToolInput(rawInput);
        }
    }

    private static List<String> sdkAgentNames(String cwd) {
        List<String> loadedAgents = AgentDefinitionLoader.getActive(cwd).stream()
            .map(BuiltInAgentDefinitions.AgentDefinition::agentType)
            .toList();
        return orderSdkAgentNames(loadedAgents);
    }

    static List<String> orderSdkAgentNames(List<String> loadedAgents) {
        List<String> preferredBuiltIns = List.of(
            "claude", "Explore", "general-purpose", "Plan");
        List<String> agents = new ArrayList<>();

        for (String name : loadedAgents) {
            if (!preferredBuiltIns.contains(name)
                    && !Strings.CS.equals("claude-code-guide", name)
                    && !Strings.CS.equals("verification", name)
                    && !Strings.CS.equals("statusline-setup", name)) {
                agents.add(name);
            }
        }
        for (String name : preferredBuiltIns) {
            if (loadedAgents.contains(name)) agents.add(name);
        }
        if (loadedAgents.contains("statusline-setup")) agents.add("statusline-setup");
        return List.copyOf(agents);
    }

    private static String truncateToolInput(String rawInput) {
        return rawInput.length() > 50 ? rawInput.substring(0, 50) + "..." : rawInput;
    }

    private static String getJsonField(JsonNode node, String field) {
        if (node != null && node.has(field)) {
            JsonNode val = node.get(field);
            return val.isTextual() ? val.asText() : val.toString();
        }
        return "";
    }


    static void validateSandboxAtStartup(CliOutput errorOutput) {
        SandboxConfig cfg = SandboxSettings.loadSandboxConfig();
        if (!cfg.enabled()) {
            return;
        }
// WSL1 has no real Linux kernel — bubblewrap/seccomp cannot run.
        if (Platform.IS_WSL && Platform.WSL_VERSION < 2) {
            errorOutput.println("""

                ⚠ Sandbox disabled: WSL1 is not supported (requires WSL2).\

                  Commands will run WITHOUT sandboxing. Network and filesystem restrictions will NOT be enforced.
                """);
            return;
        }
        SandboxManager mgr = PlatformSandboxManager.create();
        if (!mgr.isPlatformSupported(cfg)) {
            // Platform not in sandbox.enabledPlatforms (or enabledPlatforms is an
            // empty list) — sandbox is simply inactive here, no error.
            return;
        }
        // Linux/WSL: bubblewrap cannot match glob characters in filesystem paths, so warn about any
        // such entry.
        for (String w : mgr.globPatternWarnings(cfg)) {
            errorOutput.println("⚠ Sandbox filesystem path contains a glob pattern that bubblewrap "
                + "cannot match: " + w);
        }
        if (mgr.available()) {
            return;
        }
        String reason = mgr.unavailableReason();
        if (cfg.failIfUnavailable()) {
            errorOutput.println("\nError: sandbox required but unavailable: " + reason
                + "\n  sandbox.failIfUnavailable is set — refusing to start without a working sandbox.\n");
            System.exit(1);
            return;
        }
        errorOutput.println("\n⚠ Sandbox disabled: " + reason
            + "\n  Commands will run WITHOUT sandboxing. Network and filesystem restrictions will NOT be enforced.\n");
    }

    /**
     * Extracts and prints text from an assistant message.
     */
    static void printAssistantMessage(SDKMessage.Assistant assistant, CliOutput out) {
        if (assistant.message() != null && assistant.message().message() != null) {
            MarkdownRenderer md = MarkdownRenderer.shared();
            for (ContentBlock block : assistant.message().message().content()) {
                if (block instanceof TextBlock(String text)) {
                    out.println(md.render(text));
                }
            }
        }
    }
}

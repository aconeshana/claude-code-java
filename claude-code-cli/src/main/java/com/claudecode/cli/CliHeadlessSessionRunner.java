package com.claudecode.cli;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.LlmClient;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.bootstrap.CommandFactory;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.prompt.PromptInvocation;
import com.claudecode.commands.prompt.PromptInvocationLifecycle;
import com.claudecode.commands.prompt.PromptShellExecution;
import com.claudecode.commands.prompt.PromptShellExecutor;
import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.commands.impl.integration.McpPromptCommand;
import com.claudecode.commands.impl.integration.McpCommand;
import com.claudecode.commands.workflows.WorkflowCommandSync;
import com.claudecode.commands.workflows.WorkflowCommandDefinition;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.core.engine.CompactProgressEvent;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.core.engine.SubmitOptions;
import com.claudecode.core.engine.ToolContextModifier;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.engine.ToolResult;
import com.claudecode.core.engine.TranscriptSink;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.core.feature.FeatureGate.Flag;
import com.claudecode.core.message.ContentBlock;
import com.claudecode.core.message.ImageBlock;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.MessageContent;
import com.claudecode.core.message.MessageConstants;
import com.claudecode.core.message.SDKMessage;
import com.claudecode.core.message.SystemMessage;
import com.claudecode.core.message.TextBlock;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.services.compact.CompactService;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.services.insights.InsightsGenerator;
import com.claudecode.services.insights.InsightsPipeline;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.services.model.ModelValidator;
import com.claudecode.services.plugins.marketplace.PluginMarketplaceAdapter;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.session.SessionManager;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.cron.CronFeatureGate;
import com.claudecode.tools.cron.CronScheduler;
import com.claudecode.tools.loop.LoopPromptResolver;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.skills.InvokedSkillRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.workflows.BundledWorkflowLoader;
import com.claudecode.tools.workflows.WorkflowCatalog;
import com.claudecode.tools.workflows.WorkflowDefinition;
import com.claudecode.tools.workflows.WorkflowFeatureGate;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a single non-interactive CLI turn after the session has been assembled.
 */
final class CliHeadlessSessionRunner {

    private static final Logger log = LoggerFactory.getLogger(CliHeadlessSessionRunner.class);

    private CliHeadlessSessionRunner() {}

    /** All dependencies are explicit so this runner never reads mutable root-command fields. */
    record Request(
            QuerySession engine,
            String prompt,
            String initialSessionName,
            String setupTrigger,
            CliOutput output,
            CliOutput diagnosticOutput,
            JsonNode structuredOutputSchema,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState,
            String outputFormat,
            boolean verbose,
            boolean includePartialMessages,
            boolean startupSessionRestored,
            Runnable recoveryTranscript,
            String resolvedModel,
            PermissionGate permissionGate,
            ToolRegistry toolRegistry,
            CompactService compactService,
            Supplier<ContextData> contextDataCollector,
            HookEngine hookEngine,
            TranscriptRecorder transcriptRecorder,
            LlmClient llmClient,
            QuerySessionSpec config,
            McpRuntime mcpRuntime,
            CliPluginRuntimeView pluginRuntime,
            SkillToolProvider skillToolProvider,
            String cwd) {}

    /**
     * Processes either a slash-command invocation or a plain prompt and
     * returns the top-level process exit code.
     */
    static int run(Request request) {
        FileChangedHookWatcher fileWatcher = new FileChangedHookWatcher(request.hookEngine());
        CliHookEffectSink hookEffects = new CliHookEffectSink(
            request.engine(), request.transcriptRecorder(),
            request.skillToolProvider().getSkillLoader(), fileWatcher,
            request.output(), request.diagnosticOutput(), false,
            Strings.CS.equals("stream-json", request.outputFormat()));
        try (hookEffects) {
            fileWatcher.initialize(Path.of(request.cwd()),
                request.hookEngine().configuredFileChangedMatchers());
            request.hookEngine().setHookEffectSink(hookEffects);
            CliSessionAssembler.runSetupHook(request.setupTrigger(), request.hookEngine(),
                request.engine(), request.diagnosticOutput());
            request.engine().execution().setHookDispatcher(request.hookEngine());
            HeadlessCommands commands = Strings.CS.startsWith(request.prompt().stripLeading(), "/")
                ? createHeadlessCommands(request) : null;
            if (commands != null) {
                hookEffects.bindCommandRegistry(commands.registry());
            }
            TerminalSessionTitleGenerator titleGenerator = request.llmClient() != null
                ? new TerminalSessionTitleGenerator(request.llmClient(), request.resolvedModel())
                : null;
            return processSinglePrompt(
                request, commands,
                HeadlessSessionTitleCoordinator.create(
                    request.engine(), titleGenerator, request.cwd(), request.initialSessionName()));
        }
    }


    private record HeadlessCommands(
            CommandRegistry registry,
            CommandContext context,
            List<SDKMessage> localCommandSdkPrelude) {}

    static InsightsPort insightsAdapter(InsightsPipeline pipeline) {
        return () -> {
            InsightsPipeline.Report report = pipeline.generate();
            var data = report.data();
            return new InsightsPort.Report(report.insights(),
                InsightsGenerator.toJson(report.insights()),
                report.htmlPath(), new InsightsPort.Stats(
                    data.totalSessions(), data.totalSessionsScanned(), data.totalMessages(),
                    data.totalDurationHours(), data.gitCommits(),
                    data.dateRange().start(), data.dateRange().end()));
        };
    }

    /**
     * Reuses the live Bash/PowerShell tool path for markdown prompt shell
     * interpolation, including command-scoped allow rules, permission asks,
     * sandboxing and session-scoped output persistence.
     */
    static PromptShellExecutor newPromptShellExecutor(
            QuerySession engine, ToolRegistry toolRegistry, PermissionGate permissionGate) {
        return (text, _, allowedTools, shell) -> {
            List<PermissionRule> rules = CliPermissionRuleParser.parse(
                allowedTools, RuleSource.COMMAND);
            if (!rules.isEmpty()) permissionGate.addRules(rules);
            try {
                return PromptShellExecution.expand(text, (command, pattern) -> {
                    String toolName = Strings.CS.equals("powershell", shell)
                            && toolRegistry.get("PowerShell").isPresent()
                        ? "PowerShell" : "Bash";
                    ObjectNode input = JsonUtils.getMapper().createObjectNode();
                    input.put("command", command);
                    ToolExecutionContext toolContext = ToolExecutionContext
                        .builder(engine.execution().getAbortController(), engine.conversation().getSessionId())
                        .workingDirectory(engine.configuration().getConfig().workingDirectory())
                        .permissionAskCallback(engine.execution().getPermissionAskCallback())
                        .fileStateCache(engine.forks().getFileStateCache())
                        .fileHistoryManager(engine.conversation().getFileHistoryManager())
                        .messageQueueManager(engine.conversation().getMessageQueue())
                        .agentId(engine.configuration().getConfig().agentId())
                        .nestedMemoryAttachmentTriggers(engine.forks().getNestedMemoryAttachmentTriggers())
                        .loadedNestedMemoryPaths(engine.forks().getLoadedNestedMemoryPaths())
                        .teamMemoryEnabled(engine.configuration().getConfig().teamMemoryEnabledSupplier().get())
                        .currentModel(engine.configuration().getConfig().model())
                        .sandboxConfig(engine.configuration().getConfig().sandboxConfigSupplier().get())
                        .readDenyIgnorePatterns(engine.configuration().getConfig().readDenyIgnorePatternsSupplier().get())
                        .build()
                        .withWorkingDirectoryController(engine.configuration().workingDirectoryController());
                    ToolResult result = toolRegistry.execute(toolName, input, toolContext);
                    String output = promptShellOutput(result);
                    if (result.isError()) {
                        String detail = StringUtils.isBlank(output) ? "Permission denied" : output;
                        throw new IllegalStateException(
                            "Shell command permission check failed for pattern \""
                                + pattern + "\": " + detail);
                    }
                    return output;
                });
            } finally {
                if (!rules.isEmpty()) {
                    permissionGate.removeRules(rules::contains);
                }
            }
        };
    }

    private static String promptShellOutput(ToolResult result) {
        List<String> parts = new ArrayList<>();
        for (ContentBlock block : result.content()) {
            if (block instanceof TextBlock(String text1) && text1 != null) {
                parts.add(text1);
            } else if (block instanceof ImageBlock(JsonNode source) && source != null) {
                String mediaType = source.path("media_type").asText("");
                String data = source.path("data").asText("");
                if (!mediaType.isEmpty() && !data.isEmpty()) {
                    parts.add("data:" + mediaType + ";base64," + data);
                }
            }
        }
        return String.join("\n", parts);
    }

    private static HeadlessCommands createHeadlessCommands(Request request) {
        CliSettingsManagementAdapter settingsManagement = new CliSettingsManagementAdapter();
        var toolingCommands = CliToolingCommandAdapter.create(
            TaskRegistry.global(),
            InvokedSkillRegistry.global());
        CommandRegistry registry = CommandFactory.createDefault(settingsManagement, toolingCommands);
        Path cwd = Path.of(request.cwd());
        CliSkillCommandSync.sync(registry, request.skillToolProvider().getSkillLoader(), cwd);
        String commandName = request.prompt().stripLeading().substring(1)
            .split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (Strings.CS.startsWith(commandName, "mcp__")) {
            request.mcpRuntime().whenReady().join();
            CliMcpPromptAdapter promptAdapter =
                new CliMcpPromptAdapter(request.mcpRuntime().clientRuntime());
            request.mcpRuntime().syncPromptsToRegistry(info ->
                registry.register(new McpPromptCommand(
                    CliMcpPromptAdapter.definition(info), promptAdapter)));
        }
        if (request.pluginRuntime() != null) {
            request.pluginRuntime().attachCommandRegistry(registry);
        }
        var pluginMarketplace = PluginMarketplaceAdapter.standard(request.cwd(), () ->
            request.pluginRuntime() != null
                ? request.pluginRuntime().currentSnapshot().errors() : List.of(), () ->
            request.pluginRuntime() != null
                ? request.pluginRuntime().currentSnapshot().mcpServers() : List.of());
        var mcpManagement = new CliMcpManagementAdapter(
            cwd, request.mcpRuntime()::clientRuntime,
            request.toolRegistry(), registry, pluginMarketplace);
        registry.registerBuiltIn(new McpCommand(mcpManagement));
        syncWorkflowCommands(registry, Path.of(System.getProperty("user.dir")),
            request.pluginRuntime());

        LlmClient insightsClient = request.llmClient();
        QuerySessionSpec insightsConfig = request.config();
        Supplier<InsightsPort> insightsPipelineSupplier = () ->
            insightsClient == null ? null
                : insightsAdapter(new InsightsPipeline(insightsClient, () -> {
                    String env = SubprocessEnvironment.get("ANTHROPIC_DEFAULT_OPUS_MODEL");
                    return StringUtils.isNotBlank(env) ? env
                        : ModelNames.parseUserSpecifiedModel(insightsConfig.model());
                }, registry::isBuiltInCommandName));
        Runnable reAppendMetadata = () -> {
            String sid = request.engine().conversation().getSessionId();
            if (StringUtils.isBlank(sid)) return;
            try {
                TranscriptSink transcript = request.engine().execution().getTranscriptSink();
                if (transcript != null) {
                    transcript.flushCachedLastPrompt(sid);
                    transcript.awaitPendingWrites(sid, 2_000);
                }
                new SessionManager(System.getProperty("user.dir"))
                    .reAppendSessionMetadata(sid);
            } catch (Exception _) {
                // Metadata repair is best effort after compaction.
            }
        };
        Runnable postCompact = () -> {
            LoopPromptResolver.global().resetDeliveredState();
            request.transcriptRecorder().clearCompactionCaches(
                request.engine().conversation().getSessionId(), 2_000);
            reAppendMetadata.run();
        };

        ModelValidator modelValidator = request.llmClient() != null
            ? new ModelValidator(request.llmClient()) : null;
        List<SDKMessage> localCommandSdkPrelude = new ArrayList<>();
        CommandContext context = CommandContext.builder(
                request.resolvedModel(),
                request.engine().conversation()::getMessages,
                () -> { },
                model -> request.engine().configuration().setModel(ModelAllowlist.isAllowed(model)
                    ? model : ModelNames.defaultMainLoopModel()),
                request.engine().execution()::getTotalUsage,
                usage -> CostCalculator.forModel(ModelNames
                    .parseUserSpecifiedModel(request.engine().configuration().getConfig().model()))
                    .calculateCost(usage),
                System.getProperty("user.dir"),
                false)
            .modelSupplier(request.engine().configuration().getConfig()::model)
            .modelAllowed(ModelAllowlist::isAllowed)
            .modelValidator(name -> {
                if (!ModelAllowlist.isAllowed(name)) {
                    return ModelAllowlist.rejectionMessage(name);
                }
                if (modelValidator == null) return null;
                var result = modelValidator.validate(name);
                return result.valid() ? null : result.error();
            })
            .loadMessages(request.engine().conversation()::loadMessages)
            .currentSessionId(request.engine().conversation()::getSessionId)
            .permissionCommands(new CliPermissionCommandAdapter(request.permissionGate()))
            .sessionCommands(new CliSessionCommandAdapter(request.cwd()))
            .toolingCommands(toolingCommands)
            .promptShellExecutor(newPromptShellExecutor(
                request.engine(), request.toolRegistry(), request.permissionGate()))
            .compactService(request::compactService)
            .pluginRuntime(request.pluginRuntime())
            .doctor(CliRuntimeAdapters.newDoctorPort(
                request.permissionGate(), request.toolRegistry(), request.cwd(),
                request.pluginRuntime()))
            .dream(CliRuntimeAdapters.newDreamPort())
            .insightsPipeline(insightsPipelineSupplier)
            .settingsManagement(settingsManagement)
            .mcpManagement(mcpManagement)
            .transcriptRecorder(message ->
                request.transcriptRecorder().record(request.engine().conversation().getSessionId(), message))
            .postCompactCallback(postCompact)
            .hookDispatcher(request.hookEngine())
            .goalGate(CliRuntimeAdapters.newGoalGate(request.cwd(), true))
            .onCompactProgress(event -> {
                if (event instanceof CompactProgressEvent.HooksStart(String hookType)
                        && Strings.CS.equals("pre_compact", hookType)) {
                    localCommandSdkPrelude.add(
                        new SDKMessage.Status("compacting", null, null));
                }
            })
            .verboseSupplier(request::verbose)
            .messageAppender(request.engine().conversation()::appendTranscriptMessage)
            .contextDataCollector(request.contextDataCollector())
            .nonInteractive(true)
            .build();
        return new HeadlessCommands(registry, context, localCommandSdkPrelude);
    }


    static void syncWorkflowCommands(CommandRegistry registry, Path cwd,
                                     CliPluginRuntimeView pluginRuntime) {
        syncWorkflowCommands(registry, cwd, pluginRuntime, new WorkflowCommandSync());
    }

    static void syncWorkflowCommands(CommandRegistry registry, Path cwd,
                                     CliPluginRuntimeView pluginRuntime,
                                     WorkflowCommandSync commandSync) {
        boolean enabled = WorkflowFeatureGate.evaluate(
            SubprocessEnvironment.snapshot(),
            Boolean.TRUE.equals(RuntimeSettings.readPolicyBoolean("disableWorkflows")),
            RuntimeSettings.loadOptionalBoolean("enableWorkflows"),
            true,
            true);
        if (!enabled) {
            registry.unregisterMatching("workflows"::equals);
            commandSync.sync(registry, List.of());
            return;
        }
        WorkflowCatalog catalog = new WorkflowCatalog(
            ClaudePaths.WORKFLOWS_DIR,
            BundledWorkflowLoader.load(),
            () -> pluginWorkflows(pluginRuntime));
        commandSync.sync(registry, catalog.load(cwd).stream()
            .map(CliHeadlessSessionRunner::commandDefinition)
            .toList());
    }

    private static WorkflowCommandDefinition commandDefinition(WorkflowDefinition definition) {
        return new WorkflowCommandDefinition(
            definition.metadata().name(),
            definition.metadata().title(),
            definition.metadata().description(),
            definition.metadata().whenToUse(),
            definition.metadata().phases().stream()
                .map(phase -> new WorkflowCommandDefinition.Phase(phase.title(), phase.detail()))
                .toList(),
            definition.script(),
            WorkflowCommandDefinition.Source.valueOf(definition.source().name()),
            definition.pluginName(),
            definition.hidden());
    }

    static List<WorkflowDefinition> pluginWorkflows(CliPluginRuntimeView runtime) {
        return runtime == null ? List.of() : runtime.currentSnapshot().workflows();
    }

    private static int processSinglePrompt(
            Request request,
            HeadlessCommands headless,
            HeadlessSessionTitleCoordinator sessionTitles) {
        QuerySession engine = request.engine();
        TranscriptSink transcript = engine.execution().getTranscriptSink();
        sessionTitles.maybeGenerate(request.prompt());
        String restoredSessionMode = restoredSessionMode(request);

        // after last-prompt; it is intentionally not part of the recovery preamble.
        recordPromptPreamble(transcript, engine.conversation().getSessionId(), request.prompt(),
            request.recoveryTranscript());
        long startedAt = System.currentTimeMillis();
        CronScheduler scheduler = new CronScheduler(
            () -> false,
            fired -> engine.conversation().getMessageQueue().enqueuePendingNotification(
                QueuedCommand.modelScheduled(
                    fired.resolvedPrompt(), fired.prompt(), "cron", fired.agentId())),
            Path.of(engine.configuration().getConfig().workingDirectory()), engine.conversation().getSessionId(),
            () -> !CronFeatureGate.system().cronEnabled());
        scheduler.start();
        int exitCode = 0;
        boolean queriedPromptCommand = false;
        boolean suppressQueriedCommandLastPrompt = false;
        try {
            Optional<CommandResult> dispatched = headless != null
                ? headless.registry().dispatchNonInteractive(request.prompt(), headless.context())
                : Optional.empty();
            if (dispatched.isPresent()) {
                CommandResult result = dispatched.orElseThrow();
                if (result.shouldQuery()) {
                    queriedPromptCommand = true;
                    PromptInvocation invocation = result.promptInvocation() != null
                        ? result.promptInvocation() : PromptInvocation.text(result.output());
                    suppressQueriedCommandLastPrompt = invocation.suppressLastPrompt();
                    HookDispatcher promptHooks = headless.context().session().hookDispatcher();
                    HookDispatcher.HookOutcome expansionOutcome = PromptInvocationLifecycle.install(
                        invocation,
                        request.prompt(),
                        PromptInvocationLifecycle.commandNameFromInput(request.prompt()),
                        promptHooks,
                        headless.context().application().tooling().skillAttribution());
                    if (!expansionOutcome.proceed() || expansionOutcome.preventContinuation()) {
                        String reason = expansionOutcome.hasBlockingErrors()
                            ? expansionOutcome.blockingErrors().getFirst()
                            : expansionOutcome.stopReason();
                        if (Strings.CS.equals("text", request.outputFormat())) {
                            request.output().println(StringUtils.isNotBlank(reason)
                                ? "Prompt expansion blocked by hook: " + reason
                                : "Prompt expansion blocked by hook");
                        }
                        PromptInvocationLifecycle.clear(promptHooks);
                        return 1;
                    }
                    String activeCommand = PromptInvocationLifecycle.commandNameFromInput(request.prompt());
                    if (activeCommand != null && !invocation.suppressSkillAttribution()) {
                        engine.configuration().applyContextModifier(new ToolContextModifier(
                            invocation.allowedTools(), invocation.model(), invocation.effort(),
                            activeCommand, null));
                    }
                    PermissionGate gate = request.permissionGate();
                    if (gate != null) {
                        gate.removeRules(rule -> rule.source() == RuleSource.COMMAND);
                        gate.addRules(CliPermissionRuleParser.parse(
                            invocation.allowedTools(), RuleSource.COMMAND));
                    }
                    try {
                        SubmitOptions options = (request.structuredOutputSchema() != null
                            ? SubmitOptions.withSchema("user", request.structuredOutputSchema())
                            : SubmitOptions.DEFAULT)
                            .asSlashCommand()
                            .withPromptOverrides(invocation.model(), invocation.effort())
                            .withPrecedingUserMessages(invocation.precedingUserMessages());
                        if (expansionOutcome.hasAdditionalContext()) {
                            List<MessageContent> preceding = new ArrayList<>(options.precedingUserMessages());
                            expansionOutcome.additionalContexts().stream()
                                .filter(StringUtils::isNotBlank)
                                .map(MessageConstants::wrapInSystemReminder)
                                .map(MessageContent::ofText)
                                .forEach(preceding::add);
                            options = options.withPrecedingUserMessages(preceding);
                        }
                        if (!invocation.suppressCommandPermissions()) {
                            options = options.withCommandPermissions(
                                invocation.allowedTools(), invocation.model());
                        }
                        if (invocation.suppressInitialAttachments()) {
                            options = options.withoutInitialAttachments();
                        }
                        Object invocationContent = invocation.scalarTextContent()
                            && invocation.content().size() == 1
                            && invocation.content().getFirst() instanceof TextBlock(String text1)
                                ? text1
                                : MessageContent.ofBlocks(invocation.content());
                        exitCode = CliHeadlessOutput.processPrompt(
                            engine, invocationContent, options,
                            request.output(), request.outputFormat(), request.verbose(),
                            request.includePartialMessages(), request.sdkOutputState());
                    } finally {
                        if (gate != null) {
                            gate.removeRules(rule -> rule.source() == RuleSource.COMMAND);
                        }
                        PromptInvocationLifecycle.clear(promptHooks);
                    }
                } else {
                    List<SDKMessage> afterInit = List.of();
                    if (isCompactCommand(request.prompt())) {
                        afterInit = completedCompactSdkMessages(
                            engine, headless.localCommandSdkPrelude());
                    }
                    writeCommandResult(
                        engine, result, request.output(), request.outputFormat(), request.verbose(),
                        System.currentTimeMillis() - startedAt, request.sdkOutputState(),
                        headless.localCommandSdkPrelude(), afterInit);
                }
            } else {
                SubmitOptions options = request.structuredOutputSchema() != null
                    ? SubmitOptions.withSchema("user", request.structuredOutputSchema())
                    : SubmitOptions.DEFAULT;
                exitCode = CliHeadlessOutput.processPrompt(
                    engine, request.prompt(), options, request.output(), request.outputFormat(),
                    request.verbose(), request.includePartialMessages(), request.sdkOutputState());
            }
        } finally {
            scheduler.stop();
            if (transcript != null) {
                String sessionId = engine.conversation().getSessionId();
                if (queriedPromptCommand && !suppressQueriedCommandLastPrompt) {
                    transcript.recordQueriedCommandLastPrompt(sessionId, request.prompt());
                } else if (!queriedPromptCommand) {
                    transcript.recordLastPrompt(sessionId, request.prompt());
                }
                if (shouldAppendRestoredMode(
                        transcript, sessionId, restoredSessionMode)) {

                    // the resumed transcript did not already contain one. A
                    // later resumed process reuses that row; manual compact has
                    // its own unconditional metadata refresh before the boundary.
                    transcript.recordMode(sessionId, restoredSessionMode);
                }
                if (!transcript.awaitPendingWrites(sessionId, 5_000)) {
                    log.warn("Timed out waiting for transcript writes for session {}", sessionId);
                }
                if (transcript instanceof TranscriptRecorder recorder
                        && !recorder.releaseSessionState(sessionId, 5_000)) {
                    log.warn("Timed out releasing transcript state for session {}", sessionId);
                }
            }
        }
        if (engine.configuration().getConfig().memoryExtractor() != null) {
            engine.configuration().getConfig().memoryExtractor().drainPending(5000);
        }
        return exitCode;
    }

    static boolean shouldAppendRestoredMode(
            TranscriptSink transcript, String sessionId, String mode) {
        return transcript != null && mode != null && !StringUtils.isBlank(mode)
            && !transcript.hasPersistedMode(sessionId);
    }

    private static boolean isCompactCommand(String prompt) {
        if (prompt == null) return false;
        String stripped = prompt.stripLeading();
        return Strings.CS.equals("/compact", stripped)
            || Strings.CS.startsWith(stripped, "/compact ");
    }

    private static List<SDKMessage> completedCompactSdkMessages(
            QuerySession engine, List<SDKMessage> prelude) {
        List<Message> messages = engine.conversation().getMessages();
        int boundaryIndex = -1;
        SystemMessage boundary = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof SystemMessage system
                    && Strings.CS.equals("compact_boundary", system.subtype())) {
                boundaryIndex = i;
                boundary = system;
                break;
            }
        }
        if (boundary == null) {
            if (!prelude.isEmpty()) {
                prelude.add(new SDKMessage.Status(null, null, null));
            }
            return List.of();
        }

        prelude.add(new SDKMessage.Status(null, "success", null));
        List<SDKMessage> events = new ArrayList<>();
        events.add(new SDKMessage.CompactBoundary(
            messages.subList(0, boundaryIndex).stream().map(Message::uuid).toList(),
            engine.execution().getTotalUsage(), boundary));
        for (int i = boundaryIndex + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof UserMessage user && user.isCompactSummary()) {
                events.add(new SDKMessage.User(user, false, null, null, null, true, true));
            }
        }
        return List.copyOf(events);
    }

    static void recordPromptPreamble(TranscriptSink transcript,
                                     String sessionId,
                                     String prompt,
                                     Runnable recoveryTranscript) {
        if (transcript != null) {
            transcript.prepareSessionMaterialization(sessionId);
            transcript.recordQueueOperation(sessionId, "enqueue", prompt);
            transcript.recordPromptStart(sessionId, "sdk");
            transcript.recordQueueOperation(sessionId, "dequeue", null);
        }
        if (recoveryTranscript != null) {
            recoveryTranscript.run();
        }
    }

    private static String restoredSessionMode(Request request) {
        boolean coordinatorModeAvailable = FeatureGate.isEnabled(Flag.COORDINATOR_MODE);
        if (!shouldRecordRestoredSessionMode(
                request.startupSessionRestored(), coordinatorModeAvailable)) {
            return null;
        }
        return isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_COORDINATOR_MODE"))
            ? "coordinator" : "normal";
    }

    static boolean shouldRecordRestoredSessionMode(boolean restored,
                                                   boolean coordinatorModeAvailable) {
        return restored && coordinatorModeAvailable;
    }

    /** Writes a command result in the format selected by the immutable launch request. */
    static void writeCommandResult(
            QuerySession engine,
            CommandResult result,
            CliOutput output,
            String outputFormat,
            boolean verbose,
            long durationMs,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState) {
        writeCommandResult(engine, result, output, outputFormat, verbose, durationMs,
            sdkOutputState, List.of(), List.of());
    }

    static void writeCommandResult(
            QuerySession engine,
            CommandResult result,
            CliOutput output,
            String outputFormat,
            boolean verbose,
            long durationMs,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState,
            List<SDKMessage> beforeInit,
            List<SDKMessage> afterInit) {
        String value = result.headlessOutput() == null ? "" : result.headlessOutput();
        if (Strings.CS.equals("json", outputFormat)
                || Strings.CS.equals("stream-json", outputFormat)) {
            StdoutMessageWriter.writeCommandResult(
                engine, result, output, outputFormat, verbose, durationMs,
                sdkOutputState.get().metadata(), beforeInit, afterInit);
            return;
        }
        if (!result.silent() && !value.isEmpty()) {
            output.println(value);
            output.flush();
        }
    }

    static void writeCommandResult(
            QuerySession engine,
            CommandResult result,
            PrintWriter output,
            String outputFormat,
            boolean verbose,
            long durationMs,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState) {
        writeCommandResult(engine, result, CliOutput.borrowed(output), outputFormat,
            verbose, durationMs, sdkOutputState);
    }
}

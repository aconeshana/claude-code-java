package com.claudecode.cli;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.ApiConfig;
import com.claudecode.api.LlmClient;
import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.ConfigLiveSetters;
import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.insights.InsightsPort;
import com.claudecode.commands.impl.config.AddDirCommand;
import com.claudecode.commands.impl.config.ModelCommand;
import com.claudecode.commands.impl.integration.McpCommand;
import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.core.effort.EffortHelpers;
import com.claudecode.core.engine.CostCalculator;
import com.claudecode.runtime.interaction.InteractionFeatures;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.message.Message;
import com.claudecode.core.message.UserMessage;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.pokemon.PokemonProfile;
import com.claudecode.core.model.CustomModelCatalog;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.session.SessionLifecycle;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.services.agent.AgentSummaryService;
import com.claudecode.services.agent.AwaySummaryService;
import com.claudecode.services.cache.PromptCacheBreakDetection;
import com.claudecode.services.compact.CompactService;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.services.hooks.HooksSettings;
import com.claudecode.services.insights.InsightsPipeline;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.services.model.ModelValidator;
import com.claudecode.services.model.SideQuery;
import com.claudecode.services.outputstyle.OutputStyleService;
import com.claudecode.services.permissions.PermissionExplainerService;
import com.claudecode.services.plugins.marketplace.PluginMarketplaceAdapter;
import com.claudecode.services.tips.ExternalTips;
import com.claudecode.services.titles.SessionTitleGenerator;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.session.SessionManager;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.loop.LoopPromptResolver;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.ui.lanterna.repl.LanternaProgressSink;
import com.claudecode.ui.lanterna.repl.LanternaReplScreen;
import com.claudecode.ui.lanterna.dialog.TuiSudoPasswordPresenter;
import com.claudecode.ui.lanterna.repl.ReplCommandUiBridge;
import com.claudecode.ui.lanterna.repl.ReplStartupReadiness;
import com.claudecode.ui.lanterna.repl.ReplWiring;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the interactive Lanterna session after the common CLI session is assembled.
 *
 * <ul>
 *   <li>creates the interactive terminal lifecycle
 *       after common setup completes.</li>
 *   <li>builds the command context and screen-bound
 *       callbacks, then binds progress and LSP recommendations after screen creation.</li>
 *   <li>flushes outstanding
 *       asynchronous hooks at interactive session shutdown.</li>
 * </ul>
 */
final class CliInteractiveSessionRunner {

    private static final Logger log = LoggerFactory.getLogger(CliInteractiveSessionRunner.class);

    private CliInteractiveSessionRunner() {}

    /** Explicit runtime dependencies; this runner never reads Picocli fields. */
    record Request(
            QuerySession engine,
            StreamingClient client,
            String resolvedModel,
            boolean teamMemoryEnabled,
            PermissionGate permissionGate,
            McpRuntime mcpRuntime,
            CliPluginRuntimeView pluginRuntime,
            CliSessionLifecycleBootstrap.PromptInventory promptInventory,
            ToolRegistry toolRegistry,
            CompactService compactService,
            SideQuery sideQuery,
            QuerySessionFactory querySessionFactory,
            LlmClient llmClient,
            QuerySessionSpec config,
            TranscriptRecorder transcriptRecorder,
            HookEngine hookEngine,
            SettingsReloadOrchestrator settingsReload,
            SkillToolProvider skillToolProvider,
            TaskBoardPort taskBoard,
            OutputStyleService outputStyleService,
            ApiConfig.ApiProvider resolvedApiProvider,
            String resolvedBaseUrl,
            String apiKey,
            boolean showBuiltInModelFamilies,
            boolean dangerouslySkipPermissions,
            String initialPrompt,
            String initialSessionName,
            String setupTrigger,
            boolean printMode,
            boolean noInteractive,
            boolean restoredSession,
            boolean verbose,
            Supplier<ContextData> contextDataCollector,
            AgentSummaryService agentSummaryService,
            LanternaProgressSink progressSink,
            CliLspIntegration lspIntegration,
            CustomModelCatalog customModelCatalog,
            CliOutput shellOutput,
            CliOutput errorOutput,
            /** Bare {@code -r}: open the session picker once startup gates resolve. */
            boolean startupResumePicker,
            /**
             * Pre-fills that picker's search box with a {@code -r <value>} that matched no single
             * session title, so the unresolved value stays visible instead of being dropped.
             */
            String startupResumeSearchTerm) {}

    private record OptionalInteractiveSettings(
            long idlePromptThresholdMs, boolean awaySummaryEnabled) {
    }

    static int run(Request input) {
        QuerySession engine = input.engine();
        StreamingClient client = input.client();
        String resolvedModel = input.resolvedModel();
        boolean teamMemoryEnabled = input.teamMemoryEnabled();
        PermissionGate permissionGate = input.permissionGate();
        McpRuntime mcpRuntime = input.mcpRuntime();
        CliPluginRuntimeView pluginRuntime = input.pluginRuntime();
        CliSessionLifecycleBootstrap.PromptInventory promptInventory = input.promptInventory();
        ToolRegistry toolRegistry = input.toolRegistry();
        CompactService compactService = input.compactService();
        SideQuery sideQuery = input.sideQuery();
        LlmClient llmClient = input.llmClient();
        QuerySessionSpec config = input.config();
        TranscriptRecorder transcriptRecorder = input.transcriptRecorder();
        HookEngine hookEngine = input.hookEngine();
        SettingsReloadOrchestrator settingsReload = input.settingsReload();
        var skillToolProvider = input.skillToolProvider();
        TaskBoardPort taskBoard = input.taskBoard();
        OutputStyleService outputStyleService = input.outputStyleService();
        ApiConfig.ApiProvider resolvedApiProvider = input.resolvedApiProvider();
        String resolvedBaseUrl = input.resolvedBaseUrl();
        String apiKey = input.apiKey();
        boolean showBuiltInModelFamilies = input.showBuiltInModelFamilies();
        boolean dangerouslySkipPermissions = input.dangerouslySkipPermissions();
        String initialPrompt = input.initialPrompt();
        String initialSessionName = input.initialSessionName();
        boolean printMode = input.printMode();
        boolean noInteractive = input.noInteractive();
        boolean restoredSession = input.restoredSession();
        boolean verbose = input.verbose();
        Supplier<ContextData> contextDataCollector = input.contextDataCollector();
        AgentSummaryService agentSummaryService = input.agentSummaryService();
        LanternaProgressSink progressSink = input.progressSink();
        CliLspIntegration lspIntegration = input.lspIntegration();
        CustomModelCatalog customModelCatalog = input.customModelCatalog();
        CliOutput shellOutput = input.shellOutput();

        FileChangedHookWatcher fileWatcher = new FileChangedHookWatcher(hookEngine);
        CompletableFuture<OptionalInteractiveSettings> optionalSettings =
            CliStartupTasks.supply("interactive-optional-settings",
                CliInteractiveSessionRunner::loadOptionalInteractiveSettings);
        CliHookEffectSink hookEffects = new CliHookEffectSink(
            engine, transcriptRecorder, skillToolProvider.getSkillLoader(), fileWatcher,
            shellOutput, input.errorOutput(), true, false);

            // Enter REPL loop — use Lanterna full-screen UI
            try {
                CliInteractiveSessionLauncher.Preparation interactivePreparation =
                    CliInteractiveSessionLauncher.prepare(
                        engine, input.querySessionFactory(), client, resolvedModel,
                        () -> teamMemoryEnabled, permissionGate,
                        mcpRuntime, toolRegistry, input.errorOutput());
                CommandRegistry cmdRegistry = interactivePreparation.commandRegistry();
                hookEffects.bindCommandRegistry(cmdRegistry);
                CliInteractiveStartupCoordinator.Result startup =
                    CliInteractiveStartupCoordinator.start(
                        engine, hookEngine, fileWatcher, hookEffects,
                        input.setupTrigger(), input.errorOutput(), cmdRegistry,
                        Path.of(System.getProperty("user.dir")), pluginRuntime,
                        promptInventory);
                Function<String, String> sideQuestionRunner =
                    interactivePreparation.sideQuestionRunner();
                CliInteractiveReplAssembler.Bindings replBindings =
                    CliInteractiveReplAssembler.create(engine, compactService, sideQuestionRunner);
                AtomicReference<LanternaReplScreen> screenRef = replBindings.screenRef();
                CliSessionHostRuntime sessionHostRuntime = CliSessionHostRuntime.prepare(
                    engine, screenRef, System.getProperty("user.dir"));
                ReplCommandUiBridge commandUi = replBindings.commandUi();
                Consumer<String> btwLauncher = replBindings.btwLauncher();
                Consumer<String> colorSetter = replBindings.colorSetter();
                Consumer<PokemonProfile> pokemonSetter =
                    replBindings.pokemonSetter();
                Consumer<String> effortSetter = replBindings.effortSetter();
                Supplier<String> effortGetter = replBindings.effortGetter();
                Runnable effortLauncher = replBindings.effortLauncher();
                Consumer<String> exportLauncher = replBindings.exportLauncher();
                Consumer<String> themeLauncher = replBindings.themeLauncher();
                BiFunction<CommandContext, String, CommandResult> themeApplyFromDialog =
                    replBindings.themeApplyFromDialog();
                Runnable configLauncher = replBindings.configLauncher();
                Runnable statusLauncher = replBindings.statusLauncher();
                Runnable usageLauncher = replBindings.usageLauncher();
                Runnable permissionsLauncher = replBindings.permissionsLauncher();
                Runnable agentsLauncher = replBindings.agentsLauncher();
                Consumer<String> addDirLauncher = replBindings.addDirLauncher();
                CommandContext.AddDirApply addDirApply = replBindings.addDirApply();
                ConfigLiveSetters configLiveSetters = replBindings.configLiveSetters();
                Runnable rewindLauncher = replBindings.rewindLauncher();
                // Reuse the SideQuery installed before the headless exits for
                // rename/session-search/permission-explainer services.


                // llmClient isn't available (offline, cred-less tests).
                Function<List<Message>, String> titleGenerator = null;
                if (sideQuery != null) {
                    SessionTitleGenerator gen = new SessionTitleGenerator(sideQuery);
                    titleGenerator = gen::generate;
                }
                // First-real-prompt terminal title helper — separate from /rename's

                // fire-and-forget; metadata/config reads and the whole network
                // handshake therefore stay off Lanterna's submit thread.
                Function<String, CompletableFuture<String>> sessionTitleGenerator = null;
                if (llmClient != null
                        && !isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_TERMINAL_TITLE"))) {
                    TerminalSessionTitleGenerator gen =
                        new TerminalSessionTitleGenerator(llmClient, resolvedModel);
                    sessionTitleGenerator = prompt -> {
                        CompletableFuture<String> result = new CompletableFuture<>();
                        Thread.ofVirtual().name("session-title-submit").start(() -> {
                            try {
                                String effort = EffortHelpers.resolveAppliedEffort(
                                    resolvedModel, engine.configuration().getConfig().effortValue());
                                String sessionId = engine.conversation().getSessionId();
                                gen.generateAsync(prompt, sessionId,
                                        LlmClientAdapter.requestMetadata(sessionId), effort)
                                    .whenComplete((title, failure) -> {
                                        if (failure != null) result.complete(null);
                                        else result.complete(title);
                                    });
                            } catch (RuntimeException _) {
                                result.complete(null);
                            }
                        });
                        return result;
                    };
                }
// Shared post-compact durability hook: after any compact (manual /compact via
// CommandContext, or auto-compact via QuerySession.postCompactCallback) re-append
// session metadata to JSONL EOF so customTitle/agentName/agentColor/tag stay within
// the 64KB tail-scan window used by readLiteMetadata.
                Runnable reAppendMetadata = () -> {
                    String sid = engine.conversation().getSessionId();
                    if (StringUtils.isBlank(sid)) return;
                    try {

                        new SessionManager(
                            System.getProperty("user.dir"))
                            .reAppendSessionMetadata(sid);
                    } catch (Exception _) { /* best-effort */ }
                };
                Runnable postCompact = () -> {
                    LoopPromptResolver.global().resetDeliveredState();
                    transcriptRecorder.clearCompactionCaches(engine.conversation().getSessionId(), 2_000);
                    reAppendMetadata.run();
                };
                Runnable manualPostCompact = () -> {
                    LoopPromptResolver.global().resetDeliveredState();
                    var transcript = engine.execution().getTranscriptSink();
                    if (transcript != null) {
                        String commandMessageId = engine.conversation().getMessages().stream()
                            .filter(UserMessage.class::isInstance)
                            .map(UserMessage.class::cast)
                            .filter(message -> message.message() != null
                                && message.message().isText()
                                && Strings.CS.startsWith(message.message().text(), "<command-name>/compact"))
                            .map(UserMessage::uuid)
                            .reduce((_, second) -> second)
                            .orElse(null);
                        transcript.prepareManualCompactMetadata(
                            engine.conversation().getSessionId(), commandMessageId);
                        transcriptRecorder.clearCompactionCaches(
                            engine.conversation().getSessionId(), 2_000);
                    }
                };
                engine.execution().setPostCompactCallback(postCompact);
                engine.execution().setOnCompactProgress(event -> {
                    LanternaReplScreen s = screenRef.get();
                    if (s != null) s.handleCompactProgress(event);
                });

                // /model: register a ModelCommand with a live current-model
                // supplier (dynamic "(currently X)" description) — overwrites the
                // no-arg one from CommandFactory. Its applyFromDialog binds the
                // picker's confirm path; ModelValidator gives /model <name> a
                // live-API id check (null llmClient → skip, e.g. streaming override).
                ModelCommand modelCmd = new ModelCommand(() -> engine.configuration().getConfig().model());
                cmdRegistry.register(modelCmd);
                final ModelValidator modelValidator =
                    llmClient != null ? new ModelValidator(llmClient) : null;

                final LlmClient insightsClient = llmClient;
                final QuerySessionSpec insightsConfig = config;
                String interactiveCwd = System.getProperty("user.dir");
                CliInteractiveRuntimeAssembler interactiveRuntime =
                    new CliInteractiveRuntimeAssembler(cmdRegistry::isBuiltInCommandName);
                DoctorPort doctorPort = CliRuntimeAdapters.newDoctorPort(
                    permissionGate, toolRegistry, interactiveCwd, pluginRuntime);
                CliSettingsManagementAdapter settingsManagement =
                    new CliSettingsManagementAdapter();
                var pluginMarketplace = PluginMarketplaceAdapter.standard(interactiveCwd, () ->
                    pluginRuntime != null
                        ? pluginRuntime.currentSnapshot().errors() : List.of(), () ->
                    pluginRuntime != null
                        ? pluginRuntime.currentSnapshot().mcpServers() : List.of());
                var mcpManagement = new CliMcpManagementAdapter(
                    Path.of(interactiveCwd), mcpRuntime::clientRuntime,
                    toolRegistry, cmdRegistry, pluginMarketplace);
                Supplier<InsightsPort>
                    insightsPipelineSupplier = () -> insightsClient == null ? null
                        : CliHeadlessSessionRunner.insightsAdapter(
                            new InsightsPipeline(insightsClient, () -> {
                            String env = SubprocessEnvironment.get("ANTHROPIC_DEFAULT_OPUS_MODEL");
                            return StringUtils.isNotBlank(env) ? env
                                : ModelNames.parseUserSpecifiedModel(insightsConfig.model());
                        }, cmdRegistry::isBuiltInCommandName));


                CommandContext cmdContext =
                    CommandContext.builder(
                        resolvedModel,
                        () -> engine.conversation().getMessages(),
                        commandUi::clearConversation,
                        m -> {
                            engine.configuration().setModel(m);
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.applyModelSelection(m);
                        },
                        engine.execution()::getTotalUsage,
                        // Real cost — prices the running usage by the LIVE model
                        // (re-resolved each call so a /model switch reprices).
                        // Previously stubbed to 0.0, so /cost and /status showed
                        // $0.0000 despite CostCalculator having full pricing.
                        u -> CostCalculator.forModel(ModelNames
                            .parseUserSpecifiedModel(engine.configuration().getConfig().model())).calculateCost(u),
                        System.getProperty("user.dir"),
                        false)
                        .modelSupplier(engine.configuration().getConfig()::model)
                        .modelAllowed(ModelAllowlist::isAllowed)
                        .loadMessages(engine.conversation()::loadMessages)
                        .loadCompactedMessages(engine.conversation()::loadCompactedMessages)
                        .currentSessionId(() -> engine.conversation().getSessionId())
                        .permissionCommands(new CliPermissionCommandAdapter(permissionGate))
                        .sessionCommands(new CliSessionCommandAdapter(System.getProperty("user.dir")))
                        .toolingCommands(interactiveRuntime.toolingCommands())
                        .promptShellExecutor(CliHeadlessSessionRunner.newPromptShellExecutor(
                            engine, toolRegistry, permissionGate))
                        .sideQuestionRunner(sideQuestionRunner)
                        .compactService(() -> compactService)
                        .pluginRuntime(pluginRuntime)
                        .doctor(doctorPort)
                        .dream(CliRuntimeAdapters.newDreamPort())

                        .insightsPipeline(insightsPipelineSupplier)
                        .settingsManagement(settingsManagement)
                        .mcpManagement(mcpManagement)
// disableNonInteractive: hidden in print / --no-interactive mode.
                        .nonInteractive(printMode || noInteractive)
                        .btwDialogLauncher(btwLauncher)
                        .sessionColorSetter(colorSetter)
                        .pokemonSetter(pokemonSetter)
                        .pokemonStatusPresenter(pokemon -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.showWelcomePokemon(pokemon);
                        })
                        .pokemonHatchLauncher(request -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openPokemonHatchDialog(request);
                        })
                        .effortValueSetter(effortSetter)
                        .effortValueSupplier(effortGetter)
                        .effortDialogLauncher(effortLauncher)
                        .exportDialogLauncher(exportLauncher)
                        .hooksDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openHooksDialog();
                        })
                        .sandboxDialogLauncher(commandUi::openSandbox)
                        .titleGenerator(titleGenerator)
                        .postCompactCallback(manualPostCompact)
                        .postCompactTranscriptCallback(() -> transcriptRecorder.recordLastPrompt(
                            engine.conversation().getSessionId(), "/compact"))
                        .transcriptRecorder(m -> transcriptRecorder.record(engine.conversation().getSessionId(), m))
                        .openMessageSelector(rewindLauncher)
                        .hookDispatcher(hookEngine)
                        .goalGate(CliRuntimeAdapters.newGoalGate(
                            interactiveCwd, printMode || noInteractive))
                        .messageAppender(engine.conversation()::appendTranscriptMessage)
                        .goalDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openGoalDialog();
                        })
                        .sessionIdSwitcher(commandUi::switchActiveSession)
                        .resetSessionCost(commandUi::resetSessionCost)
                        .onCompactProgress(event -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.handleCompactProgress(event);
                        })
                        .verboseSupplier(() -> verbose)
                        .memoryDialogLauncher(commandUi::openMemoryDialog)
                        .openEditor(commandUi::openFileInEditor)
                        .doctorDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openDoctorDialog();
                        })
                        .apiBaseUrlSupplier(() -> resolvedBaseUrl)
                        .statusRuntimePropertiesSupplier(() ->
                            CliRuntimeAdapters.statusRuntimeProperties(
                                resolvedApiProvider, resolvedBaseUrl, apiKey))
                        .configLiveSetters(configLiveSetters)
                        .themeDialogLauncher(themeLauncher)
                        .configDialogLauncher(configLauncher)
                        .themeApplyFromDialog(themeApplyFromDialog)
                        .statusDialogLauncher(statusLauncher)
                        .usageDialogLauncher(usageLauncher)
                        // /model picker wiring: launcher opens the Lanterna picker,
                        // applyFromDialog is the confirm path (model + optional effort),
                        // modelValidator is the live-API id check for /model <name>.
                        .modelDialogLauncher(commandUi::openModelPicker)
                        .modelApplyFromDialog(modelCmd::applyFromDialog)
                        .modelValidator(name -> {
                            if (!ModelAllowlist.isAllowed(name)) {
                                return ModelAllowlist.rejectionMessage(name);
                            }
                            if (modelValidator == null) return null;
                            var r = modelValidator.validate(name);
                            return r.valid() ? null : r.error();
                        })
                        .addDirDialogLauncher(addDirLauncher)
                        .addDirValidator(path -> {
                            var snapshot = new CliPermissionCommandAdapter(permissionGate).snapshot();
                            return AddDirCommand.validate(path, System.getProperty("user.dir"),
                                snapshot.workingDirectories());
                        })
                        .addDirApply(addDirApply)
                        .mcpStatusSupplier(() -> mcpRuntime.clientRuntime().connectionSummary())
                        .permissionsDialogLauncher(permissionsLauncher)
                        .agentsDialogLauncher(agentsLauncher)
                        .resumeLauncher(request -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.resumeSession(request);
                        })
                        .contextDataCollector(contextDataCollector)
                        .contextVisualizerLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.showContextVisualization();
                        })
                        .copyPickerLauncher((fullText, codeBlocks, skipPicker) -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openCopyPicker(fullText, codeBlocks, skipPicker);
                        })
                        .copyApplyFromDialog((text, filename, saveAlways, writeOnly) ->
                            CopyCommand.applyCopy(text, filename, saveAlways, writeOnly,
                                settingsManagement.preferences()))
                        .diffDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openDiffDialog();
                        })
                        .helpDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openHelpPanel();
                        })
                        .pluginDialogLauncher(args -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openPluginPanel(args);
                        })
                        .skillsDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openSkillsDialog();
                        })
// /stats opens the interactive stats panel.
                        .statsDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openStatsDialog();
                        })
                        .tagRemovalLauncher(request -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openTagRemovalDialog(request);
                        })
// /tasks (alias /bashes) opens the interactive background-tasks panel.
                        .tasksDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openTasksDialog();
                        })
                        .workflowsDialogLauncher(() -> {
                            LanternaReplScreen s = screenRef.get();
                            if (s != null) s.openWorkflowsDialog();
                        })
                        .build();
                var permissionExplainer = sideQuery != null
                    ? new PermissionExplainerService(sideQuery, resolvedModel) : null;
                CliRuntimeAdapters.configureUiSettingsBackend();
                SessionLifecycle sessionLifecycle = CliSessionRestoreCoordinator.newSessionLifecycle(engine);
                HookConfigurationPort hookConfiguration =
                    CliRuntimeAdapters.newHookConfigurationPort(settingsReload, hookEngine);
                var memoryCatalog = CliRuntimeAdapters.newMemoryCatalog(interactiveCwd);
                WorktreeService.setMemoryFileCacheClearer(memoryCatalog::clearCache);
                mcpRuntime.clientRuntime().setMessageQueue(engine.conversation().getMessageQueue());
                Consumer<String> skillHookRegistrar = rawHooks -> {
                        HooksSettings parsed = HooksSettings.fromYaml(rawHooks);
                        if (parsed != HooksSettings.EMPTY) {
                            hookEngine.addExtraHooks(parsed);
                        }
                    };
                var applicationPorts = interactiveRuntime.application(
                    commandUi, hookConfiguration, mcpManagement,
                    CliRuntimeAdapters.newCompactWarningProvider(compactService),
                    sessionLifecycle,
                    PromptCacheBreakDetection::resetPromptCacheBreakDetection,
                    memoryCatalog,
                    outputStyleService,
                    doctorPort,
                    pluginMarketplace,
                    CliRuntimeAdapters.newStatusLinePort(interactiveCwd),
                    CliRuntimeAdapters.newStartupTrustPort(),
                    CliRuntimeAdapters.newShutdownPort(engine, shellOutput),
                    CliRuntimeAdapters.newTurnAwakeGuard(), taskBoard);
                var featureRuntime = interactiveRuntime.features(
                    permissionGate, toolRegistry,
                    () -> startup.skills().toCompletableFuture().getNow(List.of()),
                    skillHookRegistrar, permissionExplainer);
                var launchState = interactiveRuntime.launch(
                    UserKeybindingsStore.create(), dangerouslySkipPermissions, initialPrompt,
                    initialSessionName, restoredSession, sessionTitleGenerator,
                    showBuiltInModelFamilies, customModelCatalog,
                    ExternalTips::getNextTip,
                    sessionHostRuntime.registry(), sessionHostRuntime.interactions(),
                    sessionHostRuntime.collaboration(), sessionHostRuntime);
                ReplWiring wiring = interactiveRuntime.assemble(
                    applicationPorts, featureRuntime, launchState,
                    new ReplStartupReadiness(startup.inputSemanticReady(),
                        promptInventory.timeline()::mark));
                LanternaReplScreen lanternaRepl =
                    new LanternaReplScreen(engine, cmdRegistry, cmdContext, wiring);
                screenRef.set(lanternaRepl);
                hookEffects.bindUi(lanternaRepl::postSystemMessage,
                    lanternaRepl::applyHookSessionTitle);
                CompletableFuture.allOf(optionalSettings, lanternaRepl.sessionHostReady())
                    .thenRun(() -> Thread.ofVirtual()
                        .name("interactive-optional-services")
                        .start(() -> installOptionalInteractiveServices(
                            optionalSettings.getNow(new OptionalInteractiveSettings(60_000, false)),
                            lanternaRepl, hookEngine, llmClient, engine)));
                toolRegistry.get("Bash")
                    .filter(BashTool.class::isInstance)
                    .map(BashTool.class::cast)
                    .ifPresent(bash -> bash.setSudoPasswordInteraction(
                        sessionHostRuntime.interactions()));
                sessionHostRuntime.interactions().register(
                    InteractionFeatures.SUDO_PASSWORD,
                    new TuiSudoPasswordPresenter(
                        sessionHostRuntime.interactions(), lanternaRepl::promptSudoPassword));
                if (input.startupResumePicker()) {
                    lanternaRepl.requestStartupResumePicker(input.startupResumeSearchTerm());
                }
                lanternaRepl.setModel(resolvedModel);
                lanternaRepl.setVerbose(verbose);
                lanternaRepl.setContextDataCollector(contextDataCollector);
                // Bind the progress sink to the REPL (engine config was built earlier,
                // so the sink is late-bound via setScreen).
                progressSink.setScreen(lanternaRepl);
                // Install the REPL-bound LSP recommendation trigger only after
                // the screen exists; its lifecycle and response persistence stay
                // encapsulated in CliLspIntegration.
                lspIntegration.attachRecommendationTrigger(engine, lanternaRepl);
                lanternaRepl.sessionHostReady().thenRunAsync(() -> {
                    try {
                        sessionHostRuntime.start();
                    } catch (RuntimeException failure) {
                        log.warn("Session Host IM endpoint could not start", failure);
                    }
                });
// Register /mcp now that the client manager exists (see ReplWiring).
                McpCommand mcpCmd = new McpCommand(mcpManagement);
                mcpCmd.setDialogLauncher(lanternaRepl::openMcpDialog);
                cmdRegistry.registerBuiltIn(mcpCmd);
                lanternaRepl.setToolNames(toolRegistry.getAll().stream()
                    .map(Tool::name)
                    .collect(Collectors.toList()));
                try {
                    lanternaRepl.run();
                    return 0;
                } finally {
                    sessionHostRuntime.close();
                }
            } catch (Exception e) {
                log.error("Lanterna UI failed", e);
                return 1;
            } finally {
                finalizeInteractiveSession(agentSummaryService, hookEngine);
                hookEffects.close();
            }

    }

    private static OptionalInteractiveSettings loadOptionalInteractiveSettings() {
        long idleThreshold = 60_000;
        boolean awaySummary = false;
        try {
            idleThreshold = RuntimeSettings.loadMessageIdleNotifThresholdMs();
        } catch (RuntimeException failure) {
            log.debug("Idle prompt setting degraded to its default: {}", failure.toString());
        }
        try {
            awaySummary = RuntimeSettings.loadAwaySummaryEnabled();
        } catch (RuntimeException failure) {
            log.debug("Away summary setting degraded to its default: {}", failure.toString());
        }
        return new OptionalInteractiveSettings(idleThreshold, awaySummary);
    }

    private static void installOptionalInteractiveServices(
            OptionalInteractiveSettings settings,
            LanternaReplScreen screen,
            HookEngine hooks,
            LlmClient llmClient,
            QuerySession engine) {
        screen.configureIdlePromptNotification(
            settings.idlePromptThresholdMs(),
            () -> hooks.dispatchNotification(
                "Claude is waiting for your input", null, "idle_prompt"));
        if (!settings.awaySummaryEnabled()) return;
        AwaySummaryService awaySummary = new AwaySummaryService(llmClient);
        awaySummary.startIdleWatcher(
            () -> engine.conversation().getMessages(), screen::postAwaySummary);
    }

    static boolean showBuiltInModelFamilies(
            ApiConfig.ApiProvider provider, String resolvedBaseUrl,
            ConfigLoader.Credentials credentials) {
        return new ModelAvailability(provider, resolvedBaseUrl, credentials, null)
            .showBuiltInModelFamilies();
    }

    /** Runs the exit cleanup for both normal and exceptional REPL termination. */
    static void finalizeInteractiveSession(
            AgentSummaryService agentSummaryService, HookEngine hookEngine) {

        // and the remaining cleanup still runs.
        try {
            // The JVM shutdown hook is only a fallback for headless paths; an
            // interactive return must release the shared summary scheduler now.
            if (agentSummaryService != null) agentSummaryService.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close agent summary service", e);
        }
        if (hookEngine == null) return;
        try {

            hookEngine.setForceSyncExecution(true);
            hookEngine.finalizePendingAsyncHooks();
        } catch (RuntimeException e) {
            log.warn("Failed to finalize pending async hooks", e);
        }
    }
}

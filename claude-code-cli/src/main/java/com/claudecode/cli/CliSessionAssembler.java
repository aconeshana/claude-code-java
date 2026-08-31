package com.claudecode.cli;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.LlmClient;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.feature.FeatureGate;
import com.claudecode.core.feature.FeatureGate.Flag;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.telemetry.Telemetry;
import com.claudecode.services.titles.TerminalSessionTitleGenerator;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.hooks.FileChangedHookWatcher;
import com.claudecode.tools.agent.SubAgentModelPolicy;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.McpConfigWarning;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.ui.lanterna.repl.LanternaProgressSink;
import com.claudecode.runtime.query.DefaultQuerySessionFactory;
import com.claudecode.runtime.query.QuerySessionFactory;

import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Orchestrates the fixed CLI startup sequence without owning Picocli state.
 *
 * <ul>
 *   <li>runs settings/cwd/hooks/worktree, client and
 *       tool setup, engine setup, lifecycle setup, restore, then mode routing
 *       in that order.</li>
 *   <li>composes workspace, registry, engine, plugin,
 *       LSP, settings-reload, and transcript lifecycle collaborators.</li>
 *   <li>receive a
 *       complete session only after restore has produced its immutable result.</li>
 * </ul>
 */
final class CliSessionAssembler {

    private CliSessionAssembler() {}

    static int assembleAndRun(CliLaunchRequest request, McpRuntime mcpRuntime) {
        CliOutput output = request.testOverrides().output();
        if (output == null) output = CliOutput.systemOut();
        CliOutput errorOutput = request.testOverrides().errorOutput();
        if (errorOutput == null) errorOutput = CliOutput.systemErr();

        final CliWorkspaceBootstrap.SettingSourceSelection settingSources;
        try {
            settingSources = CliWorkspaceBootstrap.parseSettingSources(request.workspace().settingSourcesRaw());
        } catch (IllegalArgumentException e) {
            errorOutput.println("Error processing --setting-sources: " + e.getMessage());
            return 1;
        }

        String previousNonInteractive = System.getProperty("claude.code.nonInteractive");
        String previousEntrypoint = System.getProperty("claude.code.entrypoint");
        try (CliResourceScope sessionResources = new CliResourceScope()) {
            Telemetry.initialize(!request.mode().formattedOutput());
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Telemetry.flush();
                Telemetry.shutdown();
            }));

            AtomicReference<SdkControlBroker> sdkBrokerRef = new AtomicReference<>();
            AtomicReference<CliPluginRuntimeView> pluginRuntimeRef = new AtomicReference<>();
            LanternaProgressSink progressSink = new LanternaProgressSink();
            QuerySessionFactory querySessionFactory = new DefaultQuerySessionFactory();
            CliWorkspaceBootstrap.Workspace workspace =
                CliWorkspaceBootstrap.bootstrap(request, settingSources, errorOutput);
            CliToolchainAssembler.Toolchain toolchain = CliToolchainAssembler.assemble(
                workspace, mcpRuntime, sdkBrokerRef, pluginRuntimeRef, querySessionFactory,
                sessionResources, errorOutput);
            CliEngineAssembler.EngineRuntime engine = CliEngineAssembler.assemble(
                workspace, toolchain, mcpRuntime, pluginRuntimeRef, querySessionFactory,
                progressSink, errorOutput);
            try (CliSessionLifecycleBootstrap.Lifecycle lifecycle =
                    CliSessionLifecycleBootstrap.bootstrap(
                        workspace, toolchain, engine, mcpRuntime, pluginRuntimeRef,
                        sessionResources)) {
                Runnable initializeInteractiveMcp;
                if (interactiveStartup(request)) {
                    CompletableFuture<McpConfig> interactiveMcpConfig =
                        prepareInteractiveMcpConfig(workspace, errorOutput);
                    initializeInteractiveMcp = () -> interactiveMcpConfig.thenAccept(config ->
                        CliToolchainAssembler.startMcpConnections(
                            workspace, mcpRuntime, toolchain.toolRegistry(), config));
                } else {
                    initializeInteractiveMcp = () -> { };
                }
                if (request.session().initOnly()) {
                    CliToolchainAssembler.startMcpConnections(
                        workspace, mcpRuntime, toolchain.toolRegistry());
                    return runInitOnly(workspace, toolchain, engine, lifecycle, output, errorOutput);
                }
                lifecycle.transcriptRecorder().cacheSessionTitle(request.session().name());
                String selectedAgent = request.workspace().agent();
                if (StringUtils.isNotBlank(selectedAgent)) {
                    lifecycle.transcriptRecorder().cacheAgentSetting(selectedAgent);
                }
                CliSessionRestoreCoordinator.Restoration restoration = CliSessionRestoreCoordinator.restore(
                    new CliSessionRestoreCoordinator.Request(
                        engine.engine(), workspace.cwd(), request.session().resumeSession(),
                        request.session().continueLastSession(), request.session().forkSession(),
                        request.session().resumeSessionAt(), request.output().printMode()
                            || request.session().rewindFiles() != null,
                        interactiveStartup(request),
                        workspace.launch().initialPrompt(), request.output().inputFormat(),
                        lifecycle.transcriptRecorder(), output, errorOutput));
                lifecycle.transcriptRecorder().activateSessionMetadata(
                    engine.engine().conversation().getSessionId(), restoration.restored());
                if (request.session().rewindFiles() != null) {
                    return CliRewindFilesOperation.run(
                        engine.engine(), request.session().rewindFiles(), output, errorOutput);
                }
                CliSessionRuntime runtime = new CliSessionRuntime(
                    request, workspace, toolchain, engine, lifecycle, restoration, output,
                    errorOutput, sdkBrokerRef, progressSink);
                if (interactiveStartup(request)
                        && StringUtils.isNotBlank(workspace.launch().initialPrompt())) {

                    // MCP discovery is still starting. Use the exact request-ready
                    // boundary instead of a timing sleep: the first request snapshots
                    // the built-ins, then MCP tools join the live registry for later
                    // turns. A prompt typed after the idle REPL opens takes the normal
                    // eager path below and can wait for MCP readiness explicitly.
                    engine.engine().execution().setBeforeModelRequestCallback(() -> {
                        engine.engine().execution().setBeforeModelRequestCallback(null);
                        initializeInteractiveMcp.run();
                    });
                } else if (interactiveStartup(request)) {
                    initializeInteractiveMcp.run();
                } else {
                    CliToolchainAssembler.startMcpConnections(
                        workspace, mcpRuntime, toolchain.toolRegistry());
                }
                return route(runtime, mcpRuntime);
            }
        } catch (CliLaunchAbort abort) {
            return abort.exitCode();
        } finally {
            restoreSystemProperty("claude.code.nonInteractive", previousNonInteractive);
            restoreSystemProperty("claude.code.entrypoint", previousEntrypoint);
        }
    }

    private static void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) System.clearProperty(name);
        else System.setProperty(name, previousValue);
    }

    private static CompletableFuture<McpConfig> prepareInteractiveMcpConfig(
            CliWorkspaceBootstrap.Workspace workspace, CliOutput errorOutput) {
        if (workspace.request().workspace().strictMcpConfig()) {
            McpConfig config = CliToolchainAssembler.loadMcpConfig(workspace);
            List<String> fatal = config.diagnostics().stream()
                .filter(diagnostic ->
                    diagnostic.severity() == McpConfigWarning.Severity.FATAL)
                .map(McpConfigWarning::message)
                .toList();
            if (!fatal.isEmpty()) {
                fatal.forEach(message -> errorOutput.println(
                    "Error processing --mcp-config: " + message));
                throw new CliLaunchAbort(1);
            }
            return CompletableFuture.completedFuture(config);
        }
        return CliStartupTasks.supply("mcp-config-startup",
            () -> CliToolchainAssembler.loadMcpConfig(workspace))
            .exceptionally(failure -> {
                errorOutput.println("MCP configuration could not be loaded: "
                    + (failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage()));
                return new McpConfig(Map.of());
            });
    }

    static void runSetupHook(
            String trigger, HookEngine hooks, QuerySession session,
            CliOutput errorOutput) {
        if (trigger == null) return;
        var outcome = hooks.dispatchSetupWithOutcome(trigger);
        if (outcome.hasAdditionalContext()) {
            session.conversation().injectSystemReminder(outcome.additionalContext());
        }
        if (!outcome.proceed() || outcome.hasBlockingErrors()) {
            outcome.blockingErrors().forEach(errorOutput::println);
        }
        if (outcome.hasUserDisplayMessage()) errorOutput.println(outcome.userDisplayMessage());
    }

    private static int runInitOnly(
            CliWorkspaceBootstrap.Workspace workspace,
            CliToolchainAssembler.Toolchain toolchain,
            CliEngineAssembler.EngineRuntime engine,
            CliSessionLifecycleView lifecycle,
            CliOutput output, CliOutput errorOutput) {
        FileChangedHookWatcher watcher = new FileChangedHookWatcher(workspace.hookEngine());
        try (CliHookEffectSink effects = new CliHookEffectSink(
                engine.engine(), lifecycle.transcriptRecorder(),
                toolchain.skillToolProvider().getSkillLoader(), watcher,
                output, errorOutput, false, false)) {
            watcher.initialize(Path.of(workspace.cwd()),
                workspace.hookEngine().configuredFileChangedMatchers());
            workspace.hookEngine().setHookEffectSink(effects);
            runSetupHook(workspace.request().session().setupTrigger(), workspace.hookEngine(),
                engine.engine(), errorOutput);
            engine.engine().execution().setHookDispatcher(workspace.hookEngine());
            workspace.hookEngine().consumeHookMessages().forEach(
                engine.engine().conversation()::appendTranscriptMessage);
            return 0;
        }
    }

    /**
     * Whether this launch will reach the Lanterna REPL, and therefore whether a target-less
     * {@code --resume} has a picker to open. Kept in step with the non-interactive branches
     * of {@link CliExecutionRouter#route}.
     */
    private static boolean interactiveStartup(CliLaunchRequest request) {
        return request.mode().interactive();
    }

    private static int route(CliSessionRuntime runtime, McpRuntime mcpRuntime) {
        CliLaunchRequest.OutputOptions output = runtime.request().output();
        Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState = sdkOutputState(runtime, mcpRuntime);
        return CliExecutionRouter.route(new CliExecutionRouter.Request(
            runtime.request().mode().sdkStreamJson(),
            output.printMode(),
            output.noInteractive(),
            runtime.workspace().launch().initialPrompt() != null,
            () -> runSdkControlMode(runtime, mcpRuntime, sdkOutputState),
            () -> runHeadless(runtime, mcpRuntime, sdkOutputState),
            () -> runInteractive(runtime, mcpRuntime)));
    }

    private static Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState(
            CliSessionRuntime runtime, McpRuntime mcpRuntime) {
        QuerySession engine = runtime.engine().engine();
        SkillToolProvider skillToolProvider = runtime.toolchain().skillToolProvider();
        CliPluginRuntimeView pluginRuntime = runtime.lifecycle().pluginRuntime();
        return () -> {
            StdoutMessageWriter.SdkOutputMetadata metadata = CliHeadlessOutput.withMcpRuntime(
                CliHeadlessOutput.buildSdkOutputMetadata(engine, skillToolProvider, pluginRuntime),
                mcpRuntime);
            ModelAvailability modelAvailability = runtime.toolchain().modelAvailability();
            var subAgentPolicy = runtime.toolchain().subAgentModelPolicy();
            String metadataModel = metadata.model();
            Set<String> availableAgents = AgentDefinitionLoader.getActive(metadata.cwd()).stream()
                .filter(agent -> subAgentPolicy.resolveAgent(agent, null, metadataModel).outcome()
                    != SubAgentModelPolicy.Outcome.REJECT)
                .map(BuiltInAgentDefinitions.AgentDefinition::agentType)
                .collect(Collectors.toUnmodifiableSet());
            metadata = new StdoutMessageWriter.SdkOutputMetadata(
                metadata.sessionId(), metadata.cwd(), metadata.model(), metadata.permissionMode(),
                metadata.tools(), metadata.mcpServers(), metadata.slashCommands(),
                metadata.apiKeySource(), metadata.claudeCodeVersion(), metadata.outputStyle(),
                metadata.agents().stream().filter(availableAgents::contains).toList(),
                metadata.skills(), metadata.plugins(), metadata.mcpToolUseMetadata(),
                metadata.fastModeState());
            if (runtime.request().workspace().disableSlashCommands()) {
                metadata = new StdoutMessageWriter.SdkOutputMetadata(
                    metadata.sessionId(), metadata.cwd(), metadata.model(), metadata.permissionMode(),
                    metadata.tools(), metadata.mcpServers(), List.of(),
                    metadata.apiKeySource(), metadata.claudeCodeVersion(), metadata.outputStyle(),
                    metadata.agents(), List.of(), metadata.plugins(),
                    metadata.mcpToolUseMetadata(), metadata.fastModeState());
            }
            var catalog = CliHeadlessOutput.buildSdkControlCatalog(
                metadata, skillToolProvider, pluginRuntime);
            Map<String, BuiltInAgentDefinitions.AgentDefinition> definitions =
                AgentDefinitionLoader.getActive(metadata.cwd()).stream().collect(
                    Collectors.toMap(BuiltInAgentDefinitions.AgentDefinition::agentType,
                        agent -> agent, (first, _) -> first));
            catalog = new SdkInboundControlHandler.ControlCatalog(
                catalog.commands(), catalog.agents().stream()
                    .filter(agent -> {
                        var definition = definitions.get(agent.name());
                        return definition != null
                            ? subAgentPolicy.resolveAgent(definition, null, metadataModel).outcome()
                                != SubAgentModelPolicy.Outcome.REJECT
                            : subAgentPolicy.resolve(agent.model(), metadataModel).outcome()
                                != SubAgentModelPolicy.Outcome.REJECT;
                    })
                    .toList(), catalog.outputStyles());
            return new StdoutMessageWriter.SdkOutputState(metadata, catalog,
                modelAvailability.showBuiltInModelFamilies(),
                modelAvailability.customModelNames(ModelAllowlist::isAllowed));
        };
    }

    private static int runSdkControlMode(
            CliSessionRuntime runtime,
            McpRuntime mcpRuntime,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState) {
        QuerySession engine = runtime.engine().engine();
        CliLaunchRequest.OutputOptions output = runtime.request().output();
        boolean coordinatorFeatureEnabled = FeatureGate.isEnabled(Flag.COORDINATOR_MODE);
        String restoredSessionMode = CliHeadlessSessionRunner.shouldRecordRestoredSessionMode(
                runtime.restoration().restored(), coordinatorFeatureEnabled)
            ? (isEnvTruthy(SubprocessEnvironment.get(
                "CLAUDE_CODE_COORDINATOR_MODE")) ? "coordinator" : "normal")
            : null;
        LlmClient llmClient = runtime.toolchain().llmClient();
        TerminalSessionTitleGenerator sdkTitleGenerator = llmClient != null
            ? new TerminalSessionTitleGenerator(llmClient, runtime.workspace().launch().model()) : null;
        Function<String, String> sdkSideQuestionRunner = wrappedQuestion ->
            CliInteractiveSessionLauncher.runSideQuestion(
                engine, runtime.engine().querySessionFactory(), runtime.toolchain().client(),
                runtime.workspace().launch().model(),
                () -> runtime.engine().teamMemoryEnabled(), runtime.toolchain().permissionGate(),
                runtime.errorOutput(), wrappedQuestion);
        if (output.sessionMirror()
                && engine.execution().getTranscriptSink()
                    instanceof TranscriptRecorder recorder) {
            recorder.setAppendListener((file, entry) -> {
                var frame = JsonUtils.getMapper().createObjectNode();
                frame.put("type", "transcript_mirror");
                frame.put("filePath", file.toString());
                frame.putArray("entries").add(entry);
                runtime.output().println(frame.toString());
            });
        }
        return CliHeadlessOutput.runSdkControlMode(
            engine, runtime.output(), runtime.errorOutput(),
            runtime.toolchain().permissionGate(), runtime.workspace().cwd(),
            output.includePartialMessages(), output.replayUserMessages(),
            runtime.toolchain().structuredOutputSchema(), sdkOutputState, restoredSessionMode,
            output.permissionPromptToolName(), runtime.engine().contextDataCollector(),
            mcpRuntime::snapshotServerStatuses, runtime.request().session().name(),
            sdkTitleGenerator, sdkSideQuestionRunner,
            runtime.lifecycle().pluginRuntime(), mcpRuntime, runtime.sdkBrokerRef(),
            runtime.workspace().hookEngine());
    }

    private static int runHeadless(
            CliSessionRuntime runtime,
            McpRuntime mcpRuntime,
            Supplier<StdoutMessageWriter.SdkOutputState> sdkOutputState) {
        CliLaunchRequest.OutputOptions output = runtime.request().output();
        return CliHeadlessSessionRunner.run(new CliHeadlessSessionRunner.Request(
            runtime.engine().engine(), runtime.workspace().launch().initialPrompt(),
            runtime.request().session().name(), runtime.request().session().setupTrigger(),
            runtime.output(), runtime.errorOutput(),
            runtime.toolchain().structuredOutputSchema(), sdkOutputState, output.outputFormat(),
            output.verbose(), output.includePartialMessages(), runtime.restoration().restored(),
            runtime.restoration().deferredRecoveryTranscript(), runtime.workspace().launch().model(),
            runtime.toolchain().permissionGate(), runtime.toolchain().toolRegistry(),
            runtime.engine().compactService(), runtime.engine().contextDataCollector(),
            runtime.workspace().hookEngine(), runtime.lifecycle().transcriptRecorder(),
            runtime.toolchain().llmClient(), runtime.engine().config(), mcpRuntime,
            runtime.lifecycle().pluginRuntime(), runtime.toolchain().skillToolProvider(),
            runtime.workspace().cwd()));
    }

    private static int runInteractive(CliSessionRuntime runtime, McpRuntime mcpRuntime) {
        CliLaunchRequest.OutputOptions output = runtime.request().output();
        CliLaunchRequest.ModelOptions model = runtime.request().model();
        return CliInteractiveSessionRunner.run(new CliInteractiveSessionRunner.Request(
            runtime.engine().engine(), runtime.toolchain().client(), runtime.workspace().launch().model(),
            runtime.engine().teamMemoryEnabled(), runtime.toolchain().permissionGate(), mcpRuntime,
            runtime.lifecycle().pluginRuntime(), runtime.lifecycle().promptInventory(),
            runtime.toolchain().toolRegistry(),
            runtime.engine().compactService(), runtime.engine().sideQuery(),
            runtime.engine().querySessionFactory(), runtime.toolchain().llmClient(),
            runtime.engine().config(), runtime.lifecycle().transcriptRecorder(), runtime.workspace().hookEngine(),
            runtime.lifecycle().settingsReload(), runtime.toolchain().skillToolProvider(),
            runtime.toolchain().taskBoard(),
            runtime.engine().outputStyleService(), runtime.toolchain().resolvedApiProvider(),
            runtime.toolchain().resolvedBaseUrl(), model.apiKey(),
            runtime.toolchain().modelAvailability().showBuiltInModelFamilies(),
            runtime.request().permissions().dangerouslySkipPermissions()
                || runtime.request().permissions().allowDangerouslySkipPermissions(),
            runtime.workspace().launch().initialPrompt(), runtime.request().session().name(),
            runtime.request().session().setupTrigger(),
            output.printMode(), output.noInteractive(),
            runtime.restoration().restored(), output.verbose(), runtime.engine().contextDataCollector(),
            runtime.toolchain().agentSummaryService(),
            runtime.progressSink(), runtime.toolchain().lspIntegration(),
            runtime.toolchain().customModelCatalog(), runtime.output(),
            runtime.errorOutput(), runtime.restoration().pickerRequested(),
            runtime.restoration().pickerSearchTerm()));
    }
}

package com.claudecode.cli;

import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.ApiConfig;
import com.claudecode.api.ApiKeyVerifier;
import com.claudecode.api.ApiProviderResolver;
import com.claudecode.api.LlmClient;
import com.claudecode.api.CustomModelJsonStore;
import com.claudecode.api.CustomModelRoutingClient;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.core.engine.MidConversationSystemSupport;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.SubAgentLifecycleListener;
import com.claudecode.core.engine.ToolSearchGate;
import com.claudecode.core.message.SkillListingEntry;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.claudecode.core.platform.Platform;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.prompt.SystemPromptConfig;
import com.claudecode.core.prompt.SystemPromptProfileResolver;
import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.core.state.CwdState;
import com.claudecode.mcp.McpConfigLoader;
import com.claudecode.mcp.McpConfig;
import com.claudecode.mcp.SdkControlTransport;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionMode;
import com.claudecode.permissions.PermissionPathContext;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.claudecode.services.agent.AgentSummaryService;
import com.claudecode.services.cache.PromptCacheBreakCleanup;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.services.claudemd.MemoryPromptBuilder;
import com.claudecode.services.claudemd.MemoryType;
import com.claudecode.services.compact.SubAgentCompactServiceImpl;
import com.claudecode.services.config.GitSettings;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.McpSkillsFeatureGate;
import com.claudecode.services.config.PermissionSettings;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.config.SettingsPaths;
import com.claudecode.services.config.SettingsEditor;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.services.config.SimpleSystemPromptFeatureGate;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.model.GoalContextWindowPolicy;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolBootstrap;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.ToolSearchTool;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.agent.AgentTool;
import com.claudecode.tools.agent.DefaultSubAgentFactory;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.tools.agent.SubAgentModelPolicy;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.cron.CronCreateTool;
import com.claudecode.tools.cron.CronDeleteTool;
import com.claudecode.tools.cron.CronListTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.output.SyntheticOutputTool;
import com.claudecode.tools.plan.EnterPlanModeTool;
import com.claudecode.tools.plan.ExitPlanModeTool;
import com.claudecode.tools.plan.PlanFeatureGate;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.powershell.PowerShellTool;
import com.claudecode.tools.questions.AskUserQuestionTool;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.skills.DynamicSkillDiscovery;
import com.claudecode.tools.skills.DynamicSkillTriggerSet;
import com.claudecode.tools.skills.ShellVariableInjector;
import com.claudecode.tools.skills.SkillLoader;
import com.claudecode.tools.skills.Skill;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.runtime.tasks.TaskBoardPort;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskReminderSource;
import com.claudecode.tools.tasks.TaskToolProvider;
import com.claudecode.tools.tasks.TodoStore;
import com.claudecode.tools.tasks.TodoWriteTool;
import com.claudecode.tools.workflows.BundledWorkflowLoader;
import com.claudecode.tools.workflows.SubAgentWorkflowExecutor;
import com.claudecode.tools.workflows.WorkflowCatalog;
import com.claudecode.tools.workflows.WorkflowFeatureGate;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.tools.workflows.WorkflowRuntime;
import com.claudecode.tools.workflows.WorkflowTool;
import com.claudecode.tools.worktree.EnterWorktreeTool;
import com.claudecode.tools.worktree.ExitWorktreeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the permission gate and complete model-visible tool inventory.
 *
 * <ul>
 *   <li>constructs the API clients,
 *       permission context, built-ins, MCP, skills, tasks, workflows, and LSP
 *       before the query configuration snapshots the catalogue.</li>
 *   <li>loads persisted
 *       rules/directories and applies CLI allow, deny, and base-tool filtering.</li>
 *   <li>wires sub-agent compact,
 *       memory, hook, dynamic-skill collaborators, and the same optional
 *       Anthropic credentials into built-in tools, including when the main
 *       loop is routed through a custom model provider.</li>
 *   <li>
 *       — supplies the resolved first-party/provider classification, configured
 *       project MCP approvals in effective settings, and live Skill inventory.</li>
 *   <li>installs MCP SDK message exchange
 *       only for stream-json controller sessions.</li>
 *   <li>
 *        separates
 *       first-party routing from usable direct-API authentication when
 *       projecting built-in models and model-pinned sub-agents.</li>
 * </ul>
 */
final class CliToolchainAssembler {

    private static final Logger log = LoggerFactory.getLogger(CliToolchainAssembler.class);

    private CliToolchainAssembler() {}

    record Toolchain(
            StreamingClient client,
            LlmClient llmClient,
            ApiConfig.ApiProvider resolvedApiProvider,
            String resolvedBaseUrl,
            ModelAvailability modelAvailability,
            SubAgentModelPolicy subAgentModelPolicy,
            ToolRegistry toolRegistry,
            PermissionGate permissionGate,
            SkillToolProvider skillToolProvider,
            TaskBoardPort taskBoard,
            TaskReminderSource taskReminders,
            CliLspIntegration lspIntegration,
            JsonNode structuredOutputSchema,
            AgentSummaryService agentSummaryService,
            CustomModelJsonStore customModelCatalog,
            Set<String> dynamicSkillDirTriggers,
            List<Path> inlinePluginPaths,
            List<Path> inlinePluginPathsWithoutMcp) {
        Toolchain {
            // This is intentionally the live trigger set populated by Read/
            // Write/Edit during a turn; copying it would suppress the next
            // dynamic-skill attachment.
            inlinePluginPaths = List.copyOf(inlinePluginPaths);
            inlinePluginPathsWithoutMcp = List.copyOf(inlinePluginPathsWithoutMcp);
        }
    }

    static Toolchain assemble(
            CliWorkspaceBootstrap.Workspace workspace,
            McpRuntime mcpRuntime,
            AtomicReference<SdkControlBroker> sdkBrokerRef,
            AtomicReference<CliPluginRuntimeView> pluginRuntimeRef,
            QuerySessionFactory querySessionFactory,
            CliResourceScope resources,
            CliOutput errorOutput) {
        CliLaunchRequest request = workspace.request();
        CliLaunchRequest.ModelOptions modelOptions = request.model();
        CliLaunchRequest.OutputOptions output = request.output();
        CliLaunchRequest.WorkspaceOptions workspaceOptions = request.workspace();
        CliLaunchRequest.PermissionOptions permissionOptions = request.permissions();
        HookEngine hookEngine = workspace.hookEngine();
        @Explanation("Wires the model.json custom-model catalogue")
        CustomModelJsonStore customModelCatalog = new CustomModelJsonStore();

        StreamingClient client;
        LlmClient llmClient = null;
        ApiConfig.ApiProvider resolvedApiProvider = ApiProviderResolver.resolve();
        ConfigLoader.Credentials credentials = resolveFallbackCredentials(
            workspace.configLoader(), resolvedApiProvider, modelOptions.apiKey());
        String clientBaseUrl = selectBaseUrl(
            modelOptions.baseUrl(), workspace.configLoader().resolveBaseUrl());
        ModelAvailability modelAvailability = new ModelAvailability(
            resolvedApiProvider, clientBaseUrl, credentials, customModelCatalog);
        if (request.testOverrides().streamingClient() != null) {
            client = request.testOverrides().streamingClient();
        } else {
            boolean nonInteractive = output.printMode() || output.noInteractive();
            // The resolved value deliberately includes ANTHROPIC_BASE_URL.  The
            // client factory has no independent environment lookup, so passing
            // only the CLI option would silently discard an environment override.
            LlmClient fallbackClient = workspace.configLoader().createLlmClient(
                credentials.apiKey(), credentials.authToken(), workspace.launch().model(),
                clientBaseUrl, modelOptions.betas());
            if (resolvedApiProvider == ApiConfig.ApiProvider.ANTHROPIC) {
                fallbackClient = new CredentialGuardedLlmClient(
                    fallbackClient, modelAvailability.showBuiltInModelFamilies());
            }
            llmClient = CustomModelRoutingClient.standard(fallbackClient, customModelCatalog::find);
            client = new LlmClientAdapter(llmClient);

            if (resolvedApiProvider == ApiConfig.ApiProvider.ANTHROPIC
                    && modelOptions.apiKey() != null && !StringUtils.isBlank(modelOptions.apiKey())
                    && !ApiKeyVerifier.verify(modelOptions.apiKey(), nonInteractive, llmClient)) {
                errorOutput.println(
                    "Error: the provided ANTHROPIC_API_KEY is invalid (API returned "
                        + "\"invalid x-api-key\"). Check the key and try again.");
                throw new CliLaunchAbort(1);
            }
        }
        log.info("[goal-diag] CliToolchainAssembler: llmClient={} (testOverrideStreamingClient={})",
            llmClient != null ? "NON-NULL" : "null",
            request.testOverrides().streamingClient() != null);
        hookEngine.setLlmClient(llmClient);
        hookEngine.setLlmModel(workspace.launch().model());
        hookEngine.setGoalContextWindowResolver(candidateModel -> {
            Long configured = customModelCatalog.contextWindow(candidateModel);
            return configured != null ? configured
                : GoalContextWindowPolicy.contextWindow(
                    candidateModel, resolvedApiProvider, clientBaseUrl);
        });
        ToolSearchGate.configureResolvedBaseUrl(clientBaseUrl);
        ModelApiProtocol fallbackProtocol =
            resolvedApiProvider == ApiConfig.ApiProvider.OPENAI_COMPAT
                ? ModelApiProtocol.OPENAI_CHAT : ModelApiProtocol.ANTHROPIC;
        ToolSearchGate.configureProtocolResolver(candidateModel ->
            customModelCatalog.find(candidateModel)
                .map(CustomModelConfig::protocol)
                .orElse(fallbackProtocol));
        MidConversationSystemSupport.configureBaseUrlResolver(candidateModel ->
            customModelCatalog.find(candidateModel).map(CustomModelConfig::baseUrl).orElse(clientBaseUrl));

        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.setDestructiveCommandWarningEnabled(
            GlobalConfigStore.getBoolean("destructiveCommandWarning", false));
        PlanFiles.configurePlansDirectory(WorkspaceSettings.loadPlansDirectory(System.getProperty("user.dir")));
        PlanFiles.configureMultiPlan(PlanFeatureGate.systemEnabled());
        PermissionGate permissionGate = createPermissionGate(workspace);

        permissionGate.configureAutoModeModelSupport(model ->
            resolvedApiProvider == ApiConfig.ApiProvider.ANTHROPIC
                && PermissionGate.supportsReleasedExternalAutoModeModel(model));
        permissionGate.setAutoModeCurrentModel(workspace.launch().model());
        SandboxManager sandboxMgr = PlatformSandboxManager.create();
        hookEngine.setSandboxProxyEnvironmentSupplier(() ->
            sandboxMgr.sandboxEnvironment(SandboxSettings.loadSandboxConfig()));
        permissionGate.setBashSandboxGate((cmd, dds) -> {
            SandboxConfig cfg = SandboxSettings.loadSandboxConfig();
            return cfg.autoAllowBashIfSandboxed() && sandboxMgr.decide(cmd, dds, cfg).isSandboxed();
        });
        permissionGate.addRules(loadPermissionRules());
        permissionGate.addDirectories(loadAdditionalDirectories());
        permissionGate.addDirectories(workspace.cliAdditionalDirectories());
        toolRegistry.setPermissionGate(permissionGate);

        AgentSummaryService agentSummaryService = RuntimeSettings.loadAgentProgressSummariesEnabled()
            ? new AgentSummaryService(llmClient) : null;
        PromptCacheBreakCleanup promptCacheBreakCleanup = new PromptCacheBreakCleanup();
        SubAgentLifecycleListener subAgentLifecycle = new SubAgentLifecycleListener() {
            @Override
            public HookDispatcher.HookOutcome onSubAgentStart(String agentId, String agentType) {
                return hookEngine.dispatchSubAgentStartWithOutcome(agentId, agentType);
            }

            @Override
            public HookDispatcher createSubAgentHookDispatcher(SubAgentHookContext context) {
                return hookEngine.createSubAgentDispatcher(context);
            }

            @Override
            public void onSubAgentComplete(String agentId) {
                promptCacheBreakCleanup.onSubAgentComplete(agentId);
                TaskRegistry.global().killShellAndMonitorTasksForAgent(agentId);
            }
        };
        SubAgentCompactServiceImpl subAgentCompactFactory = new SubAgentCompactServiceImpl(
            llmClient, RuntimeSettings.loadAutoCompactEnabled());
        SkillLoader sessionSkillLoader = new SkillLoader();
        Set<String> dynamicSkillDirTriggers = new DynamicSkillTriggerSet();
        DynamicSkillDiscovery dynamicSkillDiscovery = new DynamicSkillDiscovery(
            sessionSkillLoader, dynamicSkillDirTriggers,
            !workspaceOptions.disableSlashCommands() && workspace.settingSources().project());
        Function<Path, String> subAgentClaudeMdLoader = subAgentCwd -> {
            try {
                var scanner = MemoryFileScanner.forConfigHome(
                    ClaudePaths.CLAUDE_HOME,
                    WorkspaceSettings.loadClaudeMdExcludes(settingsRootForSubAgent(subAgentCwd)), null);
                return new MemoryPromptBuilder(scanner).build(subAgentCwd, List.of(),
                    Set.of(MemoryType.USER, MemoryType.PROJECT, MemoryType.LOCAL), null);
            } catch (Throwable t) {
                log.warn("Sub-agent memory content load failed: {}", t.getMessage());
                return "";
            }
        };
        boolean usingThirdPartyServices = isUsingThirdPartyServices(resolvedApiProvider);
        Supplier<List<Skill>> liveSkillSupplier = () -> {
            try { return sessionSkillLoader.loadAll(); }
            catch (Throwable _) { return List.of(); }
        };
        Supplier<List<SkillListingEntry>> liveSkillListingSupplier = () -> {
            try { return CliPromptInventoryAssembler.skillListingEntries(liveSkillSupplier.get()); }
            catch (Throwable _) { return List.of(); }
        };
        ToolBootstrap.registerBuiltInTools(toolRegistry, client, toolRegistry, agentSummaryService,
            workspace.sessionIdentity(), subAgentLifecycle, subAgentCompactFactory, dynamicSkillDiscovery,
            GitSettings::shouldIncludeGitInstructions,
            liveSkillListingSupplier,
            liveSkillSupplier,
            subAgentClaudeMdLoader, RuntimeSettings::loadSkipWebFetchPreflight,
            usingThirdPartyServices,
            () -> CliPromptInventoryAssembler.guideSettings(System.getProperty("user.dir")),
            querySessionFactory);
        CliSubAgentModelPolicy subAgentModelPolicy = new CliSubAgentModelPolicy(
            modelAvailability, ModelAllowlist::isAllowed);
        toolRegistry.get("Agent")
            .filter(AgentTool.class::isInstance)
            .map(AgentTool.class::cast)
            .ifPresent(agent -> {
                agent.setSubAgentModelPolicy(subAgentModelPolicy);
                agent.setSubagentMaxDepthSupplier(RuntimeSettings::loadSubagentMaxDepth);
                agent.setTeammateHookDispatcher(hookEngine);
                hookEngine.setAgentHookFactory(agent.subAgentFactory());
            });
        // QuerySessionSpec snapshots the model-visible tool definitions during
        // construction, before CliEngineAssembler can bind its live model
        // supplier. Seed model-dependent descriptions from the resolved launch
        // model now; CliEngineAssembler replaces these with the live supplier
        // afterward so /model changes still take effect on later turns.
        toolRegistry.get("Bash")
            .filter(BashTool.class::isInstance)
            .map(BashTool.class::cast)
            .ifPresent(bash -> bash.setModelSupplier(workspace.launch()::model));
        toolRegistry.get("Read")
            .filter(FileReadTool.class::isInstance)
            .map(FileReadTool.class::cast)
            .ifPresent(read -> read.setModelSupplier(workspace.launch()::model));
        final List<String> todoWriteSimplePromptModelPatterns =
            SimpleSystemPromptFeatureGate.modelPatterns();
        toolRegistry.get("TodoWrite")
            .filter(TodoWriteTool.class::isInstance)
            .map(TodoWriteTool.class::cast)
            .ifPresent(todoWrite -> todoWrite.setSimplePromptSupplier(() ->
                SystemPromptProfileResolver.resolve(SystemPromptConfig.builder()
                    .modelId(workspace.launch().model())
                    .apiProvider(client.provider())
                    .simpleSystemPromptModelPatterns(
                        todoWriteSimplePromptModelPatterns)
                    .build()) == SystemPromptProfileResolver.Profile.HARNESS));

        boolean workflowManagedDisabled = Boolean.TRUE.equals(
            RuntimeSettings.readPolicyBoolean("disableWorkflows"));
        boolean workflowsEnabled = WorkflowFeatureGate.evaluate(SubprocessEnvironment.snapshot(), workflowManagedDisabled,
            RuntimeSettings.loadOptionalBoolean("enableWorkflows"), true, true);
        WorkflowCatalog workflowCatalog = new WorkflowCatalog(ClaudePaths.WORKFLOWS_DIR,
            BundledWorkflowLoader.load(), () ->
                CliHeadlessSessionRunner.pluginWorkflows(pluginRuntimeRef.get()));
        DefaultSubAgentFactory workflowSubAgents = new DefaultSubAgentFactory(client, toolRegistry,
            System.getProperty("user.dir"), agentSummaryService, workspace.sessionIdentity(),
            subAgentLifecycle, subAgentCompactFactory, liveSkillListingSupplier, liveSkillSupplier,
            GitSettings::shouldIncludeGitInstructions, subAgentClaudeMdLoader,
            usingThirdPartyServices,
            () -> CliPromptInventoryAssembler.guideSettings(System.getProperty("user.dir")));
        workflowSubAgents.setSubAgentModelPolicy(subAgentModelPolicy);
        workflowSubAgents.setQuerySessionFactory(querySessionFactory);
        WorkflowRuntime workflowRuntime = new WorkflowRuntime(
            new SubAgentWorkflowExecutor(workflowSubAgents, permissionGate), workflowCatalog);
        toolRegistry.register(new WorkflowTool(workflowRuntime, workflowCatalog, TaskRegistry.global(),
            WorkflowRunStore.global(), ClaudePaths.CLAUDE_HOME, workflowsEnabled, workflowManagedDisabled));

        try {
            AskUserQuestionTool.setPreviewFormat(resolveQuestionPreviewFormat(
                workspace.sdkCliSession(), SubprocessEnvironment.get("CLAUDE_CODE_QUESTION_PREVIEW_FORMAT"),
                RuntimeSettings.loadAskUserQuestionPreviewFormat()));
        } catch (Throwable _) {
            // A failed optional preview setting must not prevent tool setup.
        }

        configureMcp(workspace, mcpRuntime, sdkBrokerRef, sessionSkillLoader, hookEngine);
        SkillToolProvider skillToolProvider = new SkillToolProvider(
            sessionSkillLoader, new ShellVariableInjector(workspace.sessionIdentity()));
        skillToolProvider.initialize(Path.of(System.getProperty("user.dir")), toolRegistry,
            !workspaceOptions.disableSlashCommands(), workspace.settingSources().user(),
            workspace.settingSources().project(), false);
        TaskToolProvider taskToolProvider = resources.own(new TaskToolProvider(
            new TodoStore(workspace.sessionIdentity().get()), workspace.sessionIdentity()));
        taskToolProvider.initialize(toolRegistry);
        List<Path> inlinePluginPathsWithoutMcp = workspaceOptions.pluginDirectoriesNoMcp().stream()
            .map(Path::of).toList();
        List<Path> inlinePluginPaths = Stream.concat(
                workspaceOptions.pluginDirectories().stream(),
                workspaceOptions.pluginDirectoriesNoMcp().stream())
            .map(Path::of).distinct().toList();
        CliLspIntegration lspIntegration = CliLspIntegration.wire(Path.of(System.getProperty("user.dir")),
            toolRegistry, isEnvTruthy(SubprocessEnvironment.get("ENABLE_LSP_TOOL")));

        toolRegistry.register(new EnterPlanModeTool(permissionGate));
        toolRegistry.register(new ExitPlanModeTool(permissionGate));
        toolRegistry.register(new EnterWorktreeTool());
        toolRegistry.register(new ExitWorktreeTool());
        if (workspace.promptNonInteractive()
                && (StringUtils.isBlank(output.permissionPromptToolName()))) {
            toolRegistry.unregisterMatching(Set.of("AskUserQuestion", "EnterPlanMode", "ExitPlanMode")::contains);
        }
        toolRegistry.register(new ToolSearchTool(toolRegistry, mcpRuntime::pendingServerNames));
        if (Platform.IS_WINDOWS && isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_USE_POWERSHELL_TOOL"))) {
            toolRegistry.register(new PowerShellTool());
        }
        toolRegistry.register(new CronCreateTool());
        toolRegistry.register(new CronDeleteTool());
        toolRegistry.register(new CronListTool());
        toolRegistry.register(new ConfigureStatusLineTool((command, padding) -> {
            ObjectNode statusLine = JsonUtils.getMapper().createObjectNode();
            statusLine.put("type", "command");
            statusLine.put("command", command);
            statusLine.put("padding", padding);
            SettingsEditor.writeUserValue("statusLine", statusLine);
        }));

        // Released 2.1.197 omits the dedicated Glob/Grep tools from its default
        // preset. Explicit allow/deny rules and --tools rebuild the selectable
        // catalogue before applying their filters, so retain the search tools
        // for all three explicit paths.
        boolean explicitToolSelection = CliToolSelection.hasExplicitToolSelection(
            permissionOptions.allowedTools(), permissionOptions.disallowedTools(),
            permissionOptions.baseTools());
        if (!explicitToolSelection) {
            toolRegistry.unregisterMatching(Set.of("Glob", "Grep")::contains);
        }
        applyCliToolFiltering(workspace, toolRegistry, permissionGate);

        // filtering. A schema makes it a protocol requirement, not a
        // user-controlled entry in --tools / --disallowed-tools.
        JsonNode structuredOutputSchema = registerStructuredOutput(
            workspace, toolRegistry, errorOutput);

        return new Toolchain(client, llmClient, resolvedApiProvider, clientBaseUrl,
            modelAvailability, subAgentModelPolicy, toolRegistry,
            permissionGate, skillToolProvider, taskToolProvider.taskBoard(),
            taskToolProvider.taskReminders(), lspIntegration, structuredOutputSchema,
            agentSummaryService, customModelCatalog, dynamicSkillDirTriggers, inlinePluginPaths,
            inlinePluginPathsWithoutMcp);
    }

    /**
     * Loads optional Anthropic credentials even when the selected main model is
     * custom. Missing credentials remain valid at startup, while a sub-agent
     * that explicitly selects Sonnet/Opus/Haiku can use the configured fallback.
     */
    static ConfigLoader.Credentials resolveFallbackCredentials(
            ConfigLoader configLoader, ApiConfig.ApiProvider provider, String cliApiKey) {
        return provider == ApiConfig.ApiProvider.ANTHROPIC
            ? configLoader.resolveCredentials(cliApiKey)
            : new ConfigLoader.Credentials(null, null);
    }


    static String settingsRootForSubAgent(Path subAgentCwd) {
        Path original = CwdState.getOriginalCwd();
        return (original != null ? original : subAgentCwd).toString();
    }

    /** CLI option takes precedence; otherwise retain ConfigLoader's environment-derived URL. */
    static String selectBaseUrl(String cliBaseUrl, String environmentBaseUrl) {
        return cliBaseUrl != null ? cliBaseUrl : environmentBaseUrl;
    }

    static boolean isUsingThirdPartyServices(ApiConfig.ApiProvider provider) {

        // endpoint. ANTHROPIC_BASE_URL may point at a compatible gateway while
        // /feedback remains available; only Bedrock/Vertex/Foundry are 3P here.
        return provider != ApiConfig.ApiProvider.ANTHROPIC;
    }

    private static PermissionGate createPermissionGate(CliWorkspaceBootstrap.Workspace workspace) {
        // launchCwd is retained only for the pre-worktree git snapshot. Every
        // tool and permission decision must start from the live worktree cwd.
        Path cwdPath = Path.of(workspace.cwd());
        Path settingsRoot = CwdState.getOriginalCwd() == null
            ? cwdPath : CwdState.getOriginalCwd();

        // while rooted settings patterns resolve through the session-root map below

        PermissionPathContext permissionPathContext = PermissionPathContext.forSession(cwdPath, Map.of(
            RuleSource.USER_SETTINGS,
                SettingsPaths.userSettingsPath().toAbsolutePath().normalize().getParent(),
            RuleSource.PROJECT_SETTINGS, settingsRoot,
            RuleSource.LOCAL_SETTINGS, settingsRoot,
            RuleSource.POLICY_SETTINGS, settingsRoot,
            RuleSource.FLAG_SETTINGS, SettingsSources.flagSettingsRootPath(settingsRoot.toString())), Set.of());
        ToolPermissionContext permissionContext = ToolPermissionContext.builder()
            .workingDirectory(cwdPath).pathContext(permissionPathContext).build();
        PermissionGate gate = new PermissionGate(permissionContext,
            new CliPermissionPaths(cwdPath, workspace.sessionIdentity().get()));
        PermissionMode initialPermissionMode = resolvePermissionMode(workspace.request().permissions());
        boolean bypassLaunchAllowed = workspace.request().permissions().dangerouslySkipPermissions()
            || workspace.request().permissions().allowDangerouslySkipPermissions()
            || initialPermissionMode == PermissionMode.BYPASS_PERMISSIONS;
        gate.configureBypassPermissionsMode(
            bypassLaunchAllowed, PermissionSettings.isBypassPermissionsModeDisabled());
        gate.configurePlanAutoMode(
            PermissionSettings::hasSkipAutoPermissionPrompt,
            PermissionSettings::useAutoModeDuringPlan,
            PermissionSettings::isAutoModeGateEnabledBySettings);
        gate.setMode(initialPermissionMode);
        return gate;
    }

    static String resolveQuestionPreviewFormat(
            boolean sdkCliSession, String environmentFormat, String configuredFormat) {
        if (Strings.CS.equals("markdown", environmentFormat)
                || Strings.CS.equals("html", environmentFormat)) {
            return environmentFormat;
        }
        if (Strings.CS.equals("markdown", configuredFormat)
                || Strings.CS.equals("html", configuredFormat)) {
            return configuredFormat;
        }
        return sdkCliSession ? null : "markdown";
    }

    private static PermissionMode resolvePermissionMode(CliLaunchRequest.PermissionOptions options) {
        List<String> candidates = new ArrayList<>();
        if (options.dangerouslySkipPermissions()) candidates.add("bypassPermissions");
        if (StringUtils.isNotBlank(options.permissionMode())) {
            candidates.add(options.permissionMode());
        }
        String fromSettings = PermissionSettings.loadDefaultPermissionMode();
        if (fromSettings != null) candidates.add(fromSettings);
        boolean bypassDisabled = PermissionSettings.isBypassPermissionsModeDisabled();
        for (String candidate : candidates) {
            PermissionMode mode = PermissionMode.fromString(candidate);
            if (mode == PermissionMode.BYPASS_PERMISSIONS && bypassDisabled) continue;
            return mode;
        }
        return PermissionMode.DEFAULT;
    }

    private static List<PermissionRule> loadPermissionRules() {

// getSettingsForSource: a malformed source is empty, but valid sibling
        // sources still contribute their rules.  Keep strict readers for diagnostics
        // and editing, not for executable startup state.
        return PermissionSettings.loadPermissionRulesForExecution(System.getProperty("user.dir"));
    }

    private static List<Path> loadAdditionalDirectories() {
        return PermissionSettings.loadAdditionalDirectories(System.getProperty("user.dir")).stream()
            .map(Path::of).toList();
    }

    @Explanation("MCP OAuth is intentionally not mapped to Claude-account auth_success hooks")
    private static void configureMcp(
            CliWorkspaceBootstrap.Workspace workspace,
            McpRuntime mcpRuntime,
            AtomicReference<SdkControlBroker> sdkBrokerRef,
            SkillLoader sessionSkillLoader,
            HookEngine hookEngine) {
        if (Strings.CS.equals("stream-json", workspace.request().output().inputFormat())) {
            mcpRuntime.clientRuntime().setSdkMessageExchange(new SdkControlTransport.MessageExchange() {
                @Override
                public CompletableFuture<JsonNode> exchange(String server, JsonNode message) {
                    SdkControlBroker broker = sdkBrokerRef.get();
                    if (broker == null) throw new IllegalStateException("SDK control broker is not initialized");
                    return broker.askMcpMessageAsync(server, message);
                }

                @Override
                public void send(String server, JsonNode message) {
                    SdkControlBroker broker = sdkBrokerRef.get();
                    if (broker == null) throw new IllegalStateException("SDK control broker is not initialized");
                    broker.sendMcpMessage(server, message);
                }
            });
            mcpRuntime.clientRuntime().setElicitationHandler((server, params, toolUseIds) -> {
                hookEngine.dispatchNotification(
                    params != null ? params.path("message").asText("MCP server requests input")
                        : "MCP server requests input",
                    server, "elicitation_dialog");
                HookDispatcher.HookOutcome before = hookEngine.dispatchElicitationWithOutcome(
                    server,
                    params != null ? params.path("message").asText("") : "",
                    params != null ? params.path("mode").asText(null) : null,
                    params != null ? params.path("url").asText(null) : null,
                    params != null ? params.path("elicitationId").asText(null) : null,
                    params != null ? params.get("requestedSchema") : null);
                JsonNode hookDecision = before.specificOutput("Elicitation").orElse(null);
                if (!before.proceed() || before.preventContinuation()) {
                    return JsonUtils.getMapper().createObjectNode().put("action", "decline");
                }
                if (hookDecision != null && validElicitationAction(
                        hookDecision.path("action").asText(null))) {
                    ObjectNode directResponse = JsonUtils.getMapper().createObjectNode();
                    directResponse.put("action", hookDecision.path("action").asText());
                    if (hookDecision.has("content")) {
                        directResponse.set("content", hookDecision.get("content"));
                    }
                    JsonNode response = directResponse;
                    HookDispatcher.HookOutcome after =
                        hookEngine.dispatchElicitationResultWithOutcome(
                            server, response.path("action").asText(), response.get("content"),
                            params != null ? params.path("mode").asText(null) : null,
                            params != null ? params.path("elicitationId").asText(null) : null);
                    response = applyElicitationResultOutcome(response, after);
                    hookEngine.dispatchNotification(
                        "MCP elicitation " + response.path("action").asText(),
                        server, "elicitation_response");
                    return response;
                }
                SdkControlBroker broker = sdkBrokerRef.get();
                JsonNode response = broker != null ? broker.askElicitation(server, params, toolUseIds)
                    : JsonUtils.getMapper().createObjectNode().put("action", "cancel");
                HookDispatcher.HookOutcome after =
                    hookEngine.dispatchElicitationResultWithOutcome(
                        server, response.path("action").asText("cancel"), response.get("content"),
                        params != null ? params.path("mode").asText(null) : null,
                        params != null ? params.path("elicitationId").asText(null) : null);
                response = applyElicitationResultOutcome(response, after);
                hookEngine.dispatchNotification(
                    "MCP elicitation " + response.path("action").asText("cancel"),
                    server, "elicitation_complete");
                return response;
            });
        }
        mcpRuntime.configureMcpSkills(
            McpSkillsFeatureGate.enabled(), sessionSkillLoader, ClaudePaths.CLAUDE_HOME);
    }




    static void startMcpConnections(
            CliWorkspaceBootstrap.Workspace workspace,
            McpRuntime mcpRuntime,
            ToolRegistry toolRegistry) {
        startMcpConnections(workspace, mcpRuntime, toolRegistry, loadMcpConfig(workspace));
    }

    static McpConfig loadMcpConfig(CliWorkspaceBootstrap.Workspace workspace) {
        Path cwdPath = Path.of(workspace.cwd());
        return McpConfigLoader.loadConfig(cwdPath,
            workspace.request().workspace().mcpConfig(), workspace.request().workspace().strictMcpConfig());
    }

    static void startMcpConnections(
            CliWorkspaceBootstrap.Workspace workspace,
            McpRuntime mcpRuntime,
            ToolRegistry toolRegistry,
            McpConfig mcpConfig) {
        mcpRuntime.initialize(mcpConfig, Path.of(workspace.cwd()), toolRegistry);
    }

    private static boolean validElicitationAction(String action) {
        return Strings.CS.equals("accept", action)
            || Strings.CS.equals("decline", action)
            || Strings.CS.equals("cancel", action);
    }

    static JsonNode applyElicitationResultOutcome(
            JsonNode response, HookDispatcher.HookOutcome outcome) {
        if (outcome == null || !outcome.proceed() || outcome.preventContinuation()) {
            return JsonUtils.getMapper().createObjectNode().put("action", "decline");
        }
        JsonNode override = outcome.specificOutput("ElicitationResult").orElse(null);
        if (override == null || !validElicitationAction(override.path("action").asText(null))) {
            return response;
        }
        ObjectNode result = JsonUtils.getMapper().createObjectNode()
            .put("action", override.path("action").asText());
        JsonNode content = override.hasNonNull("content")
            ? override.get("content") : response != null ? response.get("content") : null;
        if (content != null && !content.isNull()) {
            result.set("content", content);
        }
        return result;
    }

    private static JsonNode registerStructuredOutput(
            CliWorkspaceBootstrap.Workspace workspace, ToolRegistry toolRegistry,
            CliOutput errorOutput) {
        String raw = workspace.request().model().jsonSchemaRaw();
        if (!workspace.sdkCliSession() || raw == null || StringUtils.isBlank(raw)) return null;
        JsonNode parsedSchema;
        try {
            parsedSchema = JsonUtils.getMapper().readTree(raw);
        } catch (Exception e) {
            errorOutput.println("Warning: --json-schema is not valid JSON: " + e.getMessage());
            return null;
        }
        SyntheticOutputTool.CreateResult created = SyntheticOutputTool.create(parsedSchema);
        if (created instanceof SyntheticOutputTool.CreateResult.Ok(SyntheticOutputTool tool)) {
            toolRegistry.register(tool);
            return parsedSchema;
        }
        if (created instanceof SyntheticOutputTool.CreateResult.Err(String error)) {
            errorOutput.println("Warning: Invalid --json-schema: " + error);
        }
        return null;
    }

    private static void applyCliToolFiltering(
            CliWorkspaceBootstrap.Workspace workspace,
            ToolRegistry toolRegistry,
            PermissionGate permissionGate) {
        CliLaunchRequest.PermissionOptions permissionOptions = workspace.request().permissions();
        List<PermissionRule> cliAllowRules = CliToolSelection.permissionRules(
            permissionOptions.allowedTools(), PermissionBehavior.ALLOW);
        List<PermissionRule> cliDenyRules = CliToolSelection.permissionRules(
            permissionOptions.disallowedTools(), PermissionBehavior.DENY);
        permissionGate.addRules(cliAllowRules);
        permissionGate.addRules(cliDenyRules);
        Set<String> wholeToolDenials = new HashSet<>(CliToolSelection.wholeToolDenials(cliDenyRules));
        if (permissionOptions.baseTools() != null) {
            Set<String> currentCatalog = toolRegistry.getAll().stream().map(Tool::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> selected = CliToolSelection.selectedBaseTools(permissionOptions.baseTools(), currentCatalog);
            List<String> excluded = currentCatalog.stream().filter(name -> !selected.contains(name)).toList();
            permissionGate.addRules(CliToolSelection.permissionRules(excluded, PermissionBehavior.DENY));
            wholeToolDenials.addAll(excluded);
        }
        if (!wholeToolDenials.isEmpty()) toolRegistry.unregisterMatching(wholeToolDenials::contains);
        String agent = workspace.request().workspace().agent();
        if (StringUtils.isBlank(agent)) return;
        AgentDefinitionLoader.getActive(System.getProperty("user.dir")).stream()
            .filter(definition -> agent.equals(definition.agentType()))
            .filter(definition -> !definition.tools().isEmpty())
            .findFirst()
            .ifPresent(definition -> {
                Set<String> denied = new HashSet<>(definition.disallowedTools());
                if (definition.tools().contains("*")) {
                    if (!denied.isEmpty()) toolRegistry.unregisterMatching(denied::contains);
                    return;
                }
                Set<String> allowed = new HashSet<>(definition.tools());
                allowed.removeAll(denied);
                toolRegistry.unregisterMatching(name -> !allowed.contains(name));
                toolRegistry.setModelVisibleToolOrder(definition.tools());
            });
    }
}

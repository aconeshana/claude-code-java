package com.claudecode.cli;

import static com.claudecode.core.config.EnvUtils.isEnvDefinedFalsy;
import static com.claudecode.core.config.EnvUtils.isEnvTruthy;

import com.claudecode.api.CreateMessageRequest;
import com.claudecode.api.LlmClient;
import com.claudecode.commands.context.ContextData;
import com.claudecode.commands.context.ContextUsageAnalyzer;
import com.claudecode.core.attachment.AgentListingDeltaAttachmentProvider;
import com.claudecode.core.attachment.AgentMentionAttachmentProvider;
import com.claudecode.core.attachment.AsyncHookResponseAttachmentProvider;
import com.claudecode.core.attachment.AtMentionedFilesProvider;
import com.claudecode.core.attachment.AttachmentService;
import com.claudecode.core.attachment.AutoModeReminderAttachmentProvider;
import com.claudecode.core.attachment.BudgetUsdAttachmentProvider;
import com.claudecode.core.attachment.ChangedFilesProvider;
import com.claudecode.core.attachment.CompactionReminderAttachmentProvider;
import com.claudecode.core.attachment.ContextEfficiencyAttachmentProvider;
import com.claudecode.core.attachment.CriticalSystemReminderProvider;
import com.claudecode.core.attachment.DateChangeAttachmentProvider;
import com.claudecode.core.attachment.DeferredToolsDeltaAttachmentProvider;
import com.claudecode.core.attachment.DynamicSkillAttachmentProvider;
import com.claudecode.core.attachment.FeatureFlag;
import com.claudecode.core.attachment.FeatureFlagRegistry;
import com.claudecode.core.attachment.McpInstructionsDeltaAttachmentProvider;
import com.claudecode.core.attachment.McpResourceAttachmentProvider;
import com.claudecode.core.attachment.OutputStyleAttachmentProvider;
import com.claudecode.core.attachment.OutputTokenUsageAttachmentProvider;
import com.claudecode.core.attachment.PlanModeExitAttachmentProvider;
import com.claudecode.core.attachment.PlanModeExitSignal;
import com.claudecode.core.attachment.PlanModeReminderAttachmentProvider;
import com.claudecode.core.attachment.SkillListingAttachmentProvider;
import com.claudecode.core.attachment.TodoReminderAttachmentProvider;
import com.claudecode.core.attachment.TaskReminderAttachmentProvider;
import com.claudecode.core.attachment.TokenUsageAttachmentProvider;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.message.PlanModeInstructions;
import com.claudecode.runtime.query.AutoDreamEngine;
import com.claudecode.runtime.query.BoundedHeadlessTurnProfiler;
import com.claudecode.runtime.query.FastModeController;
import com.claudecode.runtime.query.HeadlessTurnProfiler;
import com.claudecode.core.engine.HookDispatcher;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.runtime.query.QuerySessionSpec;
import com.claudecode.runtime.query.QuerySessionFactory;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.engine.StreamingClient;
import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.core.message.TodoItem;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.message.Usage;
import com.claudecode.core.message.UsageSnapshot;
import com.claudecode.core.model.ModelNames;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.prompt.McpInstructionEntry;
import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.core.prompt.SystemPromptConstants;
import com.claudecode.core.prompt.SystemPromptConfig;
import com.claudecode.core.prompt.SystemPromptProfileResolver;
import com.claudecode.core.prompt.SystemPromptRuntime;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.services.claudemd.MemoryPromptBuilder;
import com.claudecode.services.claudemd.MemoryType;
import com.claudecode.services.claudemd.NestedMemoryAttachmentProvider;
import com.claudecode.services.compact.CompactService;
import com.claudecode.services.compact.LlmCompactSummarizer;
import com.claudecode.services.config.FileHistorySettings;
import com.claudecode.services.config.GitSettings;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.config.SettingsSnapshots;
import com.claudecode.services.config.SimpleSystemPromptFeatureGate;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.dream.AutoDreamEngineImpl;
import com.claudecode.services.hooks.HookEngine;
import com.claudecode.services.memory.ExtractMemoriesService;
import com.claudecode.services.model.ModelAllowlist;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.services.model.SideQuery;
import com.claudecode.services.outputstyle.OutputStyleService;
import com.claudecode.services.permissions.AutoModeClassifierService;
import com.claudecode.services.summary.ToolUseSummaryGenerator;
import com.claudecode.session.SessionManager;
import com.claudecode.session.SessionStorage;
import com.claudecode.tools.agent.SubAgentModelPolicy;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.bash.BashTool;
import com.claudecode.tools.files.FileReadTool;
import com.claudecode.tools.mcp.McpRuntime;
import com.claudecode.tools.plan.PlanFiles;
import com.claudecode.tools.skills.SkillToolProvider;
import com.claudecode.tools.tasks.TaskNotificationBridge;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskReminderSource;
import com.claudecode.tools.tasks.TodoWriteTool;
import com.claudecode.tools.worktree.WorktreeService;
import com.claudecode.ui.lanterna.repl.LanternaProgressSink;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the query engine configuration, prompt runtime, attachments, and hook bridge.
 */
final class CliEngineAssembler {

    private static final Logger log = LoggerFactory.getLogger(CliEngineAssembler.class);

    private CliEngineAssembler() {}

    record EngineRuntime(
            QuerySession engine,
            QuerySessionSpec config,
            CompactService compactService,
            SideQuery sideQuery,
            QuerySessionFactory querySessionFactory,
            Supplier<ContextData> contextDataCollector,
            OutputStyleService outputStyleService,
            boolean teamMemoryEnabled) {
    }

    static EngineRuntime assemble(
            CliWorkspaceBootstrap.Workspace workspace,
            CliToolchainAssembler.Toolchain toolchain,
            McpRuntime mcpRuntime,
            AtomicReference<CliPluginRuntimeView> pluginRuntimeRef,
            QuerySessionFactory querySessionFactory,
            LanternaProgressSink progressSink,
            CliOutput errorOutput) {
        CliLaunchRequest request = workspace.request();
        CliLaunchRequest.ModelOptions modelOptions = request.model();
        CliLaunchRequest.OutputOptions output = request.output();
        String effort = modelOptions.effort();
        String thinkingMode = modelOptions.thinkingMode();
        Integer maxThinkingTokens = modelOptions.maxThinkingTokens();
        String fallbackModel = modelOptions.fallbackModel();
        String model = workspace.launch().modelPinned() ? workspace.launch().model() : null;
        String resolvedModel = workspace.launch().model();
        String systemPrompt = workspace.launch().systemPrompt();
        String appendSystemPrompt = workspace.launch().appendSystemPrompt();
        PlanModeInstructions.configureCustomWorkflow(modelOptions.planModeInstructions());
        Integer maxTokens = modelOptions.maxTokens();
        // `--max-turns` is wired into runHeadless only ("only works with
        // --print"), so an interactive REPL keeps maxTurns undefined no matter
        // what was passed on the command line.
        int maxTurns = request.mode().headless() ? modelOptions.maxTurns() : 0;
        String inputFormat = output.inputFormat();
        boolean printMode = output.printMode();
        boolean noInteractive = output.noInteractive();
        String worktreeName = request.workspace().worktreeName();
        AutoDreamEngine autoDreamEngineOverride = request.testOverrides().autoDreamEngine();
        // Post-worktree engine inputs (memory, settings, transcript, dynamic
        // model) are rooted at the active directory. The launch directory is
        // retained separately only for the pre-worktree git-status snapshot.
        Path cwdPath = Path.of(workspace.cwd());
        Path launchCwd = workspace.launchCwd();
        SessionIdentity sessionIdentity = workspace.sessionIdentity();
        boolean sdkCliSession = workspace.sdkCliSession();
        double effectiveMaxBudgetUsd = workspace.effectiveMaxBudgetUsd();
        StreamingClient client = toolchain.client();
        LlmClient llmClient = toolchain.llmClient();
        ToolRegistry toolRegistry = toolchain.toolRegistry();
        PermissionGate permissionGate = toolchain.permissionGate();
        SkillToolProvider skillToolProvider = toolchain.skillToolProvider();
        TaskReminderSource taskReminders = toolchain.taskReminders();
        SubAgentModelPolicy subAgentModelPolicy = toolchain.subAgentModelPolicy();
        Set<String> dynamicSkillDirTriggers = toolchain.dynamicSkillDirTriggers();
        HookEngine hookEngine = workspace.hookEngine();
        CliWorkspaceBootstrap.SettingSourceSelection settingSources = workspace.settingSources();

            // CLAUDE.md content supplier — pre-loads via full MemoryFileScanner
            // pipeline (discovery + @include recursion + HTML strip + glob
            // filter) each turn so frontmatter-gated files react to cwd

            // so external edits take effect without restart.
            String cwd = System.getProperty("user.dir");
            Path workingDirPath = Path.of(cwd);
            OutputStyleService outputStyleService = OutputStyleService.standard(() -> {
                CliPluginRuntimeView runtime = pluginRuntimeRef.get();
                return runtime != null ? runtime.currentSnapshot().outputStyles() : List.of();
            });
            // --setting-sources is parsed before initialization. Absent flag =
            // all scopes; an explicit empty value is SDK isolation mode.
            Set<MemoryType> enabledScopes = settingSources.memoryScopes();
            // HookDispatcher isn't constructed until later; use an
            // AtomicReference so the supplier lambda picks up the real
            // engine once it's wired. Null until then — MemoryPromptBuilder
            // treats null as "skip hook fire".
            AtomicReference<HookDispatcher> hookDispatcherRef = new AtomicReference<>();
            // The same late-bound handle also lets eager memory loading seed
            // the session's read-state cache once the engine exists.
            final AtomicReference<QuerySession> engineRef = new AtomicReference<>();
            Supplier<String> claudeMdSupplier = () -> {
                try {
                    var scanner = MemoryFileScanner.forConfigHome(
                        ClaudePaths.CLAUDE_HOME,
                        WorkspaceSettings.loadClaudeMdExcludes(cwd),
                        hookDispatcherRef.get());
                    // Pull the live --add-dir list from the permission gate so
                    // /add-dir at runtime immediately widens the memory scan
                    // (env-gated inside the scanner).
                    List<Path> extraDirs = List.of();
                    if (permissionGate.currentContext() != null) {
                        extraDirs = new ArrayList<>(permissionGate.currentContext().additionalDirs().keySet());
                    }
                    QuerySession liveEngine = engineRef.get();
                    return new MemoryPromptBuilder(scanner).build(
                        workingDirPath, extraDirs, enabledScopes,
                        liveEngine != null ? liveEngine.forks().getFileStateCache() : null);
                } catch (Throwable t) {
                    log.warn("Memory content load failed: {}", t.getMessage());
                    return "";
                }
            };

// Build engine config.

            final Integer cliMaxTokensOverride = maxTokens;

            final FeatureFlagRegistry featureFlags = FeatureFlagRegistry.builder()
                .enableIf(FeatureFlag.AGENT_LISTING_DELTA,
                    effectiveAgentListingDeltaEnabled(
                        SubprocessEnvironment.get("CLAUDE_CODE_AGENT_LIST_IN_MESSAGES")))
                .enableIf(FeatureFlag.MCP_INSTRUCTIONS_DELTA,
                    effectiveMcpInstructionsDeltaEnabled(
                        SubprocessEnvironment.get("CLAUDE_CODE_MCP_INSTR_DELTA"),
                        SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC"),
                        SubprocessEnvironment.get("USER_TYPE")))
                .build();
            ToIntFunction<String> maxTokensResolver = selectedModel -> {
                String requestModel = ModelNames.parseUserSpecifiedModel(selectedModel);
                if (cliMaxTokensOverride != null) {
                    return (int) ModelOutputTokens.resolveMaxOutputTokens(
                        requestModel, cliMaxTokensOverride, featureFlags, true);
                }
                return (int) ModelOutputTokens.getMaxOutputTokensForModel(requestModel, featureFlags);
            };
            int effectiveMaxTokens = maxTokensResolver.applyAsInt(resolvedModel);

// Dynamic system-prompt inputs — gathered fresh per query so settings edits (language /
// outputStyle) and late MCP connections are reflected on the next turn.
            final var promptSkillLoader = skillToolProvider.getSkillLoader();
            final PermissionGate promptGate = permissionGate;
            // Auto-memory dir — resolved+created once at startup, which makes
            // the selected profile's memory section promise that "This
            // directory already exists". Null on failure → section omitted.
            Path promptMemoryDirTmp = null;
            try {
                promptMemoryDirTmp = AutoMemoryPrompt.ensureAutoMemDir(workingDirPath);
            } catch (Throwable _) { }
            final Path promptMemoryDir = promptMemoryDirTmp;
            final List<String> simpleSystemPromptModelPatterns =
                SimpleSystemPromptFeatureGate.modelPatterns();
            Supplier<SystemPromptRuntime> promptRuntimeSupplier = () -> {
                String language = null;
                String styleName = null;
                OutputStyleConfig outputStyle = null;
                boolean hasSkills = false;
                try { language = RuntimeSettings.loadLanguage(); } catch (Throwable _) { }
                try { styleName = RuntimeSettings.loadOutputStyleName(); } catch (Throwable _) { }
                try {
                    outputStyle = outputStyleService.resolve(
                        Path.of(System.getProperty("user.dir")), styleName);
                }
                catch (Throwable _) { }
                try { hasSkills = !promptSkillLoader.loadAll().isEmpty(); } catch (Throwable _) { }
                List<McpInstructionEntry> mcpInstr = new ArrayList<>();
                try {
                    // Sorted for byte-stable prompt output — a shuffled order
                    // would bust the API prompt cache every turn.
                    mcpRuntime.clientRuntime().getServerInstructions().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> mcpInstr.add(new McpInstructionEntry(
                            entry.getKey(), entry.getValue())));
                } catch (Throwable _) { }
                List<String> addDirs = List.of();
                try {
                    if (promptGate.currentContext() != null) {
                        addDirs = promptGate.currentContext().additionalDirs().keySet().stream()
                            .map(Path::toString).toList();
                    }
                } catch (Throwable _) { }
                String agentListing = null;
                try {
                    agentListing = CliPromptInventoryAssembler.insertMcpInstructions(
                        CliPromptInventoryAssembler.buildAgentListingMessage(
                            promptSkillLoader, resolvedModel,
                            agent -> subAgentModelPolicy.resolveAgent(
                                agent, null, resolvedModel).outcome()
                                != SubAgentModelPolicy.Outcome.REJECT), mcpInstr);
                }
                catch (Throwable _) { }
                return new SystemPromptRuntime(
                    language, hasSkills,
                    outputStyle,

                    sdkCliSession, List.of(), addDirs,
                    WorktreeService.getCurrentWorktreeSession() != null,
                    promptMemoryDir, agentListing, simpleSystemPromptModelPatterns);
            };

            // @Explanation extractMemoriesEnabled (default false) — background
            // memory extraction, see RuntimeSettings#loadExtractMemoriesEnabled. Held in
            // a local so the drain-on-shutdown call below can reach it without a cast.
            ExtractMemoriesService memoryExtractor = RuntimeSettings.loadExtractMemoriesEnabled()
                ? new ExtractMemoriesService(client, toolRegistry, querySessionFactory) : null;

            // @Explanation teamMemoryEnabled (default false) — local
            // team-memory secret-write guard, see RuntimeSettings#loadTeamMemoryEnabled.
            // Held in a local so the config builder below can capture it (the
// guard is session-stable, read once at engine build, matching
            // memoryExtractor above).
            boolean teamMemoryEnabled = RuntimeSettings.loadTeamMemoryEnabled();



            // by WorkspaceSettings#loadAutoMemoryDirectory (security).
            AutoMemoryPrompt.setAutoMemoryDirectory(WorkspaceSettings.loadAutoMemoryDirectory());

// Attachment system — per-turn maybe(name, fn) dispatch matching


            // nested_memory scanner deliberately uses a null HookDispatcher:
// NestedMemoryAttachmentProvider.add fires INSTRUCTIONS_LOADED
            // itself via the late-bound supplier below, so the scanner must
            // not double-fire on its own cache-miss path.
            MemoryFileScanner nestedScanner = MemoryFileScanner.forConfigHome(
                ClaudePaths.CLAUDE_HOME, WorkspaceSettings.loadClaudeMdExcludes(cwd), null);
            // Late-bound engine handle for the per-turn usage suppliers (token/
            // budget/output-token usage read live engine totals, but the engine
            // is constructed only after this config is built).
            AttachmentService attachmentService = new AttachmentService(List.of(
                new AtMentionedFilesProvider(),
                // Tier-1: mcp_resources (fires on @server:uri mentions)
                new McpResourceAttachmentProvider(),
                // Tier-1: agent_mentions (fires on @agent-<type> mentions)
                new AgentMentionAttachmentProvider(),
                new NestedMemoryAttachmentProvider(nestedScanner, hookDispatcherRef::get),
                new DateChangeAttachmentProvider(),
                new ChangedFilesProvider(),
                new CriticalSystemReminderProvider(),

                // so they emit nothing in prod. When a flag is enabled the
                // per-turn suppliers below feed them live state.
                new DeferredToolsDeltaAttachmentProvider(),
                new AgentListingDeltaAttachmentProvider(),
                new McpInstructionsDeltaAttachmentProvider(),
                new CompactionReminderAttachmentProvider(),
                new ContextEfficiencyAttachmentProvider(),
                // Tier-1: dynamic_skill (one-shot dirs from Read/Write/Edit)
                new DynamicSkillAttachmentProvider(),
                // Tier-1: skill_listing (delta-based, announces new skills)
                new SkillListingAttachmentProvider(),

                new PlanModeReminderAttachmentProvider(
                    () -> permissionGate.currentMode().kind(),
                    () -> PlanFiles.activatePlan(sessionIdentity.get(), null),
                    () -> permissionGate.consumePlanModeReentry(true)),
                // Tier-1: plan_mode_exit (one-shot, set by ExitPlanModeTool)
                new PlanModeExitAttachmentProvider(),

                new AutoModeReminderAttachmentProvider(
                    () -> permissionGate.currentMode().kind()),
                // Tier-1: todo_reminders (timed nudge)
                new TodoReminderAttachmentProvider(),
                new TaskReminderAttachmentProvider(() -> {
                    try {
                        return taskReminders.currentReminders();
                    } catch (Throwable _) {
                        return List.of();
                    }
                }),
                // Tier-1: output_style (suppressed when "default"/unset)
                new OutputStyleAttachmentProvider(),
                // Tier-1: token_usage / budget_usd / output_token_usage (main
                // thread only, read live engine totals via engineRef)
                new TokenUsageAttachmentProvider(isEnvTruthy(SubprocessEnvironment.get(
                    "CLAUDE_CODE_ENABLE_TOKEN_USAGE_ATTACHMENT"))),
                new BudgetUsdAttachmentProvider(),
                new OutputTokenUsageAttachmentProvider(),
// Tier-2→implemented: async_hook_responses — re-injects completed
                // background (output-driven / config-async) hook results as
                // attachments, fed by HookDispatcher.checkForAsyncHookResponses.
                new AsyncHookResponseAttachmentProvider()),
                featureFlags);

            // Structured SDK sessions need the live resolver so clearing an
            // apply_flag_settings model override can fall back through the
            // current env/settings chain. Interactive sessions intentionally

            // AppState.mainLoopModel, so another process changing user settings
            // must not switch an already-running session.
            final boolean startupModelOverride = model != null;
            QuerySessionSpec.Builder configBuilder = QuerySessionSpec.builder()
                .llmClient(client)
                .model(resolvedModel)
                .modelPreference(workspace.launch().modelPreference())
                .modelAllowed(ModelAllowlist::isAllowed)
                .customModel(name -> toolchain.customModelCatalog().find(name).isPresent())
                .dynamicEffortSettingSupplier(RuntimeSettings::loadEffortLevel)
                .systemPrompt(systemPrompt)  // null → QuerySession assembles via SystemPromptService
                .appendSystemPrompt(appendSystemPrompt)
                .maxTokens(effectiveMaxTokens)
                .maxTokensResolver(maxTokensResolver)
                .maxTokensExplicit(cliMaxTokensOverride != null)
                .featureFlags(featureFlags)
                .maxTurns(maxTurns)
                .maxBudgetUsd(effectiveMaxBudgetUsd)
                .taskBudgetTokens(request.model().taskBudget())
                .toolExecutor(toolRegistry)
                .tools(toolRegistry.getAll().stream().map(Tool::name).toList())
                .claudeMdContentSupplier(claudeMdSupplier)
                .promptRuntimeSupplier(promptRuntimeSupplier)
                .includeGitInstructionsSupplier(GitSettings::shouldIncludeGitInstructions)

                .gitStatusWorkingDirectory(worktreeName != null
                    ? launchCwd.toString() : cwd)
                .progressSink(progressSink)
                .headlessTurnProfiler(headlessProfiler(printMode || noInteractive))
                .fastModeController(fastModeController(
                    client, Strings.CS.equals("stream-json", inputFormat)))
                .sessionIdentity(sessionIdentity)
                .planSlugInitializer(sid -> PlanFiles.getPlanFilePath(sid, null))
// Disk-first transcript loader for orphaned-permission recovery.
                .transcriptLoader(sid -> {
                    try {
                        Path file = new SessionManager(cwd).getSessionFile(sid);
                        return new SessionStorage().readMessages(file);
                    } catch (Throwable _) {
                        return List.of();
                    }
                })
                .fileHistoryEnabled(request.session().rewindFiles() != null
                    ? FileHistorySettings.isEnabled()
                    : FileHistorySettings.isEnabled(
                        printMode || noInteractive || Strings.CS.equals("stream-json", inputFormat)))
                // Haiku-backed tool-batch summary generator (env CLAUDE_CODE_EMIT_TOOL_USE_SUMMARIES
                // gates actual firing in QueryLoop; wiring it here is cheap regardless).
                .toolBatchSummarizer(new ToolUseSummaryGenerator(llmClient))
                .memoryExtractor(memoryExtractor)
                .autoDreamEngine(autoDreamEngineOverride != null
                    ? autoDreamEngineOverride
                    : new AutoDreamEngineImpl(client, toolRegistry, querySessionFactory))
                .attachmentService(attachmentService)
                // Per-turn suppliers feeding the Phase-2 delta attachments.
// Re-evaluated every turn (matches claudeMdContentSupplier) so
                // /agents edits and MCP connect/disconnect show up without a
                // restart. Both delta providers are feature-gated OFF by
                // default, so an empty pool/map simply yields no attachment.
                .activeAgentsSupplier(() -> {
                    try {
                        return AgentDefinitionLoader.getActive(cwd).stream()
                            .filter(agent -> subAgentModelPolicy.resolveAgent(
                                agent, null, resolvedModel).outcome()
                                != SubAgentModelPolicy.Outcome.REJECT)
                            .toList();
                    } catch (Throwable _) {
                        return List.of();
                    }
                })
                .mcpServerInstructionsSupplier(() -> {
                    try {
                        return mcpRuntime.clientRuntime().getServerInstructions();
                    } catch (Throwable _) {
                        return Map.of();
                    }
                })
                // ── Tier-1 attachment suppliers (re-evaluated every turn) ──

                // configured value, even when it cannot be resolved. Rendering
                // performs the separate exact-key lookup; e.g. `explanatory`
                // remains in JSONL but produces no wire reminder.
                .outputStyleSupplier(() -> {
                    try {
                        String selected = RuntimeSettings.loadOutputStyleName();
                        if (StringUtils.isEmpty(selected)
                                || Strings.CS.equals("default", selected)) {
                            return null;
                        }
                        return selected;
                    } catch (Throwable _) {
                        return null;
                    }
                })
// todo_reminder: current legacy TodoWrite list.
                .todosSupplier(() -> {
                    try {
                        return TodoWriteTool.getTodos(sessionIdentity.get()).stream()
                            .map(todo -> new TodoItem(todo.status(), todo.content()))
                            .toList();
                    } catch (Throwable _) {
                        return List.of();
                    }
                })
                // plan_mode_exit: one-shot signal consumed after ExitPlanModeTool.
                .planModeExitSupplier(PlanModeExitSignal::consume)
                // dynamic_skill: one-shot dirs populated by Read/Write/Edit.
                .dynamicSkillDirTriggersSupplier(() -> dynamicSkillDirTriggers)
                // skill_listing: all loaded skills mapped to listing entries.
                .skillListingSupplier(() -> {
                    try {
                        QuerySession liveEngine = engineRef.get();
                        String currentModel = liveEngine != null
                            ? liveEngine.configuration().getConfig().model() : resolvedModel;
                        return CliPromptInventoryAssembler.skillListingEntries(
                            promptSkillLoader.loadAll(), currentModel);
                    } catch (Throwable _) {
                        return List.of();
                    }
                })
                // mcp_resource: reads a resource's text via resources/read.
                .mcpResourceReader((server, uri) -> {
                    try {
                        JsonNode result = mcpRuntime.clientRuntime().readResource(server, uri);
                        JsonNode contents = result.get("contents");
                        if (contents != null && contents.isArray() && !contents.isEmpty()) {
                            JsonNode first = contents.get(0);
                            JsonNode text = first.get("text");
                            return text != null ? text.asText() : first.toString();
                        }
                        return null;
                    } catch (Throwable _) {
                        return null;
                    }
                })
                // token_usage / budget_usd / output_token_usage: live engine totals.
                .usageSupplier(() -> {
                    if (!isEnvTruthy(SubprocessEnvironment.get(
                            "CLAUDE_CODE_ENABLE_TOKEN_USAGE_ATTACHMENT"))
                            && effectiveMaxBudgetUsd <= 0) {
                        return null;
                    }
                    QuerySession engine = engineRef.get();
                    if (engine == null) {
                        return null;
                    }
                    try {
                        Usage total = engine.execution().getTotalUsage();
                        long tokenUsed = total.inputTokens() + total.outputTokens();
                        long tokenTotal = 200_000L;
                        long tokenRemaining = Math.max(0, tokenTotal - tokenUsed);
                        double budgetUsed = engine.execution().getCostCalculator().calculateCost(total);
                        double budgetTotal = effectiveMaxBudgetUsd > 0
                            ? effectiveMaxBudgetUsd : 0;
                        double budgetRemaining = Math.max(0, budgetTotal - budgetUsed);
                        return new UsageSnapshot(
                            tokenUsed, tokenTotal, tokenRemaining,
                            budgetUsed, budgetTotal, budgetRemaining,
                            total.outputTokens(), null, total.outputTokens());
                    } catch (Throwable _) {
                        return null;
                    }
                })
                // Team-memory secret-write guard: blocks Write/Edit into the
                // team-memory directory when content contains secrets. Default
                // off (teamMemoryEnabled settings key, @Explanation).
                .teamMemoryEnabledSupplier(() -> teamMemoryEnabled)
                .sandboxConfigSupplier(SandboxSettings::loadSandboxConfig)
// File-read deny rules → GlobTool exclusion mask.
                .readDenyIgnorePatternsSupplier(permissionGate::getFileReadIgnorePatterns)
                // Changed-file attachments re-check the live Read deny rules before disk access.
                .fileReadDeniedPredicate(permissionGate::isFileReadDenied);
            if (sdkCliSession && !startupModelOverride) {
                configBuilder.dynamicModelSupplier(
                    () -> resolveDynamicModel(cwdPath.toString(), resolvedModel));
                configBuilder.dynamicModelPreferenceSupplier(
                    () -> resolveDynamicModelPreference(cwdPath.toString()));
            }
            QuerySessionSpec config = configBuilder.build();
            // Surface (or refuse to start on) a misconfigured sandbox at launch —
// matches  (#34044): an explicitly enabled sandbox whose
            // backend is missing must warn (or hard-fail) once, not reject each
            // command later.
            CliHeadlessOutput.validateSandboxAtStartup(errorOutput);
            // --fallback-model: overloaded primary → FallbackTriggeredError →

            config.setFallbackModel(fallbackModel);

            // Permission gate: install before constructing the engine so tool
            // execution paths consult it. Already created above so Plan-mode
            // tools could capture it; we keep the variable for the rest of the
            // wiring (UI + memory + remote, below).

// Compact service: implements MessageCompactor — drives microcompact (every turn),
// shouldAutoCompact (at 93% context window), and manual /compact.
            boolean autoCompactEnabled = loadAutoCompactEnabled();
            CompactService compactService = new CompactService(
                TokenEstimator.getInstance(),
                new LlmCompactSummarizer(client, engineRef::get),
                autoCompactEnabled);
            compactService.setReactiveCompactEnabled(
                RuntimeSettings.loadReactiveCompactEnabled());
            compactService.setCustomModelContextWindowResolver(
                toolchain.customModelCatalog()::contextWindow);
            // opusplan runtime resolution needs the live permission mode

            // the per-request model resolver.
            config.setPermissionModeSupplier(() -> permissionGate.currentMode().kind());
            // Explicit CLI permission intent has higher precedence than the
            // restored transcript's cached mode. In particular,
            // --dangerously-skip-permissions must remain bypassPermissions when
            // resuming a transcript whose previous invocation ended in default.
            boolean permissionModePinned = request.permissions().dangerouslySkipPermissions()
                || (StringUtils.isNotBlank(request.permissions().permissionMode()));
            if (!permissionModePinned) {
                config.setPermissionModeRestorer(permissionGate::setMode);
            }
            QuerySession engine = querySessionFactory.create(
                config.attachMessageCompactor(compactService));
            // Bind the engine into the late-bound handle so the per-turn usage
            // suppliers (token/budget/output-token usage) can read live totals.
            engineRef.set(engine);
            toolRegistry.get("Bash")
                .filter(BashTool.class::isInstance)
                .map(BashTool.class::cast)
                .ifPresent(bash -> bash.setModelSupplier(engine.configuration().getConfig()::model));
            toolRegistry.get("Read")
                .filter(FileReadTool.class::isInstance)
                .map(FileReadTool.class::cast)
                .ifPresent(read -> read.setModelSupplier(engine.configuration().getConfig()::model));
            toolRegistry.get("TodoWrite")
                .filter(TodoWriteTool.class::isInstance)
                .map(TodoWriteTool.class::cast)
                .ifPresent(todoWrite -> todoWrite.setSimplePromptSupplier(() ->
                    SystemPromptProfileResolver.resolve(SystemPromptConfig.builder()
                        .modelId(engine.configuration().getConfig().model())
                        .apiProvider(client.provider())
                        .simpleSystemPromptModelPatterns(simpleSystemPromptModelPatterns)
                        .build()) == SystemPromptProfileResolver.Profile.HARNESS));
            // Post-compact file re-attachment needs the live FileStateCache, which
            // only exists once QuerySession is built — compactService is constructed
            // above, so wire it in with a late-bound supplier instead of a
// constructor argument (matches setPermissionModeSupplier above).
            compactService.setFileStateCacheSupplier(engine.forks()::getFileStateCache);
            compactService.setSessionIdentity(sessionIdentity);
            compactService.setTranscriptPathSupplier(() ->
                new SessionManager(cwd).getSessionFile(engine.conversation().getSessionId()).toString());
            compactService.setAgentListingSupplier(() -> {
                SystemPromptRuntime runtime = promptRuntimeSupplier.get();
                return runtime != null ? runtime.agentListingMessage() : null;
            });
            compactService.setMcpInstructionsSupplier(() -> {
                try {
                    return mcpRuntime.clientRuntime().getServerInstructions();
                } catch (Throwable _) {
                    return Map.of();
                }
            });
            compactService.setToolNamesSupplier(
                () -> engine.configuration().getConfig().tools());
            // ToolSearch's schema-not-sent hint needs live conversation history to
            // tell a deferred-and-undiscovered tool apart from a genuinely broken
            // one — same late-bound-supplier pattern as compactService above (engine
            // doesn't exist yet when toolRegistry is built).
            toolRegistry.setMessagesSupplier(() -> engine.conversation().getMessages());
            // Install side-query services before any headless early return:

            // -p/stream-json sessions as well as the interactive TTY.
            SideQuery sideQuery = llmClient != null ? new SideQuery(llmClient) : null;
            log.info("[goal-diag] CliEngineAssembler: sideQuery={} (llmClient={})",
                sideQuery != null ? "NON-NULL" : "null",
                llmClient != null ? "NON-NULL" : "null");
            hookEngine.setSideQuery(sideQuery);
            hookEngine.setLlmModelSupplier(() ->
                engine.configuration().getConfig().model());
            hookEngine.setGoalSystemPromptIdentitySupplier(() ->
                SystemPromptConstants.AGENT_SDK_SYSPROMPT_PREFIX);
            hookEngine.setGoalMetadataSupplier(() ->
                LlmClientAdapter.requestMetadata(engine.conversation().getSessionId()));
            hookEngine.setGoalEffortSupplier(() -> effort != null
                ? effort
                : engine.configuration().getConfig().effortValue() != null
                    ? engine.configuration().getConfig().effortValue()
                    : RuntimeSettings.loadEffortLevel());
            hookEngine.setGoalToolsSupplier(() -> goalToolDefinitions(
                toolRegistry, engine));
            toolRegistry.setAutoModeClassifier(sideQuery != null
                ? new AutoModeClassifierService(sideQuery, LlmClientAdapter::requestMetadata)
                : null);
// Resolve settings, hidden CLI flags and MAX_THINKING_TOKENS with the
// exact.
            configureInitialThinking(engine.configuration().getConfig(), thinkingMode, maxThinkingTokens);
// A refused turn only asks the user first when this is turned off.
            engine.configuration().getConfig().setSwitchModelsOnFlag(
                RuntimeSettings.loadSwitchModelsOnFlagEnabled());
            // Seed the session effort before the first request. Previously the
            // setting only became live after /effort was used interactively,
            // so headless gateway comparisons silently omitted output_config.
            engine.configuration().getConfig().setEffortValue(
                effort != null ? effort : RuntimeSettings.loadEffortLevel());
            // Observe the persisted value after seeding the session/CLI effort.
            // Without this, the first request's live settings refresh would
            // mistake the initial settings value for a post-start change and
            // overwrite an explicit --effort flag.
            engine.configuration().getConfig().initializeDynamicEffortObservation();

            // /context usage analyzer — same data sources the query path uses:
            // live tool registry, base system prompt (no CLAUDE.md — memory
            // files are counted per-file), the claudeMdSupplier's scan
            // pipeline, skill loader, agent loader, microcompact.
            final LlmClient contextUsageLlmClient = llmClient;
            var contextAnalyzer = new ContextUsageAnalyzer(
                new ContextUsageAnalyzer.Sources(
                    toolRegistry::getContextAnalysisToolDefinitions,
                    () -> engine.configuration().assembleSystemPrompt(null),
                    () -> engine.configuration().assembleSystemPromptParts(null),
                    () -> {
                        var scanner = MemoryFileScanner.forConfigHome(
                            ClaudePaths.CLAUDE_HOME,
                            WorkspaceSettings.loadClaudeMdExcludes(cwd),
                            hookDispatcherRef.get());
                        List<Path> extraDirs = List.of();
                        if (permissionGate.currentContext() != null) {
                            extraDirs = new ArrayList<>(permissionGate.currentContext().additionalDirs().keySet());
                        }
                        return scanner.scan(cwdPath, extraDirs, enabledScopes).stream()
                            .map(file -> new ContextUsageAnalyzer.MemoryDocument(
                                file.path(), file.type() == null ? null
                                    : ContextUsageAnalyzer.MemoryScope.valueOf(file.type().name()),
                                file.content()))
                            .toList();
                    },
                    () -> skillToolProvider.getSkillLoader().loadAll().stream()
                        .map(skill -> new ContextUsageAnalyzer.SkillDescriptor(
                            skill.name(), skill.description(),
                            ContextUsageAnalyzer.SkillDescriptor.Source.valueOf(
                                skill.source().name())))
                        .toList(),
                    AgentDefinitionLoader::getActive,
                    () -> compactService,
                    compactService::isAutoCompactEnabled,
                    contextUsageLlmClient != null
                        ? (candidateModel, messages, tools) ->
                            LlmClientAdapter.countTokens(
                                contextUsageLlmClient, candidateModel, messages, tools)
                        : null,
                    contextUsageLlmClient != null
                        ? (candidateModel, messages, tools) ->
                            LlmClientAdapter.countTokensFallback(
                                contextUsageLlmClient, candidateModel, messages, tools,
                                engine.conversation().getSessionId())
                        : null,
                    cwd,
                    toolchain.customModelCatalog()::contextWindow));
            Supplier<ContextData> contextDataCollector =
                () -> contextAnalyzer.analyze(engine.conversation().getMessages(), config.model());

// Hook engine: implements HookDispatcher — drives lifecycle hooks (SessionStart,
// UserPromptSubmit, PreToolUse, PostToolUse, Stop).

            hookEngine.setMessageQueue(engine.conversation().getMessageQueue());
            hookEngine.setPermissionModeSupplier(
                () -> permissionGate.currentMode().external());
            // Stop and StopFailure hooks read the final assistant text from the
            // live conversation instead of reopening the transcript.
            hookEngine.setMessagesSupplier(() -> engine.conversation().getMessages());
            // Headless sessions do not construct LanternaReplScreen, so wire the
            // same background-task queue bridge here. Interactive sessions keep
            // their existing UI composition-root registration to avoid duplicate
            // completion listeners.
            if (sdkCliSession) {
                TaskRegistry.global().setMessageQueue(engine.conversation().getMessageQueue());
                new TaskNotificationBridge(engine.conversation().getMessageQueue()).register();
            }
            // Publish the hook dispatcher to the memory content supplier so
            // InstructionsLoaded hooks fire on every per-turn memory scan.
            hookDispatcherRef.set(hookEngine);
            // hookEngine shares `sessionIdentity` with `engine` (both built
            // above from the same instance) — session_id in hook JSON input
            // stays correct across /resume, /branch etc. with no separate
            // sync call needed here.
            // Live conversation view — lets Stop/StopFailure hook inputs carry

        return new EngineRuntime(
            engine, config, compactService, sideQuery, querySessionFactory, contextDataCollector,
            outputStyleService, teamMemoryEnabled);
    }

    private static boolean loadAutoCompactEnabled() {
        return RuntimeSettings.loadAutoCompactEnabled();
    }

    private static List<CreateMessageRequest.ToolDefinition> goalToolDefinitions(
            ToolRegistry toolRegistry, QuerySession engine) {
        QuerySessionSpec config = engine.configuration().getConfig();
        String model = config.model();
        ToolExecutionContext promptContext = ToolExecutionContext.builder(
                engine.execution().getAbortController(),
                engine.conversation().getSessionId())
            .workingDirectory(config.workingDirectory())
            .currentModel(model)
            .enabledTools(config.tools())
            .build();
        return toolRegistry.getToolDefinitions(promptContext).stream()
            .map(tool -> tool.type() != null
                ? CreateMessageRequest.ToolDefinition.serverTool(
                    tool.type(), tool.name(), tool.maxUses(),
                    tool.allowedDomains(), tool.blockedDomains())
                : new CreateMessageRequest.ToolDefinition(
                    tool.name(), tool.description(),
                    tool.inputSchema() instanceof JsonNode node ? node : null,
                    null, null, null, null, null,
                    tool.deferLoading() ? Boolean.TRUE : null,
                    CreateMessageRequest.strictToolEnabled(model, tool.strict())
                        ? Boolean.TRUE : null,
                    tool.eagerInputStreaming() ? Boolean.TRUE : null))
            .toList();
    }


    static boolean effectiveAgentListingDeltaEnabled(String value) {
        if (isEnvTruthy(value)) return true;
        return !isEnvDefinedFalsy(value);
    }

    static boolean effectiveMcpInstructionsDeltaEnabled(String value) {
        return effectiveMcpInstructionsDeltaEnabled(value, null, null);
    }


    static boolean effectiveMcpInstructionsDeltaEnabled(
            String value, String disableNonessentialTraffic, String userType) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (Strings.CS.equals(normalized, "1") || Strings.CS.equals(normalized, "true")
                    || Strings.CS.equals(normalized, "yes") || Strings.CS.equals(normalized, "on")) {
                return true;
            }
            return !Strings.CS.equals(normalized, "0") && !Strings.CS.equals(normalized, "false")
                && !Strings.CS.equals(normalized, "no") && !Strings.CS.equals(normalized, "off");
        }
        if (Strings.CS.equals("ant", userType)) return true;
        return !isEnvTruthy(disableNonessentialTraffic);
    }

    static String resolveDynamicModel(String cwd, String fallback) {
        try {
            String environmentModel = SubprocessEnvironment.get("ANTHROPIC_MODEL");
            JsonNode settingsNode = SettingsSnapshots.effective(cwd).get("model");
            String settingsModel = settingsNode != null && settingsNode.isTextual()
                ? settingsNode.asText() : null;
            String resolved = CliWorkspaceBootstrap.resolveLaunchModel(
                null, environmentModel, settingsModel);
            if ((StringUtils.isNotBlank(environmentModel))
                    || (StringUtils.isNotBlank(settingsModel))) {
                if (!ModelAllowlist.isAllowed(resolved)) {
                    resolved = ModelNames.defaultMainLoopModel();
                }
            }
            return StringUtils.isNotBlank(resolved) ? resolved : fallback;
        } catch (RuntimeException _) {
            return fallback;
        }
    }

    static String resolveDynamicModelPreference(String cwd) {
        try {
            String environmentModel = SubprocessEnvironment.get("ANTHROPIC_MODEL");
            JsonNode settingsNode = SettingsSnapshots.effective(cwd).get("model");
            String settingsModel = settingsNode != null && settingsNode.isTextual()
                ? settingsNode.asText() : null;
            String preference = CliWorkspaceBootstrap.resolveLaunchModelPreference(
                null, environmentModel, settingsModel);
            return preference == null || ModelAllowlist.isAllowed(preference)
                ? preference : null;
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static FastModeController fastModeController(
            StreamingClient client, boolean sdkControlSession) {
        boolean available = client != null
            && Strings.CS.equals("firstParty", client.provider())
            && !isEnvTruthy(SubprocessEnvironment.get("CLAUDE_CODE_DISABLE_FAST_MODE"));
        boolean enabled = !sdkControlSession
            && RuntimeSettings.loadFastModeEnabled()
            && !RuntimeSettings.loadFastModePerSessionOptIn();
        return new FastModeController(
            available, enabled, System::currentTimeMillis, RuntimeSettings::saveFastModeEnabled);
    }

    private static HeadlessTurnProfiler headlessProfiler(boolean nonInteractive) {
        if (!nonInteractive || !isEnvTruthy(
                SubprocessEnvironment.get("CLAUDE_CODE_PROFILE_STARTUP"))) {
            return HeadlessTurnProfiler.NOOP;
        }
        String entrypoint = SubprocessEnvironment.get("CLAUDE_CODE_ENTRYPOINT");
        return new BoundedHeadlessTurnProfiler(System::currentTimeMillis,
            metrics -> log.info("[headlessProfiler] {}", metrics), entrypoint);
    }

    private static void configureInitialThinking(
            QuerySessionSpec config, String thinkingMode, Integer maxThinkingTokens) {
        config.setThinkingEnabled(RuntimeSettings.loadAlwaysThinkingEnabled());
        if (Strings.CS.equals("enabled", thinkingMode) || Strings.CS.equals("adaptive", thinkingMode)) {
            config.setThinkingEnabled(true);
            return;
        }
        if (Strings.CS.equals("disabled", thinkingMode)) {
            config.setThinkingEnabled(false);
            return;
        }
        Integer selected = maxThinkingTokens;
        String envBudget = SubprocessEnvironment.get("MAX_THINKING_TOKENS");
        if (StringUtils.isNotBlank(envBudget)) {
            try {
                selected = Integer.parseInt(envBudget.trim());
            } catch (NumberFormatException _) {
                return;
            }
        }
        if (selected != null && (selected > 0 || selected == 0)) {
            config.setThinkingBudgetTokens(selected);
        }
    }
}

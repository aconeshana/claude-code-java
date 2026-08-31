package com.claudecode.cli;

import com.claudecode.commands.StatusProperty;
import com.claudecode.api.ApiConfig;
import com.claudecode.commands.impl.context.GoalCommand;
import com.claudecode.commands.plugins.PluginRuntimePort;
import org.apache.commons.lang3.StringUtils;
import com.claudecode.runtime.query.QuerySession;
import com.claudecode.core.config.EnvUtils;
import com.claudecode.core.engine.PermissionUpdate;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionEngine;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.runtime.compact.CompactWarningProvider;
import com.claudecode.runtime.doctor.DoctorPort;
import com.claudecode.runtime.doctor.DoctorReport;
import com.claudecode.runtime.hooks.HookConfigurationPort;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEntry;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEvent;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookEventMetadata;
import com.claudecode.runtime.hooks.HookConfigurationSnapshot.HookKind;
import com.claudecode.runtime.memory.MemoryCatalog;
import com.claudecode.runtime.shutdown.ShutdownPort;
import com.claudecode.runtime.startup.StartupTrustPort;
import com.claudecode.runtime.statusline.StatusLinePort;
import com.claudecode.runtime.turn.TurnAwakeGuard;
import com.claudecode.commands.dream.DreamPort;
import com.claudecode.core.memdir.AutoMemoryPrompt;
import com.claudecode.services.claudemd.AgentMemory;
import com.claudecode.services.claudemd.AutoMemory;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.services.compact.CompactService;
import com.claudecode.services.config.GlobalConfigStore;
import com.claudecode.services.config.HookSettings;
import com.claudecode.services.config.ManagedEnvironmentApplier;
import com.claudecode.services.config.PermissionSettings;
import com.claudecode.services.config.RuntimeSettings;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.config.SettingsReloadOrchestrator;
import com.claudecode.services.config.SettingsEditor;
import com.claudecode.services.config.TrustConfigStore;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.doctor.DiagnosticReport;
import com.claudecode.services.doctor.DoctorDiagnosticsCollector;
import com.claudecode.services.doctor.McpDiagnosticsFormatter;
import com.claudecode.services.dream.DreamLock;
import com.claudecode.services.dream.ConsolidationPromptGenerator;
import com.claudecode.services.hooks.*;
import com.claudecode.services.shutdown.GracefulShutdown;
import com.claudecode.services.statusline.StatusLineConfig;
import com.claudecode.services.statusline.StatusLineExecutor;
import com.claudecode.services.system.SleepPreventer;
import com.claudecode.session.SessionManager;
import com.claudecode.session.TranscriptRecorder;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.tasks.TeamMemPaths;
import com.claudecode.tools.ToolRegistry;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
import com.claudecode.core.config.ClaudePaths;
import com.claudecode.core.process.SubprocessEnvironment;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.Optional;

/**
 * Service-to-runtime/UI adapters created by the CLI composition root.
 */
final class CliRuntimeAdapters {

    private CliRuntimeAdapters() {}

    static MemoryCatalog newMemoryCatalog(String cwd) {
        MemoryFileScanner scanner = MemoryFileScanner.forConfigHome(
            ClaudePaths.CLAUDE_HOME, WorkspaceSettings.loadClaudeMdExcludes(cwd), null);
        return new MemoryCatalog() {
            @Override
            public List<MemoryCatalog.File> scan(Path workingDirectory) {
                return scanner.scan(workingDirectory).stream()
                    .map(file -> new MemoryCatalog.File(
                        file.path(), MemoryCatalog.Scope.valueOf(file.type().name()), file.parent()))
                    .toList();
            }

            @Override public void clearCache() { scanner.clearCache(); }
            @Override public boolean autoMemoryEnabled() { return AutoMemory.isEnabled(); }
            @Override public void setAutoMemoryEnabled(boolean enabled) {
                RuntimeSettings.saveAutoMemoryEnabled(enabled);
            }
            @Override public Path autoMemoryDirectory(Path workingDirectory) {
                return Path.of(AutoMemory.autoMemoryPath(workingDirectory));
            }
            @Override public boolean autoDreamEnabled() {
                return RuntimeSettings.isAutoDreamEnabled();
            }
            @Override public void setAutoDreamEnabled(boolean enabled) {
                RuntimeSettings.saveAutoDreamEnabled(enabled);
            }
            @Override public boolean autoDreamRunning() {
                return TaskRegistry.global().store().list().stream()
                    .anyMatch(task -> task.type() == TaskType.DREAM
                        && task.status() == TaskStatus.RUNNING);
            }
            @Override public long lastDreamAtMillis(Path workingDirectory) {
                Path memoryRoot = Path.of(AutoMemory.autoMemoryPath(workingDirectory));
                return new DreamLock(memoryRoot).readLastConsolidatedAt();
            }
            @Override public boolean teamMemoryEnabled() {
                return RuntimeSettings.loadTeamMemoryEnabled();
            }
            @Override public Path teamMemoryDirectory(Path workingDirectory) {
                return Path.of(TeamMemPaths.getTeamMemPath(workingDirectory.toString()));
            }
            @Override
            public Path agentMemoryDirectory(String agentType, String memoryScope,
                                             Path workingDirectory) {
                return AgentMemory.getMemoryDir(
                    agentType, memoryScope, workingDirectory, ClaudePaths.CLAUDE_HOME);
            }
        };
    }

    static DoctorPort newDoctorPort(PermissionGate permissionGate,
                                    ToolRegistry toolRegistry, String cwd,
                                    CliPluginRuntimeView runtime) {
        Path workingDirectory = Path.of(cwd);
        Path home = Path.of(System.getProperty("user.home"));
        return () -> {
            var pluginErrors = runtime != null
                ? runtime.diagnostics().stream()
                    .map(PluginRuntimePort.Diagnostic::formatted)
                    .toList()
                : List.<String>of();
            DiagnosticReport report = new DoctorDiagnosticsCollector().collect(
                new DoctorDiagnosticsCollector.Inputs(
                    workingDirectory, home, ClaudePaths.CLAUDE_HOME,
                    permissionGate.currentContext().rules(),
                    List.copyOf(toolRegistry.getAll()), pluginErrors));
            return toDoctorReport(report, workingDirectory);
        };
    }

    static DoctorReport toDoctorReport(DiagnosticReport report, Path cwd) {
        List<DoctorReport.DiagnosticRow> mcpRows =
            McpDiagnosticsFormatter.format(report.mcpWarnings(), cwd).stream()
                .map(row -> new DoctorReport.DiagnosticRow(
                    row.text(), DoctorReport.Style.valueOf(row.style().name())))
                .toList();
        DiagnosticReport.ContextUsage source = report.contextUsage();
        DoctorReport.ContextUsage context = new DoctorReport.ContextUsage(
            source.claudeMd() == null ? null : new DoctorReport.ClaudeMdWarning(
                source.claudeMd().largeFiles().stream()
                    .map(file -> new DoctorReport.FileSize(file.path(), file.chars())).toList(),
                source.claudeMd().thresholdChars()),
            source.agents() == null ? null : new DoctorReport.AgentDescriptionsWarning(
                source.agents().totalTokens(), source.agents().thresholdTokens(),
                source.agents().topAgents().stream()
                    .map(agent -> new DoctorReport.AgentTokens(agent.name(), agent.tokens())).toList(),
                source.agents().moreCount()),
            source.mcpTools() == null ? null : new DoctorReport.McpToolsWarning(
                source.mcpTools().totalTokens(), source.mcpTools().thresholdTokens(),
                source.mcpTools().byServer().stream()
                    .map(server -> new DoctorReport.ServerTokens(
                        server.serverName(), server.toolCount(), server.tokens())).toList(),
                source.mcpTools().moreCount()));
        return new DoctorReport(
            new DoctorReport.RuntimeInfo(report.runtime().appVersion()),
            new DoctorReport.RipgrepStatus(
                report.ripgrepStatus().working(),
                DoctorReport.RipgrepMode.valueOf(report.ripgrepStatus().mode().name()),
                report.ripgrepStatus().systemPath()),
            mcpRows,
            report.envVarChecks().stream()
                .map(check -> new DoctorReport.EnvVarCheck(
                    check.name(), check.effective(), check.status(), check.message())).toList(),
            report.unreachableRules().stream()
                .map(rule -> new DoctorReport.UnreachablePermissionRule(
                    rule.ruleDisplay(), rule.reason(), rule.fix()))
                .toList(), context,
            report.invalidSettings().stream()
                .map(error -> new DoctorReport.SettingsValidationError(
                    error.file(), error.path(), error.message())).toList(),
            report.sandboxDiagnostics(),
            report.agentParseErrors().stream()
                .map(error -> new DoctorReport.AgentParseError(error.path(), error.error())).toList(),
            report.pluginErrors());
    }

    static StatusLinePort newStatusLinePort(String cwd) {
        StatusLineExecutor executor = new StatusLineExecutor(cwd);
        return json -> StatusLineConfig.load(cwd)
            .flatMap(config -> executor.execute(config, json)
                .map(text -> new StatusLinePort.Output(text, config.padding())));
    }

    /**
     * Builds the account/API-provider portion of {@code /status} from the already-resolved CLI runtime.
     */
    static List<StatusProperty> statusRuntimeProperties(
            ApiConfig.ApiProvider provider, String resolvedBaseUrl, String cliApiKey) {
        return statusRuntimeProperties(
            provider, resolvedBaseUrl, cliApiKey, SubprocessEnvironment.snapshot(),
            GlobalConfigStore.getApiKey().isPresent(), ClaudePaths.GLOBAL_JSON);
    }

    static List<StatusProperty> statusRuntimeProperties(
            ApiConfig.ApiProvider provider,
            String resolvedBaseUrl,
            String cliApiKey,
            Map<String, String> env,
            boolean globalApiKeyPresent,
            Path globalConfigPath) {
        List<StatusProperty> rows = new ArrayList<>();
        ApiConfig.ApiProvider active = provider != null
            ? provider : ApiConfig.ApiProvider.ANTHROPIC;

        switch (active) {
            case ANTHROPIC -> {
                if (nonBlank(env.get("ANTHROPIC_AUTH_TOKEN"))) {
                    rows.add(new StatusProperty("Auth token", "ANTHROPIC_AUTH_TOKEN"));
                }
                String apiKeySource = nonBlank(cliApiKey) ? "--api-key"
                    : nonBlank(env.get("ANTHROPIC_API_KEY")) ? "ANTHROPIC_API_KEY"
                    : globalApiKeyPresent ? globalConfigPath.toString() : null;
                if (apiKeySource != null) {
                    rows.add(new StatusProperty("API key", apiKeySource));
                }
                if (nonBlank(resolvedBaseUrl)) {
                    rows.add(new StatusProperty("Anthropic base URL", resolvedBaseUrl));
                }
            }
            case BEDROCK -> {
                rows.add(new StatusProperty("API provider", "AWS Bedrock"));
                addIfPresent(rows, "Bedrock base URL", env.get("BEDROCK_BASE_URL"));
                rows.add(new StatusProperty("AWS region", firstNonBlank(
                    env.get("AWS_REGION"), env.get("AWS_DEFAULT_REGION"), "us-east-1")));
                if (EnvUtils.isEnvTruthy(
                        env.get("CLAUDE_CODE_SKIP_BEDROCK_AUTH"))) {
                    rows.add(new StatusProperty(null, "AWS auth skipped"));
                }
            }
            case VERTEX -> {
                rows.add(new StatusProperty("API provider", "Google Vertex AI"));
                addIfPresent(rows, "Vertex base URL", env.get("VERTEX_BASE_URL"));
                addIfPresent(rows, "GCP project", env.get("ANTHROPIC_VERTEX_PROJECT_ID"));
                rows.add(new StatusProperty("Default region",
                    firstNonBlank(env.get("CLOUD_ML_REGION"), "us-east5")));
                if (EnvUtils.isEnvTruthy(
                        env.get("CLAUDE_CODE_SKIP_VERTEX_AUTH"))) {
                    rows.add(new StatusProperty(null, "GCP auth skipped"));
                }
            }
            case OPENAI_COMPAT -> rows.add(
                new StatusProperty("API provider", "OpenAI-compatible"));
        }

        addIfPresent(rows, "Proxy", firstNonBlank(
            env.get("https_proxy"), env.get("HTTPS_PROXY"),
            env.get("http_proxy"), env.get("HTTP_PROXY")));
        addIfPresent(rows, "Additional CA cert(s)", env.get("NODE_EXTRA_CA_CERTS"));
        addIfPresent(rows, "mTLS client cert", env.get("CLAUDE_CODE_CLIENT_CERT"));
        addIfPresent(rows, "mTLS client key", env.get("CLAUDE_CODE_CLIENT_KEY"));
        return List.copyOf(rows);
    }

    private static void addIfPresent(List<StatusProperty> rows, String label, String value) {
        if (nonBlank(value)) rows.add(new StatusProperty(label, value));
    }

    private static boolean nonBlank(String value) {
        return StringUtils.isNotBlank(value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (nonBlank(value)) return value;
        }
        return null;
    }

    static CompactWarningProvider newCompactWarningProvider(CompactService compactService) {
        return (messages, model) -> {
            if (compactService == null || compactService.isCompactWarningSuppressed()) {
                return Optional.empty();
            }
            long tokens = compactService.estimateTokenCount(messages);
            CompactService.TokenWarningState warning =
                compactService.calculateTokenWarningState(tokens, model);
            return warning.isAboveWarningThreshold()
                ? Optional.of(new CompactWarningProvider.Warning(warning.percentLeft()))
                : Optional.empty();
        };
    }

    static StartupTrustPort newStartupTrustPort() {
        return new StartupTrustPort() {
            @Override public void migrateLegacyTrust() {
                TrustConfigStore.migrateRemoveLegacyTrustedFolders();
            }
            @Override public boolean isTrustAccepted(Path cwd) {
                return TrustConfigStore.isTrustAccepted(cwd);
            }
            @Override public void acceptTrust(Path cwd) { TrustConfigStore.acceptTrust(cwd); }
            @Override public boolean hasExternalIncludesApproved(Path cwd) {
                return TrustConfigStore.hasExternalIncludesApproved(cwd);
            }
            @Override public boolean hasExternalIncludesWarningShown(Path cwd) {
                return TrustConfigStore.hasExternalIncludesWarningShown(cwd);
            }
            @Override public void saveExternalIncludesDecision(Path cwd, boolean approved) {
                TrustConfigStore.saveExternalIncludesDecision(cwd, approved);
            }
        };
    }

    static ShutdownPort newShutdownPort(QuerySession engine, CliOutput shellOutput) {
        return new ShutdownPort() {
            @Override
            public void prepare(String reason, int exitCode) {
                var transcript = engine.execution().getTranscriptSink();
                String sessionId = engine.conversation().getSessionId();
                if (transcript != null && sessionId != null && !StringUtils.isBlank(sessionId)) {
                    transcript.flushCachedLastPrompt(sessionId);
                    transcript.awaitPendingWrites(sessionId, 2_000);
                    if (transcript instanceof TranscriptRecorder recorder) {
                        recorder.releaseSessionState(sessionId, 2_000);
                    }
                }
            }

            @Override
            public void shutdown(String reason, int exitCode) {
                if (engine.configuration().getConfig().memoryExtractor() != null) {
                    engine.configuration().getConfig().memoryExtractor().drainPending(5000);
                }
                String sessionId = engine.conversation().getSessionId();
                GracefulShutdown.run(
                    GracefulShutdown.Request.of(reason)
                        .exitCode(exitCode)
                        .sessionId(sessionId)
                        .workingDirectory(engine.configuration().getConfig().workingDirectory())
                        .hookDispatcher(engine.execution().getHookDispatcher())
                        .sessionManager(new SessionManager(System.getProperty("user.dir")))
                        .resumeHintSink(shellOutput::println));
            }
        };
    }

    static TurnAwakeGuard newTurnAwakeGuard() {
        SleepPreventer sleepPreventer = new SleepPreventer();
        return new TurnAwakeGuard() {
            @Override public void preventSleep() { sleepPreventer.preventSleep(); }
            @Override public void allowSleep() { sleepPreventer.allowSleep(); }
        };
    }

    static Supplier<String> newGoalGate(String cwd, boolean nonInteractive) {
        return () -> {
            if (HookSettings.areGoalHooksRestricted()) {
                return GoalCommand.HOOKS_ERROR;
            }
            if (nonInteractive) return null;
            try {
                return TrustConfigStore.isTrustAccepted(Path.of(cwd)) ? null
                    : GoalCommand.TRUST_ERROR;
            } catch (RuntimeException _) {
                return GoalCommand.TRUST_ERROR;
            }
        };
    }

    static DreamPort newDreamPort() {
        return new DreamPort() {
            @Override public boolean available() {
                return AutoMemory.isEnabled() && RuntimeSettings.isAutoDreamEnabled();
            }

            @Override public String buildPrompt(String workingDirectory) {
                Path memoryRoot = AutoMemoryPrompt.resolveAutoMemPath(Path.of(workingDirectory));
                Path transcriptDir = new SessionManager(workingDirectory).getProjectDir();
                new DreamLock(memoryRoot).recordConsolidation();
                return ConsolidationPromptGenerator.buildConsolidationPrompt(
                    memoryRoot, transcriptDir, null);
            }
        };
    }

    static void configureUiSettingsBackend() {
        UiSettings.configure(new UiSettings.Backend() {
            @Override public boolean globalBoolean(String key, boolean defaultValue) {
                return GlobalConfigStore.getBoolean(key, defaultValue);
            }
            @Override public String globalString(String key, String defaultValue) {
                return GlobalConfigStore.getString(key, defaultValue);
            }
            @Override public int globalInt(String key, int defaultValue) {
                return GlobalConfigStore.getInt(key, defaultValue);
            }
            @Override public JsonNode globalNode(String key) {
                return GlobalConfigStore.getNode(ClaudePaths.GLOBAL_JSON, key);
            }
            @Override public JsonNode effectiveSetting(String key) {
                return RuntimeSettings.loadEffectiveSetting(key);
            }
            @Override public String userSettingString(String key) {
                return RuntimeSettings.loadUserSettingString(key);
            }
            @Override public void setGlobal(String key, Object value) {
                GlobalConfigStore.set(key, value);
            }
            @Override public void setUserSetting(String key, Object value) {
                SettingsEditor.writeUserValue(key, value);
            }
            @Override public Map<String, Double> skillUsageScores() {
                return GlobalConfigStore.getSkillUsageScores();
            }
            @Override public boolean skipAutoPermissionPrompt() {
                return PermissionSettings.hasSkipAutoPermissionPrompt();
            }
            @Override public void applyTrustedEnvironment(String cwd) {
                ManagedEnvironmentApplier.applyConfigEnvironmentVariables();
            }
            @Override public boolean skipDangerousModePermissionPrompt() {
                return PermissionSettings.hasSkipDangerousModePermissionPrompt();
            }
            @Override public void persistDangerousModePermissionPrompt() {
                PermissionSettings.saveSkipDangerousModePermissionPrompt();
            }
            @Override public boolean spinnerTipsEnabled() {
                return RuntimeSettings.loadSpinnerTipsEnabled();
            }
            @Override public boolean prefersReducedMotion() {
                return RuntimeSettings.loadPrefersReducedMotion();
            }
            @Override public Boolean policyBoolean(String key) {
                return RuntimeSettings.readPolicyBoolean(key);
            }
            @Override public SandboxConfig sandboxConfig() {
                return SandboxSettings.loadSandboxConfig();
            }
            @Override public UiSettings.SandboxDependencyStatus sandboxDependencyStatus() {
                SandboxConfig config = SandboxSettings.loadSandboxConfig();
                SandboxManager manager = PlatformSandboxManager.create();
                List<String> errors = manager.isNativePlatformSupported() && manager.available()
                    ? List.of() : List.of(manager.unavailableReason());
                return new UiSettings.SandboxDependencyStatus(
                    errors, manager.globPatternWarnings(config));
            }
            @Override public boolean sandboxSettingsLockedByPolicy() {
                return SandboxSettings.areSandboxSettingsLockedByPolicy();
            }
            @Override
            public void setSandboxSettings(Boolean enabled, Boolean autoAllowBashIfSandboxed,
                                           Boolean allowUnsandboxedCommands) {
                new CliSettingsManagementAdapter().sandbox().saveSettings(
                    System.getProperty("user.dir"), enabled,
                    autoAllowBashIfSandboxed, allowUnsandboxedCommands);
            }
            @Override
            public void addPermissionRule(String cwd, PermissionBehavior behavior,
                                          String ruleString, RuleSource tier) {
                PermissionSettings.addPermissionRule(cwd, behavior, ruleString, tier);
            }
            @Override
            public void removePermissionRule(String cwd, PermissionBehavior behavior,
                                             String ruleString, RuleSource tier) {
                PermissionSettings.removePermissionRule(cwd, behavior, ruleString, tier);
            }
            @Override
            public void persistPermissionUpdate(String cwd, PermissionUpdate update) {
                persistInteractivePermissionUpdate(cwd, update);
            }
        });
    }


    private static void persistInteractivePermissionUpdate(String cwd, PermissionUpdate update) {
        RuleSource tier = switch (update.destination()) {
            case USER_SETTINGS -> RuleSource.USER_SETTINGS;
            case PROJECT_SETTINGS -> RuleSource.PROJECT_SETTINGS;
            case LOCAL_SETTINGS -> RuleSource.LOCAL_SETTINGS;
            case SESSION, CLI_ARG -> null;
        };
        if (tier == null) return;

        switch (update) {
            case PermissionUpdate.AddRules add -> {
                PermissionBehavior behavior = permissionBehavior(add.behavior());
                for (PermissionUpdate.RuleValue value : add.rules()) {
                    PermissionSettings.addPermissionRule(cwd, behavior,
                        permissionRuleString(value, behavior, tier), tier);
                }
            }
            case PermissionUpdate.ReplaceRules replace -> {
                PermissionBehavior behavior = permissionBehavior(replace.behavior());
                List<String> rules = replace.rules().stream()
                    .map(value -> permissionRuleString(value, behavior, tier)).toList();
                PermissionSettings.replacePermissionRules(cwd, behavior, rules, tier);
            }
            case PermissionUpdate.RemoveRules remove -> {
                PermissionBehavior behavior = permissionBehavior(remove.behavior());
                if (remove.rules().isEmpty()) {

                    // array even when removeRules is empty.
                    PermissionSettings.replacePermissionRules(cwd, behavior, List.of(), tier);
                    break;
                }
                for (PermissionUpdate.RuleValue value : remove.rules()) {
                    PermissionSettings.removePermissionRuleForUpdate(cwd, behavior,
                        permissionRuleString(value, behavior, tier), tier);
                }
            }
            case PermissionUpdate.SetMode mode ->
                PermissionSettings.saveDefaultPermissionMode(cwd, mode.mode().wireValue(), tier);
            case PermissionUpdate.AddDirectories add ->
                PermissionSettings.addAdditionalDirectories(cwd, add.directories(), tier);
            case PermissionUpdate.RemoveDirectories remove ->
                PermissionSettings.removeAdditionalDirectories(cwd, remove.directories(), tier);
        }
    }

    private static PermissionBehavior permissionBehavior(PermissionUpdate.Behavior behavior) {
        return switch (behavior) {
            case ALLOW -> PermissionBehavior.ALLOW;
            case DENY -> PermissionBehavior.DENY;
            case ASK -> PermissionBehavior.ASK;
        };
    }

    private static String permissionRuleString(
            PermissionUpdate.RuleValue value, PermissionBehavior behavior, RuleSource source) {
        PermissionRule rule = StringUtils.isBlank(value.ruleContent())
            ? PermissionRule.of(value.toolName(), behavior, source)
            : PermissionRule.withPattern(value.toolName(), behavior, source, value.ruleContent());
        return PermissionEngine.permissionRuleToString(rule);
    }

    static HookConfigurationPort newHookConfigurationPort(
        SettingsReloadOrchestrator settingsReload, HookEngine hookEngine) {
        return new HookConfigurationPort() {
            @Override
            public HookConfigurationSnapshot snapshot(String workingDirectory,
                                                       List<String> toolNames) {
                List<IndividualHookConfig> configs = new ArrayList<>(
                    HooksConfigManager.getAllHooks(workingDirectory));
                if (hookEngine != null) {
                    addRuntimeHooks(configs, hookEngine.currentSessionHooks(),
                        HookSource.SESSION_HOOK);
                    addRuntimeHooks(configs, hookEngine.currentPluginHooks(),
                        HookSource.PLUGIN_HOOK);
                }
                List<HookEntry> hooks = configs.stream()
                    .map(CliRuntimeAdapters::toHookEntry)
                    .toList();

                Map<HookEvent, HookEventMetadata> metadata = new LinkedHashMap<>();
                HooksConfigManager.getHookEventMetadata(toolNames).forEach((event, value) -> {
                    HookEventMetadata.MatcherMetadata matcher = value.matcherMetadata() == null
                        ? null : new HookEventMetadata.MatcherMetadata(
                            value.matcherMetadata().matcherPlaceholder(),
                            value.matcherMetadata().matcherType());
                    metadata.put(HookEvent.valueOf(event.name()),
                        new HookEventMetadata(value.summary(), value.description(), matcher));
                });
                return new HookConfigurationSnapshot(hooks, metadata);
            }

            @Override public AutoCloseable subscribeReload(Runnable listener) {
                return settingsReload != null
                    ? settingsReload.subscribeReload(listener)
                    : HookConfigurationPort.super.subscribeReload(listener);
            }
            @Override public void clearSessionHooks() {
                if (hookEngine != null) hookEngine.clearExtraHooks();
            }
        };
    }

    private static void addRuntimeHooks(
            List<IndividualHookConfig> target,
            Map<com.claudecode.services.hooks.HookEvent,
                List<HookMatcher>> hooks,
            HookSource source) {
        hooks.forEach((event, matchers) -> matchers.forEach(matcher ->
            matcher.hooks().forEach(command -> target.add(new IndividualHookConfig(
                event, command, matcher.matcher().orElse(null), source, null)))));
    }

    private static HookEntry toHookEntry(IndividualHookConfig config) {
        HookKind kind = switch (config.command()) {
            case BashCommandHook _ -> HookKind.COMMAND;
            case PromptHook _ -> HookKind.PROMPT;
            case HttpHook _ -> HookKind.HTTP;
            case AgentHook _ -> HookKind.AGENT;
            case CallbackHook _ -> HookKind.COMMAND;
        };
        return new HookEntry(
            HookEvent.valueOf(config.event().name()), kind, config.matcher(),
            config.source().headerDisplay(config.pluginName()),
            config.source().inlineDisplay(),
            config.source().descriptionDisplay(),
            HooksConfigManager.getHookDisplayText(config.command(), Integer.MAX_VALUE),
            HooksConfigManager.getRawHookContent(config.command()));
    }
}

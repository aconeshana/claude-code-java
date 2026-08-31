package com.claudecode.services.doctor;


import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpConfigWarning;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.UnreachableRule;
import com.claudecode.permissions.UnreachableRuleDetector;
import com.claudecode.services.claudemd.MemoryFileInfo;
import com.claudecode.services.claudemd.MemoryFileScanner;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.core.process.SubprocessEnvironment;
import com.claudecode.core.config.EnvValidation;
import com.claudecode.core.config.VersionInfo;
import com.claudecode.core.engine.SandboxConfig;
import com.claudecode.services.config.SandboxSettings;
import com.claudecode.services.config.SettingsDiagnostics;
import com.claudecode.services.config.SettingsValidationError;
import com.claudecode.services.config.WorkspaceSettings;
import com.claudecode.services.model.ModelOutputTokens;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.tools.files.RipGrepUtil;
import com.claudecode.tools.sandbox.PlatformSandboxManager;
import com.claudecode.tools.sandbox.SandboxManager;
import com.claudecode.tools.Tool;
import com.claudecode.tools.shell.OutputLimits;
import com.claudecode.tools.tasks.TaskOutputFormatting;
import com.claudecode.mcp.McpConfigLoader;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Aggregates the Java-mappable subset of {@code /doctor}'s diagnostics into a {@link
 * DiagnosticReport}.
 */
public final class DoctorDiagnosticsCollector {


    static final long CLAUDE_MD_CHAR_THRESHOLD = 40_000;

    static final long AGENT_DESCRIPTIONS_TOKEN_THRESHOLD = 15_000;

    static final long MCP_TOOLS_TOKEN_THRESHOLD = 25_000;

    private final TokenEstimator tokenEstimator = TokenEstimator.getInstance();


    public record Inputs(
        Path cwd,
        Path homeDir,
        Path configHome,
        List<PermissionRule> permissionRules,
        List<Tool<?, ?>> liveTools,
        List<String> pluginErrors
    ) {
        /** Backwards-compatible form: default config home under the supplied physical home. */
        public Inputs(Path cwd, Path homeDir,
                      List<PermissionRule> permissionRules, List<Tool<?, ?>> liveTools,
                      List<String> pluginErrors) {
            this(cwd, homeDir, homeDir.resolve(".claude"), permissionRules, liveTools, pluginErrors);
        }

        /** Convenience form for callers/tests without a plugin runtime. */
        public Inputs(Path cwd, Path homeDir,
                      List<PermissionRule> permissionRules, List<Tool<?, ?>> liveTools) {
            this(cwd, homeDir, homeDir.resolve(".claude"), permissionRules, liveTools, List.of());
        }
    }

    public DiagnosticReport collect(Inputs inputs) {
        DiagnosticReport.RuntimeInfo runtime = collectRuntime(inputs);
        RipGrepUtil.RipgrepStatus ripgrep = RipGrepUtil.status();
        DiagnosticReport.RipgrepStatus ripgrepStatus = new DiagnosticReport.RipgrepStatus(
            ripgrep.working(),
            DiagnosticReport.RipgrepMode.valueOf(ripgrep.mode().name()),
            ripgrep.systemPath());
        SandboxConfig sandboxConfig = SandboxSettings.loadSandboxConfig();
        SandboxManager sandboxManager = PlatformSandboxManager.create();
        List<McpConfigWarning> mcpWarnings = collectMcpWarnings(inputs.cwd());
        List<DiagnosticReport.EnvVarCheck> envVarChecks = collectEnvVarChecks();
        boolean sandboxAutoAllowEnabled = sandboxConfig.enabled()
            && sandboxConfig.autoAllowBashIfSandboxed()
            && sandboxManager.isPlatformSupported(sandboxConfig)
            && sandboxManager.available();
        List<UnreachableRule> unreachableRules =
            UnreachableRuleDetector.detect(
                inputs.permissionRules() != null ? inputs.permissionRules() : List.of(),
                sandboxAutoAllowEnabled);
        DiagnosticReport.ContextUsage contextUsage = collectContextUsage(inputs);
        List<DiagnosticReport.SettingsValidationError> invalidSettings = collectInvalidSettings(inputs);
        List<String> sandboxDiagnostics =
            collectSandboxDiagnostics(sandboxConfig, sandboxManager);
        List<DiagnosticReport.AgentParseError> agentParseErrors = collectAgentParseErrors(inputs);
        List<String> pluginErrors = collectPluginErrors(inputs);

        return new DiagnosticReport(runtime, ripgrepStatus, mcpWarnings,
            envVarChecks, unreachableRules, contextUsage, invalidSettings,
            sandboxDiagnostics, agentParseErrors, pluginErrors);
    }

    private DiagnosticReport.RuntimeInfo collectRuntime(Inputs inputs) {
        String cwd = inputs.cwd() != null ? inputs.cwd().toString() : System.getProperty("user.dir");
        return new DiagnosticReport.RuntimeInfo(
            System.getProperty("java.version"),
            System.getProperty("java.vendor"),
            System.getProperty("os.name"),
            System.getProperty("os.version"),
            System.getProperty("os.arch"),
            cwd,
            resolveAppVersion());
    }

    /**
     * App version from the shared build-version resolver — the same source as {@code
     * LogoPanel.appVersion} / {@code VersionCommand}.
     */
    private static String resolveAppVersion() {
        return VersionInfo.version();
    }

    private List<McpConfigWarning> collectMcpWarnings(Path cwd) {
        if (cwd == null) return List.of();
        return McpConfigLoader.loadConfig(cwd).diagnostics();
    }


    private List<DiagnosticReport.SettingsValidationError> collectInvalidSettings(Inputs inputs) {
        String cwd = inputs.cwd() != null ? inputs.cwd().toString() : System.getProperty("user.dir");
        List<DiagnosticReport.SettingsValidationError> errors = new ArrayList<>();
        for (SettingsValidationError error :
                SettingsDiagnostics.loadSettingsWithErrors(cwd).errors()) {
            errors.add(new DiagnosticReport.SettingsValidationError(
                error.file(), error.path(), error.message()));
        }
        return errors;
    }

    private List<DiagnosticReport.EnvVarCheck> collectEnvVarChecks() {


        ModelOutputTokens.Bounds tokenBounds = ModelOutputTokens.getModelMaxOutputTokens("claude-opus-4-6");
        List<DiagnosticReport.EnvVarCheck> out = new ArrayList<>();
        out.add(check("BASH_MAX_OUTPUT_LENGTH",
            OutputLimits.BASH_MAX_OUTPUT_DEFAULT, OutputLimits.BASH_MAX_OUTPUT_UPPER_LIMIT));
        out.add(check("TASK_MAX_OUTPUT_LENGTH",
            TaskOutputFormatting.TASK_MAX_OUTPUT_DEFAULT, TaskOutputFormatting.TASK_MAX_OUTPUT_UPPER_LIMIT));
        out.add(check("CLAUDE_CODE_MAX_OUTPUT_TOKENS", tokenBounds.defaultTokens(), tokenBounds.upperLimit()));
        return out.stream().filter(c -> !Strings.CS.equals("valid", c.status())).toList();
    }

    private static DiagnosticReport.EnvVarCheck check(String name, long def, long upper) {
        return validateBoundedIntEnvVar(name,
            SubprocessEnvironment.get(name), def, upper);
    }

    /**
     * Adapter over the shared {@link EnvValidation#validateBoundedIntEnvVar} that maps the result to a
     * doctor {@link DiagnosticReport.EnvVarCheck}.
     */
    static DiagnosticReport.EnvVarCheck validateBoundedIntEnvVar(
            String name, String value, long defaultValue, long upperLimit) {
        EnvValidation.Result r = EnvValidation.validateBoundedIntEnvVar(name, value, defaultValue, upperLimit);
        return new DiagnosticReport.EnvVarCheck(name, r.effective(), r.status(), r.message());
    }

    private List<String> collectSandboxDiagnostics(
            SandboxConfig config, SandboxManager manager) {
        return formatSandboxDiagnostics(
            manager.isNativePlatformSupported(),
            config != null && config.enabled(),
            manager.available(),
            manager.unavailableReason(),
            manager.globPatternWarnings(config));
    }


    static List<String> formatSandboxDiagnostics(
            boolean supported, boolean enabled, boolean available,
            String unavailableReason, List<String> warnings) {
        if (!supported || !enabled) return List.of();
        List<String> safeWarnings = warnings != null ? warnings : List.of();
        boolean hasError = !available;
        if (!hasError && safeWarnings.isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        out.add("Status: " + (hasError
            ? "Missing dependencies" : "Available (with warnings)"));
        if (hasError && unavailableReason != null && !StringUtils.isBlank(unavailableReason)) {
            out.add("ERROR: " + unavailableReason);
        }
        safeWarnings.stream()
            .filter(StringUtils::isNotBlank)
            .map(w -> "WARNING: " + w)
            .forEach(out::add);
        if (hasError) out.add("Run /sandbox for install instructions");
        return List.copyOf(out);
    }


    private List<DiagnosticReport.AgentParseError> collectAgentParseErrors(Inputs inputs) {
        try {
            String cwd = inputs.cwd() != null ? inputs.cwd().toString() : null;
            return AgentDefinitionLoader.getParseErrors(cwd).stream()
                .map(e -> new DiagnosticReport.AgentParseError(e.path(), e.error()))
                .toList();
        } catch (RuntimeException _) {
            return List.of();
        }
    }


    private List<String> collectPluginErrors(Inputs inputs) {
        if (inputs.pluginErrors() == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : inputs.pluginErrors()) {
            try {
                if (StringUtils.isNotBlank(line)) out.add(line);
            } catch (RuntimeException _) {
                // diagnostics must never break doctor
            }
        }
        return out;
    }


    static String formatPluginError(PluginError e) {
        String source = StringUtils.isEmpty(e.source()) ? "unknown" : e.source();
        String plugin = StringUtils.isNotEmpty(e.plugin()) ? " [" + e.plugin() + "]" : "";
        return source + plugin + ": " + e.getMessage();
    }

    private DiagnosticReport.ContextUsage collectContextUsage(Inputs inputs) {
        return new DiagnosticReport.ContextUsage(
            checkClaudeMdFiles(inputs),
            checkAgentDescriptions(inputs),
            checkMcpTools(inputs));
    }


    private DiagnosticReport.ClaudeMdWarning checkClaudeMdFiles(Inputs inputs) {
        if (inputs.cwd() == null || inputs.homeDir() == null || inputs.configHome() == null) return null;
        String cwd = inputs.cwd().toString();
        MemoryFileScanner scanner = MemoryFileScanner.forConfigHome(
            inputs.homeDir(), inputs.configHome(), WorkspaceSettings.loadClaudeMdExcludes(cwd), null);
        List<MemoryFileInfo> files = scanner.scan(inputs.cwd());

        List<DiagnosticReport.FileSize> large = files.stream()
            .filter(f -> f.content().length() > CLAUDE_MD_CHAR_THRESHOLD)
            .sorted(Comparator.comparingInt((MemoryFileInfo f) -> f.content().length()).reversed())
            .map(f -> new DiagnosticReport.FileSize(f.path().toString(), f.content().length()))
            .toList();

        if (large.isEmpty()) return null;
        return new DiagnosticReport.ClaudeMdWarning(large, CLAUDE_MD_CHAR_THRESHOLD);
    }


    private DiagnosticReport.AgentDescriptionsWarning checkAgentDescriptions(Inputs inputs) {
        String cwd = inputs.cwd() != null ? inputs.cwd().toString() : null;
        List<BuiltInAgentDefinitions.AgentDefinition> all = AgentDefinitionLoader.getAll(cwd);

        Set<String> builtInNames = BuiltInAgentDefinitions.getBuiltInAgents().stream()
            .map(BuiltInAgentDefinitions.AgentDefinition::agentType)
            .collect(Collectors.toSet());

        List<DiagnosticReport.AgentTokens> ranked = all.stream()
            .filter(a -> !builtInNames.contains(a.agentType()))
            .map(a -> new DiagnosticReport.AgentTokens(a.agentType(),
                tokenEstimator.estimateTokenCount(describeAgent(a))))
            .sorted(Comparator.comparingLong(DiagnosticReport.AgentTokens::tokens).reversed())
            .toList();

        long totalTokens = ranked.stream().mapToLong(DiagnosticReport.AgentTokens::tokens).sum();
        if (totalTokens <= AGENT_DESCRIPTIONS_TOKEN_THRESHOLD) return null;

        List<DiagnosticReport.AgentTokens> top = ranked.stream().limit(5).toList();
        int moreCount = Math.max(0, ranked.size() - 5);

        return new DiagnosticReport.AgentDescriptionsWarning(
            totalTokens, AGENT_DESCRIPTIONS_TOKEN_THRESHOLD, top, moreCount);
    }

    private static String describeAgent(BuiltInAgentDefinitions.AgentDefinition agent) {
        return agent.agentType() + ": " + agent.whenToUse();
    }


    private DiagnosticReport.McpToolsWarning checkMcpTools(Inputs inputs) {
        if (inputs.liveTools() == null) return null;
        List<Tool<?, ?>> mcpTools = inputs.liveTools().stream()
            .filter(t -> t.name() != null && Strings.CS.startsWith(t.name(), "mcp__"))
            .toList();
        if (mcpTools.isEmpty()) return null;

        Map<String, long[]> byServer = new LinkedHashMap<>(); // [count, tokens]
        long totalTokens = 0;
        for (Tool<?, ?> tool : mcpTools) {
            String[] parts = tool.name().split("__");
            String server = parts.length > 1 ? parts[1] : "unknown";
            String text = tool.name() + (tool.description() != null ? tool.description() : "");
            long tokens = tokenEstimator.estimateTokenCount(text);
            totalTokens += tokens;
            long[] agg = byServer.computeIfAbsent(server, _ -> new long[2]);
            agg[0] += 1;
            agg[1] += tokens;
        }
        if (totalTokens <= MCP_TOOLS_TOKEN_THRESHOLD) return null;

        List<DiagnosticReport.ServerTokens> ranked = byServer.entrySet().stream()
            .map(e -> new DiagnosticReport.ServerTokens(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
            .sorted(Comparator.comparingLong(DiagnosticReport.ServerTokens::tokens).reversed())
            .toList();

        List<DiagnosticReport.ServerTokens> top = ranked.stream().limit(5).toList();
        int moreCount = Math.max(0, ranked.size() - 5);

        return new DiagnosticReport.McpToolsWarning(totalTokens, MCP_TOOLS_TOKEN_THRESHOLD, top, moreCount);
    }
}

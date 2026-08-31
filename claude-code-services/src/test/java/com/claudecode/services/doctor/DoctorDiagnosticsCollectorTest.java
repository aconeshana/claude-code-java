package com.claudecode.services.doctor;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.ToolExecutionContext;
import com.claudecode.core.message.TokenEstimator;
import com.claudecode.services.plugins.marketplace.InstalledPlugins;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.services.plugins.marketplace.PluginScope;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.tools.Tool;
import com.claudecode.tools.ToolIdentity;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DoctorDiagnosticsCollectorTest {

    private final DoctorDiagnosticsCollector collector = new DoctorDiagnosticsCollector();

    @AfterEach
    void clearAgentCache() {
        AgentDefinitionLoader.clearCache();
    }

    private Path fakeHome(Path root) throws IOException {
        Path h = root.resolve("home");
        Files.createDirectories(h);
        return h;
    }

    @Test
    void sandboxDiagnosticsHiddenWhenUnsupportedOrDisabledOrHealthy() {
        assertTrue(DoctorDiagnosticsCollector.formatSandboxDiagnostics(
            false, true, false, "missing", List.of()).isEmpty());
        assertTrue(DoctorDiagnosticsCollector.formatSandboxDiagnostics(
            true, false, false, "missing", List.of()).isEmpty());
        assertTrue(DoctorDiagnosticsCollector.formatSandboxDiagnostics(
            true, true, true, null, List.of()).isEmpty());
    }

    @Test
    void sandboxDiagnosticsReportErrorsWarningsAndInstallHint() {
        assertEquals(List.of(
                "Status: Missing dependencies",
                "ERROR: bubblewrap is not installed",
                "WARNING: glob pattern is unsupported: **/build",
                "Run /sandbox for install instructions"),
            DoctorDiagnosticsCollector.formatSandboxDiagnostics(
                true, true, false, "bubblewrap is not installed",
                List.of("glob pattern is unsupported: **/build")));
    }

    @Test
    void sandboxDiagnosticsUseWarningStatusWithoutDependencyErrors() {
        assertEquals(List.of(
                "Status: Available (with warnings)",
                "WARNING: glob pattern is unsupported"),
            DoctorDiagnosticsCollector.formatSandboxDiagnostics(
                true, true, true, null, List.of("glob pattern is unsupported")));
    }

    // ── env var validation ──────────────────────────────────────────────────

    @Test
    void envVarCheck_unsetIsValidWithDefault() {
        var result = DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", null, 100, 200);
        assertEquals("valid", result.status());
        assertEquals(100, result.effective());
        assertNull(result.message());
    }

    @Test
    void envVarCheck_validWithinBound() {
        var result = DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", "150", 100, 200);
        assertEquals("valid", result.status());
        assertEquals(150, result.effective());
    }

    @Test
    void envVarCheck_nonNumericIsInvalid() {
        var result = DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", "abc", 100, 200);
        assertEquals("invalid", result.status());
        assertEquals(100, result.effective());
        assertNotNull(result.message());
    }

    @Test
    void envVarCheck_zeroOrNegativeIsInvalid() {
        assertEquals("invalid", DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", "0", 100, 200).status());
        assertEquals("invalid", DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", "-5", 100, 200).status());
    }

    @Test
    void envVarCheck_overUpperLimitIsCapped() {
        var result = DoctorDiagnosticsCollector.validateBoundedIntEnvVar("X", "500", 100, 200);
        assertEquals("capped", result.status());
        assertEquals(200, result.effective());
    }

    // ── base URL fallback ───────────────────────────────────────────────────



    // ── CLAUDE.md large-file detection ─────────────────────────────────────

    @Test
    void claudeMdWarning_flagsFilesOverThresholdSortedDescending(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Files.createDirectories(home.resolve(".claude"));
        Files.writeString(home.resolve(".claude").resolve("CLAUDE.md"),
            "x".repeat((int) DoctorDiagnosticsCollector.CLAUDE_MD_CHAR_THRESHOLD + 1000));
        Files.writeString(tmp.resolve("CLAUDE.md"),
            "y".repeat((int) DoctorDiagnosticsCollector.CLAUDE_MD_CHAR_THRESHOLD + 5000));

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertNotNull(report.contextUsage().claudeMd());
        List<DiagnosticReport.FileSize> large = report.contextUsage().claudeMd().largeFiles();
        assertEquals(2, large.size());
        // Sorted descending by size — the 5000-extra file comes first.
        assertTrue(large.getFirst().chars() > large.get(1).chars());
    }

    @Test
    void claudeMdWarning_nullWhenNoFileExceedsThreshold(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Files.writeString(tmp.resolve("CLAUDE.md"), "small body");

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertNull(report.contextUsage().claudeMd());
    }

    // ── agent description token aggregation ─────────────────────────────────

    @Test
    void agentWarning_flagsWhenCustomAgentPushesTotalOverThreshold(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        // ~4 chars/token — comfortably clears the 15_000 token threshold.
        String longDescription = "word ".repeat(15_000);
        Files.writeString(agentsDir.resolve("huge.md"), """
            ---
            name: huge-agent
            description: %s
            ---
            """.formatted(longDescription));
        AgentDefinitionLoader.clearCache();

        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        var inputs = new DoctorDiagnosticsCollector.Inputs(
            tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertNotNull(report.contextUsage().agents());
        assertTrue(report.contextUsage().agents().totalTokens()
            > DoctorDiagnosticsCollector.AGENT_DESCRIPTIONS_TOKEN_THRESHOLD);
        assertTrue(report.contextUsage().agents().topAgents().stream()
            .anyMatch(a -> Strings.CS.equals(a.name(), "huge-agent")));
        // Built-in agents must never appear in the "top contributors" breakdown.
        assertTrue(report.contextUsage().agents().topAgents().stream()
            .noneMatch(a -> Strings.CS.equals(a.name(), "general-purpose")));
        // #1 regression lock: the total must count CUSTOM agents only — built-in

        // source !== 'built-in'). Computed from the actual loaded set so the
        // assertion is independent of whatever lives in the real ~/.claude/agents.
        assertEquals(expectedCustomTotal(tmp.toString()), report.contextUsage().agents().totalTokens());
    }

    /** Sum of token estimates for every non-built-in agent visible to the collector for {@code cwd}. */
    private static long expectedCustomTotal(String cwd) {
        Set<String> builtIn = BuiltInAgentDefinitions.getBuiltInAgents().stream()
            .map(BuiltInAgentDefinitions.AgentDefinition::agentType)
            .collect(Collectors.toSet());
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(a -> !builtIn.contains(a.agentType()))
            .mapToLong(a -> TokenEstimator.getInstance()
                .estimateTokenCount(a.agentType() + ": " + a.whenToUse()))
            .sum();
    }

    /** Count of non-built-in agents visible to the collector for {@code cwd}. */
    private static long customAgentCount(String cwd) {
        Set<String> builtIn = BuiltInAgentDefinitions.getBuiltInAgents().stream()
            .map(BuiltInAgentDefinitions.AgentDefinition::agentType)
            .collect(Collectors.toSet());
        return AgentDefinitionLoader.getAll(cwd).stream()
            .filter(a -> !builtIn.contains(a.agentType()))
            .count();
    }

    @Test
    void agentWarning_topAgentsCappedAtFiveWithMoreCount(@TempDir Path tmp) throws IOException {
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        // 7 project-level custom agents, each token-bearing — total clears 15_000.
        for (int i = 0; i < 7; i++) {
            Files.writeString(agentsDir.resolve("agent" + i + ".md"), """
                ---
                name: agent-%d
                description: %s
                ---
                """.formatted(i, "word ".repeat(3_000 + i * 100)));
        }
        AgentDefinitionLoader.clearCache();

        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        var inputs = new DoctorDiagnosticsCollector.Inputs(
            tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertNotNull(report.contextUsage().agents());
        assertEquals(5, report.contextUsage().agents().topAgents().size());
        // moreCount = (all custom agents) − 5; computed from the actual loaded set
        // so real ~/.claude/agents entries don't skew the assertion (we added ≥7,
        // so it's always ≥ 2).
        long expectedMore = Math.max(0, customAgentCount(tmp.toString()) - 5);
        assertEquals(expectedMore, report.contextUsage().agents().moreCount());
        assertTrue(report.contextUsage().agents().moreCount() >= 2);
    }

    @Test
    void agentWarning_nullWhenTotalUnderThreshold(@TempDir Path tmp) throws IOException {
        AgentDefinitionLoader.clearCache();
        Path home = tmp.resolve("home");
        Files.createDirectories(home);
        var inputs = new DoctorDiagnosticsCollector.Inputs(
            tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertNull(report.contextUsage().agents());
    }

    // ── MCP tool token aggregation ───────────────────────────────────────────

    private static Tool<JsonNode, String> fakeMcpTool(String fullName, String description) {
        return new Tool<>() {
            @Override public ToolIdentity identity() { return new ToolIdentity(fullName); }
            @Override public String description() { return description; }
            @Override public JsonNode inputSchema() {
              return JsonUtils.getMapper().createObjectNode(); }
            @Override public String call(JsonNode input, ToolExecutionContext context) { return ""; }
        };
    }

    @Test
    void mcpToolsWarning_groupsByServerAndFlagsOverThreshold(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        String bigDescription = "z".repeat(120_000); // ~30k tokens, well over the 25k threshold
        List<Tool<?, ?>> tools = List.of(
            fakeMcpTool("mcp__github__search", bigDescription),
            fakeMcpTool("mcp__github__list", "short"),
            fakeMcpTool("Bash", "not mcp"));

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), tools);
        DiagnosticReport report = collector.collect(inputs);

        assertNotNull(report.contextUsage().mcpTools());
        assertEquals(1, report.contextUsage().mcpTools().byServer().size());
        assertEquals("github", report.contextUsage().mcpTools().byServer().getFirst().serverName());
        assertEquals(2, report.contextUsage().mcpTools().byServer().getFirst().toolCount());
        assertEquals(0, report.contextUsage().mcpTools().moreCount());
    }

    @Test
    void mcpToolsWarning_serversCappedAtFiveWithMoreCount(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        String bigDescription = "z".repeat(40_000); // ~13k tokens per server after padding
        List<Tool<?, ?>> tools = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            tools.add(fakeMcpTool("mcp__srv" + i + "__tool", bigDescription));
        }

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), tools);
        DiagnosticReport report = collector.collect(inputs);

        assertNotNull(report.contextUsage().mcpTools());
        assertEquals(5, report.contextUsage().mcpTools().byServer().size());
        assertEquals(2, report.contextUsage().mcpTools().moreCount());
    }

    @Test
    void mcpToolsWarning_nullWhenNoMcpTools(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        var inputs = new DoctorDiagnosticsCollector.Inputs(
            tmp, home, List.of(), List.of(fakeMcpTool("Bash", "not mcp")));
        DiagnosticReport report = collector.collect(inputs);

        assertNull(report.contextUsage().mcpTools());
    }



    @Test
    void agentParseErrors_surfaceNamedFileMissingDescription(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Path bad = agentsDir.resolve("bad.md");
        Files.writeString(bad, """
            ---
            name: bad-agent
            ---
            Missing the required description.
            """);
        AgentDefinitionLoader.clearCache();

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        // Filter by tmp prefix so real ~/.claude/agents contents don't skew the assertion.
        var mine = report.agentParseErrors().stream()
            .filter(e -> Strings.CS.startsWith(e.path(), tmp.toString()))
            .toList();
        assertEquals(1, mine.size(), report.agentParseErrors().toString());
        assertEquals(bad.toString(), mine.getFirst().path());
        assertEquals("Missing required \"description\" field in frontmatter", mine.getFirst().error());
    }

    @Test
    void agentParseErrors_emptyForCwdWhenAllAgentsValid(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Path agentsDir = tmp.resolve(".claude/agents");
        Files.createDirectories(agentsDir);
        Files.writeString(agentsDir.resolve("ok.md"), """
            ---
            name: ok-agent
            description: Fine
            ---
            """);
        AgentDefinitionLoader.clearCache();

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        assertTrue(report.agentParseErrors().stream()
            .noneMatch(e -> Strings.CS.startsWith(e.path(), tmp.toString())),
            report.agentParseErrors().toString());
    }

    // ── invalid settings (malformed JSON per scope) ─────────────────────────

    @Test
    void invalidSettings_flagsMalformedProjectSettingsJson(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Path claudeDir = tmp.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), "{ not valid json ]");

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

// Environment-robust: the machine's real ~/on may or may
        // not exist, so assert on the project file specifically rather than a count.
        String projectSettings = tmp.resolve(".claude").resolve("settings.json").toString();
        assertTrue(report.invalidSettings().stream()
                .anyMatch(e -> e.file().equals(projectSettings)
                    && Strings.CI.contains(e.message(), "malformed")),
            "malformed project settings.json must produce an Invalid Settings entry");
    }

    @Test
    void invalidSettings_wellFormedProjectSettingsProducesNoEntryForIt(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Path claudeDir = tmp.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), "{ \"permissions\": { \"defaultMode\": \"default\" } }");

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        String projectSettings = tmp.resolve(".claude").resolve("settings.json").toString();
        assertTrue(report.invalidSettings().stream().noneMatch(e -> e.file().equals(projectSettings)),
            "well-formed project settings.json must not be flagged");
    }

    @Test
    void invalidSettings_flagsFieldLevelSchemaErrors(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        Path claudeDir = tmp.resolve(".claude");
        Files.createDirectories(claudeDir);
        Files.writeString(claudeDir.resolve("settings.json"), """
            {"permissions": {"defaultMode": "yolo"}, "model": 5}
            """);

        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);

        String projectSettings = tmp.resolve(".claude").resolve("settings.json").toString();
        assertTrue(report.invalidSettings().stream()
                .anyMatch(e -> e.file().equals(projectSettings)
                    && Strings.CS.equals(e.path(), "permissions.defaultMode")
                    && Strings.CS.equals(e.message(), "Invalid value. Expected one of: \"acceptEdits\", "
                        + "\"bypassPermissions\", \"default\", \"dontAsk\", \"plan\"")),
            report.invalidSettings().toString());
        assertTrue(report.invalidSettings().stream()
                .anyMatch(e -> e.file().equals(projectSettings)
                    && Strings.CS.equals(e.path(), "model")
                    && Strings.CS.equals(e.message(), "Expected string, but received number")),
            report.invalidSettings().toString());
    }

    // ── plugin errors (loader errors → doctor section) ───────────────────────

    @Test
    void pluginErrors_badManifestPluginSurfacesFormattedEntry(@TempDir Path tmp) throws IOException {

        var dirs = new PluginDirectories(
            tmp.resolve("plugins"));
        var settings = new PluginSettingsStore(
            tmp.resolve("settings/user.json"),
            tmp.resolve("settings/project.json"),
            tmp.resolve("settings/local.json"),
            tmp.resolve("settings/policy.json"));
        var installedStore = new InstalledPluginsStore(
            dirs.installedPluginsFile());
        Path root = tmp.resolve("cache/badplug-mkt");
        Files.createDirectories(root.resolve(".claude-plugin"));
        Files.writeString(root.resolve(".claude-plugin").resolve("plugin.json"), "{ broken");
        String now = Instant.now().toString();
        installedStore.save(installedStore.load().withInstallation("badplug@mkt",
            new InstalledPlugins.InstallationEntry(
                PluginScope.USER,
                null, root.toString(), "1.0.0", now, now, null)));
        settings.setEnabledPlugin("badplug@mkt", true, PluginScope.USER);

        var loaderErrors = new PluginRuntimeLoader(
            dirs, settings, installedStore, () -> "sess").loadAll().errors();
        assertFalse(loaderErrors.isEmpty());

        Path home = fakeHome(tmp);
        var inputs = new DoctorDiagnosticsCollector.Inputs(
            tmp, home, List.of(), List.of(), loaderErrors.stream()
                .map(DoctorDiagnosticsCollector::formatPluginError).toList());
        DiagnosticReport report = collector.collect(inputs);


        assertTrue(report.pluginErrors().stream()
                .anyMatch(e -> Strings.CS.startsWith(e, "badplug@mkt [badplug]: Manifest parse error:")),
            report.pluginErrors().toString());
    }

    @Test
    void pluginErrors_formatFallsBackToUnknownSourceAndOmitsAbsentPlugin() {


        var err = new PluginError.MarketplaceNotFound(
            "", "mkt", List.of());
        assertEquals("unknown: Marketplace mkt not found",
            DoctorDiagnosticsCollector.formatPluginError(err));

        var withPlugin = new PluginError.PluginCacheMiss(
            "p@mkt", "p", "/cache/p");
        assertEquals("p@mkt [p]: Plugin \"p\" not cached at /cache/p — run /plugins to refresh",
            DoctorDiagnosticsCollector.formatPluginError(withPlugin));
    }

    // ── defensive baseline ───────────────────────────────────────────────────

    @Test
    void runtime_reportsNonBlankAppVersion(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, List.of(), List.of());
        DiagnosticReport report = collector.collect(inputs);
        // Manifest version in a jar run, else the "0.1.0" fallback — never blank.
        assertNotNull(report.runtime().appVersion());
        assertFalse(StringUtils.isBlank(report.runtime().appVersion()));
    }

    @Test
    void collect_neverThrowsForAllNullOrEmptyInputs(@TempDir Path tmp) throws IOException {
        Path home = fakeHome(tmp);
        var inputs = new DoctorDiagnosticsCollector.Inputs(tmp, home, null, null);
        assertDoesNotThrow(() -> collector.collect(inputs));
    }
}

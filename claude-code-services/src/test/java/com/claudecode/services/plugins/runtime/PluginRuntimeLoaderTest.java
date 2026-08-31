package com.claudecode.services.plugins.runtime;

import org.apache.commons.lang3.Strings;

import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.oauth.SecureStorage;
import com.claudecode.mcp.oauth.SecureStorageData;
import com.claudecode.services.hooks.HookEvent;
import com.claudecode.services.hooks.HookMatcher;
import com.claudecode.services.plugins.marketplace.InstalledPlugins;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.services.plugins.marketplace.PluginScope;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.config.SettingsSources;
import com.claudecode.runtime.plugins.PluginCommandDefinition;
import com.claudecode.core.agent.AgentSource;
import com.claudecode.core.agent.BuiltInAgentDefinitions;
import com.claudecode.core.prompt.OutputStyleConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * All paths run against {@code @TempDir} — the real {@code ~/.claude} is never
 * touched.
 */
class PluginRuntimeLoaderTest {

    @TempDir
    Path tmp;

    private PluginDirectories dirs;
    private PluginSettingsStore settings;
    private InstalledPluginsStore installedStore;
    private PluginRuntimeLoader loader;

    @BeforeEach
    void setUp() {
        dirs = new PluginDirectories(tmp.resolve("plugins"));
        settings = new PluginSettingsStore(
            tmp.resolve("settings/user.json"),
            tmp.resolve("settings/project.json"),
            tmp.resolve("settings/local.json"),
            tmp.resolve("settings/policy.json"));
        installedStore = new InstalledPluginsStore(dirs.installedPluginsFile());
        loader = new PluginRuntimeLoader(dirs, settings, installedStore, () -> "sess-42");
    }

    // ── fixture helpers ──────────────────────────────────────────────────────

    private Path installEnabledPlugin(String pluginId) throws IOException {
        Path root = tmp.resolve("cache").resolve(pluginId.replace('@', '-'));
        Files.createDirectories(root);
        registerInstallation(pluginId, root);
        settings.setEnabledPlugin(pluginId, true, PluginScope.USER);
        return root;
    }

    private void registerInstallation(String pluginId, Path root) {
        String now = Instant.now().toString();
        installedStore.save(installedStore.load().withInstallation(pluginId,
            new InstalledPlugins.InstallationEntry(
                PluginScope.USER, null, root.toString(), "1.0.0", now, now, null)));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private Optional<PluginCommandDefinition> command(PluginRuntimeSnapshot snapshot, String name) {
        return snapshot.commands().stream().filter(c -> c.name().equals(name)).findFirst();
    }

    // ── enumeration ──────────────────────────────────────────────────────────

    @Test
    void disabledPluginIsExcluded() throws IOException {
        Path root = tmp.resolve("cache/disabled-p");
        Files.createDirectories(root);
        write(root.resolve("commands/x.md"), "do X");
        registerInstallation("disabled-p@mkt", root);
        settings.setEnabledPlugin("disabled-p@mkt", false, PluginScope.USER);

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(0, snapshot.enabledCount());
        assertEquals(1, snapshot.disabledCount());
        assertTrue(snapshot.commands().isEmpty());
    }

    @Test
    void installedButNotInSettingsCountsAsDisabled() throws IOException {
        Path root = tmp.resolve("cache/orphan-p");
        Files.createDirectories(root);
        registerInstallation("orphan-p@mkt", root);

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(0, snapshot.enabledCount());
        assertEquals(1, snapshot.disabledCount());
    }

    @Test
    void enabledPluginSettingsBecomeLowestPrioritySettingsBase() throws Exception {
        Path root = installEnabledPlugin("settings-p@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"settings-p","settings":{"agent":"from-plugin","ignored":true}}
            """);
        try {
            loader.loadAll();
            assertEquals("from-plugin", SettingsSources.pluginSettingsBaseSnapshot()
                .path("agent").asText());
            assertFalse(SettingsSources.pluginSettingsBaseSnapshot().has("ignored"));
        } finally {
            SettingsSources.clearPluginSettingsBase();
        }
    }

    @Test
    void missingInstallPathReportsCacheMiss() {
        registerInstallation("gone-p@mkt", tmp.resolve("cache/does-not-exist"));
        settings.setEnabledPlugin("gone-p@mkt", true, PluginScope.USER);

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(0, snapshot.enabledCount());
        assertTrue(snapshot.errors().stream()
            .anyMatch(PluginError.PluginCacheMiss.class::isInstance));
    }

    @Test
    void corruptManifestReportsParseErrorAndSkipsComponents() throws IOException {
        Path root = installEnabledPlugin("bad-p@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), "{not valid json");
        write(root.resolve("commands/x.md"), "do X");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(snapshot.errors().stream()
            .anyMatch(PluginError.ManifestParseError.class::isInstance));
        assertTrue(snapshot.commands().isEmpty(),
            "components of a corrupt-manifest plugin must be skipped");
    }

    @Test
    void sessionOnlyPluginLoadsDirectlyAndUsesManifestName() throws IOException {
        Path root = tmp.resolve("inline/source-directory-name");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"manifest-name","version":"1.2.3"}
            """);
        write(root.resolve("commands/ping.md"), """
            ---
            description: Inline ping
            ---
            Ping $ARGUMENTS
            """);

        PluginRuntimeLoader inlineLoader = new PluginRuntimeLoader(
            dirs, settings, installedStore, () -> "sess-42", List.of(root));
        PluginRuntimeSnapshot snapshot = inlineLoader.loadAll();

        assertEquals(1, snapshot.enabledCount());
        PluginCommandDefinition command = command(snapshot, "manifest-name:ping").orElseThrow();
        assertEquals("manifest-name", command.pluginName());
        assertEquals("manifest-name@inline", command.source());
        assertEquals(root.toAbsolutePath().normalize().toString(), command.loadedFrom());
    }

    @Test
    void sessionOnlyPluginCanLoadEverythingExceptMcpServers() throws IOException {
        Path root = tmp.resolve("inline/no-mcp");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"no-mcp","mcpServers":{"local":{"command":"echo"}}}
            """);
        write(root.resolve("commands/ping.md"), "Ping");

        PluginRuntimeLoader inlineLoader = new PluginRuntimeLoader(
            dirs, settings, installedStore, () -> "sess-42", List.of(root), List.of(root));
        PluginRuntimeSnapshot snapshot = inlineLoader.loadAll();

        assertTrue(command(snapshot, "no-mcp:ping").isPresent());
        assertTrue(snapshot.mcpServers().isEmpty());
    }

    @Test
    void installedPluginRetainsMarketplaceIdentityAndInstallPathForSdkInit() throws IOException {
        Path root = installEnabledPlugin("wire@fixture");
        write(root.resolve("commands/ping.md"), "Ping");

        PluginCommandDefinition command = command(loader.loadAll(), "wire:ping").orElseThrow();

        assertEquals("wire@fixture", command.source());
        assertEquals(root.toAbsolutePath().normalize().toString(), command.loadedFrom());
    }

    @Test
    void loadsDefaultAndManifestDeclaredPluginWorkflowsWithPluginNamespace() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("workflows/default.js"), """
            export const meta = { name: 'audit', description: 'Audit defaults' };
            return 'default';
            """);
        write(root.resolve("extras/custom.js"), """
            export const meta = { name: 'verify', description: 'Verify custom' };
            return 'custom';
            """);
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"myplugin","workflows":"./extras/custom.js"}
            """);

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertEquals(List.of("myplugin:audit", "myplugin:verify"),
            snapshot.workflows().stream().map(w -> w.metadata().name()).sorted().toList());
        assertTrue(snapshot.workflows().stream().allMatch(w -> Strings.CS.equals("myplugin", w.pluginName())));
    }

    // ── commands ─────────────────────────────────────────────────────────────

    @Test
    void loadsCommandsFromDefaultDirectoryWithNamespaces() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/build.md"), """
            ---
            description: Build the project
            argument-hint: "[target]"
            ---
            Build now: $ARGUMENTS""");
        write(root.resolve("commands/git/commit.md"), "Commit helper");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.enabledCount());

        PluginCommandDefinition build = command(snapshot, "myplugin:build").orElseThrow();
        assertEquals("Build the project", build.description());
        assertEquals("[target]", build.argumentHint());
        assertEquals("Build now: $ARGUMENTS", build.prompt());
        assertEquals("myplugin", build.pluginName());
        assertFalse(build.hidden());

        assertTrue(command(snapshot, "myplugin:git:commit").isPresent(),
            "subdirectory becomes ':'-separated namespace");
    }

    @Test
    void skillDirectoryInsideCommandsDirUsesDirectoryName() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/deploy/SKILL.md"), """
            ---
            description: Deploy skill
            ---
            Deploy content""");
        write(root.resolve("commands/deploy/helper.md"), "should be ignored");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(command(snapshot, "myplugin:deploy").isPresent());
        assertTrue(command(snapshot, "myplugin:deploy:helper").isEmpty(),
            "skill directories are leaf containers — sibling .md files are not commands");
    }

    @Test
    void descriptionFallsBackToFirstContentLine() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/plain.md"), "\n# Do the thing\n\nBody text");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals("Do the thing",
            command(snapshot, "myplugin:plain").orElseThrow().description());
    }

    @Test
    void commandPromptGetsVariableSubstitution() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        settings.setPluginConfig("myplugin@mkt", JsonUtils.getMapper().readTree(
            "{\"options\": {\"endpoint\": \"https://api.example.com\"}}"));
        write(root.resolve("commands/call.md"),
            "Run ${CLAUDE_PLUGIN_ROOT}/bin/x against ${user_config.endpoint} in ${CLAUDE_SESSION_ID}");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        String prompt = command(snapshot, "myplugin:call").orElseThrow().prompt();
        assertEquals("Run " + root
            + "/bin/x against https://api.example.com in ${CLAUDE_SESSION_ID}", prompt);
    }

    @Test
    void securePluginOptionOverridesLegacySettingsDuringRuntimeSubstitution() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        settings.setPluginConfig("myplugin@mkt", JsonUtils.getMapper().readTree(
            "{\"options\": {\"token\": \"legacy-plaintext\"}}"));
        SecureStorage storage = new SecureStorage() {
            @Override public String name() { return "test"; }
            @Override public Optional<SecureStorageData> read() {
                return Optional.of(new SecureStorageData(null, null,
                    Map.of("myplugin@mkt", Map.of("token", "secure-token")), null));
            }
            @Override public Optional<String> update(SecureStorageData ignored) { return Optional.empty(); }
            @Override public boolean delete() { return true; }
        };
        loader = new PluginRuntimeLoader(dirs, settings, installedStore, () -> "sess-42", storage);
        write(root.resolve("commands/call.md"), "Token: ${user_config.token}");

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertEquals("Token: secure-token",
            command(snapshot, "myplugin:call").orElseThrow().prompt());
    }

    @Test
    void userInvocableFalseMarksCommandHidden() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/internal.md"), """
            ---
            description: internal
            user-invocable: false
            ---
            secret""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(command(snapshot, "myplugin:internal").orElseThrow().hidden());
    }

    @Test
    void preservesCompletePromptCommandFrontmatterEnvelope() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/full.md"), """
            ---
            description: Full command
            allowed-tools: Read, Edit(${CLAUDE_PLUGIN_ROOT}/**)
            model: sonnet
            effort: medium
            shell: PowerShell
            disable-model-invocation: true
            when_to_use: Use for full checks
            version: 2.0.0
            name: Friendly Full
            ---
            Run checks
            """);

        PluginCommandDefinition def = command(loader.loadAll(), "myplugin:full").orElseThrow();

        assertAll(
            () -> assertEquals(List.of("Read", "Edit(" + root + "/**)"), def.allowedTools()),
            () -> assertEquals("sonnet", def.model()),
            () -> assertEquals("medium", def.effort()),
            () -> assertEquals("powershell", def.shell()),
            () -> assertTrue(def.disableModelInvocation()),
            () -> assertEquals("Use for full checks", def.whenToUse()),
            () -> assertEquals("2.0.0", def.version()),
            () -> assertEquals("Friendly Full", def.userFacingName()),
            () -> assertEquals("running", def.progressMessage()),
            () -> assertEquals("myplugin@mkt", def.source()),
            () -> assertEquals(root.toAbsolutePath().normalize().toString(), def.loadedFrom()),
            () -> assertTrue(def.hasUserSpecifiedDescription()),
            () -> assertEquals("Run checks\n", def.prompt()),
            () -> assertEquals(def.prompt().length(), def.contentLength())
        );
    }

    @Test
    void userInvocableDefaultsToVisibleAndAcceptsOnlyExactTrue() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("commands/default.md"), "default body");
        write(root.resolve("commands/literal-true.md"), """
            ---
            user-invocable: true
            ---
            literal true body""");
        write(root.resolve("commands/quoted-true.md"), """
            ---
            user-invocable: "true"
            ---
            quoted true body""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertAll(
            () -> assertFalse(command(snapshot, "myplugin:default").orElseThrow().hidden()),
            () -> assertFalse(command(snapshot, "myplugin:literal-true").orElseThrow().hidden()),
            () -> assertFalse(command(snapshot, "myplugin:quoted-true").orElseThrow().hidden())
        );
    }

    @Test
    void everyOtherExplicitUserInvocableValueIsHidden() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        Map<String, String> values = Map.of(
            "literal-false", "false",
            "quoted-false", "\"false\"",
            "invalid", "sometimes",
            "uppercase-true", "TRUE",
            "numeric-one", "1"
        );
        for (Map.Entry<String, String> entry : values.entrySet()) {
            write(root.resolve("commands/" + entry.getKey() + ".md"), """
                ---
                user-invocable: %s
                ---
                body
                """.formatted(entry.getValue()));
        }

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertAll(values.keySet().stream()
            .map(name -> () -> assertTrue(
                command(snapshot, "myplugin:" + name).orElseThrow().hidden(),
                "explicit user-invocable value for " + name + " must not be user-visible")));
    }

    @Test
    void manifestCommandMetadataMappingSupportsSourceAndInlineContent() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("docs/about.md"), "All about this plugin");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {
              "name": "myplugin",
              "commands": {
                "about": {"source": "./docs/about.md", "description": "About override"},
                "hi": {"content": "---\\ndescription: inline hello\\n---\\nHello there"},
                "broken": {"source": "./missing.md"}
              }
            }""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        PluginCommandDefinition about = command(snapshot, "myplugin:about").orElseThrow();
        assertEquals("About override", about.description());
        assertEquals("All about this plugin", about.prompt());

        PluginCommandDefinition hi = command(snapshot, "myplugin:hi").orElseThrow();
        assertEquals("inline hello", hi.description());
        assertEquals("Hello there", hi.prompt());

        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.PathNotFound p
                && p.component() == PluginError.Component.COMMANDS));
    }

    // ── agents ───────────────────────────────────────────────────────────────

    @Test
    void loadsAgentsWithPluginSourceAndSubstitutedPrompt() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("agents/reviewer.md"), """
            ---
            description: Reviews code carefully
            tools: Read, Grep
            model: haiku
            color: blue
            maxTurns: 12
            permissionMode: bypassPermissions
            ---
            Review files under ${CLAUDE_PLUGIN_ROOT}/rules.""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.agents().size());
        BuiltInAgentDefinitions.AgentDefinition agent = snapshot.agents().getFirst();
        assertEquals("myplugin:reviewer", agent.agentType());
        assertEquals(AgentSource.PLUGIN, agent.source());
        assertEquals("Reviews code carefully", agent.whenToUse());
        assertEquals(List.of("Read", "Grep"), agent.tools());
        assertEquals("haiku", agent.model());
        assertEquals("blue", agent.color());
        assertEquals(12, agent.maxTurns());
        assertEquals("Review files under " + root + "/rules.", agent.systemPrompt());
        // permissionMode is deliberately ignored for plugin agents (PR #22558) —
        // the definition carries no escalation from it.
        assertTrue(agent.mcpServers().isEmpty());
    }

    @Test
    void agentNamespaceComesFromSubdirectories() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("agents/review/deep.md"), """
            ---
            description: deep reviewer
            ---
            body""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals("myplugin:review:deep", snapshot.agents().getFirst().agentType());
    }

    @Test
    void agentWithoutDescriptionGetsDefaultWhenToUse() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("agents/bare.md"), "just a body");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals("Agent from myplugin plugin", snapshot.agents().getFirst().whenToUse());
    }

    @Test
    void manifestAgentPathMissingReportsPathNotFound() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "agents": "./nope/agents"}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.PathNotFound p
                && p.component() == PluginError.Component.AGENTS));
    }

    // ── skills ───────────────────────────────────────────────────────────────

    @Test
    void collectsDefaultSkillsDirectory() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("skills/pdf/SKILL.md"), "---\ndescription: pdf\n---\nbody");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.skillDirs().size());
        assertEquals("myplugin", snapshot.skillDirs().getFirst().pluginName());
        assertEquals(root.resolve("skills"), snapshot.skillDirs().getFirst().directory());
    }

    @Test
    void collectsManifestlessDirectSkillRoot() throws IOException {
        Path root = installEnabledPlugin("dataset-skill@mkt");
        write(root.resolve("SKILL.md"), "---\ndescription: dataset helper\n---\nbody");

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertEquals(1, snapshot.skillDirs().size());
        assertEquals("dataset-skill", snapshot.skillDirs().getFirst().pluginName());
        assertEquals(root, snapshot.skillDirs().getFirst().directory(),
            "a cache root containing SKILL.md is itself a plugin skill root");
        assertEquals("dataset-skill", snapshot.skillDirs().getFirst().directSkillName(),
            "cache version/hash directory must not leak into the model-visible skill name");
    }

    @Test
    void manifestSkillPathMissingReportsPathNotFound() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "skills": ["./no-such-skills"]}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(snapshot.skillDirs().isEmpty());
        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.PathNotFound p
                && p.component() == PluginError.Component.SKILLS));
    }

    // ── output styles ───────────────────────────────────────────────────────

    @Test
    void loadsDefaultAndManifestOutputStylesWithPluginNamespace() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("output-styles/teaching.md"), """
            ---
            name: Teaching
            description: Explains the code
            force-for-plugin: true
            ---
            Teach while implementing.
            """);
        write(root.resolve("extras/terse.md"), """
            ---
            description: Keeps replies terse
            ---
            Be terse.
            """);
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "outputStyles": "./extras/terse.md"}
            """);

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertEquals(2, snapshot.outputStyles().size());
        OutputStyleConfig teaching = snapshot.outputStyles().stream()
            .filter(style -> Strings.CS.equals(style.name(), "myplugin:Teaching"))
            .findFirst().orElseThrow();
        assertAll(
            () -> assertEquals("Explains the code", teaching.description()),
            () -> assertEquals("Teach while implementing.", teaching.prompt()),
            () -> assertEquals(OutputStyleConfig.Source.PLUGIN, teaching.source()),
            () -> assertTrue(teaching.forceForPlugin()),
            () -> assertFalse(teaching.keepCodingInstructions())
        );
        assertTrue(snapshot.outputStyles().stream()
            .anyMatch(style -> Strings.CS.equals(style.name(), "myplugin:terse")));
    }

    @Test
    void duplicateOutputStylePathIsLoadedOnlyOnce() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("output-styles/one.md"), "Body");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "outputStyles": "./output-styles"}
            """);

        assertEquals(1, loader.loadAll().outputStyles().size());
    }

    // ── hooks ────────────────────────────────────────────────────────────────

    @Test
    void loadsHooksFromStandardFileWithVariableSubstitution() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("hooks/hooks.json"), """
            {
              "description": "my hooks",
              "hooks": {
                "PreToolUse": [
                  {"matcher": "Bash",
                   "hooks": [{"type": "command", "command": "${CLAUDE_PLUGIN_ROOT}/check.sh"}]}
                ]
              }
            }""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        List<HookMatcher> matchers = snapshot.hooks().get(HookEvent.PRE_TOOL_USE);
        assertNotNull(matchers);
        assertEquals(1, matchers.size());
        assertEquals("Bash", matchers.getFirst().matcher().orElseThrow());
        assertEquals(1, snapshot.hookCommandCount());
        assertTrue(Strings.CS.contains(matchers.getFirst().hooks().getFirst().toString(), root.toString()),
            "hook command should have ${CLAUDE_PLUGIN_ROOT} substituted");
    }

    @Test
    void manifestInlineHooksAreMerged() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {
              "name": "myplugin",
              "hooks": {
                "SessionStart": [
                  {"hooks": [{"type": "command", "command": "echo hi"}]}
                ]
              }
            }""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertNotNull(snapshot.hooks().get(HookEvent.SESSION_START));
        assertEquals(1, snapshot.hookCommandCount());
    }

    @Test
    void duplicateHookFileInManifestReportsError() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve("hooks/hooks.json"), """
            {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "echo bye"}]}]}}""");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "hooks": "./hooks/hooks.json"}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.hookCommandCount(), "duplicate file must not double-register");
        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.HookLoadFailed h
                && Strings.CS.contains(h.reason(), "Duplicate hooks file")));
    }

    // ── MCP servers ──────────────────────────────────────────────────────────

    @Test
    void loadsMcpServersWithScopedNamesAndEnvInjection() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        settings.setPluginConfig("myplugin@mkt", JsonUtils.getMapper().readTree(
            "{\"options\": {\"apiKey\": \"k-123\"}}"));
        write(root.resolve(".mcp.json"), """
            {
              "mcpServers": {
                "srv": {
                  "command": "${CLAUDE_PLUGIN_ROOT}/bin/server",
                  "args": ["--data", "${CLAUDE_PLUGIN_DATA}"],
                  "env": {"API_KEY": "${user_config.apiKey}"}
                }
              }
            }""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.mcpServers().size());
        McpServerConfig config = snapshot.mcpServers().getFirst();
        assertEquals("plugin:myplugin:srv", config.name());
        assertEquals(root + "/bin/server", config.command());
        assertEquals(List.of("--data", dirs.pluginDataDir("myplugin@mkt").toString()),
            config.args());
        assertEquals("k-123", config.env().get("API_KEY"));
        assertEquals(root.toString(), config.env().get("CLAUDE_PLUGIN_ROOT"));
        assertEquals(dirs.pluginDataDir("myplugin@mkt").toString(),
            config.env().get("CLAUDE_PLUGIN_DATA"));
    }

    // ── LSP servers ──────────────────────────────────────────────────────────

    @Test
    void loadsLspServersWithScopedNamesAndEnvInjection() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        settings.setPluginConfig("myplugin@mkt", JsonUtils.getMapper().readTree(
            "{\"options\": {\"apiKey\": \"k-123\"}}"));
        write(root.resolve(".lsp.json"), """
            {
              "lspServers": {
                "srv": {
                  "command": "${CLAUDE_PLUGIN_ROOT}/bin/server",
                  "args": ["--data", "${CLAUDE_PLUGIN_DATA}"],
                  "env": {"API_KEY": "${user_config.apiKey}"},
                  "extensionToLanguage": {".ts": "typescript"},
                  "transport": "stdio",
                  "workspaceFolder": "${CLAUDE_PLUGIN_ROOT}"
                }
              }
            }""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.lspServers().size());
        JsonNode server = snapshot.lspServers().get("plugin:myplugin:srv");
        assertNotNull(server);
        assertEquals(root + "/bin/server", server.get("command").asText());
        assertEquals("--data", server.get("args").get(0).asText());
        assertEquals(dirs.pluginDataDir("myplugin@mkt").toString(),
            server.get("args").get(1).asText());
        assertEquals("k-123", server.get("env").get("API_KEY").asText());
        assertEquals(root.toString(), server.get("env").get("CLAUDE_PLUGIN_ROOT").asText());
        assertEquals(dirs.pluginDataDir("myplugin@mkt").toString(),
            server.get("env").get("CLAUDE_PLUGIN_DATA").asText());
        assertEquals("typescript", server.get("extensionToLanguage").get(".ts").asText());
        assertEquals("stdio", server.get("transport").asText());
        assertEquals(root.toString(), server.get("workspaceFolder").asText());
    }

    @Test
    void manifestLspServersOverrideDotLspJson() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".lsp.json"), """
            {"lspServers": {"srv": {"command": "low-priority",
              "extensionToLanguage": {".ts": "typescript"}}}}""");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "lspServers": {"srv": {"command": "high-priority",
              "extensionToLanguage": {".ts": "typescript"}}}}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.lspServers().size());
        assertEquals("high-priority",
            snapshot.lspServers().get("plugin:myplugin:srv").get("command").asText());
    }

    @Test
    void lspServersStringPathOutsidePluginIsRejected() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "lspServers": "../../etc/secrets.json"}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(snapshot.lspServers().isEmpty());
        assertTrue(snapshot.errors().stream()
            .anyMatch(PluginError.LspConfigInvalid.class::isInstance));
    }

    @Test
    void manifestMcpServersOverrideDotMcpJson() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".mcp.json"), """
            {"mcpServers": {"srv": {"command": "low-priority"}}}""");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name": "myplugin", "mcpServers": {"srv": {"command": "high-priority"}}}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.mcpServers().size());
        assertEquals("high-priority", snapshot.mcpServers().getFirst().command());
    }

    @Test
    void remoteMcpServerKeepsUrlAndHeaders() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".mcp.json"), """
            {"mcpServers": {"remote": {"type": "sse", "url": "https://x.example/sse",
             "headers": {"Authorization": "Bearer t"}}}}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        McpServerConfig config = snapshot.mcpServers().getFirst();
        assertEquals("sse", config.transportType());
        assertEquals("https://x.example/sse", config.url());
        assertEquals("Bearer t", config.headers().get("Authorization"));
        assertNull(config.command());
    }

    @Test
    void missingEnvVarInMcpConfigReportsError() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".mcp.json"), """
            {"mcpServers": {"srv": {"command": "${DEFINITELY_NOT_SET_VAR_98765}/x"}}}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(1, snapshot.mcpServers().size(), "server still loads with literal value");
        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.McpConfigInvalid m
                && Strings.CS.contains(m.validationError(), "DEFINITELY_NOT_SET_VAR_98765")));
    }

    @Test
    void missingUserConfigInMcpConfigDropsServerWithGenericError() throws IOException {
        Path root = installEnabledPlugin("myplugin@mkt");
        write(root.resolve(".mcp.json"), """
            {"mcpServers": {"srv": {"command": "x", "env": {"K": "${user_config.never_set}"}}}}""");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertTrue(snapshot.mcpServers().isEmpty());
        assertTrue(snapshot.errors().stream().anyMatch(e ->
            e instanceof PluginError.GenericError g
                && Strings.CS.contains(g.getMessage(), "never_set")));
    }

    @Test
    void channelUserConfigOverridesTopLevelForItsMcpServer() throws IOException {
        Path root = installEnabledPlugin("chat@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"chat",
             "userConfig":{"room":{"type":"string","title":"Room",
               "description":"Default room","required":true}},
             "channels":[{"server":"telegram","displayName":"Telegram",
               "userConfig":{"room":{"type":"string","title":"Telegram room",
                 "description":"Channel room","required":true}}}],
             "mcpServers":{"telegram":{"command":"bot","args":["${user_config.room}"]}}}
            """);
        ObjectNode pluginConfig = JsonUtils.getMapper().createObjectNode();
        pluginConfig.putObject("options").put("room", "general");
        pluginConfig.putObject("mcpServers").putObject("telegram").put("room", "alerts");
        settings.setPluginConfig("chat@mkt", pluginConfig);

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertEquals(1, snapshot.mcpServers().size());
        assertEquals("alerts", snapshot.mcpServers().getFirst().args().getFirst(),
            "channel-specific configuration must win over top-level options");
    }

    @Test
    void dxtMcpServerLoadsAndRequiredConfigurationControlsActivation() throws Exception {
        Path root = installEnabledPlugin("bundle@mkt");
        write(root.resolve(".claude-plugin/plugin.json"), """
            {"name":"bundle","mcpServers":"server.dxt"}
            """);
        Files.write(root.resolve("server.dxt"), mcpb(Map.of(
            "manifest.json", """
                {"manifest_version":"0.4","name":"bundled","version":"1.0.0",
                 "description":"Bundled server","author":{"name":"Tester"},
                 "server":{"type":"binary","entry_point":"bin/server",
                   "mcp_config":{"command":"${__dirname}/bin/server",
                     "args":["${user_config.channel}"]}},
                 "user_config":{"channel":{"type":"string","title":"Channel",
                   "description":"Channel name","required":true}}}
                """,
            "bin/server", "binary")));

        PluginRuntimeSnapshot unconfigured = loader.loadAll();
        assertTrue(unconfigured.mcpServers().isEmpty());
        assertTrue(unconfigured.errors().isEmpty(), "needs-config is a normal inactive state");

        ObjectNode pluginConfig = JsonUtils.getMapper().createObjectNode();
        pluginConfig.putObject("mcpServers").putObject("bundled").put("channel", "stable");
        settings.setPluginConfig("bundle@mkt", pluginConfig);
        PluginRuntimeSnapshot configured = loader.loadAll();

        assertEquals(1, configured.mcpServers().size());
        McpServerConfig server = configured.mcpServers().getFirst();
        assertEquals("plugin:bundle:bundled", server.name());
        assertEquals("stable", server.args().getFirst());
        assertTrue(Strings.CS.contains(server.command(), ".mcpb-cache"));
        assertTrue(Files.isRegularFile(Path.of(server.command())));
    }

    @Test
    void malformedMcpbProducesTypedManifestError() throws Exception {
        Path root = installEnabledPlugin("bad@mkt");
        write(root.resolve(".claude-plugin/plugin.json"),
            "{\"name\":\"bad\",\"mcpServers\":\"bad.mcpb\"}");
        Files.write(root.resolve("bad.mcpb"), mcpb(Map.of("readme.txt", "missing manifest")));

        PluginRuntimeSnapshot snapshot = loader.loadAll();

        assertTrue(snapshot.mcpServers().isEmpty());
        assertTrue(snapshot.errors().stream().anyMatch(
            PluginError.McpbInvalidManifest.class::isInstance));
    }

    @Test
    void multiplePluginsLoadIndependently() throws IOException {
        Path rootA = installEnabledPlugin("alpha@mkt");
        write(rootA.resolve("commands/a.md"), "A");
        Path rootB = installEnabledPlugin("beta@mkt");
        write(rootB.resolve("commands/b.md"), "B");
        // beta has a corrupt manifest — alpha must be unaffected
        write(rootB.resolve(".claude-plugin/plugin.json"), "{oops");

        PluginRuntimeSnapshot snapshot = loader.loadAll();
        assertEquals(2, snapshot.enabledCount());
        assertTrue(command(snapshot, "alpha:a").isPresent());
        assertTrue(command(snapshot, "beta:b").isEmpty());
        assertEquals(1, snapshot.errors().size());
    }

    private static byte[] mcpb(Map<String, String> files) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}

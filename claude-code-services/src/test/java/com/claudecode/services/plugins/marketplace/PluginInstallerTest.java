package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.claudecode.services.http.ServiceHttpClient;
import com.claudecode.core.serialization.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end install/enable/disable/uninstall against a directory marketplace
 * with relative-path plugins, plus git-source command assembly via fake executor.
 */
class PluginInstallerTest {

    @TempDir
    Path tempDir;

    private Path pluginsRoot;
    private Path userSettings;
    private Path projectSettings;
    private String projectPath;
    private PluginSettingsStore settings;
    private FakeGitExecutor git;
    private MarketplaceManager manager;
    private PluginInstaller installer;

    @BeforeEach
    void setUp() throws IOException {
        pluginsRoot = tempDir.resolve("plugins-root");
        userSettings = tempDir.resolve("settings/user.json");
        projectSettings = tempDir.resolve("project/.claude/settings.json");
        projectPath = tempDir.resolve("project").toString();
        settings = new PluginSettingsStore(
            userSettings,
            projectSettings,
            tempDir.resolve("project/.claude/settings.local.json"),
            tempDir.resolve("settings/managed-settings.json"));
        git = FakeGitExecutor.alwaysFailing();
        manager = new MarketplaceManager(pluginsRoot, git, ServiceHttpClient.noRedirects(), settings);
        installer = new PluginInstaller(manager, git, projectPath);

        manager.add(new MarketplaceSource.Directory(createMarketplaceDir().toString()));
    }

    private Path createMarketplaceDir() throws IOException {
        Path mkt = tempDir.resolve("fixtures/test-mkt");
        Files.createDirectories(mkt.resolve(".claude-plugin"));
        Files.writeString(mkt.resolve(".claude-plugin/marketplace.json"), """
            {
              "name": "test-mkt",
              "owner": {"name": "Tester"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello"},
                {"name": "no-manifest", "source": "./plugins/bare", "version": "0.5.0", "strict": false,
                 "mcpServers": "server.mcpb"},
                {"name": "versionless", "source": "./plugins/versionless"},
                {"name": "escaper", "source": "./../outside"},
                {"name": "from-git", "source": {"source": "url", "url": "https://example.com/plug.git"}}
              ]
            }
            """);
        writePlugin(mkt.resolve("plugins/hello"), "{\"name\": \"hello\", \"version\": \"1.2.3\"}");
        Files.createDirectories(mkt.resolve("plugins/bare"));
        Files.writeString(mkt.resolve("plugins/bare/README.md"), "bare plugin");
        writePlugin(mkt.resolve("plugins/versionless"), "{\"name\": \"versionless\"}");
        return mkt;
    }

    private static void writePlugin(Path pluginDir, String manifestJson) throws IOException {
        Files.createDirectories(pluginDir.resolve(".claude-plugin"));
        Files.writeString(pluginDir.resolve(".claude-plugin/plugin.json"), manifestJson);
        Files.createDirectories(pluginDir.resolve("commands"));
        Files.writeString(pluginDir.resolve("commands/hi.md"), "# hi");
    }

    // ── install ───────────────────────────────────────────────────────────────

    @Test
    void installRelativePathPluginEndToEnd() throws Exception {
        PluginInstaller.InstallResult result =
            installer.install("hello", "test-mkt", PluginScope.USER);

        assertEquals("hello@test-mkt", result.pluginId());
        assertEquals("1.2.3", result.version());

        // Immutable versioned snapshot at cache/<marketplace>/<plugin>/<version>/.
        Path expected = pluginsRoot.resolve("cache/test-mkt/hello/1.2.3");
        assertEquals(expected, result.installPath());
        assertTrue(Files.isRegularFile(expected.resolve(".claude-plugin/plugin.json")));
        assertTrue(Files.isRegularFile(expected.resolve("commands/hi.md")));


        InstalledPlugins installed =
            new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json")).load();
        assertEquals(2, installed.version());
        InstalledPlugins.InstallationEntry entry = installed.installationsOf("hello@test-mkt").getFirst();
        assertEquals(PluginScope.USER, entry.scope());
        assertEquals(expected.toString(), entry.installPath());
        assertEquals("1.2.3", entry.version());
        assertNull(entry.projectPath());

        // enabledPlugins["hello@test-mkt"] = true in the user tier.
        JsonNode settingsJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertTrue(settingsJson.get("enabledPlugins").get("hello@test-mkt").asBoolean());
    }

    @Test
    void installWithoutManifestPersistsEffectiveMarketplaceManifest() throws Exception {
        PluginInstaller.InstallResult result =
            installer.install("no-manifest", "test-mkt", PluginScope.USER);

        assertEquals("0.5.0", result.version());
        assertTrue(Files.isRegularFile(
            pluginsRoot.resolve("cache/test-mkt/no-manifest/0.5.0/README.md")));
        Path effectiveManifest = result.installPath().resolve(".claude-plugin/plugin.json");
        assertTrue(Files.isRegularFile(effectiveManifest),
            "runtime reload must retain the marketplace-entry fallback manifest");
        JsonNode persisted = JsonUtils.getMapper().readTree(effectiveManifest.toFile());
        assertEquals("no-manifest", persisted.path("name").asText());
        assertEquals("0.5.0", persisted.path("version").asText());
        assertEquals("server.mcpb", persisted.path("mcpServers").asText());
    }

    @Test
    void installWithoutAnyVersionUsesUnknown() {
        PluginInstaller.InstallResult result =
            installer.install("versionless", "test-mkt", PluginScope.USER);
        assertEquals("unknown", result.version());
    }

    @Test
    void updateMarketplacePluginsReplacesEveryInstalledScopeWithoutEnablingIt() throws Exception {
        installer.install("hello", "test-mkt", PluginScope.USER);
        installer.install("hello", "test-mkt", PluginScope.PROJECT);
        installer.disable("hello@test-mkt", PluginScope.USER);
        Path sourceManifest = tempDir.resolve("fixtures/test-mkt/plugins/hello/.claude-plugin/plugin.json");
        Files.writeString(sourceManifest, "{\"name\": \"hello\", \"version\": \"2.0.0\"}");
        manager.update("test-mkt");

        List<String> updated = installer.updateMarketplacePlugins("test-mkt");

        assertEquals(List.of("hello@test-mkt"), updated);
        List<InstalledPlugins.InstallationEntry> entries = new InstalledPluginsStore(
            pluginsRoot.resolve("installed_plugins.json")).load().installationsOf("hello@test-mkt");
        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> Strings.CS.equals(entry.version(), "2.0.0")));
        assertTrue(entries.stream().allMatch(entry -> Strings.CS.endsWith(entry.installPath(), "/hello/2.0.0")));
        JsonNode settingsJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertFalse(settingsJson.get("enabledPlugins").get("hello@test-mkt").asBoolean(),
            "update must preserve the disabled state");
    }

    @Test
    void updatePluginChangesOnlyRequestedScope() throws Exception {
        installer.install("hello", "test-mkt", PluginScope.USER);
        installer.install("hello", "test-mkt", PluginScope.PROJECT);
        Path sourceManifest = tempDir.resolve("fixtures/test-mkt/plugins/hello/.claude-plugin/plugin.json");
        Files.writeString(sourceManifest, "{\"name\": \"hello\", \"version\": \"3.0.0\"}");

        PluginInstaller.UpdateResult result = installer.updatePlugin(
            "hello@test-mkt", PluginScope.PROJECT);

        assertTrue(result.updated());
        assertEquals("3.0.0", result.version());
        List<InstalledPlugins.InstallationEntry> entries = new InstalledPluginsStore(
            pluginsRoot.resolve("installed_plugins.json")).load().installationsOf("hello@test-mkt");
        assertEquals("1.2.3", entries.stream()
            .filter(entry -> entry.scope() == PluginScope.USER).findFirst().orElseThrow().version());
        assertEquals("3.0.0", entries.stream()
            .filter(entry -> entry.scope() == PluginScope.PROJECT).findFirst().orElseThrow().version());
    }

    @Test
    void installAtProjectScopeRecordsProjectPathAndWritesProjectSettings() throws Exception {
        installer.install("hello", "test-mkt", PluginScope.PROJECT);

        InstalledPlugins installed =
            new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json")).load();
        assertEquals(projectPath, installed.installationsOf("hello@test-mkt").getFirst().projectPath());

        JsonNode projectJson = JsonUtils.getMapper().readTree(projectSettings.toFile());
        assertTrue(projectJson.get("enabledPlugins").get("hello@test-mkt").asBoolean());
        assertFalse(Files.exists(userSettings), "user tier must stay untouched");
    }

    @Test
    void installRejectsPathTraversalSource() {
        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> installer.install("escaper", "test-mkt", PluginScope.USER));
        assertEquals("Path traversal detected: \"./../outside\" would escape the base directory",
            error.getMessage());
    }

    @Test
    void installUnknownPluginUsesPluginNotFoundMessage() {
        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> installer.install("ghost", "test-mkt", PluginScope.USER));
        assertEquals("Plugin ghost@test-mkt not found in marketplace test-mkt", error.getMessage());
    }

    @Test
    void installFromGitUrlClonesAndVersionsFromSha() {
        String sha = "abcdef0123456789abcdef0123456789abcdef01";
        FakeGitExecutor cloningGit = new FakeGitExecutor((_, args) -> {
            if (args.contains("clone")) {
                Path target = Path.of(args.getLast());
                try {
                    Files.createDirectories(target.resolve(".claude-plugin"));
                    Files.writeString(target.resolve(".claude-plugin/plugin.json"),
                        "{\"name\": \"from-git\"}");
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return new GitExecutor.GitResult(0, "", "");
            }
            if (args.contains("rev-parse")) {
                return new GitExecutor.GitResult(0, sha + "\n", "");
            }
            return new GitExecutor.GitResult(128, "", "unexpected command");
        });
        PluginInstaller gitInstaller = new PluginInstaller(manager, cloningGit, projectPath);

        PluginInstaller.InstallResult result =
            gitInstaller.install("from-git", "test-mkt", PluginScope.USER);

        assertEquals(sha.substring(0, 12), result.version());
        List<String> clone = cloningGit.invocationsMatching("clone").getFirst();
        assertTrue(clone.containsAll(List.of("clone", "--depth", "1")));
        assertEquals("https://example.com/plug.git", clone.get(clone.size() - 2));
    }

    // ── enable / disable ──────────────────────────────────────────────────────

    @Test
    void enableDisableFlipTheSettingsKey() throws Exception {
        installer.enable("hello@test-mkt", PluginScope.USER);
        JsonNode afterEnable = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertTrue(afterEnable.get("enabledPlugins").get("hello@test-mkt").asBoolean());

        installer.disable("hello@test-mkt", PluginScope.USER);
        JsonNode afterDisable = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertFalse(afterDisable.get("enabledPlugins").get("hello@test-mkt").asBoolean());
    }

    // ── uninstall ─────────────────────────────────────────────────────────────

    @Test
    void uninstallRemovesRecordAndSettingsKeyButKeepsCache() throws Exception {
        PluginInstaller.InstallResult result =
            installer.install("hello", "test-mkt", PluginScope.USER);

        PluginInstaller.UninstallResult uninstall = installer.uninstall(
            "hello@test-mkt", PluginScope.USER, true);
        assertTrue(uninstall.removed());
        assertTrue(uninstall.lastScope());

        InstalledPlugins installed =
            new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json")).load();
        assertTrue(installed.plugins().isEmpty());
        JsonNode settingsJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertFalse(settingsJson.get("enabledPlugins").has("hello@test-mkt"));

        assertTrue(Files.isDirectory(result.installPath()));
    }

    @Test
    void uninstallOfUnknownPluginReturnsFalse() {
        assertFalse(installer.uninstall(
            "ghost@test-mkt", PluginScope.USER, true).removed());
    }

    @Test
    void scopedUninstallPreservesOtherScopeAndDeletesDataOnlyOnLastScope() throws Exception {
        String pluginId = "hello@test-mkt";
        installer.install("hello", "test-mkt", PluginScope.USER);
        installer.install("hello", "test-mkt", PluginScope.PROJECT);
        Path dataDir = manager.directories().pluginDataDir(pluginId);
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("state.db"), "persistent");

        PluginInstaller.UninstallResult userResult =
            installer.uninstall(pluginId, PluginScope.USER, true);

        assertTrue(userResult.removed());
        assertFalse(userResult.lastScope());
        assertEquals(1, new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json"))
            .load().installationsOf(pluginId).size());
        assertTrue(Files.exists(dataDir.resolve("state.db")));
        JsonNode userJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        JsonNode projectJson = JsonUtils.getMapper().readTree(projectSettings.toFile());
        assertFalse(userJson.get("enabledPlugins").has(pluginId));
        assertTrue(projectJson.get("enabledPlugins").has(pluginId));

        PluginInstaller.UninstallResult projectResult =
            installer.uninstall(pluginId, PluginScope.PROJECT, true);
        assertTrue(projectResult.removed());
        assertTrue(projectResult.lastScope());
        assertFalse(Files.exists(dataDir));
    }

    @Test
    void lastScopeUninstallCanPreservePersistentData() throws Exception {
        String pluginId = "hello@test-mkt";
        installer.install("hello", "test-mkt", PluginScope.USER);
        Path dataDir = manager.directories().pluginDataDir(pluginId);
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("state.db"), "persistent");

        PluginInstaller.UninstallResult result =
            installer.uninstall(pluginId, PluginScope.USER, false);

        assertTrue(result.removed());
        assertTrue(result.lastScope());
        assertTrue(Files.exists(dataDir.resolve("state.db")));
    }

    // ── listInstalled ─────────────────────────────────────────────────────────

    @Test
    void listInstalledMergesEnabledState() throws Exception {
        installer.install("hello", "test-mkt", PluginScope.USER);
        installer.install("no-manifest", "test-mkt", PluginScope.USER);
        installer.disable("no-manifest@test-mkt", PluginScope.USER);
        // A record with no settings entry at all:
        new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json")).save(
            new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json")).load()
                .withInstallation("orphan@test-mkt", new InstalledPlugins.InstallationEntry(
                    PluginScope.USER, null, "/cache/x", "1.0.0", "t", "t", null)));

        List<PluginInstaller.InstalledPluginStatus> statuses = installer.listInstalled();

        assertEquals(3, statuses.size());
        assertEquals(Boolean.TRUE, statusOf(statuses, "hello@test-mkt").enabled());
        assertEquals(Boolean.FALSE, statusOf(statuses, "no-manifest@test-mkt").enabled());
        assertNull(statusOf(statuses, "orphan@test-mkt").enabled());
    }

    private static PluginInstaller.InstalledPluginStatus statusOf(
        List<PluginInstaller.InstalledPluginStatus> statuses, String pluginId) {
        return statuses.stream()
            .filter(status -> status.pluginId().equals(pluginId))
            .findFirst()
            .orElseThrow();
    }
}

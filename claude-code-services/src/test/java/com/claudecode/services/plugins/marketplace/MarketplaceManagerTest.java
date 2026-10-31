package com.claudecode.services.plugins.marketplace;


import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.http.ServiceHttpClient;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marketplace lifecycle against a temp plugins root — add (directory/file/git),
 * list, get, update, remove, policy enforcement. No network, no real ~/.claude.
 */
class MarketplaceManagerTest {

    @TempDir
    Path tempDir;

    private Path pluginsRoot;
    private Path userSettings;
    private Path policySettings;
    private PluginSettingsStore settings;
    private FakeGitExecutor git;
    private MarketplaceManager manager;

    @BeforeEach
    void setUp() {
        pluginsRoot = tempDir.resolve("plugins-root");
        userSettings = tempDir.resolve("settings/user.json");
        policySettings = tempDir.resolve("settings/managed-settings.json");
        settings = new PluginSettingsStore(
            userSettings,
            tempDir.resolve("project/.claude/settings.json"),
            tempDir.resolve("project/.claude/settings.local.json"),
            policySettings);
        git = FakeGitExecutor.alwaysFailing();
        manager = new MarketplaceManager(pluginsRoot, git, ServiceHttpClient.noRedirects(), settings);
    }

    private Path createMarketplaceDir(String name) throws IOException {
        Path mkt = tempDir.resolve("fixtures").resolve(name);
        Files.createDirectories(mkt.resolve(".claude-plugin"));
        Files.writeString(mkt.resolve(".claude-plugin/marketplace.json"), """
            {
              "name": "%s",
              "owner": {"name": "Tester"},
              "plugins": [
                {"name": "hello", "source": "./plugins/hello", "description": "Say hello"}
              ]
            }
            """.formatted(name));
        Path plugin = mkt.resolve("plugins/hello");
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"),
            "{\"name\": \"hello\", \"version\": \"1.2.3\"}");
        Files.createDirectories(plugin.resolve("commands"));
        Files.writeString(plugin.resolve("commands/hi.md"), "# hi");
        return mkt;
    }

    // ── add ───────────────────────────────────────────────────────────────────

    @Test
    void addDirectorySourceRegistersUnderManifestName() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");

        MarketplaceManager.AddResult result =
            manager.add(new MarketplaceSource.Directory(mkt.toString()));

        assertEquals("test-mkt", result.name());
        assertFalse(result.alreadyMaterialized());

        KnownMarketplaces.Entry entry = manager.list().get("test-mkt");
        assertEquals(mkt.toString(), entry.installLocation());
        assertEquals(new MarketplaceSource.Directory(mkt.toString()), entry.source());
        assertTrue(Files.exists(pluginsRoot.resolve("known_marketplaces.json")));
    }

    @Test
    void addSameSourceTwiceIsIdempotent() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));

        MarketplaceManager.AddResult second =
            manager.add(new MarketplaceSource.Directory(mkt.toString()));

        assertTrue(second.alreadyMaterialized());
        assertEquals("test-mkt", second.name());
        assertEquals(1, manager.list().size());
    }

    @Test
    void addFileSourceRegistersManifestFile() throws Exception {
        Path mkt = createMarketplaceDir("file-mkt");
        Path manifestFile = mkt.resolve(".claude-plugin/marketplace.json");

        MarketplaceManager.AddResult result =
            manager.add(new MarketplaceSource.File(manifestFile.toString()));

        assertEquals("file-mkt", result.name());

        assertEquals(mkt.toString(), manager.list().get("file-mkt").installLocation());
    }

    @Test
    void addRejectsManifestWithInvalidName() throws Exception {
        Path mkt = tempDir.resolve("fixtures/bad name");
        Files.createDirectories(mkt.resolve(".claude-plugin"));
        Files.writeString(mkt.resolve(".claude-plugin/marketplace.json"),
            "{\"name\": \"Bad Name\", \"owner\": {\"name\": \"x\"}, \"plugins\": []}");

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Directory(mkt.toString())));
        assertTrue(Strings.CS.contains(error.getMessage(), 
            "Marketplace name cannot contain spaces. Use kebab-case (e.g., \"my-marketplace\")"),
            error.getMessage());
    }

    /**
     * The name guard in the cache-target derivation is unreachable through the add
     * path — {@code MarketplaceNames.validate} rejects separators and {@code ..}
     * before the manifest is ever cached — so it is exercised directly here. It is
     * the last line of defense for the recursive delete that follows: a name that
     * resolves outside the cache directory would let that delete reach beyond it.
     */
    @Test
    void cacheTargetRejectsANameThatEscapesTheCacheDirectory() {
        Path cacheDir = tempDir.resolve("plugins-root/marketplaces");
        MarketplaceSource source =
            new MarketplaceSource.Directory(tempDir.resolve("fixtures/whatever").toString());

        // A leading separator makes Path.resolve discard the cache directory, so
        // the result is an absolute path outside it. Name validation rejects "/",
        // which is exactly why this guard is the backstop rather than a duplicate.
        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> MarketplaceManager.resolveCacheTarget(source,
                new MarketplaceManifest("/etc/evil", null, List.of(), null, null, null), cacheDir));
        assertTrue(Strings.CS.contains(error.getMessage(), "resolves to a path outside the cache directory"),
            error.getMessage());
    }

    @Test
    void cacheTargetRejectsANameThatResolvesToTheCacheDirectoryItself() {
        Path cacheDir = tempDir.resolve("plugins-root/marketplaces");
        MarketplaceSource source =
            new MarketplaceSource.Directory(tempDir.resolve("fixtures/whatever").toString());

        // An empty name resolves to the cache directory, and the later recursive
        // delete would remove every marketplace in it rather than one entry.
        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> MarketplaceManager.resolveCacheTarget(source,
                new MarketplaceManifest("", null, List.of(), null, null, null), cacheDir));
        assertTrue(Strings.CS.contains(error.getMessage(), "resolves to a path outside the cache directory"),
            error.getMessage());
    }

    @Test
    void cacheTargetPlacesADeclaredNameInsideTheCacheDirectory() {
        Path cacheDir = tempDir.resolve("plugins-root/marketplaces");
        MarketplaceSource source =
            new MarketplaceSource.Directory(tempDir.resolve("fixtures/whatever").toString());

        assertEquals(cacheDir.resolve("my-marketplace"),
            MarketplaceManager.resolveCacheTarget(source,
                new MarketplaceManifest("my-marketplace", null, List.of(), null, null, null), cacheDir));
    }

    @Test
    void cacheTargetCachesUrlSourcesAsAFlatJsonFile() {
        Path cacheDir = tempDir.resolve("plugins-root/marketplaces");

        // URL sources replace the directory with a sibling <name>.json, which the
        // guard must still have vetted — the suffix is applied to the same name.
        assertEquals(cacheDir.resolve("my-marketplace.json"),
            MarketplaceManager.resolveCacheTarget(new MarketplaceSource.Url("https://example.test/m.json"),
                new MarketplaceManifest("my-marketplace", null, List.of(), null, null, null), cacheDir));
    }

    @Test
    void addRejectsReservedOfficialNameFromUnofficialSource() throws Exception {
        Path mkt = tempDir.resolve("fixtures/impostor");
        Files.createDirectories(mkt.resolve(".claude-plugin"));
        Files.writeString(mkt.resolve(".claude-plugin/marketplace.json"),
            "{\"name\": \"claude-code-plugins\", \"owner\": {\"name\": \"x\"}, \"plugins\": []}");

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Directory(mkt.toString())));
        assertEquals(
            "The name 'claude-code-plugins' is reserved for official Anthropic marketplaces and "
                + "can only be used with GitHub sources from the 'anthropics' organization.",
            error.getMessage());
    }

    @Test
    void addMissingDirectoryFailsWithMarketplaceFileNotFound() {
        Path mkt = tempDir.resolve("fixtures/empty-dir");
        assertThrows(PluginOperationException.class, () -> {
            Files.createDirectories(mkt);
            manager.add(new MarketplaceSource.Directory(mkt.toString()));
        });
    }

    @Test
    void addGithubSourceTriesSshThenFallsBackToHttps() throws Exception {
        FakeGitExecutor cloningGit = new FakeGitExecutor((_, args) -> {
            if (!args.contains("clone")) {
                return new GitExecutor.GitResult(128, "", "fatal: not a git repository");
            }
            String url = args.get(args.size() - 2);
            if (Strings.CS.startsWith(url, "git@")) {
                return new GitExecutor.GitResult(128, "", "Permission denied (publickey)");
            }
            // HTTPS clone "succeeds": materialize a marketplace at the target.
            Path target = Path.of(args.getLast());
            try {
                Files.createDirectories(target.resolve(".claude-plugin"));
                Files.writeString(target.resolve(".claude-plugin/marketplace.json"),
                    "{\"name\": \"gh-mkt\", \"owner\": {\"name\": \"x\"}, \"plugins\": []}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new GitExecutor.GitResult(0, "", "");
        });
        MarketplaceManager githubManager =
            new MarketplaceManager(pluginsRoot, cloningGit, ServiceHttpClient.noRedirects(), settings);

        MarketplaceManager.AddResult result =
            githubManager.add(new MarketplaceSource.Github("owner/repo"));

        assertEquals("gh-mkt", result.name());
        List<List<String>> clones = cloningGit.invocationsMatching("clone");
        assertEquals(2, clones.size(), "SSH attempt then HTTPS fallback");
        assertEquals("git@github.com:owner/repo.git", clones.getFirst().get(clones.getFirst().size() - 2));
        assertEquals("https://github.com/owner/repo.git", clones.get(1).get(clones.get(1).size() - 2));
        // Command assembly: shallow clone with no-prompt SSH config.
        assertTrue(clones.getFirst().containsAll(List.of("clone", "--depth", "1")));
        assertTrue(clones.getFirst().contains(
            "core.sshCommand=ssh -o BatchMode=yes -o StrictHostKeyChecking=yes"));
        // Cache dir renamed from temp (owner-repo) to the manifest name.
        assertTrue(Files.isDirectory(pluginsRoot.resolve("marketplaces/gh-mkt")));
        assertFalse(Files.exists(pluginsRoot.resolve("marketplaces/owner-repo")));
    }

    @Test
    void addGitSourceWithRefPassesBranchArgument() {
        FakeGitExecutor cloningGit = new FakeGitExecutor((_, args) -> {
            if (!args.contains("clone")) {
                return new GitExecutor.GitResult(128, "", "fatal: not a git repository");
            }
            Path target = Path.of(args.getLast());
            try {
                Files.createDirectories(target.resolve(".claude-plugin"));
                Files.writeString(target.resolve(".claude-plugin/marketplace.json"),
                    "{\"name\": \"ref-mkt\", \"owner\": {\"name\": \"x\"}, \"plugins\": []}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new GitExecutor.GitResult(0, "", "");
        });
        MarketplaceManager gitManager =
            new MarketplaceManager(pluginsRoot, cloningGit, ServiceHttpClient.noRedirects(), settings);

        gitManager.add(new MarketplaceSource.Git("https://example.com/mkt.git", "v2"));

        List<String> clone = cloningGit.invocationsMatching("clone").getFirst();
        int branchIndex = clone.indexOf("--branch");
        assertTrue(branchIndex > 0);
        assertEquals("v2", clone.get(branchIndex + 1));
    }

    // ── get / list ────────────────────────────────────────────────────────────

    @Test
    void getReturnsCachedManifest() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));

        MarketplaceManifest manifest = manager.get("test-mkt");

        assertEquals("test-mkt", manifest.name());
        assertEquals(1, manifest.plugins().size());
        assertEquals("hello", manifest.plugins().getFirst().name());
    }

    @Test
    void getUnknownMarketplaceListsAvailable() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));

        PluginOperationException error =
            assertThrows(PluginOperationException.class, () -> manager.get("nope"));
        assertEquals("Marketplace 'nope' not found in configuration. "
            + "Available marketplaces: test-mkt", error.getMessage());
    }

    // ── remove ────────────────────────────────────────────────────────────────

    @Test
    void removeCleansConfigSettingsAndInstalledRecords() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));

        // Seed settings + installed records referencing the marketplace.
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, """
            {
              "enabledPlugins": {"hello@test-mkt": true, "other@other-mkt": true},
              "extraKnownMarketplaces": {"test-mkt": {"source": {"source": "directory", "path": "%s"}}},
              "pluginConfigs": {
                "hello@test-mkt": {"apiKey": "secret", "mcpServers": {"x": {}}},
                "other@other-mkt": {"apiKey": "keep-me"}
              }
            }
            """.formatted(mkt.toString().replace("\\", "\\\\")));
        new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json"))
            .save(InstalledPlugins.empty().withInstallation("hello@test-mkt",
                new InstalledPlugins.InstallationEntry(PluginScope.USER, null,
                    "/cache/x", "1.0.0", "t", "t", null)));

        manager.remove("test-mkt");

        assertFalse(manager.list().containsKey("test-mkt"));
        JsonNode settingsJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertFalse(settingsJson.get("enabledPlugins").has("hello@test-mkt"));
        assertTrue(settingsJson.get("enabledPlugins").has("other@other-mkt"));
        assertFalse(settingsJson.get("extraKnownMarketplaces").has("test-mkt"));
        // No installation of hello@test-mkt survives, so its stored options go
        // too — the same "last scope gone" rule uninstall applies. A surviving
        // marketplace's config must not be collateral damage.
        assertFalse(settingsJson.get("pluginConfigs").has("hello@test-mkt"));
        assertTrue(settingsJson.get("pluginConfigs").has("other@other-mkt"));
        assertTrue(new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json"))
            .load().plugins().isEmpty());
        // User-owned directory itself is untouched.
        assertTrue(Files.exists(mkt.resolve(".claude-plugin/marketplace.json")));
    }

    @Test
    void removingMarketplaceWithoutStoredOptionsDoesNotCreatePluginConfigs() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));
        Files.createDirectories(userSettings.getParent());
        Files.writeString(userSettings, """
            {
              "enabledPlugins": {"hello@test-mkt": true},
              "extraKnownMarketplaces": {"test-mkt": {"source": {"source": "directory", "path": "%s"}}}
            }
            """.formatted(mkt.toString().replace("\\", "\\\\")));
        new InstalledPluginsStore(pluginsRoot.resolve("installed_plugins.json"))
            .save(InstalledPlugins.empty().withInstallation("hello@test-mkt",
                new InstalledPlugins.InstallationEntry(PluginScope.USER, null,
                    "/cache/x", "1.0.0", "t", "t", null)));

        manager.remove("test-mkt");

        JsonNode settingsJson = JsonUtils.getMapper().readTree(userSettings.toFile());
        assertFalse(settingsJson.get("enabledPlugins").has("hello@test-mkt"));
        assertFalse(settingsJson.get("extraKnownMarketplaces").has("test-mkt"));
        assertFalse(settingsJson.has("pluginConfigs"));
    }

    @Test
    void removeUnknownMarketplaceThrows() {
        PluginOperationException error =
            assertThrows(PluginOperationException.class, () -> manager.remove("ghost"));
        assertEquals("Marketplace 'ghost' not found", error.getMessage());
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    void updateLocalDirectoryRevalidatesAndBumpsTimestamp() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));
        String before = manager.list().get("test-mkt").lastUpdated();

        manager.update("test-mkt");

        assertNotEquals(before, manager.list().get("test-mkt").lastUpdated());
    }

    @Test
    void updateFailsWhenLocalManifestDisappeared() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));
        Files.delete(mkt.resolve(".claude-plugin/marketplace.json"));

        PluginOperationException error =
            assertThrows(PluginOperationException.class, () -> manager.update("test-mkt"));
        assertTrue(Strings.CS.startsWith(error.getMessage(), "Failed to refresh marketplace 'test-mkt':"),
            error.getMessage());
    }

    @Test
    void updateRejectsCorruptedInstallLocationOutsideCacheDir() throws Exception {

        // whose installLocation points at a user project (gh-32793, gh-32661).
        Path evil = Files.createDirectories(tempDir.resolve("user-project"));
        KnownMarketplacesStore store =
            new KnownMarketplacesStore(pluginsRoot.resolve("known_marketplaces.json"));
        store.save(KnownMarketplaces.empty().with("corrupt-mkt", new KnownMarketplaces.Entry(
            new MarketplaceSource.Github("owner/repo"), evil.toString(), "t", null)));

        PluginOperationException error =
            assertThrows(PluginOperationException.class, () -> manager.update("corrupt-mkt"));
        assertTrue(Strings.CS.contains(error.getMessage(), "has a corrupted installLocation"), error.getMessage());
        assertTrue(Strings.CS.contains(error.getMessage(), "claude plugin marketplace remove \"corrupt-mkt\""),
            error.getMessage());
    }

    @Test
    void updateGitSourcePullsInPlaceWhenRepoExists() throws Exception {
        // Materialize a fake git clone in the cache dir.
        Path installLocation = pluginsRoot.resolve("marketplaces/pull-mkt");
        Files.createDirectories(installLocation.resolve(".git"));
        Files.createDirectories(installLocation.resolve(".claude-plugin"));
        Files.writeString(installLocation.resolve(".claude-plugin/marketplace.json"),
            "{\"name\": \"pull-mkt\", \"owner\": {\"name\": \"x\"}, \"plugins\": []}");
        new KnownMarketplacesStore(pluginsRoot.resolve("known_marketplaces.json"))
            .save(KnownMarketplaces.empty().with("pull-mkt", new KnownMarketplaces.Entry(
                new MarketplaceSource.Git("https://example.com/mkt.git"),
                installLocation.toString(), "t", null)));
        FakeGitExecutor pullingGit = new FakeGitExecutor(
            (_, _) -> new GitExecutor.GitResult(0, "", ""));
        MarketplaceManager pullManager =
            new MarketplaceManager(pluginsRoot, pullingGit, ServiceHttpClient.noRedirects(), settings);

        pullManager.update("pull-mkt");

        assertEquals(List.of(List.of("pull", "origin", "HEAD")),
            pullingGit.invocationsMatching("pull"));
        assertTrue(pullingGit.invocationsMatching("clone").isEmpty());
    }

    // ── policy ────────────────────────────────────────────────────────────────

    @Test
    void blockedMarketplaceSourceIsRejectedBeforeAnyFetch() throws Exception {
        Files.createDirectories(policySettings.getParent());
        Files.writeString(policySettings, """
            {"blockedMarketplaces": [{"source": "github", "repo": "evil/repo"}]}
            """);

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Github("evil/repo")));
        assertEquals("Marketplace source 'github:evil/repo' is blocked by enterprise policy.",
            error.getMessage());
        assertTrue(git.invocations.isEmpty(), "policy must block before any git operation");
    }

    @Test
    void blockedGithubRepoCannotBeBypassedViaGitUrl() throws Exception {
        Files.createDirectories(policySettings.getParent());
        Files.writeString(policySettings, """
            {"blockedMarketplaces": [{"source": "github", "repo": "evil/repo"}]}
            """);

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Git("git@github.com:evil/repo.git")));
        assertEquals(
            "Marketplace source 'git:git@github.com:evil/repo.git' is blocked by enterprise policy.",
            error.getMessage());
    }

    @Test
    void emptyAllowlistBlocksAllExternalSources() throws Exception {
        Files.createDirectories(policySettings.getParent());
        Files.writeString(policySettings, "{\"strictKnownMarketplaces\": []}");
        Path mkt = createMarketplaceDir("test-mkt");

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Directory(mkt.toString())));
        assertEquals("Marketplace source 'dir:" + mkt
            + "' is blocked by enterprise policy. No external marketplaces are allowed.",
            error.getMessage());
    }

    @Test
    void allowlistMissListsAllowedSourcesAndGithubTip() throws Exception {
        Files.createDirectories(policySettings.getParent());
        Files.writeString(policySettings, """
            {"strictKnownMarketplaces": [
              {"source": "hostPattern", "hostPattern": "^github\\\\.corp\\\\.com$"}
            ]}
            """);

        PluginOperationException error = assertThrows(PluginOperationException.class,
            () -> manager.add(new MarketplaceSource.Github("owner/repo")));
        assertTrue(Strings.CS.contains(error.getMessage(), 
            "Marketplace source 'github:owner/repo' (github.com) is blocked by enterprise policy."),
            error.getMessage());
        assertTrue(Strings.CS.contains(error.getMessage(), 
            "Allowed sources: hostPattern:^github\\.corp\\.com$"), error.getMessage());
        assertTrue(Strings.CS.contains(error.getMessage(), 
            "Tip: The shorthand \"owner/repo\" assumes github.com."), error.getMessage());
    }

    @Test
    void allowlistedSourcePassesPolicy() throws Exception {
        Path mkt = createMarketplaceDir("allowed-mkt");
        Files.createDirectories(policySettings.getParent());
        Files.writeString(policySettings, """
            {"strictKnownMarketplaces": [{"source": "pathPattern", "pathPattern": ".*"}]}
            """);

        MarketplaceManager.AddResult result =
            manager.add(new MarketplaceSource.Directory(mkt.toString()));
        assertEquals("allowed-mkt", result.name());
    }

    // ── credential redaction ──────────────────────────────────────────────────

    @Test
    void redactUrlCredentialsScrubsUserinfoFromHttpUrls() {
        assertEquals("https://***:***@github.com/org/repo",
            MarketplaceManager.redactUrlCredentials("https://user:token@github.com/org/repo"));
        assertEquals("https://:***@github.com/org/repo",
            MarketplaceManager.redactUrlCredentials("https://:token@github.com/org/repo"));
        assertEquals("https://***@github.com/org/repo",
            MarketplaceManager.redactUrlCredentials("https://token@github.com/org/repo"));
        // Non-http(s) schemes and shorthand pass through unchanged.
        assertEquals("git@github.com:owner/repo.git",
            MarketplaceManager.redactUrlCredentials("git@github.com:owner/repo.git"));
        assertEquals("owner/repo", MarketplaceManager.redactUrlCredentials("owner/repo"));
    }

    // ── autoUpdate ────────────────────────────────────────────────────────────

    @Test
    void setAutoUpdatePersistsFlag() throws Exception {
        Path mkt = createMarketplaceDir("test-mkt");
        manager.add(new MarketplaceSource.Directory(mkt.toString()));

        manager.setAutoUpdate("test-mkt", true);

        assertTrue(manager.list().get("test-mkt").autoUpdate());
    }
}

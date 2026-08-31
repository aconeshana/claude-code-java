package com.claudecode.services.plugins.marketplace;

import org.apache.commons.lang3.Strings;

import com.claudecode.services.http.ServiceHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDelistingServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void forceRemoveDeletedPluginsUninstallsEditableScopesAndFlagsPlugin() throws Exception {
        Path marketplaceDir = tempDir.resolve("marketplace");
        Path metadata = marketplaceDir.resolve(".claude-plugin");
        Files.createDirectories(metadata);
        Files.createDirectories(marketplaceDir.resolve("plugins/demo/.claude-plugin"));
        Files.writeString(marketplaceDir.resolve("plugins/demo/.claude-plugin/plugin.json"),
            "{\"name\":\"demo\",\"version\":\"1.0.0\"}");
        Files.writeString(metadata.resolve("marketplace.json"), """
            {"name":"test-market","owner":{"name":"Tester"},
             "forceRemoveDeletedPlugins":true,
             "plugins":[{"name":"demo","source":"./plugins/demo"}]}
            """);
        PluginSettingsStore settings = new PluginSettingsStore(
            tempDir.resolve("user.json"), tempDir.resolve("project.json"),
            tempDir.resolve("local.json"), tempDir.resolve("policy.json"));
        GitExecutor git = (_, _) -> new GitExecutor.GitResult(1, "", "disabled");
        MarketplaceManager manager = new MarketplaceManager(
            tempDir.resolve("plugins-root"), git, ServiceHttpClient.noRedirects(), settings);
        manager.add(new MarketplaceSource.Directory(marketplaceDir.toString()));
        PluginInstaller installer = new PluginInstaller(manager, git, tempDir.toString());
        installer.install("demo", "test-market", PluginScope.USER);
        Files.createDirectories(manager.directories().pluginDataDir("demo@test-market"));
        Files.writeString(manager.directories().pluginDataDir("demo@test-market").resolve("state"),
            "persistent");

        Files.writeString(metadata.resolve("marketplace.json"), """
            {"name":"test-market","owner":{"name":"Tester"},
             "forceRemoveDeletedPlugins":true,"plugins":[]}
            """);
        FlaggedPluginStore flagged = new FlaggedPluginStore(
            manager.directories().flaggedPluginsFile());
        PluginDelistingService service = new PluginDelistingService(manager, installer, flagged);

        assertEquals(Boolean.TRUE, manager.get("test-market").forceRemoveDeletedPlugins());
        assertTrue(installer.listInstalled().stream()
            .anyMatch(status -> Strings.CS.equals(status.pluginId(), "demo@test-market")));
        assertEquals(List.of("demo@test-market"), service.reconcile());
        assertTrue(installer.listInstalled().isEmpty());
        assertTrue(flagged.load().containsKey("demo@test-market"));
        assertFalse(Files.exists(manager.directories().pluginDataDir("demo@test-market")));
        assertTrue(service.reconcile().isEmpty(), "already flagged entries are idempotent");
    }
}

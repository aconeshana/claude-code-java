package com.claudecode.cli;


import org.apache.commons.lang3.Strings;

import com.claudecode.commands.CommandContext;
import com.claudecode.commands.CommandRegistry;
import com.claudecode.commands.CommandResult;
import com.claudecode.commands.impl.integration.ReloadPluginsCommand;
import com.claudecode.core.message.Usage;
import com.claudecode.mcp.McpClientManager;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.mcp.McpToolInfo;
import com.claudecode.services.plugins.marketplace.InstalledPlugins;
import com.claudecode.services.plugins.marketplace.InstalledPluginsStore;
import com.claudecode.services.plugins.marketplace.PluginDirectories;
import com.claudecode.services.plugins.marketplace.PluginScope;
import com.claudecode.services.plugins.marketplace.PluginSettingsStore;
import com.claudecode.services.plugins.runtime.PluginRuntimeLoader;
import com.claudecode.tools.agent.AgentDefinitionLoader;
import com.claudecode.tools.skills.SkillLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliPluginRuntimeTest {

    @TempDir
    Path tmp;

    private SkillLoader pluginSkillLoader;
    private Path pluginRoot;

    @AfterEach
    void tearDown() {
        // Both are process-global channels — reset so other tests in this JVM
        // fork never see this test's plugin state.
        AgentDefinitionLoader.setPluginAgentsProvider(null);
    }

    private CliPluginRuntime newRuntime() {
        return newRuntime(null);
    }

    private CliPluginRuntime newRuntime(McpClientManager mcpClientManager) {
        PluginDirectories dirs = new PluginDirectories(tmp.resolve("plugins"));
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("settings/user.json"),
            tmp.resolve("settings/project.json"),
            tmp.resolve("settings/local.json"),
            tmp.resolve("settings/policy.json"));
        InstalledPluginsStore store = new InstalledPluginsStore(dirs.installedPluginsFile());
        PluginRuntimeLoader loader =
            new PluginRuntimeLoader(dirs, settings, store, () -> "s-1");

        Path root = tmp.resolve("cache/demo");
        pluginRoot = root;
        try {
            Files.createDirectories(root.resolve("commands"));
            Files.writeString(root.resolve("commands/greet.md"), """
                ---
                description: Greet a person
                arguments: who
                ---
                Say hello $who""");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        String now = Instant.now().toString();
        store.save(InstalledPlugins.empty().withInstallation("demo@mkt",
            new InstalledPlugins.InstallationEntry(
                PluginScope.USER, null, root.toString(), "1.0.0", now, now, null)));
        settings.setEnabledPlugin("demo@mkt", true, PluginScope.USER);

        pluginSkillLoader = new SkillLoader();
        return new CliPluginRuntime(loader, tmp.toString(), pluginSkillLoader, null,
            mcpClientManager, null);
    }

    @Test
    void reportsUninitializedRuntimeGracefully() {
        CommandResult r = new ReloadPluginsCommand().execute(CommandContext.minimal(), "");
        assertFalse(r.shouldQuery());
        assertTrue(Strings.CS.contains(r.output(), "not initialized"), r.output());
    }

    @Test
    void reloadReportsTsStyleStatistics() {
        CliPluginRuntime runtime = newRuntime();
        CommandResult r = new ReloadPluginsCommand().execute(context(runtime), "");

        String out = r.output();
        assertTrue(Strings.CS.startsWith(out, "Reloaded: "), out);
        assertTrue(Strings.CS.contains(out, "1 plugin ·"), out);

        assertTrue(Strings.CS.contains(out, "1 skill ·"), out);
        assertTrue(out.matches("(?s).*\\d+ agents?.*"), out);
        assertTrue(Strings.CS.contains(out, "0 hooks"), out);
        assertTrue(Strings.CS.contains(out, "0 plugin MCP servers"), out);
        assertTrue(Strings.CS.contains(out, "0 plugin LSP servers"), out);
        assertFalse(Strings.CS.contains(out, "error"), "no error line when error_count == 0: " + out);
    }

    @Test
    void reloadSyncsCommandsIntoAttachedRegistry() {
        CliPluginRuntime runtime = newRuntime();
        CommandRegistry registry = new CommandRegistry();
        runtime.attachCommandRegistry(registry);

        new ReloadPluginsCommand().execute(context(runtime), "");
        assertTrue(registry.find("demo:greet").isPresent(),
            "reload must re-register the plugin command generation");
    }

    @Test
    void reloadInjectsPluginCommandsIntoModelSkillInventory() {
        CliPluginRuntime runtime = newRuntime();

        runtime.loadAndInject();

        var skill = pluginSkillLoader.loadAll().stream()
            .filter(candidate -> Strings.CS.equals(candidate.name(), "demo:greet"))
            .findFirst().orElseThrow();
        assertTrue(skill.isPluginCommand());
        assertTrue(skill.argumentNames().contains("who"));
        assertFalse(skill.disableModelInvocation());
    }

    @Test
    void reloadReconnectsSameNamedPluginMcpServerWhenConfigurationChanges() throws Exception {
        RecordingMcpClientManager mcp = new RecordingMcpClientManager();
        CliPluginRuntime runtime = newRuntime(mcp);
        Files.writeString(pluginRoot.resolve(".mcp.json"), """
            {"mcpServers":{"helper":{"command":"first","args":["--one"]}}}
            """);

        runtime.loadAndInject();
        assertTrue(mcp.awaitConnectCount(1), "initial plugin MCP connection");

        Files.writeString(pluginRoot.resolve(".mcp.json"), """
            {"mcpServers":{"helper":{"command":"second","args":["--two"]}}}
            """);
        runtime.refresh();

        assertTrue(mcp.awaitConnectCount(2), "changed config must create a new connection");
        assertTrue(mcp.disconnected.contains("plugin:demo:helper"),
            "changed config must evict the old connection and its tools");
        assertEquals("second", mcp.connected.getLast().command());
        assertEquals(List.of("--two"), mcp.connected.getLast().args());
    }

    private static final class RecordingMcpClientManager extends McpClientManager {
        private final List<McpServerConfig> connected = new CopyOnWriteArrayList<>();
        private final List<String> disconnected = new CopyOnWriteArrayList<>();

        @Override
        public void connect(McpServerConfig config) {
            connected.add(config);
        }

        @Override
        public void disconnect(String serverId) {
            disconnected.add(serverId);
        }

        @Override
        public List<McpToolInfo> listToolsForServer(String serverId) {
            return List.of();
        }

        boolean awaitConnectCount(int expected) throws InterruptedException {
            for (int attempt = 0; attempt < 100; attempt++) {
                if (connected.size() >= expected) return true;
                Thread.sleep(10);
            }
            return false;
        }
    }

    @Test
    void errorLineAppearsWhenLoadHasErrors() throws IOException {
        CliPluginRuntime runtime = newRuntime();
        // Sabotage the plugin manifest to force one load error.
        Files.createDirectories(tmp.resolve("cache/demo/.claude-plugin"));
        Files.writeString(tmp.resolve("cache/demo/.claude-plugin/plugin.json"), "{broken");
        String out = new ReloadPluginsCommand()
            .execute(context(runtime), "").output();
        assertTrue(Strings.CS.contains(out, "1 error during load. Run /doctor for details."), out);
    }

    @Test
    void pluralizationMatchesTsPluralHelper() {
        CliPluginRuntime runtime = newRuntime();
        String out = new ReloadPluginsCommand()
            .execute(context(runtime), "").output();
        // count==1 → singular noun, count==0 → plural noun
        assertTrue(Strings.CS.contains(out, "1 plugin ·"), out);
        assertTrue(Strings.CS.contains(out, "0 hooks"), out);
    }

    private static CommandContext context(CliPluginRuntime runtime) {
        return CommandContext.builder(
            "m", List::of, () -> { }, _ -> { }, () -> Usage.EMPTY,
            _ -> 0.0, "/tmp", false)
            .pluginRuntime(runtime)
            .build();
    }
}

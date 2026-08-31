package com.claudecode.services.plugins.marketplace;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.mcp.oauth.SecureStorage;
import com.claudecode.mcp.oauth.SecureStorageData;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigOption;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ConfigurationStep;
import com.claudecode.runtime.plugins.PluginMarketplacePort.McpbConfiguration;
import com.claudecode.services.http.ServiceHttpClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginMarketplaceAdapterOptionsTest {

    @TempDir Path tmp;

    @Test
    void sensitiveOptionsUseSecureStorageAndOverrideLegacySettings() {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("user.json"), tmp.resolve("project.json"),
            tmp.resolve("local.json"), tmp.resolve("policy.json"));
        MemoryStorage secure = new MemoryStorage(new SecureStorageData(null, null,
            Map.of("demo@market", Map.of("token", "secure-old")), null));
        PluginMarketplaceAdapter adapter = adapter(settings, secure);
        LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
        schema.put("endpoint", option(false));
        schema.put("token", option(true));

        adapter.saveOptions("demo@market", Map.of(
            "endpoint", "https://example.test", "token", "secure-new"), schema);

        assertEquals("secure-new", secure.data.pluginSecrets().get("demo@market").get("token"));
        var onDisk = settings.pluginConfig("demo@market").get("options");
        assertEquals("https://example.test", onDisk.get("endpoint").asText());
        assertNull(onDisk.get("token"), "sensitive value must never remain in settings.json");
        assertEquals(Map.of("endpoint", "https://example.test", "token", "secure-new"),
            adapter.loadOptions("demo@market"));
    }

    @Test
    void loadOptionsReturnsSecretWhenNoPlaintextOptionsExist() {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("user.json"), tmp.resolve("project.json"),
            tmp.resolve("local.json"), tmp.resolve("policy.json"));
        PluginMarketplaceAdapter adapter = adapter(settings, new MemoryStorage(
            new SecureStorageData(null, null,
                Map.of("demo@market", Map.of("token", "secure-only")), null)));

        assertEquals(Map.of("token", "secure-only"), adapter.loadOptions("demo@market"));
    }

    @Test
    void secureStorageFailureLeavesLegacyPlaintextUntouched() throws Exception {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("user.json"), tmp.resolve("project.json"),
            tmp.resolve("local.json"), tmp.resolve("policy.json"));
        settings.setPluginConfig("demo@market", JsonUtils.getMapper()
            .readTree("{\"options\":{\"token\":\"legacy\"}}"));
        PluginMarketplaceAdapter adapter = adapter(settings, new FailingStorage());

        try {
            adapter.saveOptions("demo@market", Map.of("token", "new-secret"),
                new LinkedHashMap<>(Map.of("token", option(true))));
        } catch (RuntimeException _) {
            assertEquals("legacy", settings.pluginConfig("demo@market")
                .get("options").get("token").asText());
            return;
        }
        throw new AssertionError("secure-storage failure must propagate before settings are touched");
    }

    @Test
    void mcpbConfigurationSplitsSensitiveServerValuesAndReloadsThem() throws Exception {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("user.json"), tmp.resolve("project.json"),
            tmp.resolve("local.json"), tmp.resolve("policy.json"));
        MemoryStorage secure = new MemoryStorage(SecureStorageData.empty());
        PluginMarketplaceAdapter adapter = adapter(settings, secure);
        Path plugin = Files.createDirectories(tmp.resolve("plugin"));
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"),
            "{\"name\":\"demo\",\"mcpServers\":\"server.mcpb\"}");
        Files.write(plugin.resolve("server.mcpb"), bundle(Map.of(
            "manifest.json", """
                {"manifest_version":"0.4","name":"server","version":"1",
                 "description":"server","author":{"name":"Tester"},
                 "server":{"type":"binary","entry_point":"bin/server",
                   "mcp_config":{"command":"${__dirname}/bin/server",
                     "env":{"ENDPOINT":"${user_config.endpoint}","TOKEN":"${user_config.token}"}}},
                 "user_config":{
                   "endpoint":{"type":"string","title":"Endpoint","description":"URL","required":true},
                   "token":{"type":"string","title":"Token","description":"Secret","required":true,"sensitive":true}}}
                """,
            "bin/server", "binary")));

        assertTrue(adapter.hasMcpb("demo@market", plugin));
        McpbConfiguration initial = adapter.loadMcpbConfiguration("demo@market", plugin)
            .orElseThrow();
        assertEquals(2, initial.validationErrors().size());

        adapter.saveMcpbConfiguration("demo@market", plugin, initial,
            Map.of("endpoint", "https://example.test", "token", "secret"));

        JsonNode stored = settings.pluginConfig("demo@market")
            .path("mcpServers").path("server");
        assertEquals("https://example.test", stored.path("endpoint").asText());
        assertNull(stored.get("token"));
        assertEquals("secret", secure.data.pluginSecrets()
            .get("demo@market/server").get("token"));
        McpbConfiguration reloaded = adapter.loadMcpbConfiguration("demo@market", plugin)
            .orElseThrow();
        assertTrue(reloaded.validationErrors().isEmpty());
        assertEquals("secret", reloaded.existingValues().get("token"));
    }

    @Test
    void pluginManifestDoesNotInheritMarketplaceMcpbDeclaration() throws Exception {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("ignore-user.json"), tmp.resolve("ignore-project.json"),
            tmp.resolve("ignore-local.json"), tmp.resolve("ignore-policy.json"));
        Path marketplace = tmp.resolve("marketplace");
        Files.createDirectories(marketplace.resolve(".claude-plugin"));
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), """
            {"name":"market","owner":{"name":"Tester"},"plugins":[
              {"name":"demo","source":"./demo","mcpServers":"marketplace.mcpb"}
            ]}
            """);
        Path plugin = Files.createDirectories(tmp.resolve("plugin-with-manifest"));
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"),
            "{\"name\":\"demo\"}");

        GitExecutor git = (_, _) -> new GitExecutor.GitResult(1, "", "disabled");
        MarketplaceManager marketplaces = new MarketplaceManager(
            tmp.resolve("ignore-plugins"), git, ServiceHttpClient.noRedirects(), settings);
        marketplaces.add(new MarketplaceSource.Directory(marketplace.toString()));
        PluginMarketplaceAdapter adapter = new PluginMarketplaceAdapter(marketplaces,
            new PluginInstaller(marketplaces, git, tmp.toString()), settings, () -> null,
            List::of, new MemoryStorage(SecureStorageData.empty()));

        assertFalse(adapter.hasMcpb("demo@market", plugin),
            "TS treats plugin.json as authoritative for mcpServers");
    }

    @Test
    void unconfiguredStepsWalkTopLevelThenAssistantModeChannel() throws Exception {
        PluginSettingsStore settings = new PluginSettingsStore(
            tmp.resolve("channel-user.json"), tmp.resolve("channel-project.json"),
            tmp.resolve("channel-local.json"), tmp.resolve("channel-policy.json"));
        MemoryStorage secure = new MemoryStorage(SecureStorageData.empty());
        PluginMarketplaceAdapter adapter = adapter(settings, secure);
        Path plugin = Files.createDirectories(tmp.resolve("channel-plugin"));
        Files.createDirectories(plugin.resolve(".claude-plugin"));
        Files.writeString(plugin.resolve(".claude-plugin/plugin.json"), """
            {"name":"demo",
             "userConfig":{"endpoint":{"type":"string","title":"Endpoint",
               "description":"API URL","required":true}},
             "channels":[{"server":"telegram","displayName":"Telegram",
               "userConfig":{"token":{"type":"string","title":"Token",
                 "description":"Bot token","required":true,"sensitive":true}}}],
             "mcpServers":{"telegram":{"command":"bot","env":{
               "ENDPOINT":"${user_config.endpoint}","TOKEN":"${user_config.token}"}}}}
            """);

        List<ConfigurationStep> initial = adapter.unconfiguredSteps("demo@market", plugin);
        assertEquals(List.of("top-level", "channel:telegram"),
            initial.stream().map(ConfigurationStep::key).toList());
        assertEquals("Configure Telegram", initial.get(1).title());

        adapter.saveConfigurationStep("demo@market", initial.getFirst(),
            Map.of("endpoint", "https://example.test"));
        adapter.saveConfigurationStep("demo@market", initial.get(1), Map.of("token", "secret"));

        assertTrue(adapter.unconfiguredSteps("demo@market", plugin).isEmpty());
        assertEquals("https://example.test", settings.pluginConfig("demo@market")
            .path("options").path("endpoint").asText());
        assertEquals("secret", secure.data.pluginSecrets()
            .get("demo@market/telegram").get("token"));
    }

    private PluginMarketplaceAdapter adapter(PluginSettingsStore settings, SecureStorage secure) {
        GitExecutor git = (_, _) -> new GitExecutor.GitResult(1, "", "disabled");
        MarketplaceManager marketplaces = new MarketplaceManager(
            tmp.resolve("plugins"), git, ServiceHttpClient.noRedirects(), settings);
        return new PluginMarketplaceAdapter(marketplaces,
            new PluginInstaller(marketplaces, git, tmp.toString()), settings, () -> null,
            List::of, secure);
    }

    private static ConfigOption option(boolean sensitive) {
        return new ConfigOption("string", "Option", null, null, null, null,
            sensitive, null, null);
    }

    private static byte[] bundle(Map<String, String> files) throws Exception {
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

    private static final class MemoryStorage implements SecureStorage {
        private SecureStorageData data;
        MemoryStorage(SecureStorageData data) { this.data = data; }
        @Override public String name() { return "memory"; }
        @Override public Optional<SecureStorageData> read() { return Optional.ofNullable(data); }
        @Override public Optional<String> update(SecureStorageData data) { this.data = data; return Optional.empty(); }
        @Override public boolean delete() { data = null; return true; }
    }

    private static final class FailingStorage implements SecureStorage {
        @Override public String name() { return "failing"; }
        @Override public Optional<SecureStorageData> read() { return Optional.empty(); }
        @Override public Optional<String> update(SecureStorageData data) { throw new RuntimeException("locked"); }
        @Override public boolean delete() { return false; }
    }
}

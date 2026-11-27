package com.claudecode.gateway;

import static org.junit.jupiter.api.Assertions.*;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.sessionhost.SessionHostInfo;
import com.claudecode.runtime.sessionhost.SessionHostRegistry;
import com.claudecode.runtime.sessionhost.SessionHostSession;
import com.claudecode.runtime.sessionhost.SessionOpenRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class GatewayPluginsHandlerTest {

    private static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Test
    void endpointsServeSnapshotAddInstallToggleOptionsUninstallAndReload() throws Exception {
        AtomicBoolean installed = new AtomicBoolean(false);
        AtomicBoolean enabled = new AtomicBoolean(false);
        AtomicInteger refreshCalls = new AtomicInteger(0);
        Map<String, PluginMarketplacePort.Marketplace> marketplaces = new LinkedHashMap<>();
        Map<String, Object> savedOpts = new LinkedHashMap<>();

        PluginMarketplacePort fakeMarketplace = new PluginMarketplacePort() {
            @Override
            public Map<String, Marketplace> marketplaces() {
                return Map.copyOf(marketplaces);
            }

            @Override
            public MarketplaceManifest marketplace(String name) {
                return new MarketplaceManifest(name, List.of(
                    new PluginEntry("opencli-browser", "CDP Browser Use", "0.1.0",
                        new Author("aconeshana"), "browser-automation",
                        List.of("browser", "cdp"), "https://github.com/aconeshana/poco-plugins",
                        "https://github.com/aconeshana/poco-plugins")));
            }

            @Override
            public ParsedMarketplaceInput parseMarketplaceInput(String input) {
                return Strings.CS.contains(input, "/")
                    ? new ParsedMarketplaceInput.Parsed(input)
                    : new ParsedMarketplaceInput.Unrecognized();
            }

            @Override
            public AddResult addMarketplace(ParsedMarketplaceInput.Parsed input, Consumer<String> progress) {
                marketplaces.put("poco-plugins", new Marketplace(
                    "poco-plugins", input.input(), "/tmp/poco-plugins", "2026-11-26T00:00:00Z", false));
                return new AddResult("poco-plugins", false);
            }

            @Override
            public void removeMarketplace(String name) {
                marketplaces.remove(name);
            }

            @Override
            public void updateMarketplace(String name, Consumer<String> progress) {}

            @Override
            public void setMarketplaceAutoUpdate(String name, boolean auto) {
                Marketplace old = marketplaces.get(name);
                if (old != null) {
                    marketplaces.put(name, new Marketplace(
                        old.name(), old.source(), old.installLocation(), old.lastUpdated(), auto));
                }
            }

            @Override
            public List<InstalledPlugin> installedPlugins() {
                if (!installed.get()) return List.of();
                LinkedHashMap<String, ConfigOption> schema = new LinkedHashMap<>();
                schema.put("default_session", new ConfigOption(
                    "string", "Default Session", "Session name", false, "poco", false, false, null, null));
                return List.of(new InstalledPlugin(
                    "opencli-browser@poco-plugins",
                    List.of(new Installation(Scope.USER, null, Path.of("/tmp/cache"), "0.1.0")),
                    enabled.get(),
                    "CDP Browser Use",
                    "0.1.0",
                    "aconeshana",
                    "",
                    "",
                    schema));
            }

            @Override
            public InstallResult install(String pluginName, String marketplaceName, Scope scope) {
                installed.set(true);
                enabled.set(true);
                return new InstallResult(pluginName + "@" + marketplaceName, "0.1.0", Path.of("/tmp/cache"));
            }

            @Override
            public PluginUninstallResult uninstall(String pluginId, Scope scope, boolean deleteDataDirectory) {
                boolean was = installed.getAndSet(false);
                enabled.set(false);
                return new PluginUninstallResult(was, true);
            }

            @Override public Optional<PluginDataDirectory> pluginDataDirectory(String pluginId) { return Optional.empty(); }
            @Override public boolean isEnabledAtProjectScope(String pluginId) { return false; }
            @Override public void disableLocally(String pluginId) {}
            @Override public String updateAvailabilityError(String pluginId) { return null; }
            @Override public DiscoveryEnvironment discoveryEnvironment() { return new DiscoveryEnvironment(true, false, false); }
            @Override public List<FlaggedPlugin> flaggedPlugins() { return List.of(); }
            @Override public void dismissFlaggedPlugin(String pluginId) {}
            @Override public List<FailedPlugin> failedPlugins() { return List.of(); }
            @Override public List<PluginMcpServer> pluginMcpServers() { return List.of(); }
            @Override public PluginUpdateResult updatePlugin(String pluginId, Scope scope) { return new PluginUpdateResult(false, "0.1.0"); }
            @Override public void enable(String pluginId, Scope scope) { enabled.set(true); }
            @Override public void disable(String pluginId, Scope scope) { enabled.set(false); }
            @Override public Map<String, Long> installCounts() { return Map.of(); }
            @Override public List<ErrorView> errors() { return List.of(); }
            @Override public void removeExtraMarketplace(String marketplaceName) {}
            @Override public boolean openExternalUrl(String url) { return false; }
            @Override public LinkedHashMap<String, ConfigOption> userConfig(Path installPath) { return new LinkedHashMap<>(); }
            @Override public Map<String, Object> loadOptions(String pluginId) { return Map.copyOf(savedOpts); }
            @Override public void saveOptions(String pluginId, Map<String, Object> values, LinkedHashMap<String, ConfigOption> schema) {
                savedOpts.clear();
                savedOpts.putAll(values);
            }
            @Override public ValidationResult validate(Path path) {
                return new ValidationResult(true, List.of(), List.of(), path, "plugin");
            }
        };

        GatewayPluginsPort port = new GatewayPluginsPort() {
            @Override public PluginMarketplacePort marketplace() { return fakeMarketplace; }
            @Override public RuntimeSummary summary() {
                return new RuntimeSummary(enabled.get() ? 3 : 0, enabled.get() ? 1 : 0, enabled.get() ? 1 : 0, 0, 0);
            }
            @Override public RuntimeSummary refreshRuntime() {
                refreshCalls.incrementAndGet();
                return summary();
            }
        };

        SessionHostRegistry registry = new SessionHostRegistry(new SessionHostRegistry.Activator() {
            @Override public CompletableFuture<SessionHostSession> activate(SessionOpenRequest req) {
                return CompletableFuture.failedFuture(new UnsupportedOperationException());
            }
            @Override public List<SessionHostInfo> list() { return List.of(); }
        });

        try (GatewayServer server = GatewayServer.builder(
                new GatewayServer.Config("127.0.0.1", 0), TOKEN, registry)
                .plugins(port)
                .build()) {
            server.start();
            String base = "http://127.0.0.1:" + server.port();
            HttpClient http = HttpClient.newHttpClient();

            // 1. Unauthenticated rejected
            HttpResponse<String> unauth = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(401, unauth.statusCode());

            // 2. Add marketplace
            HttpResponse<String> addMkt = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins/marketplaces/add"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"source\":\"aconeshana/poco-plugins\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, addMkt.statusCode());
            JsonNode addJson = JsonUtils.getMapper().readTree(addMkt.body());
            assertEquals(1, addJson.path("marketplaces").size());
            assertEquals("opencli-browser", addJson.path("marketplaces").get(0).path("plugins").get(0).path("name").asText());

            // 3. Install plugin
            HttpResponse<String> installRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins/install"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"plugin_name\":\"opencli-browser\",\"marketplace_name\":\"poco-plugins\",\"scope\":\"user\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, installRes.statusCode());
            JsonNode installJson = JsonUtils.getMapper().readTree(installRes.body());
            assertEquals(1, installJson.path("installed").size());
            assertTrue(installJson.path("installed").get(0).path("enabled").asBoolean());
            assertEquals(3, installJson.path("summary").path("command_count").asInt());
            assertTrue(refreshCalls.get() >= 1);

            // 4. Save options
            HttpResponse<String> optsRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins/options"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"plugin_id\":\"opencli-browser@poco-plugins\",\"values\":{\"default_session\":\"work\"}}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, optsRes.statusCode());
            assertEquals("work", JsonUtils.getMapper().readTree(optsRes.body())
                .path("installed").get(0).path("options").path("default_session").asText());

            // 5. Toggle disable
            HttpResponse<String> toggleRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins/toggle"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"plugin_id\":\"opencli-browser@poco-plugins\",\"enabled\":false,\"scope\":\"user\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, toggleRes.statusCode());
            assertFalse(JsonUtils.getMapper().readTree(toggleRes.body())
                .path("installed").get(0).path("enabled").asBoolean());

            // 6. Uninstall
            HttpResponse<String> uninstallRes = http.send(
                HttpRequest.newBuilder(URI.create(base + "/api/plugins/uninstall"))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"plugin_id\":\"opencli-browser@poco-plugins\",\"scope\":\"user\"}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(200, uninstallRes.statusCode());
            assertEquals(0, JsonUtils.getMapper().readTree(uninstallRes.body()).path("installed").size());
        }
    }
}

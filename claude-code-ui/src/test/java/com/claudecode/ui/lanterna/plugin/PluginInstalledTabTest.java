package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;
import com.claudecode.runtime.plugins.PluginMarketplacePort;
import com.claudecode.runtime.plugins.PluginMarketplacePort.Scope;
import com.claudecode.runtime.mcp.McpManagementPort;
import com.claudecode.runtime.mcp.McpManagementPort.AuthStatus;
import com.claudecode.runtime.mcp.McpManagementPort.Server;
import com.claudecode.runtime.mcp.McpManagementPort.Status;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.mcp.McpServerConfig;
import com.claudecode.core.serialization.JsonUtils;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;


class PluginInstalledTabTest {

    private static Server server(McpServerConfig config, String scope, int scopeOrder,
                                 Status status, boolean manageable, AuthStatus authStatus) {
        return new Server(config.name(), config.name(), scope, scopeOrder, status, authStatus,
            authStatus == AuthStatus.NOT_AUTHENTICATED ? "auth: ✗ not authenticated" : "",
            false, manageable, config.transportType(), config.command(), config.args(),
            config.env(), config.url(), config.headers().size(), "test");
    }

    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);

    @TempDir
    Path tmp;

    private final List<String> recorded = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginSettingsPanel panel;

    private PluginInstalledTab openedTab(PluginPanelServices services, String args) {
        panel = new PluginSettingsPanel(services);
        panel.show(PluginRoute.parse(args), (line, _) -> recorded.add(line),
            () -> closed.set(true));
        return panel.installedTab();
    }

    private void send(KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    private PluginPanelServices installedServices() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("beta", "Beta plugin", "2.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginPanelTestHarness.install(services, "beta", "test-market");
        return services;
    }

    private static Boolean enabled(PluginPanelServices services, String pluginId) {
        return services.plugins().installedPlugins().stream()
            .filter(plugin -> plugin.pluginId().equals(pluginId))
            .findFirst().map(plugin -> plugin.enabled() != Boolean.FALSE).orElse(null);
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void emptyState_showsManagePluginsCopy() {
        PluginInstalledTab tab = openedTab(PluginPanelTestHarness.services(tmp), "manage");
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("Manage plugins", lines.getFirst());
        assertTrue(lines.contains("No plugins or MCP servers installed."));
        assertTrue(lines.contains("Esc to go back"));
    }

    @Test
    void list_showsScopeHeaderAndEnabledMarkers() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        assertEquals(2, tab.items().size());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("  User"), "scope header");
        assertTrue(lines.stream().anyMatch(
            l -> Strings.CS.contains(l, "alpha Plugin · test-market · ✔ enabled")));
    }

    @Test
    void delistedPluginIsAutoRemovedShownAsFlaggedAndCanBeDismissed() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path marketplace = PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"),
            "test-market", PluginPanelTestHarness.PluginSpec.of(
                "alpha", "Alpha plugin", "1.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        Files.writeString(marketplace.resolve(".claude-plugin/marketplace.json"), """
            {"name":"test-market","owner":{"name":"Tester"},
             "forceRemoveDeletedPlugins":true,"plugins":[]}
            """);

        PluginInstalledTab tab = openedTab(services, "manage");

        assertTrue(tab.items().isEmpty(), "delisted install is removed");
        assertEquals(1, tab.flaggedItems().size());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("  Flagged"));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, 
            "alpha Plugin · test-market · ✘ removed")));
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.FLAGGED_DETAILS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains(
            "Removed from marketplace · reason: delisted"));
        send(ENTER); // Dismiss
        assertEquals(PluginInstalledTab.Mode.LIST, tab.mode());
        assertTrue(tab.flaggedItems().isEmpty());
        assertTrue(services.plugins().flaggedPlugins().isEmpty());
    }

    @Test
    void fatalLoadErrorUsesFailedRowAndRemovalPreservesData() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp, List.of(
            new PluginError.PluginCacheMiss(
                "alpha@test-market", "alpha", "/missing/cache")));
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        Path dataDir = tmp.resolve("plugins-root/data/alpha-test-market");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("state"), "persistent");

        PluginInstalledTab tab = openedTab(services, "manage");

        assertTrue(tab.items().isEmpty());
        assertEquals(1, tab.failedItems().size());
        assertTrue(StyledText.plain(tab.buildLines()).stream().anyMatch(line -> Strings.CS.contains(line, 
            "alpha Plugin · test-market · ✘ failed to load · 1 error")));
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.FAILED_DETAILS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains(
            "Plugin \"alpha\" not cached at /missing/cache — run /plugins to refresh"));
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.LIST, tab.mode());
        assertTrue(tab.failedItems().isEmpty());
        assertTrue(services.plugins().installedPlugins().isEmpty());
        assertTrue(Files.exists(dataDir.resolve("state")));
    }

    @Test
    void mcpRowsSupportDetailsToolsAndSpaceToggle() {
        PluginPanelServices base = PluginPanelTestHarness.services(tmp);
        AtomicReference<McpManagementPort.Status> status =
            new AtomicReference<>(McpManagementPort.Status.CONNECTED);
        McpServerConfig config = new McpServerConfig(
            "docs", "npx", List.of("docs-mcp"), Map.of(), false, "stdio");
        McpManagementPort mcp = new McpManagementPort() {
            @Override
            public List<Server> servers() {
                return List.of(server(config, "user", 2, status.get(), true,
                    AuthStatus.NOT_APPLICABLE));
            }

            @Override
            public String toggle(String serverName) {
                status.set(status.get() == Status.DISABLED ? Status.DISCONNECTED : Status.DISABLED);
                return "toggled";
            }

            @Override public String reconnect(String serverName) { return "reconnected"; }

            @Override
            public List<Tool> tools(String serverName) {
                return List.of(new Tool("search", "Search documentation"));
            }
        };
        PluginPanelServices services = new PluginPanelServices(
            base.plugins(), Runnable::run, mcp);
        PluginInstalledTab tab = openedTab(services, "manage");

        assertEquals(1, tab.mcpItems().size());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("  User"));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "docs MCP · ✓ connected")));
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.MCP_DETAILS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains("❯ View tools"));
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.MCP_TOOLS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains("❯ search"));
        assertTrue(StyledText.plain(tab.buildLines()).contains("  Search documentation"));
        send(ESC);
        send(ESC);
        send(SPACE);
        assertEquals(McpManagementPort.Status.DISABLED, tab.mcpItems().getFirst().server().status());
    }

    @Test
    void remoteMcpMenuAuthenticatesWhileDynamicServersRemainReadOnly() {
        PluginPanelServices base = PluginPanelTestHarness.services(tmp);
        AtomicBoolean authenticated = new AtomicBoolean(false);
        AtomicBoolean toggled = new AtomicBoolean(false);
        McpServerConfig remote = new McpServerConfig(
            "remote", null, List.of(), Map.of(), false, "http",
            "https://example.test/mcp", Map.of());
        McpServerConfig dynamic = new McpServerConfig(
            "builtin", null, List.of(), Map.of(), false, "agent");
        McpManagementPort mcp = new McpManagementPort() {
            @Override
            public List<Server> servers() {
                return List.of(
                    server(remote, "user", 2, Status.NEEDS_AUTH, true,
                        AuthStatus.NOT_AUTHENTICATED),
                    server(dynamic, "dynamic", 5, Status.CONNECTED, false,
                        AuthStatus.NOT_APPLICABLE));
            }

            @Override public String toggle(String serverName) {
                toggled.set(true);
                return "toggled";
            }
            @Override public String reconnect(String serverName) { return "reconnected"; }
            @Override public List<Tool> tools(String serverName) { return List.of(); }
            @Override public boolean manageable(String serverName) { return !Strings.CS.equals("builtin", serverName); }
            @Override public AuthStatus authStatus(String serverName) {
                return Strings.CS.equals("remote", serverName) ? AuthStatus.NOT_AUTHENTICATED
                    : AuthStatus.NOT_APPLICABLE;
            }
            @Override public String authenticate(String serverName) {
                authenticated.set(true);
                return "authenticated";
            }
        };
        PluginInstalledTab tab = openedTab(new PluginPanelServices(
            base.plugins(), Runnable::run, mcp), "manage");

        send(ENTER);
        assertTrue(StyledText.plain(tab.buildLines()).contains("❯ Authenticate"));
        send(ENTER);
        assertTrue(authenticated.get());
        assertTrue(recorded.contains("authenticated"));

        tab = openedTab(new PluginPanelServices(base.plugins(), Runnable::run, mcp), "manage");
        send(DOWN);
        send(ENTER);
        List<String> dynamicDetails = StyledText.plain(tab.buildLines());
        assertFalse(dynamicDetails.stream().anyMatch(line -> Strings.CS.contains(line, "Disable")));
        assertFalse(dynamicDetails.stream().anyMatch(line -> Strings.CS.contains(line, "Reconnect")));
        send(ESC);
        send(SPACE);
        assertFalse(toggled.get());
    }

    @Test
    void mcpToolListEnterOpensSchemaDetailAndEscapeReturnsToList() throws Exception {
        PluginPanelServices base = PluginPanelTestHarness.services(tmp);
        McpServerConfig config = new McpServerConfig(
            "docs", "npx", List.of("docs-mcp"), Map.of(), false, "stdio");
        var schema = JsonUtils.getMapper().readTree("""
            {"type":"object","properties":{"query":{"type":"string","description":"Search text"}},
             "required":["query"]}
            """);
        McpManagementPort mcp = new McpManagementPort() {
            @Override public List<Server> servers() {
                return List.of(server(config, "user", 2, Status.CONNECTED, true,
                    AuthStatus.NOT_APPLICABLE));
            }
            @Override public String toggle(String serverName) { return "toggled"; }
            @Override public String reconnect(String serverName) { return "reconnected"; }
            @Override public List<Tool> tools(String serverName) {
                return List.of(new Tool("search", "Search documentation", schema));
            }
        };
        PluginInstalledTab tab = openedTab(new PluginPanelServices(
            base.plugins(), Runnable::run, mcp), "manage");

        send(ENTER);
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.MCP_TOOLS, tab.mode());
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.MCP_TOOL_DETAIL, tab.mode());
        List<String> detail = StyledText.plain(tab.buildLines());
        assertTrue(detail.contains("search"));
        assertTrue(detail.contains("Search documentation"));
        assertTrue(detail.stream().anyMatch(line -> Strings.CS.contains(line, "query (required): string")));
        assertTrue(detail.stream().anyMatch(line -> Strings.CS.contains(line, "Search text")));
        send(ESC);
        assertEquals(PluginInstalledTab.Mode.MCP_TOOLS, tab.mode());
    }

    @Test
    void printableInputEntersSearchAndFiltersByNameOrDescription() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");

        send(new KeyStroke('b', false, false));

        assertTrue(tab.isSearchMode());
        assertEquals("b", tab.searchQuery());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "beta Plugin")));
        assertFalse(lines.stream().anyMatch(l -> Strings.CS.contains(l, "alpha Plugin")));
    }

    @Test
    void searchEscapeClearsThenExitsAndEnterOpensFilteredSelection() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(new KeyStroke('b', false, false));
        send(ENTER); // useSearchInput commit-exit
        assertFalse(tab.isSearchMode());
        send(ENTER); // filtered beta details
        assertEquals("beta", tab.selectedItem().name());
        send(ESC); // back to list, query retained

        send(new KeyStroke('/', false, false));
        send(new KeyStroke('z', false, false));
        assertTrue(StyledText.plain(tab.buildLines()).contains("No items match \"z\""));
        send(ESC); // non-empty query: clear, remain in search
        assertTrue(tab.isSearchMode());
        assertEquals("", tab.searchQuery());
        send(ESC); // empty query: leave search
        assertFalse(tab.isSearchMode());
        assertFalse(closed.get());
    }

    @Test
    void upAtFirstItemMovesFocusToSearch() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(new KeyStroke(KeyType.ARROW_UP));
        assertTrue(tab.isSearchMode());
    }

    @Test
    void spaceToggle_disablesPluginAndMarksPending() {
        PluginPanelServices services = installedServices();
        PluginInstalledTab tab = openedTab(services, "manage");
        send(SPACE); // alpha (first row) → will-disable
        assertEquals("will-disable", tab.pendingToggles().get("alpha@test-market"));
        assertEquals(Boolean.FALSE, enabled(services, "alpha@test-market"));
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "→ will disable")));
        assertTrue(lines.contains("Run /reload-plugins to apply changes"));
        // Second press cancels: pending cleared, setting restored.
        send(SPACE);
        assertTrue(tab.pendingToggles().isEmpty());
        assertEquals(Boolean.TRUE, enabled(services, "alpha@test-market"));
    }

    @Test
    void escWithPendingToggles_closesWithReloadMessage() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(SPACE);
        send(ESC);
        assertTrue(closed.get());
        assertEquals(List.of("Run /reload-plugins to apply plugin changes."), recorded);
    }

    // ── details & operations ─────────────────────────────────────────────────

    @Test
    void detailsView_showsMetadataAndMenu() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(ENTER); // alpha details
        assertEquals(PluginInstalledTab.Mode.DETAILS, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("alpha @ test-market", lines.getFirst());
        assertTrue(lines.contains("Scope: user"));
        assertTrue(lines.contains("Version: 1.0.0"));
        assertTrue(lines.contains("Status: Enabled"));
        assertTrue(lines.contains("❯ Disable plugin"));
        assertTrue(lines.contains("  Mark for update"));
        assertTrue(lines.contains("  Update now"));
        assertTrue(lines.contains("  Uninstall"));
        assertTrue(lines.contains("  Back to plugin list"));
    }

    @Test
    void detailsViewShowsPluginErrorsAndGuidance() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp, List.of(
            new PluginError.ComponentLoadFailed("alpha@test-market", "alpha",
                PluginError.Component.COMMANDS, "commands/broken.md", "invalid frontmatter")));
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");

        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER);
        List<String> lines = StyledText.plain(tab.buildLines());

        assertTrue(lines.contains("1 error:"));
        assertTrue(lines.contains(
            "  commands load failed from commands/broken.md: invalid frontmatter"));
        assertTrue(lines.contains(
            "  → Check commands directory structure and file permissions"));
    }

    @Test
    void disableFromDetails_writesSettingAndClosesWithMessage() {
        PluginPanelServices services = installedServices();
        openedTab(services, "manage");
        send(ENTER); // details
        send(ENTER); // "Disable plugin"
        assertTrue(closed.get());
        assertEquals(List.of("✓ Disabled alpha. Run /reload-plugins to apply."), recorded);
        assertEquals(Boolean.FALSE, enabled(services, "alpha@test-market"));
    }

    @Test
    void localPluginCannotBeMarkedForRemoteUpdate() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(ENTER);
        send(DOWN);  // Mark for update
        send(ENTER);

        assertTrue(StyledText.plain(tab.buildLines()).stream().anyMatch(line -> Strings.CS.contains(line, 
            "Local plugins cannot be updated remotely. To update, modify the source at: "
                + "./plugins/alpha")));
    }

    @Test
    void remotePluginCanBeMarkedAndUnmarkedForUpdate() throws Exception {
        PluginPanelServices services = installedServices();
        Files.writeString(tmp.resolve("m1/.claude-plugin/marketplace.json"), """
            {
              "name": "test-market",
              "owner": {"name": "Tester"},
              "plugins": [
                {"name":"alpha","source":{"source":"github","repo":"owner/alpha"},
                 "description":"Alpha plugin","version":"1.0.0"},
                {"name":"beta","source":"./plugins/beta",
                 "description":"Beta plugin","version":"2.0.0"}
              ]
            }""");
        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER);
        send(DOWN);
        send(ENTER);

        List<String> marked = StyledText.plain(tab.buildLines());
        assertTrue(marked.contains("Status: Enabled · Marked for update"));
        assertTrue(marked.contains("❯ Unmark for update"));
        send(ENTER);
        List<String> unmarked = StyledText.plain(tab.buildLines());
        assertTrue(unmarked.contains("Status: Enabled"));
        assertFalse(unmarked.stream().anyMatch(line -> Strings.CS.contains(line, "Marked for update")));
    }

    @Test
    void uninstallFromDetails_removesInstallRecord() {
        PluginPanelServices services = installedServices();
        openedTab(services, "manage");
        send(ENTER); // details for alpha
        send(DOWN);  // → Mark for update
        send(DOWN);  // → Update now
        send(DOWN);  // → Uninstall
        send(ENTER);
        assertTrue(closed.get());
        assertEquals(List.of("✓ Uninstalled alpha. Run /reload-plugins to apply."), recorded);
        assertTrue(services.plugins().installedPlugins().stream()
            .noneMatch(s -> Strings.CS.equals(s.pluginId(), "alpha@test-market")));
    }

    @Test
    void uninstallRemovesOnlySelectedScope() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        services.plugins().install("alpha", "test-market", Scope.PROJECT);
        services.plugins().install("alpha", "test-market", Scope.USER);
        services.plugins().disable("alpha@test-market", Scope.PROJECT);
        openedTab(services, "manage");
        send(DOWN);  // project row -> user row
        send(ENTER);
        send(DOWN);  // Mark for update
        send(DOWN);  // Update now
        send(DOWN);  // Uninstall
        send(ENTER);

        assertTrue(closed.get());
        List<Scope> remaining = services.plugins().installedPlugins().stream()
            .filter(plugin -> Strings.CS.equals(plugin.pluginId(), "alpha@test-market"))
            .flatMap(plugin -> plugin.installations().stream())
            .map(PluginMarketplacePort.Installation::scope)
            .toList();
        assertEquals(List.of(Scope.PROJECT), remaining);
    }

    @Test
    void projectEnabledUninstallOffersLocalDisableInstead() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        services.plugins().install("alpha", "test-market", Scope.PROJECT);
        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER);
        send(DOWN);
        send(DOWN);
        send(DOWN);
        send(ENTER);

        assertEquals(PluginInstalledTab.Mode.CONFIRM_PROJECT_UNINSTALL, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).stream()
            .anyMatch(line -> Strings.CS.contains(line, "shared with your team")));
        send(new KeyStroke('y', false, false));
        assertTrue(closed.get());
        assertEquals(List.of("✓ Disabled alpha in .claude/settings.local.json. "
            + "Run /reload-plugins to apply."), recorded);
        assertEquals(Boolean.FALSE, enabled(services, "alpha@test-market"));
        assertFalse(services.plugins().installedPlugins().isEmpty());
    }

    @Test
    void persistentDataRequiresExplicitDeleteOrKeepChoice() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        Path dataDir = tmp.resolve("plugins-root/data/alpha-test-market");
        Files.createDirectories(dataDir);
        Files.writeString(dataDir.resolve("state.db"), "persistent");
        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER);
        send(DOWN);
        send(DOWN);
        send(DOWN);
        send(ENTER);

        assertEquals(PluginInstalledTab.Mode.CONFIRM_DATA_CLEANUP, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains(
            "y to delete · n to keep · esc to cancel"));
        send(ENTER); // destructive delete must never be the default
        assertEquals(PluginInstalledTab.Mode.CONFIRM_DATA_CLEANUP, tab.mode());
        send(new KeyStroke('n', false, false));
        assertTrue(closed.get());
        assertTrue(Files.exists(dataDir.resolve("state.db")));
        assertEquals(List.of("✓ Uninstalled alpha · data preserved. "
            + "Run /reload-plugins to apply."), recorded);
    }

    @Test
    void enableRoute_autoNavigatesAndExecutes() {
        PluginPanelServices services = installedServices();
        services.plugins().disable("alpha@test-market", Scope.USER);
        openedTab(services, "enable alpha");
        assertTrue(closed.get());
        assertEquals(List.of("✓ Enabled alpha. Run /reload-plugins to apply."), recorded);
        assertEquals(Boolean.TRUE, enabled(services, "alpha@test-market"));
    }

    @Test
    void enablingPluginWalksTopLevelThenChannelConfiguration() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path marketplace = PluginPanelTestHarness.writeMarketplace(services,
            tmp.resolve("channel-market"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("chat", "Chat plugin", "1.0.0"));
        Files.writeString(marketplace.resolve("plugins/chat/.claude-plugin/plugin.json"), """
            {"name":"chat","version":"1.0.0","description":"Chat plugin",
             "userConfig":{"endpoint":{"type":"string","title":"Endpoint",
               "description":"API URL","required":true}},
             "channels":[{"server":"telegram","displayName":"Telegram",
               "userConfig":{"token":{"type":"string","title":"Token",
                 "description":"Bot token","required":true}}}],
             "mcpServers":{"telegram":{"command":"bot","env":{
               "ENDPOINT":"${user_config.endpoint}","TOKEN":"${user_config.token}"}}}}
            """);
        PluginPanelTestHarness.install(services, "chat", "test-market");
        services.plugins().disable("chat@test-market", Scope.USER);
        PluginInstalledTab tab = openedTab(services, "manage");

        send(ENTER); // details
        send(ENTER); // enable
        assertEquals(PluginInstalledTab.Mode.OPTIONS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains("Configure chat"));
        for (char c : "https://api".toCharArray()) send(new KeyStroke(c, false, false));
        send(ENTER);

        assertFalse(closed.get());
        assertTrue(StyledText.plain(tab.buildLines()).contains("Configure Telegram"));
        for (char c : "secret".toCharArray()) send(new KeyStroke(c, false, false));
        send(ENTER);

        assertTrue(closed.get());
        assertEquals(List.of("✓ Enabled and configured chat. Run /reload-plugins to apply."),
            recorded);
    }

    @Test
    void actionRouteForUnknownPlugin_closesWithNotInstalledMessage() {
        openedTab(installedServices(), "uninstall ghost");
        assertTrue(closed.get());
        assertEquals(List.of("Plugin \"ghost\" is not installed in this project"), recorded);
    }

    @Test
    void escFromDetails_returnsToList() {
        PluginInstalledTab tab = openedTab(installedServices(), "manage");
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.DETAILS, tab.mode());
        send(ESC);
        assertEquals(PluginInstalledTab.Mode.LIST, tab.mode());
        assertFalse(closed.get());
        send(ESC);
        assertTrue(closed.get());
    }

    @Test
    void configureOptions_savesAndClosesWithConfigurationMessage() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            new PluginPanelTestHarness.PluginSpec("cfg", "Config plugin", "1.0.0", """
                {"endpoint": {"type": "string", "title": "Endpoint"}}"""));
        PluginPanelTestHarness.install(services, "cfg", "test-market");
        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER); // details
        send(DOWN);  // → Mark for update
        send(DOWN);  // → Configure options
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.OPTIONS, tab.mode());
        for (char c : "https://api".toCharArray()) {
            send(new KeyStroke(c, false, false));
        }
        send(ENTER);
        assertTrue(closed.get());
        assertEquals(List.of("Configuration saved. Run /reload-plugins for changes to take effect."),
            recorded);
        assertEquals("https://api",
            services.plugins().loadOptions("cfg@test-market").get("endpoint"));
    }

    @Test
    void mcpbConfigureUsesBundleSchemaAndPersistsServerConfiguration() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path marketplace = PluginPanelTestHarness.writeMarketplace(services,
            tmp.resolve("mcpb-market"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("bundle", "Bundle plugin", "1.0.0"));
        Path source = marketplace.resolve("plugins/bundle");
        Files.writeString(source.resolve(".claude-plugin/plugin.json"), """
            {"name":"bundle","version":"1.0.0","description":"Bundle plugin",
             "mcpServers":"server.mcpb"}
            """);
        Files.write(source.resolve("server.mcpb"), bundle(Map.of(
            "manifest.json", """
                {"manifest_version":"0.4","name":"bundled","version":"1",
                 "description":"Bundled server","author":{"name":"Tester"},
                 "server":{"type":"binary","entry_point":"bin/server",
                   "mcp_config":{"command":"${__dirname}/bin/server",
                     "args":["${user_config.channel}"]}},
                 "user_config":{"channel":{"type":"string","title":"Channel",
                   "description":"Channel name","required":true}}}
                """,
            "bin/server", "binary")));
        PluginPanelTestHarness.install(services, "bundle", "test-market");
        PluginInstalledTab tab = openedTab(services, "manage");

        send(ENTER);
        List<String> details = StyledText.plain(tab.buildLines());
        assertTrue(details.contains("  Configure"));
        send(DOWN); // Mark for update
        send(DOWN); // Configure
        send(ENTER);
        assertEquals(PluginInstalledTab.Mode.OPTIONS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).stream()
            .anyMatch(line -> Strings.CS.contains(line, "Channel")));
        for (char c : "stable".toCharArray()) send(new KeyStroke(c, false, false));
        send(ENTER);

        assertTrue(closed.get());
        assertEquals(List.of("Configuration saved. Run /reload-plugins for changes to take effect."),
            recorded);
        var saved = services.plugins().loadMcpbConfiguration(
            "bundle@test-market", tab.selectedItem().installPath()).orElseThrow();
        assertEquals("stable", saved.existingValues().get("channel"));
        assertTrue(saved.validationErrors().isEmpty());
    }

    @Test
    void detailsViewShowsHomepageAndRepositoryActionsFromManifest() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path marketplace = PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("links"),
            "test-market", PluginPanelTestHarness.PluginSpec.of(
                "linked", "Linked plugin", "1.0.0"));
        Files.writeString(marketplace.resolve(
            "plugins/linked/.claude-plugin/plugin.json"), """
            {"name":"linked","version":"1.0.0","description":"Linked plugin",
             "homepage":"https://example.com/docs",
             "repository":"https://example.com/repo"}
            """);
        PluginPanelTestHarness.install(services, "linked", "test-market");

        PluginInstalledTab tab = openedTab(services, "manage");
        send(ENTER);
        List<String> lines = StyledText.plain(tab.buildLines());

        assertTrue(lines.contains("  Open homepage"));
        assertTrue(lines.contains("  View repository"));
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
}

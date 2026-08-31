package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import com.claudecode.core.io.FileUtils;
import com.claudecode.core.serialization.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;


class PluginDiscoverTabTest {

    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);

    @TempDir
    Path tmp;

    private final List<String> recorded = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginSettingsPanel panel;

    private PluginDiscoverTab openedTab(PluginPanelServices services, String args) {
        panel = new PluginSettingsPanel(services);
        panel.show(PluginRoute.parse(args), (line, _) -> recorded.add(line),
            () -> closed.set(true));
        return panel.discoverTab();
    }

    private void send(KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    private PluginPanelServices singleMarketplaceServices() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("beta", "Beta plugin", "2.0.0"));
        return services;
    }

    // ── initial view resolution ──────────────────────────────────────────────

    @Test
    void singleMarketplace_skipsMarketplaceListAndShowsPlugins() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("Install Plugins"));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "◯ alpha")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Alpha plugin · v1.0.0")));
    }

    @Test
    void multipleMarketplaces_startOnMarketplaceList() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m2"), "second-market",
            PluginPanelTestHarness.PluginSpec.of("gamma", "Gamma plugin", "0.1.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        assertEquals(PluginDiscoverTab.Mode.MARKETPLACE_LIST, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("Select marketplace", lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "2 plugins available")));
        // Enter drills into the highlighted marketplace's plugin list.
        send(ENTER);
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
    }

    @Test
    void noMarketplaces_showEmptyState() {
        PluginDiscoverTab tab = openedTab(PluginPanelTestHarness.services(tmp), null);
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("No plugins available."));
        assertTrue(lines.contains("Add a marketplace first using the Marketplaces tab."));
    }

    @Test
    void managedPolicyThatBlocksAllMarketplacesExplainsTheEmptyState() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Files.writeString(tmp.resolve("policy-settings.json"),
            "{\"strictKnownMarketplaces\":[]}");

        List<String> lines = StyledText.plain(openedTab(services, null).buildLines());

        assertTrue(lines.contains(
            "Your organization policy does not allow any external marketplaces."));
        assertTrue(lines.contains("Contact your administrator."));
    }

    @Test
    void partialMarketplaceFailureShowsWarningAndKeepsAvailablePlugins() throws Exception {
        PluginPanelServices services = singleMarketplaceServices();
        Path known = tmp.resolve("plugins-root/known_marketplaces.json");
        String good = JsonUtils.toJson(tmp.resolve("m1").toString());
        String missing = JsonUtils.toJson(tmp.resolve("missing-marketplace").toString());
        Files.writeString(known, """
            {
              "test-market": {
                "source": {"source":"directory","path":%s},
                "installLocation": %s
              },
              "broken-market": {
                "source": {"source":"directory","path":%s},
                "installLocation": %s
              }
            }""".formatted(good, good, missing, missing));

        PluginDiscoverTab tab = openedTab(services, null);
        List<String> lines = StyledText.plain(tab.buildLines());

        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.startsWith(line, "Warning: Failed to load marketplace 'broken-market':")));
        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, "alpha")));
    }

    @Test
    void allMarketplaceFailuresAreReportedAsAnError() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path missing = tmp.resolve("missing-marketplace");
        Files.createDirectories(tmp.resolve("plugins-root"));
        String missingJson = JsonUtils.toJson(missing.toString());
        Files.writeString(tmp.resolve("plugins-root/known_marketplaces.json"), """
            {
              "broken-market": {
                "source": {"source":"directory","path":%s},
                "installLocation": %s
              }
            }""".formatted(missingJson, missingJson));

        PluginDiscoverTab tab = openedTab(services, null);

        assertEquals(PluginDiscoverTab.Mode.ERROR, tab.mode());
        assertTrue(Strings.CS.startsWith(StyledText.plain(tab.buildLines()).getFirst(), "Failed to load all marketplaces. Errors: broken-market:"));
    }

    @Test
    void targetPluginRoute_jumpsStraightToDetails() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), "install beta");
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, tab.mode());
        assertEquals("beta", tab.selectedPlugin().entry().name());
    }

    @Test
    void unknownTargetPlugin_showsNotFoundError() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), "install nope");
        assertEquals(PluginDiscoverTab.Mode.ERROR, tab.mode());
        assertEquals("Plugin \"nope\" not found in any marketplace", tab.error());
    }

    @Test
    void alreadyInstalledTargetPlugin_showsAlreadyInstalledError() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginDiscoverTab tab = openedTab(services, "install alpha");
        assertEquals(PluginDiscoverTab.Mode.ERROR, tab.mode());
        assertEquals("Plugin 'alpha@test-market' is already installed globally. "
            + "Use '/plugin' to manage existing plugins.", tab.error());
    }

    // ── navigation ───────────────────────────────────────────────────────────

    @Test
    void listNavigation_supportsArrowsAndJk() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        assertEquals(0, tab.selectedPluginIndex());
        send(DOWN);
        assertEquals(1, tab.selectedPluginIndex());
        send(new KeyStroke('k', false, false));
        assertEquals(0, tab.selectedPluginIndex());
        send(new KeyStroke('j', false, false));
        assertEquals(1, tab.selectedPluginIndex());
    }

    @Test
    void detailsView_showsTrustWarningVerbatimAndScopedInstallMenu() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        send(ENTER); // alpha → details
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("⚠ " + PluginDiscoverTab.TRUST_WARNING));
        assertTrue(lines.contains("> Install for you (user scope)"));
        assertTrue(lines.contains(
            "  Install for all collaborators on this repository (project scope)"));
        assertTrue(lines.contains("  Install for you, in this repo only (local scope)"));
        assertTrue(lines.contains("  Back to plugin list"));
    }

    @Test
    void detailsViewShowsHomepageAndGithubSourceActions() throws Exception {
        PluginPanelServices services = singleMarketplaceServices();
        Files.writeString(tmp.resolve("m1/.claude-plugin/marketplace.json"), """
            {
              "name": "test-market",
              "owner": {"name": "Tester"},
              "plugins": [{
                "name": "linked",
                "source": {"source": "github", "repo": "owner/linked"},
                "description": "Linked plugin",
                "version": "1.0.0",
                "homepage": "https://example.com/docs"
              }]
            }""");

        PluginDiscoverTab tab = openedTab(services, "install linked");
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("  Open homepage"));
        assertTrue(lines.contains("  View on GitHub"));
    }

    @Test
    void detailsViewDoesNotInventRepositoryActionForNonGithubSource() throws Exception {
        PluginPanelServices services = singleMarketplaceServices();
        Files.writeString(tmp.resolve("m1/.claude-plugin/marketplace.json"), """
            {
              "name": "test-market",
              "owner": {"name": "Tester"},
              "plugins": [{
                "name": "linked",
                "source": "./plugins/alpha",
                "description": "Linked plugin",
                "version": "1.0.0",
                "repository": "https://example.com/not-github-source"
              }]
            }""");

        PluginDiscoverTab tab = openedTab(services, "install linked");
        List<String> lines = StyledText.plain(tab.buildLines());
        assertFalse(lines.contains("  View on GitHub"));
    }

    @Test
    void escFromDetails_returnsToPluginList_thenEscCloses() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        send(ENTER);
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, tab.mode());
        send(ESC);
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
        send(ESC);
        assertTrue(closed.get());
    }

    @Test
    void escFromPluginList_withMultipleMarketplaces_returnsToMarketplaceList() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m2"), "second-market",
            PluginPanelTestHarness.PluginSpec.of("gamma", "Gamma plugin", "0.1.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        send(ENTER); // into plugin list
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
        send(ESC);
        assertEquals(PluginDiscoverTab.Mode.MARKETPLACE_LIST, tab.mode());
        assertFalse(closed.get());
    }

    // ── install flow ─────────────────────────────────────────────────────────

    @Test
    void installUserScope_installsAndClosesWithResultMessage() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginDiscoverTab tab = openedTab(services, null);
        send(ENTER); // details for alpha
        send(ENTER); // Install for you (user scope)
        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed alpha. Run /reload-plugins to activate."), recorded);
        assertTrue(services.plugins().installedPlugins().stream()
            .anyMatch(s -> Strings.CS.equals(s.pluginId(), "alpha@test-market")));
    }

    @Test
    void installPluginWithUserConfig_walksOptionsFlowAndSavesValues() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            new PluginPanelTestHarness.PluginSpec("cfg", "Config plugin", "1.0.0", """
                {"apiKey": {"type": "string", "title": "API Key", "required": true}}"""));
        PluginDiscoverTab tab = openedTab(services, null);
        send(ENTER); // details
        send(ENTER); // install user scope → options flow
        assertEquals(PluginDiscoverTab.Mode.OPTIONS, tab.mode());
        assertNotNull(tab.optionsView());
        for (char c : "secret".toCharArray()) {
            send(new KeyStroke(c, false, false));
        }
        send(ENTER); // last field → save
        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed and configured cfg. Run /reload-plugins to apply."),
            recorded);
        assertEquals("secret",
            services.plugins().loadOptions("cfg@test-market").get("apiKey"));
    }

    @Test
    void installWalksTopLevelThenChannelConfiguration() throws Exception {
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
        PluginDiscoverTab tab = openedTab(services, null);

        send(ENTER);
        send(ENTER);
        assertEquals(PluginDiscoverTab.Mode.OPTIONS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains("Configure chat"));
        for (char c : "https://api".toCharArray()) send(new KeyStroke(c, false, false));
        send(ENTER);

        assertFalse(closed.get());
        assertEquals(PluginDiscoverTab.Mode.OPTIONS, tab.mode());
        assertTrue(StyledText.plain(tab.buildLines()).contains("Configure Telegram"));
        for (char c : "secret".toCharArray()) send(new KeyStroke(c, false, false));
        send(ENTER);

        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed and configured chat. Run /reload-plugins to apply."),
            recorded);
        assertTrue(services.plugins().unconfiguredSteps("chat@test-market",
            tmp.resolve("plugins-root/cache/test-market/chat/1.0.0")).isEmpty());
    }

    @Test
    void requiredOptionLeftBlank_blocksSaveWithError() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            new PluginPanelTestHarness.PluginSpec("cfg", "Config plugin", "1.0.0", """
                {"apiKey": {"type": "string", "title": "API Key", "required": true}}"""));
        PluginDiscoverTab tab = openedTab(services, null);
        send(ENTER);
        send(ENTER);
        send(ENTER); // save with blank required field
        assertFalse(closed.get());
        assertEquals("API Key is required", tab.optionsView().error());
    }

    @Test
    void optionsFlowEscape_skipsConfigurationButKeepsInstall() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            new PluginPanelTestHarness.PluginSpec("cfg", "Config plugin", "1.0.0", """
                {"apiKey": {"type": "string", "title": "API Key", "required": true}}"""));
        openedTab(services, null);
        send(ENTER);
        send(ENTER); // install → options
        send(ESC);   // skip configuration
        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed cfg. Run /reload-plugins to apply."), recorded);
        assertTrue(services.plugins().installedPlugins().stream()
            .anyMatch(s -> Strings.CS.equals(s.pluginId(), "cfg@test-market")));
    }

    @Test
    void enterOnInstalledPlugin_divertsToInstalledTab() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginDiscoverTab tab = openedTab(services, null);
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "✔ alpha (installed)")));
        send(ENTER); // installed plugin → manage-plugins divert
        assertEquals(PluginSettingsPanel.Tab.INSTALLED, panel.selectedTab());
        assertEquals("alpha", panel.installedTab().selectedItem().name());
    }



    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);
    private static final KeyStroke KEY_I = new KeyStroke('i', false, false);

    private void sendChars(String text) {
        for (char c : text.toCharArray()) {
            send(new KeyStroke(c, false, false));
        }
    }

    private List<String> tabLines(PluginDiscoverTab tab) {
        return StyledText.plain(tab.buildLines());
    }

    @Test
    void installCounts_sortDescendingWithNameTieBreak() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp,
            () -> Map.of("alpha@test-market", 5L, "beta@test-market", 100L));
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("beta", "Beta plugin", "2.0.0"),
            PluginPanelTestHarness.PluginSpec.of("gamma", "Gamma plugin", "3.0.0"),
            PluginPanelTestHarness.PluginSpec.of("delta", "Delta plugin", "4.0.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        // beta(100) > alpha(5) > {delta, gamma}(0, name tie-break)
        assertEquals(List.of("beta", "alpha", "delta", "gamma"),
            tab.plugins().stream().map(p -> p.entry().name()).toList());
    }

    @Test
    void installCountsUnavailable_fallsBackToAlphabetical() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        assertEquals(List.of("alpha", "beta"),
            tab.plugins().stream().map(p -> p.entry().name()).toList());
        assertTrue(tabLines(tab).stream().noneMatch(l -> Strings.CS.contains(l, "installs")));
    }

    @Test
    void installCountsSupplierThrows_fallsBackToAlphabetical() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp,
            () -> { throw new RuntimeException("boom"); });
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("beta", "Beta plugin", "2.0.0"),
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, tab.mode());
        assertEquals(List.of("alpha", "beta"),
            tab.plugins().stream().map(p -> p.entry().name()).toList());
    }

    @Test
    void officialMarketplaceRows_showFormattedInstallCounts() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp,
            () -> Map.of("pop@claude-plugins-official", 36200L));
        PluginPanelTestHarness.writeOfficialMarketplace(services, tmp.resolve("m1"),
            PluginPanelTestHarness.PluginSpec.of("pop", "Popular plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("niche", "Niche plugin", "1.0.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        List<String> lines = tabLines(tab);
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "◯ pop · 36.2K installs")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "◯ niche · 0 installs")));
    }

    @Test
    void nonOfficialMarketplaceRows_hideInstallCounts() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp,
            () -> Map.of("alpha@test-market", 5L));
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginDiscoverTab tab = openedTab(services, null);
        assertTrue(tabLines(tab).stream().noneMatch(l -> Strings.CS.contains(l, "installs")));
    }



    private PluginPanelServices searchableServices() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "First plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("zulu", "Last plugin", "2.0.0"));
        return services;
    }

    @Test
    void typingPrintableChar_entersSearchModeSeedingQuery() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        sendChars("al");
        assertTrue(tab.isSearchMode());
        assertEquals("al", tab.searchQuery());
        assertEquals(List.of("alpha"),
            tab.filteredPlugins().stream().map(p -> p.entry().name()).toList());
        // Focused SearchBox: ⌕ prefix + query + block cursor.
        assertTrue(tabLines(tab).stream().anyMatch(l -> Strings.CS.contains(l, "│ ⌕ al█")));
    }

    @Test
    void slashEntersSearchMode_withEmptyQuery() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        send(new KeyStroke('/', false, false));
        assertTrue(tab.isSearchMode());
        assertEquals("", tab.searchQuery());
        assertEquals(2, tab.filteredPlugins().size());
    }

    @Test
    void upAtTopOfList_entersSearchMode() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        send(UP);
        assertTrue(tab.isSearchMode());
    }

    @Test
    void queryChange_resetsSelection() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        send(DOWN);
        assertEquals(1, tab.selectedPluginIndex());
        sendChars("l"); // enters search, seeds query → selection resets
        assertEquals(0, tab.selectedPluginIndex());
    }

    @Test
    void searchFiltersOnDescriptionToo() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        sendChars("Last");
        assertEquals(List.of("zulu"),
            tab.filteredPlugins().stream().map(p -> p.entry().name()).toList());
    }

    @Test
    void enterExitsSearchMode_keepingFilter() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        sendChars("alp");
        send(ENTER);
        assertFalse(tab.isSearchMode());
        assertEquals("alp", tab.searchQuery());
        assertEquals(1, tab.filteredPlugins().size());
        // Unfocused SearchBox still shows the query, without the cursor.
        assertTrue(tabLines(tab).stream().anyMatch(l -> Strings.CS.contains(l, "│ ⌕ alp ")));
    }

    @Test
    void escInSearchMode_clearsQueryThenExits() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        sendChars("alp");
        send(ESC);
        assertTrue(tab.isSearchMode(), "first Esc clears the query but stays in search");
        assertEquals("", tab.searchQuery());
        assertEquals(2, tab.filteredPlugins().size());
        send(ESC);
        assertFalse(tab.isSearchMode(), "second Esc exits search mode");
        assertFalse(closed.get(), "panel stays open");
    }

    @Test
    void backspaceOnEmptyQuery_exitsSearchMode() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        send(new KeyStroke('/', false, false));
        send(new KeyStroke(KeyType.BACKSPACE));
        assertFalse(tab.isSearchMode());
    }

    @Test
    void navigationKeysBecomeTextInSearchMode() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        send(new KeyStroke('/', false, false));
        send(new KeyStroke('j', false, false));
        assertTrue(tab.isSearchMode());
        assertEquals("j", tab.searchQuery());
        assertEquals(0, tab.selectedPluginIndex());
    }

    @Test
    void noMatches_showNoPluginsMatchLine() {
        PluginDiscoverTab tab = openedTab(searchableServices(), null);
        sendChars("zzz");
        assertTrue(tabLines(tab).contains("No plugins match \"zzz\""));
    }

    @Test
    void searchMode_blocksTabSwitching() {
        openedTab(searchableServices(), null);
        send(new KeyStroke('/', false, false));
        send(new KeyStroke(KeyType.ARROW_RIGHT));
        assertEquals(PluginSettingsPanel.Tab.DISCOVER, panel.selectedTab());
    }



    @Test
    void spaceTogglesSelection_withCheckboxGlyphs() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        send(SPACE);
        assertEquals(Set.of("alpha@test-market"), tab.selectedForInstall());
        assertTrue(tabLines(tab).stream().anyMatch(l -> Strings.CS.contains(l, "◉ alpha")));
        send(SPACE);
        assertTrue(tab.selectedForInstall().isEmpty());
        assertTrue(tabLines(tab).stream().anyMatch(l -> Strings.CS.contains(l, "◯ alpha")));
    }

    @Test
    void spaceOnInstalledPlugin_isIgnored() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginDiscoverTab tab = openedTab(services, null);
        send(SPACE); // alpha (installed) is first alphabetically
        assertTrue(tab.selectedForInstall().isEmpty());
    }

    @Test
    void iWithoutSelection_doesNothingAndNeverEntersSearch() {
        PluginDiscoverTab tab = openedTab(singleMarketplaceServices(), null);
        send(KEY_I);
        assertFalse(tab.isSearchMode());
        assertEquals("", tab.searchQuery());
        assertFalse(closed.get());
    }

    @Test
    void batchInstall_installsAllSelectedAndReportsPluralResult() {
        PluginPanelServices services = singleMarketplaceServices();
        openedTab(services, null);
        send(SPACE);
        send(DOWN);
        send(SPACE);
        send(KEY_I);
        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed 2 plugins. Run /reload-plugins to activate."),
            recorded);
        assertTrue(services.plugins().installedPlugins().stream()
            .anyMatch(s -> Strings.CS.equals(s.pluginId(), "alpha@test-market")));
        assertTrue(services.plugins().installedPlugins().stream()
            .anyMatch(s -> Strings.CS.equals(s.pluginId(), "beta@test-market")));
    }

    @Test
    void batchInstall_singleSelection_usesSingularNoun() {
        openedTab(singleMarketplaceServices(), null);
        send(SPACE);
        send(KEY_I);
        assertEquals(List.of("✓ Installed 1 plugin. Run /reload-plugins to activate."),
            recorded);
    }

    @Test
    void batchInstall_allFailures_recordsFailedToInstall() {
        PluginPanelServices services = singleMarketplaceServices();
        FileUtils.deleteRecursively(tmp.resolve("m1").resolve("plugins").resolve("alpha"));
        openedTab(services, null);
        send(SPACE); // alpha — source dir removed, install will fail
        send(KEY_I);
        assertTrue(closed.get());
        assertEquals(1, recorded.size());
        assertTrue(Strings.CS.startsWith(recorded.getFirst(), "Failed to install: alpha ("),
            "got: " + recorded.getFirst());
    }

    @Test
    void batchInstall_partialFailure_reportsCountsAndFailedNames() {
        PluginPanelServices services = singleMarketplaceServices();
        FileUtils.deleteRecursively(tmp.resolve("m1").resolve("plugins").resolve("alpha"));
        openedTab(services, null);
        send(SPACE);        // alpha (broken)
        send(DOWN);
        send(SPACE);        // beta (fine)
        send(KEY_I);
        assertTrue(closed.get());
        assertEquals(List.of("✓ Installed 1 of 2 plugins. Failed: alpha. "
            + "Run /reload-plugins to activate successfully installed plugins."), recorded);
    }

    @Test
    void footerHints_followSelectionAndToggleAvailability() {
        PluginPanelServices services = singleMarketplaceServices();
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginDiscoverTab tab = openedTab(services, null);
        // Selected row = alpha (installed) → no toggle hint, no install hint.
        assertTrue(tabLines(tab).contains("type to search · Enter to details · Esc to back"));
        send(DOWN); // beta (uninstalled) → toggle hint appears
        assertTrue(tabLines(tab).contains(
            "type to search · Space to toggle · Enter to details · Esc to back"));
        send(SPACE); // selection made → bold install hint appears
        assertTrue(tabLines(tab).contains(
            "i to install · type to search · Space to toggle · Enter to details · Esc to back"));
    }

}

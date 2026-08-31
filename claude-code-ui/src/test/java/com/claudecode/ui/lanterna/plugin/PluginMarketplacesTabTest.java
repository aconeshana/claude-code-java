package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;


class PluginMarketplacesTabTest {

    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);

    @TempDir
    Path tmp;

    private final List<String> recorded = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginSettingsPanel panel;

    private PluginMarketplacesTab openedTab(PluginPanelServices services, String args) {
        panel = new PluginSettingsPanel(services);
        panel.show(PluginRoute.parse(args), (line, _) -> recorded.add(line),
            () -> closed.set(true));
        return panel.marketplacesTab();
    }

    private void send(KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    private void type(String text) {
        for (char c : text.toCharArray()) {
            send(new KeyStroke(c, false, false));
        }
    }

    private PluginPanelServices withMarketplace() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        return services;
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_showsAddMarketplaceRowFirstAndMarketplaceDetails() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("Manage marketplaces", lines.getFirst());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "+ Add Marketplace")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "• test-market")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "1 available")));
        assertTrue(lines.contains("Enter to select · u to update · r to remove · Esc to go back"));
    }

    @Test
    void enterOnAddRow_opensAddView() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(ENTER);
        assertEquals(PluginMarketplacesTab.Mode.ADD, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("Add Marketplace", lines.getFirst());
        assertTrue(lines.contains("Enter marketplace source:"));
        assertTrue(lines.contains(" · owner/repo (GitHub)"));
        assertTrue(lines.contains(" · git@github.com:owner/repo.git (SSH)"));
        assertTrue(lines.contains(" · https://example.com/marketplace.json"));
        assertTrue(lines.contains(" · ./path/to/marketplace"));
    }

    // ── add view ─────────────────────────────────────────────────────────────

    @Test
    void addEmptyInput_showsPleaseEnterError() {
        PluginMarketplacesTab tab = openedTab(PluginPanelTestHarness.services(tmp), "marketplace");
        send(ENTER); // open add
        send(ENTER); // submit empty
        assertEquals(PluginMarketplaceAddView.EMPTY_INPUT_ERROR, tab.addError());
    }

    @Test
    void addNonexistentLocalPath_showsParseError() {
        PluginMarketplacesTab tab = openedTab(PluginPanelTestHarness.services(tmp), "marketplace");
        send(ENTER);
        type("./does-not-exist");
        send(ENTER);
        assertTrue(Strings.CS.startsWith(tab.addError(), "Path does not exist: "));
        assertFalse(closed.get());
    }

    @Test
    void addValidDirectory_registersMarketplaceAndDivertsToDiscover() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path dir = tmp.resolve("new-market");
        Files.createDirectories(dir);
        // Prepare fixture files without registering (openAdd will do the add).
        PluginPanelServices scratch = PluginPanelTestHarness.services(tmp.resolve("scratch"));
        PluginPanelTestHarness.writeMarketplace(scratch, dir, "new-market",
            PluginPanelTestHarness.PluginSpec.of("gamma", "Gamma plugin", "0.1.0"));

        PluginMarketplacesTab tab = openedTab(services, "marketplace");
        send(ENTER); // add view
        panel.handleKey(new PasteKeyStroke(dir.toString()), new AtomicBoolean(true));
        assertEquals(dir.toString(), tab.addInput(), "PASTE must land in the add input");
        send(ENTER);
        assertTrue(services.plugins().marketplaces().containsKey("new-market"));
        assertEquals(PluginSettingsPanel.Tab.DISCOVER, panel.selectedTab(),
            "interactive add diverts to browse the new marketplace");
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, panel.discoverTab().mode());
    }

    @Test
    void cliModeAdd_closesWithSuccessMessage() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        Path dir = tmp.resolve("cli-market");
        Files.createDirectories(dir);
        PluginPanelServices scratch = PluginPanelTestHarness.services(tmp.resolve("scratch"));
        PluginPanelTestHarness.writeMarketplace(scratch, dir, "cli-market");

        openedTab(services, "marketplace add " + dir);
        assertTrue(closed.get());
        assertEquals(List.of("Successfully added marketplace: cli-market"), recorded);
        assertTrue(services.plugins().marketplaces().containsKey("cli-market"));
    }

    @Test
    void escInAddView_returnsToMarketplacesList() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace add");
        assertEquals(PluginMarketplacesTab.Mode.ADD, tab.mode());
        send(ESC);
        assertEquals(PluginMarketplacesTab.Mode.LIST, tab.mode());
        assertFalse(closed.get());
    }

    // ── pending update / remove ──────────────────────────────────────────────

    @Test
    void uKey_marksPendingUpdateAndShowsSummary() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(DOWN); // select test-market row
        send(new KeyStroke('u', false, false));
        assertTrue(tab.states().getFirst().pendingUpdate);
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "[UPDATE]")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Pending changes:")));
        assertTrue(lines.contains("• Update 1 marketplace"));
        assertTrue(lines.contains("Enter to apply changes · Esc to cancel"));
        // Esc clears pending marks instead of closing.
        send(ESC);
        assertFalse(tab.states().getFirst().pendingUpdate);
        assertFalse(closed.get());
    }

    @Test
    void applyPendingUpdate_refreshesAndClosesWithMessage() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(DOWN);
        send(new KeyStroke('u', false, false));
        send(ENTER); // apply
        assertTrue(closed.get());
        assertEquals(List.of("✓ Updated 1 marketplace"), recorded);
    }

    @Test
    void rKey_opensConfirmRemove_yRemoves() {
        PluginPanelServices services = withMarketplace();
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginMarketplacesTab tab = openedTab(services, "marketplace");
        send(DOWN);
        send(new KeyStroke('r', false, false));
        assertEquals(PluginMarketplacesTab.Mode.CONFIRM_REMOVE, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("Remove marketplace test-market?", lines.getFirst());
        assertTrue(lines.contains("This will also uninstall 1 plugin from this marketplace:"));
        assertTrue(lines.contains("  • alpha"));
        assertTrue(lines.contains("Press y to confirm or n to cancel"));
        send(new KeyStroke('y', false, false));
        assertTrue(closed.get());
        assertEquals(List.of("✓ Removed 1 marketplace"), recorded);
        assertFalse(services.plugins().marketplaces().containsKey("test-market"));
    }

    @Test
    void confirmRemove_nCancelsBackToList() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(DOWN);
        send(new KeyStroke('r', false, false));
        send(new KeyStroke('n', false, false));
        assertEquals(PluginMarketplacesTab.Mode.LIST, tab.mode());
        assertTrue(panel.isActive());
    }

    // ── details ──────────────────────────────────────────────────────────────

    @Test
    void detailsView_showsMenuOptions() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(DOWN);
        send(ENTER); // details
        assertEquals(PluginMarketplacesTab.Mode.DETAILS, tab.mode());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("test-market", lines.getFirst());
        assertTrue(lines.contains("1 available plugin"));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "❯ Browse plugins (1)")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Update marketplace")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Enable auto-update")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Remove marketplace")));
    }

    @Test
    void detailsToggleAutoUpdate_persistsAndShowsExplanatoryText() {
        PluginPanelServices services = withMarketplace();
        PluginMarketplacesTab tab = openedTab(services, "marketplace");
        send(DOWN);
        send(ENTER); // details
        send(DOWN);
        send(DOWN); // → Enable auto-update
        send(ENTER);
        assertTrue(tab.selectedMarketplace().autoUpdate);
        assertEquals(Boolean.TRUE,
            services.plugins().marketplaces().get("test-market").autoUpdate());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.contains("Auto-update enabled. Claude Code will automatically update "
            + "this marketplace and its installed plugins."));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "Disable auto-update")));
    }

    @Test
    void detailsBrowsePlugins_divertsToDiscoverTargetingMarketplace() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace");
        send(DOWN);
        send(ENTER); // details
        send(ENTER); // Browse plugins
        assertEquals(PluginSettingsPanel.Tab.DISCOVER, panel.selectedTab());
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, panel.discoverTab().mode());
    }

    @Test
    void updateRouteWithUnknownTarget_showsNotFoundError() {
        PluginMarketplacesTab tab = openedTab(withMarketplace(), "marketplace update ghost");
        assertEquals("Marketplace not found: ghost", tab.processError());
        assertFalse(closed.get());
    }

    @Test
    void removeRouteWithTarget_removesImmediately() {
        PluginPanelServices services = withMarketplace();
        openedTab(services, "marketplace remove test-market");
        assertTrue(closed.get());
        assertEquals(List.of("✓ Removed 1 marketplace"), recorded);
        assertFalse(services.plugins().marketplaces().containsKey("test-market"));
    }
}

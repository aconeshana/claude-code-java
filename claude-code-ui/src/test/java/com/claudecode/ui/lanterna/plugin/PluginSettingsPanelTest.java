package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Verifies {@link PluginSettingsPanel}'s route dispatch, tab switching, and close paths.
 */
class PluginSettingsPanelTest {

    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);

    @TempDir
    Path tmp;

    private final List<String> recorded = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginSettingsPanel open(PluginPanelServices services, String args) {
        PluginSettingsPanel panel = new PluginSettingsPanel(services);
        panel.show(PluginRoute.parse(args),
            (line, _) -> recorded.add(line),
            () -> closed.set(true));
        return panel;
    }

    private static void send(PluginSettingsPanel panel, KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    // ── route dispatch ───────────────────────────────────────────────────────

    @Test
    void menuRoute_opensDiscoverTab() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        assertTrue(panel.isActive());
        assertEquals(PluginSettingsPanel.Tab.DISCOVER, panel.selectedTab());
    }

    @Test
    void discoverListUsesReboundSelectAndPluginContexts() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"),
            PluginPanelTestHarness.PluginSpec.of("beta", "Beta plugin", "2.0.0"));
        PluginSettingsPanel panel = open(services, null);
        var store = createStore(tmp.resolve("discover-bindings.json"), """
            [
              {"context":"Select","bindings":{"ctrl+x":"select:next"}},
              {"context":"Plugin","bindings":{"ctrl+y":"plugin:toggle"}}
            ]
            """);
        try {
            panel.setKeybindingsStore(store);
            send(panel, new KeyStroke('x', true, false));
            send(panel, new KeyStroke('y', true, false));

            assertEquals(1, panel.discoverTab().selectedPluginIndex());
            assertEquals(Set.of("beta@test-market"),
                panel.discoverTab().selectedForInstall());
        } finally {
            store.dispose();
        }
    }

    @Test
    void discoverDetailsUsesReboundConfirmationCancelAndHonorsUnbind() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginSettingsPanel panel = open(services, null);
        send(panel, new KeyStroke(KeyType.ENTER));
        assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, panel.discoverTab().mode());
        var store = createStore(tmp.resolve("confirmation-bindings.json"), """
            [{"context":"Confirmation","bindings":{
              "ctrl+g":"confirm:no",
              "escape":null
            }}]
            """);
        try {
            panel.setKeybindingsStore(store);
            send(panel, new KeyStroke(KeyType.ESCAPE));
            assertEquals(PluginDiscoverTab.Mode.PLUGIN_DETAILS, panel.discoverTab().mode());

            send(panel, new KeyStroke('g', true, false));
            assertEquals(PluginDiscoverTab.Mode.PLUGIN_LIST, panel.discoverTab().mode());
        } finally {
            store.dispose();
        }
    }

    @Test
    void installedListUsesReboundPluginToggle() throws Exception {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        PluginPanelTestHarness.install(services, "alpha", "test-market");
        PluginSettingsPanel panel = open(services, "manage");
        var store = createStore(tmp.resolve("installed-bindings.json"), """
            [{"context":"Plugin","bindings":{"ctrl+y":"plugin:toggle"}}]
            """);
        try {
            panel.setKeybindingsStore(store);
            send(panel, new KeyStroke('y', true, false));

            assertEquals("will-disable",
                panel.installedTab().pendingToggles().get("alpha@test-market"));
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(
            Path file, String json) throws Exception {
        Files.writeString(file, json);
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    @Test
    void manageRoute_opensInstalledTab() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "manage");
        assertEquals(PluginSettingsPanel.Tab.INSTALLED, panel.selectedTab());
        assertEquals(PluginInstalledTab.Mode.LIST, panel.installedTab().mode());
    }

    @Test
    void marketplaceRoute_opensMarketplacesTab() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "marketplace");
        assertEquals(PluginSettingsPanel.Tab.MARKETPLACES, panel.selectedTab());
        assertEquals(PluginMarketplacesTab.Mode.LIST, panel.marketplacesTab().mode());
    }

    @Test
    void marketplaceAddRoute_opensAddInput() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "marketplace add");
        assertEquals(PluginSettingsPanel.Tab.MARKETPLACES, panel.selectedTab());
        assertEquals(PluginMarketplacesTab.Mode.ADD, panel.marketplacesTab().mode());
    }

    @Test
    void helpRoute_emitsVerbatimHelpTextAndClosesWithoutActivating() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "help");
        assertFalse(panel.isActive());
        assertTrue(closed.get());
        assertEquals(1, recorded.size());
        String help = recorded.getFirst();
        assertTrue(Strings.CS.startsWith(help, "Plugin Command Usage:"));
        assertTrue(Strings.CS.contains(help, " /plugin install <plugin>@<market> - Install plugin from marketplace"));
        assertTrue(Strings.CS.contains(help, " /plugins - Alias for /plugin"));
    }

    @Test
    void marketplaceListRoute_withNoMarketplaces_reportsAndCloses() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "marketplace list");
        assertTrue(closed.get());
        assertEquals(List.of("No marketplaces configured"), recorded);
        assertFalse(panel.isActive());
    }

    @Test
    void marketplaceListRoute_listsConfiguredNames() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp);
        PluginPanelTestHarness.writeMarketplace(services, tmp.resolve("m1"), "test-market",
            PluginPanelTestHarness.PluginSpec.of("alpha", "Alpha plugin", "1.0.0"));
        open(services, "marketplace list");
        assertTrue(closed.get());
        assertEquals(List.of("Configured marketplaces:\n  • test-market"), recorded);
    }

    @Test
    void validateRoute_withoutPath_showsPathInputView() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "validate");
        assertTrue(panel.validateVisible());
        assertEquals(PluginValidateView.Mode.INPUT, panel.validateView().mode());
        assertTrue(panel.contentPlainLines().stream()
            .anyMatch(l -> Strings.CS.contains(l, "Validate a plugin or marketplace manifest file or directory.")));
    }

    // ── tab switching ────────────────────────────────────────────────────────

    @Test
    void arrowKeys_cycleTabsWithWrap() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        send(panel, RIGHT);
        assertEquals(PluginSettingsPanel.Tab.INSTALLED, panel.selectedTab());
        send(panel, RIGHT);
        assertEquals(PluginSettingsPanel.Tab.MARKETPLACES, panel.selectedTab());
        send(panel, RIGHT);
        assertEquals(PluginSettingsPanel.Tab.ERRORS, panel.selectedTab());
        send(panel, RIGHT);
        assertEquals(PluginSettingsPanel.Tab.DISCOVER, panel.selectedTab(), "wraps around");
        send(panel, LEFT);
        assertEquals(PluginSettingsPanel.Tab.ERRORS, panel.selectedTab(), "wraps backwards");
    }

    @Test
    void tabSwitching_isSuppressedInsideTextInputViews() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "marketplace add");
        send(panel, LEFT);
        assertEquals(PluginSettingsPanel.Tab.MARKETPLACES, panel.selectedTab(),
            "←/→ must reach the add-marketplace input, not switch tabs");
        assertEquals(PluginMarketplacesTab.Mode.ADD, panel.marketplacesTab().mode());
    }

    @Test
    void errorsTabTitle_includesCount() {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp, List.of(
            new PluginError.GenericError("a@m", "a", "boom"),
            new PluginError.MarketplaceLoadFailed("m", "m", "offline")));
        PluginSettingsPanel panel = open(services, null);
        assertEquals("Errors (2)", panel.errorsTabTitle());
    }

    // ── closing ──────────────────────────────────────────────────────────────

    @Test
    void ctrlC_andCtrlD_closeFromAnywhere() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), "marketplace add");
        send(panel, new KeyStroke('c', true, false));
        assertTrue(closed.get());
        assertFalse(panel.isActive());

        closed.set(false);
        PluginSettingsPanel panel2 = open(PluginPanelTestHarness.services(tmp), null);
        send(panel2, new KeyStroke('d', true, false));
        assertTrue(closed.get());
    }

    @Test
    void escAtTabRoot_closesPanel() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        send(panel, ESC);
        assertTrue(closed.get());
        assertFalse(panel.isActive());
    }

    @Test
    void pasteKey_isConsumedNotDelivered() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        AtomicBoolean deliver = new AtomicBoolean(true);
        panel.handleKey(new PasteKeyStroke("leak"), deliver);
        assertFalse(deliver.get(), "PASTE must never leak to the main input");
        assertTrue(panel.isActive());
    }

    @Test
    void finish_recordsResultThenClosesExactlyOnce() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        panel.finish("✓ done");
        assertTrue(closed.get());
        assertEquals(List.of("✓ done"), recorded);
        closed.set(false);
        panel.closePanel();
        assertFalse(closed.get(), "onClose must fire only once");
    }

    @Test
    void recordUsesProvidedColor() {
        PluginSettingsPanel panel = open(PluginPanelTestHarness.services(tmp), null);
        List<TextColor> colors = new ArrayList<>();
        panel.show(PluginRoute.menu(), (_, color) -> colors.add(color), () -> { });
        panel.record("x", LanternaTheme.toolWarning());
        assertEquals(List.of(LanternaTheme.toolWarning()), colors);
    }
}

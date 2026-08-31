package com.claudecode.ui.lanterna.plugin;

import org.apache.commons.lang3.Strings;

import com.claudecode.core.serialization.JsonUtils;
import com.claudecode.services.plugins.marketplace.PluginError;
import com.claudecode.runtime.plugins.PluginMarketplacePort.ErrorView;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.claudecode.ui.lanterna.components.StyledText;

/**
 * Verifies {@link PluginErrorsTab}.
 */
class PluginErrorsTabTest {

    @TempDir
    Path tmp;

    private PluginSettingsPanel panel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private PluginErrorsTab openedErrorsTab(List<PluginError> errors) {
        PluginPanelServices services = PluginPanelTestHarness.services(tmp, errors);
        panel = new PluginSettingsPanel(services);
        panel.show(PluginRoute.menu(), (_, _) -> { }, () -> closed.set(true));
        // Navigate to the Errors tab (Discover → ← wraps to Errors).
        panel.handleKey(new KeyStroke(KeyType.ARROW_LEFT), new AtomicBoolean(true));
        return panel.errorsTab();
    }

    @Test
    void emptyErrors_showNoPluginErrorsCopy() {
        PluginErrorsTab tab = openedErrorsTab(List.of());
        List<String> lines = StyledText.plain(tab.buildLines());
        assertEquals("No plugin errors", lines.getFirst());
        assertTrue(lines.contains("Esc to go back"));
    }

    @Test
    void rows_showMessageAndGuidance() {
        PluginErrorsTab tab = openedErrorsTab(List.of(
            new PluginError.MarketplaceNotFound("bad-market", "bad-market", List.of()),
            new PluginError.GitTimeout("p@m", "p", "https://example.com/x.git", "clone")));
        List<String> lines = StyledText.plain(tab.buildLines());
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "❯ bad-market")), "selected row marker");
        assertTrue(lines.stream().anyMatch(
            l -> Strings.CS.contains(l, "Add the marketplace first using /plugin marketplace add")));
        assertTrue(lines.stream().anyMatch(l -> Strings.CS.contains(l, "✖ p@m")));
        assertTrue(lines.stream().anyMatch(
            l -> Strings.CS.contains(l, "Check your internet connection and try again")));
    }

    @Test
    void navigation_movesSelection_andEscCloses() {
        PluginErrorsTab tab = openedErrorsTab(List.of(
            new PluginError.GenericError("a@m", "a", "boom"),
            new PluginError.GenericError("b@m", "b", "bang")));
        assertEquals(0, tab.selectedIndex());
        panel.handleKey(new KeyStroke(KeyType.ARROW_DOWN), new AtomicBoolean(true));
        assertEquals(1, tab.selectedIndex());
        panel.handleKey(new KeyStroke('k', false, false), new AtomicBoolean(true));
        assertEquals(0, tab.selectedIndex());
        panel.handleKey(new KeyStroke(KeyType.ESCAPE), new AtomicBoolean(true));
        assertTrue(closed.get());
    }

    @Test
    void pluginErrorEnterRoutesToInstalledUninstallFlow() {
        PluginErrorsTab tab = openedErrorsTab(List.of(
            new PluginError.GenericError("missing@test-market", "missing", "boom")));
        panel.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));
        assertEquals(PluginSettingsPanel.Tab.INSTALLED, panel.selectedTab());
        assertTrue(StyledText.plain(tab.buildLines()).stream().anyMatch(
            line -> Strings.CS.contains(line, "Enter to resolve")));
    }

    @Test
    void editableMarketplaceErrorEnterRemovesOnlyEditableReferences() throws Exception {
        Files.writeString(tmp.resolve("user-settings.json"), """
            {"extraKnownMarketplaces":{"bad-market":{"source":"x"}},
             "enabledPlugins":{"p@bad-market":true,"q@other":true}}
            """);
        PluginErrorsTab tab = openedErrorsTab(List.of(
            new PluginError.MarketplaceNotFound("bad-market", "bad-market", List.of())));

        panel.handleKey(new KeyStroke(KeyType.ENTER), new AtomicBoolean(true));

        var settings = JsonUtils.getMapper()
            .readTree(tmp.resolve("user-settings.json").toFile());
        assertFalse(settings.get("extraKnownMarketplaces").has("bad-market"));
        assertFalse(settings.get("enabledPlugins").has("p@bad-market"));
        assertTrue(settings.get("enabledPlugins").has("q@other"));
        assertTrue(tab.errors().isEmpty(), "resolved row is removed from the current view");
    }

    @Test
    void policyOnlyMarketplaceErrorHasNoResolveAction() throws Exception {
        Files.writeString(tmp.resolve("policy-settings.json"), """
            {"extraKnownMarketplaces":{"managed-market":{"source":"x"}}}
            """);
        PluginErrorsTab tab = openedErrorsTab(List.of(
            new PluginError.MarketplaceNotFound(
                "managed-market", "managed-market", List.of())));

        List<String> lines = StyledText.plain(tab.buildLines());

        assertTrue(lines.stream().anyMatch(line -> Strings.CS.contains(line, 
            "Managed by your organization — contact your admin")));
        assertFalse(lines.stream().anyMatch(line -> Strings.CS.contains(line, "Enter to resolve")));
    }

    @Test
    void guidance_coversRepresentativeErrorArms() {
        assertEquals("Configure SSH keys or use HTTPS URL instead",
            PluginErrorsTab.guidanceFor(
                new ErrorView("s", "auth failed",
                    "Configure SSH keys or use HTTPS URL instead")));
        assertEquals("Available marketplaces: m1, m2",
            PluginErrorsTab.guidanceFor(
                new ErrorView("s", "missing marketplace", "Available marketplaces: m1, m2")));
        assertEquals("Enable \"dep\" or uninstall \"p\"",
            PluginErrorsTab.guidanceFor(
                new ErrorView("s", "dependency disabled",
                    "Enable \"dep\" or uninstall \"p\"")));
        assertEquals("Run /plugins to refresh the plugin cache",
            PluginErrorsTab.guidanceFor(
                new ErrorView("s", "cache miss",
                    "Run /plugins to refresh the plugin cache")));
        assertNull(PluginErrorsTab.guidanceFor(
            new ErrorView("s", "boom", null)));
        assertNull(PluginErrorsTab.guidanceFor(
            new ErrorView("s", "reason", null)));
    }
}

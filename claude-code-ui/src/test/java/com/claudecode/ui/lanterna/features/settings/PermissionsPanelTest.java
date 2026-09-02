package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.permissions.PermissionBehavior;
import com.claudecode.permissions.PermissionGate;
import com.claudecode.permissions.PermissionRule;
import com.claudecode.permissions.RuleSource;
import com.claudecode.permissions.ToolPermissionContext;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.nio.file.Files;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import com.claudecode.ui.lanterna.dialog.AddDirDialog;


class PermissionsPanelTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static void send(PermissionsPanel p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    private PermissionGate gateWithSampleRules() {
        return new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .rules(List.of(
                PermissionRule.of("Bash", PermissionBehavior.ALLOW, RuleSource.USER_SETTINGS),
                PermissionRule.withPattern("Bash", PermissionBehavior.DENY, RuleSource.LOCAL_SETTINGS, "rm *")))
            .build());
    }

    private PermissionsPanel opened(PermissionsPanel.Tab tab, PermissionGate gate, Runnable onClose) {
        PermissionsPanel p = new PermissionsPanel();
        p.show(tab, () -> gate, () -> ".", path -> new AddDirDialog.ValidationOutcome(path, null),
            (_, _) -> {}, (_, _) -> {}, onClose);
        return p;
    }

    // ── initial state ────────────────────────────────────────────────────────

    @Test
    void show_startsHeaderFocusedOnRequestedTab() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        assertTrue(p.headerFocused());
        assertEquals(PermissionsPanel.Tab.ALLOW, p.selectedTab());
        assertTrue(p.isActive());
    }

    @Test
    void show_loadsMatchingRulesIntoEachTab() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        assertEquals(List.of("Bash"), p.allowTab().filteredRuleStrings());
        // Deny/Ask are lazily reloaded only when they become the selected tab
// (matches SettingsTabContainer's statusPane/usagePane pattern) — switch
        // to each before checking its contents.
        send(p, RIGHT); // -> ASK
        assertEquals(List.of(), p.askTab().filteredRuleStrings());
        send(p, RIGHT); // -> DENY
        assertEquals(List.of("Bash(rm *)"), p.denyTab().filteredRuleStrings());
    }

    @Test
    void released197AllowChromeAt80Columns() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW,
            gateWithRules(), () -> {});

        List<String> lines = renderedLines(p, 80);

        assertEquals(11, p.calculatePreferredSize().getRows());
        assertEquals("─".repeat(80), lines.get(0));
        assertEquals("  Permissions  Recently denied   Allow   Ask   Deny   Workspace",
            lines.get(1).stripTrailing());
        assertEquals("  Claude Code won't ask before using allowed tools.",
            lines.get(3).stripTrailing());
        assertEquals("  ╭" + "─".repeat(47) + "╮", lines.get(4).stripTrailing());
        assertEquals("  │ ⌕ Search…                                     │",
            lines.get(5).stripTrailing());
        assertEquals("  ╰" + "─".repeat(47) + "╯", lines.get(6).stripTrailing());
        assertEquals("    1. Add a new rule…", lines.get(8).stripTrailing());
        assertEquals("  ←/→ to switch · ↓ to select · Esc to cancel",
            lines.get(10).stripTrailing());
    }

    @Test
    void released197RecentlyDeniedEmptyStateAt80Columns() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW,
            gateWithRules(), () -> {});
        send(p, LEFT);

        List<String> lines = renderedLines(p, 80);

        assertEquals(PermissionsPanel.Tab.RECENTLY_DENIED, p.selectedTab());
        assertEquals(7, p.calculatePreferredSize().getRows());
        assertEquals("  No recent denials. Commands denied by the auto mode classifier will appear",
            lines.get(3).stripTrailing());
        assertEquals("  here.", lines.get(4).stripTrailing());
        assertEquals("  ←/→ to switch · ↓ to select · Esc to cancel",
            lines.get(6).stripTrailing());
    }

    @Test
    void released197AllowListAndSearchFocusAt80Columns() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW,
            gateWithRules(), () -> {});
        send(p, DOWN);

        List<String> listLines = renderedLines(p, 80);
        assertEquals("  > 1. Add a new rule…", listLines.get(8).stripTrailing());
        assertEquals(
            "  ↑/↓ to navigate · Enter to select · ←/→ to switch · Esc to cancel",
            listLines.get(10).stripTrailing());

        send(p, new KeyStroke('b', false, false));
        List<String> searchLines = renderedLines(p, 80);
        assertEquals("  │ ⌕ b", searchLines.get(5).substring(0, 7));
        assertEquals(
            "  Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear",
            searchLines.get(10).stripTrailing());
    }

    @Test
    void released197WorkspaceChromeAt80Columns() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW,
            gateWithRules(), () -> {});
        send(p, RIGHT);
        send(p, RIGHT);
        send(p, RIGHT);

        List<String> lines = renderedLines(p, 80);

        assertEquals(PermissionsPanel.Tab.WORKSPACE, p.selectedTab());
        assertEquals(12, p.calculatePreferredSize().getRows());
        assertEquals(
            "  Claude Code can read files in the workspace, and make edits when auto-accept",
            lines.get(3).stripTrailing());
        assertEquals("  edits is on.", lines.get(4).stripTrailing());
        assertEquals("    -  ." + " ".repeat(43) + "(Original working",
            lines.get(6).stripTrailing());
        assertEquals(" ".repeat(51) + "directory)", lines.get(7).stripTrailing());
        assertEquals("    1. Add directory…", lines.get(8).stripTrailing());
        assertEquals("  ←/→ to switch · ↓ to select · Esc to cancel",
            lines.get(11).stripTrailing());
    }

    // ── tab switching ────────────────────────────────────────────────────────

    @Test
    void arrowRight_cyclesTabsForwardWithWrap() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.ASK, p.selectedTab());
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.DENY, p.selectedTab());
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.WORKSPACE, p.selectedTab());
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.RECENTLY_DENIED, p.selectedTab());
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.ALLOW, p.selectedTab(), "wraps back around");
    }

    @Test
    void arrowLeft_cyclesTabsBackwardWithWrap() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        send(p, LEFT);
        assertEquals(PermissionsPanel.Tab.RECENTLY_DENIED, p.selectedTab());
    }

    @Test
    void switchingTabs_keepsHeaderFocused() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        send(p, RIGHT);
        assertTrue(p.headerFocused());
    }

    // ── header <-> content focus handoff ─────────────────────────────────────

    @Test
    void downFromHeader_movesFocusIntoContent() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        send(p, DOWN);
        assertFalse(p.headerFocused());
    }

    @Test
    void searchArrowUpAtTop_handsFocusBackToHeader() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
        send(p, DOWN); // content-focused, LIST mode
        send(p, UP);   // LIST index 0 -> SEARCH
        assertEquals(PermissionRulesTab.Mode.SEARCH, p.allowTab().mode());
        send(p, UP);   // SEARCH -> onExitUp -> focusHeader
        assertTrue(p.headerFocused());
    }

    @Test
    void keysWhileContentFocused_forwardToSelectedTab() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.DENY, gateWithSampleRules(), () -> {});
        send(p, DOWN); // content-focused on Deny tab
        send(p, new KeyStroke('r', false, false));
        assertEquals(PermissionRulesTab.Mode.SEARCH, p.denyTab().mode());
    }

    @Test
    void listFocusCanSwitchTabsButSearchCursorKeysStayInTheSearchField() {
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW,
            gateWithSampleRules(), () -> {});
        send(p, DOWN);
        send(p, RIGHT);
        assertEquals(PermissionsPanel.Tab.ASK, p.selectedTab());

        send(p, DOWN);
        send(p, new KeyStroke('b', false, false));
        send(p, LEFT);
        assertEquals(PermissionsPanel.Tab.ASK, p.selectedTab());
        assertEquals(PermissionRulesTab.Mode.SEARCH, p.askTab().mode());
    }

    // ── closing ──────────────────────────────────────────────────────────────

    @Test
    void escWhileHeaderFocused_closesPanel() {
        AtomicBoolean closed = new AtomicBoolean(false);
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> closed.set(true));
        send(p, ESC);
        assertTrue(closed.get());
        assertFalse(p.isActive());
    }

    @Test
    void escFromContentFocusedListMode_alsoClosesPanel() {
        AtomicBoolean closed = new AtomicBoolean(false);
        PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> closed.set(true));
        send(p, DOWN); // content-focused
        send(p, ESC);
        assertTrue(closed.get(), "ConfigPanel convention: content-focused Esc closes the whole panel");
        assertFalse(p.isActive());
    }

    @Test
    void headerUsesRuntimeTabsAndSettingsBindings(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Tabs","bindings":{"x":"tabs:next","right":null}},
              {"context":"Settings","bindings":{"z":"confirm:no","escape":null}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            PermissionsPanel p = opened(PermissionsPanel.Tab.ALLOW, gateWithSampleRules(), () -> {});
            p.setKeybindingsStore(store);

            send(p, RIGHT);
            assertEquals(PermissionsPanel.Tab.ALLOW, p.selectedTab());
            send(p, new KeyStroke('x', false, false));
            assertEquals(PermissionsPanel.Tab.ASK, p.selectedTab());

            send(p, ESC);
            assertTrue(p.isActive());
            send(p, new KeyStroke('z', false, false));
            assertFalse(p.isActive());
        } finally {
            store.dispose();
        }
    }

    private static UserKeybindingsStore createStore(Path file) throws Exception {
        Method create = UserKeybindingsStore.class
            .getDeclaredMethod("create", Path.class, boolean.class);
        create.setAccessible(true);
        return (UserKeybindingsStore) create.invoke(null, file, true);
    }

    private PermissionGate gateWithRules(PermissionRule... rules) {
        return new PermissionGate(ToolPermissionContext.builder()
            .workingDirectory(Path.of("."))
            .rules(List.of(rules))
            .build());
    }

    private static List<String> renderedLines(PermissionsPanel panel, int columns) {
        TerminalSize size = new TerminalSize(
            columns, panel.calculatePreferredSize().getRows());
        panel.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        panel.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        List<String> lines = new ArrayList<>(size.getRows());
        for (int row = 0; row < size.getRows(); row++) {
            StringBuilder line = new StringBuilder(columns);
            for (int column = 0; column < columns; column++) {
                line.append(image.getCharacterAt(column, row).getCharacterString());
            }
            lines.add(line.toString());
        }
        return lines;
    }
}

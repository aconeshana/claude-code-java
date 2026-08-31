package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.commands.StatusProperty;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link SettingsTabContainer}'s tab-switching / header-content focus handoff state
 * machine.
 */
class SettingsTabContainerTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);

    private static void send(SettingsTabContainer c, KeyStroke k) {
        c.handleKey(k, new AtomicBoolean(true));
    }

    private static Map<String, String> defaultConfigValues() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("autoCompactEnabled", "true");
        m.put("switchModelsOnFlag", "true");
        m.put("spinnerTipsEnabled", "true");
        m.put("prefersReducedMotion", "false");
        m.put("thinkingEnabled", "true");
        m.put("fileCheckpointingEnabled", "true");
        m.put("verbose", "false");
        m.put("terminalProgressBarEnabled", "true");
        m.put("showTurnDuration", "true");
        m.put("defaultPermissionMode", "default");
        m.put("copyFullResponse", "false");
        m.put("copyOnSelect", "true");
        m.put("theme", "dark");
        m.put("outputStyle", "default");
        m.put("editorMode", "normal");
        m.put("model", "claude-sonnet-4-6");
        return m;
    }

    private static List<StatusProperty> sampleStatusProperties() {
        return List.of(new StatusProperty("Model", "claude-sonnet-4-6"),
            new StatusProperty("Messages in session", "3"));
    }

    private SettingsTabContainer opened(SettingsTabContainer.Tab tab,
                                         Consumer<Map<String, String>> onResult) {
        SettingsTabContainer c = new SettingsTabContainer(40);
        c.show(tab, defaultConfigValues(), false, SettingsTabContainerTest::sampleStatusProperties,
            _ -> {}, onResult);
        return c;
    }

    // ── initial focus / visibility ──────────────────────────────────────────

    @Test
    void openOnConfig_startsContentFocused() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG, _ -> {});
        assertFalse(c.headerFocused(), "TS initialHeaderFocused = defaultTab !== 'Config'");
        assertEquals(SettingsTabContainer.Tab.CONFIG, c.selectedTab());
        assertEquals(ConfigPanel.Mode.SEARCH, c.configPanel().mode(), "Config's own default open state");
    }

    @Test
    void configViewport_tracksLiveTerminalRows() {
        AtomicInteger terminalRows = new AtomicInteger(20);
        SettingsTabContainer c = new SettingsTabContainer(
            terminalRows::get, OutputStyleCatalog.builtIns());
        c.show(SettingsTabContainer.Tab.CONFIG, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});

        assertEquals(13, c.configPanel().getPreferredSize().getRows());
        terminalRows.set(40);
        assertEquals(27, c.configPanel().getPreferredSize().getRows());
    }

    @Test
    void released197ConfigChromeAt80Columns() {
        SettingsTabContainer c = new SettingsTabContainer(
            () -> 24, OutputStyleCatalog.builtIns());
        c.show(SettingsTabContainer.Tab.CONFIG, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});

        List<String> lines = renderedLines(c, 80);

        assertEquals(20, c.calculatePreferredSize().getRows());
        assertEquals("─".repeat(80), lines.get(0));
        assertEquals("  Settings  Status   Config   Usage   Stats",
            lines.get(1).stripTrailing());
        assertEquals("  ╭" + "─".repeat(74) + "╮", lines.get(4).stripTrailing());
        assertEquals("  │ ⌕ Search settings…", lines.get(5).substring(0, 22));
        assertEquals("  ╰" + "─".repeat(74) + "╯", lines.get(6).stripTrailing());
        assertEquals("    Auto-compact                               true",
            lines.get(8).stripTrailing());
        assertEquals("    Switch models when a message is flagged    true",
            lines.get(9).stripTrailing());
        assertEquals("  Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear",
            lines.get(19).stripTrailing());
    }

    @Test
    void released197FilteredConfigShrinksFooterUnderTheMatch() {
        SettingsTabContainer c = new SettingsTabContainer(
            () -> 24, OutputStyleCatalog.builtIns());
        Map<String, String> values = defaultConfigValues();
        values.put("language", "Chinese");
        c.show(SettingsTabContainer.Tab.CONFIG, values, false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        c.handlePlainText("language", deliver);
        List<String> lines = renderedLines(c, 80);

        assertFalse(deliver.get());
        assertEquals(20, c.calculatePreferredSize().getRows());
        assertEquals("    Language                                   Chinese",
            lines.get(8).stripTrailing());
        assertEquals("  Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear",
            lines.get(10).stripTrailing());
    }

    @Test
    void released197LanguageEditorKeepsTheOverlayTopAnchored() {
        SettingsTabContainer c = new SettingsTabContainer(
            () -> 24, OutputStyleCatalog.builtIns());
        Map<String, String> values = defaultConfigValues();
        values.put("language", "Chinese");
        c.show(SettingsTabContainer.Tab.CONFIG, values, false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        c.handlePlainText("language", new AtomicBoolean(true));
        send(c, DOWN);

        send(c, SPACE);
        List<String> lines = renderedLines(c, 80);

        assertEquals(20, c.calculatePreferredSize().getRows());
        assertEquals("─".repeat(80), lines.get(0));
        assertEquals("  Enter your preferred response and voice language:",
            lines.get(1).stripTrailing());
        assertEquals("  > Chinese", lines.get(3).stripTrailing());
        assertEquals("  Leave empty for default (English)", lines.get(5).stripTrailing());
        assertEquals("  Enter to confirm · Esc to cancel", lines.get(7).stripTrailing());
        assertFalse(lines.stream().anyMatch(line -> line.contains("Settings  Status")));
    }

    @Test
    void languageEditorTransitionClearsThePreviousListFrame() {
        SettingsTabContainer c = new SettingsTabContainer(
            () -> 24, OutputStyleCatalog.builtIns());
        Map<String, String> values = defaultConfigValues();
        values.put("language", "Chinese");
        c.show(SettingsTabContainer.Tab.CONFIG, values, false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        TerminalSize size = new TerminalSize(80, c.calculatePreferredSize().getRows());
        c.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        var graphics = TextGUIGraphicsBridge.wrap(null, image.newTextGraphics());
        c.draw(graphics);
        c.handlePlainText("language", new AtomicBoolean(true));
        send(c, DOWN);

        send(c, SPACE);
        c.draw(graphics);
        List<String> lines = imageLines(image, 80, size.getRows());

        assertTrue(lines.stream().noneMatch(line -> line.contains("╭") || line.contains("╯")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("Language")
            && !line.contains("preferred response")));
    }

    @Test
    void openOnStatus_startsHeaderFocused() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        assertTrue(c.headerFocused());
        assertEquals(SettingsTabContainer.Tab.STATUS, c.selectedTab());
        assertTrue(c.statusPane().isShowing());
        assertFalse(c.usagePane().isShowing());
    }

    @Test
    void openOnUsage_startsHeaderFocused() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.USAGE, _ -> {});
        assertTrue(c.headerFocused());
        assertEquals(SettingsTabContainer.Tab.USAGE, c.selectedTab());
        assertTrue(c.usagePane().isShowing());
        assertFalse(c.statusPane().isShowing());
    }

    // ── tab switching (header-focused) ──────────────────────────────────────

    @Test
    void arrowRight_cyclesTabsForwardWithWrap() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        send(c, RIGHT);
        assertEquals(SettingsTabContainer.Tab.CONFIG, c.selectedTab());
        send(c, RIGHT);
        assertEquals(SettingsTabContainer.Tab.USAGE, c.selectedTab());
        send(c, RIGHT);
        assertEquals(SettingsTabContainer.Tab.STATUS, c.selectedTab(), "wraps back around");
        assertTrue(c.headerFocused(), "handleTabChange keeps header focused");
    }

    @Test
    void arrowLeft_cyclesTabsBackwardWithWrap() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        send(c, LEFT);
        assertEquals(SettingsTabContainer.Tab.USAGE, c.selectedTab(), "wraps to the last tab");
    }

    @Test
    void statsHeaderEntryOpensTheRealStatsSurfaceWithoutSavingConfig() {
        AtomicBoolean statsOpened = new AtomicBoolean();
        AtomicInteger resultCalls = new AtomicInteger();
        SettingsTabContainer c = opened(
            SettingsTabContainer.Tab.USAGE, _ -> resultCalls.incrementAndGet());
        c.setStatsTabRequest(() -> statsOpened.set(true));

        send(c, RIGHT);

        assertTrue(statsOpened.get());
        assertFalse(c.isActive());
        assertEquals(0, resultCalls.get());
    }

    @Test
    void switchingToConfigTab_doesNotAutoFocusContent() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        send(c, RIGHT); // -> CONFIG
        assertEquals(SettingsTabContainer.Tab.CONFIG, c.selectedTab());
        assertTrue(c.headerFocused(), "TS handleTabChange always leaves header focused after a switch");
    }

    @Test
    void windowsOpenAndTabSwitchRequestACompleteRefresh() {
        AtomicInteger refreshes = new AtomicInteger();
        SettingsTabContainer c = new SettingsTabContainer(
            40, true, refreshes::incrementAndGet);

        c.show(SettingsTabContainer.Tab.STATUS, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        assertEquals(1, refreshes.get());

        send(c, RIGHT);
        assertEquals(2, refreshes.get());
    }

    @Test
    void windowsConfigSearchFocusHandoffRequestsACompleteRefresh() {
        AtomicInteger refreshes = new AtomicInteger();
        SettingsTabContainer c = new SettingsTabContainer(
            40, true, refreshes::incrementAndGet);

        c.show(SettingsTabContainer.Tab.CONFIG, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        assertEquals(1, refreshes.get());

        send(c, UP);

        assertTrue(c.headerFocused());
        assertEquals(2, refreshes.get());
    }

    @Test
    void windowsTabRoundTripRedrawsTheCompleteConfigFrameOnTheSameBuffer() {
        SettingsTabContainer c = new SettingsTabContainer(24, true, () -> {});
        c.show(SettingsTabContainer.Tab.CONFIG, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
        TerminalSize size = new TerminalSize(80, c.calculatePreferredSize().getRows());
        c.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        var graphics = TextGUIGraphicsBridge.wrap(null, image.newTextGraphics());
        c.draw(graphics);

        send(c, UP);
        send(c, LEFT);
        graphics.fill(' ');
        c.draw(graphics);
        List<String> statusLines = imageLines(image, 80, size.getRows());
        assertEquals("  Settings  Status   Config   Usage   Stats",
            statusLines.get(1).stripTrailing());
        assertEquals("─".repeat(80), statusLines.get(4));
        assertEquals("  Model:                  claude-sonnet-4-6",
            statusLines.get(5).stripTrailing());
        send(c, RIGHT);
        graphics.fill(' ');
        c.draw(graphics);

        List<String> lines = imageLines(image, 80, size.getRows());
        assertEquals("─".repeat(80), lines.get(0));
        assertEquals("  Settings  Status   Config   Usage   Stats",
            lines.get(1).stripTrailing());
        assertEquals("  ╭" + "─".repeat(74) + "╮", lines.get(4).stripTrailing());
        assertEquals("  ╰" + "─".repeat(74) + "╯", lines.get(6).stripTrailing());
        assertEquals("    Auto-compact                               true",
            lines.get(8).stripTrailing());
        assertEquals("  Type to filter · Enter/↓ to select · ↑ to tabs · Esc to clear",
            lines.get(19).stripTrailing());
    }

    // ── header <-> content focus handoff (Config tab only) ──────────────────

    @Test
    void downOnConfigTab_movesFocusIntoContent() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        send(c, RIGHT); // -> CONFIG, header focused
        send(c, DOWN);
        assertFalse(c.headerFocused());
    }

    @Test
    void downOnStatusOrUsageTab_isNoOp() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, _ -> {});
        send(c, DOWN);
        assertTrue(c.headerFocused(), "Status/Usage never opt in to content focus");
    }

    @Test
    void configSearchArrowUp_handsFocusBackToHeader() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG, _ -> {});
        assertFalse(c.headerFocused());
        send(c, UP); // ConfigPanel is in default SEARCH mode -> onExitUp -> focusHeader
        assertTrue(c.headerFocused());
    }

    @Test
    void keysWhileContentFocused_forwardToConfigPanel() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG, _ -> {});
        send(c, ENTER);  // exit search into Config's own list
        send(c, SPACE);  // toggle autoCompactEnabled (index 0)
        assertEquals("false", c.configPanel().pendingSnapshot().get("autoCompactEnabled"));
    }

    @Test
    void released197ConfigChangesApplyImmediatelyAndEscClosesWithTheDiff() {
        List<String> applied = new java.util.ArrayList<>();
        AtomicReference<Map<String, String>> closed = new AtomicReference<>();
        SettingsTabContainer c = new SettingsTabContainer(40);
        c.show(SettingsTabContainer.Tab.CONFIG, defaultConfigValues(), false,
            SettingsTabContainerTest::sampleStatusProperties, _ -> {},
            (key, value) -> applied.add(key + "=" + value), closed::set);
        send(c, ENTER);

        send(c, SPACE);

        assertEquals(List.of("autoCompactEnabled=false"), applied);
        assertTrue(c.isActive());
        send(c, ESC);
        assertEquals(Map.of("autoCompactEnabled", "false"), closed.get());
        assertFalse(c.isActive());
    }

    @Test
    void released197LegacyEnterCloseBindingStillChangesTheSelectedConfigRow(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Settings","bindings":{"enter":"settings:close"}}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            SettingsTabContainer c = new SettingsTabContainer(24);
            Map<String, String> values = defaultConfigValues();
            values.put("language", "Chinese");
            c.setKeybindingsStore(store);
            c.show(SettingsTabContainer.Tab.CONFIG, values, false,
                SettingsTabContainerTest::sampleStatusProperties, _ -> {}, _ -> {});
            c.handlePlainText("language", new AtomicBoolean(true));
            send(c, DOWN);

            send(c, ENTER);

            assertTrue(c.isActive());
            assertTrue(c.configPanel().stringEditorActive());
        } finally {
            store.dispose();
        }
    }

    // ── tab switch preserves Config's buffered edits ────────────────────────

    @Test
    void switchingAwayAndBack_preservesConfigPendingEdits() {
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG, _ -> {});
        send(c, ENTER);
        send(c, SPACE); // autoCompactEnabled -> false (pending)
        assertEquals("false", c.configPanel().pendingSnapshot().get("autoCompactEnabled"));

        send(c, UP);     // LIST (index 0) -> SEARCH
        send(c, UP);     // SEARCH -> onExitUp -> focusHeader
        assertTrue(c.headerFocused());
        send(c, RIGHT);  // -> USAGE
        assertEquals(SettingsTabContainer.Tab.USAGE, c.selectedTab());
        assertTrue(c.configPanel().isActive(), "ConfigPanel's edit session must not end on tab switch");

        send(c, LEFT);   // back to CONFIG
        assertEquals(SettingsTabContainer.Tab.CONFIG, c.selectedTab());
        assertEquals("false", c.configPanel().pendingSnapshot().get("autoCompactEnabled"),
            "pending edits must survive a tab round-trip (TS keeps all tabs mounted)");
    }

    // ── closing ──────────────────────────────────────────────────────────────

    @Test
    void escWhileHeaderFocused_closesWithNullPending() {
        AtomicReference<Map<String, String>> gotPending = new AtomicReference<>();
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, gotPending::set);
        send(c, ESC);
        assertNull(gotPending.get());
        assertFalse(c.isActive());
    }

    @Test
    void enterOnConfigTabChangesValueWithoutClosing() {
        AtomicReference<Map<String, String>> gotPending = new AtomicReference<>();
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG, gotPending::set);
        send(c, ENTER);  // exit search into list
        send(c, ENTER);  // autoCompactEnabled -> false
        assertNull(gotPending.get());
        assertEquals("false", c.configPanel().pendingSnapshot().get("autoCompactEnabled"));
        assertTrue(c.isActive());
    }

    @Test
    void escOnConfigTabContentFocused_closesWithPendingChanges() {
        AtomicReference<Map<String, String>> pending = new AtomicReference<>();
        SettingsTabContainer c = opened(SettingsTabContainer.Tab.CONFIG,
            pending::set);
        send(c, ENTER);  // exit search into list (content-focused the whole time)
        send(c, SPACE);
        send(c, ESC);
        assertEquals(Map.of("autoCompactEnabled", "false"), pending.get());
        assertFalse(c.isActive());
    }

    @Test
    void tabsAndSettingsContextsSupportRebindingAndNullUnbinding(
            @TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("keybindings.json");
        Files.writeString(file, """
            [
              {"context":"Tabs","bindings":{
                "x":"tabs:next",
                "right":null
              }},
              {"context":"Settings","bindings":{
                "g":"select:next",
                "z":"select:accept",
                "q":"settings:close",
                "space":null,
                "enter":null
              }}
            ]
            """);
        UserKeybindingsStore store = createStore(file);
        try {
            AtomicReference<Map<String, String>> pending = new AtomicReference<>();
            SettingsTabContainer c = opened(SettingsTabContainer.Tab.STATUS, pending::set);
            c.setKeybindingsStore(store);

            send(c, RIGHT);
            assertEquals(SettingsTabContainer.Tab.STATUS, c.selectedTab());
            send(c, new KeyStroke('x', false, false));
            assertEquals(SettingsTabContainer.Tab.CONFIG, c.selectedTab());
            send(c, DOWN);   // header -> Config search
            send(c, ENTER);  // raw search-mode Enter -> list (Settings inactive here)

            send(c, SPACE);
            assertTrue(c.configPanel().pendingSnapshot().isEmpty());
            send(c, new KeyStroke('g', false, false));
            send(c, new KeyStroke('g', false, false));   // past switchModelsOnFlag
            send(c, new KeyStroke('z', false, false));
            assertEquals("false",
                c.configPanel().pendingSnapshot().get("spinnerTipsEnabled"));

            send(c, ENTER);
            assertTrue(c.isActive(), "null-unbound settings:close must be consumed");
            send(c, new KeyStroke('q', false, false));
            assertFalse(c.isActive());
            assertEquals("false", pending.get().get("spinnerTipsEnabled"));
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

    private static List<String> renderedLines(SettingsTabContainer container, int columns) {
        TerminalSize size = new TerminalSize(
            columns, container.calculatePreferredSize().getRows());
        container.setSize(size);
        BasicTextImage image = new BasicTextImage(size);
        container.draw(TextGUIGraphicsBridge.wrap(null, image.newTextGraphics()));
        return imageLines(image, columns, size.getRows());
    }

    private static List<String> imageLines(BasicTextImage image, int columns, int rows) {
        List<String> lines = new java.util.ArrayList<>(rows);
        for (int row = 0; row < rows; row++) {
            StringBuilder line = new StringBuilder(columns);
            for (int column = 0; column < columns; column++) {
                line.append(image.getCharacterAt(column, row).getCharacterString());
            }
            lines.add(line.toString());
        }
        return lines;
    }
}

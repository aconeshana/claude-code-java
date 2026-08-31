package com.claudecode.ui.lanterna.features.settings;

import com.claudecode.commands.impl.config.ConfigCommand;
import com.claudecode.core.model.CustomModelConfig;
import com.claudecode.core.model.ModelApiProtocol;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link ConfigPanel}'s interaction logic — navigation/search/toggle
 * state machine, immediate setting application, theme submenu, and close callbacks.
 * GUI-thread wiring and pixel rendering are covered only by manual test.
 */
class ConfigPanelTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);
    private static final KeyStroke ENTER = new KeyStroke(KeyType.ENTER);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);
    private static final KeyStroke SPACE = new KeyStroke(' ', false, false);
    private static final KeyStroke BACKSPACE = new KeyStroke(KeyType.BACKSPACE);

    private static void send(ConfigPanel p, KeyStroke k) {
        p.handleKey(k, new AtomicBoolean(true));
    }

    private static KeyStroke ch(char c) {
        return new KeyStroke(c, false, false);
    }

    private static Map<String, String> defaultSnapshot() {
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
        m.put("claudeHudEnabled", "true");
        m.put("subagentMaxDepth", "2");
        m.put("defaultPermissionMode", "default");
        m.put("copyFullResponse", "false");
        m.put("copyOnSelect", "true");
        m.put("theme", "dark");
        m.put("outputStyle", "default");
        m.put("editorMode", "normal");
        m.put("model", "claude-sonnet-4-6");
        return m;
    }

    private ConfigPanel opened() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        send(p, ENTER);
        return p;
    }

    private static void navigateTo(ConfigPanel p, String key) {
        int target = p.filteredKeys().indexOf(key);
        assertTrue(target >= 0, "missing ConfigPanel item: " + key);
        while (p.selectedIndex() < target) send(p, DOWN);
        while (p.selectedIndex() > target) send(p, UP);
        assertEquals(key, p.filteredKeys().get(p.selectedIndex()));
    }

    // ── activation / navigation ─────────────────────────────────────────────

    @Test
    void show_activatesInSearchModeWithAllItems() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        assertTrue(p.isActive());
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        assertEquals(ConfigPanel.itemKeys().size(), p.filteredKeys().size());
        assertEquals(0, p.selectedIndex());
    }

    @Test
    void released197InventoryUsesTheThirtyOfficialRowsInOrder() {
        assertEquals(List.of(
            "autoCompactEnabled",
            "switchModelsOnFlag",
            "spinnerTipsEnabled",
            "prefersReducedMotion",
            "thinkingEnabled",
            "awaySummaryEnabled",
            "fileCheckpointingEnabled",
            "enableWorkflows",
            "workflowKeywordTriggerEnabled",
            "verbose",
            "terminalProgressBarEnabled",
            "showTurnDuration",
            "defaultPermissionMode",
            "worktreeBaseRef",
            "useAutoModeDuringPlan",
            "respectGitignore",
            "copyFullResponse",
            "defaultToAgentsView",
            "leftArrowOpensAgents",
            "autoUpdatesChannel",
            "theme",
            "preferredNotifChannel",
            "outputStyle",
            "language",
            "editorMode",
            "externalEditorContext",
            "prStatusFooterEnabled",
            "model",
            "autoConnectIde",
            "claudeInChromeDefaultEnabled"), ConfigPanel.itemKeys());
    }

    @Test
    void smallTerminal_usesOfficialSettingsContentHeightFormula() {
        ConfigPanel p = new ConfigPanel(20);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});


        assertEquals(13, p.getPreferredSize().getRows());
    }

    @Test
    void arrowDown_movesSelection() {
        ConfigPanel p = opened();
        send(p, DOWN);
        assertEquals(1, p.selectedIndex());
    }

    @Test
    void arrowUp_atTop_entersSearch() {
        ConfigPanel p = opened();
        send(p, UP);
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
    }

    // ── boolean toggle diff ─────────────────────────────────────────────────

    @Test
    void space_togglesBoolean_addsThenRemovesPending() {
        ConfigPanel p = opened();      // index 0 = autoCompactEnabled (true)
        send(p, SPACE);
        assertEquals("false", p.pendingSnapshot().get("autoCompactEnabled"));
        send(p, SPACE);               // back to original true
        assertFalse(p.pendingSnapshot().containsKey("autoCompactEnabled"));
    }

    // ── enum cycle + wrap-around ────────────────────────────────────────────

    @Test
    void arrowLeft_cyclesEnumWithWrap_thenRightRestoresOriginal() {
        ConfigPanel p = opened();
        navigateTo(p, "defaultPermissionMode");
        send(p, LEFT);   // default → wraps to last option
        assertEquals("dontAsk", p.pendingSnapshot().get("defaultPermissionMode"));
        send(p, RIGHT);  // back to default == original → pending cleared
        assertFalse(p.pendingSnapshot().containsKey("defaultPermissionMode"));
    }

    // ── search filter ───────────────────────────────────────────────────────

    @Test
    void typing_entersSearchAndFilters() {
        ConfigPanel p = opened();
        send(p, ch('t'));
        send(p, ch('h'));
        send(p, ch('e'));   // "the" matches theme
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        assertTrue(p.filteredKeys().contains("theme"));
        assertFalse(p.filteredKeys().contains("verbose"));
    }

    @Test
    void terminalInputBatchPublishesOnlyTheFinalSearchGeneration() {
        ConfigPanel p = new ConfigPanel(40);
        List<Runnable> guiTasks = new ArrayList<>();
        p.setGuiInvoker(guiTasks::add);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        int stableHeight = p.getPreferredSize().getRows();

        p.beginInputBatch();
        for (char character : "model".toCharArray()) send(p, ch(character));
        assertEquals(ConfigPanel.itemKeys().size(), p.filteredKeys().size(),
            "an in-flight PTY drain must not expose intermediate filter generations");
        p.endInputBatch();

        assertEquals(List.of("switchModelsOnFlag", "model"), p.filteredKeys());
        assertTrue(guiTasks.isEmpty(),
            "one PTY batch should produce one complete frame without a deferred filter frame");
        assertEquals(stableHeight, p.getPreferredSize().getRows(),
            "the overlay stays top-anchored while its footer moves under filtered matches");
    }

    @Test
    void plainTextBatchMutatesAndFiltersSearchOnce() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        AtomicBoolean deliver = new AtomicBoolean(true);

        p.handlePlainText("model", deliver);

        assertFalse(deliver.get());
        assertEquals(List.of("switchModelsOnFlag", "model"), p.filteredKeys());
    }

    @Test
    void productionSearchPublishesQueryBeforeQueuedListRefresh() {
        ConfigPanel p = new ConfigPanel(40);
        List<Runnable> guiTasks = new ArrayList<>();
        p.setGuiInvoker(guiTasks::add);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});

        p.handlePlainText("model", new AtomicBoolean(true));

        assertTrue(p.filteredKeys().isEmpty(),
            "the first input frame must not retain stale unfiltered rows");
        assertEquals(1, guiTasks.size());
        guiTasks.removeFirst().run();
        assertEquals(List.of("switchModelsOnFlag", "model"), p.filteredKeys());

        send(p, BACKSPACE);
        assertEquals(List.of("switchModelsOnFlag", "model"), p.filteredKeys(),
            "editing an existing query retains the old complete generation for the echo frame");
        assertEquals(1, guiTasks.size());
        guiTasks.removeFirst().run();
        assertTrue(p.filteredKeys().contains("defaultPermissionMode"));
    }

    @Test
    void search_noMatch_emptyFiltered() {
        ConfigPanel p = opened();
        send(p, ch('z'));
        send(p, ch('z'));
        assertTrue(p.filteredKeys().isEmpty());
    }

    @Test
    void search_escClearsQueryThenExits() {
        ConfigPanel p = opened();
        send(p, ch('t'));
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        send(p, ESC);   // query non-empty → clear
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        assertEquals(ConfigPanel.itemKeys().size(), p.filteredKeys().size());
        send(p, ESC);   // empty → exit to list
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
    }

    @Test
    void search_backspaceOnEmptyExits() {
        ConfigPanel p = opened();
        send(p, ch('x'));
        send(p, BACKSPACE);   // deletes 'x' -> empty
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        send(p, BACKSPACE);   // empty -> exit
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
    }

    @Test
    void slash_entersSearchWithEmptyQuery() {
        ConfigPanel p = opened();
        send(p, ch('/'));
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
        assertEquals(ConfigPanel.itemKeys().size(), p.filteredKeys().size());
    }

    @Test
    void arrowUpInSearchMode_invokesFocusHeaderRequest_whenWired() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        AtomicBoolean requested = new AtomicBoolean(false);
        p.setOnFocusHeaderRequest(() -> requested.set(true));
        send(p, UP);   // still in default SEARCH mode
        assertTrue(requested.get());
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode(), "stays in search mode — only the callback fires");
    }

    @Test
    void arrowUpInSearchMode_noOp_whenUnwired() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        send(p, UP);   // no onFocusHeaderRequest set — must not throw
        assertEquals(ConfigPanel.Mode.SEARCH, p.mode());
    }

    // ── commit / cancel ─────────────────────────────────────────────────────

    @Test
    void enterChangesValueAndEscReportsTheSessionDiff() {
        AtomicReference<Map<String, String>> gotPending = new AtomicReference<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, gotPending::set);
        send(p, ENTER);   // exit search into the list
        send(p, ENTER);   // autoCompactEnabled -> false
        assertTrue(p.isActive());
        send(p, ESC);
        assertNotNull(gotPending.get());
        assertEquals("false", gotPending.get().get("autoCompactEnabled"));
        assertFalse(p.isActive());
    }

    @Test
    void released197EnterChangesTheSelectedValueWithoutClosing() {
        ConfigPanel p = opened();

        send(p, ENTER);

        assertTrue(p.isActive());
        assertEquals("false", p.pendingSnapshot().get("autoCompactEnabled"));
    }

    @Test
    void released197ChangesAreAppliedImmediatelyAndEscOnlyCloses() {
        List<String> applied = new ArrayList<>();
        AtomicReference<Map<String, String>> closed = new AtomicReference<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {},
            (key, value) -> applied.add(key + "=" + value), closed::set);
        send(p, ENTER);

        send(p, SPACE);

        assertEquals(List.of("autoCompactEnabled=false"), applied);
        assertNull(closed.get());
        assertTrue(p.isActive());

        send(p, ESC);

        assertEquals(Map.of("autoCompactEnabled", "false"), closed.get());
        assertFalse(p.isActive());
    }

    @Test
    void released197ListFooterAndEnumValueUseOfficialPresentation() {
        ConfigPanel p = opened();
        navigateTo(p, "defaultPermissionMode");

        List<String> lines = renderedLines(p, 80);

        assertTrue(lines.stream().anyMatch(line ->
            line.contains("Default permission mode")
                && line.stripTrailing().endsWith("Default")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("‹") || line.contains("›")));
        assertEquals("  Enter/Space to change · / to search · Esc to close",
            lines.getLast().stripTrailing());
    }

    @Test
    void released197LanguageRowOpensInlineTextEditorAndAppliesTheValue() {
        Map<String, String> snapshot = defaultSnapshot();
        snapshot.put("language", "");
        List<String> applied = new ArrayList<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(snapshot, false, _ -> {},
            (key, value) -> applied.add(key + "=" + value), _ -> {});
        send(p, ENTER);
        navigateTo(p, "language");

        send(p, SPACE);
        assertEquals(ConfigPanel.Mode.SUBMENU, p.mode());
        p.handlePlainText("Chinese", new AtomicBoolean(true));
        send(p, ENTER);

        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertEquals(List.of("language=Chinese"), applied);
        assertEquals("Chinese", p.pendingSnapshot().get("language"));
    }

    @Test
    void escClosesAndReturnsPendingChanges() {
        AtomicReference<Map<String, String>> result = new AtomicReference<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, _ -> {}, result::set);
        send(p, ENTER);   // exit search into the list
        send(p, SPACE);
        send(p, ESC);
        assertEquals(Map.of("autoCompactEnabled", "false"), result.get());
        assertFalse(p.isActive());
    }

    // ── theme submenu ────────────────────────────────────────────────────────

    @Test
    void defaultPermissionModeCyclesAcrossTheReleasedFiveChoices() {
        ConfigPanel p = opened();
        navigateTo(p, "defaultPermissionMode");

        send(p, SPACE);
        assertEquals("plan", p.pendingSnapshot().get("defaultPermissionMode"));
        send(p, SPACE);
        send(p, SPACE);
        send(p, SPACE);
        assertEquals("dontAsk", p.pendingSnapshot().get("defaultPermissionMode"));
        send(p, SPACE);
        assertFalse(p.pendingSnapshot().containsKey("defaultPermissionMode"));
    }

    @Test
    void theme_space_opensSubmenu() {
        ConfigPanel p = opened();
        navigateTo(p, "theme");
        send(p, SPACE);
        assertEquals(ConfigPanel.Mode.SUBMENU, p.mode());
        assertTrue(p.themeSubmenu().isActive());
    }

    @Test
    void theme_submenuConfirm_setsPendingAndReturnsToList() {
        ConfigPanel p = opened();
        navigateTo(p, "theme");
        send(p, SPACE);      // open submenu (selected "dark")
        send(p, DOWN);       // preview "light"
        send(p, ENTER);      // confirm
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertEquals("light", p.pendingSnapshot().get("theme"));
    }

    @Test
    void theme_submenuEsc_revertsPreviewNoPending() {
        AtomicReference<String> lastPreview = new AtomicReference<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, lastPreview::set, _ -> {});
        send(p, ENTER);      // exit search into the list
        navigateTo(p, "theme");
        send(p, SPACE);      // open submenu
        send(p, DOWN);       // preview "light"
        assertEquals("light", lastPreview.get());
        send(p, ESC);        // cancel submenu -> revert to "dark"
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertEquals("dark", lastPreview.get());
        assertFalse(p.pendingSnapshot().containsKey("theme"));
    }

    @Test
    void escAfterConfirmedThemeChangeLeavesTheAppliedPreviewInPlace() {
        AtomicReference<String> lastPreview = new AtomicReference<>();
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), false, lastPreview::set, _ -> {});
        send(p, ENTER);      // exit search into the list
        navigateTo(p, "theme");
        send(p, SPACE);      // submenu
        send(p, DOWN);       // preview light
        send(p, ENTER);      // confirm -> pending theme=light
        assertEquals("light", p.pendingSnapshot().get("theme"));
        send(p, ESC);        // close whole panel; the confirmed change was already applied
        assertEquals("light", lastPreview.get());
    }

    // ── thinkingEnabled mid-conversation warning ────────────────────────────

    private static void navigateToThinkingEnabled(ConfigPanel p) {
        navigateTo(p, "thinkingEnabled");
    }

    // ── output-style submenu ────────────────────────────────────────────────

    @Test
    void outputStyle_space_opensManagedPicker() {
        ConfigPanel p = opened();
        navigateTo(p, "outputStyle");

        send(p, SPACE);

        assertEquals(ConfigPanel.Mode.SUBMENU, p.mode());
        assertTrue(p.outputStyleSubmenu().isActive());
    }

    @Test
    void outputStyle_confirm_setsPendingAndReturnsToList() {
        ConfigPanel p = opened();
        navigateTo(p, "outputStyle");
        send(p, SPACE);
        send(p, DOWN);       // default -> Explanatory
        send(p, ENTER);

        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertEquals("Explanatory", p.pendingSnapshot().get("outputStyle"));
    }

    @Test
    void thinkingEnabled_toggleWithAssistantMessage_showsWarning() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), true, _ -> {}, _ -> {});
        send(p, ENTER);
        navigateToThinkingEnabled(p);
        send(p, SPACE);
        assertTrue(p.showThinkingWarning());
    }

    @Test
    void thinkingEnabled_toggleWithoutAssistantMessage_noWarning() {
        ConfigPanel p = opened();   // hasAssistantMessage=false
        navigateToThinkingEnabled(p);
        send(p, SPACE);
        assertFalse(p.showThinkingWarning());
    }

    @Test
    void thinkingEnabled_toggleBackToOriginal_hidesWarning() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), true, _ -> {}, _ -> {});
        send(p, ENTER);
        navigateToThinkingEnabled(p);
        send(p, SPACE);   // true -> false, differs from original -> warning
        assertTrue(p.showThinkingWarning());
        send(p, SPACE);   // false -> true, back to original -> warning clears
        assertFalse(p.showThinkingWarning());
    }

    @Test
    void thinkingEnabled_navigatingAway_hidesWarning() {
        ConfigPanel p = new ConfigPanel(40);
        p.show(defaultSnapshot(), true, _ -> {}, _ -> {});
        send(p, ENTER);
        navigateToThinkingEnabled(p);
        send(p, SPACE);
        assertTrue(p.showThinkingWarning());
        send(p, DOWN);
        assertFalse(p.showThinkingWarning());
    }

    // ── model submenu ────────────────────────────────────────────────────────

    @Test
    void model_space_opensSubmenu() {
        ConfigPanel p = opened();
        navigateTo(p, "model");
        send(p, SPACE);
        assertEquals(ConfigPanel.Mode.SUBMENU, p.mode());
        assertTrue(p.modelSubmenu().isActive());
        assertFalse(p.themeSubmenu().isActive(), "opening model must not also leave theme active");
    }

    @Test
    void defaultModelPreferenceOpensCanonicalDefaultWithoutCreatingPendingChange() {
        Map<String, String> snapshot = defaultSnapshot();
        snapshot.put("model", "default");
        ConfigPanel p = new ConfigPanel(40);
        p.show(snapshot, false, _ -> {}, _ -> {});
        send(p, ENTER);
        navigateTo(p, "model");

        send(p, SPACE);

        // Default + Fable/Opus/Sonnet/Haiku, plus picker chrome. Passing the
        // UI sentinel "default" through as a literal model would append a
        // bogus sixth "Current session model" row.
        assertEquals(12, p.modelSubmenu().getPreferredSize().getRows());
        send(p, ENTER);
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertFalse(p.pendingSnapshot().containsKey("model"));
    }

    @Test
    void modelSubmenuUsesPreloadedMetadata() {
        ConfigPanel p = new ConfigPanel(40);
        p.setModelPickerMetadata("high", List.of(new CustomModelConfig(
            "gateway-model", ModelApiProtocol.OPENAI_RESPONSES,
            "https://example.test/v1", null, Map.of())));
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        send(p, ENTER);
        navigateTo(p, "model");

        send(p, SPACE);

        // 5 standard rows (Default + Fable/Opus/Sonnet/Haiku) + legacy current row
        // + custom row + "Add custom model…",
        // plus picker chrome. The old pinned Sonnet 4.6 remains selectable.
        assertEquals(15, p.modelSubmenu().getPreferredSize().getRows());
    }

    @Test
    void model_submenuConfirm_setsPendingAndReturnsToList() {
        ConfigPanel p = opened();
        navigateTo(p, "model");
        send(p, SPACE);      // open submenu
        send(p, DOWN);       // move selection
        send(p, ENTER);      // confirm
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertNotEquals("claude-sonnet-4-6", p.pendingSnapshot().get("model"));
    }

    @Test
    void model_submenuEsc_noPendingNoRevertNeeded() {
        ConfigPanel p = opened();
        navigateTo(p, "model");
        send(p, SPACE);      // open submenu
        send(p, DOWN);       // move selection (no live-preview to revert)
        send(p, ESC);        // cancel submenu
        assertEquals(ConfigPanel.Mode.LIST, p.mode());
        assertFalse(p.pendingSnapshot().containsKey("model"));
    }

    // ── refusal-fallback lane gate ───────────────────────────────────────────

    @Test
    void switchModelsOnFlag_sitsBetweenAutoCompactAndTips() {
        List<String> keys = ConfigPanel.itemKeys();
        assertEquals(List.of("autoCompactEnabled", "switchModelsOnFlag", "spinnerTipsEnabled"),
            keys.subList(0, 3),
            "released places the row right after Auto-compact, before Show tips");
    }

    @Test
    void switchModelsOnFlag_isOfferedWhileTheLaneIsOn() {
        ConfigPanel p = new ConfigPanel(40);
        p.setRefusalLaneEnabled(() -> true);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});

        assertTrue(p.filteredKeys().contains("switchModelsOnFlag"));
        assertEquals(ConfigPanel.itemKeys().size(), p.filteredKeys().size());
    }

    @Test
    void switchModelsOnFlag_disappearsWhenTheLaneIsDisabled() {
        ConfigPanel p = new ConfigPanel(40);
        p.setRefusalLaneEnabled(() -> false);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});

        assertFalse(p.filteredKeys().contains("switchModelsOnFlag"),
            "a toggle that cannot change anything is not offered");
        assertEquals(ConfigPanel.itemKeys().size() - 1, p.filteredKeys().size(),
            "and only that one row goes: " + p.filteredKeys());
    }

    @Test
    void switchModelsOnFlag_staysHiddenWhileSearching() {
        ConfigPanel p = new ConfigPanel(40);
        p.setRefusalLaneEnabled(() -> false);
        p.show(defaultSnapshot(), false, _ -> {}, _ -> {});
        send(p, ch('s'));
        send(p, ch('w'));

        assertTrue(p.filteredKeys().isEmpty(),
            "the search box cannot reach a gated row: " + p.filteredKeys());
    }

    // ── guard: view model aligned with ConfigCommand ─────────────────────────

    @Test
    void itemKeys_alignWithConfigCommand() {
        // "model" is the one ConfigPanel item with no ConfigCommand.Setting row —
        // it's a runtime-only setting (see ConfigPanel's class Javadoc), so the
        // guard is "superset minus exactly {model}", not full equality.
        List<String> panelKeys = new ArrayList<>(ConfigPanel.itemKeys());
        assertTrue(panelKeys.remove("model"), "ConfigPanel.itemKeys() must contain \"model\"");
        assertEquals(ConfigCommand.settingKeys(), panelKeys,
            "ConfigPanel.ITEMS (minus \"model\") must stay aligned with ConfigCommand.SETTINGS (same keys, same order)");
    }

    private static List<String> renderedLines(ConfigPanel panel, int columns) {
        TerminalSize size = new TerminalSize(columns, panel.calculatePreferredSize().getRows());
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

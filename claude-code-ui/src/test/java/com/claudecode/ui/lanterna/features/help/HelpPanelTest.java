package com.claudecode.ui.lanterna.features.help;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.keybindings.KeybindingResolver;
import com.claudecode.keybindings.KeystrokeParser;
import com.claudecode.ui.lanterna.features.help.HelpPanel.CommandEntry;
import com.claudecode.ui.lanterna.features.help.HelpPanel.ShortcutLabels;
import com.claudecode.ui.lanterna.features.help.HelpPanel.Tab;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.graphics.BasicTextImage;
import com.googlecode.lanterna.gui2.TextGUIGraphicsBridge;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class HelpPanelTest {

    private static final KeyStroke UP = new KeyStroke(KeyType.ARROW_UP);
    private static final KeyStroke DOWN = new KeyStroke(KeyType.ARROW_DOWN);
    private static final KeyStroke LEFT = new KeyStroke(KeyType.ARROW_LEFT);
    private static final KeyStroke RIGHT = new KeyStroke(KeyType.ARROW_RIGHT);
    private static final KeyStroke TAB = new KeyStroke(KeyType.TAB);
    private static final KeyStroke SHIFT_TAB = new KeyStroke(KeyType.REVERSE_TAB);
    private static final KeyStroke ESC = new KeyStroke(KeyType.ESCAPE);

    private static final List<CommandEntry> BUILTINS = List.of(
        new CommandEntry("clear", "Clear conversation history"),
        new CommandEntry("config", "Open config panel"),
        new CommandEntry("help", "Show help"));

    /** The command window uses the released terminal-row budget, not half the terminal. */
    private static HelpPanel shown(int termRows, List<CommandEntry> custom) {
        HelpPanel panel = new HelpPanel(termRows, false);
        panel.show("2.0.0", BUILTINS, custom, () -> {});
        return panel;
    }

    private static void send(HelpPanel panel, KeyStroke key) {
        panel.handleKey(key, new AtomicBoolean(true));
    }

    // ── overlay contract ─────────────────────────────────────────────────────

    @Test
    void idle_zeroSizeAndKeysFallThrough() {
        HelpPanel panel = new HelpPanel(40, false);
        assertEquals(0, panel.calculatePreferredSize().getRows());
        AtomicBoolean deliver = new AtomicBoolean(true);
        panel.handleKey(ESC, deliver);
        assertTrue(deliver.get(), "idle panel must not consume keys");
    }

    @Test
    void show_activatesWithNonZeroHeightOnGeneralTab() {
        HelpPanel panel = shown(40, List.of());
        assertTrue(panel.isActive());
        assertEquals(Tab.GENERAL, panel.selectedTab());
        assertTrue(panel.headerFocused(), "TS initialHeaderFocused defaults true");
        assertTrue(panel.calculatePreferredSize().getRows() > 0);
    }

    @Test
    void windows_dropsCtrlZSuspendRow() {
        HelpPanel panel = new HelpPanel(40, true);
        panel.show("2.0.0", BUILTINS, List.of(), () -> {});
        assertFalse(panel.shortcutLabels().column3(true).contains("ctrl + z to suspend"));
        assertTrue(panel.shortcutLabels().column3(false).contains("ctrl + z to suspend"));
    }

    @Test
    void released197GeneralHelpWrapsAt80ColumnsWithoutCrossColumnOverwrite() {
        HelpPanel panel = released197WindowsPanel(80);

        List<String> lines = renderedLines(panel, 80);

        assertEquals(20, panel.calculatePreferredSize().getRows());
        assertEquals("  Help  General   Commands   Custom commands", lines.get(2).stripTrailing());
        assertEquals("  Claude understands your codebase, makes edits with your permission, and",
            lines.get(4).stripTrailing());
        assertEquals("  executes commands — right from your terminal.", lines.get(5).stripTrailing());
        assertEquals("  Shortcuts", lines.get(6).stripTrailing());
        assertEquals("  ! for shell mode    double tap esc to clear      ctrl + shift + - to undo",
            lines.get(7).stripTrailing());
        assertEquals("  / for commands      input                        alt + v to paste images",
            lines.get(8).stripTrailing());
        assertEquals("  @ for file paths    alt + m to auto-accept       alt + p to switch model",
            lines.get(9).stripTrailing());
        assertEquals("  /btw for side       edits                        ctrl + s to stash prompt",
            lines.get(10).stripTrailing());
        assertEquals("  question            ctrl + o for verbose         ctrl + x ctrl + e to edit",
            lines.get(11).stripTrailing());
        assertEquals("                      output                       in $EDITOR",
            lines.get(12).stripTrailing());
        assertEquals("                      ctrl + t to toggle tasks     /keybindings to customize",
            lines.get(13).stripTrailing());
        assertEquals("                      backslash (\\) + return (⏎)",
            lines.get(14).stripTrailing());
        assertEquals("                      for newline", lines.get(15).stripTrailing());
        assertEquals("  For more help: https://code.claude.com/docs/en/overview",
            lines.get(17).stripTrailing());
        assertEquals("  Esc to cancel", lines.get(19).stripTrailing());
    }

    @Test
    void released197GeneralHelpUsesWideColumnGeometryAt120Columns() {
        HelpPanel panel = released197WindowsPanel(120);

        List<String> lines = renderedLines(panel, 120);

        assertEquals(17, panel.calculatePreferredSize().getRows());
        assertEquals("  ! for shell mode          double tap esc to clear input        ctrl + shift + - to undo",
            lines.get(7).stripTrailing());
        assertEquals("  /btw for side question    ctrl + t to toggle tasks             ctrl + s to stash prompt",
            lines.get(10).stripTrailing());
        assertEquals("                            backslash (\\) + return (⏎) for       ctrl + x ctrl + e to edit in $EDITOR",
            lines.get(11).stripTrailing());
        assertEquals("                            newline                              /keybindings to customize",
            lines.get(12).stripTrailing());
    }

    @Test
    void released197CommandsTabShowsSevenEntriesAt24Rows() {
        List<CommandEntry> commands = List.of(
            new CommandEntry("add-dir", "Add a new working directory"),
            new CommandEntry("agents", "Manage agent configurations"),
            new CommandEntry("background", "Send this session to the background and free the terminal"),
            new CommandEntry("batch", "Research and plan a large-scale change"),
            new CommandEntry("branch", "Create a branch of the current conversation at this point"),
            new CommandEntry("btw", "Ask a quick side question"),
            new CommandEntry("cd", "Move this session to a new working directory"),
            new CommandEntry("clear", "Clear conversation history"));
        HelpPanel panel = new HelpPanel(24, true);
        panel.setTerminalColumnsSupplier(() -> 80);
        panel.show("2.1.197", commands, List.of(), () -> { });

        send(panel, RIGHT);
        List<String> lines = renderedLines(panel, 80);

        assertEquals(7, panel.visibleCount());
        assertEquals(24, panel.calculatePreferredSize().getRows());
        assertFalse(lines.stream().anyMatch(line -> line.contains("Help  General")));
        assertEquals("  Browse default commands", lines.get(2).stripTrailing());
        assertEquals("    /add-dir", lines.get(4).stripTrailing());
        assertEquals("  ↓ /cd", lines.get(16).stripTrailing());
        assertFalse(lines.stream().anyMatch(line -> line.contains("/clear")));
        assertEquals("  For more help: https://code.claude.com/docs/en/overview",
            lines.get(20).stripTrailing());
        assertEquals("  Esc to cancel", lines.get(22).stripTrailing());
    }

    @Test
    void windowsTabSwitchRequestsACompleteRefresh() {
        AtomicInteger refreshes = new AtomicInteger();
        HelpPanel panel = new HelpPanel(24, true, refreshes::incrementAndGet);
        panel.show("2.1.197", BUILTINS, List.of(), () -> { });

        send(panel, RIGHT);

        assertEquals(1, refreshes.get());
    }

    @Test
    void activeKeys_areConsumedNotDelivered() {
        HelpPanel panel = shown(40, List.of());
        AtomicBoolean deliver = new AtomicBoolean(true);
        panel.handleKey(new KeyStroke('x', false, false), deliver);
        assertFalse(deliver.get(), "active help panel is modal — all keys consumed");
        assertTrue(panel.isActive());
    }

    @Test
    void pasteKey_isConsumedAndDoesNotClose() {
        HelpPanel panel = shown(40, List.of());
        AtomicBoolean deliver = new AtomicBoolean(true);
        panel.handleKey(new KeyStroke(KeyType.PASTE), deliver);
        assertFalse(deliver.get(),
            "PASTE must be swallowed so it can't leak into the main input");
        assertTrue(panel.isActive(), "PASTE must not close the panel");
    }

    // ── closing ──────────────────────────────────────────────────────────────

    @Test
    void escape_closesAndFiresOnCloseOnce() {
        AtomicInteger closed = new AtomicInteger();
        HelpPanel panel = new HelpPanel(40, false);
        panel.show("2.0.0", BUILTINS, List.of(), closed::incrementAndGet);
        send(panel, ESC);
        assertFalse(panel.isActive());
        assertEquals(0, panel.calculatePreferredSize().getRows(), "zero height after close");
        send(panel, ESC); // inactive → no second callback
        assertEquals(1, closed.get());
    }

    @Test
    void ctrlC_andCtrlD_close() {
        for (char c : new char[]{'c', 'd'}) {
            AtomicInteger closed = new AtomicInteger();
            HelpPanel panel = new HelpPanel(40, false);
            panel.show("2.0.0", BUILTINS, List.of(), closed::incrementAndGet);
            send(panel, new KeyStroke(c, true, false));
            assertFalse(panel.isActive(), "ctrl+" + c + " must close");
            assertEquals(1, closed.get());
        }
    }

    @Test
    void configuredDismissBindingDrivesBehaviorAndFooterLabel() {
        List<KeybindingResolver.ParsedBinding> bindings = new ArrayList<>(
            KeybindingResolver.defaultsAsBindings());
        bindings.add(new KeybindingResolver.ParsedBinding(
            KeystrokeParser.parseChord("escape"), null, "Help"));
        bindings.add(new KeybindingResolver.ParsedBinding(
            KeystrokeParser.parseChord("x"), "help:dismiss", "Help"));
        KeybindingResolver resolver = new KeybindingResolver(bindings);
        HelpPanel panel = new HelpPanel(40, false);
        panel.show("2.0.0", BUILTINS, List.of(),
            ShortcutLabels.from(resolver, true), resolver, () -> {});

        send(panel, ESC);
        assertTrue(panel.isActive(), "an explicitly unbound Esc must not fall back to hardcoded dismissal");
        assertEquals("x", panel.shortcutLabels().dismiss());

        send(panel, new KeyStroke('x', false, false));
        assertFalse(panel.isActive());
    }

    @Test
    void shortcutLabelsUseRuntimeBindingsAndRespectCustomizationGate() {
        List<KeybindingResolver.ParsedBinding> bindings = new ArrayList<>(
            KeybindingResolver.defaultsAsBindings());
        bindings.add(new KeybindingResolver.ParsedBinding(
            KeystrokeParser.parseChord("ctrl+y"), "app:toggleTranscript", "Global"));
        KeybindingResolver resolver = new KeybindingResolver(bindings);

        ShortcutLabels enabled = ShortcutLabels.from(resolver, true);
        ShortcutLabels disabled = ShortcutLabels.from(resolver, false);

        assertTrue(enabled.column2().contains("ctrl + y for verbose output"));
        assertTrue(enabled.column3(false).contains(
            "ctrl + x ctrl + e to edit in $EDITOR"), enabled.column3(false)::toString);
        assertTrue(enabled.column3(false).contains("/keybindings to customize"));
        assertFalse(disabled.column3(false).contains("/keybindings to customize"));
    }

    @Test
    void escape_closesEvenWhileListFocused() {
        AtomicInteger closed = new AtomicInteger();
        HelpPanel panel = new HelpPanel(30, false);
        panel.show("2.0.0", BUILTINS, List.of(), closed::incrementAndGet);
        send(panel, RIGHT); // commands tab
        send(panel, DOWN);  // focus list
        assertFalse(panel.headerFocused());
        send(panel, ESC);
        assertEquals(1, closed.get());
        assertFalse(panel.isActive());
    }

    // ── tab switching ────────────────────────────────────────────────────────

    @Test
    void arrowsAndTab_switchTabsWithWraparound() {
        HelpPanel panel = shown(40, List.of());
        send(panel, RIGHT);
        assertEquals(Tab.COMMANDS, panel.selectedTab());
        send(panel, RIGHT);
        assertEquals(Tab.CUSTOM_COMMANDS, panel.selectedTab());
        send(panel, RIGHT);
        assertEquals(Tab.GENERAL, panel.selectedTab(), "→ wraps past the last tab");
        send(panel, LEFT);
        assertEquals(Tab.CUSTOM_COMMANDS, panel.selectedTab(), "← wraps back");
        send(panel, TAB);
        assertEquals(Tab.GENERAL, panel.selectedTab(), "Tab = tabs:next");
        send(panel, SHIFT_TAB);
        assertEquals(Tab.CUSTOM_COMMANDS, panel.selectedTab(), "Shift+Tab = tabs:previous");
    }

    @Test
    void switchingTabs_resetsListFocusAndRefocusesHeader() {
        HelpPanel panel = shown(30, List.of());
        send(panel, RIGHT); // commands
        send(panel, DOWN);  // into list
        send(panel, DOWN);  // idx 1
        assertEquals(1, panel.selectedIdx());
        assertFalse(panel.headerFocused());

        send(panel, RIGHT);
        assertEquals(Tab.COMMANDS, panel.selectedTab());
        // …so go back up to the header, then switch.
        send(panel, UP);
        send(panel, UP);
        assertTrue(panel.headerFocused());
        send(panel, RIGHT);
        send(panel, LEFT); // custom → back to commands
        assertEquals(Tab.COMMANDS, panel.selectedTab());
        assertEquals(0, panel.selectedIdx(), "TS unmounts the Tab's Select — focus resets");
        assertEquals(0, panel.visibleFrom());
        assertTrue(panel.headerFocused(), "TS handleTabChange: setHeaderFocused(true)");
    }

    // ── list focus / scroll window ───────────────────────────────────────────

    @Test
    void downOnGeneralTab_keepsHeaderFocused() {
        HelpPanel panel = shown(40, List.of());
        send(panel, DOWN);
        assertTrue(panel.headerFocused(), "General never opts into header blur");
    }

    @Test
    void upDown_moveListFocusAndUpFromFirstReturnsToHeader() {
        HelpPanel panel = shown(30, List.of());
        send(panel, RIGHT); // commands tab
        send(panel, DOWN);  // blur header into list
        assertFalse(panel.headerFocused());
        assertEquals(0, panel.selectedIdx());
        send(panel, DOWN);
        assertEquals(1, panel.selectedIdx());
        send(panel, UP);
        assertEquals(0, panel.selectedIdx());
        send(panel, UP);
        assertTrue(panel.headerFocused());
        assertEquals(0, panel.selectedIdx());
    }

    @Test
    void scrollWindow_followsFocusAndWrapsAtBottom() {
        // 12 custom commands, termRows=30 → visibleCount=10.
        List<CommandEntry> many = IntStream.range(0, 12)
            .mapToObj(i -> new CommandEntry("cmd" + i, "desc" + i))
            .toList();
        HelpPanel panel = shown(30, many);
        assertEquals(10, panel.visibleCount());
        send(panel, LEFT); // wrap to custom-commands
        assertEquals(Tab.CUSTOM_COMMANDS, panel.selectedTab());
        send(panel, DOWN); // into list
        for (int i = 0; i < 11; i++) send(panel, DOWN); // walk to last
        assertEquals(11, panel.selectedIdx());
        assertEquals(2, panel.visibleFrom(), "window slides to keep focus visible");
        send(panel, DOWN);
        assertEquals(0, panel.selectedIdx());
        assertEquals(0, panel.visibleFrom(), "window snaps back to the top");
    }

    @Test
    void commandsTab_heightCoversVisibleWindowOnly() {
        HelpPanel panel = shown(30, List.of()); // visibleCount=10, all 3 builtins
        send(panel, RIGHT); // commands tab
        // Chrome(4) + paddingY(2) + title+gap(2) + 3 visible × 2 rows + footer(4).
        assertEquals(4 + 2 + 2 + 6 + 4, panel.calculatePreferredSize().getRows());
    }

    // ── empty custom-commands ────────────────────────────────────────────────

    @Test
    void emptyCustomTab_showsEmptyStateAndBlocksListFocus() {
        HelpPanel panel = shown(30, List.of());
        send(panel, LEFT); // wrap to custom-commands
        assertEquals(Tab.CUSTOM_COMMANDS, panel.selectedTab());
        send(panel, DOWN);
        assertTrue(panel.headerFocused(), "empty list: nothing to focus into");
        // Chrome(4) + paddingY(2) + "No custom commands found"(1) + footer(4).
        assertEquals(4 + 2 + 1 + 4, panel.calculatePreferredSize().getRows());
    }

    private static HelpPanel released197WindowsPanel(int columns) {
        HelpPanel panel = new HelpPanel(40, true);
        panel.setTerminalColumnsSupplier(() -> columns);
        panel.show("2.1.197", BUILTINS, List.of(), new ShortcutLabels(
            List.of("! for shell mode", "/ for commands", "@ for file paths",
                "/btw for side question"),
            List.of("double tap esc to clear input", "alt + m to auto-accept edits",
                "ctrl + o for verbose output", "ctrl + t to toggle tasks",
                "backslash (\\) + return (⏎) for newline"),
            List.of("ctrl + shift + - to undo", "alt + v to paste images",
                "alt + p to switch model", "ctrl + s to stash prompt",
                "ctrl + x ctrl + e to edit in $EDITOR", "/keybindings to customize"),
            List.of("ctrl + shift + - to undo", "ctrl + z to suspend",
                "alt + v to paste images", "alt + p to switch model",
                "ctrl + s to stash prompt", "ctrl + x ctrl + e to edit in $EDITOR",
                "/keybindings to customize"),
            "Esc"), KeybindingResolver.defaultResolver(), () -> {});
        return panel;
    }

    private static List<String> renderedLines(HelpPanel panel, int columns) {
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

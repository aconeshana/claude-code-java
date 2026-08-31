package com.claudecode.ui.lanterna.features.help;

import com.claudecode.keybindings.DefaultBindings;
import com.claudecode.keybindings.KeybindingResolver;
import com.claudecode.keybindings.KeystrokeParser;
import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.ui.lanterna.dialog.CopyPickerDialog;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.input.LanternaKeyAdapter;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntSupplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Interactive {@code /help} panel — sits above {@link InputPanel} in the SmartLayout stack,
 * occupying zero rows when idle.
 */
public final class HelpPanel extends Panel implements InlineOverlay {

    /** One row of a commands tab. The caller filters hidden commands and sorts. */
    public record CommandEntry(String name, String description) {}


    public record ShortcutLabels(
        List<String> column1,
        List<String> column2,
        List<String> column3Windows,
        List<String> column3Other,
        String dismiss
    ) {
        public ShortcutLabels {
            column1 = List.copyOf(column1);
            column2 = List.copyOf(column2);
            column3Windows = List.copyOf(column3Windows);
            column3Other = List.copyOf(column3Other);
        }

        public static ShortcutLabels from(KeybindingResolver resolver,
                                          boolean customizationEnabled) {
            String cycleMode = releasedDisplay(binding(resolver,
                "chat:cycleMode", "Chat", DefaultBindings.MODE_CYCLE_KEY));
            String transcript = releasedDisplay(binding(resolver,
                "app:toggleTranscript", "Global", "ctrl+o"));
            String todos = releasedDisplay(binding(resolver,
                "app:toggleTodos", "Global", "ctrl+t"));
            String undo = releasedDisplay(binding(resolver,
                "chat:undo", "Chat", "ctrl+shift+-"));
            String imagePaste = releasedDisplay(binding(resolver,
                "chat:imagePaste", "Chat", DefaultBindings.IMAGE_PASTE_KEY));
            String modelPicker = releasedDisplay(binding(resolver,
                "chat:modelPicker", "Chat", "alt+p"));
            String stash = releasedDisplay(binding(resolver,
                "chat:stash", "Chat", "ctrl+s"));
            String externalEditor = releasedDisplay(binding(resolver,
                "chat:externalEditor", "Chat", "ctrl+x ctrl+e"));
            String dismiss = binding(resolver,
                "help:dismiss", "Help", "esc");

            List<String> col1 = List.of(
                "! for shell mode",
                "/ for commands",
                "@ for file paths",
                "/btw for side question");
            List<String> col2 = List.of(
                "double tap esc to clear input",
                cycleMode + " to auto-accept edits",
                transcript + " for verbose output",
                todos + " to toggle tasks",
                "backslash (\\) + return (⏎) for newline");
            List<String> common3 = new ArrayList<>(List.of(
                undo + " to undo",
                imagePaste + " to paste images",
                modelPicker + " to switch model",
                stash + " to stash prompt",
                externalEditor + " to edit in $EDITOR"));
            if (customizationEnabled) common3.add("/keybindings to customize");
            List<String> windows = List.copyOf(common3);
            common3.add(1, "ctrl + z to suspend");
            return new ShortcutLabels(col1, col2, windows,
                List.copyOf(common3), releasedDismiss(dismiss));
        }

        public List<String> column3(boolean isWindows) {
            return isWindows ? column3Windows : column3Other;
        }

        private static String binding(KeybindingResolver resolver, String action,
                                      String context, String fallback) {
            if (resolver == null) return fallback;
            String resolved = resolver.getBindingDisplayText(action, context);
            return StringUtils.isBlank(resolved) ? fallback : resolved;
        }

        private static String releasedDisplay(String shortcut) {
            String released = Strings.CI.equals(shortcut, "ctrl+_")
                ? "ctrl+shift+-" : shortcut;
            if (DefaultBindings.IS_WINDOWS) {
                released = released.replace("meta+", "alt+");
            }
            return released.replace("+", " + ");
        }

        private static String releasedDismiss(String shortcut) {
            return Strings.CI.equals(shortcut, "esc")
                    || Strings.CI.equals(shortcut, "escape")
                ? "Esc" : shortcut;
        }
    }

    enum Tab { GENERAL, COMMANDS, CUSTOM_COMMANDS }


    private static final int LEFT_PAD = 2;

    private static final int COL1_WIDTH = 24;
    private static final int COL2_WIDTH = 35;
    private static final int COL_GAP = 2;

    private static final String INTRO =
        "Claude understands your codebase, makes edits with your permission, "
            + "and executes commands — right from your terminal.";
    private static final String DOCS_LINE =
        "For more help: https://code.claude.com/docs/en/overview";

    private final int termRows;
    private final boolean isWindows;
    private final Runnable tabSwitchRefresh;
    private IntSupplier terminalColumnsSupplier = () -> 80;

    private boolean active;
    private Tab selectedTab = Tab.GENERAL;
    private boolean headerFocused = true;
    private int selectedIdx;
    private int visibleFrom;

    private String version = "";
    private List<CommandEntry> builtinCommands = List.of();
    private List<CommandEntry> customCommands = List.of();
    private ShortcutLabels shortcutLabels = defaultShortcutLabels();
    private KeybindingResolver keybindingResolver = defaultResolver();
    private KeystrokeParser.Chord pendingChord;
    private Runnable onClose;

    public HelpPanel(int termRows) {
        this(termRows, Strings.CI.contains(System.getProperty("os.name", ""), "win"), null);
    }

    /** Test seam for the platform-dependent "ctrl + z to suspend" row. */
    HelpPanel(int termRows, boolean isWindows) {
        this(termRows, isWindows, null);
    }

    /** Test seam for the Windows complete-refresh workaround. */
    HelpPanel(int termRows, boolean isWindows, Runnable tabSwitchRefresh) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.termRows = termRows;
        this.isWindows = isWindows;
        this.tabSwitchRefresh = tabSwitchRefresh != null
            ? tabSwitchRefresh : this::requestCompleteRefresh;
        HelpArea area = new HelpArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    /** Supplies the live terminal width so resize changes recompute the overlay height. */
    public synchronized void setTerminalColumnsSupplier(IntSupplier supplier) {
        terminalColumnsSupplier = supplier != null ? supplier : () -> 80;
        invalidate();
    }

    /**
     * Activates the panel. Must run on the GUI thread.
     *
     * @param version         app version for the "Claude Code v&lt;version&gt;" title
     * @param builtinCommands commands tab content — pre-filtered (no hidden)
     *                        and pre-sorted by the caller
     * @param customCommands  custom-commands tab content, same contract
     * @param onClose         invoked once on Esc / Ctrl+C / Ctrl+D, after the
     *                        panel hides
     */
    public synchronized void show(String version,
                                  List<CommandEntry> builtinCommands,
                                  List<CommandEntry> customCommands,
                                  Runnable onClose) {
        show(version, builtinCommands, customCommands,
            defaultShortcutLabels(), defaultResolver(), onClose);
    }

    public synchronized void show(String version,
                                  List<CommandEntry> builtinCommands,
                                  List<CommandEntry> customCommands,
                                  ShortcutLabels shortcutLabels,
                                  KeybindingResolver keybindingResolver,
                                  Runnable onClose) {
        this.version = version != null ? version : "";
        this.builtinCommands = List.copyOf(builtinCommands);
        this.customCommands = List.copyOf(customCommands);
        this.shortcutLabels = shortcutLabels != null
            ? shortcutLabels : defaultShortcutLabels();
        this.keybindingResolver = keybindingResolver != null
            ? keybindingResolver : defaultResolver();
        this.pendingChord = null;
        this.onClose = onClose;
        this.selectedTab = Tab.GENERAL;
        this.headerFocused = true;
        this.selectedIdx = 0;
        this.visibleFrom = 0;
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;

        // every key is consumed, including PASTE, so nothing leaks into the
        // main input behind this overlay.
        deliver.set(false);

        KeyType t = key.getKeyType();
        Character ch = key.getCharacter();

        if (t == KeyType.PASTE) return;
        if (dispatchViaResolver(key)) return;

        if (headerFocused) {
            handleHeaderKey(key, t);
        } else {
            handleListKey(key, t, ch);
        }
    }

    private boolean dispatchViaResolver(KeyStroke key) {
        KeystrokeParser.Keystroke keystroke = LanternaKeyAdapter.toKeystroke(key);
        if (keystroke == null) return false;
        KeybindingResolver.ChordResolveResult result = keybindingResolver.resolveChord(
            List.of("Help", "Tabs", "Global"), keystroke, pendingChord);
        return switch (result) {
            case KeybindingResolver.ChordResolveResult.ChordMatch(String action) -> {
                pendingChord = null;
                yield dispatchAction(action);
            }
            case KeybindingResolver.ChordResolveResult.ChordUnbound() -> {
                pendingChord = null;
                yield true;
            }
            case KeybindingResolver.ChordResolveResult.ChordStarted(KeystrokeParser.Chord pending) -> {
                pendingChord = pending;
                yield true;
            }
            case KeybindingResolver.ChordResolveResult.ChordCancelled() -> {
                pendingChord = null;
                yield true;
            }
            case KeybindingResolver.ChordResolveResult.ChordNone() -> {
                pendingChord = null;
                yield false;
            }
        };
    }

    private boolean dispatchAction(String action) {
        return switch (action) {
            case "help:dismiss", "app:interrupt", "app:exit" -> {
                close();
                yield true;
            }
            case "tabs:previous" -> {
                if (headerFocused) switchTab(-1);
                yield true;
            }
            case "tabs:next" -> {
                if (headerFocused) switchTab(1);
                yield true;
            }
            default -> false;
        };
    }


    private void handleHeaderKey(KeyStroke key, KeyType t) {
        if (t == KeyType.ARROW_LEFT || t == KeyType.REVERSE_TAB
                || (t == KeyType.TAB && key.isShiftDown())) {
            switchTab(-1);
            return;
        }
        if (t == KeyType.ARROW_RIGHT || t == KeyType.TAB) {
            switchTab(1);
            return;
        }
        if (t == KeyType.ARROW_DOWN && !currentList().isEmpty()) {

            // tab's content opted in — General never does, and an empty

            // "↓ with no onUpFromFirstItem to recover" trap, see the
            // useTabHeaderFocus docstring).
            headerFocused = false;
            invalidate();
        }
    }


    private void handleListKey(KeyStroke key, KeyType t, Character ch) {
        boolean up = t == KeyType.ARROW_UP;
        boolean down = t == KeyType.ARROW_DOWN;
        if (t == KeyType.CHARACTER && ch != null) {
            // In this block t is CHARACTER, so up/down are still false (seeded
            // from ARROW_UP/ARROW_DOWN above) — plain '=', not '|='.
            if (key.isCtrlDown()) {
                up = ch == 'p';
                down = ch == 'n';
            } else {
                up = ch == 'k';
                down = ch == 'j';
            }
        }
        if (up) {
            if (selectedIdx == 0) {

                headerFocused = true;
                invalidate();
            } else {
                moveFocus(-1);
            }
            return;
        }
        if (down) {
            moveFocus(1);
        }
        // Enter/other keys: disableSelection — browse only, already consumed.
    }

    private void switchTab(int delta) {
        Tab[] tabs = Tab.values();
        selectedTab = tabs[InlineOverlay.cycleIndex(selectedTab.ordinal(), delta, tabs.length)];
        headerFocused = true;
        selectedIdx = 0;
        visibleFrom = 0;
        invalidate();
        if (isWindows) tabSwitchRefresh.run();
    }

    @Explanation("Windows console repaint after a help-tab switch can retain stale blank cells")
    private void requestCompleteRefresh() {
        var textGui = getTextGUI();
        if (textGui == null) return;
        textGui.getGUIThread().invokeLater(() -> {
            try {
                textGui.updateScreen();
                textGui.getScreen().refresh(Screen.RefreshType.COMPLETE);
            } catch (Exception _) {
                // The terminal may be closing while the queued repaint runs.
            }
        });
    }

    private void moveFocus(int delta) {
        List<CommandEntry> list = currentList();
        if (list.isEmpty()) return;
        selectedIdx = InlineOverlay.cycleIndex(selectedIdx, delta, list.size());
        adjustWindow();
        invalidate();
    }

    /** Keep the focused command inside the visibleCount scroll window. */
    private void adjustWindow() {
        int count = visibleCount();
        if (selectedIdx < visibleFrom) {
            visibleFrom = selectedIdx;
        } else if (selectedIdx >= visibleFrom + count) {
            visibleFrom = selectedIdx - count + 1;
        }
    }

    private synchronized void close() {
        if (!active) return;
        Runnable cb = onClose;
        active = false;
        onClose = null;
        invalidate();
        if (cb != null) cb.run();
    }

    private List<CommandEntry> currentList() {
        return switch (selectedTab) {
            case GENERAL -> List.of();
            case COMMANDS -> builtinCommands;
            case CUSTOM_COMMANDS -> customCommands;
        };
    }


    int visibleCount() {
        return Math.max(1, (termRows - 10) / 2);
    }

    private static KeybindingResolver defaultResolver() {
        return KeybindingResolver.defaultResolver();
    }

    private static ShortcutLabels defaultShortcutLabels() {
        return ShortcutLabels.from(defaultResolver(), false);
    }

    // ── layout ───────────────────────────────────────────────────────────────

    private int visibleTo() {
        return Math.min(currentList().size(), visibleFrom + visibleCount());
    }

    /**
     * Row layout. Chrome: blank (Pane paddingTop) · divider · title+tabs ·
     * blank (Tabs content marginTop). Then the selected tab's content, then
     * the shared footer: blank · docs link · blank · "esc to cancel".
     */
    private int totalRows(int columns) {
        return 4 + tabContentRows(columns) + 4;
    }

    private boolean compactCommandsLayout(int columns) {
        return selectedTab != Tab.GENERAL && totalRows(columns) > termRows;
    }

    private int tabContentRows(int columns) {
        return switch (selectedTab) {
            // Wrapped intro · "Shortcuts" · independently wrapped columns.
            case GENERAL -> wrapWords(INTRO, contentWidth(columns)).size()
                + 1 + shortcutTableRows(columns);
            case COMMANDS, CUSTOM_COMMANDS -> {
                List<CommandEntry> list = currentList();
                if (list.isEmpty()) {
                    // custom-commands: the emptyMessage line replaces everything;

                    yield 2 + (selectedTab == Tab.CUSTOM_COMMANDS ? 1 : 2);
                }
                int optionRows = 0;
                for (int i = visibleFrom; i < visibleTo(); i++) {
                    optionRows += rowsFor(list.get(i));
                }
                yield 2 + 2 + optionRows; // title · blank (marginTop) · options
            }
        };
    }

    private int shortcutTableRows(int columns) {
        ShortcutColumns layout = shortcutColumns(columns);
        return Math.max(wrappedColumn(shortcutLabels.column1(), layout.firstWidth()).size(),
            Math.max(wrappedColumn(shortcutLabels.column2(), layout.secondWidth()).size(),
                wrappedColumn(shortcutLabels.column3(isWindows), layout.thirdWidth()).size()));
    }

    private int terminalColumns() {
        try {
            return Math.max(1, terminalColumnsSupplier.getAsInt());
        } catch (RuntimeException _) {
            return 80;
        }
    }

    private static int contentWidth(int columns) {
        return Math.max(1, columns - LEFT_PAD * 2);
    }

    private static ShortcutColumns shortcutColumns(int columns) {
        int tableWidth = contentWidth(columns);
        int gap = tableWidth >= 9 ? COL_GAP : 0;
        int available = Math.max(3, tableWidth - gap * 2);
        int firstWidth = Math.max(1, Math.min(COL1_WIDTH, available / 4));
        int remaining = Math.max(2, available - firstWidth);
        int secondWidth = Math.max(1, Math.min(COL2_WIDTH, remaining / 2));
        int thirdWidth = Math.max(1, remaining - secondWidth);
        int secondX = LEFT_PAD + firstWidth + gap;
        int thirdX = secondX + secondWidth + gap;
        return new ShortcutColumns(
            LEFT_PAD, secondX, thirdX, firstWidth, secondWidth, thirdWidth);
    }

    private static List<String> wrappedColumn(List<String> labels, int boxWidth) {
        int textWidth = Math.max(1, boxWidth - 1);
        List<String> rows = new ArrayList<>();
        for (String label : labels) rows.addAll(wrapWords(label, textWidth));
        return List.copyOf(rows);
    }

    private static List<String> wrapWords(String text, int width) {
        if (StringUtils.isBlank(text) || width <= 0) return List.of();
        List<String> rows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (FormatUtils.displayWidth(candidate) <= width) {
                current.setLength(0);
                current.append(candidate);
                continue;
            }
            if (!current.isEmpty()) {
                rows.add(current.toString());
                current.setLength(0);
            }
            List<String> hardWrapped = FormatUtils.wrapText(word, width);
            for (int index = 0; index + 1 < hardWrapped.size(); index++) {
                rows.add(hardWrapped.get(index));
            }
            if (!hardWrapped.isEmpty()) current.append(hardWrapped.getLast());
        }
        if (!current.isEmpty()) rows.add(current.toString());
        return List.copyOf(rows);
    }

    private record ShortcutColumns(
        int firstX,
        int secondX,
        int thirdX,
        int firstWidth,
        int secondWidth,
        int thirdWidth
    ) {}

    private static int rowsFor(CommandEntry entry) {
        return StringUtils.isNotEmpty(entry.description()) ? 2 : 1;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── test accessors (package-private) ─────────────────────────────────────

    Tab selectedTab() { return selectedTab; }
    boolean headerFocused() { return headerFocused; }
    int selectedIdx() { return selectedIdx; }
    int visibleFrom() { return visibleFrom; }
    ShortcutLabels shortcutLabels() { return shortcutLabels; }

    // ── renderer ─────────────────────────────────────────────────────────────

    private final class HelpArea extends AbstractComponent<HelpArea> {
        @Override protected ComponentRenderer<HelpArea> createDefaultRenderer() {
            return new HelpRenderer();
        }
    }

    private final class HelpRenderer implements ComponentRenderer<HelpArea> {

        @Override
        public TerminalSize getPreferredSize(HelpArea c) {
            int columns = terminalColumns();
            int rows = compactCommandsLayout(columns) ? termRows : totalRows(columns);
            return new TerminalSize(columns, active ? rows : 0);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, HelpArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();

            if (compactCommandsLayout(cols)) {
                int row = switch (selectedTab) {
                    case COMMANDS -> drawCommands(g, 1, cols,
                        "Browse default commands", null);
                    case CUSTOM_COMMANDS -> drawCommands(g, 1, cols,
                        "Browse custom commands", "No custom commands found");
                    case GENERAL -> throw new IllegalStateException("general tab is never compact");
                };
                drawFooter(g, row, cols);
                return;
            }

            // Row 0: blank — Pane paddingTop.
            // Row 1: divider — <Pane color="professionalBlue"> forwards color
            // to <Divider>, so this line is professionalBlue, not the generic
            // dim divider.
            g.setForegroundColor(LanternaTheme.professionalBlue());
            g.putString(0, 1, "─".repeat(Math.max(0, cols)));

            // Row 2: Tabs header — bold professionalBlue title + tab labels.
            drawHeader(g);

            // Row 3: blank — Tabs content marginTop.
            int row = switch (selectedTab) {
                case GENERAL -> drawGeneral(g, 4);
                case COMMANDS -> drawCommands(g, 4, cols,
                    "Browse default commands", null);
                case CUSTOM_COMMANDS -> drawCommands(g, 4, cols,
                    "Browse custom commands", "No custom commands found");
            };

            drawFooter(g, row, cols);
        }

        /** Footer: blank · docs link · blank · dim italic "esc to cancel". */
        private void drawFooter(TextGUIGraphics g, int row, int cols) {
            g.setForegroundColor(LanternaTheme.inputText());
            putClipped(g, LEFT_PAD, row + 1, cols - LEFT_PAD, DOCS_LINE);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            putClipped(g, LEFT_PAD, row + 3, cols - LEFT_PAD,
                shortcutLabels.dismiss() + " to cancel");
            g.disableModifiers(SGR.ITALIC);
        }

        private void drawHeader(TextGUIGraphics g) {
            int col = LEFT_PAD;
            int cols = g.getSize().getColumns();
            String title = "Help";
            g.setForegroundColor(LanternaTheme.professionalBlue());
            g.enableModifiers(SGR.BOLD);
            putClipped(g, col, 2, cols - col, title);
            g.disableModifiers(SGR.BOLD);
            col += FormatUtils.displayWidth(title) + 1;

            for (Tab tab : Tab.values()) {
                if (col >= cols) break;
                boolean isCurrent = tab == selectedTab;
                String label = " " + tabLabel(tab) + " ";
                if (isCurrent && headerFocused) {
                    g.setBackgroundColor(LanternaTheme.professionalBlue());
                    g.setForegroundColor(LanternaTheme.inverseText());
                } else {
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(isCurrent
                        ? LanternaTheme.inputText() : LanternaTheme.welcomeDim());
                }
                if (isCurrent) g.enableModifiers(SGR.BOLD);
                putClipped(g, col, 2, cols - col, label);
                g.disableModifiers(SGR.BOLD);
                col += FormatUtils.displayWidth(label) + 1;
            }
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);
        }

        /** General tab: wrapped intro · bold "Shortcuts" · three independently wrapped columns. */
        private int drawGeneral(TextGUIGraphics g, int row) {
            int cols = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.inputText());
            for (String line : wrapWords(INTRO, contentWidth(cols))) {
                putClipped(g, LEFT_PAD, row++, cols - LEFT_PAD, line);
            }
            g.enableModifiers(SGR.BOLD);
            putClipped(g, LEFT_PAD, row++, cols - LEFT_PAD, "Shortcuts");
            g.disableModifiers(SGR.BOLD);

            ShortcutColumns layout = shortcutColumns(cols);
            List<String> col1 = wrappedColumn(
                shortcutLabels.column1(), layout.firstWidth());
            List<String> col2 = wrappedColumn(
                shortcutLabels.column2(), layout.secondWidth());
            List<String> col3 = wrappedColumn(
                shortcutLabels.column3(isWindows), layout.thirdWidth());
            g.setForegroundColor(LanternaTheme.welcomeDim());
            int rows = Math.max(col1.size(), Math.max(col2.size(), col3.size()));
            for (int i = 0; i < rows; i++) {
                if (i < col1.size()) {
                    putClipped(g, layout.firstX(), row + i,
                        layout.firstWidth(), col1.get(i));
                }
                if (i < col2.size()) {
                    putClipped(g, layout.secondX(), row + i,
                        layout.secondWidth(), col2.get(i));
                }
                if (i < col3.size()) {
                    putClipped(g, layout.thirdX(), row + i,
                        layout.thirdWidth(), col3.get(i));
                }
            }
            row += rows;
            return row;
        }

        /**
         * Commands tab: paddingY blank · title · gap · options (each: pointer + /name row, then indented
         * dim description row) · paddingY blank.
         */
        private int drawCommands(TextGUIGraphics g, int row, int cols,
                                 String title, String emptyMessage) {
            List<CommandEntry> list = currentList();
            row++; // paddingTop
            if (list.isEmpty() && emptyMessage != null) {
                g.setForegroundColor(LanternaTheme.welcomeDim());
                putClipped(g, LEFT_PAD, row++, cols - LEFT_PAD, emptyMessage);
                row++; // paddingBottom
                return row;
            }

            g.setForegroundColor(LanternaTheme.inputText());
            putClipped(g, LEFT_PAD, row++, cols - LEFT_PAD, title);
            row++; // Box marginTop={1}

            int to = visibleTo();
            boolean moreAbove = visibleFrom > 0;
            boolean moreBelow = to < list.size();
            int maxWidth = Math.max(1, cols - 10);
            for (int i = visibleFrom; i < to; i++) {
                CommandEntry entry = list.get(i);

                // the tab header owns the keys.
                boolean focused = !headerFocused && i == selectedIdx;
                String pointer = focused ? "❯ "
                    : (i == visibleFrom && moreAbove) ? "↑ "
                    : (i == to - 1 && moreBelow) ? "↓ "
                    : "  ";
                g.setForegroundColor(focused
                    ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
                putClipped(g, LEFT_PAD, row, cols - LEFT_PAD, pointer);

                g.setForegroundColor(focused
                    ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                putClipped(g, LEFT_PAD + 2, row, cols - LEFT_PAD - 2,
                    "/" + entry.name());
                row++;

                if (rowsFor(entry) == 2) {

                    // paddingLeft={4}, dim (suggestion-tinted when focused).
                    g.setForegroundColor(focused
                        ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
                    putClipped(g, LEFT_PAD + 4, row, cols - LEFT_PAD - 4,
                        CopyPickerDialog.truncateLine(entry.description(), maxWidth));
                    row++;
                }
            }
            row++; // paddingBottom
            return row;
        }

        private void putClipped(
                TextGUIGraphics g, int column, int row, int maxWidth, String value) {
            if (row < 0 || row >= g.getSize().getRows()) return;
            int available = Math.min(maxWidth, g.getSize().getColumns() - column);
            if (available <= 0 || StringUtils.isEmpty(value)) return;
            g.putString(column, row, FormatUtils.truncateNoEllipsis(value, available));
        }
    }

    private static String tabLabel(Tab tab) {
        return switch (tab) {
            case GENERAL -> "General";
            case COMMANDS -> "Commands";
            case CUSTOM_COMMANDS -> "Custom commands";
        };
    }
}

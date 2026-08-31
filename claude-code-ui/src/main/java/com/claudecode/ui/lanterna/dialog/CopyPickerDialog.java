package com.claudecode.ui.lanterna.dialog;

import com.claudecode.commands.impl.terminal.CopyCommand;
import com.claudecode.commands.impl.terminal.CopyCommand.CodeBlock;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.InputPanel;

/**
 * Inline {@code /copy} content picker — sits above {@link InputPanel} in the SmartLayout stack,
 * occupying zero rows when idle.
 */
public final class CopyPickerDialog extends Panel implements InlineOverlay {


    private static final int VISIBLE_OPTIONS = 5;
    private static final int LEFT_PAD = 2;
    private static final int LABEL_MAX_WIDTH = 60;

    /**
     * The user's choice. {@code blockIndex} is -1 for the full response;
     * {@code always} marks the "Always copy full response" option (full text
     * + preference persistence); {@code writeOnly} is the {@code w} shortcut
     * (file only, no clipboard).
     */
    public record CopySelection(int blockIndex, boolean always, boolean writeOnly) {}

    record Option(String label, String description, int blockIndex, boolean always) {}

    private boolean active;
    private int selectedIdx;
    private int visibleFrom;
    private List<Option> options = List.of();
    private Consumer<CopySelection> onResult;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public CopyPickerDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        PickerArea area = new PickerArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activate the picker. Must run on the GUI thread.
     *
     * @param fullText   the selected assistant response (full markdown)
     * @param codeBlocks extracted code blocks (non-empty — the caller skips
     *                   the picker otherwise)
     * @param onResult   invoked with the selection on Enter/w/digit, or
     *                   {@code null} on Esc / Ctrl+C/D. Called on the GUI
     *                   thread after the dialog hides.
     */
    public synchronized void show(String fullText, List<CodeBlock> codeBlocks,
                                  Consumer<CopySelection> onResult) {
        this.options = buildOptions(fullText, codeBlocks);
        this.onResult = onResult;
        this.selectedIdx = 0;
        this.visibleFrom = 0;
        this.active = true;
        invalidate();
    }


    static List<Option> buildOptions(String fullText, List<CodeBlock> codeBlocks) {
        List<Option> opts = new ArrayList<>();
        opts.add(new Option(
            "Full response",
            fullText.length() + " chars, " + countLines(fullText) + " lines",
            -1, false));
        for (int i = 0; i < codeBlocks.size(); i++) {
            CodeBlock block = codeBlocks.get(i);
            int blockLines = countLines(block.code());
            StringBuilder desc = new StringBuilder();
            if (block.lang() != null) desc.append(block.lang());
            if (blockLines > 1) {
                if (!desc.isEmpty()) desc.append(", ");
                desc.append(blockLines).append(" lines");
            }
            opts.add(new Option(
                truncateLine(block.code(), LABEL_MAX_WIDTH),
                desc.isEmpty() ? null : desc.toString(),
                i, false));
        }
        opts.add(new Option(
            "Always copy full response",
            "Skip this picker in the future (revert via /config)",
            -1, true));
        return opts;
    }


    public static String truncateLine(String text, int maxLen) {
        String firstLine = text.split("\n", 2)[0];
        if (TerminalTextUtils.getColumnWidth(firstLine) <= maxLen) {
            return firstLine;
        }
        StringBuilder result = new StringBuilder();
        int width = 0;
        int targetWidth = maxLen - 1;
        for (int i = 0; i < firstLine.length(); ) {
            int cp = firstLine.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int charWidth = TerminalTextUtils.getColumnWidth(ch);
            if (width + charWidth > targetWidth) break;
            result.append(ch);
            width += charWidth;
            i += Character.charCount(cp);
        }
        return result + "…";
    }

    private static int countLines(String text) {
        return CopyCommand.countLines(text);
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.PASTE) {
            // No text field here — swallow so the paste can't leak into the
            // main input behind this overlay.
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null
                && (key.getCharacter() == 'c' || key.getCharacter() == 'd')) {
            resolve(null);
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action) {
            dispatchSelectAction(action.value());
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_UP) {
            moveFocus(-1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ARROW_DOWN) {
            moveFocus(1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.PAGE_UP) {
            jumpFocus(0);
            deliver.set(false);
            return;
        }
        if (t == KeyType.PAGE_DOWN) {
            jumpFocus(options.size() - 1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            confirm(selectedIdx, false);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            resolve(null);
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (key.isCtrlDown()) {
                if (ch == 'c' || ch == 'd') {
                    resolve(null);
                    deliver.set(false);
                } else if (ch == 'n') {
                    moveFocus(1);
                    deliver.set(false);
                } else if (ch == 'p') {
                    moveFocus(-1);
                    deliver.set(false);
                }
                return;
            }
            if (ch == 'j') {
                moveFocus(1);
                deliver.set(false);
                return;
            }
            if (ch == 'k') {
                moveFocus(-1);
                deliver.set(false);
                return;
            }
            if (ch == 'w') {

                confirm(selectedIdx, true);
                deliver.set(false);
                return;
            }
            if (ch >= '1' && ch <= '9') {
                int idx = ch - '1';
                if (idx < options.size()) {
                    confirm(idx, false);
                    deliver.set(false);
                }
            }
        }
    }

    private void dispatchSelectAction(String action) {
        switch (action) {
            case "select:previous" -> moveFocus(-1);
            case "select:next" -> moveFocus(1);
            case "select:pageUp", "select:first" -> jumpFocus(0);
            case "select:pageDown", "select:last" -> jumpFocus(options.size() - 1);
            case "select:accept" -> confirm(selectedIdx, false);
            case "select:cancel" -> resolve(null);
            default -> { }
        }
    }

    private void moveFocus(int delta) {
        selectedIdx = InlineOverlay.cycleIndex(selectedIdx, delta, options.size());
        adjustWindow();
        invalidate();
    }

    private void jumpFocus(int idx) {
        selectedIdx = idx;
        adjustWindow();
        invalidate();
    }

    /** Keep the focused option inside the 5-row scroll window. */
    private void adjustWindow() {
        if (selectedIdx < visibleFrom) {
            visibleFrom = selectedIdx;
        } else if (selectedIdx >= visibleFrom + VISIBLE_OPTIONS) {
            visibleFrom = selectedIdx - VISIBLE_OPTIONS + 1;
        }
    }

    private void confirm(int idx, boolean writeOnly) {
        Option opt = options.get(idx);
        resolve(new CopySelection(opt.blockIndex(), opt.always(), writeOnly));
    }

    private synchronized void resolve(CopySelection selection) {
        if (!active) return;
        Consumer<CopySelection> cb = onResult;
        hide();
        if (cb != null) cb.accept(selection);
    }

    private synchronized void hide() {
        active = false;
        onResult = null;
        invalidate();
    }

    // ── layout ───────────────────────────────────────────────────────────────

    private List<Option> visibleOptions() {
        int to = Math.min(options.size(), visibleFrom + VISIBLE_OPTIONS);
        return options.subList(visibleFrom, to);
    }

    /**
     * Row layout: blank (Pane paddingTop) · divider · header · blank (gap) ·
     * visible options (label + optional description line each) · blank (gap) ·
     * footer.
     */
    private int contentRows() {
        int optionRows = 0;
        for (Option opt : visibleOptions()) {
            optionRows += opt.description() != null ? 2 : 1;
        }
        return 4 + optionRows + 2;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(50, parent.getColumns()), contentRows());
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ── renderer ─────────────────────────────────────────────────────────────

    private final class PickerArea extends AbstractComponent<PickerArea> {
        @Override protected ComponentRenderer<PickerArea> createDefaultRenderer() {
            return new PickerRenderer();
        }
    }

    private final class PickerRenderer implements ComponentRenderer<PickerArea> {

        @Override
        public TerminalSize getPreferredSize(PickerArea c) {
            return new TerminalSize(LEFT_PAD * 2 + 60, active ? contentRows() : 0);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, PickerArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();


            // Row 1: divider — CopyPicker's <Pane> has no color prop, so this
            // is the generic dim divider (unlike ThemePickerDialog's
            // permission-blue one).
            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 1, "─".repeat(Math.max(0, cols)));


            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 2, "Select content to copy:");




            // (padEnd-aligned), label in suggestion color when focused, and
            // the description on its own line indented past the index column,
            // dim (focused rows tint it suggestion instead).
            int maxIndexWidth = String.valueOf(options.size()).length() + 1; // "N."
            List<Option> visible = visibleOptions();
            boolean moreAbove = visibleFrom > 0;
            boolean moreBelow = visibleFrom + VISIBLE_OPTIONS < options.size();
            int row = 4;
            for (int vi = 0; vi < visible.size(); vi++) {
                int i = visibleFrom + vi;
                Option opt = visible.get(vi);
                boolean isFocused = i == selectedIdx;


                // shouldShowUpArrow/shouldShowDownArrow).
                String pointer = isFocused ? "❯ "
                    : (vi == 0 && moreAbove) ? "↑ "
                    : (vi == visible.size() - 1 && moreBelow) ? "↓ "
                    : "  ";
                g.setForegroundColor(isFocused
                    ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, row, pointer);

                String index = ((i + 1) + ".");
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD + 2, row, index);

                g.setForegroundColor(isFocused
                    ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD + 2 + maxIndexWidth + 1, row, opt.label());
                row++;

                if (opt.description() != null) {
                    g.setForegroundColor(isFocused
                        ? LanternaTheme.suggestion() : LanternaTheme.welcomeDim());
                    g.putString(LEFT_PAD + maxIndexWidth + 4, row, opt.description());
                    row++;
                }
            }


            // KeyboardShortcutHints (no italic on this one, unlike ThemePicker).
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, row + 1, "enter to copy · w to write to file · esc to cancel");
        }
    }
}

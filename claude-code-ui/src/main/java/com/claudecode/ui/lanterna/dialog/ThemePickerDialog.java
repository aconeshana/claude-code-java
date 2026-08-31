package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.input.InputPanel;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.apache.commons.lang3.Strings;

/**
 * Inline theme-picker dialog — sits above {@link InputPanel} in the SmartLayout stack, occupying
 * zero rows when idle.
 */
public final class ThemePickerDialog extends Panel implements InlineOverlay {


    record ThemeOption(String value, String label) {}

    private static final List<ThemeOption> OPTIONS = List.of(
        new ThemeOption("auto",             "Auto (match terminal)"),
        new ThemeOption("dark",             "Dark mode"),
        new ThemeOption("light",            "Light mode"),
        new ThemeOption("dark-daltonized",  "Dark mode (colorblind-friendly)"),
        new ThemeOption("light-daltonized", "Light mode (colorblind-friendly)"),
        new ThemeOption("dark-ansi",        "Dark mode (ANSI colors only)"),
        new ThemeOption("light-ansi",       "Light mode (ANSI colors only)")
    );

    private static final int LEFT_PAD = 2;
    private static final String SUBTITLE = "Choose the text style that looks best with your terminal";

    private boolean active;
    private int selectedIdx;
    private int originalIdx;
    private String originalName;
    private Consumer<String> onPreview;
    private Consumer<String> onResult;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private BooleanSupplier syntaxDisabledReader;
    private Consumer<Boolean> syntaxDisabledWriter;
    private boolean syntaxHighlightingDisabled;

    public ThemePickerDialog() {
        this(() -> false, _ -> { });
    }

    ThemePickerDialog(BooleanSupplier syntaxDisabledReader,
                      Consumer<Boolean> syntaxDisabledWriter) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.syntaxDisabledReader = syntaxDisabledReader;
        this.syntaxDisabledWriter = syntaxDisabledWriter;
        PickerArea area = new PickerArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setSyntaxHighlightingAccess(BooleanSupplier reader,
                                            Consumer<Boolean> writer) {
        syntaxDisabledReader = reader != null ? reader : () -> false;
        syntaxDisabledWriter = writer != null ? writer : _ -> { };
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activate the picker with {@code currentThemeName} pre-selected.
     */
    public synchronized void show(String currentThemeName, Consumer<String> onPreview, Consumer<String> onResult) {
        this.originalName = resolveOrDefault(currentThemeName);
        this.onPreview = onPreview;
        this.onResult = onResult;
        this.originalIdx = findInitialIndex(this.originalName);
        this.selectedIdx = this.originalIdx;
        this.syntaxHighlightingDisabled = syntaxDisabledReader.getAsBoolean();
        this.active = true;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    /**
     * Intercept a key while active.
     */
    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null
                && (key.getCharacter() == 'c' || key.getCharacter() == 'd')) {
            cancel();
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result themeResolved = keybindings.resolve("ThemePicker", key);
        if (themeResolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (themeResolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            if (Strings.CS.equals("theme:toggleSyntaxHighlighting", value)) toggleSyntaxHighlighting();
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            dispatchSelectAction(value);
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
            jumpFocus(OPTIONS.size() - 1);
            deliver.set(false);
            return;
        }
        if (t == KeyType.ENTER) {
            resolve(OPTIONS.get(selectedIdx).value());
            deliver.set(false);
            return;
        }
        if (t == KeyType.ESCAPE) {
            cancel();
            deliver.set(false);
            return;
        }
        if (t == KeyType.CHARACTER && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (key.isCtrlDown()) {
                if (ch == 'c' || ch == 'd') {
                    cancel();
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
            if (ch >= '1' && ch <= '9') {
                int idx = (ch - '1');
                if (idx < OPTIONS.size()) {
                    resolve(OPTIONS.get(idx).value());
                    deliver.set(false);
                }
            }
        }
    }

    private void toggleSyntaxHighlighting() {
        syntaxHighlightingDisabled = !syntaxHighlightingDisabled;
        syntaxDisabledWriter.accept(syntaxHighlightingDisabled);
        invalidate();
    }

    boolean syntaxHighlightingDisabled() {
        return syntaxHighlightingDisabled;
    }

    private void dispatchSelectAction(String action) {
        switch (action) {
            case "select:previous" -> moveFocus(-1);
            case "select:next" -> moveFocus(1);
            case "select:pageUp", "select:first" -> jumpFocus(0);
            case "select:pageDown", "select:last" -> jumpFocus(OPTIONS.size() - 1);
            case "select:accept" -> resolve(OPTIONS.get(selectedIdx).value());
            case "select:cancel" -> cancel();
            default -> { }
        }
    }

    private void moveFocus(int delta) {
        selectedIdx = InlineOverlay.cycleIndex(selectedIdx, delta, OPTIONS.size());
        invalidate();
        preview(OPTIONS.get(selectedIdx).value());
    }

    /** PageUp/PageDown — jump straight to {@code idx}, no wraparound. */
    private void jumpFocus(int idx) {
        selectedIdx = idx;
        invalidate();
        preview(OPTIONS.get(selectedIdx).value());
    }

    private int findInitialIndex(String name) {
        for (int i = 0; i < OPTIONS.size(); i++) {
            if (OPTIONS.get(i).value().equals(name)) return i;
        }
// Unmatched name (e.g.
        return 0;
    }

    private static String resolveOrDefault(String name) {
        if (name != null) {
            for (ThemeOption o : OPTIONS) {
                if (o.value().equals(name)) return name;
            }
        }
        return OPTIONS.getFirst().value();
    }

    private void preview(String value) {
        Consumer<String> cb = onPreview;
        if (cb != null) cb.accept(value);
    }

    private synchronized void resolve(String value) {
        if (!active) return;
        Consumer<String> cb = onResult;
        hide();
        if (cb != null) cb.accept(value);
    }

    /** Esc / Ctrl+C/D — revert the live preview to the original theme, then resolve null. */
    private synchronized void cancel() {
        if (!active) return;
        preview(originalName);
        resolve(null);
    }

    private synchronized void hide() {
        active = false;
        onPreview = null;
        onResult = null;
        invalidate();
    }

    /** Collapse to zero size while idle — same pattern as {@link ModelPickerDialog}. */
    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        int rows = 2 + OPTIONS.size() + 11;
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(50, parent.getColumns()), rows);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class PickerArea extends AbstractComponent<PickerArea> {
        @Override protected ComponentRenderer<PickerArea> createDefaultRenderer() {
            return new PickerRenderer();
        }
    }

    private final class PickerRenderer implements ComponentRenderer<PickerArea> {

        @Override
        public TerminalSize getPreferredSize(PickerArea c) {
            return new TerminalSize(LEFT_PAD * 2 + 50, 2 + OPTIONS.size() + 11);
        }

        @Override
        public void drawComponent(TextGUIGraphics g, PickerArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();



            // g.fill(' ') above.


            // and Pane forwards `color` straight to <Divider color={color} />, which
            // renders non-dim `color` text — i.e. this line is permission-blue, not
            // the generic dim divider color other panes (without a Pane `color` prop) use.
            g.setForegroundColor(LanternaTheme.permission());
            g.putString(0, 1, "─".repeat(Math.max(0, cols)));


            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 2, "Theme");
            g.disableModifiers(SGR.BOLD);


            g.setForegroundColor(LanternaTheme.inputText());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 3, SUBTITLE);
            g.disableModifiers(SGR.BOLD);

            // Row 4: blank


            // states — isFocused (arrow cursor; ❯ pointer, "suggestion" text color)
            // and isSelected (the value the picker opened with, i.e. state.value,

            // trailing "✓"). Both can be true at once (initial row, before any
            // navigation) — the pointer and tick then appear on the same row.

            // not a dim/inactive color.
            for (int i = 0; i < OPTIONS.size(); i++) {
                int row = 5 + i;
                ThemeOption opt = OPTIONS.get(i);
                boolean isFocused = i == selectedIdx;
                boolean isSelected = i == originalIdx;

                g.setForegroundColor(isFocused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, isFocused ? "❯ " : "  ");

                g.setForegroundColor(isSelected ? LanternaTheme.toolSuccess()
                    : isFocused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                String numberPrefix = (i + 1) + ". ";
                g.putString(LEFT_PAD + 2, row, numberPrefix + opt.label() + (isSelected ? " ✓" : ""));
            }

            int demoTop = 5 + OPTIONS.size();
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(0, demoTop, "┄".repeat(Math.max(0, cols)));
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, demoTop + 1, "  function greet() {");
            g.setForegroundColor(LanternaTheme.toolError());
            g.putString(LEFT_PAD, demoTop + 2, "-   console.log(\"Hello, World!\");");
            g.setForegroundColor(LanternaTheme.toolSuccess());
            g.putString(LEFT_PAD, demoTop + 3, "+   console.log(\"Hello, Claude!\");");
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, demoTop + 4, "  }");
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(0, demoTop + 5, "┄".repeat(Math.max(0, cols)));
            g.putString(LEFT_PAD, demoTop + 6,
                syntaxHighlightingDisabled
                    ? "Syntax highlighting disabled (ctrl+t to enable)"
                    : "Syntax highlighting enabled (ctrl+t to disable)");


            // KeyboardShortcutHint("Enter","select") + KeyboardShortcutHint("Esc","cancel")}</Text>,
            // i.e. "Enter to select · Esc to cancel" (no arrow-key hint). The Ctrl-C/Ctrl-D
            // "Press X again to exit" pending-state text is NOT reproduced here — that requires
            // the per-dialog double-press timer this dialog deliberately doesn't implement
            // (see class Javadoc / coverage.yml).
            int footerRow = demoTop + 8;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, footerRow, "Enter to select · Esc to cancel");
            g.disableModifiers(SGR.ITALIC);
        }
    }
}

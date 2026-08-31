package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.prompt.OutputStylePresets;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.outputstyle.OutputStyleCatalog;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Managed output-style picker embedded in {@code /config}.
 */
public final class OutputStylePickerDialog extends Panel implements InlineOverlay {

    private record Option(String value, String label, String description) {}

    private static final List<Option> BUILT_IN_OPTIONS = List.of(
        new Option("default", "Default",
            "Claude completes coding tasks efficiently and provides concise responses"),
        new Option("Explanatory", OutputStylePresets.EXPLANATORY.name(),
            OutputStylePresets.EXPLANATORY.description()),
        new Option("Learning", OutputStylePresets.LEARNING.name(),
            OutputStylePresets.LEARNING.description())
    );

    private static final int LEFT_PAD = 2;
    private static final String SUBTITLE = "This changes how Claude Code communicates with you";

    private final OutputStyleCatalog catalog;
    private final Supplier<Path> cwdSupplier;

    private boolean active;
    private int focusedIndex;
    private int originalIndex;
    private int scrollOffset;
    private List<Option> options = BUILT_IN_OPTIONS;
    private Consumer<String> onResult;
    private Consumer<Runnable> guiInvoker;
    private long loadGeneration;
    private boolean loading;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();

    public OutputStylePickerDialog() {
        this(OutputStyleCatalog.builtIns(),
            () -> Path.of(System.getProperty("user.dir")));
    }

    public OutputStylePickerDialog(OutputStyleCatalog catalog, Path cwd) {
        this(catalog, () -> cwd);
    }

    public static OutputStylePickerDialog liveCwd(OutputStyleCatalog catalog) {
        return new OutputStylePickerDialog(catalog,
            () -> Path.of(System.getProperty("user.dir")));
    }

    private OutputStylePickerDialog(OutputStyleCatalog catalog,
                                    Supplier<Path> cwdSupplier) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.catalog = catalog != null ? catalog : OutputStyleCatalog.builtIns();
        this.cwdSupplier = cwdSupplier;
        PickerArea area = new PickerArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public synchronized void setGuiInvoker(Consumer<Runnable> guiInvoker) {
        this.guiInvoker = guiInvoker;
    }

    /** Opens with the current style selected; unknown names fall back to Default. */
    public synchronized void show(String currentStyle, Consumer<String> onResult) {
        this.onResult = onResult;
        this.active = true;
        long generation = ++loadGeneration;
        if (guiInvoker == null) {
            applyLoadedOptions(generation, currentStyle, loadOptions());
            return;
        }
        this.loading = true;
        this.options = BUILT_IN_OPTIONS;
        this.originalIndex = findIndex(currentStyle);
        this.focusedIndex = originalIndex;
        this.scrollOffset = Math.max(0, focusedIndex - 9);
        invalidate();
        Thread.ofVirtual().name("output-style-picker-load").start(() -> {
            List<Option> loaded = loadOptions();
            guiInvoker.accept(() -> applyLoadedOptions(generation, currentStyle, loaded));
        });
    }

    private synchronized void applyLoadedOptions(long generation, String currentStyle,
                                                  List<Option> loaded) {
        if (!active || generation != loadGeneration) return;
        this.options = loaded;
        this.originalIndex = findIndex(currentStyle);
        this.focusedIndex = originalIndex;
        this.scrollOffset = Math.max(0, focusedIndex - 9);
        this.loading = false;
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType type = key.getKeyType();
        if (loading) {
            if (type == KeyType.ESCAPE || (type == KeyType.CHARACTER && key.isCtrlDown()
                    && key.getCharacter() != null
                    && (key.getCharacter() == 'c' || key.getCharacter() == 'd'))) {
                resolve(null);
            }
            deliver.set(false);
            return;
        }
        if (type == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null
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
        if (type == KeyType.ARROW_UP) {
            move(-1);
            deliver.set(false);
            return;
        }
        if (type == KeyType.ARROW_DOWN) {
            move(1);
            deliver.set(false);
            return;
        }
        if (type == KeyType.PAGE_UP) {
            focusedIndex = 0;
            ensureVisible();
            invalidate();
            deliver.set(false);
            return;
        }
        if (type == KeyType.PAGE_DOWN) {
            focusedIndex = options.size() - 1;
            ensureVisible();
            invalidate();
            deliver.set(false);
            return;
        }
        if (type == KeyType.ENTER) {
            resolve(options.get(focusedIndex).value());
            deliver.set(false);
            return;
        }
        if (type == KeyType.ESCAPE) {
            resolve(null);
            deliver.set(false);
            return;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (key.isCtrlDown()) {
                if (ch == 'c' || ch == 'd') resolve(null);
                else if (ch == 'n') move(1);
                else if (ch == 'p') move(-1);
                else return;
                deliver.set(false);
                return;
            }
            if (ch == 'j') {
                move(1);
                deliver.set(false);
                return;
            }
            if (ch == 'k') {
                move(-1);
                deliver.set(false);
                return;
            }
            if (ch >= '1' && ch <= '9') {
                int index = ch - '1';
                if (index < options.size()) {
                    resolve(options.get(index).value());
                    deliver.set(false);
                }
            }
        }
    }

    private void dispatchSelectAction(String action) {
        switch (action) {
            case "select:previous" -> move(-1);
            case "select:next" -> move(1);
            case "select:pageUp", "select:first" -> jumpTo(0);
            case "select:pageDown", "select:last" -> jumpTo(options.size() - 1);
            case "select:accept" -> resolve(options.get(focusedIndex).value());
            case "select:cancel" -> resolve(null);
            default -> { }
        }
    }

    private void move(int delta) {
        focusedIndex = InlineOverlay.cycleIndex(focusedIndex, delta, options.size());
        ensureVisible();
        invalidate();
    }

    private int findIndex(String value) {
        if (value != null) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).value().equals(value)) return i;
            }
        }
        return 0;
    }

    private List<Option> loadOptions() {
        try {
            Path cwd = cwdSupplier.get();
            List<OutputStyleCatalog.Entry> entries = catalog.list(
                cwd != null ? cwd : Path.of(System.getProperty("user.dir")));
            if (entries != null && !entries.isEmpty()) {
                List<Option> loaded = new ArrayList<>(entries.size());
                for (OutputStyleCatalog.Entry entry : entries) {
                    loaded.add(new Option(entry.value(), entry.label(), entry.description()));
                }
                return List.copyOf(loaded);
            }
        } catch (RuntimeException _) {

        }
        return BUILT_IN_OPTIONS;
    }

    private void jumpTo(int index) {
        focusedIndex = index;
        ensureVisible();
        invalidate();
    }

    private void ensureVisible() {
        int visible = Math.min(10, options.size());
        if (focusedIndex < scrollOffset) scrollOffset = focusedIndex;
        if (focusedIndex >= scrollOffset + visible) {
            scrollOffset = focusedIndex - visible + 1;
        }
    }

    private synchronized void resolve(String value) {
        if (!active) return;
        Consumer<String> callback = onResult;
        loadGeneration++;
        loading = false;
        active = false;
        onResult = null;
        invalidate();
        if (callback != null) callback.accept(value);
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        int height = loading ? 8 : 6 + Math.min(10, options.size()) * 2;
        return new TerminalSize(Math.max(72, parent.getColumns()), height);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    private final class PickerArea extends AbstractComponent<PickerArea> {
        @Override protected ComponentRenderer<PickerArea> createDefaultRenderer() {
            return new PickerRenderer();
        }
    }

    private final class PickerRenderer implements ComponentRenderer<PickerArea> {
        @Override public TerminalSize getPreferredSize(PickerArea component) {
            return new TerminalSize(72, loading ? 8 : 6 + Math.min(10, options.size()) * 2);
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, PickerArea component) {
            if (!active) return;
            graphics.fill(' ');
            int columns = graphics.getSize().getColumns();

            graphics.setForegroundColor(LanternaTheme.permission());
            graphics.putString(0, 1, "─".repeat(Math.max(0, columns)));
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(LEFT_PAD, 2, "Preferred output style");
            graphics.disableModifiers(SGR.BOLD);

            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(LEFT_PAD, 3, SUBTITLE);

            if (loading) {
                graphics.putString(LEFT_PAD, 5, "Loading output styles…");
                return;
            }

            int visible = Math.min(10, options.size());
            int end = Math.min(options.size(), scrollOffset + visible);
            for (int i = scrollOffset; i < end; i++) {
                Option option = options.get(i);
                int row = 5 + (i - scrollOffset) * 2;
                boolean focused = i == focusedIndex;
                boolean selected = i == originalIndex;
                graphics.setForegroundColor(focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                graphics.putString(LEFT_PAD, row, focused ? "❯ " : "  ");
                graphics.setForegroundColor(selected ? LanternaTheme.toolSuccess()
                    : focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                graphics.putString(LEFT_PAD + 2, row,
                    (i + 1) + ". " + option.label() + (selected ? " ✓" : ""));
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                graphics.putString(LEFT_PAD + 5, row + 1, option.description());
            }

            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.enableModifiers(SGR.ITALIC);
            graphics.putString(LEFT_PAD, 5 + visible * 2, "Enter to select · Esc to cancel");
            graphics.disableModifiers(SGR.ITALIC);
        }
    }
}

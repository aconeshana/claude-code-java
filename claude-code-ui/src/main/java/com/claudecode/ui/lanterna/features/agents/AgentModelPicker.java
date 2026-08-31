package com.claudecode.ui.lanterna.features.agents;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.agent.AgentModelOptions;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Model picker for the {@code /agents} create wizard's Model step and the {@code /agents} panel's
 * Edit-model quick action — a shared child component (not its own {@link InlineOverlay}),
 * instantiated once per owner ({@link AgentCreateWizard} and {@code AgentsPanel}).
 */
final class AgentModelPicker extends Panel {

    private static final int LEFT_PAD = 2;
    private static final int VISIBLE_OPTION_COUNT = 5;

    private List<AgentModelOptions.Option> options = AgentModelOptions.options();

    private boolean pickerVisible;
    private int selectedIdx;
    private int visibleFromIdx;
    private Consumer<String> onConfirm;
    private Runnable onCancel;
    private String title = "Create new agent";
    private String subtitle = "Select model";
    private boolean showFooter = true;
    private TextColor titleColor = LanternaTheme.suggestion();
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();

    AgentModelPicker() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    /**
     * Activates the picker.
     */
    void activate(String currentModelOrNull, Consumer<String> onConfirm, Runnable onCancel) {
        activate(currentModelOrNull, onConfirm, onCancel,
            "Create new agent", "Select model", true, LanternaTheme.suggestion());
    }

    void activate(String currentModelOrNull, Consumer<String> onConfirm, Runnable onCancel,
                  String title, String subtitle, boolean showFooter, TextColor titleColor) {
        List<AgentModelOptions.Option> nextOptions = new ArrayList<>(AgentModelOptions.options());
        if (currentModelOrNull != null && nextOptions.stream()
                .noneMatch(option -> option.value().equals(currentModelOrNull))) {
            nextOptions.addFirst(new AgentModelOptions.Option(currentModelOrNull,
                currentModelOrNull, "Current model (custom ID)"));
        }
        this.options = List.copyOf(nextOptions);
        String seed = currentModelOrNull != null ? currentModelOrNull : "sonnet";
        int idx = 0;
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equals(seed)) { idx = i; break; }
        }
        this.selectedIdx = idx;
        this.visibleFromIdx = initialVisibleFrom(idx);
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.title = title;
        this.subtitle = subtitle;
        this.showFooter = showFooter;
        this.titleColor = titleColor;
        setPickerVisible(true);
    }

    void setPickerVisible(boolean visible) {
        this.pickerVisible = visible;
        invalidate();
    }

    void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!pickerVisible) return;
        KeyType t = key.getKeyType();
        deliver.set(false);
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)) {
            boolean handled = switch (value) {
                case "select:previous" -> { move(-1); yield true; }
                case "select:next" -> { move(1); yield true; }
                case "select:pageUp" -> { movePage(-1); yield true; }
                case "select:pageDown" -> { movePage(1); yield true; }
                case "select:first" -> { jumpTo(0); yield true; }
                case "select:last" -> { jumpTo(options.size() - 1); yield true; }
                case "select:accept" -> { confirm(); yield true; }
                case "select:cancel" -> { cancel(); yield true; }
                default -> false;
            };
            if (handled) return;
        }
        if (t == KeyType.ARROW_UP) {
            move(-1);
        } else if (t == KeyType.ARROW_DOWN) {
            move(1);
        } else if (t == KeyType.PAGE_UP) {
            movePage(-1);
        } else if (t == KeyType.PAGE_DOWN) {
            movePage(1);
        } else if (t == KeyType.ENTER) {
            confirm();
        } else if (t == KeyType.ESCAPE) {
            cancel();
        } else if (t == KeyType.CHARACTER && key.getCharacter() != null) {
            int index = selectDigitValue(key.getCharacter()) - 1;
            if (index >= 0 && index < options.size()) {
                selectedIdx = index;
                confirm();
            }
        }
    }

    private static int selectDigitValue(char character) {
        if (character >= '0' && character <= '9') {
            return character - '0';
        }
        if (character >= '\uFF10' && character <= '\uFF19') {
            return character - '\uFF10';
        }
        return -1;
    }

    private void move(int delta) {
        int previous = selectedIdx;
        selectedIdx = InlineOverlay.cycleIndex(selectedIdx, delta, options.size());
        if (delta > 0 && selectedIdx < previous) {
            visibleFromIdx = 0;
        } else if (delta < 0 && selectedIdx > previous) {
            visibleFromIdx = Math.max(0, options.size() - VISIBLE_OPTION_COUNT);
        } else {
            keepSelectedVisible();
        }
        invalidate();
    }

    private void movePage(int direction) {
        selectedIdx = direction > 0
            ? Math.min(options.size() - 1, selectedIdx + VISIBLE_OPTION_COUNT)
            : Math.max(0, selectedIdx - VISIBLE_OPTION_COUNT);
        keepSelectedVisible();
        invalidate();
    }

    private void jumpTo(int index) {
        selectedIdx = index;
        keepSelectedVisible();
        invalidate();
    }

    private int initialVisibleFrom(int index) {
        return index < VISIBLE_OPTION_COUNT
            ? 0 : Math.max(0, index - VISIBLE_OPTION_COUNT + 1);
    }

    private void keepSelectedVisible() {
        if (selectedIdx < visibleFromIdx) {
            visibleFromIdx = selectedIdx;
        } else if (selectedIdx >= visibleFromIdx + VISIBLE_OPTION_COUNT) {
            visibleFromIdx = selectedIdx - VISIBLE_OPTION_COUNT + 1;
        }
    }

    private void confirm() {
        Consumer<String> cb = onConfirm;
        String chosen = options.get(selectedIdx).value();
        setPickerVisible(false);
        if (cb != null) cb.accept(chosen);
    }

    private void cancel() {
        Runnable cb = onCancel;
        setPickerVisible(false);
        if (cb != null) cb.run();
    }

    private int totalRows() {
        return contentStartRow() + 2 + Math.min(VISIBLE_OPTION_COUNT, options.size())
            + (showFooter ? 2 : 0);
    }

    private int contentStartRow() {
        return title == null ? 0 : subtitle == null ? 2 : 3;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!pickerVisible) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    @Override public Interactable nextFocus(Interactable fromThis) { return pickerVisible ? super.nextFocus(fromThis) : null; }
    @Override public Interactable previousFocus(Interactable fromThis) { return pickerVisible ? super.previousFocus(fromThis) : null; }

    // ── Test accessors (package-private) ────────────────────────────────────

    boolean isPickerVisible() { return pickerVisible; }
    String selectedModel() { return options.get(selectedIdx).value(); }

    // ──────────────────────────────────────────────────────────────────────────

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!pickerVisible) return new TerminalSize(0, 0);
            return new TerminalSize(70, totalRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, Area c) {
            if (!pickerVisible) return;
            g.fill(' ');

            if (title != null) {
                g.setForegroundColor(titleColor);
                g.enableModifiers(SGR.BOLD);
                g.putString(LEFT_PAD, 0, title);
                g.disableModifiers(SGR.BOLD);
                if (subtitle != null) {
                    g.setForegroundColor(LanternaTheme.ghostText());
                    g.putString(LEFT_PAD, 1, subtitle);
                }
            }

            int contentRow = contentStartRow();
            g.setForegroundColor(LanternaTheme.ghostText());
            g.putString(LEFT_PAD, contentRow,
                "Model determines the agent's reasoning capabilities and speed.");

            int visibleTo = Math.min(options.size(), visibleFromIdx + VISIBLE_OPTION_COUNT);
            for (int i = visibleFromIdx; i < visibleTo; i++) {
                int row = contentRow + 2 + i - visibleFromIdx;
                AgentModelOptions.Option opt = options.get(i);
                boolean selected = i == selectedIdx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                String prefix = (selected ? "❯ " : "  ") + (i + 1) + ". ";
                g.putString(LEFT_PAD, row, prefix + opt.label());
                g.setForegroundColor(LanternaTheme.ghostText());
                g.putString(LEFT_PAD + prefix.length() + opt.label().length() + 2,
                    row, opt.description());
            }

            if (showFooter) {
                int footerRow = contentRow + 2
                    + Math.min(VISIBLE_OPTION_COUNT, options.size()) + 1;
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.enableModifiers(SGR.ITALIC);
                g.putString(LEFT_PAD, footerRow,
                    "↑↓ navigate · Enter select · Esc go back");
                g.disableModifiers(SGR.ITALIC);
            }
        }
    }
}

package com.claudecode.ui.lanterna.features.agents;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.commands.impl.config.ColorCommand;
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
 * Color picker for the {@code /agents} create wizard's Color step and the {@code /agents} panel's
 * Edit-color quick action — a shared child component (not its own {@link InlineOverlay}),
 * instantiated once per owner ({@link AgentCreateWizard} and {@code AgentsPanel}) per the {@code
 * PermissionRulesTab} precedent of one instance per usage site.
 */
final class AgentColorPicker extends Panel {

    private static final int LEFT_PAD = 2;

    /** {@code null} for "automatic". */
    private final List<String> options = buildOptions();

    private boolean pickerVisible;
    private int selectedIdx;
    private String agentNamePreview = "my-agent";
    private Consumer<String> onConfirm;
    private Runnable onCancel;
    private String title = "Create new agent";
    private String subtitle = "Choose background color";
    private boolean showFooter = true;
    private TextColor titleColor = LanternaTheme.suggestion();
    private final ContextKeybindingDispatcher keybindings = new ContextKeybindingDispatcher();

    AgentColorPicker() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Area area = new Area();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    private static List<String> buildOptions() {
        List<String> opts = new ArrayList<>();
        opts.add(null);
        opts.addAll(ColorCommand.AGENT_COLORS);
        return opts;
    }

    /**
     * Activates the picker.
     *
     * @param currentColorOrNull the agent's current color, or {@code null} for "automatic"
     * @param agentNamePreview   the name to render in the live preview line
     * @param onConfirm          receives the chosen color, or {@code null} for "automatic"
     * @param onCancel           invoked on Esc
     */
    void activate(String currentColorOrNull, String agentNamePreview, Consumer<String> onConfirm, Runnable onCancel) {
        activate(currentColorOrNull, agentNamePreview, onConfirm, onCancel,
            "Create new agent", "Choose background color", true, LanternaTheme.suggestion());
    }

    void activate(String currentColorOrNull, String agentNamePreview,
                  Consumer<String> onConfirm, Runnable onCancel,
                  String title, String subtitle, boolean showFooter, TextColor titleColor) {
        this.selectedIdx = Math.max(0, options.indexOf(currentColorOrNull));
        this.agentNamePreview = agentNamePreview != null ? agentNamePreview : "my-agent";
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
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Confirmation", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) return;
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            cancel();
            return;
        }
        if (t == KeyType.ARROW_UP) {
            selectedIdx = InlineOverlay.cycleIndex(selectedIdx, -1, options.size());
            invalidate();
        } else if (t == KeyType.ARROW_DOWN) {
            selectedIdx = InlineOverlay.cycleIndex(selectedIdx, 1, options.size());
            invalidate();
        } else if (t == KeyType.ENTER) {
            Consumer<String> cb = onConfirm;
            setPickerVisible(false);
            if (cb != null) cb.accept(options.get(selectedIdx));
        } else if (t == KeyType.ESCAPE) {
            cancel();
        }
    }

    private void cancel() {
        Runnable cb = onCancel;
        setPickerVisible(false);
        if (cb != null) cb.run();
    }

    private int totalRows() {
        return contentStartRow() + options.size() + 3 + (showFooter ? 2 : 0);
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
    String selectedColor() { return options.get(selectedIdx); }
    String optionLabel(int index) {
        String option = options.get(index);
        return option == null ? "Automatic color" : capitalize(option);
    }
    String previewText() { return "Preview:  @" + agentNamePreview + " "; }

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private final class Area extends AbstractComponent<Area> {
        @Override protected ComponentRenderer<Area> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Area> {

        @Override
        public TerminalSize getPreferredSize(Area c) {
            if (!pickerVisible) return new TerminalSize(0, 0);
            return new TerminalSize(60, totalRows());
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
            for (int i = 0; i < options.size(); i++) {
                int row = contentRow + i;
                String opt = options.get(i);
                String label = optionLabel(i);
                boolean selected = i == selectedIdx;
                g.setForegroundColor(selected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD, row, selected ? "❯ " : "  ");
                if (opt == null) {
                    g.putString(LEFT_PAD + 2, row, label);
                } else {
                    g.setForegroundColor(LanternaTheme.inverseText());
                    g.setBackgroundColor(LanternaTheme.agentColor(opt));
                    g.putString(LEFT_PAD + 2, row, " ");
                    g.setBackgroundColor(TextColor.ANSI.DEFAULT);
                    g.setForegroundColor(selected
                        ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                    g.putString(LEFT_PAD + 4, row, label);
                }
            }

            int previewRow = contentRow + options.size() + 1;
            String chosen = options.get(selectedIdx);
            g.setForegroundColor(LanternaTheme.inputText());
            g.putString(LEFT_PAD, previewRow, "Preview: ");
            int badgeX = LEFT_PAD + "Preview: ".length();
            g.setForegroundColor(LanternaTheme.inverseText());
            if (chosen == null) {
                g.setBackgroundColor(LanternaTheme.inputText());
            } else {
                g.setBackgroundColor(LanternaTheme.agentColor(chosen));
            }
            g.enableModifiers(SGR.BOLD);
            g.putString(badgeX, previewRow, " @" + agentNamePreview + " ");
            g.disableModifiers(SGR.BOLD);
            g.setBackgroundColor(TextColor.ANSI.DEFAULT);

            if (showFooter) {
                int footerRow = previewRow + 2;
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.enableModifiers(SGR.ITALIC);
                g.putString(LEFT_PAD, footerRow,
                    "↑↓ navigate · Enter select · Esc go back");
                g.disableModifiers(SGR.ITALIC);
            }
        }
    }
}

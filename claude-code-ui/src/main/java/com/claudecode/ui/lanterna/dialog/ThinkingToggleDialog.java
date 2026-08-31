package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Inline session thinking-mode picker opened by {@code chat:thinkingToggle}.
 */
public final class ThinkingToggleDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int PICKER_ROWS = 9;
    private static final int CONFIRM_ROWS = 9;

    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private boolean active;
    private boolean currentValue;
    private boolean selectedValue;
    private boolean midConversation;
    private Boolean confirmationPending;
    private Consumer<Boolean> onResult;

    public ThinkingToggleDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Body body = new Body();
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public synchronized void show(boolean currentValue, boolean midConversation,
                                  Consumer<Boolean> onResult) {
        this.currentValue = currentValue;
        this.selectedValue = currentValue;
        this.midConversation = midConversation;
        this.confirmationPending = null;
        this.onResult = onResult;
        this.active = true;
        invalidate();
    }

    @Override public synchronized boolean isActive() {
        return active;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType type = key.getKeyType();
        if (type == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                cancel();
                deliver.set(false);
                return;
            }
        }

        List<String> contexts = confirmationPending != null
            ? List.of("Confirmation") : List.of("Select", "Confirmation");
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve(contexts, key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action action
                && dispatchAction(action.value())) {
            deliver.set(false);
            return;
        }

        if (confirmationPending != null) {
            if (type == KeyType.ENTER) confirmPending();
            else if (type == KeyType.ESCAPE) cancelConfirmation();
            else return;
            deliver.set(false);
            return;
        }
        if (type == KeyType.ARROW_UP || type == KeyType.ARROW_DOWN) {
            selectedValue = !selectedValue;
        } else if (type == KeyType.ENTER) {
            selectCurrent();
        } else if (type == KeyType.ESCAPE) {
            cancel();
        } else {
            return;
        }
        invalidate();
        deliver.set(false);
    }

    private boolean dispatchAction(String action) {
        return switch (action) {
            case "select:previous", "select:next" -> {
                if (confirmationPending == null) {
                    selectedValue = !selectedValue;
                    invalidate();
                }
                yield true;
            }
            case "select:accept" -> {
                if (confirmationPending == null) selectCurrent();
                yield true;
            }
            case "select:cancel", "confirm:no" -> {
                if (confirmationPending != null) cancelConfirmation();
                else cancel();
                yield true;
            }
            case "confirm:yes" -> {
                if (confirmationPending != null) confirmPending();
                yield true;
            }
            default -> false;
        };
    }

    private void selectCurrent() {
        if (midConversation && selectedValue != currentValue) {
            confirmationPending = selectedValue;
            invalidate();
            return;
        }
        resolve(selectedValue);
    }

    private void confirmPending() {
        if (confirmationPending != null) resolve(confirmationPending);
    }

    private void cancelConfirmation() {
        confirmationPending = null;
        invalidate();
    }

    private void cancel() {
        resolve(null);
    }

    private void resolve(Boolean result) {
        Consumer<Boolean> callback = onResult;
        active = false;
        confirmationPending = null;
        onResult = null;
        invalidate();
        if (callback != null) callback.accept(result);
    }

    boolean isConfirmationPendingForTest() {
        return confirmationPending != null;
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        return new TerminalSize(76,
            confirmationPending == null ? PICKER_ROWS : CONFIRM_ROWS);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new Renderer();
        }
    }

    private final class Renderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body component) {
            return ThinkingToggleDialog.this.calculatePreferredSize();
        }

        @Override public void drawComponent(TextGUIGraphics g, Body component) {
            if (!active) return;
            int width = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 0, "Toggle thinking mode");
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 1, "Enable or disable thinking for this session.");
            if (confirmationPending != null) {
                g.setForegroundColor(LanternaTheme.toolWarning());
                g.putString(LEFT_PAD, 3, fit(
                    "Changing thinking mode mid-conversation will increase latency and may reduce quality.", width));
                g.putString(LEFT_PAD, 4, fit(
                    "For best results, set this at the start of a session.", width));
                g.putString(LEFT_PAD, 6, "Do you want to proceed?");
                g.setForegroundColor(LanternaTheme.welcomeDim());
                g.putString(LEFT_PAD, 8, "Enter confirm  ·  Esc cancel");
                return;
            }
            drawOption(g, 3, true, "Enabled", "Claude will think before responding");
            drawOption(g, 5, false, "Disabled", "Claude will respond without extended thinking");
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD, 8, "Enter confirm  ·  Esc exit");
        }

        private void drawOption(TextGUIGraphics g, int row, boolean value,
                                String label, String description) {
            boolean focused = selectedValue == value;
            g.setForegroundColor(focused ? LanternaTheme.claude() : LanternaTheme.inputText());
            if (focused) g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row, (focused ? "❯ " : "  ") + label);
            if (focused) g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.putString(LEFT_PAD + 4, row + 1, description);
        }

        private String fit(String text, int width) {
            int max = Math.max(0, width - LEFT_PAD - 1);
            return text.length() <= max ? text : text.substring(0, max);
        }
    }
}

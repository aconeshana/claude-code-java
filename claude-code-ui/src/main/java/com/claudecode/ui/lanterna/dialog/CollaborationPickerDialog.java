package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
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
import com.googlecode.lanterna.input.PasteKeyStroke;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Keyboard-first picker for the current session's IM collaboration channel.
 */
@Explanation("Selects one IM collaboration channel")
public final class CollaborationPickerDialog extends Panel implements InlineOverlay {

    public static final String SETUP_FEISHU = "__setup_feishu__";
    public static final String CONTINUE_FEISHU = "__continue_feishu__";
    private static final int VISIBLE_OPTION_COUNT = 5;
    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    private record Option(String value, String label) {}

    private static final int LEFT_PAD = 2;
    private final ContextKeybindingDispatcher keybindings =
        new ContextKeybindingDispatcher();
    private boolean active;
    private int focusedIndex;
    private int selectedIndex;
    private int visibleFromIndex;
    private List<Option> options = List.of(new Option("", "Off"));
    private Consumer<String> onResult;
    private Consumer<Character> exitGestureHandler = _ -> { };
    private BooleanSupplier interactionBlocked = () -> false;
    private Character pendingExitKey;
    private long pendingExitTime;

    public CollaborationPickerDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Body body = new Body();
        body.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(body);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        keybindings.setStore(store);
    }

    public void setInteractionBlocked(BooleanSupplier blocked) {
        interactionBlocked = blocked == null ? () -> false : blocked;
    }

    /** Routes Ctrl+C/D through the REPL's existing double-press exit controller. */
    public void setExitGestureHandler(Consumer<Character> handler) {
        exitGestureHandler = handler != null ? handler : _ -> { };
    }

    public synchronized void show(List<String> channels, String current,
                                  Consumer<String> onResult) {
		show(channels, current, false, onResult);
	}

    public synchronized void show(List<String> channels, String current,
                                  boolean allowSetup, Consumer<String> onResult) {
        show(channels, current, allowSetup, false, onResult);
    }

    public synchronized void show(List<String> channels, String current,
                                  boolean allowSetup, boolean continueSetup,
                                  Consumer<String> onResult) {
        List<Option> values = new ArrayList<>();
        values.add(new Option("", "Off"));
        if (channels != null) {
            channels.stream().map(CollaborationPickerDialog::normalized)
                .filter(channel -> !channel.isEmpty()).distinct()
                .forEach(channel -> values.add(new Option(channel, display(channel))));
        }
		if (continueSetup) values.add(new Option(CONTINUE_FEISHU, "Continue Feishu setup…"));
		else if (allowSetup) values.add(new Option(SETUP_FEISHU, "Set up Feishu…"));
        this.options = List.copyOf(values);
        this.selectedIndex = findIndex(normalized(current));
        this.focusedIndex = selectedIndex;
        this.visibleFromIndex = focusedIndex < VISIBLE_OPTION_COUNT ? 0
            : Math.max(0, focusedIndex - VISIBLE_OPTION_COUNT + 1);
        this.onResult = onResult;
        this.pendingExitKey = null;
        this.pendingExitTime = 0L;
        this.active = true;
        invalidate();
    }

    @Override public synchronized boolean isActive() {
        return active && !interactionBlocked.getAsBoolean();
    }

    @Override public synchronized boolean isVisibleInScene() {
        return active;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        if (key.getKeyType() == KeyType.CHARACTER && key.isCtrlDown()
                && !key.isAltDown() && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'c' || ch == 'd') {
                armExitPending(ch);
                exitGestureHandler.accept(ch);
                deliver.set(false);
                return;
            }
        }
        if (key.getKeyType() == KeyType.PASTE) {
            if (key instanceof PasteKeyStroke paste) {
                selectByNumericInput(paste.getPastedText());
            }
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result resolved = keybindings.resolve("Select", key);
        if (resolved instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (resolved instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && dispatch(value)) {
            deliver.set(false);
            return;
        }
        KeyType type = key.getKeyType();
        if (type == KeyType.ARROW_UP) move(-1);
        else if (type == KeyType.ARROW_DOWN) move(1);
        else if (type == KeyType.PAGE_UP) movePage(-1);
        else if (type == KeyType.PAGE_DOWN) movePage(1);
        else if (type == KeyType.ENTER) resolve(options.get(focusedIndex).value());
        else if (type == KeyType.ESCAPE) cancel();
        else if (type == KeyType.CHARACTER && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (key.isCtrlDown()) {
                if (ch == 'n') move(1);
                else if (ch == 'p') move(-1);
                else return;
            } else if (ch == 'j') move(1);
            else if (ch == 'k') move(-1);
            else if (!selectByNumericInput(String.valueOf(ch))) return;
        } else return;
        deliver.set(false);
    }

    private boolean dispatch(String action) {
        return switch (action) {
            case "select:previous" -> { move(-1); yield true; }
            case "select:next" -> { move(1); yield true; }
            case "select:pageUp" -> { movePage(-1); yield true; }
            case "select:pageDown" -> { movePage(1); yield true; }
            case "select:first" -> { jumpTo(0); yield true; }
            case "select:last" -> { jumpTo(options.size() - 1); yield true; }
            case "select:accept" -> { resolve(options.get(focusedIndex).value()); yield true; }
            case "select:cancel" -> { cancel(); yield true; }
            default -> false;
        };
    }

    private void move(int delta) {
        int previous = focusedIndex;
        focusedIndex = InlineOverlay.cycleIndex(focusedIndex, delta, options.size());
        if (focusedIndex == 0 && previous == options.size() - 1) {
            visibleFromIndex = 0;
        } else if (focusedIndex == options.size() - 1 && previous == 0) {
            visibleFromIndex = Math.max(0, options.size() - VISIBLE_OPTION_COUNT);
        } else {
            keepFocusedOptionVisible();
        }
        invalidate();
    }

    private void movePage(int direction) {
        focusedIndex = Math.max(0, Math.min(
            focusedIndex + direction * VISIBLE_OPTION_COUNT, options.size() - 1));
        keepFocusedOptionVisible();
        invalidate();
    }

    private void jumpTo(int index) {
        focusedIndex = index;
        keepFocusedOptionVisible();
        invalidate();
    }

    private void keepFocusedOptionVisible() {
        int visibleCount = Math.min(VISIBLE_OPTION_COUNT, options.size());
        if (focusedIndex < visibleFromIndex) {
            visibleFromIndex = focusedIndex;
        } else if (focusedIndex >= visibleFromIndex + visibleCount) {
            visibleFromIndex = focusedIndex - visibleCount + 1;
        }
    }

    private static int digitValue(char character) {
        if (character >= '0' && character <= '9') return character - '0';
        return character >= '\uFF10' && character <= '\uFF19'
            ? character - '\uFF10' : -1;
    }

    private boolean selectByNumericInput(String input) {
        if (StringUtils.isEmpty(input)) return false;
        StringBuilder ascii = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            int digit = digitValue(input.charAt(i));
            if (digit < 0) return false;
            ascii.append(digit);
        }
        try {
            int index = Integer.parseInt(ascii.toString()) - 1;
            if (index < 0 || index >= options.size()) return false;
            resolve(options.get(index).value());
            return true;
        } catch (NumberFormatException _) {
            return false;
        }
    }

    private int findIndex(String current) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).value().equals(current)) return i;
        }
        return 0;
    }

    private void cancel() {
        resolve(null);
    }

    private void resolve(String value) {
        Consumer<String> callback = onResult;
        active = false;
        pendingExitKey = null;
        onResult = null;
        invalidate();
        if (callback != null) callback.accept(value);
    }

    @Override public synchronized TerminalSize calculatePreferredSize() {
        return active ? new TerminalSize(72, 7 + Math.min(VISIBLE_OPTION_COUNT, options.size()))
            : new TerminalSize(0, 0);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    List<String> optionLabelsForTest() {
        return options.stream().map(Option::label).toList();
    }

    int focusedIndexForTest() { return focusedIndex; }

    private void armExitPending(char ch) {
        pendingExitKey = ch;
        pendingExitTime = System.currentTimeMillis();
        invalidate();
        long armedAt = pendingExitTime;
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(DOUBLE_PRESS_TIMEOUT_MS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            synchronized (CollaborationPickerDialog.this) {
                if (active && pendingExitKey != null && pendingExitKey == ch
                        && pendingExitTime == armedAt) {
                    pendingExitKey = null;
                    invalidate();
                }
            }
        });
    }

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() {
            return new Renderer();
        }
    }

    private final class Renderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body component) {
            return CollaborationPickerDialog.this.calculatePreferredSize();
        }

        @Override public void drawComponent(TextGUIGraphics g, Body component) {
            if (!active) return;
            g.fill(' ');
            int columns = g.getSize().getColumns();
            g.setForegroundColor(LanternaTheme.permission());
            g.putString(0, 0, "─".repeat(Math.max(0, columns)));
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, "Collaboration");
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.welcomeDim());
            List<String> description = DialogText.wrapWords(
                "Mirror progress and interactions to one IM channel.",
                Math.max(1, columns - LEFT_PAD));
            for (int i = 0; i < description.size(); i++) {
                g.putString(LEFT_PAD, 2 + i, description.get(i));
            }
            int optionStartRow = 3 + description.size();
            int visibleToIndex = Math.min(options.size(), visibleFromIndex + VISIBLE_OPTION_COUNT);
            int indexWidth = Integer.toString(options.size()).length();
            for (int i = visibleFromIndex; i < visibleToIndex; i++) {
                boolean focused = focusedIndex == i;
                boolean selected = selectedIndex == i;
                int row = optionStartRow + i - visibleFromIndex;
                g.setForegroundColor(focused ? LanternaTheme.suggestion()
                    : LanternaTheme.inputText());
                String marker = focused ? "❯ "
                    : i == visibleFromIndex && visibleFromIndex > 0 ? "↑ "
                    : i == visibleToIndex - 1 && visibleToIndex < options.size() ? "↓ " : "  ";
                g.putString(LEFT_PAD, row, marker);
                g.setForegroundColor(LanternaTheme.welcomeDim());
                String index = (i + 1) + ".";
                g.putString(LEFT_PAD + 2, row,
                    index + " ".repeat(Math.max(1, indexWidth + 2 - index.length())));
                g.setForegroundColor(selected ? LanternaTheme.toolSuccess()
                    : focused ? LanternaTheme.suggestion() : LanternaTheme.inputText());
                g.putString(LEFT_PAD + 4 + indexWidth, row,
                    options.get(i).label() + (selected ? " ✓" : ""));
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD,
                optionStartRow + Math.min(VISIBLE_OPTION_COUNT, options.size()) + 1,
                pendingExitKey == null
                    ? "Enter to select · Esc to cancel"
                    : "Press Ctrl-" + Character.toUpperCase(pendingExitKey) + " again to exit");
            g.disableModifiers(SGR.ITALIC);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String display(String channel) {
        return switch (channel) {
            case "feishu" -> "Feishu";
            case "slack" -> "Slack";
            default -> channel;
        };
    }
}

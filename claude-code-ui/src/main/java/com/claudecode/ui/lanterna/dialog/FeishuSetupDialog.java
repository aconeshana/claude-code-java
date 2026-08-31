package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.core.text.FormatUtils;
import com.claudecode.runtime.sessionhost.CollaborationSetupPort;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;

/** Keyboard-first Feishu collaboration onboarding surface. */
@Explanation("Renders Feishu collaboration onboarding inside the terminal")
public final class FeishuSetupDialog extends Panel implements InlineOverlay {

    private enum State { CHOICE, APP_ID, APP_SECRET, RUNNING, DONE, FAILED }
    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    private final Body body = new Body();
    private final StringBuilder input = new StringBuilder();
    private final List<String> output = new ArrayList<>();
    private State state = State.CHOICE;
    private int choice;
    private String appId = "";
    private boolean active;
    private CollaborationSetupPort setup;
    private Consumer<Runnable> guiInvoker = Runnable::run;
    private Consumer<Character> exitGestureHandler = _ -> { };
    private Runnable onClose = () -> {};
    private Character pendingExitKey;
    private long pendingExitTime;

    public FeishuSetupDialog() {
        super(new LinearLayout(Direction.VERTICAL));
        addComponent(body);
    }

    public void setGuiInvoker(Consumer<Runnable> invoker) {
        guiInvoker = invoker == null ? Runnable::run : invoker;
    }

    public void setExitGestureHandler(Consumer<Character> handler) {
        exitGestureHandler = handler == null ? _ -> { } : handler;
    }

    public synchronized void show(CollaborationSetupPort setup, Runnable onClose) {
        this.setup = setup;
        this.onClose = onClose == null ? () -> {} : onClose;
        state = State.CHOICE;
        choice = 0;
        appId = "";
        clearInput();
        output.clear();
        pendingExitKey = null;
        pendingExitTime = 0L;
        active = true;
        if (setup != null && setup.setupPending()) {
            output.add("Waiting for a Feishu bot message to select the collaboration chat.");
            begin(CollaborationSetupPort.Mode.RESUME);
        }
        invalidate();
    }

    @Override public synchronized boolean isActive() { return active; }

    @Override public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        // Credential input is modal: paste and unknown keys must not reach the
        // focused prompt behind this hand-drawn overlay.
        deliver.set(false);
        KeyType type = key.getKeyType();
        if (type == KeyType.CHARACTER && key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null) {
            char ch = Character.toLowerCase(key.getCharacter());
            if (ch == 'c' || ch == 'd') {
                armExitPending(ch);
                exitGestureHandler.accept(ch);
                return;
            }
        }
        if (type == KeyType.ESCAPE) {
            if (state == State.RUNNING && setup != null) setup.cancel();
            close();
        } else if (state == State.CHOICE) {
            if (type == KeyType.ARROW_UP) moveChoice(-1);
            else if (type == KeyType.ARROW_DOWN) moveChoice(1);
            else if (type == KeyType.ENTER) selectChoice(choice);
            else if (type == KeyType.PASTE && key instanceof PasteKeyStroke paste) {
                if (!selectNumericChoice(paste.getPastedText())) return;
            } else if (type == KeyType.CHARACTER && key.getCharacter() != null) {
                char ch = Character.toLowerCase(key.getCharacter());
                if (key.isCtrlDown()) {
                    if (ch == 'n') moveChoice(1);
                    else if (ch == 'p') moveChoice(-1);
                    else return;
                } else if (ch == 'j') moveChoice(1);
                else if (ch == 'k') moveChoice(-1);
                else if (!selectNumericChoice(String.valueOf(ch))) return;
            } else return;
        } else if (state == State.APP_ID || state == State.APP_SECRET) {
            if (type == KeyType.BACKSPACE && !input.isEmpty()) input.deleteCharAt(input.length() - 1);
            else if (type == KeyType.PASTE && key instanceof PasteKeyStroke paste) {
                appendInput(paste.getPastedText());
            }
            else if (type == KeyType.CHARACTER && key.getCharacter() != null
                    && !Character.isISOControl(key.getCharacter())) {
                appendInput(String.valueOf(key.getCharacter()));
            } else if (type == KeyType.ENTER && !StringUtils.isBlank(input.toString())) {
                if (state == State.APP_ID) {
                    appId = input.toString().trim();
                    clearInput();
                    state = State.APP_SECRET;
                } else {
                    char[] secret = new char[input.length()];
                    input.getChars(0, input.length(), secret, 0);
                    clearInput();
                    begin(new CollaborationSetupPort.Request(
                        CollaborationSetupPort.Mode.BIND, appId, secret));
                    Arrays.fill(secret, '\0');
                }
            } else return;
        } else if ((state == State.DONE || state == State.FAILED) && type == KeyType.ENTER) {
            close();
        } else return;
        invalidate();
    }

    private void appendInput(String value) {
        if (StringUtils.isEmpty(value)) return;
        for (int i = 0; i < value.length() && input.length() < 512; i++) {
            char ch = value.charAt(i);
            if (!Character.isISOControl(ch)) input.append(ch);
        }
    }

    private void moveChoice(int delta) {
        choice = Math.floorMod(choice + delta, 2);
    }

    private boolean selectNumericChoice(String value) {
        if (value == null || value.length() != 1) return false;
        char ch = value.charAt(0);
        int digit = ch >= '\uFF10' && ch <= '\uFF19'
            ? ch - '\uFF10' : Character.digit(ch, 10);
        if (digit < 1 || digit > 2) return false;
        selectChoice(digit - 1);
        return true;
    }

    private void selectChoice(int selected) {
        choice = selected;
        if (choice == 0) begin(CollaborationSetupPort.Mode.CREATE);
        else state = State.APP_ID;
    }

    private void clearInput() {
        for (int i = 0; i < input.length(); i++) input.setCharAt(i, '\0');
        input.setLength(0);
    }

    private void begin(CollaborationSetupPort.Mode mode) {
        begin(new CollaborationSetupPort.Request(mode, "", new char[0]));
    }

    private void begin(CollaborationSetupPort.Request request) {
        state = State.RUNNING;
        output.clear();
        setup.setup(request, line -> guiInvoker.accept(() -> append(line)))
            .whenComplete((result, failure) -> guiInvoker.accept(() -> complete(result, failure)));
    }

    private synchronized void append(String line) {
        if (StringUtils.isEmpty(line)) return;
        output.add(line.replace('\t', ' '));
        if (output.size() > 200) output.removeFirst();
        invalidate();
    }

    private synchronized void complete(CollaborationSetupPort.Result result, Throwable failure) {
        if (!active) return;
        if (failure == null) {
            state = State.DONE;
            output.add(result == null ? "Feishu collaboration is ready." : result.message());
        } else {
            state = State.FAILED;
            output.add("Setup failed: " + rootMessage(failure));
        }
        invalidate();
    }

    private void close() {
        active = false;
        pendingExitKey = null;
        clearInput();
        appId = "";
        Runnable callback = onClose;
        onClose = () -> {};
        invalidate();
        callback.run();
    }

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
            synchronized (FeishuSetupDialog.this) {
                if (active && pendingExitKey != null && pendingExitKey == ch
                        && pendingExitTime == armedAt) {
                    pendingExitKey = null;
                    invalidate();
                }
            }
        });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    @Override public synchronized TerminalSize calculatePreferredSize() {
        return active ? new TerminalSize(92, state == State.RUNNING ? 28 : 13)
            : new TerminalSize(0, 0);
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    String renderedInputForTest() {
        return state == State.APP_SECRET ? "•".repeat(input.length()) : input.toString();
    }

    private final class Body extends AbstractComponent<Body> {
        @Override protected ComponentRenderer<Body> createDefaultRenderer() { return new Renderer(); }
    }

    private final class Renderer implements ComponentRenderer<Body> {
        @Override public TerminalSize getPreferredSize(Body component) {
            return FeishuSetupDialog.this.calculatePreferredSize();
        }

        @Override public void drawComponent(TextGUIGraphics g, Body component) {
            if (!active) return;
            int width = Math.max(1, g.getSize().getColumns() - 4);
            String footer = pendingExitKey != null
                ? "Press Ctrl-" + Character.toUpperCase(pendingExitKey) + " again to exit"
                : switch (state) {
                    case CHOICE -> "Enter to select · Esc to cancel";
                    case APP_ID, APP_SECRET -> "Enter to continue · Esc to cancel";
                    case RUNNING -> "Follow the instructions above · Esc to cancel";
                    case DONE, FAILED -> "Enter to close";
                };
            List<String> footerLines = wrapForTerminal(footer, width);
            int footerStart = Math.max(0, g.getSize().getRows() - footerLines.size());
            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(2, 0, "Set up Feishu collaboration");
            g.disableModifiers(SGR.BOLD);
            g.setForegroundColor(LanternaTheme.inputText());
            if (state == State.CHOICE) {
                g.putString(2, 2, "Choose how to connect a bot:");
                drawChoice(g, 4, 0, "Create a new bot (scan QR)");
                drawChoice(g, 6, 1, "Bind an existing Feishu app");
            } else if (state == State.APP_ID || state == State.APP_SECRET) {
                String label = state == State.APP_ID ? "App ID" : "App Secret";
                g.putString(2, 2, label + ":");
                g.putString(2, 4, crop(renderedInputForTest() + "█", width));
            } else {
                List<String> lines = output.stream()
                    .flatMap(line -> wrapForTerminal(line, width).stream())
                    .toList();
                int availableRows = Math.max(0, footerStart - 2);
                int first = Math.max(0, lines.size() - availableRows);
                int row = 2;
                for (int i = first; i < lines.size() && row < footerStart; i++) {
                    g.putString(2, row++, lines.get(i));
                }
            }
            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (int i = 0; i < footerLines.size(); i++) {
                g.putString(2, footerStart + i, footerLines.get(i));
            }
        }

        private void drawChoice(TextGUIGraphics g, int row, int index, String label) {
            boolean selected = choice == index;
            g.setForegroundColor(selected ? LanternaTheme.claude() : LanternaTheme.inputText());
            g.putString(2, row, (selected ? "❯ " : "  ") + label);
        }
    }

    private static String crop(String value, int width) {
        return value.length() <= width ? value : value.substring(0, Math.max(0, width - 1)) + "…";
    }

    private static List<String> wrapForTerminal(String value, int width) {
        List<String> lines = new ArrayList<>();
        for (String wordWrapped : DialogText.wrapWords(value, width)) {
            List<String> hardWrapped = FormatUtils.wrapText(wordWrapped, width);
            if (hardWrapped.isEmpty()) lines.add("");
            else lines.addAll(hardWrapped);
        }
        return List.copyOf(lines);
    }
}

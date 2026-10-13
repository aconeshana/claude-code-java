package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.core.engine.PermissionAskCallback;
import com.claudecode.core.engine.PermissionAskContext;
import com.claudecode.core.serialization.JsonUtils;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.util.concurrent.CompletableFuture;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

/**
 * Guards that text typed into the "No, keep planning" amend box actually reaches the screen.
 *
 * <p>Lanterna's {@code TextBox.handleKeyStroke} mutates its buffer but never invalidates, and the
 * spinner — the only other thing that would mark the tree dirty — is paused while a permission
 * prompt is up. The inline amend box therefore has to invalidate itself, or the terminal stays
 * frozen on the frame that first drew the placeholder: the caret moves but no typed character ever
 * appears. Asserting on {@code getText()} alone does not catch this, so these assertions read the
 * rendered cells.
 */
class ExitPlanModeAmendInputRenderTest {

    private static final String PLACEHOLDER = "Tell Claude what to change";

    private final DefaultVirtualTerminal terminal;
    private final TerminalSize size = new TerminalSize(100, 40);
    private final MultiWindowTextGUI gui;
    private final BasicWindow window;
    private final PermissionDialog dialog = new PermissionDialog();

    ExitPlanModeAmendInputRenderTest() throws Exception {
        terminal = new DefaultVirtualTerminal(size);
        var screen = new TerminalScreen(terminal);
        screen.startScreen();
        gui = new MultiWindowTextGUI(new SameTextGUIThread.Factory(), screen);
        window = new BasicWindow();
        var root = new Panel();
        root.addComponent(dialog);
        window.setComponent(root);
        gui.addWindow(window);
    }

    /** Flattens the rendered cells so assertions read what the user would see. */
    private String screenText() {
        StringBuilder all = new StringBuilder();
        for (int row = 0; row < size.getRows(); row++) {
            for (int col = 0; col < size.getColumns(); col++) {
                var cell = terminal.getCharacter(col, row);
                all.append(cell == null ? " " : cell.getCharacterString());
            }
            all.append('\n');
        }
        return all.toString();
    }

    private void send(KeyStroke key) throws Exception {
        gui.getGUIThread().invokeLater(() -> window.handleInput(key));
        gui.getGUIThread().processEventsAndUpdate();
    }

    @Test
    void typedFeedbackIsRenderedAndReplacesThePlaceholder() throws Exception {
        var input = JsonUtils.getMapper().createObjectNode().put("plan", "Implement the parser");
        CompletableFuture<PermissionAskCallback.Result> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> result.complete(dialog.showAndWait(
            gui, PermissionPreviewPreparer.standard().prepare(
                PermissionAskContext.simple("ExitPlanMode", input, "toolu_exit_plan")),
            null, _ -> {}, () -> {}, _ -> {}, () -> false)));

        long deadline = System.currentTimeMillis() + 3000;
        while (!dialog.isActive() && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }
        assertTrue(dialog.isActive(), "dialog never became active");

        // Walk down to "No, keep planning" the way a user would, then select it. Selecting it
        // opens the inline amend box rather than resolving the prompt.
        send(new KeyStroke(KeyType.ARROW_DOWN));
        send(new KeyStroke(KeyType.ARROW_DOWN));
        send(new KeyStroke(KeyType.ENTER));
        gui.getGUIThread().processEventsAndUpdate();
        gui.getGUIThread().processEventsAndUpdate();

        assertTrue(Strings.CS.contains(screenText(), PLACEHOLDER),
            "amend box should start out showing its placeholder");

        for (char c : "hello".toCharArray()) {
            send(new KeyStroke(c, false, false));
        }

        String typed = screenText();
        assertTrue(Strings.CS.contains(typed, "hello"),
            "typed feedback must be rendered, not just buffered");
        assertFalse(Strings.CS.contains(typed, PLACEHOLDER),
            "placeholder must disappear once the box has text");

        send(new KeyStroke(KeyType.BACKSPACE));
        String afterBackspace = screenText();
        assertTrue(Strings.CS.contains(afterBackspace, "hell"),
            "backspace must repaint the shortened text");
        assertFalse(Strings.CS.contains(afterBackspace, "hello"),
            "stale text must not linger after backspace");

        // The caret belongs on the row the feedback is drawn on. While the screen was frozen the
        // caret was the only thing still moving, which is what made it look like it was drifting
        // across the dialog.
        var caret = window.toGlobal(window.getCursorPosition());
        String[] rows = afterBackspace.split("\n", -1);
        int textRow = -1;
        for (int row = 0; row < rows.length; row++) {
            if (Strings.CS.contains(rows[row], "hell")) {
                textRow = row;
                break;
            }
        }
        assertEquals(textRow, caret.getRow(),
            "caret row should match the row the typed feedback renders on, got " + caret);
        assertEquals(rows[textRow].indexOf("hell") + "hell".length(), caret.getColumn(),
            "caret should sit just past the typed text; row=" + rows[textRow].stripTrailing()
                + " caret=" + caret);
    }
}

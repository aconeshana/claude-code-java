package com.claudecode.ui.lanterna.dialog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.SameTextGUIThread;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import com.googlecode.lanterna.screen.TerminalScreen;
import com.googlecode.lanterna.terminal.virtual.DefaultVirtualTerminal;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.Strings;
import org.junit.jupiter.api.Test;

class SudoPasswordDialogTest {

    @Test
    void masksTypedSecretAndReturnsOneShotProvidedResult() throws Exception {
        SudoPasswordDialog dialog = dialog();
        "computer-password".chars()
            .forEach(ch -> dialog.handleInput(new KeyStroke((char) ch, false, false)));

        assertEquals("•".repeat(17), dialog.renderedSecretForTest());

        dialog.handleInput(new KeyStroke(KeyType.ENTER));
        SudoPasswordInteraction.Result.Provided provided = assertInstanceOf(
            SudoPasswordInteraction.Result.Provided.class, dialog.resultForTest());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        provided.writeTo(output);
        assertEquals("computer-password\n", output.toString(StandardCharsets.UTF_8));
        assertEquals("", dialog.renderedSecretForTest());
    }

    @Test
    void escapeCancelsAndWipesTheInput() {
        SudoPasswordDialog dialog = dialog();
        dialog.handleInput(new PasteKeyStroke("do-not-retain\n"));

        dialog.handleInput(new KeyStroke(KeyType.ESCAPE));

        assertInstanceOf(SudoPasswordInteraction.Result.Cancelled.class, dialog.resultForTest());
        assertEquals("", dialog.renderedSecretForTest());
    }

    @Test
    void submittedDialogStopsRenderingPasswordPlaceholderAndCursorAfterClose() {
        SudoPasswordDialog dialog = dialog();
        dialog.handleInput(new KeyStroke('s', false, false));
        assertEquals("•█", passwordFieldText(dialog));

        dialog.handleInput(new KeyStroke(KeyType.ENTER));

        assertEquals("", passwordFieldText(dialog),
            "a closed submitted dialog must not leave its masked field or cursor visible");
    }

    @Test
    void cancelledDialogStopsRenderingPasswordPlaceholderAndCursorAfterClose() {
        SudoPasswordDialog dialog = dialog();
        dialog.handleInput(new PasteKeyStroke("secret"));
        assertEquals("••••••█", passwordFieldText(dialog));

        dialog.handleInput(new KeyStroke(KeyType.ESCAPE));

        assertEquals("", passwordFieldText(dialog),
            "a closed cancelled dialog must not leave its masked field or cursor visible");
    }

    @Test
    void closingDisplayedDialogClearsPasswordTextFromTerminalFinalFrame() throws Exception {
        TerminalSize size = new TerminalSize(100, 40);
        DefaultVirtualTerminal terminal = new DefaultVirtualTerminal(size);
        TerminalScreen screen = new TerminalScreen(terminal);
        screen.startScreen();
        MultiWindowTextGUI gui = new MultiWindowTextGUI(
            new SameTextGUIThread.Factory(), screen);
        BasicWindow underlying = new BasicWindow();
        underlying.setHints(Set.of(Window.Hint.FULL_SCREEN));
        underlying.setComponent(new Label("❯ "));
        gui.addWindow(underlying);
        gui.updateScreen();

        CompletableFuture<SudoPasswordInteraction.Result> result = new CompletableFuture<>();
        Thread.ofVirtual().start(() -> result.complete(SudoPasswordDialog.prompt(gui,
            new SudoPasswordInteraction.Request(
                "/usr/bin/sudo", "sudo launchctl limit maxfiles 65536"))));

        long deadline = System.currentTimeMillis() + 2_000;
        while (!Strings.CS.contains(terminalFrame(terminal, size), "Password:")
                && System.currentTimeMillis() < deadline) {
            gui.getGUIThread().processEventsAndUpdate();
            Thread.sleep(5);
        }
        assertTrue(Strings.CS.contains(terminalFrame(terminal, size), "Password:"),
            "the test must first observe the displayed sudo dialog");

        SudoPasswordDialog displayed = assertInstanceOf(
            SudoPasswordDialog.class, gui.getActiveWindow());
        displayed.handleInput(new KeyStroke('x', false, false));
        displayed.handleInput(new KeyStroke(KeyType.ENTER));
        assertInstanceOf(SudoPasswordInteraction.Result.Provided.class,
            result.get(2, TimeUnit.SECONDS));

        String finalFrame = terminalFrame(terminal, size);
        assertTrue(Strings.CS.contains(finalFrame, "❯ "),
            "the underlying prompt should remain visible after the modal closes");
        assertFalse(Strings.CS.contains(finalFrame, "Password:"),
            "the closed sudo dialog must not remain painted over the main prompt");
    }

    @Test
    void ignoresControlCharactersFromPaste() {
        SudoPasswordDialog dialog = dialog();

        boolean handled = dialog.handleInput(new PasteKeyStroke("abc\r\ndef"));

        assertFalse(dialog.renderedSecretForTest().isEmpty());
        assertEquals("•".repeat(6), dialog.renderedSecretForTest());
        assertTrue(handled);
    }

    private static SudoPasswordDialog dialog() {
        return new SudoPasswordDialog(new SudoPasswordInteraction.Request(
            "/usr/bin/sudo", "sudo launchctl limit maxfiles 65536"));
    }

    private static String passwordFieldText(SudoPasswordDialog dialog) {
        Container root = assertInstanceOf(Container.class, dialog.getComponent());
        List<Component> children = root.getChildrenList();
        for (int index = 0; index + 1 < children.size(); index++) {
            if (children.get(index) instanceof Label label
                    && Strings.CS.equals("Password:", label.getText())) {
                return assertInstanceOf(Label.class, children.get(index + 1)).getText();
            }
        }
        throw new AssertionError("Password field label was not found");
    }

    private static String terminalFrame(DefaultVirtualTerminal terminal, TerminalSize size) {
        StringBuilder frame = new StringBuilder(size.getRows() * (size.getColumns() + 1));
        for (int row = 0; row < size.getRows(); row++) {
            for (int column = 0; column < size.getColumns(); column++) {
                frame.append(terminal.getCharacter(new TerminalPosition(column, row))
                    .getCharacter());
            }
            frame.append('\n');
        }
        return frame.toString();
    }
}

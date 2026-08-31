package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.annotation.Explanation;
import com.claudecode.runtime.interaction.SudoPasswordInteraction;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.gui2.BasicWindow;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.Window;
import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Local masked prompt for a password delivered only to a verified system sudo.
 */
@Explanation("Adds a local-only masked sudo authentication prompt for interactive shell authorization")
public final class SudoPasswordDialog extends BasicWindow {

    private static final Logger log = LoggerFactory.getLogger(SudoPasswordDialog.class);
    private static final int MAX_PASSWORD_CHARS = 1024;

    private final char[] input = new char[MAX_PASSWORD_CHARS];
    private final Label secretLabel = new Label("");
    private int length;
    private volatile SudoPasswordInteraction.Result result =
        SudoPasswordInteraction.Result.cancelled();
    private boolean submitted;

    SudoPasswordDialog(SudoPasswordInteraction.Request request) {
        super("Authentication required");
        setHints(Set.of(Window.Hint.CENTERED, Window.Hint.MODAL));

        Panel root = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        Label title = new Label("Authentication required by " + request.executable());
        title.setForegroundColor(LanternaTheme.permission());
        title.addStyle(SGR.BOLD);
        root.addComponent(title);
        root.addComponent(new Label(" "));
        root.addComponent(new Label(cropCommand(request.command())));
        root.addComponent(new Label(" "));
        root.addComponent(new Label("Password:"));
        secretLabel.setForegroundColor(LanternaTheme.inputText());
        root.addComponent(secretLabel);
        root.addComponent(new Label(" "));
        Label warning = new Label("The password is sent only to the system sudo process.");
        warning.setForegroundColor(LanternaTheme.welcomeDim());
        root.addComponent(warning);
        root.addComponent(new Label("Enter submit · Esc cancel"));
        setComponent(root);
        renderSecret();
    }

    /** Opens the modal synchronously on the calling tool-execution thread. */
    public static SudoPasswordInteraction.Result prompt(
            WindowBasedTextGUI gui, SudoPasswordInteraction.Request request) {
        if (gui == null || request == null) return SudoPasswordInteraction.Result.unavailable();
        SudoPasswordDialog dialog = new SudoPasswordDialog(request);
        gui.addWindowAndWait(dialog);
        clearClosedDialogFrame(gui);
        return dialog.result;
    }

    private static void clearClosedDialogFrame(WindowBasedTextGUI gui) {
        try {
            // Lanterna redraws components over its existing back buffer. When the
            // underlying prompt is shorter than this modal, cells such as
            // "Password:" can survive window removal. Clear the complete back
            // buffer first, then rebuild the current window stack immediately.
            gui.getScreen().clear();
            gui.updateScreen();
        } catch (IOException e) {
            log.debug("[SUDO] Failed to refresh the terminal after closing the password dialog", e);
        }
    }

    @Override
    public boolean handleInput(KeyStroke key) {
        KeyType type = key.getKeyType();
        if (type == KeyType.ESCAPE) {
            close();
            return true;
        }
        if (type == KeyType.ENTER) {
            if (length == 0) return true;
            char[] password = Arrays.copyOf(input, length);
            try {
                result = SudoPasswordInteraction.Result.provided(password);
                submitted = true;
            } finally {
                Arrays.fill(password, '\0');
                wipeInput();
            }
            super.close();
            return true;
        }
        if (type == KeyType.BACKSPACE) {
            if (length > 0) input[--length] = '\0';
            renderSecret();
            return true;
        }
        if (type == KeyType.PASTE && key instanceof PasteKeyStroke paste) {
            append(paste.getPastedText());
            return true;
        }
        if (type == KeyType.CHARACTER && key.getCharacter() != null
                && !key.isCtrlDown() && !key.isAltDown()) {
            append(key.getCharacter());
            return true;
        }
        return true;
    }

    @Override
    public void close() {
        if (!submitted) result = SudoPasswordInteraction.Result.cancelled();
        wipeInput();
        super.close();
    }

    private void append(String text) {
        if (text == null) return;
        for (int index = 0; index < text.length(); index++) append(text.charAt(index));
    }

    private void append(char character) {
        if (length >= input.length || Character.isISOControl(character)) return;
        input[length++] = character;
        renderSecret();
    }

    private void renderSecret() {
        secretLabel.setText("•".repeat(length) + "█");
    }

    private void wipeInput() {
        Arrays.fill(input, '\0');
        length = 0;
        // The dialog is closing, so do not repaint the empty-field cursor. A final
        // "█" update can otherwise race with window removal and remain as a terminal
        // artifact until the next full-screen refresh.
        secretLabel.setText("");
    }

    private static String cropCommand(String command) {
        if (command == null) return "";
        StringBuilder sanitized = new StringBuilder(command.length());
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            sanitized.append(Character.isISOControl(character) ? ' ' : character);
        }
        String singleLine = sanitized.toString();
        return singleLine.length() <= 96 ? singleLine : singleLine.substring(0, 93) + "...";
    }

    String renderedSecretForTest() {
        return "•".repeat(length);
    }

    SudoPasswordInteraction.Result resultForTest() {
        return result;
    }
}

package com.claudecode.ui.lanterna.dialog;

import com.claudecode.keybindings.UserKeybindingsStore;
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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Startup warning shown before entering a session that bypasses all permission checks.
 */
public final class BypassPermissionsModeDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 76;
    private static final int CONTENT_WIDTH = 76;
    private static final String TITLE =
        "WARNING: Claude Code running in Bypass Permissions mode";
    private static final String FIRST_PARAGRAPH =
        """
        In Bypass Permissions mode, Claude Code will not ask for your approval \
        before running potentially dangerous commands.
        This mode should only be used in a sandboxed container/VM that has \
        restricted internet access and can easily be restored if damaged.""";
    private static final String RESPONSIBILITY =
        "By proceeding, you accept all responsibility for actions taken while "
        + "running in Bypass Permissions mode.";
    private static final String SECURITY_GUIDE =
        "https://code.claude.com/docs/en/security";
    private static final String FOOTER = "Enter to confirm · Esc to cancel";

    private final int terminalRows;
    private final ConfirmationPrompt confirmation = new ConfirmationPrompt(
        "Yes, I accept", "No, exit", true,
        ConfirmationPrompt.Choice.CANCEL, this::invalidate);
    private boolean active;
    private Runnable onAccept;
    private Runnable onDecline;
    private Runnable onEscape;

    public BypassPermissionsModeDialog() {
        this(24);
    }

    public BypassPermissionsModeDialog(int terminalRows) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.terminalRows = Math.max(1, terminalRows);
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        confirmation.setKeybindingsStore(store);
    }

    public synchronized void prompt(Runnable onAccept, Runnable onDecline,
                                    Runnable onEscape) {
        this.onAccept = onAccept;
        this.onDecline = onDecline;
        this.onEscape = onEscape;
        this.active = true;
        confirmation.activate(
            () -> resolve(this.onAccept),
            () -> resolve(this.onDecline),
            () -> resolve(this.onEscape));
        invalidate();
    }

    @Override
    public synchronized boolean isActive() {
        return active;
    }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        confirmation.handleKey(key, deliver);
    }

    private synchronized void resolve(Runnable action) {
        if (!active) return;
        hide();
        if (action != null) action.run();
    }

    private synchronized void hide() {
        active = false;
        confirmation.deactivate();
        onAccept = null;
        onDecline = null;
        onEscape = null;
        invalidate();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()),
            Math.max(totalRows(), terminalRows - 1));
    }

    @Override
    public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override
    public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    private static int totalRows() {
        return 1 + 1 + 1 + DialogText.wrapWords(FIRST_PARAGRAPH, CONTENT_WIDTH).size() + 1
            + DialogText.wrapWords(RESPONSIBILITY, CONTENT_WIDTH).size()
            + 1 + 1 + 1 + 2 + 1 + 1;
    }

    private final class DialogArea extends AbstractComponent<DialogArea> {
        @Override
        protected ComponentRenderer<DialogArea> createDefaultRenderer() {
            return new DialogRenderer();
        }
    }

    private final class DialogRenderer implements ComponentRenderer<DialogArea> {
        @Override
        public TerminalSize getPreferredSize(DialogArea component) {
            if (!active) return new TerminalSize(0, 0);
            return new TerminalSize(LEFT_PAD * 2 + MIN_WIDTH, totalRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, DialogArea component) {
            if (!active) return;
            graphics.fill(' ');
            int columns = graphics.getSize().getColumns();
            int row = 0;

            graphics.setForegroundColor(LanternaTheme.divider());
            graphics.putString(0, row++, "─".repeat(Math.max(0, columns)));

            graphics.setForegroundColor(LanternaTheme.bypassRed());
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(LEFT_PAD, row++, InlineOverlay.clip(TITLE, columns - LEFT_PAD));
            graphics.disableModifiers(SGR.BOLD);
            row++;

            graphics.setForegroundColor(LanternaTheme.inputText());
            for (String line : DialogText.wrapWords(FIRST_PARAGRAPH, CONTENT_WIDTH)) {
                graphics.putString(LEFT_PAD, row++, InlineOverlay.clip(line, columns - LEFT_PAD));
            }
            row++;
            for (String line : DialogText.wrapWords(RESPONSIBILITY, CONTENT_WIDTH)) {
                graphics.putString(LEFT_PAD, row++, InlineOverlay.clip(line, columns - LEFT_PAD));
            }
            row++;
            graphics.setForegroundColor(LanternaTheme.suggestion());
            graphics.putString(LEFT_PAD, row++, SECURITY_GUIDE);
            row++;

            graphics.setForegroundColor(LanternaTheme.inputText());
            graphics.putString(LEFT_PAD, row++, optionLine(
                ConfirmationPrompt.Choice.CANCEL, 1, "No, exit"));
            graphics.putString(LEFT_PAD, row++, optionLine(
                ConfirmationPrompt.Choice.CONFIRM, 2, "Yes, I accept"));
            row++;
            graphics.setForegroundColor(LanternaTheme.welcomeDim());
            graphics.putString(LEFT_PAD, row, FOOTER);
        }

        private String optionLine(ConfirmationPrompt.Choice choice,
                                  int number, String label) {
            return (confirmation.isFocused(choice) ? "❯ " : "  ")
                + number + ". " + label;
        }
    }
}

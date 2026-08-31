package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
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
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;

/**
 * Startup "trust this folder?" dialog — shown once per interactive session when the current working
 * directory has not yet been trusted.
 */
public final class TrustFolderDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 70;
    /** Width available for wrapped text (drawn at x = LEFT_PAD). */
    private static final int CONTENT_WIDTH = LEFT_PAD + MIN_WIDTH;

    private static final String TITLE = "Accessing workspace:";
    private static final String SAFETY =
        "Quick safety check: Is this a project you created or one you trust? "
        + "(Like your own code, a well-known open source project, or work from your team). "
        + "If not, take a moment to review what's in this folder first.";
    private static final String ABILITY =
        "Claude Code'll be able to read, edit, and execute files here.";
    private static final String SECURITY_GUIDE =
        "Security guide: https://code.claude.com/docs/en/security";
    private static final String FOOTER = "Enter to confirm · Esc to cancel";

    private boolean active;
    /** 'c' or 'd' while a single Ctrl-C/Ctrl-D has been seen and is awaiting the second press. */
    private Character pendingExitKey;
    /** Timestamp (ms) of the pending arming — double-press must land within {@link #DOUBLE_PRESS_TIMEOUT_MS}. */
    private long pendingExitTime;
    private Path cwd;
    private Runnable onTrust;
    private Runnable onExit;
    private final ContextKeybindingDispatcher confirmationBindings =
        new ContextKeybindingDispatcher();
    private final ConfirmationPrompt confirmation = new ConfirmationPrompt(
        "Yes, I trust this folder", "No, exit", false,
        ConfirmationPrompt.Choice.CONFIRM, this::onConfirmationChanged);


    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    public TrustFolderDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        confirmationBindings.setStore(store);
        confirmation.setKeybindingsStore(store);
    }

    /**
     * Activate the dialog. May be called from any thread (startup runs it before
     * the GUI thread is up); {@code handleKey} always runs on the GUI thread.
     *
     * @param cwd     the working directory being questioned.
     * @param onTrust called when the user accepts (trust + persist).
     * @param onExit  called when the user declines or presses Esc (shut down).
     */
    public synchronized void prompt(Path cwd, Runnable onTrust, Runnable onExit) {
        this.cwd = cwd;
        this.onTrust = onTrust;
        this.onExit = onExit;
        this.pendingExitKey = null;
        this.pendingExitTime = 0;
        this.active = true;
        confirmation.activate(
            () -> resolve(this.onTrust),
            () -> resolve(this.onExit));
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        ContextKeybindingDispatcher.Result confirmation =
            confirmationBindings.resolve("Confirmation", key);
        if (confirmation instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (confirmation instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            resolve(onExit);
            deliver.set(false);
            return;
        }
        KeyType t = key.getKeyType();
        this.confirmation.handleKey(key, deliver);
        if (!deliver.get()) return;
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null) {
            char ch = key.getCharacter();
            if (ch == 'c' || ch == 'd') {
                if (pendingExitKey != null && pendingExitKey == ch
                        && System.currentTimeMillis() - pendingExitTime <= DOUBLE_PRESS_TIMEOUT_MS) {
                    resolve(onExit);
                } else {
                    armExitPending(ch);
                }
                deliver.set(false);
            }
        }
    }

    private synchronized void onConfirmationChanged() {
        pendingExitKey = null;
        invalidate();
    }

    /** Arms (or re-arms) the pending-exit state and schedules its auto-revert after the timeout. */
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
            if (active && pendingExitKey != null && pendingExitKey == ch && pendingExitTime == armedAt) {
                pendingExitKey = null;
                invalidate();
            }
        });
    }

    private synchronized void resolve(Runnable action) {
        if (!active) return;
        hide();
        if (action != null) action.run();
    }

    private synchronized void hide() {
        active = false;
        confirmation.deactivate();
        pendingExitKey = null;
        pendingExitTime = 0;
        onTrust = null;
        onExit = null;
        invalidate();
    }

    // ── sizing ───────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()), totalRows());
    }

    /** Total occupied rows, derived from the (variable) wrapped safety paragraph. */
    private int totalRows() {
        int safetyLines = DialogText.wrapWords(SAFETY, CONTENT_WIDTH).size();
        // 0 divider, 1 title, 2 cwd, [safety], ability, security-guide, spacer, 2 options, footer
        return 1 + 1 + 1 + safetyLines + 1 + 1 + 1 + 2 + 1;
    }

    @Override public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Renderer
    // ──────────────────────────────────────────────────────────────────────────

    private final class DialogArea extends AbstractComponent<DialogArea> {
        @Override protected ComponentRenderer<DialogArea> createDefaultRenderer() {
            return new DialogRenderer();
        }
    }

    private final class DialogRenderer implements ComponentRenderer<DialogArea> {

        @Override
        public TerminalSize getPreferredSize(DialogArea c) {
            return new TerminalSize(LEFT_PAD * 2 + MIN_WIDTH, totalRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics g, DialogArea c) {
            if (!active) return;
            g.fill(' ');
            int cols = g.getSize().getColumns();

            g.setForegroundColor(LanternaTheme.divider());
            g.putString(0, 0, "─".repeat(Math.max(0, cols)));

            g.setForegroundColor(LanternaTheme.permission());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 1, TITLE);
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.inputText());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, 2, InlineOverlay.clip(
                cwd != null ? cwd.toAbsolutePath().normalize().toString() : "", cols - LEFT_PAD));
            g.disableModifiers(SGR.BOLD);

            int row = 3;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (String line : DialogText.wrapWords(SAFETY, CONTENT_WIDTH)) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip(line, cols - LEFT_PAD));
            }
            g.putString(LEFT_PAD, row++, InlineOverlay.clip(ABILITY, cols - LEFT_PAD));
            g.putString(LEFT_PAD, row++, InlineOverlay.clip(SECURITY_GUIDE, cols - LEFT_PAD));

            row++; // spacer

            drawOption(g, LEFT_PAD, row++, ConfirmationPrompt.Choice.CONFIRM,
                "Yes, I trust this folder");
            drawOption(g, LEFT_PAD, row++, ConfirmationPrompt.Choice.CANCEL,
                "No, exit");

            g.setForegroundColor(LanternaTheme.welcomeDim());
            g.enableModifiers(SGR.ITALIC);
            g.putString(LEFT_PAD, row, footerText());
            g.disableModifiers(SGR.ITALIC);
        }

        private String footerText() {
            if (pendingExitKey != null) {
                return "Press Ctrl+" + (pendingExitKey == 'c' ? "C" : "D") + " again to exit";
            }
            return FOOTER;
        }

        private void drawOption(TextGUIGraphics g, int x, int row,
                                ConfirmationPrompt.Choice choice, String label) {
            boolean isSelected = confirmation.isFocused(choice);
            g.setForegroundColor(isSelected ? LanternaTheme.suggestion() : LanternaTheme.inputText());
            g.putString(x, row, (isSelected ? "❯ " : "  ") + label);
        }
    }
}

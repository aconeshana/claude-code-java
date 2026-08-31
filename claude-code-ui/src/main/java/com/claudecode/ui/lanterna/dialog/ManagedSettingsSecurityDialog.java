package com.claudecode.ui.lanterna.dialog;

import org.apache.commons.lang3.Strings;

import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.lanterna.input.ContextKeybindingDispatcher;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Startup "managed settings require approval" dialog — shown (interactive sessions only) when
 * enterprise/MDM managed settings contain dangerous entries (arbitrary code execution or
 * prompt/response interception).
 */
public final class ManagedSettingsSecurityDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 72;
    /** Width available for wrapped text (drawn at x = LEFT_PAD). */
    private static final int CONTENT_WIDTH = LEFT_PAD + MIN_WIDTH;

    private static final String TITLE = "Managed settings require approval";
    private static final String WARNING =
        "Your organization has configured managed settings that could allow "
        + "execution of arbitrary code or interception of your prompts and responses.";
    private static final String LEAD = "Settings requiring approval:";
    private static final String TRUST_LINE =
        "Only accept if you trust your organization's IT administration and "
        + "expect these settings to be configured.";
    private static final String FOOTER = "Enter to confirm · Esc to exit";

    private boolean active;
    /** 'c' or 'd' while a single Ctrl-C/Ctrl-D has been seen and is awaiting the second press. */
    private Character pendingExitKey;
    /** Timestamp (ms) of the pending arming — double-press must land within {@link #DOUBLE_PRESS_TIMEOUT_MS}. */
    private long pendingExitTime;
    private List<String> dangerousItems = List.of();
    private Runnable onAccept;
    private Runnable onExit;
    private final ContextKeybindingDispatcher confirmationBindings =
        new ContextKeybindingDispatcher();
    private final ConfirmationPrompt confirmation = new ConfirmationPrompt(
        "Yes, I trust these settings", "No, exit Claude Code", false,
        ConfirmationPrompt.Choice.CONFIRM, this::onConfirmationChanged);


    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    public ManagedSettingsSecurityDialog() {
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
     * Activate the dialog.
     */
    public synchronized void prompt(Path cwd, List<String> dangerousItems,
            Runnable onAccept, Runnable onExit) {
        this.dangerousItems = (dangerousItems == null) ? List.of() : dangerousItems;
        this.onAccept = onAccept;
        this.onExit = onExit;
        this.pendingExitKey = null;
        this.pendingExitTime = 0;
        this.active = true;
        confirmation.activate(
            () -> resolve(this.onAccept),
            () -> resolve(this.onExit));
        invalidate();
    }

    @Override public boolean isActive() { return active; }

    @Override
    public synchronized void handleKey(KeyStroke key, AtomicBoolean deliver) {
        if (!active) return;
        KeyType t = key.getKeyType();
        if (t == KeyType.CHARACTER && key.isCtrlDown() && key.getCharacter() != null
                && (key.getCharacter() == 'c' || key.getCharacter() == 'd')) {
            handleExitPress(key.getCharacter());
            deliver.set(false);
            return;
        }
        ContextKeybindingDispatcher.Result confirmationResult =
            confirmationBindings.resolve("Confirmation", key);
        if (confirmationResult instanceof ContextKeybindingDispatcher.Result.Consumed) {
            deliver.set(false);
            return;
        }
        if (confirmationResult instanceof ContextKeybindingDispatcher.Result.Action(String value)
                && Strings.CS.equals("confirm:no", value)) {
            resolve(onExit);
            deliver.set(false);
            return;
        }
        confirmation.handleKey(key, deliver);
    }

    private synchronized void onConfirmationChanged() {
        pendingExitKey = null;
        invalidate();
    }

    private void handleExitPress(char ch) {
        if (pendingExitKey != null && pendingExitKey == ch
                && System.currentTimeMillis() - pendingExitTime <= DOUBLE_PRESS_TIMEOUT_MS) {
            resolve(onExit);
        } else {
            armExitPending(ch);
        }
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
        dangerousItems = List.of();
        onAccept = null;
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

    /** Total occupied rows, derived from the (variable) wrapped warning + the item list. */
    private int totalRows() {
        int warningLines = DialogText.wrapWords(WARNING, CONTENT_WIDTH).size();
        int trustLines = DialogText.wrapWords(TRUST_LINE, CONTENT_WIDTH).size();
        int items = dangerousItems.size();
        // 0 divider, 1 title, [warning], 1 lead, [items], 1 spacer, [trust], 2 options, 1 footer
        return 1 + 1 + warningLines + 1 + items + 1 + trustLines + 2 + 1;
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
            if (!active) return new TerminalSize(0, 0);
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

            int row = 2;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (String line : DialogText.wrapWords(WARNING, CONTENT_WIDTH)) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip(line, cols - LEFT_PAD));
            }

            row++; // spacer before lead
            g.setForegroundColor(LanternaTheme.inputText());
            g.enableModifiers(SGR.BOLD);
            g.putString(LEFT_PAD, row++, InlineOverlay.clip(LEAD, cols - LEFT_PAD));
            g.disableModifiers(SGR.BOLD);

            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (String item : dangerousItems) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip("· " + item, cols - LEFT_PAD));
            }

            row++; // spacer before trust line
            for (String line : DialogText.wrapWords(TRUST_LINE, CONTENT_WIDTH)) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip(line, cols - LEFT_PAD));
            }

            g.setForegroundColor(LanternaTheme.suggestion());
            g.putString(LEFT_PAD, row++, (confirmation.isFocused(
                ConfirmationPrompt.Choice.CONFIRM) ? "❯ " : "  ")
                + "Yes, I trust these settings");
            g.putString(LEFT_PAD, row++, (confirmation.isFocused(
                ConfirmationPrompt.Choice.CANCEL) ? "❯ " : "  ")
                + "No, exit Claude Code");

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
    }
}

package com.claudecode.ui.lanterna.dialog;

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
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;

/**
 * Second interactive startup gate (after {@link TrustFolderDialog}): warns when the project's
 * {@code CLAUDE.md} (@)imports memory files located outside the current working directory — a
 * third-party repo could otherwise pull in instructions from arbitrary paths.
 */
public final class ClaudeMdExternalIncludesDialog extends Panel implements InlineOverlay {

    private static final int LEFT_PAD = 2;
    private static final int MIN_WIDTH = 72;
    private static final int CONTENT_WIDTH = LEFT_PAD + MIN_WIDTH;

    private static final String TITLE = "Allow external CLAUDE.md file imports?";
    private static final String INTRO =
        "This project's CLAUDE.md imports files outside the current working directory. "
        + "Never allow this for third-party repositories.";
    private static final String EXTERNAL_HEADER = "External imports:";
    private static final String IMPORTANT =
        "Important: Only use Claude Code with files you trust. "
        + "Accessing untrusted files may pose security risks";
    private static final String SECURITY_GUIDE =
        "Security guide: https://code.claude.com/docs/en/security";
    private static final String FOOTER = "↑/↓ to choose · Enter to confirm · Esc to cancel";

    private boolean active;
    private Character pendingExitKey;
    private long pendingExitTime;
    private List<String> externals;
    private Runnable onAllow;
    private Runnable onDisable;
    private Runnable onExit;
    private final ConfirmationPrompt confirmation = new ConfirmationPrompt(
        "Yes, allow external imports", "No, disable external imports", false,
        ConfirmationPrompt.Choice.CONFIRM, this::onConfirmationChanged);


    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800L;

    public ClaudeMdExternalIncludesDialog() {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        confirmation.setKeybindingsStore(store);
    }

    /**
     * Activate the dialog. May be called from any thread; {@code handleKey}
     * always runs on the GUI thread.
     *
     * @param cwd       the working directory (kept for provenance; the detected
     *                  external include paths are passed explicitly in {@code externals}).
     * @param externals the absolute paths of {@code @}-imported memory files
     *                  found outside {@code cwd} (non-empty when this is shown).
     * @param onAllow   called when the user allows external imports.
     * @param onDisable called when the user declines (Esc or "No") — the session
     *                  continues, external imports are disabled for this project.
     * @param onExit    called on a genuine double Ctrl+C / Ctrl+D.
     */
    public synchronized void prompt(Path cwd, List<String> externals,
                                    Runnable onAllow, Runnable onDisable, Runnable onExit) {
        this.externals = externals == null ? List.of() : List.copyOf(externals);
        this.onAllow = onAllow;
        this.onDisable = onDisable;
        this.onExit = onExit;
        this.pendingExitKey = null;
        this.pendingExitTime = 0;
        this.active = true;
        confirmation.activate(
            () -> resolve(this.onAllow),
            () -> resolve(this.onDisable));
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
        externals = null;
        onAllow = null;
        onDisable = null;
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

    private int totalRows() {
        int rows = 1; // divider
        rows += 1;   // title
        rows += DialogText.wrapWords(INTRO, CONTENT_WIDTH).size();
        if (externals != null && !externals.isEmpty()) {
            rows += 1 + externals.size(); // "External imports:" header + each path
        }
        rows += DialogText.wrapWords(IMPORTANT, CONTENT_WIDTH).size();
        rows += 1; // security guide link
        rows += 1; // spacer
        rows += 2; // options
        rows += 1; // footer
        return rows;
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

            int row = 2;
            g.setForegroundColor(LanternaTheme.welcomeDim());
            for (String line : DialogText.wrapWords(INTRO, CONTENT_WIDTH)) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip(line, cols - LEFT_PAD));
            }

            if (externals != null && !externals.isEmpty()) {
                g.putString(LEFT_PAD, row++, EXTERNAL_HEADER);
                for (String path : externals) {
                    g.putString(LEFT_PAD + 2, row++,
                        InlineOverlay.clip(path, cols - LEFT_PAD - 2));
                }
            }

            for (String line : DialogText.wrapWords(IMPORTANT, CONTENT_WIDTH)) {
                g.putString(LEFT_PAD, row++, InlineOverlay.clip(line, cols - LEFT_PAD));
            }
            g.putString(LEFT_PAD, row++, InlineOverlay.clip(SECURITY_GUIDE, cols - LEFT_PAD));

            row++; // spacer

            drawOption(g, LEFT_PAD, row++, ConfirmationPrompt.Choice.CONFIRM,
                "Yes, allow external imports");
            drawOption(g, LEFT_PAD, row++, ConfirmationPrompt.Choice.CANCEL,
                "No, disable external imports");

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

package com.claudecode.ui.lanterna.dialog;

import com.claudecode.core.engine.RefusalFallbackPrompt;
import com.claudecode.core.message.RefusalFallbackDecision;
import com.claudecode.core.message.RefusalFallbackPromptCopy;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.ui.Ansi;
import com.claudecode.ui.lanterna.dialog.RefusalFallbackBody.Run;
import com.claudecode.ui.lanterna.overlay.InlineOverlay;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TerminalTextUtils;
import com.googlecode.lanterna.TextCharacter;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.AbstractComponent;
import com.googlecode.lanterna.gui2.ComponentRenderer;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.MultiWindowTextGUI;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextGUIGraphics;
import com.googlecode.lanterna.input.KeyStroke;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The dialog a refused turn opens so the user can pick which model answers it.
 */
public final class RefusalFallbackDialog extends Panel implements InlineOverlay {

    /** {@code Dm}'s title row padding. */
    private static final int TITLE_PAD = 1;




    private static final int LEFT_PAD = 2;

    private static final int MIN_WIDTH = 76;
    private static final int CONTENT_WIDTH = 76;
    private static final String TITLE = "Session paused";

    private final int terminalRows;

    /**
     * Terminal hyperlink capability. Production reads the process environment;
     * tests inject a constant, matching {@code LanternaMessageDispatcher}.
     */
    private BooleanSupplier hyperlinkSupport = Ansi::supportsHyperlinks;

    private UserKeybindingsStore keybindingsStore;
    private boolean active;
    private ConfirmationPrompt confirmation;
    private List<List<Run>> bodyLines = List.of();
    private List<String> guidanceLines = List.of();
    private String switchLabel = "";
    private String editLabel = "";
    private Consumer<RefusalFallbackDecision.Choice> resultConsumer;
    private Runnable onClose;

    public RefusalFallbackDialog() {
        this(24);
    }

    public RefusalFallbackDialog(int terminalRows) {
        super(new LinearLayout(Direction.VERTICAL).setSpacing(0));
        this.terminalRows = Math.max(1, terminalRows);
        DialogArea area = new DialogArea();
        area.setLayoutData(LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(area);
    }

    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        ConfirmationPrompt current = confirmation;
        if (current != null) current.setKeybindingsStore(store);
    }

    /** Test seam for {@link #hyperlinkSupport}; production keeps the environment probe. */
    void setHyperlinkSupport(BooleanSupplier support) {
        this.hyperlinkSupport = support;
    }

    // ── entry point (turn thread) ────────────────────────────────────────────

    /**
     * Blocks the calling thread until the user picks an option or dismisses the
     * dialog. Must not be called from the Lanterna GUI thread.
     *
     * @return the user's answer, never null
     */
    public RefusalFallbackDecision.Choice showAndWait(MultiWindowTextGUI gui,
                                                     RefusalFallbackPrompt.Request request,
                                                     Runnable onCloseCb) {
        BlockingQueue<RefusalFallbackDecision.Choice> queue = new ArrayBlockingQueue<>(1);
        gui.getGUIThread().invokeLater(() -> prompt(request, choice -> {
            try {
                queue.put(choice);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }, onCloseCb));
        try {
            return queue.take();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return RefusalFallbackDecision.Choice.CANCELLED;
        }
    }

    // ── GUI thread ───────────────────────────────────────────────────────────

    synchronized void prompt(RefusalFallbackPrompt.Request request,
                             Consumer<RefusalFallbackDecision.Choice> onChoice) {
        prompt(request, onChoice, null);
    }

    private synchronized void prompt(RefusalFallbackPrompt.Request request,
                                     Consumer<RefusalFallbackDecision.Choice> onChoice,
                                     Runnable onCloseCb) {
        this.resultConsumer = onChoice;
        this.onClose = onCloseCb;
        this.switchLabel = RefusalFallbackPromptCopy.switchLabel(request.fallbackModel());
        this.editLabel = RefusalFallbackPromptCopy.editLabel(request.refusedModel());
        this.bodyLines = RefusalFallbackBody.lines(
            RefusalFallbackPromptCopy.body(request.refusedModel(), request.category()),
            hyperlinkSupport.getAsBoolean(), CONTENT_WIDTH);
        this.guidanceLines = request.guidanceText() == null ? List.of()
            : List.copyOf(DialogText.wrapWords(request.guidanceText(), CONTENT_WIDTH));
        // Labels come from the payload, so the two-choice state is per prompt.
        this.confirmation = new ConfirmationPrompt(switchLabel, editLabel, false,
            ConfirmationPrompt.Choice.CONFIRM, this::invalidate);
        this.confirmation.setKeybindingsStore(keybindingsStore);
        this.active = true;
        this.confirmation.activate(
            () -> resolve(RefusalFallbackDecision.Choice.RETRY_FALLBACK),
            () -> resolve(RefusalFallbackDecision.Choice.EDIT_PROMPT),
            () -> resolve(RefusalFallbackDecision.Choice.CANCELLED));
        invalidate();
    }

    @Override
    public synchronized boolean isActive() {
        return active;
    }

    @Override
    public void handleKey(KeyStroke key, AtomicBoolean deliver) {
        ConfirmationPrompt current;
        synchronized (this) {
            if (!active) return;
            current = confirmation;
        }
        if (current != null) current.handleKey(key, deliver);
    }

    private void resolve(RefusalFallbackDecision.Choice choice) {
        Consumer<RefusalFallbackDecision.Choice> consumer;
        Runnable closeCallback;
        synchronized (this) {
            if (!active) return;
            consumer = resultConsumer;
            closeCallback = onClose;
            hide();
        }
        if (consumer != null) consumer.accept(choice);
        if (closeCallback != null) closeCallback.run();
    }

    private synchronized void hide() {
        active = false;
        if (confirmation != null) confirmation.deactivate();
        resultConsumer = null;
        onClose = null;
        invalidate();
    }

    // ── layout ───────────────────────────────────────────────────────────────

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (!active) return new TerminalSize(0, 0);
        TerminalSize parent = super.calculatePreferredSize();
        return new TerminalSize(Math.max(MIN_WIDTH, parent.getColumns()),
            Math.max(contentRows(), terminalRows - 1));
    }

    @Override
    public Interactable nextFocus(Interactable fromThis) {
        return active ? super.nextFocus(fromThis) : null;
    }

    @Override
    public Interactable previousFocus(Interactable fromThis) {
        return active ? super.previousFocus(fromThis) : null;
    }

    /** Rows the dialog draws: frame, body, optional guidance block, options. */
    synchronized int contentRows() {
        int guidance = guidanceLines.isEmpty() ? 0 : 1 + guidanceLines.size();
        return 1 + 1 + 1 + 1 + bodyLines.size() + guidance + 1 + 2;
    }

    String title() {
        return TITLE;
    }

    synchronized List<List<Run>> bodyLines() {
        return bodyLines;
    }

    synchronized List<String> guidanceLines() {
        return guidanceLines;
    }

    /** The option rows without the frame's left padding, focus marker included. */
    synchronized List<String> optionLines() {
        return List.of(
            optionLine(ConfirmationPrompt.Choice.CONFIRM, switchLabel),
            optionLine(ConfirmationPrompt.Choice.CANCEL, editLabel));
    }

    private String optionLine(ConfirmationPrompt.Choice choice, String label) {
        boolean focused = confirmation != null && confirmation.isFocused(choice);
        return (focused ? "❯ " : "  ") + label;
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
            if (!isActive()) return new TerminalSize(0, 0);
            return new TerminalSize(LEFT_PAD * 2 + MIN_WIDTH, contentRows());
        }

        @Override
        public void drawComponent(TextGUIGraphics graphics, DialogArea component) {
            if (!isActive()) return;
            graphics.fill(' ');
            int columns = graphics.getSize().getColumns();
            TextColor accent = LanternaTheme.statusCost();
            int row = 1;                                    // Dm marginTop={1}

            graphics.setForegroundColor(accent);
            graphics.putString(0, row++, "─".repeat(Math.max(0, columns)));
            graphics.enableModifiers(SGR.BOLD);
            graphics.putString(TITLE_PAD, row++, InlineOverlay.clip(TITLE, columns - TITLE_PAD));
            graphics.disableModifiers(SGR.BOLD);
            row++;                                          // inner column marginTop={1}

            for (List<Run> line : bodyLines()) {
                drawRuns(graphics, LEFT_PAD, row++, columns, line);
            }
            List<String> guidance = guidanceLines();
            if (!guidance.isEmpty()) {
                row++;
                graphics.setForegroundColor(LanternaTheme.welcomeDim());
                for (String line : guidance) {
                    graphics.putString(LEFT_PAD, row++,
                        InlineOverlay.clip(line, columns - LEFT_PAD));
                }
            }
            row++;
            graphics.setForegroundColor(LanternaTheme.inputText());
            for (String line : optionLines()) {
                graphics.putString(LEFT_PAD, row++, InlineOverlay.clip(line, columns - LEFT_PAD));
            }
        }

        /**
         * Draws one wrapped row cell by cell. {@code putString} cannot carry a
         * hyperlink, so the run's url has to be attached per character — the same
         * shape {@code MessagePanel} uses for transcript rows.
         */
        private void drawRuns(TextGUIGraphics graphics, int left, int row,
                              int columns, List<Run> runs) {
            int column = left;
            for (Run run : runs) {
                for (int i = 0; i < run.text().length() && column < columns; i++) {
                    char character = run.text().charAt(i);
                    if (Character.isISOControl(character)) continue;
                    TextCharacter cell = TextCharacter
                        .fromCharacter(character, run.color(), TextColor.ANSI.DEFAULT);
                    if (!run.modifiers().isEmpty()) {
                        cell = cell.withModifiers(run.modifiers());
                    }
                    if (run.hyperlinkUrl() != null) {
                        cell = cell.withHyperlink(run.hyperlinkUrl());
                    }
                    graphics.setCharacter(column, row, cell);
                    column += TerminalTextUtils.isCharDoubleWidth(character) ? 2 : 1;
                }
            }
        }
    }
}

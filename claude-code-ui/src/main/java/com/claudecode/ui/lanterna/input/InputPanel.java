package com.claudecode.ui.lanterna.input;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import com.claudecode.ui.lanterna.components.HighlightedTextBox.Highlight;
import com.claudecode.ui.lanterna.suggest.SuggestionPanel;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.CursorStyle;
import com.googlecode.lanterna.SGR;
import com.googlecode.lanterna.TerminalPosition;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.Component;
import com.googlecode.lanterna.gui2.Container;
import com.googlecode.lanterna.gui2.Direction;
import com.googlecode.lanterna.gui2.Interactable;
import com.googlecode.lanterna.gui2.Label;
import com.googlecode.lanterna.gui2.LinearLayout;
import com.googlecode.lanterna.gui2.Panel;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

/**
 * Input area for the Lanterna prompt component stack: the composition root that mounts the
 * prompt widgets (queued preview, dividers, {@code ❯} pointer + {@link PromptTextBox},
 * suggestions, status line, hint row, footer) and refreshes them from {@link PromptState}.
 *
 * <p>It owns no key routing itself. Keys go to {@link PromptTextBox}; the footer selection to
 * {@link PromptFooter}; hints to {@link PromptHintBar}; chips, paste, vim and suggestions to
 * their {@code Prompt*} collaborators. What this class keeps is the public REPL-facing API,
 * the {@code *Host} adapters those collaborators call back through, mode/colour/hint refresh,
 * history-entry application, the queued-command preview and the query-change scheduler.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — component layout order (queued
 *       commands, top border, mode indicator + text input, bottom border, suggestions, footer)
 *       and the {@code onInputChange} / {@code setPastedContents} notifications.</li>
 *   <li>{@code src/components/PromptInput/PromptInputModeIndicator.tsx} — the {@code ❯} /
 *       {@code !} pointer, dim while loading, bash colour in bash mode.</li>
 *   <li>{@code src/components/PromptInput/PromptInputQueuedCommands.tsx} — the italic queued
 *       prompt preview above the border.</li>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} history application — recalled
 *       entries restore their pasted contents and keep their mode with the prefix stripped.</li>
 * </ul>
 */
public class InputPanel extends Panel {

    private static final int PROMPT_INPUT_COLUMN_OVERHEAD = 3;

    public enum Mode { NORMAL, BASH }

    /** Mode, text snapshot, REPL flags and draft undo shared with the key pipeline. */
    private final PromptState state = new PromptState();
    private String permMode;

    private BooleanSupplier bypassPermissionsModeAvailable = () -> true;

    // Vim mode state
    /** Vim keybinding bridge; owns the state machine, the INSERT label and the re-entrancy fence. */
    private final PromptVimAdapter vim = new PromptVimAdapter(new VimHost());

    // Child components
    private final Label  promptLabel;
    private final PromptTextBox textBox;
    /**
     * Cached visual row count, including.
     */
    private int currentTextRows = 1;
    /** Width of the editable content region before PromptTextLayout reserves its cursor cell. */
    private int inputContentColumns = 80 - PROMPT_INPUT_COLUMN_OVERHEAD;
    /** Immutable visual projection rebuilt after text or pane-width changes. */
    private PromptTextLayout textLayout = PromptTextLayout.create("", inputContentColumns);

    private List<HighlightedTextBox.Highlight> currentHighlights = List.of();
    private HighlightedTextBox.Highlight historySearchHighlight;
    /** Exact-command argument hint painted inline after the caret, never in the footer. */
    private volatile String argumentHint;
    /** Hint row labels + status line; owns hint priority and temporary notifications. */
    private final PromptHintBar hintBar;
    /** Established prompt hint/tasks row; coordinator rows are mounted after it. */
    private final Panel hintRow;
    /**
     * Every keyboard-selectable control below the text box (≡ button, tasks
     * pill, coordinator rows, workflow rows, Collaboration) and the single
     * selection walking between them.
     */
    private final PromptFooter footer;

    /** Top/bottom rules with their shared border color and the session-name/history badges. */
    private final PromptDividers dividers = new PromptDividers();
    /** Atomic {@code [Image #N]} / pasted-text chip editing over the text box. */
    private final PromptChipEditor chips;
    /** Slash/@/bash-path dropdown between the divider and the hint row, plus its accept/fill rules. */
    private final PromptSuggestionBridge suggestions = new PromptSuggestionBridge(new SuggestionHost());

    /** Reactive queued-input preview above the prompt divider; never enters transcript history. */
    private final Panel queuedPreviewPanel;
    /** Plain lines retained for deterministic headless tests and change-gated rerenders. */
    private List<String> queuedPreviewLines = List.of();

    /**
     * The single outward port for every REPL action / notification this panel
     * fires — submit, cancel, overlay toggles, permission-mode change,
     * message-actions navigation, and the query / pasted-content / cursor-style
     * / focus notifications. Replaces the former bag of ~19 individual
     * {@code setOnXxx} callback setters. See {@link InputActions} for the
     * anti-rot invariant (new REPL action → new interface method, NOT a new
     * {@code onXxx} field + setter).
     */
    private InputActions actions;

    /** Opt-in keybinding store, kept here only for the history-search shortcut hint. */
    private UserKeybindingsStore keybindingsStore;

    // Images are inserted as [Image #N] chips in the textBox; orphaned chips
    // (deleted by the user) are pruned on every text change.
    private final PromptPastedContentController pastedContent =
        new PromptPastedContentController();
    {
        chips = new PromptChipEditor(new ChipHost(), pastedContent);
    }

    /** Editor access and prompt actions for the vim bridge. */
    private final class VimHost implements PromptVimAdapter.Host {
        @Override public String text() { return textBox.getText(); }
        @Override public int caret() { return caretCol(); }
        @Override public void setTextRaw(String text) { textBox.setText(text); }
        @Override public void moveCaretTo(int offset) { InputPanel.this.moveCaretTo(offset); }
        @Override public TextBox.Result forwardToEditor(KeyStroke key) { return textBox.handleKeyStroke(key); }
        @Override public TextBox.Result historyUp() { return textBox.rl_historyUp(); }
        @Override public TextBox.Result historyDown() { return textBox.rl_historyDown(); }
        @Override public boolean chipBackspace() {
            if (!chips.backspace()) return false;
            chips.pruneOrphanedImages();
            return true;
        }
        @Override public boolean chipDelete() {
            if (!chips.delete()) return false;
            chips.pruneOrphanedImages();
            return true;
        }
        @Override public void textEdited() { updateMode(); fireQueryChange(); }
        @Override public void submit(String text) {
            resetMode();
            if (!StringUtils.isBlank(text) && actions != null) {
                actions.submit(state.prependModePrefix(text));
            }
        }
        @Override public void cancel() { if (actions != null) actions.cancel(); }
        @Override public void cursorStyleChanged(CursorStyle style) {
            if (actions != null) actions.cursorStyleChanged(style);
        }
    }

    /** Editor access for suggestion fills. */
    private final class SuggestionHost implements PromptSuggestionBridge.Host {
        @Override public String text() { return textBox.getText(); }
        @Override public int caret() { return caretCol(); }
        @Override public void apply(PromptSuggestionBridge.Edit edit) {
            textBox.setText(edit.text());
            TextBoxOffsetAdapter.setOffset(textBox, edit.caret());
            updateMode();
            fireQueryChange();
        }
    }

    /** Panel services for the paste flow. */
    private final class PasteHost implements PromptPasteHandler.Host {
        @Override public int terminalRows() { return InputPanel.this.terminalRows(); }
        @Override public Consumer<Runnable> guiInvoker() { return guiInvoker; }
        @Override public String sessionId() { return sessionIdentity.get(); }
        @Override public void insertChip(String chip, boolean armLazySpace) {
            chips.insertAtCursor(chip, armLazySpace);
            firePastedContentsChange();
        }
        @Override public void refreshHint() { updateHint(); }
        @Override public void submitDeferred() {
            textBox.submit();
        }
    }

    /** Text-box access for the chip editor plus the panel's post-edit notifications. */
    private final class ChipHost implements PromptChipEditor.Host {
        @Override public String text() { return textBox.getText(); }
        @Override public int caret() { return caretCol(); }
        @Override public void setTextRaw(String text) { textBox.setText(text); }
        @Override public void moveCaretTo(int offset) { InputPanel.this.moveCaretTo(offset); }
        @Override public void textEdited() { updateMode(); fireQueryChange(); }
        @Override public void recordUndoSnapshot() { state.draftUndo().record(captureDraftSnapshot()); }
        @Override public void pastedContentsChanged() { firePastedContentsChange(); }
    }

    // Defaults to an unshared identity so a bare `new InputPanel` (tests,
    // any caller that doesn't wire a session) keeps working; real wiring
    // replaces this via wireSessionIdentity with the SAME instance the
    // QuerySession/HookEngine use, so a single switchToSession call is
    // visible here too without a separate setSessionId sync step.
    private SessionIdentity sessionIdentity = SessionIdentity.newRandom();

    /** Scheduler onto the Lanterna GUI thread — set by LanternaReplScreen. */
    private Consumer<Runnable> guiInvoker;
    /**
     * Input bytes already waiting in one PTY read are dispatched separately by
     * Lanterna, but the suggestion/query consumer only needs their final prompt
     * state. Keep at most one delivery queued for the current GUI cycle.
     */
    private boolean queryChangeScheduled;
    /** Invalidates a queued query callback when a batch publishes synchronously. */
    private long queryChangeGeneration;

    // ── History navigation state ───────────────────────────────────────────── ── History
    // navigation (Up/Down/Ctrl+R) — extracted to InputHistoryController ──.
    private final InputHistoryController historyController =
        new InputHistoryController(new InputEditingSurface() {
            @Override public String currentText() { return textBox.getText(); }
            @Override public Mode currentModeOverride() { return state.modeOverride(); }
            @Override public Map<Integer, PastedContent> snapshotPasted() {
                return pastedContent.snapshot();
            }
            @Override public int currentCursorOffset() { return caretCol(); }
            @Override public void applyEntry(PromptHistory.Entry entry, boolean cursorToStart) {
                applyHistoryEntry(entry, cursorToStart);
            }
            @Override public void restoreDraft(String text, Mode mode,
                                               Map<Integer, PastedContent> pasted,
                                               boolean cursorToStart) {
                state.setModeOverride(mode);
                textBox.setText(text);
                TextBoxOffsetAdapter.setOffset(textBox, cursorToStart ? 0 : text.length());
                restorePastedContents(pasted);
            }
            @Override public void applySearchEntry(PromptHistory.Entry entry, int cursorOffset) {
                applyHistoryEntry(entry, false);
                moveCaretTo(Math.min(cursorOffset, textBox.getText().length()));
            }
            @Override public void restoreSearchDraft(String text, Mode mode,
                                                     Map<Integer, PastedContent> pasted,
                                                     int cursorOffset) {
                state.setModeOverride(mode);
                textBox.setText(text);
                restorePastedContents(pasted);
                moveCaretTo(Math.min(cursorOffset, text.length()));
                updateMode();
                fireQueryChange();
            }
            @Override public void setText(String text) { textBox.setText(text); }
            @Override public void setTextCaretEnd(String text) {
                textBox.setText(text);
                moveCaretToTextEnd();
            }
            @Override public void refreshModeAndQuery() { updateMode(); fireQueryChange(); }
            @Override public void showHint(String text, TextColor color, long timeoutMs) {
                showTemporaryHint(text, color, timeoutMs);
            }
            @Override public void setHistoryLabel(String label) {
                dividers.setHistoryLabel(label);
            }
            @Override public String historySearchShortcut() {
                return KeybindingHints.shortcut(keybindingsStore,
                    "history:search", "Global", "ctrl+r");
            }
            @Override public void setHistorySearchStatus(String query, boolean failedMatch) {
                hintBar.setHistorySearchStatus(query, failedMatch);
            }
            @Override public void setHistorySearchHighlight(int start, int length) {
                historySearchHighlight = length <= 0 ? null
                    : new HighlightedTextBox.Highlight(start, start + length,
                        LanternaTheme.toolWarning(), false, 20);
                refreshInputHighlights();
                textBox.invalidate();
            }
            @Override public void invokeLater(Runnable task) {
                if (guiInvoker != null) guiInvoker.accept(task);
                else task.run();
            }
        });

    /** Move to the real final line/column without synthesizing a re-entrant End key. */
    private void moveCaretToTextEnd() {
        int row = Math.max(0, textBox.getLineCount() - 1);
        textBox.setCaretPosition(row, textBox.getLine(row).length());
    }

    private DraftUndoBuffer.Snapshot captureDraftSnapshot() {
        return state.draftSnapshot(caretCol(), pastedContent.snapshot());
    }

    private static final ScheduledExecutorService ESC_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "esc-timeout");
            t.setDaemon(true);
            return t;
        });
    /** Clipboard/bracketed paste classification, pending-paste gate and deferred submit. */
    private final PromptPasteHandler paste =
        new PromptPasteHandler(new PasteHost(), pastedContent);

    /**
     * When true, the panel reports {@code (0,0)} preferred size so its host layout collapses it.
     */
    private boolean suppressed = false;

    /** Hide / restore the entire prompt bar. Caller must call {@code invalidate()}
     *  via the parent so SmartLayout re-runs. */
    public void setSuppressed(boolean suppressed) {
        this.suppressed = suppressed;
        invalidate();
    }

    @Override
    public synchronized TerminalSize calculatePreferredSize() {
        if (suppressed) return new TerminalSize(0, 0);
        return super.calculatePreferredSize();
    }

    // ── Construction ────────────────────────────────────────────────────────

    public InputPanel() {
        this("default");
    }

    public InputPanel(String initialPermissionMode) {
        this.permMode = StringUtils.isBlank(initialPermissionMode)
            ? "default" : initialPermissionMode;
        setLayoutManager(new LinearLayout(Direction.VERTICAL));

        queuedPreviewPanel = new Panel(new LinearLayout(Direction.VERTICAL).setSpacing(0));

        promptLabel = new Label("❯ ");
        promptLabel.setForegroundColor(LanternaTheme.toolSuccess());

        // Inline highlights painted over the input text.

        // ultrareview, buddy, tokenBudget, slackChannel, btw); add more
        // suppliers as their commands are implemented.
        Supplier<List<Highlight>> highlightSupplier = () -> currentHighlights;
        hintBar = new PromptHintBar(ESC_SCHEDULER, this::updateHint);
        footer = new PromptFooter(new FooterHost());
        textBox = new PromptTextBox(new TerminalSize(80, 1), highlightSupplier,
            new PromptHost(), state, ESC_SCHEDULER,
            new PromptTextBox.Collaborators(
                pastedContent, chips, paste, vim, suggestions, historyController, footer));
        // Keep the TextBox's explicitPreferredSize.rows in lock-step with the
        // current lineCount so the wrapping SmartLayout gives us the right
        // vertical slice after Shift+Enter (add row), Backspace-merge (remove
        // row), setText("") on submit (reset to 1), paste-inline (grow), etc.
        //
        // Rationale: Lanterna's TextBox constructor calls setPreferredSize()
        // with the initial (80, 1), which becomes explicitPreferredSize.
        // AbstractComponent.getPreferredSize() short-circuits to that value
        // and never consults renderer.getPreferredSize() again — so the field
        // is permanently 1-row tall unless we refresh it ourselves.
        textBox.setTextChangeListener((newText, byUser) -> {
            state.setText(newText);
            refreshInputHighlights();
            // A genuine keystroke edit (typing / backspacing) detaches the line from the history
            // cursor, so the slash/@ suggestion dropdown resumes — WITHOUT tearing down the
            // navigation state (index / draft / cache), so Up keeps stepping and Down still
            // returns to the draft. History-apply uses textBox.setText(...) → byUser=false, so
            // arrowing through recalled entries stays suppressed.
            if (byUser && historyController.hasHistoryCursor()) {
                historyController.onUserEdit();
            }
            refreshTextLayout();
        });

        Panel promptRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        promptRow.addComponent(promptLabel);
        // FILL (stretch to promptRow's height) + CAN_GROW (absorb every column
        // promptRow has left over after promptLabel) — without CAN_GROW, a
        // horizontal LinearLayout's FILL only stretches the CROSS axis (rows);
        // the MAIN axis (columns) stays pinned at the component's own
        // preferred width forever. textBox's preferred width starts at the
        // constructor's TerminalSize(80, 1) and is never recomputed to the
        // real terminal width elsewhere (the textChangeListener below only
        // ever touches preferred *rows*, carrying the *columns* forward
        // unchanged) — so without CAN_GROW here, textBox is invisibly capped
        // at ~80 columns no matter how wide the real terminal is. A pasted
        // single line wider than that (e.g. a Finder drag-and-drop path with
        // a CJK filename, where each character costs 2 terminal columns)
        // scrolls to keep the caret visible and hides its own beginning —
        // which the real terminal window had plenty of room to show in full.
        promptRow.addComponent(textBox,
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL, LinearLayout.GrowPolicy.CAN_GROW));

        // Inline ghost text — dim argument hint shown at the caret for commands that
        // have a progressive argument contract.

        // tracks the latest selected suggestion + current input.
        textBox.setGhostTextSupplier(this::inlineGhostText);
        textBox.setVisualLayoutSupplier(
            () -> textLayout, this::caretCol);

        hintRow = new Panel(new LinearLayout(Direction.HORIZONTAL));
        // ≡ is the leftmost footer control, so it is also the first keyboard
        // stop (↓/→ walk the footer left→right).
        hintRow.addComponent(footer.projectsButtonComponent());
        hintRow.addComponent(hintBar.mainLabel());
        hintRow.addComponent(hintBar.suffixLabel());
        // PromptInputFooterLeftSide's [modePart][tasksPart][...parts] order.
        // Both task labels are empty (zero-width) when no background tasks exist.
        hintRow.addComponent(footer.tasksPillsPanel());
        hintRow.addComponent(footer.tasksHintLabel());
        hintRow.addComponent(vim.label());
        // Construct before updateHint(): a surviving teammate-view selection
        // may ask the navigation host to clear this component during initial
        // projection (full-suite tests expose the same process-lifetime state
        // that a real session resume can carry).
        updateHint();

        addComponent(queuedPreviewPanel,
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(dividers.top());
        // FILL so promptRow (and in turn textBox, via its own FILL+CAN_GROW
        // layout data above) actually receives InputPanel's real width from
        // the outer VERTICAL LinearLayout — without it, promptRow is sized to
        // the sum of its children's own preferred widths (promptLabel +
        // textBox's pinned ~80 columns), capped well short of a wide terminal.
        addComponent(promptRow, LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(dividers.bottom());
        addComponent(suggestions.component());   // ← below divider, above hint

        // left column). FILL so it receives InputPanel's real width for
        // truncation — Alignment.FILL stretches the cross-axis (= width in a
        // VERTICAL layout); see the promptRow note above.
        addComponent(hintBar.statusLine(),
            LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        addComponent(hintRow);
        addComponent(footer.collaborationRow());
    }

    /** The footer's view of this panel: action port, hint/status refresh, GUI marshalling. */
    private final class FooterHost implements PromptFooter.Host {
        @Override public InputActions actions() { return actions; }
        @Override public void refreshHint() { updateHint(); }
        @Override public void clearStatusLine() { clearTransientStatusLine(); }
        @Override public void setTransientStatusLine(String text, int padding) {
            InputPanel.this.setTransientStatusLine(text, padding);
        }
        @Override public void showTemporaryHint(String text, TextColor color, long timeoutMs) {
            InputPanel.this.showTemporaryHint(text, color, timeoutMs);
        }
        @Override public int footerWidth() { return dividers.width(); }
        @Override public void runOnGui(Runnable task) {
            Consumer<Runnable> invoker = guiInvoker;
            if (invoker == null || isOnGuiThread()) task.run();
            else invoker.accept(task);
        }
    }

    /** Panel actions the key pipeline needs beyond its injected collaborators. */
    private final class PromptHost implements PromptTextBox.Host {
        @Override public int caretCol() { return InputPanel.this.caretCol(); }
        @Override public PromptTextLayout textLayout() { return textLayout; }
        @Override public int terminalRows() { return InputPanel.this.terminalRows(); }
        @Override public void setText(String text) { InputPanel.this.setText(text); }
        @Override public void fireQueryChange() { InputPanel.this.fireQueryChange(); }
        @Override public void deliverQueryChangeImmediately() {
            InputPanel.this.deliverQueryChangeImmediately();
        }
        @Override public void pastedContentsChanged() { firePastedContentsChange(); }
        @Override public void updateMode() { InputPanel.this.updateMode(); }
        @Override public void resetMode() { InputPanel.this.resetMode(); }
        @Override public void resetHistory() { InputPanel.this.resetHistory(); }
        @Override public void refreshHint() { updateHint(); }
        @Override public void showTemporaryHint(String text, TextColor color, long timeoutMs) {
            InputPanel.this.showTemporaryHint(text, color, timeoutMs);
        }
        @Override public void cancelTemporaryHint() { hintBar.cancelTemporary(); }
        @Override public void cyclePermissionMode() { InputPanel.this.cyclePermissionMode(); }
        @Override public boolean popEditableQueuedCommands() {
            return InputPanel.this.popEditableQueuedCommands();
        }
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    private void refreshInputHighlights() {
        List<HighlightedTextBox.Highlight> next = new ArrayList<>();
        List<BtwTriggers.Trigger> btwTriggers = BtwTriggers.find(state.text());
        if (!btwTriggers.isEmpty()) {
            next.add(new HighlightedTextBox.Highlight(
                btwTriggers.getFirst().start(), btwTriggers.getFirst().end(),
                BtwTriggers.highlightColor(), false, 15));
        }
        if (historySearchHighlight != null
                && historySearchHighlight.start() >= 0
                && historySearchHighlight.end() <= state.text().length()) {
            next.add(historySearchHighlight);
        }
        currentHighlights = List.copyOf(next);
    }

    private void refreshTextLayout() {
        PromptTextLayout next = PromptTextLayout.create(state.text(), inputContentColumns);
        int rows = next.lineCount();
        textLayout = next;
        if (rows != currentTextRows) {
            currentTextRows = rows;
            // explicitPreferredSize is always non-null because TextBox's constructor
            // installs it. Preserve the growable preferred width and update only height.
            TerminalSize preferred = textBox.getPreferredSize();
            int columns = preferred != null ? preferred.getColumns() : 80;
            textBox.setPreferredSize(new TerminalSize(columns, rows));
            textBox.invalidate();
            // Lanterna invalidation does not bubble. Only walk ancestors when geometry
            // changes; doing it for ordinary characters would restore the former
            // child-to-parent lock-order risk on every keystroke.
            for (Container parent = textBox.getParent();
                 parent != null; parent = parent.getParent()) {
                parent.invalidate();
            }
        }
    }

    public void setWidth(int width) {
        dividers.setWidth(width);
    }

    /**
     * Refresh divider text every time SmartLayout hands us a new size —
     * the initial {@link #setWidth} in LanternaReplScreen fires once at
     * startup, but the actual columns InputPanel receives can change on
     * subsequent layouts (Shift+Enter growing textBox rows shrinks
     * messagePanel; window resize; Welcome dialog dismiss). Without this
     * override the bottom divider stays stuck at the startup width and
     * ends up shorter than the panel.
     */
    @Override
    public synchronized InputPanel setSize(TerminalSize size) {
        super.setSize(size);
        if (size != null) {
            int contentColumns = Math.max(1,
                size.getColumns() - PROMPT_INPUT_COLUMN_OVERHEAD);
            if (contentColumns != inputContentColumns) {
                inputContentColumns = contentColumns;
                refreshTextLayout();
            }
            if (size.getColumns() != dividers.width()) setWidth(size.getColumns());
        }
        return this;
    }

    /** Set (or clear) the session name shown in the top divider. */
    public void setAgentName(String name) {
        dividers.setSessionName(name);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Renders {@code text} (possibly ANSI-colored, multi-line) as the custom status line above the hint
     * row, with {@code padding} horizontal inset.
     */
    public void setStatusLine(String text, int padding) {
        hintBar.setStatusLine(text, padding);
    }

    /** Clears the custom status line (collapses to zero height). */
    public void clearStatusLine() {
        hintBar.clearStatusLine();
    }

    /** Shows progress/navigation text without replacing the persistent HUD. */
    public void setTransientStatusLine(String text, int padding) {
        hintBar.setTransientStatusLine(text, padding);
    }

    /** Clears transient progress/navigation text and restores the persistent HUD. */
    public void clearTransientStatusLine() {
        hintBar.clearTransientStatusLine();
    }

    /** Wire the single outward action/notification port (replaces ~19 {@code setOnXxx} setters). */
    public void setActions(InputActions actions) {
        this.actions = actions;
        textBox.setActions(actions);
    }

    /** Installs the opt-in keybinding resolver. Null when customization is disabled. */
    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        textBox.setKeybindingsStore(store);
    }

    /**
     * Routes keyboard focus back to the text box. Used after a transient
     * overlay (e.g. the inline permission prompt) closes — we want the next
     * keystroke to go into the prompt, not the now-empty overlay region.
     */
    public void takeFocus() {
        if (textBox != null) textBox.takeFocus();
    }

    public void setHasMessages(BooleanSupplier supplier) { state.setHasMessages(supplier); }

    public void setIsLoading(boolean loading) {
        if (state.setLoading(loading)) {
            updatePromptColor();
            updateHint();
            invalidate();
        }
    }

    /** Re-fire the current text+cursor through {@code actions.queryChanged} (called from background threads via invokeLater). */
    public void triggerQueryChange() { fireQueryChange(); }

    /** Wire the Lanterna GUI thread invoker so chip insertion off-thread is safe. */
    public void setGuiInvoker(Consumer<Runnable> invoker) {
        this.guiInvoker = invoker;
    }

    /** Starts one terminal-read input batch; called only by the GUI host. */
    public void beginGuiInputBatch() {
        textBox.beginGuiInputBatch();
    }

    /** Accepts a decoded printable run when the prompt is the active input owner. */
    public boolean handleGuiTextBatch(Interactable focused, String text) {
        if (!textBox.isInGuiInputBatch() || focused != textBox || textBox.getInputFilter() != null) {
            return false;
        }
        return textBox.bufferPlainText(text);
    }

    boolean handleGuiTextBatchForTest(String text) {
        return textBox.isInGuiInputBatch() && textBox.bufferPlainText(text);
    }

    /** Accepts a decoded Backspace run when the prompt is the active input owner. */
    public boolean handleGuiBackspaceBatch(Interactable focused, int count) {
        if (!textBox.isInGuiInputBatch() || focused != textBox || textBox.getInputFilter() != null) {
            return false;
        }
        return textBox.bufferGuiBackspaces(count);
    }

    boolean handleGuiBackspaceBatchForTest(int count) {
        return textBox.isInGuiInputBatch() && textBox.bufferGuiBackspaces(count);
    }

    /** Publishes the final prompt state after Lanterna drains the PTY queue. */
    public void endGuiInputBatch() {
        textBox.endGuiInputBatch();
    }

    /**
     * Wires the shared {@link SessionIdentity} used for
     * {@code ImageStore.cacheImagePath} — pass the SAME instance the
     * QuerySession/HookEngine hold so a single {@code switchToSession} call
     * is visible here too.
     */
    public void wireSessionIdentity(SessionIdentity sessionIdentity) {
        this.sessionIdentity = sessionIdentity;
    }

    /**
     * Apply a user-selected prompt-bar color from {@code /color}.
     */
    public void setSessionColor(String colorName) {
        // The session color paints the input's TOP/BOTTOM BORDER, not the
        // prompt pointer: updatePromptColor keeps ❯ in its mode-only color.
        dividers.setSessionColor(colorName);
        updatePromptColor();
        invalidate();
    }

    /** Returns a copy of the current pasted contents. */
    public Map<Integer, PastedContent> getPastedContents() {
        return pastedContent.snapshot();
    }

    /**
     * Public hook so external owners (LanternaReplScreen) can refresh the
     * hint row after mutating pasted contents off-thread.
     */
    public void refreshHint() {
        updateHint();
    }

    private void firePastedContentsChange() {
        if (actions != null) {
            actions.pastedContentsChanged(getPastedContents());
        }
    }

    /** Package-private for {@code InputPanel*} tests (was exposed via {@code caretOffsetForTest()}). */
    int caretCol() {
        try {
            if (currentTextRows == 1 && textBox != null) {
                // The hot path is single-line. TextBoxOffsetAdapter otherwise
                // asks Lanterna for line count, line content, and full text on
                // every key; the row-local column is already the absolute
                // UTF-16 offset in this geometry.
                return Math.max(0, Math.min(
                    textBox.getCaretPosition().getColumn(), state.text().length()));
            }
            return TextBoxOffsetAdapter.offset(textBox);
        } catch (Exception _) {
            return 0;
        }
    }

    private void moveCaretTo(int col) {
        // Use setCaretPosition directly — sending synthetic HOME + ARROW_RIGHT
        // keystrokes routes back through the textBox's overridden
        // handleKeyStroke, which calls hopRightOverChip → moveCaretTo and
        // recurses until StackOverflowError. setCaretPosition bypasses the
        // input event chain entirely.
        TextBoxOffsetAdapter.setOffset(textBox, col);
    }

    public void setVimEnabled(boolean enabled) {
        vim.setEnabled(enabled);
    }

    public void setQueuedHint(boolean queued) {
        if (queued) {
            hintBar.showQueuedHint();
        } else {
            updateHint();
        }
    }

    /**
     * Render a live prompt-area queue projection. Unlike the former transcript
     * append path, these rows disappear when commands are popped or drained.
     */
    public void setQueuedCommands(List<QueuedCommand> commands) {
        List<String> immutableLines = QueuedPromptPreviewFormatter.format(commands);
        if (queuedPreviewLines.equals(immutableLines)) {
            setQueuedHint(!immutableLines.isEmpty());
            return;
        }
        queuedPreviewLines = immutableLines;
        queuedPreviewPanel.removeAllComponents();
        for (String line : queuedPreviewLines) {
            Label label = new Label(line);
            label.setForegroundColor(LanternaTheme.queuedText());
            label.addStyle(SGR.ITALIC);
            queuedPreviewPanel.addComponent(label,
                LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
        }
        setQueuedHint(!queuedPreviewLines.isEmpty());
        queuedPreviewPanel.invalidate();
    }

    private boolean popEditableQueuedCommands() {
        if (actions == null) return false;
        QueuedInputDraft restored = actions.popEditableQueuedCommands(getText(), caretCol());
        if (restored == null) return false;

        state.suppressUndoRecording();
        state.resetMode();
        textBox.setText(restored.text());
        moveCaretTo(Math.min(restored.cursorOffset(), restored.text().length()));
        restored.pastedContents().values().forEach(pastedContent::put);
        hideSuggestions();
        historyController.reset();
        updateMode();
        firePastedContentsChange();
        fireQueryChange();
        setQueuedHint(false);
        if (vim.isEnabled()) vim.syncBuffer();
        invalidate();
        return true;
    }

    /**
     * Show suggestion items below the input divider.
     * @param items    list of (primary, description) pairs
     * @param termW    terminal width for column sizing
     */
    public void showSuggestions(List<SuggestionPanel.Suggestion> items, int termW) {
        suggestions.show(items, termW);
    }

    /** Show path completions for the current bash token. */
    public void showBashPathSuggestions(List<SuggestionPanel.Suggestion> items, int termW) {
        suggestions.showBashPaths(items, termW);
    }

    /**
     * Show suggestion items with a pre-computed name-column width — see
     * {@link SuggestionPanel#setSuggestions(List, int, int)}.
     */
    public void showSuggestions(List<SuggestionPanel.Suggestion> items, int termW, int commandColumnWidth) {
        suggestions.show(items, termW, commandColumnWidth);
    }

    /** Hide the suggestion dropdown. */
    public void hideSuggestions() {
        suggestions.hide();
    }

    /** Current displayed input mode; bash text itself intentionally omits the leading {@code !}. */
    public boolean isBashMode() {
        return state.isBashMode();
    }

    /**
     * Show an argument hint as dim text directly after the input caret.
     */
    public void setArgumentHint(String hint) {
        argumentHint = StringUtils.isBlank(hint) ? null : hint;
        textBox.invalidate();
    }

    private String inlineGhostText() {
        String hint = argumentHint;
        if (StringUtils.isEmpty(hint)) return null;
        return Strings.CS.endsWith(state.text(), " ") ? hint : " " + hint;
    }

    String inlineGhostTextForTest() {
        return inlineGhostText();
    }

    public void cyclePermissionMode() {

        // can stay strict — see PermissionMode.fromString / PermissionGate.parseMode.
        permMode = PermissionModeCycle.next(
            permMode, bypassPermissionsModeAvailable.getAsBoolean());
        updateHint();
        if (actions != null) actions.permissionModeChanged(permMode);
    }

    /** Installs the live session policy used by the Shift+Tab mode cycle. */
    public void setBypassPermissionsModeAvailable(BooleanSupplier available) {
        bypassPermissionsModeAvailable = available != null ? available : () -> true;
        if (!bypassPermissionsModeAvailable.getAsBoolean()
                &&Strings.CS.equals( "bypassPermissions", permMode)) {
            permMode = "default";
            updateHint();
        }
    }

    /** Returns the current permission mode (e.g. "bypass", "auto", "plan"). */
    public String getPermissionMode() { return this.permMode; }

    public String getVimMode() {
        return vim.modeName();
    }

    /** Programmatically set the permission mode and refresh the hint row. */
    public void setPermissionMode(String mode) {
        this.permMode = mode;
        updateHint();
    }

    public String getText() { return state.text(); }

    /**
     * Live terminal height, used by the paste chip thresholds: official derives
     * its newline cap from the current rows, so a two-line paste stays editable
     * on a tall terminal but folds on a short one.
     */
    private int terminalRows() {
        var gui = getTextGUI();
        if (gui == null || gui.getScreen() == null) {
            return PromptPasteTextPolicy.DEFAULT_TERMINAL_ROWS;
        }
        int rows = gui.getScreen().getTerminalSize().getRows();
        return rows > 0 ? rows : PromptPasteTextPolicy.DEFAULT_TERMINAL_ROWS;
    }

    /**
     * Set the input text programmatically (e.g., from external editor).
     */
    public void setText(String text) {
        PromptChipEditor.Truncation truncated = chips.truncateForInput(text);
        textBox.setText(truncated.text());
        moveCaretToTextEnd();
        updateMode();
        fireQueryChange();
    }

    /**
     * Restores image pasted contents from a UserMessage (after rewind).
     */
    public void restoreImageChips(Map<Integer, PastedContent> images) {
        if (images == null || images.isEmpty()) return;
        restorePastedContents(images);
    }

    /** Replaces all image paste state, including clearing it when the replacement is empty. */
    public void replaceImageChips(Map<Integer, PastedContent> images) {
        restorePastedContents(images != null ? images : Map.of());
    }

    /**
     * Called by LanternaReplScreen after auto-restore (Esc interrupt) or MessageSelector.
     */
    public void setRestoredText(String text) {
        if (text == null) return;
        state.setModeOverride(InputModes.overrideFromPrefix(text));
        PromptChipEditor.Truncation truncated = chips.truncateForInput(InputModes.stripPrefix(text));
        textBox.setText(truncated.text());
        if (truncated.applied()) {
            // END only reaches the end of the FIRST line; a truncated value is
            // routinely multi-line, so use the real final line/column.
            moveCaretToTextEnd();
        } else {
            textBox.handleKeyStroke(new KeyStroke(
                KeyType.END, false, false));
        }
        updateMode();
        fireQueryChange();
    }

    /** Wire a PromptHistory so Up/Down arrows navigate history. */
    public void setPromptHistory(PromptHistory history) {
        historyController.setPromptHistory(history);
    }

/**
     * Set session ID and project for history filtering.
     */
    public void setHistoryContext(String sessionId, String project) {
        historyController.setContext(sessionId, project);
    }

    /** Viewed teammate/local-agent prompts replace disk history while that transcript is active. */
    public void setLiveHistorySupplier(
            Supplier<List<PromptHistory.Entry>> liveHistorySupplier) {
        historyController.setLiveHistorySupplier(liveHistorySupplier);
    }

    /**
     * Whether the slash/@ suggestion dropdown should be suppressed right now — true while the user is
     * navigating history or reverse-i-searching.
     */
    public boolean isSuppressingSuggestions() {
        return historyController.isNavigating();
    }

    /**
     * Reset history navigation state after a submit.
     */
    public void resetHistory() {
        historyController.reset();
        textBox.resetDoubleEscapeGate();
        state.setModeOverride(null);

        hintBar.cancelTemporary();
        updateHint();
    }

    // History navigation (Up=Ctrl+P / Down=Ctrl+N) + reverse-i-search moved to
    // InputHistoryController.

    /**
     * Apply a history entry: set text, restore pasted contents, and advance the pasted-content
     * controller's id sequence.
     */
    private void applyHistoryEntry(PromptHistory.Entry entry, boolean cursorToStart) {
        String display = entry.display();
        // Strip mode prefix BEFORE checking for newlines — the prefix is
        // single-char and matters for state.modeOverride(), but the mode char '!'
        // is never a newline itself.
        Mode entryMode = InputModes.overrideFromPrefix(display);
        String body = InputModes.stripPrefix(display);
        state.setModeOverride(entryMode);
        // drop a truncation chip registered before it (and it also advances the id
        // sequence past the entry's own chips so the new id can't collide).

        // directly editable instead of being collapsed into a pasted-text chip.
        restorePastedContents(entry.pastedContents());
        PromptChipEditor.Truncation truncated = chips.truncateForInput(body);
        textBox.setText(truncated.text());
        if (truncated.applied()) {
            moveCaretToTextEnd();
        } else {
            TextBoxOffsetAdapter.setOffset(textBox,
                cursorToStart ? 0 : truncated.text().length());
        }
        updateMode();
        fireQueryChange();
    }

    /** Apply an entry selected by the Ctrl+R history picker, with the caret at the end. */
    public void applyHistoryPickerEntry(PromptHistory.Entry entry) {
        applyHistoryEntry(entry, false);
    }

    /**
     * Replace current pasted contents with the given map, bumping the controller's id sequence past any
     * incoming id so future Ctrl+V doesn't collide.
     */
    private void restorePastedContents(Map<Integer, PastedContent> restored) {
        pastedContent.restore(restored);
        firePastedContentsChange();
    }

    private void fireQueryChange() {

        // PromptInput useEffect([input, setPastedContents]).
        if (!pastedContent.isEmpty()) chips.pruneOrphanedImages();
        if (actions == null) return;

        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null || (!textBox.isInGuiInputBatch() && isOnGuiThread())) {
            deliverQueryChange();
            return;
        }
        if (queryChangeScheduled) return;
        queryChangeScheduled = true;
        long generation = ++queryChangeGeneration;
        invoker.accept(() -> {
            if (!queryChangeScheduled || generation != queryChangeGeneration) return;
            queryChangeScheduled = false;
            deliverQueryChange();
        });
    }

    private void deliverQueryChangeImmediately() {
        queryChangeScheduled = false;
        queryChangeGeneration++;
        if (!pastedContent.isEmpty()) chips.pruneOrphanedImages();
        deliverQueryChange();
    }

    private boolean isOnGuiThread() {
        return getTextGUI() != null
            && getTextGUI().getGUIThread().getThread() == Thread.currentThread();
    }

    private void deliverQueryChange() {
        InputActions currentActions = actions;
        if (currentActions != null) currentActions.queryChanged(state.text(), caretCol());
    }

    /** Notify InputPanel when transcript mode is active (gates Ctrl+E routing). */
    public void setTranscriptMode(boolean active) {
        state.setTranscriptMode(active);
    }

    /** Set message actions active state (intercepts keys for navigation). */
    public void setMessageActionsActive(boolean active) {
        state.setMessageActionsActive(active);
        updateHint();
    }

    /** Update the action list shown in the pinned footer while browsing messages. */
    public void setMessageActionsHint(String hint) {
        state.setMessageActionsHint(hint);
        if (state.isMessageActionsActive()) updateHint();
    }

    // ── Mode ─────────────────────────────────────────────────────────────────

    private void updateMode() {
        String text = vim.isEnabled() ? vim.buffer() : state.text();
        if (state.recomputeMode(text)) {
            updatePromptColor();
            updateHint();
        }
    }

    private void resetMode() {
        state.resetMode();
        updatePromptColor();
        updateHint();
    }

    /**
     * Repaints the {@code ❯}/{@code !} pointer and tells the dividers which
     * border color applies.
     *
     * <p>The pointer is dim while loading, {@code bashBorder} in bash mode and
     * otherwise carries no foreground SGR at all so the terminal palette
     * default shows through. The plan-mode visual cue lives in the footer chip,
     * never in the pointer, and the {@code /color} session color paints only the
     * dividers.
     */
    private void updatePromptColor() {
        dividers.setBashMode(state.isBashMode());
        TextColor color;
        if (state.isLoading()) {
            color = LanternaTheme.welcomeDim();  // dimColor=true wins
        } else if (state.isBashMode()) {
            color = LanternaTheme.bashBorder();
        } else {
            color = null;  // Use the terminal's default foreground.
        }
        promptLabel.setForegroundColor(color);
        // Update prompt symbol: bash uses "! ", others use "❯ "
        promptLabel.setText(state.isBashMode() ? "! " : Figures.POINTER + " ");
    }

    /**
     * Public shim over {@link #showTemporaryHint} for callers outside
     * InputPanel (REPL Ctrl+C handler etc.). Uses {@code welcomeDim} styling
     * to match the existing "Esc again to clear" / reverse-i-search hint look.
     */
    public void showTransientHint(String text, long timeoutMs) {
        showTemporaryHint(text, LanternaTheme.welcomeDim(), timeoutMs);
    }

    /**
     * Temporarily replaces the hint row with a notification for {@code timeoutMs}, then restores the
     * normal hint.
     */
    private void showTemporaryHint(String text, TextColor color, long timeoutMs) {
        hintBar.showTemporary(text, color, timeoutMs);
    }

    /** Whether the subagent coordinator panel currently owns keyboard focus. */
    boolean isCoordinatorPanelSelected() {
        return footer.isCoordinatorPanelSelected();
    }

    /** Mirrors the project drawer's open state on the ≡ button. */
    public void setProjectsButtonActive(boolean active) {
        footer.setProjectsButtonActive(active);
    }

    public boolean handleTasksPillMouse(MouseAction mouse) {
        Panel pills = footer.tasksPillsPanel();
        return footer.handleTasksPillMouse(mouse, pills.getGlobalPosition(), pills.getSize());
    }

    /** Click handling for the footer ≡ button. Java-side extension, no 197 counterpart. */
    public boolean handleProjectsButtonMouse(MouseAction mouse) {
        Label button = footer.projectsButtonComponent();
        return footer.handleProjectsButtonMouse(mouse, button.getGlobalPosition(), button.getSize());
    }

    boolean handleProjectsButtonMouseForTest(MouseAction mouse, TerminalPosition origin,
                                             TerminalSize size) {
        return footer.handleProjectsButtonMouse(mouse, origin, size);
    }

    /** Click/hover handling for the {@code main}/subagent coordinator panel rows. */
    public boolean handleCoordinatorPanelMouse(MouseAction mouse) {
        Component component = footer.coordinatorComponent();
        if (component == null) return false;
        return footer.handleCoordinatorPanelMouse(
            mouse, component.getGlobalPosition(), component.getSize());
    }

    boolean handleCoordinatorPanelMouseForTest(MouseAction mouse, TerminalPosition origin,
                                               TerminalSize size) {
        return footer.handleCoordinatorPanelMouse(mouse, origin, size);
    }

    ScheduledFuture<?> footerRefreshFutureForTest() { return footer.refreshFutureForTest(); }
    boolean isProjectsButtonSelectedForTest() { return footer.isProjectsButtonSelected(); }
    boolean isProjectsButtonActiveForTest() { return footer.isProjectsButtonActive(); }

    /**
     * Handles the established prompt wrapper's bare-click cursor positioning.
     * Coordinates are absolute terminal cells and the hit target begins at the
     * text box, deliberately excluding the prompt glyph and layout gap.
     */
    public boolean handlePromptBareClick(int screenColumn, int screenRow) {
        return handlePromptBareClickForTest(screenColumn, screenRow,
            textBox.getGlobalPosition(), textBox.getSize());
    }

    boolean handlePromptBareClickForTest(int screenColumn, int screenRow,
                                         TerminalPosition origin, TerminalSize size) {
        if (origin == null || size == null
                || screenColumn < origin.getColumn()
                || screenColumn >= origin.getColumn() + size.getColumns()
                || screenRow < origin.getRow()
                || screenRow >= origin.getRow() + size.getRows()) {
            return false;
        }

        if (historyController.isSearching()) return true;

        clearFooterSelection();
        textBox.takeFocus();
        if (state.text().isEmpty()) return true;

        int localColumn = screenColumn - origin.getColumn();
        int localRow = screenRow - origin.getRow();
        int offset = textLayout.offsetAt(new PromptTextLayout.Position(localRow, localColumn));
        TextBoxOffsetAdapter.setOffset(textBox, offset);
        textBox.invalidate();
        fireQueryChange();
        return true;
    }

    private void clearFooterSelection() {
        footer.clearSelection();
    }

    boolean handleTasksPillMouseForTest(MouseAction mouse, TerminalPosition origin,
                                        TerminalSize size) {
        return footer.handleTasksPillMouse(mouse, origin, size);
    }

    /**
     * The footer pill must also refresh with no user input (a background task
     * finishing while the user idles has to clear the pill), so a 1 s tick
     * re-reads the registry — same cadence precedent as
     * {@code BackgroundTasksDialog}'s refresh timer. Scoped to the attached
     * lifetime so test-constructed panels never leak scheduled tasks. Runs on
     * {@code ESC_SCHEDULER}, which already mutates hint labels from its thread
     * (hint-restore timers); see {@link PromptFooter#startRefresh} for the
     * locking contract.
     */
    @Override
    public synchronized void onAdded(Container container) {
        super.onAdded(container);
        startTaskPillRefresh();
    }

    /** Starts the live task-footer refresh after the REPL scene is attached. */
    public void startTaskPillRefresh() {
        footer.startRefresh(ESC_SCHEDULER);
    }

    @Override
    public synchronized void onRemoved(Container container) {
        footer.stopRefresh();
        super.onRemoved(container);
    }

    // Test hooks — package-private, no GUI thread needed.
    TextBox.Result handleKeyForTest(KeyStroke key) { return textBox.handleKeyStroke(key); }
    void setCaretOffsetForTest(int offset) { TextBoxOffsetAdapter.setOffset(textBox, offset); }
    String queuedPreviewTextForTest() { return String.join("\n", queuedPreviewLines); }
    boolean isTasksPillSelected() { return footer.isTasksPillSelected(); }
    boolean isWorkflowFooterSelectedForTest() { return footer.isWorkflowSelected(); }
    int workflowFooterIndexForTest() { return footer.workflowIndex(); }
    String selectedWorkflowTaskIdForTest() { return footer.selectedWorkflowTaskId(); }
    boolean isCollaborationPillSelected() { return footer.isCollaborationPillSelected(); }
    int hintRowVisualIndexForTest() { return getChildrenList().indexOf(hintRow); }
    int collaborationRowVisualIndexForTest() {
        return getChildrenList().indexOf(footer.collaborationRow());
    }
    int coordinatorIndexForTest() { return footer.coordinatorIndex(); }
    String collaborationPillTextForTest() { return footer.collaborationPillText(); }
    String tasksPillTextForTest() { return footer.tasksPillText(); }
    boolean isTasksPillHoveredForTest() { return footer.isTasksPillHovered(); }
    String tasksHintTextForTest() { return footer.tasksHintText(); }
    String hintTextForTest() { return hintBar.mainText(); }
    String leaderHintTextForTest() { return hintBar.combinedText(); }
    void setLeftArrowOpensAgentsForTest(BooleanSupplier enabled) {
        state.setLeftArrowOpensAgents(enabled);
        updateHint();
    }
    boolean isHistorySearchingForTest() { return historyController.isSearching(); }
    String historySearchDraftForTest() { return historyController.searchDraftForTest(); }
    boolean isPastingForTest() { return paste.isPending(); }
    void completePasteForTest(String insertedText) {
        paste.complete(insertedText == null ? null : () -> chips.insertAtCursor(insertedText));
    }
    int textRowsForTest() { return currentTextRows; }
    PromptTextLayout.Position visualCaretPositionForTest() {
        return textLayout.positionAt(caretCol());
    }
    Mode modeForTest() { return state.mode(); }
    boolean plainCharacterFastPathForTest(KeyStroke key) {
        return textBox.canUsePlainCharacterFastPath(key);
    }
    public void setTaskRegistry(TaskRegistry registry) {
        footer.setTaskRegistry(registry);
    }

    public void setTeammateTreeExpanded(boolean expanded) {
        footer.setTeammateTreeExpanded(expanded);
        updateHint();
        invalidate();
    }

    public void setWorkflowRunStore(WorkflowRunStore workflowRuns) {
        footer.setWorkflowRunStore(workflowRuns);
    }

    /**
     * Binds the subagent coordinator panel — its navigation state machine plus
     * the view it renders into — and mounts the view between the hint row and
     * the Collaboration row.
     */
    public void setCoordinatorNavigation(CoordinatorNavigationController navigation,
                                         CoordinatorPanelView panel,
                                         Function<String, String> agentNameResolver) {
        Component previous = footer.coordinatorComponent();
        if (previous != null) removeComponent(previous);
        footer.bindCoordinator(navigation, panel, agentNameResolver);
        Component component = footer.coordinatorComponent();
        if (component != null) {
            Panel collaborationRow = footer.collaborationRow();
            removeComponent(collaborationRow);
            addComponent(component,
                LinearLayout.createLayoutData(LinearLayout.Alignment.FILL));
            addComponent(collaborationRow);
        }
    }

    /** Repaints the coordinator rows from live navigation + workflow state. */
    void refreshCoordinatorPanel() {
        footer.refreshCoordinatorPanel();
    }

    /** Recomputes the tasks pill + its trailing hint from the live registry. */
    void refreshTasksPill() {
        footer.refreshTasksPill();
    }

    /**
     * Binds the footer to the shared collaboration state. Session Link can
     * change that state from a virtual thread, so the listener projects the
     * new value through the configured GUI invoker before touching Lanterna.
     * Test-constructed panels have no invoker and refresh synchronously.
     */
    public void setCollaborationController(SessionCollaborationController controller) {
        footer.setCollaborationController(controller);
    }

    /** Releases the controller listener when the REPL is shutting down. */
    public void closeCollaborationBinding() {
        footer.closeCollaborationBinding();
    }

    /** Package-private for {@code InputPanelKeyRoutingTest}. */
    void beginPaste() {
        paste.begin();
    }

    /**
     * Recomputes the hint row, the tasks pill and the vim label from current
     * state. Anything that changes hint-relevant state calls this.
     */
    private void updateHint() {
        hintBar.render(new PromptHintBar.Context(
            paste.isPending(),
            state.isMessageActionsActive(),
            state.messageActionsHint(),
            permMode,
            state.isLoading(),
            state.leftArrowOpensAgents()), footer);
        footer.refreshTasksPill();
        vim.refreshLabel();
    }
}

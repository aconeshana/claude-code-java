package com.claudecode.ui.lanterna.input;

import com.claudecode.core.constants.Figures;
import com.claudecode.core.engine.SessionIdentity;
import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.core.queue.QueuedCommand;
import com.claudecode.keybindings.KeybindingHints;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.runtime.sessionhost.SessionCollaborationController;
import com.claudecode.runtime.turn.QueuedInputDraft;
import com.claudecode.tools.tasks.TaskRegistry;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.tools.workflows.WorkflowRunStore;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import com.claudecode.ui.lanterna.components.HighlightedTextBox.Highlight;
import com.claudecode.ui.lanterna.features.settings.UiSettings;
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
import com.googlecode.lanterna.input.FocusEventKeyStroke;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Input area for the Lanterna prompt component stack.
 */
public class InputPanel extends Panel {

    private static final Logger log = LoggerFactory.getLogger(InputPanel.class);
    /** High-volume key protocol tracing is opt-in even when general DEBUG is enabled. */
    private static final boolean KEY_DIAGNOSTICS =
        Boolean.getBoolean("claude.ui.keyDiagnostics");

    private static final int PROMPT_INPUT_COLUMN_OVERHEAD = 3;

    public enum Mode { NORMAL, BASH }

    private Mode   mode         = Mode.NORMAL;

    private Mode   modeOverride = null;
    private String permMode;

    private BooleanSupplier bypassPermissionsModeAvailable = () -> true;

    private BooleanSupplier leftArrowOpensAgents =
        () -> UiSettings.readGlobalBoolean("leftArrowOpensAgents", true);

    // Vim mode state
    /** Vim keybinding bridge; owns the state machine, the INSERT label and the re-entrancy fence. */
    private final PromptVimAdapter vim = new PromptVimAdapter(new VimHost());

    // Child components
    private final Label  promptLabel;
    private final TextBox textBox;
    /**
     * Last value delivered by Lanterna's text-change listener. Keeping this
     * immutable String snapshot avoids rebuilding the whole multi-line value
     * several times for every key (undo, mode detection, chip pruning,
     * typeahead, highlights, and ghost text all consume the same value).
     */
    private String currentText = "";
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
    /**
     * Pure Emacs/readline editing layer (cursor/word motion + kill/yank ring).
     * Constructed inside {@link PromptTextBox}'s instance initializer, so it can
     * capture that class's {@code super.handleKeyStroke} for faithful
     * cross-row caret motion. Only invoked from key dispatch, so the assignment
     * ordering (before {@code textBoxRef.set}) is irrelevant.
     */
    private ReadlineEngine engine;
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

    private BooleanSupplier hasMessages;

    /**
     * Opt-in keybinding store (gate on). When non-null and enabled, matched
     * Chat/Global keys are routed through {@link #dispatchViaResolver} instead of
     * the hardcoded readline switch below. Null in headless / when customization
     * is disabled, so the existing dispatch is unchanged.
     */
    private UserKeybindingsStore keybindingsStore;
    /** Shared resolver bridge owns pending-chord state and timeout semantics. */
    private final ContextKeybindingDispatcher keybindingDispatcher =
        new ContextKeybindingDispatcher();

    // ── Push-to-talk hold detection (terminal auto-repeat accumulator) ──
    // A terminal has no distinct key-release events, so a sustained hold
    // surfaces as a stream of repeated single-character KeyStroke events.
    // Faithful to the 197 bundle (voice handler: qYo=120ms reset window,
    // zRf=5 hold threshold), we accumulate consecutive space events within
    // the reset window and only classify the burst as a HOLD once it crosses
    // the threshold; an ordinary single tap (accumulation never reaching the
    // floor) is a plain space.
    private static final long PTT_RESET_WINDOW_MS = 120;
    private static final int PTT_HOLD_THRESHOLD = 5;
    private int pttAccumulated;
    private long pttLastEventMs;

    private static final long KILL_AGENTS_CONFIRM_WINDOW_MS = 3000;
    private long lastKillAgentsPressMs;


    // Images are inserted as [Image #N] chips in the textBox; orphaned chips
    // (deleted by the user) are pruned on every text change.
    private final PromptPastedContentController pastedContent =
        new PromptPastedContentController();
    {
        chips = new PromptChipEditor(new ChipHost(), pastedContent);
    }
    /** Draft-only undo history; never rewinds QuerySession conversation state. */
    private final DraftUndoBuffer draftUndo = new DraftUndoBuffer(50);

    /** Editor access and prompt actions for the vim bridge. */
    private final class VimHost implements PromptVimAdapter.Host {
        @Override public String text() { return textBox.getText(); }
        @Override public int caret() { return caretCol(); }
        @Override public void setTextRaw(String text) { textBox.setText(text); }
        @Override public void moveCaretTo(int offset) { InputPanel.this.moveCaretTo(offset); }
        @Override public TextBox.Result forwardToEditor(KeyStroke key) { return textBox.handleKeyStroke(key); }
        @Override public TextBox.Result historyUp() { return ((PromptTextBox) textBox).rl_historyUp(); }
        @Override public TextBox.Result historyDown() { return ((PromptTextBox) textBox).rl_historyDown(); }
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
                actions.submit(prependModePrefix(text));
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
            ((PromptTextBox) textBox).tryHandleSubmitKeyStroke(new KeyStroke(KeyType.ENTER));
        }
    }

    /** Text-box access for the chip editor plus the panel's post-edit notifications. */
    private final class ChipHost implements PromptChipEditor.Host {
        @Override public String text() { return textBox.getText(); }
        @Override public int caret() { return caretCol(); }
        @Override public void setTextRaw(String text) { textBox.setText(text); }
        @Override public void moveCaretTo(int offset) { InputPanel.this.moveCaretTo(offset); }
        @Override public void textEdited() { updateMode(); fireQueryChange(); }
        @Override public void recordUndoSnapshot() { draftUndo.record(captureDraftSnapshot()); }
        @Override public void pastedContentsChanged() { firePastedContentsChange(); }
    }
    /** Monotonic suppression token observed by nested PromptTextBox key dispatches. */
    private long draftUndoSuppressionGeneration;

    private StashedPrompt stashedPrompt;
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
    /** Nesting guard owned by the GUI host's terminal-drain cycle. */
    private int guiInputBatchDepth;

    private boolean messageActionsActive = false;
    private String messageActionsHint = "";

    // ── History navigation state ───────────────────────────────────────────── ── History
    // navigation (Up/Down/Ctrl+R) — extracted to InputHistoryController ──.
    private final InputHistoryController historyController =
        new InputHistoryController(new InputEditingSurface() {
            @Override public String currentText() { return textBox.getText(); }
            @Override public Mode currentModeOverride() { return modeOverride; }
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
                modeOverride = mode;
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
                modeOverride = mode;
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
        return new DraftUndoBuffer.Snapshot(
            currentText, caretCol(), pastedContent.snapshot(), modeOverride);
    }

    private void suppressDraftUndoRecording() {
        draftUndoSuppressionGeneration++;
    }



    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800;
    private static final ScheduledExecutorService ESC_SCHEDULER =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "esc-timeout");
            t.setDaemon(true);
            return t;
        });
    private boolean           escOnce         = false;
    private ScheduledFuture<?> escTimer        = null;
    // True when input was EMPTY on the first Esc (double-Esc → MessageSelector vs. clear).
    private boolean           escEmptyFirst   = false;
    // Whether a query is in flight — set by LanternaReplScreen.
    private volatile boolean  isLoading       = false;
    /** Clipboard/bracketed paste classification, pending-paste gate and deferred submit. */
    private final PromptPasteHandler paste =
        new PromptPasteHandler(new PasteHost(), pastedContent);
    // Whether transcript mode is active — set by LanternaReplScreen.
    // When true, Ctrl+E fires actions.transcriptShowAll() instead of engine.end().
    private volatile boolean  isTranscriptMode = false;

    // ── Temporary hint notification state ───────────────────────────────────.
    static final long HINT_TIMEOUT_MS = 1000;

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
        textBox = new PromptTextBox(new TerminalSize(80, 1),
                                    TextBox.Style.MULTI_LINE,
                                    highlightSupplier);
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
            currentText = newText == null ? "" : newText;
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
        ((HighlightedTextBox) textBox).setGhostTextSupplier(this::inlineGhostText);
        ((HighlightedTextBox) textBox).setVisualLayoutSupplier(
            () -> textLayout, this::caretCol);

        hintBar = new PromptHintBar(ESC_SCHEDULER, this::updateHint);
        footer = new PromptFooter(new FooterHost());
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

    /**
     * The multi-line prompt text box — extracted from an inline anonymous
     * {@link HighlightedTextBox} subclass to a named (non-static) inner class
     * so its ordered {@link #handleKeyStroke} protocol (readline shortcuts,
     * vim dispatch, chip-aware editing, history, suggestions, paste handling)
     * has a readable class boundary instead of living inside an anonymous
     * initializer.
     *
     * <p>Non-static on purpose: {@code handleKeyStroke} and its {@code rl_*}
     * helpers read and mutate {@link InputPanel}'s private state directly
     * ({@code actions}, {@code historyController}, {@code pastedContent},
     * {@code modeOverride}, {@code escOnce}, {@code isLoading}, …) — the same
     * reasoning as {@code LanternaReplScreen.ReplInputActions}. Promoting this
     * to a standalone top-level class would require threading every one of
     * those fields through explicit ports for no behavioral gain.
     */
    private final class PromptTextBox extends HighlightedTextBox {

        PromptTextBox(TerminalSize preferredSize, TextBox.Style style,
                      Supplier<List<Highlight>> highlightSupplier) {
            super(preferredSize, style, highlightSupplier);
        }

        {
            engine = new ReadlineEngine(
                this,
                super::handleKeyStroke,
                InputPanel.this::fireQueryChange);
        }

        @Override
        public Result handleKeyStroke(KeyStroke key) {
            if (key.getKeyType() != KeyType.ENTER) paste.cancelDeferredSubmit();
            if (pendingBatchEnter && key.getKeyType() != KeyType.ENTER) {
                // Input followed the swallowed ENTER inside the same batch, so
                // the ENTER was a paste newline — materialize the line split
                // right before this keystroke applies. Batched text runs
                // materialize it in bufferPlainText instead (they bypass this
                // method).
                pendingBatchEnter = false;
                super.handleKeyStroke(new KeyStroke(KeyType.ENTER));
            }
            if (guiInputBatchDepth > 0
                    && key.getKeyType() == KeyType.CHARACTER
                    && key.getCharacter() != null
                    && !Character.isISOControl(key.getCharacter())
                    && !key.isCtrlDown() && !key.isAltDown()) {
                plainInputCharsThisBatch++;
            }
            if (guiInputBatchDepth > 0) {
                if (canBufferPlainCharacter(key)) {
                    flushBufferedBackspaces(false);
                    bufferPlainCharacter(key.getCharacter());
                    return Result.HANDLED;
                }
                if (canBufferPlainBackspace(key)) {
                    flushBufferedPlainInput(false);
                    bufferPlainBackspace();
                    return Result.HANDLED;
                }
                // A navigation/submit key may follow buffered edits in the
                // same terminal poll. Commit them before taking the snapshot
                // for that distinct action.
                flushBufferedPlainInput(true);
                flushBufferedBackspaces(true);
            }
            String textBefore = currentText;
            int cursorBefore = caretCol();
            Mode modeBefore = modeOverride;
            Map<Integer, PastedContent> pastedBefore = pastedContent.isEmpty()
                ? Map.of() : pastedContent.snapshot();
            long suppressionAtEntry = draftUndoSuppressionGeneration;
            Result result = handleKeyStrokeRouted(key);
            if (suppressionAtEntry == draftUndoSuppressionGeneration) {
                boolean textChanged = !textBefore.equals(currentText);
                boolean modeChanged = modeBefore != modeOverride;
                boolean pastedChanged =
                    (!pastedBefore.isEmpty() || !pastedContent.isEmpty()) && !pastedBefore.equals(
                        pastedContent.snapshot());

                // navigation, and coalesces rapid typing for one second.
                if (textChanged || modeChanged || pastedChanged) {
                    draftUndo.recordDebounced(new DraftUndoBuffer.Snapshot(
                        textBefore, cursorBefore, pastedBefore, modeBefore));
                }
            }
            return result;
        }

        private Result handleKeyStrokeRouted(KeyStroke key) {
            // [DIAG] per-keystroke key-protocol probe (debug-level). Enable by setting
            // this class to DEBUG in logback to diagnose key encoding / Shift+Enter
            // issues or unrecognised key bindings (e.g. Ctrl+V arriving as plain 'v'
            // instead of ctrlDown=true) — supersedes the old dedicated
            // {@code -Danthropic.debug}/{@code ANTHROPIC_DEBUG}-gated CHARACTER-only log.
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                KeyType kt = key.getKeyType();
                String charInfo = (kt == KeyType.CHARACTER && key.getCharacter() != null)
                    ? String.format("'%c'/0x%02x", key.getCharacter(), (int) key.getCharacter())
                    : "n/a";
                log.debug("[key-diag] type={} char={} shift={} ctrl={} alt={} class={}",
                    kt, charInfo,
                    key.isShiftDown(), key.isCtrlDown(), key.isAltDown(),
                    key.getClass().getSimpleName());
            }
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()
                    && key.getKeyType() == KeyType.ARROW_UP
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                log.debug("[key-up] handleKeyStroke ARROW_UP, row={}, lineCount={}, isSearching={}, suggVisible={}, vim={}",
                    getCaretPosition().getRow(), getLineCount(), historyController.isSearching(), suggestions.isVisible(), vim.isEnabled());
            }
            // Re-entrancy fence: if vim is forwarding a key via
            // textBox.handleKeyStroke(key) we must NOT re-route through
            // handleVimKey — that would recurse on the same key. Send
            // it straight to the Lanterna TextBox parent.
            if (vim.isForwarding()) {
                return super.handleKeyStroke(key);
            }
            // DEC 1004 focus events — route to MessagePanel.setFocused via callback.

            if (key.getKeyType() == KeyType.FOCUS_EVENT && key instanceof FocusEventKeyStroke fek) {
                if (actions != null) actions.focusChanged(fek.isFocused());
                return Result.HANDLED;
            }
            // Mouse events — let them bubble to the window listener which
            // routes scrolls to messagePanel. We must NOT consume here:
            // InputPanel is the focused component so events arrive here
            // first, and returning HANDLED would short-circuit the
            // window-level handler in LanternaReplScreen.
            if (key instanceof MouseAction) {
                return Result.UNHANDLED;
            }
            if (canUsePlainCharacterFastPath(key)) {
                // The established PromptInput sends ordinary text directly to its
                // input buffer. Avoid walking every modal/readline/navigation
                // stage when none of those states can own this character.
                KillRing.INSTANCE.resetAccumulation();
                KillRing.INSTANCE.resetYankState();
                return handleDefaultTextBoxKeyStroke(key);
            }
            // Ordered pre-editor protocol. A non-null result terminates routing;
            // null means only that the next stage must see the same keystroke.
            // Keep these stages in this order: their precedence is observable UI
            // behavior, not an implementation detail.
            Result preEditorResult = tryHandleMessageActionsKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleTeammateKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleFooterKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleHistorySearchKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandlePasteOrModeCycleKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleAutocompleteKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleConfiguredKeybindingKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            preEditorResult = tryHandleEmptyPromptAgentsKeyStroke(key);
            if (preEditorResult != null) return preEditorResult;
            Result editorResult = tryExitBashModeAtInputStart(key);
            if (editorResult != null) return editorResult;
            if (vim.isEnabled()) {
                // Ctrl/Alt modifier combos bypass vim and use the
                // readline path. claude-code's global bindings (Ctrl+V
                // imagePaste, Ctrl+A home, Ctrl+P history, Ctrl+R search,
                // Alt+B/F word-wise nav) take priority over vim semantics
                // even inside INSERT mode — keystrokeToChar would have
                // dropped the ctrl flag and inserted a literal 'v' for
                // Ctrl+V. Plain (unmodified) keys still go through vim
                // so hjkl, escape→NORMAL, etc. work as expected.
                if (!key.isCtrlDown() && !key.isAltDown()) {
                    return vim.handleKey(key);
                }
            }
            editorResult = tryHandleReadlineKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandleChipEditingKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandlePromptNavigationKeyStroke(key);
            if (editorResult != null) return editorResult;

            editorResult = tryHandleSubmitKeyStroke(key);
            if (editorResult != null) return editorResult;
            editorResult = tryHandleEscapeKeyStroke(key);
            if (editorResult != null) return editorResult;
            resetDoubleEscapeGateAfterNonEscapeKey();
            insertLazySpaceAfterChip(key);
            editorResult = tryEnterBashMode(key);
            if (editorResult != null) return editorResult;
            return handleDefaultTextBoxKeyStroke(key);
        }

        private boolean canUsePlainCharacterFastPath(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || Character.isISOControl(key.getCharacter())
                    || key.isCtrlDown()
                    || key.isAltDown()) {
                return false;
            }
            return canUsePlainCharacterFastPath(key.getCharacter());
        }

        private boolean canUsePlainCharacterFastPath(char character) {
            if (Character.isISOControl(character) || !plainInputStateAllowsDirectEdit()) {
                return false;
            }
            return character != '!'
                || !textBox.getText().isEmpty()
                || modeOverride != null;
        }

        private boolean plainInputStateAllowsDirectEdit() {
            return !suggestions.isVisible() && plainInputStateAllowsBatch();
        }

        private boolean plainInputStateAllowsBatch() {
            return !messageActionsActive
                && !footer.capturesPlainInput()
                && !historyController.isSearching()
                && !customKeybindingsEnabled()
                && !vim.isEnabled()
                && !escOnce
                && pastedContent.isEmpty();
        }

        private final StringBuilder bufferedPlainInput = new StringBuilder();
        private DraftUndoBuffer.Snapshot bufferedInputStart;
        private int bufferedBackspaces;
        private int bufferedBackspaceCaret;
        private DraftUndoBuffer.Snapshot bufferedBackspaceStart;
        /**
         * Printable characters seen during the current GUI input batch (one PTY
         * drain). A batch that carries plain characters before a plain ENTER
         * may be an unbracketed-paste flood (tmux {@code paste-buffer}, CRLF
         * clipboards) — the twin of Ink receiving the whole flood as one stdin
         * chunk — so such an ENTER's meaning is deferred: see
         * {@link #pendingBatchEnter}.
         */
        private int plainInputCharsThisBatch;

        /**
         * A plain ENTER swallowed earlier in this batch, whose meaning is still
         * unresolved. If further input follows in the same batch the ENTER was
         * a paste newline (materialized as a line split just in time); if the
         * batch ends with the ENTER still pending, it was a terminal one-line
         * submit and the batch end performs it. This matches official 197,
         * which discriminates a paste by what the stdin chunk contains, not by
         * which drain delivered it.
         */
        private boolean pendingBatchEnter;

        /**
         * Prompt text and caret at the start of this batch. Everything the batch
         * inserts lands contiguously at {@code batchStartCaret}, so the batch's
         * own text — the only thing a paste-flood fold may consume — is the
         * delta between then and the batch end.
         */
        private int batchStartCaret;
        private int batchStartLength;

        private boolean canBufferPlainCharacter(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || Character.isISOControl(key.getCharacter())
                    || key.isCtrlDown() || key.isAltDown()) {
                return false;
            }
            if (key.getCharacter() == '!' && !bufferedPlainInput.isEmpty()) {
                return canUsePlainCharacterFastPath('a');
            }
            return canUsePlainCharacterFastPath(key.getCharacter());
        }

        private void bufferPlainCharacter(char character) {
            if (bufferedPlainInput.isEmpty()) bufferedInputStart = captureDraftSnapshot();
            bufferedPlainInput.append(character);
        }

        private boolean bufferPlainText(String text) {
            if (text == null || text.length() < 2 || !plainInputStateAllowsBatch()) return false;
            boolean hasBufferedPrefix = !bufferedPlainInput.isEmpty()
                || !textBox.getText().isEmpty() || modeOverride != null;
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                if (Character.isISOControl(character)
                        || character == '!' && !hasBufferedPrefix) return false;
                hasBufferedPrefix = true;
            }
            // This run bypasses handleKeyStroke, so it must materialize a pending
            // swallowed ENTER itself — text following an ENTER inside the same batch
            // proves a paste flood, and the ENTER becomes a newline ahead of this text.
            if (pendingBatchEnter) {
                pendingBatchEnter = false;
                bufferedPlainInput.append('\n');
            }
            if (bufferedPlainInput.isEmpty()) bufferedInputStart = captureDraftSnapshot();
            bufferedPlainInput.append(text);
            plainInputCharsThisBatch += text.length();
            return true;
        }

        private void flushBufferedPlainInput(boolean publishImmediately) {
            if (bufferedPlainInput.isEmpty()) return;
            String insertion = bufferedPlainInput.toString();
            bufferedPlainInput.setLength(0);
            int caret = caretCol();
            String before = currentText;
            String merged = before.substring(0, caret) + insertion + before.substring(caret);
            ((HighlightedTextBox) textBox).setTextPreservingViewport(merged);
            TextBoxOffsetAdapter.setOffset(textBox, caret + insertion.length());
            if (historyController.hasHistoryCursor()) historyController.onUserEdit();
            KillRing.INSTANCE.resetAccumulation();
            KillRing.INSTANCE.resetYankState();
            if (bufferedInputStart != null) {
                draftUndo.recordDebounced(bufferedInputStart);
                bufferedInputStart = null;
            }
            if (publishImmediately) deliverQueryChangeImmediately();
            else fireQueryChange();
        }

        /**
         * The text this batch inserted, or {@code ""} when it cannot be
         * recovered. Everything a batch inserts lands contiguously at
         * {@link #batchStartCaret}, so the run is the length delta ending at
         * the caret. A caret that no longer sits at the end of that run means
         * the batch also moved it (arrow keys, mouse) — folding a guess would
         * be wrong, so report nothing to fold.
         */
        private String batchText() {
            int inserted = currentText.length() - batchStartLength;
            if (inserted <= 0) return "";
            int caret = caretCol();
            if (caret != batchStartCaret + inserted) return "";
            return currentText.substring(batchStartCaret, caret);
        }

        /**
         * Folds one GUI input batch's text into a {@code [Pasted text #N +X
         * lines]} chip — the same end state the bracketed path produces.
         *
         * <p>Only this batch's own text is judged, because official folds per
         * stdin chunk: a long prompt typed one drain at a time never clears the
         * threshold, whereas a genuine flood arrives in a single batch. A flood
         * too large for one drain folds once per drain, which is what official
         * 2.1.236 does too. Only the batch's own text is replaced, so anything
         * typed before the flood survives.
         *
         * @return true when the batch was folded into a chip
         */
        private boolean foldUnbracketedPasteFloodIntoChip(String batchText) {
            return foldUnbracketedPasteFloodIntoChip(batchText, "");
        }

        /**
         * @param trailing batch text that was never inserted — the newline of an
         *     ENTER the batch swallowed. Official folds a whole stdin chunk,
         *     terminator included, so it counts toward the chip's content and
         *     line count.
         */
        private boolean foldUnbracketedPasteFloodIntoChip(String batchText, String trailing) {
            if (batchText.isEmpty()) return false;
            String stripped = PromptPasteTextPolicy.normalize(batchText + trailing);
            int numLines = PastedRefParser.getPastedTextRefNumLines(stripped);
            if (!PromptPasteTextPolicy.shouldFoldIntoChip(stripped, numLines, terminalRows())) {
                return false;
            }
            int pasteId = pastedContent.nextId();
            pastedContent.put(PastedContent.text(pasteId, stripped));
            int caret = caretCol();
            int start = caret - batchText.length();
            InputPanel.this.setText(
                currentText.substring(0, start) + currentText.substring(caret));
            TextBoxOffsetAdapter.setOffset(textBox, start);
            chips.insertAtCursor(PastedRefParser.formatPastedTextRef(pasteId, numLines));
            firePastedContentsChange();
            return true;
        }

        private boolean canBufferPlainBackspace(KeyStroke key) {
            if (key.getKeyType() != KeyType.BACKSPACE
                    || key.isShiftDown() || key.isCtrlDown() || key.isAltDown()) {
                return false;
            }
            return canBufferPlainBackspaces(1);
        }

        private boolean canBufferPlainBackspaces(int count) {
            if (count < 1
                    || messageActionsActive
                    || footer.capturesPlainInput()
                    || historyController.isSearching()
                    || customKeybindingsEnabled()
                    || vim.isEnabled()
                    || escOnce
                    || !pastedContent.isEmpty()
                    || modeOverride != null
                    || currentText.indexOf('\n') >= 0) {
                return false;
            }
            int caret = bufferedBackspaces == 0 ? caretCol() : bufferedBackspaceCaret;
            int end = caret - bufferedBackspaces;
            int start = end - count;
            if (start < 0) return false;
            for (int index = start; index < end; index++) {
                if (Character.isSurrogate(currentText.charAt(index))) return false;
            }
            return true;
        }

        private void bufferPlainBackspace() {
            bufferPlainBackspaces(1);
        }

        private void bufferPlainBackspaces(int count) {
            if (bufferedBackspaces == 0) {
                bufferedBackspaceCaret = caretCol();
                bufferedBackspaceStart = captureDraftSnapshot();
            }
            bufferedBackspaces += count;
        }

        private void flushBufferedBackspaces(boolean publishImmediately) {
            if (bufferedBackspaces == 0) return;
            int count = bufferedBackspaces;
            bufferedBackspaces = 0;
            int caret = bufferedBackspaceCaret;
            bufferedBackspaceCaret = 0;
            int start = Math.max(0, caret - count);
            String before = currentText;
            textBox.setText(before.substring(0, start) + before.substring(caret));
            TextBoxOffsetAdapter.setOffset(textBox, start);
            if (historyController.hasHistoryCursor()) historyController.onUserEdit();
            KillRing.INSTANCE.resetAccumulation();
            KillRing.INSTANCE.resetYankState();
            if (bufferedBackspaceStart != null) {
                draftUndo.recordDebounced(bufferedBackspaceStart);
                bufferedBackspaceStart = null;
            }
            if (publishImmediately) deliverQueryChangeImmediately();
            else fireQueryChange();
        }

        /**
         * Message-actions overlay is the first modal key surface after terminal
         * focus/mouse events. It consumes every key while active.
         */
        private Result tryHandleMessageActionsKeyStroke(KeyStroke key) {
            if (!messageActionsActive) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("MessageActions", "Global"),
                    this::dispatchMessageActionsAction);
                if (resolved != null) return resolved;
            }
            if (key.getKeyType() == KeyType.ESCAPE) {
                if (actions != null) actions.messageActionsEscape();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.CHARACTER
                    && key.getCharacter() != null && key.getCharacter() == 'c'
                    && key.isCtrlDown()) {
                if (actions != null) actions.messageActionsForceExit();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_UP
                    || (key.getKeyType() == KeyType.CHARACTER
                        && key.getCharacter() == 'k')) {
                if (actions != null) actions.messageActionsPrev();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN
                    || (key.getKeyType() == KeyType.CHARACTER
                        && key.getCharacter() == 'j')) {
                if (actions != null) actions.messageActionsNext();
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.CHARACTER) {
                char ch = key.getCharacter();
                if (ch == 'c') {
                    if (actions != null) actions.messageActionsCopy();
                    return Result.HANDLED;
                }
                if (ch == 'p') {
                    if (actions != null) actions.messageActionsCopyPrimaryInput();
                    return Result.HANDLED;
                }
                if (ch == '\n' || ch == '\r') {
                    if (actions != null) actions.messageActionsEdit();
                    return Result.HANDLED;
                }
            }
            if (key.getKeyType() == KeyType.ENTER) {
                if (actions != null) actions.messageActionsEdit();
                return Result.HANDLED;
            }
            // Other keys are consumed but ignored in message actions mode.
            return Result.HANDLED;
        }

        /**
         * Teammate navigation owns only its navigation keys; all other keys fall
         * through to the footer and editor stages below.
         */
        private Result tryHandleTeammateKeyStroke(KeyStroke key) {
            return footer.handleTeammateKey(key);
        }

        /**
         * While a footer stop owns focus, Footer/Global bindings precede the
         * footer's native navigation. A null result deliberately falls through
         * to global Ctrl/Alt handling.
         */
        private Result tryHandleFooterKeyStroke(KeyStroke key) {
            if (!footer.isAnySelected()) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("Footer", "Chat", "Global"),
                    this::dispatchFooterOrChatAction);
                if (resolved != null) return resolved;
            }
            return footer.handleSelectedKey(key);
        }

        /** History search is an overlay, so its resolver and native handler run before paste or text editing. */
        private Result tryHandleHistorySearchKeyStroke(KeyStroke key) {
            if (!historyController.isSearching()) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("HistorySearch", "Global"),
                    this::dispatchHistorySearchOrGlobalAction);
                if (resolved != null) return resolved;
            }
            Result result = historyController.handleSearchKey(key);
            if (result != null) return result;
            // If not consumed, fall through to normal handling.
            historyController.exitSearch();
            return null;
        }

        /**
         * Bracketed paste precedes mode cycling because a paste event must never
         * be interpreted as ordinary printable input.
         */
        private Result tryHandlePasteOrModeCycleKeyStroke(KeyStroke key) {
            // Bracketed paste — terminal wrapped the paste with \e[200~ ... \e[201~
            // and Lanterna's BracketedPastePattern decoded it into a
            // PasteKeyStroke carrying the verbatim text. Preferred over Ctrl+V
            // clipboard probing because the terminal knows what the user actually
            // pasted without us shelling out to pbpaste/xclip.
            if (key.getKeyType() == KeyType.PASTE && key instanceof PasteKeyStroke pks) {
                paste.handleBracketedPaste(pks.getPastedText());
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.REVERSE_TAB
                    || (key.getKeyType() == KeyType.TAB && key.isShiftDown())
                    || (key.getKeyType() == KeyType.TAB && key.isAltDown())) {

                // Shift+Tab cycles its permission mode rather than the leader's.
                if (footer.taskNavigation().isViewing()) {
                    footer.cycleViewedTeammatePermissionMode();
                } else {
                    cyclePermissionMode();
                }
                return Result.HANDLED;
            }
            return null;
        }

        /** Autocomplete consumes its navigation and Escape before normal bindings or request cancellation. */
        private Result tryHandleAutocompleteKeyStroke(KeyStroke key) {
            if (!suggestions.isVisible()) return null;
            if (customKeybindingsEnabled()) {
                Result resolved = dispatchViaResolver(key,
                    List.of("Chat", "Autocomplete", "Global"),
                    this::dispatchAutocompleteOrChatAction);
                if (resolved != null) return resolved;
            }
            return suggestions.handleKey(key);
        }

        /**
         * Resolver-driven Chat/Global dispatch is opt-in and deliberately leaves
         * Enter/Escape to their stateful native handlers below.
         */
        private Result tryHandleConfiguredKeybindingKeyStroke(KeyStroke key) {
            // Matched Chat/Global keys use user overrides; unmatched keys retain
            // the readline fallback. Bash typing bypasses the resolver entirely.
            if (modeOverride == Mode.BASH) return null;
            if (!customKeybindingsEnabled()
                    && !keybindingDispatcher.hasPendingChord()
                    && !startsDefaultControlChord(key)) {
                return null;
            }
            return dispatchViaResolver(key);
        }

        private Result tryHandleEmptyPromptAgentsKeyStroke(KeyStroke key) {
            if (key.getKeyType() != KeyType.ARROW_LEFT
                    || key.isCtrlDown() || key.isAltDown() || key.isShiftDown()
                    || isLoading || modeOverride != null || footer.taskNavigation().isViewing()
                    || !currentText.isEmpty() || !pastedContent.isEmpty()
                    || !leftArrowOpensAgents.getAsBoolean() || actions == null) {
                return null;
            }
            actions.openAgents();
            return Result.HANDLED;
        }

        private boolean startsDefaultControlChord(KeyStroke key) {
            return key.getKeyType() == KeyType.CHARACTER
                && key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'x';
        }

        private boolean customKeybindingsEnabled() {
            return keybindingsStore != null && keybindingsStore.isEnabled();
        }

        /**
         * At the start of an empty bash-mode input, destructive/editing keys
         * return to prompt mode. Escape intentionally returns null after that
         * transition so the normal double-Escape protocol still runs.
         */
        private Result tryExitBashModeAtInputStart(KeyStroke key) {
            if (modeOverride != Mode.BASH || caretCol() != 0) return null;
            KeyType keyType = key.getKeyType();
            boolean ctrlU = key.isCtrlDown() && !key.isAltDown()
                && key.getCharacter() != null
                && Character.toLowerCase(key.getCharacter()) == 'u';
            if (keyType == KeyType.BACKSPACE || keyType == KeyType.DELETE || ctrlU) {
                resetMode();
                fireQueryChange();
                return Result.HANDLED;
            }
            if (keyType == KeyType.ESCAPE) {
                resetMode();
                // Fall through to the normal Escape handling below.
            }
            return null;
        }

        /**
         * Readline/Emacs shortcuts match useTextInput's Ctrl/Meta mapping.
         * The kill/yank reset side effects deliberately run for every key that
         * reaches this phase, including a key with no matching shortcut.
         */
        private Result tryHandleReadlineKeyStroke(KeyStroke key) {
            if (!ReadlineEngine.isKillKey(key)) KillRing.INSTANCE.resetAccumulation();
            if (!ReadlineEngine.isYankKey(key)) KillRing.INSTANCE.resetYankState();
            if (key.getKeyType() == KeyType.CHARACTER) {
                char ch = key.getCharacter();

                // Ctrl+letter dispatch — Lanterna decodes the terminal raw
                // control byte into a CHARACTER KeyStroke with ctrlDown=true.
                if (key.isCtrlDown() && !key.isAltDown()) {
                    Result ctrlResult = switch (Character.toLowerCase(ch)) {
                        case 'a' -> engine.home();            // Ctrl+A
                        case 'b' -> actions.backgroundForegroundTasks()
                            ? Result.HANDLED : engine.left(); // Ctrl+B: task priority, then readline
                        case 'd' -> rl_ctrlD();               // Ctrl+D
                        case 'e' -> rl_ctrlE();               // Ctrl+E
                        case 'f' -> engine.right();           // Ctrl+F
                        case 'k' -> engine.killToEnd();       // Ctrl+K
                        case 'n' -> rl_historyDown();         // Ctrl+N
                        case 'p' -> rl_historyUp();           // Ctrl+P
                        case 'u' -> engine.killToStart();     // Ctrl+U
                        case 'w' -> engine.killWordBefore();  // Ctrl+W
                        case 'y' -> engine.yank();            // Ctrl+Y
                        case 'v' -> rl_imagePaste();          // Ctrl+V
                        case 'l' -> rl_redrawScreen();        // Ctrl+L
                        case 'o' -> rl_toggleTranscript();    // Ctrl+O
                        case 'g' -> rl_externalEditor();      // Ctrl+G
                        case 's' -> rl_stash();               // Ctrl+S
                        case '_' -> { rl_undo(); yield Result.HANDLED; } // Ctrl+_
                        case 'r' -> rl_historySearch();       // Ctrl+R
                        case 't' -> {
                            if (actions != null) actions.toggleTodos();
                            yield Result.HANDLED;
                        }
                        default -> null;
                    };
                    if (ctrlResult != null) return ctrlResult;
                }

                // Alt+X: Lanterna sets isAltDown for ESC-prefixed sequences.
                if (key.isAltDown()) {
                    Result altResult = switch (ch) {
                        case 'b', 'B' -> engine.prevWord();
                        case 'f', 'F' -> engine.nextWord();
                        case 'd', 'D' -> engine.killWordAfter();
                        case 'y', 'Y' -> engine.yankPop();
                        case 't', 'T' -> {
                            if (actions != null) actions.toggleThinking();
                            yield Result.HANDLED;
                        }
                        case 'p', 'P' -> {
                            if (actions != null) actions.openModelPicker();
                            yield Result.HANDLED;
                        }
                        case 'o', 'O' -> {
                            if (actions != null) actions.toggleFastMode();
                            yield Result.HANDLED;
                        }
                        default -> null;
                    };
                    if (altResult != null) return altResult;
                }
            }

            // Some terminals send modifier-arrow sequences instead of Alt+B/F.
            if (key.getKeyType() == KeyType.ARROW_LEFT && (key.isCtrlDown() || key.isAltDown())) {
                return engine.prevWord();
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT && (key.isCtrlDown() || key.isAltDown())) {
                return engine.nextWord();
            }
            if (key.getKeyType() == KeyType.BACKSPACE && key.isAltDown()) {
                return engine.killWordBefore();
            }
            if (key.getKeyType() == KeyType.DELETE && key.isAltDown()) {
                return engine.killWordAfter();
            }
            return null;
        }

        /**
         * Image and pasted-text chips are atomic cursor tokens. This stage runs
         * after modifier readline shortcuts but before ordinary arrow/history
         * routing, matching the compatibility Cursor token semantics.
         */
        private Result tryHandleChipEditingKeyStroke(KeyStroke key) {
            if (key.getKeyType() == KeyType.ARROW_LEFT
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chips.hopLeft()) {
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_RIGHT
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chips.hopRight()) {
                return Result.HANDLED;
            }

            if (key.getKeyType() == KeyType.BACKSPACE
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chips.backspace()) {
                chips.pruneOrphanedImages();
                return Result.HANDLED;
            }

            if (key.getKeyType() == KeyType.DELETE
                    && !key.isCtrlDown() && !key.isAltDown()
                    && chips.delete()) {
                chips.pruneOrphanedImages();
                return Result.HANDLED;
            }
            return null;
        }

        /**
         * Shift arrows activate overlay/task navigation; plain vertical arrows
         * first move within multiline text and only then fall back to history.
         */
        private Result tryHandlePromptNavigationKeyStroke(KeyStroke key) {
            if (key.getKeyType() == KeyType.ARROW_UP && key.isShiftDown()
                    && !key.isCtrlDown() && !key.isAltDown()) {
                if (footer.taskNavigation().hasRunningTeammates()) {
                    footer.handleTeammateShiftSelection(-1);
                } else if (actions != null) {
                    actions.toggleMessageActions();
                }
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN && key.isShiftDown()
                    && !key.isCtrlDown() && !key.isAltDown()) {
                footer.handleTeammateShiftSelection(1);
                return Result.HANDLED;
            }
            if (key.getKeyType() == KeyType.ARROW_UP
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return rl_historyUp();
            }
            if (key.getKeyType() == KeyType.ARROW_DOWN
                    && !key.isShiftDown() && !key.isCtrlDown() && !key.isAltDown()) {
                return rl_historyDown();
            }
            return null;
        }

        /** Handles Shift/Alt Enter newline insertion and the ordinary submit flow. */
        private Result tryHandleSubmitKeyStroke(KeyStroke key) {
            // Forward a real unmodified ENTER so Lanterna's MULTI_LINE TextBox
            // performs its split-line-at-caret path instead of inserting a glyph.
            if (key.getKeyType() == KeyType.ENTER
                    && (key.isShiftDown() || key.isAltDown())) {
                if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                    log.debug("[key-diag] Shift/Alt+Enter branch entered — forwarding ENTER (multi-line split)");
                    log.debug("[key-diag] BEFORE split: lineCount={} text=[{}] caret={}",
                        getLineCount(), getText().replace("\n", "\\n"), getCaretPosition());
                }
                Result result = super.handleKeyStroke(new KeyStroke(KeyType.ENTER));
                if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                    log.debug("[key-diag] AFTER split: lineCount={} text=[{}] caret={} result={}",
                        getLineCount(), getText().replace("\n", "\\n"), getCaretPosition(), result);
                }
                return result;
            }
            if (key.getKeyType() != KeyType.ENTER || key.isShiftDown()) return null;
            if (paste.deferSubmitIfPending()) return Result.HANDLED;
            if (guiInputBatchDepth > 0 && plainInputCharsThisBatch > 0) {
                // Unbracketed paste flood: this ENTER shares one PTY drain with
                // pasted text (tmux paste-buffer turns \n into \r; CRLF
                // clipboards send raw \r), so it may be a paste newline rather
                // than a submit — official 197 discriminates by what the stdin
                // chunk contains, not by which drain delivered it. Swallow it
                // for now: further input in this batch materializes it as a
                // line split (paste newline); the batch end submits it instead
                // when nothing followed (a terminal one-line submit — the TTY
                // driver coalesces those for fast typists and scripted drivers
                // alike).
                pendingBatchEnter = true;
                return Result.HANDLED;
            }

            String text = getText();
            if (KEY_DIAGNOSTICS && log.isDebugEnabled()) {
                log.debug("[key-diag] Enter submit branch: lineCount={} text=[{}]",
                    getLineCount(), text.replace("\n", "\\n"));
            }
            String submitText = prependModePrefix(text.trim());
            if (footer.taskNavigation().isViewing()) {
                footer.taskNavigation().injectViewed(submitText);
                suppressDraftUndoRecording();
                draftUndo.clear();
                setText("");
                pastedContent.clear();
                firePastedContentsChange();
                resetMode();
                return Result.HANDLED;
            }
            // Image-only input is intentionally submit-able.
            if ((!StringUtils.isBlank(submitText) || !pastedContent.isEmpty()) && actions != null) {
                actions.submit(submitText);
                suppressDraftUndoRecording();
                draftUndo.clear();
                setText("");
                pastedContent.clear();
                firePastedContentsChange();
                resetMode();
            }
            return Result.HANDLED;
        }

        /**
         * Escape priority: loading cancel, idle non-empty double-clear, then
         * idle empty double-open-selector. Returning null is reserved solely
         * for a non-Escape key.
         */
        private Result tryHandleEscapeKeyStroke(KeyStroke key) {
            if (key.getKeyType() != KeyType.ESCAPE) return null;
            if (isLoading) {
                if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                hintBar.cancelTemporary();
                escOnce = false;
                escEmptyFirst = false;
                if (actions != null) actions.cancel();
                return Result.HANDLED;
            }

            // Original priority: recover queued human prompts before either
            // non-empty double-clear or empty double-Esc message selection.
            if (popEditableQueuedCommands()) return Result.HANDLED;

            String text = getText();
            if (!StringUtils.isBlank(text)) {
                escEmptyFirst = false;
                if (!escOnce) {
                    escOnce = true;
                    if (escTimer != null) escTimer.cancel(false);
                    escTimer = ESC_SCHEDULER.schedule(() -> {
                        escOnce = false;
                        escEmptyFirst = false;
                        escTimer = null;
                    }, DOUBLE_PRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    showTemporaryHint("Esc again to clear", LanternaTheme.welcomeDim(), HINT_TIMEOUT_MS);
                } else {
                    if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                    hintBar.cancelTemporary();
                    escOnce = false;

                    historyController.addEntry(text, System.getProperty("user.dir"));
                    setText("");
                    hideSuggestions();
                    resetMode();
                    resetHistory();
                    updateHint();
                    fireQueryChange();
                }
                return Result.HANDLED;
            }

            if (!escOnce || !escEmptyFirst) {
                if (hasMessages != null && !hasMessages.getAsBoolean()) return Result.HANDLED;
                escOnce = true;
                escEmptyFirst = true;
                if (escTimer != null) escTimer.cancel(false);
                escTimer = ESC_SCHEDULER.schedule(() -> {
                    escOnce = false;
                    escEmptyFirst = false;
                    escTimer = null;
                }, DOUBLE_PRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } else {
                if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                escOnce = false;
                escEmptyFirst = false;
                if (actions != null) actions.showMessageSelector();
            }
            return Result.HANDLED;
        }

        /** Any non-Escape key cancels a pending double-Escape gate. */
        private void resetDoubleEscapeGateAfterNonEscapeKey() {
            if (!escOnce) return;
            escOnce = false;
            if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
        }

        /** Inserts the deferred separator after an image chip before forwarding printable input. */
        private void insertLazySpaceAfterChip(KeyStroke key) {
            boolean isPrintable = key.getKeyType() == KeyType.CHARACTER
                && key.getCharacter() != null
                && key.getCharacter() != ' '
                && !key.isCtrlDown() && !key.isAltDown();
            if (pastedContent.consumeLazySpace(isPrintable)) {
                super.handleKeyStroke(new KeyStroke(' ', false, false));
            }
        }

        /** Enters bash mode when an unmodified leading {@code !} is typed. */
        private Result tryEnterBashMode(KeyStroke key) {
            if (key.getKeyType() != KeyType.CHARACTER
                    || key.getCharacter() == null
                    || key.getCharacter() != '!'
                    || key.isCtrlDown()
                    || key.isAltDown()
                    || !textBox.getText().isEmpty()
                    || modeOverride != null) {
                return null;
            }
            modeOverride = Mode.BASH;
            updateMode();
            fireQueryChange();
            return Result.HANDLED;
        }

        /** Final TextBox fallback, including the panel's mode/query notifications. */
        private Result handleDefaultTextBoxKeyStroke(KeyStroke key) {
            Result result = super.handleKeyStroke(key);
            // Ordinary plain-character input is already guaranteed NORMAL by
            // canUsePlainCharacterFastPath(). Avoid re-reading the complete
            // prompt merely to prove the mode did not change on every byte of
            // a PTY burst. All fallback keys retain the full mode transition.
            if (!canUsePlainCharacterFastPath(key)) updateMode();
            fireQueryChange();
            return result;
        }

        // ── Readline helpers ────────────────────────────────────────────
        // Pure cursor/word/kill-yank editing lives in ReadlineEngine (see
        // the `engine` field). The methods below stay in the panel because
        // they depend on panel state (transcript / EOF / history / overlay
        // actions) — they delegate to the engine only for editing.

        /** Ctrl+E: end-of-line (default) or transcript show-all when in transcript mode. */
        private Result rl_ctrlE() {
            if (isTranscriptMode && actions != null) {
                actions.transcriptShowAll();
                return Result.HANDLED;
            }
            return engine.end();
        }
        private Result rl_historyUp() {
            int caret = caretCol();
            int moved = textLayout.moveVertically(caret, -1);
            if (moved != caret) {
                TextBoxOffsetAdapter.setOffset(textBox, moved);
                fireQueryChange();
                return Result.HANDLED;
            }
            if (popEditableQueuedCommands()) return Result.HANDLED;
            return historyController.up();
        }
        private Result rl_historyDown() {
            int caret = caretCol();
            int moved = textLayout.moveVertically(caret, 1);
            if (moved != caret) {
                TextBoxOffsetAdapter.setOffset(textBox, moved);
                fireQueryChange();
                return Result.HANDLED;
            }
            return historyDownOrEnterFooter();
        }

        /**
         * Ctrl+L — redraw screen (clears visual artifacts).
         */
        private Result rl_redrawScreen() {
            if (actions != null) actions.redrawScreen();
            return Result.HANDLED;
        }

        /**
         * Ctrl+O — toggle transcript mode.
         */
        private Result rl_toggleTranscript() {
            if (actions != null) actions.toggleTranscript();
            return Result.HANDLED;
        }

        /**
         * Ctrl+G — open external editor.
         */
        private Result rl_externalEditor() {
            if (actions != null) actions.externalEditor();
            return Result.HANDLED;
        }

        /**
         * Ctrl+S — push/pop the current prompt.
         */
        private Result rl_stash() {
            String input = getText();
            if (input.trim().isEmpty() && stashedPrompt != null) {
                suppressDraftUndoRecording();
                StashedPrompt restored = stashedPrompt;
                stashedPrompt = null;
                textBox.setText(restored.text());
                moveCaretTo(Math.min(restored.cursorOffset(), restored.text().length()));
                restorePastedContents(restored.pastedContents());
                updateMode();
                fireQueryChange();
                invalidate();
            } else if (!input.trim().isEmpty()) {
                suppressDraftUndoRecording();
                stashedPrompt = new StashedPrompt(input, caretCol(),
                    pastedContent.snapshot());
                textBox.setText("");
                moveCaretTo(0);
                restorePastedContents(Map.of());
                updateMode();
                fireQueryChange();
                invalidate();
                if (actions != null) actions.stash();
            }
            return Result.HANDLED;
        }

        /**
         * Ctrl+_ — restore the previous prompt draft.
         */
        private void rl_undo() {
            DraftUndoBuffer.Snapshot previous = draftUndo.undo();
            if (previous == null) return;
            suppressDraftUndoRecording();
            modeOverride = previous.modeOverride();
            textBox.setText(previous.text());
            TextBoxOffsetAdapter.setOffset(textBox, previous.cursorOffset());
            restorePastedContents(previous.pastedContents());
            updateMode();
            fireQueryChange();
            invalidate();
        }


        private Result rl_historySearch() {
            if (actions != null && actions.openHistorySearch()) {
                return Result.HANDLED;
            }
            return historyController.toggleSearch();
        }

        private Result rl_ctrlD() {
            String text = getText();
            if (text.isEmpty()) {

                // Ctrl+D through the double-press exit gate. Request
                // cancellation is owned by Ctrl+C/Escape, not EOF.
                if (actions != null) {
                    actions.exitOnEmptyEof();
                }
            } else {
                // Delete char under cursor
                super.handleKeyStroke(new KeyStroke(KeyType.DELETE, false, false));
                fireQueryChange();
            }
            return Result.HANDLED;
        }

        /** Ctrl+V — unified clipboard paste. */
        private Result rl_imagePaste() {
            paste.handleClipboardPaste();
            return Result.HANDLED;
        }

        // ── Resolver-driven dispatch (opt-in keybinding customization) ──

        /**
         * Resolve {@code key} against the user keybinding store (Chat + Global contexts) and fire the
         * matched action.
         */
        private Result dispatchViaResolver(KeyStroke key) {
            return dispatchViaResolver(key, List.of("Chat", "Global"),
                this::dispatchChatAction);
        }

        /**
         * Resolve one key against an explicit active-context set. The dispatcher
         * owns chord state; this prompt-specific bridge only maps a resolved
         * action back to its UI policy and preserves deliberate fall-through for
         * submit/cancel.
         */
        private Result dispatchViaResolver(KeyStroke key, List<String> contexts,
                                           Function<String, Boolean> dispatcher) {
            ContextKeybindingDispatcher.Result resolved =
                keybindingDispatcher.resolve(contexts, key);
            return switch (resolved) {
                case ContextKeybindingDispatcher.Result.Action(String action) -> {
                    // voice:pushToTalk needs the accumulated burst state to
                    // distinguish a sustained hold (terminal auto-repeat) from a
                    // single tap before forwarding to the reserved voice port.
                    if (Strings.CS.equals(action, "voice:pushToTalk")) {
                        yield voicePushToTalk();
                    }
                    yield Boolean.TRUE.equals(dispatcher.apply(action)) ? Result.HANDLED : null;
                }
                case ContextKeybindingDispatcher.Result.Consumed() -> Result.HANDLED;
                case ContextKeybindingDispatcher.Result.None() -> null;
            };
        }

        /**
         * Push-to-talk fast path for the bound {@code voice:pushToTalk} action.
         * <p>
         * A terminal has no distinct key-release events, so a held space
         * surfaces as a stream of repeated single-space events which we
         * accumulate across the {@link #PTT_RESET_WINDOW_MS} burst window (see
         * the reserved-voice state above). Once the accumulation crosses
         * {@link #PTT_HOLD_THRESHOLD} the burst is classified as a HOLD; a
         * single tap stays short of the floor and is a plain space. We forward
         * the classification to the (currently no-op) voice port, which
         * consumes the key only while recording is active; otherwise — and for
         * taps — the space falls through to the input so normal typing is
         * unaffected.
         */
        private Result voicePushToTalk() {
            boolean held = accumulatePushToTalk();
            boolean consume = actions != null && actions.handlePushToTalk(held);
            return consume ? Result.HANDLED : null;
        }

        private boolean accumulatePushToTalk() {
            long now = System.currentTimeMillis();
            if (now - pttLastEventMs > PTT_RESET_WINDOW_MS) {
                // The previous burst decayed; this is the first event of a new one.
                pttAccumulated = 0;
            }
            pttAccumulated++;
            pttLastEventMs = now;
            return pttAccumulated >= PTT_HOLD_THRESHOLD;
        }

        private boolean dispatchAutocompleteOrChatAction(String action) {
            return switch (action) {
                case "autocomplete:previous" -> { suggestions.moveUp(); yield true; }
                case "autocomplete:next" -> { suggestions.moveDown(); yield true; }
                case "autocomplete:accept" -> { suggestions.accept(); yield true; }
                case "autocomplete:dismiss" -> { hideSuggestions(); yield true; }
                default -> dispatchChatAction(action);
            };
        }

        private boolean dispatchFooterOrChatAction(String action) {
            if (Strings.CS.startsWith(action, "footer:")) {
                return footer.dispatchFooterAction(action);
            }
            return dispatchChatAction(action);
        }

        private boolean dispatchHistorySearchOrGlobalAction(String action) {
            if (Strings.CS.startsWith(action, "historySearch:")) {
                boolean consumed = historyController.handleSearchAction(action);
                if (!consumed && Strings.CS.equals("historySearch:execute", action)) {
                    // The action means execute regardless of which physical key
                    // the user bound to it. Re-enter after clearing search state
                    // with a canonical Enter so the normal submit path runs.
                    PromptTextBox.this.handleKeyStroke(new KeyStroke(KeyType.ENTER));
                    return true;
                }
                return consumed;
            }
            return dispatchChatAction(action);
        }

        private boolean dispatchMessageActionsAction(String action) {
            switch (action) {
                case "messageActions:prev" -> {
                    if (actions != null) actions.messageActionsPrev();
                }
                case "messageActions:next" -> {
                    if (actions != null) actions.messageActionsNext();
                }
                case "messageActions:prevUser" -> {
                    if (actions != null) actions.messageActionsPrevUser();
                }
                case "messageActions:nextUser" -> {
                    if (actions != null) actions.messageActionsNextUser();
                }
                case "messageActions:top" -> {
                    if (actions != null) actions.messageActionsTop();
                }
                case "messageActions:bottom" -> {
                    if (actions != null) actions.messageActionsBottom();
                }
                case "messageActions:escape" -> {
                    if (actions != null) actions.messageActionsEscape();
                }
                case "messageActions:ctrlc" -> {
                    if (actions != null) actions.messageActionsForceExit();
                }
                case "messageActions:enter" -> {
                    if (actions != null) actions.messageActionsEdit();
                }
                case "messageActions:c" -> {
                    if (actions != null) actions.messageActionsCopy();
                }
                case "messageActions:p" -> {
                    if (actions != null) actions.messageActionsCopyPrimaryInput();
                }
                default -> { return false; }
            }
            return true;
        }

        /**
         * Fire a resolved Chat/Global action. Returns {@code true} if the key was
         * consumed. Enter/Escape ({@code chat:submit}/{@code chat:cancel}) are
         * intentionally NOT consumed here — they are returned so the existing
         * submit/cancel logic (teammate injection, image-only submit, double-press
         * timers) runs. Every other known action routes to the same method the
         * hardcoded switch would have called, so default behaviour is unchanged;
         * unknown actions are consumed (no-op) so they don't leak into typed input.
         */
        private boolean dispatchChatAction(String action) {
            switch (action) {
                case "chat:submit", "chat:cancel" -> { return false; }
                case "history:previous"      -> { rl_historyUp(); return true; }
                case "history:next"          -> { rl_historyDown(); return true; }
                case "chat:undo"             -> { rl_undo(); return true; }
                case "chat:externalEditor"   -> { rl_externalEditor(); return true; }
                case "chat:stash"            -> { rl_stash(); return true; }
                case "chat:imagePaste"       -> { rl_imagePaste(); return true; }
                case "app:redraw"            -> { rl_redrawScreen(); return true; }
                case "app:toggleTranscript"  -> { rl_toggleTranscript(); return true; }
                case "app:toggleTodos"       -> { if (actions != null) actions.toggleTodos(); return true; }
                case "chat:thinkingToggle"   -> { if (actions != null) actions.toggleThinking(); return true; }
                case "chat:modelPicker"      -> { if (actions != null) actions.openModelPicker(); return true; }
                case "app:exit"              -> { rl_ctrlD(); return true; }
                case "history:search"        -> { rl_historySearch(); return true; }
                case "chat:cycleMode"        -> { cyclePermissionMode(); return true; }
                case "chat:killAgents"       -> { handleKillAgents(); return true; }
                case "chat:fastMode" -> {
                    if (actions != null) actions.toggleFastMode();
                    return true;
                }
                case "app:toggleTeammatePreview" -> {
                    log.debug("[keybindings] no-op for unsupported action: {}", action);
                    return true;
                }
                default -> {
                    log.debug("[keybindings] unsupported action: {}", action);
                    return true;
                }
            }
        }


        private void handleKillAgents() {
            boolean hasRunningAgents = footer.taskNavigation().registry().listBackground().stream()
                .anyMatch(t -> t.type() == TaskType.LOCAL_AGENT
                    && t.status() == TaskStatus.RUNNING);
            if (!hasRunningAgents) {
                lastKillAgentsPressMs = 0;
                showTemporaryHint("No background agents running",
                    LanternaTheme.welcomeDim(), 2000);
                return;
            }

            long now = System.currentTimeMillis();
            if (lastKillAgentsPressMs != 0
                    && now - lastKillAgentsPressMs <= KILL_AGENTS_CONFIRM_WINDOW_MS) {
                lastKillAgentsPressMs = 0;
                if (actions != null) actions.killBackgroundAgents();
                return;
            }

            lastKillAgentsPressMs = now;
            String shortcut = keybindingsStore.currentResolver()
                .getBindingDisplayText("chat:killAgents", "Chat");
            if (StringUtils.isBlank(shortcut)) shortcut = "ctrl+x ctrl+k";
            showTemporaryHint("Press " + shortcut + " again to stop background agents",
                LanternaTheme.welcomeDim(), KILL_AGENTS_CONFIRM_WINDOW_MS);
        }

    }


    // ── Layout ──────────────────────────────────────────────────────────────


    private void refreshInputHighlights() {
        List<HighlightedTextBox.Highlight> next = new ArrayList<>();
        List<BtwTriggers.Trigger> btwTriggers = BtwTriggers.find(currentText);
        if (!btwTriggers.isEmpty()) {
            next.add(new HighlightedTextBox.Highlight(
                btwTriggers.getFirst().start(), btwTriggers.getFirst().end(),
                BtwTriggers.highlightColor(), false, 15));
        }
        if (historySearchHighlight != null
                && historySearchHighlight.start() >= 0
                && historySearchHighlight.end() <= currentText.length()) {
            next.add(historySearchHighlight);
        }
        currentHighlights = List.copyOf(next);
    }


    private void refreshTextLayout() {
        PromptTextLayout next = PromptTextLayout.create(currentText, inputContentColumns);
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
    public void setActions(InputActions actions) { this.actions = actions; }

    /** Installs the opt-in keybinding resolver. Null when customization is disabled. */
    public void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        keybindingDispatcher.setStore(store);
    }

    /**
     * Routes keyboard focus back to the text box. Used after a transient
     * overlay (e.g. the inline permission prompt) closes — we want the next
     * keystroke to go into the prompt, not the now-empty overlay region.
     */
    public void takeFocus() {
        if (textBox != null) textBox.takeFocus();
    }

    public void setHasMessages(BooleanSupplier supplier) { this.hasMessages = supplier; }

    public void setIsLoading(boolean loading) {
        boolean changed = this.isLoading != loading;
        this.isLoading = loading;
        if (changed) {
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
        if (guiInputBatchDepth == 0) {
            PromptTextBox prompt = (PromptTextBox) textBox;
            prompt.plainInputCharsThisBatch = 0;
            prompt.pendingBatchEnter = false;
            prompt.batchStartCaret = caretCol();
            prompt.batchStartLength = currentText.length();
        }
        guiInputBatchDepth++;
    }

    /** Accepts a decoded printable run when the prompt is the active input owner. */
    public boolean handleGuiTextBatch(Interactable focused, String text) {
        if (guiInputBatchDepth == 0
                || focused != textBox
                || textBox.getInputFilter() != null) {
            return false;
        }
        return ((PromptTextBox) textBox).bufferPlainText(text);
    }

    boolean handleGuiTextBatchForTest(String text) {
        return guiInputBatchDepth > 0
            && ((PromptTextBox) textBox).bufferPlainText(text);
    }

    /** Accepts a decoded Backspace run when the prompt is the active input owner. */
    public boolean handleGuiBackspaceBatch(Interactable focused, int count) {
        if (guiInputBatchDepth == 0 || focused != textBox || textBox.getInputFilter() != null) {
            return false;
        }
        PromptTextBox prompt = (PromptTextBox) textBox;
        if (!prompt.canBufferPlainBackspaces(count)) return false;
        prompt.flushBufferedPlainInput(false);
        prompt.bufferPlainBackspaces(count);
        return true;
    }

    boolean handleGuiBackspaceBatchForTest(int count) {
        PromptTextBox prompt = (PromptTextBox) textBox;
        if (guiInputBatchDepth == 0 || !prompt.canBufferPlainBackspaces(count)) return false;
        prompt.flushBufferedPlainInput(false);
        prompt.bufferPlainBackspaces(count);
        return true;
    }

    /** Publishes the final prompt state after Lanterna drains the PTY queue. */
    public void endGuiInputBatch() {
        if (guiInputBatchDepth == 0) return;
        if (guiInputBatchDepth == 1) {
            // Replace or close an already-visible dropdown before the first
            // input frame so it can never be paired with a newer prompt. When
            // no dropdown exists yet, commit the prompt first and let the same
            // GUI cycle build/show new suggestions afterwards; this preserves
            // immediate echo for a whole `/config` terminal write. File
            // discovery remains asynchronous inside SuggestionController.
            boolean replaceVisibleSuggestions = suggestions.isVisible();
            PromptTextBox prompt = (PromptTextBox) textBox;
            if (prompt.pendingBatchEnter) {
                // The batch ended right after the swallowed ENTER — nothing
                // followed it. Official folds a whole stdin chunk including its
                // trailing newline, so a batch that is a flood folds and the
                // swallowed ENTER goes into the chip; anything smaller was a
                // terminal one-line submit, so commit and submit it.
                prompt.pendingBatchEnter = false;
                prompt.flushBufferedPlainInput(replaceVisibleSuggestions);
                prompt.flushBufferedBackspaces(replaceVisibleSuggestions);
                prompt.plainInputCharsThisBatch = 0;
                if (!prompt.foldUnbracketedPasteFloodIntoChip(prompt.batchText(), "\n")) {
                    prompt.tryHandleSubmitKeyStroke(new KeyStroke(KeyType.ENTER));
                }
                guiInputBatchDepth--;
                return;
            }
            prompt.flushBufferedPlainInput(replaceVisibleSuggestions);
            prompt.flushBufferedBackspaces(replaceVisibleSuggestions);
            if (prompt.plainInputCharsThisBatch > 0) {
                prompt.plainInputCharsThisBatch = 0;
                prompt.foldUnbracketedPasteFloodIntoChip(prompt.batchText());
            }
        }
        guiInputBatchDepth--;
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
                    textBox.getCaretPosition().getColumn(), currentText.length()));
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

        suppressDraftUndoRecording();
        mode = Mode.NORMAL;
        modeOverride = null;
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
        return mode == Mode.BASH;
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
        return Strings.CS.endsWith(currentText, " ") ? hint : " " + hint;
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

    public String getText() { return currentText; }

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
        modeOverride = InputModes.overrideFromPrefix(text);
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
        escOnce           = false;
        modeOverride      = null;  // clear any mode from history entry

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
        // single-char and matters for modeOverride, but the mode char '!'
        // is never a newline itself.
        Mode entryMode = InputModes.overrideFromPrefix(display);
        String body = InputModes.stripPrefix(display);
        modeOverride = entryMode;
        // Restore FIRST: restorePastedContents() clears the whole map, which would
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

    private record StashedPrompt(
        String text,
        int cursorOffset,
        Map<Integer, PastedContent> pastedContents
    ) {}


    private void fireQueryChange() {

        // PromptInput useEffect([input, setPastedContents]).
        if (!pastedContent.isEmpty()) chips.pruneOrphanedImages();
        if (actions == null) return;

        Consumer<Runnable> invoker = guiInvoker;
        if (invoker == null || (guiInputBatchDepth == 0 && isOnGuiThread())) {
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
        if (currentActions != null) currentActions.queryChanged(currentText, caretCol());
    }

    /** Notify InputPanel when transcript mode is active (gates Ctrl+E routing). */
    public void setTranscriptMode(boolean active) {
        this.isTranscriptMode = active;
    }

    /** Set message actions active state (intercepts keys for navigation). */
    public void setMessageActionsActive(boolean active) {
        this.messageActionsActive = active;
        if (!active) messageActionsHint = "";
        updateHint();
    }

    /** Update the action list shown in the pinned footer while browsing messages. */
    public void setMessageActionsHint(String hint) {
        messageActionsHint = hint == null ? "" : hint;
        if (messageActionsActive) updateHint();
    }

    // ── Mode ─────────────────────────────────────────────────────────────────

    /**
     * Returns the text to pass to {@code actions.submit}, prepending the mode prefix when {@link
     * #modeOverride} is active (text shown in box has no prefix).
     */
    private String prependModePrefix(String text) {
        return InputModes.prependPrefix(text, modeOverride);
    }

    private void updateMode() {
        String text = vim.isEnabled() ? vim.buffer() : currentText;
        if (modeOverride == null && mode == Mode.NORMAL
                && (text.isEmpty() || text.charAt(0) != '!')) {
            return;
        }
        Mode prefixMode = InputModes.fromPrefix(text);
        Mode newMode;
        if (modeOverride != null) {
            // modeOverride is set when a history entry had its prefix stripped.

            // until resetMode (Escape) or submit. The text in the box has no prefix.
            // Only clear override if user explicitly types a new mode prefix at position 0.
            if (prefixMode != Mode.NORMAL) {
                modeOverride = null;  // user typed "!" naturally → text-based detection takes over
                newMode = prefixMode;
            } else {
                newMode = modeOverride;  // keep override regardless of text content
            }
        } else {
            newMode = prefixMode;
        }
        if (newMode != mode) {
            mode = newMode;
            updatePromptColor();
            updateHint();
        }
    }

    private void resetMode() {
        mode = Mode.NORMAL;
        modeOverride = null;
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
        dividers.setBashMode(mode == Mode.BASH);
        TextColor color;
        if (isLoading) {
            color = LanternaTheme.welcomeDim();  // dimColor=true wins
        } else if (mode == Mode.BASH) {
            color = LanternaTheme.bashBorder();
        } else {
            color = null;  // Use the terminal's default foreground.
        }
        promptLabel.setForegroundColor(color);
        // Update prompt symbol: bash uses "! ", others use "❯ "
        promptLabel.setText(mode == Mode.BASH ? "! " : Figures.POINTER + " ");
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

    private TextBox.Result historyDownOrEnterFooter() {
        if (historyController.atBottom()) {
            // The ≡ projects button is the leftmost footer control, so the
            // first ↓ from the prompt selects it; the next ↓/→ resumes the
            // released chain via selectFirstFooterStopAfterProjectsButton().
            footer.enterFromPrompt();
            return TextBox.Result.HANDLED;
        }
        return historyController.down();
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
        if (currentText.isEmpty()) return true;

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
        leftArrowOpensAgents = enabled != null ? enabled : () -> true;
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
    Mode modeForTest() { return mode; }
    boolean plainCharacterFastPathForTest(KeyStroke key) {
        return ((PromptTextBox) textBox).canUsePlainCharacterFastPath(key);
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
            messageActionsActive,
            messageActionsHint,
            permMode,
            isLoading,
            leftArrowOpensAgents.getAsBoolean()), footer);
        footer.refreshTasksPill();
        vim.refreshLabel();
    }
}

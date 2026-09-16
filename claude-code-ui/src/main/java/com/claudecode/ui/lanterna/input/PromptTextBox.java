package com.claudecode.ui.lanterna.input;

import com.claudecode.core.message.PastedContent;
import com.claudecode.core.paste.PastedRefParser;
import com.claudecode.keybindings.UserKeybindingsStore;
import com.claudecode.tools.tasks.TaskStatus;
import com.claudecode.tools.tasks.TaskType;
import com.claudecode.ui.lanterna.components.HighlightedTextBox;
import com.claudecode.ui.lanterna.components.HighlightedTextBox.Highlight;
import com.claudecode.ui.lanterna.input.InputPanel.Mode;
import com.claudecode.ui.lanterna.theme.LanternaTheme;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.TextColor;
import com.googlecode.lanterna.gui2.TextBox;
import com.googlecode.lanterna.input.FocusEventKeyStroke;
import com.googlecode.lanterna.input.KeyStroke;
import com.googlecode.lanterna.input.KeyType;
import com.googlecode.lanterna.input.MouseAction;
import com.googlecode.lanterna.input.PasteKeyStroke;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The multi-line prompt text box and its ordered key pipeline: terminal focus/mouse events,
 * the plain-character fast path, the modal pre-editor stages (message actions, teammate and
 * footer navigation, history search, paste / mode cycle, autocomplete, configured
 * keybindings), then vim, readline shortcuts, chip-aware editing, history navigation,
 * submit, the double-Escape gate and bash-mode entry. Also owns the GUI input batch that
 * folds an unbracketed paste flood into a chip, the readline {@code rl_*} actions, the
 * resolver-driven {@code chat:*} / {@code messageActions:*} dispatch and push-to-talk hold
 * detection.
 *
 * <p>Collaborators arrive through {@link Collaborators}; shared draft state through
 * {@link PromptState}; everything else it needs from the panel is the small {@link Host}.
 * The stage order in {@link #handleKeyStrokeRouted} is observable UI behaviour, not an
 * implementation detail.
 *
 * <ul>
 *   <li>{@code src/components/PromptInput/PromptInput.tsx} — {@code handleInput} ordering:
 *       submit on Enter, Shift/Alt+Enter newline, {@code !} bash-mode entry, Escape priority
 *       (cancel while loading, double-Esc clear, double-Esc message selector), ↑/↓ moving
 *       within multiline text before history, Shift+↑ message actions, ← on an empty prompt
 *       opening agents, Ctrl+B background tasks, Ctrl+S stash, Ctrl+_ undo; and paste
 *       discrimination — a stdin chunk carrying text plus a terminator is a paste flood and
 *       folds into a chip whatever drain delivered it, while a lone Enter submits.</li>
 *   <li>{@code src/hooks/useTextInput.ts} — Ctrl/Meta readline mapping (a/e/f/b/k/u/w/y/d,
 *       Alt+b/f/d/y) and the modifier-arrow aliases some terminals send.</li>
 *   <li>{@code src/keybindings/} — context-ordered resolution ({@code MessageActions},
 *       {@code Footer}, {@code HistorySearch}, {@code Autocomplete}, {@code Chat},
 *       {@code Global}) with {@code chat:submit} / {@code chat:cancel} deliberately left to
 *       the native handlers so their stateful flows still run.</li>
 *   <li>2.1.197 bundle voice handler — push-to-talk hold detection from terminal
 *       auto-repeat: 120 ms reset window, 5-event hold threshold.</li>
 *   <li>{@code chat:killAgents} — double-press confirmation within 3 s before stopping
 *       background agents.</li>
 * </ul>
 */
final class PromptTextBox extends HighlightedTextBox {

    /** Panel actions the pipeline needs beyond its injected collaborators. */
    interface Host {
        int caretCol();
        PromptTextLayout textLayout();
        int terminalRows();
        /** Programmatic replace with chip truncation, caret at end, mode + query refresh. */
        void setText(String text);
        void fireQueryChange();
        void deliverQueryChangeImmediately();
        void pastedContentsChanged();
        void updateMode();
        void resetMode();
        void resetHistory();
        void refreshHint();
        void showTemporaryHint(String text, TextColor color, long timeoutMs);
        void cancelTemporaryHint();
        void cyclePermissionMode();
        boolean popEditableQueuedCommands();
    }

    /** The prompt components the pipeline routes keys to. */
    record Collaborators(
        PromptPastedContentController pastedContent,
        PromptChipEditor chips,
        PromptPasteHandler paste,
        PromptVimAdapter vim,
        PromptSuggestionBridge suggestions,
        InputHistoryController historyController,
        PromptFooter footer
    ) {}

    private static final Logger log = LoggerFactory.getLogger(PromptTextBox.class);
    /** High-volume key protocol tracing is opt-in even when general DEBUG is enabled. */
    private static final boolean KEY_DIAGNOSTICS =
        Boolean.getBoolean("claude.ui.keyDiagnostics");

    static final long HINT_TIMEOUT_MS = 1000;
    private static final long DOUBLE_PRESS_TIMEOUT_MS = 800;

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

    private final Host host;
    private final PromptState state;
    private final ScheduledExecutorService scheduler;
    private final PromptPastedContentController pastedContent;
    private final PromptChipEditor chips;
    private final PromptPasteHandler paste;
    private final PromptVimAdapter vim;
    private final PromptSuggestionBridge suggestions;
    private final InputHistoryController historyController;
    private final PromptFooter footer;
    /**
     * Pure Emacs/readline editing layer (cursor/word motion + kill/yank ring). Captures
     * {@code super::handleKeyStroke} for faithful cross-row caret motion.
     */
    private final ReadlineEngine engine;
    /** Shared resolver bridge owns pending-chord state and timeout semantics. */
    private final ContextKeybindingDispatcher keybindingDispatcher =
        new ContextKeybindingDispatcher();

    /** The panel's single outward action port; null in headless tests. */
    private InputActions actions;
    /**
     * Opt-in keybinding store (gate on). When non-null and enabled, matched Chat/Global keys
     * are routed through {@link #dispatchViaResolver} instead of the hardcoded readline
     * switch. Null in headless / when customization is disabled.
     */
    private UserKeybindingsStore keybindingsStore;

    // ── Double-Escape gate ────────────────────────────────────────────────
    private boolean           escOnce         = false;
    private ScheduledFuture<?> escTimer        = null;
    // True when input was EMPTY on the first Esc (double-Esc → MessageSelector vs. clear).
    private boolean           escEmptyFirst   = false;

    private StashedPrompt stashedPrompt;

    private record StashedPrompt(
        String text,
        int cursorOffset,
        Map<Integer, PastedContent> pastedContents
    ) {}

    PromptTextBox(TerminalSize preferredSize, Supplier<List<Highlight>> highlightSupplier,
                  Host host, PromptState state, ScheduledExecutorService scheduler,
                  Collaborators collaborators) {
        super(preferredSize, TextBox.Style.MULTI_LINE, highlightSupplier);
        this.host = host;
        this.state = state;
        this.scheduler = scheduler;
        this.pastedContent = collaborators.pastedContent();
        this.chips = collaborators.chips();
        this.paste = collaborators.paste();
        this.vim = collaborators.vim();
        this.suggestions = collaborators.suggestions();
        this.historyController = collaborators.historyController();
        this.footer = collaborators.footer();
        this.engine = new ReadlineEngine(this, super::handleKeyStroke, host::fireQueryChange);
    }

    void setActions(InputActions actions) { this.actions = actions; }

    void setKeybindingsStore(UserKeybindingsStore store) {
        this.keybindingsStore = store;
        keybindingDispatcher.setStore(store);
    }

    /** A programmatic history reset also forgets a pending first Escape. */
    void resetDoubleEscapeGate() {
        escOnce = false;
    }

    private DraftUndoBuffer.Snapshot draftSnapshot() {
        return state.draftSnapshot(host.caretCol(), pastedContent.snapshot());
    }

    private void moveCaretTo(int offset) {
        // setCaretPosition bypasses the input event chain; synthetic HOME/ARROW keys
        // would re-enter handleKeyStroke and recurse through the chip hop.
        TextBoxOffsetAdapter.setOffset(this, offset);
    }

    private void restorePastedContents(Map<Integer, PastedContent> restored) {
        pastedContent.restore(restored);
        host.pastedContentsChanged();
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
        String textBefore = state.text();
        int cursorBefore = host.caretCol();
        Mode modeBefore = state.modeOverride();
        Map<Integer, PastedContent> pastedBefore = pastedContent.isEmpty()
            ? Map.of() : pastedContent.snapshot();
        long suppressionAtEntry = state.undoSuppressionGeneration();
        Result result = handleKeyStrokeRouted(key);
        if (suppressionAtEntry == state.undoSuppressionGeneration()) {
            boolean textChanged = !textBefore.equals(state.text());
            boolean modeChanged = modeBefore != state.modeOverride();
            boolean pastedChanged =
                (!pastedBefore.isEmpty() || !pastedContent.isEmpty()) && !pastedBefore.equals(
                    pastedContent.snapshot());

            // navigation, and coalesces rapid typing for one second.
            if (textChanged || modeChanged || pastedChanged) {
                state.draftUndo().recordDebounced(new DraftUndoBuffer.Snapshot(
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

    boolean canUsePlainCharacterFastPath(KeyStroke key) {
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
            || !getText().isEmpty()
            || state.modeOverride() != null;
    }

    private boolean plainInputStateAllowsDirectEdit() {
        return !suggestions.isVisible() && plainInputStateAllowsBatch();
    }

    private boolean plainInputStateAllowsBatch() {
        return !state.isMessageActionsActive()
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

    // ── GUI input batching (one PTY drain) ────────────────────────────

    /** Nesting guard owned by the GUI host's terminal-drain cycle. */
    private int guiInputBatchDepth;

    boolean isInGuiInputBatch() { return guiInputBatchDepth > 0; }

    /** Starts one terminal-read input batch; called only by the GUI host. */
    void beginGuiInputBatch() {
        if (guiInputBatchDepth == 0) {
            plainInputCharsThisBatch = 0;
            pendingBatchEnter = false;
            batchStartCaret = host.caretCol();
            batchStartLength = state.text().length();
        }
        guiInputBatchDepth++;
    }

    /** Buffers a decoded Backspace run; {@code false} when the prompt state forbids batching. */
    boolean bufferGuiBackspaces(int count) {
        if (!canBufferPlainBackspaces(count)) return false;
        flushBufferedPlainInput(false);
        bufferPlainBackspaces(count);
        return true;
    }

    /** Publishes the final prompt state after Lanterna drains the PTY queue. */
    void endGuiInputBatch() {
        if (guiInputBatchDepth == 0) return;
        if (guiInputBatchDepth == 1) {
            // Replace or close an already-visible dropdown before the first
            // input frame so it can never be paired with a newer prompt. When
            // no dropdown exists yet, commit the prompt first and let the same
            // GUI cycle build/show new suggestions afterwards; this preserves
            // immediate echo for a whole `/config` terminal write. File
            // discovery remains asynchronous inside SuggestionController.
            boolean replaceVisibleSuggestions = suggestions.isVisible();
            if (pendingBatchEnter) {
                // The batch ended right after the swallowed ENTER — nothing
                // followed it. Official folds a whole stdin chunk including its
                // trailing newline, so a batch that is a flood folds and the
                // swallowed ENTER goes into the chip; anything smaller was a
                // terminal one-line submit, so commit and submit it.
                pendingBatchEnter = false;
                flushBufferedPlainInput(replaceVisibleSuggestions);
                flushBufferedBackspaces(replaceVisibleSuggestions);
                plainInputCharsThisBatch = 0;
                if (!foldUnbracketedPasteFloodIntoChip(batchText(), "\n")) {
                    tryHandleSubmitKeyStroke(new KeyStroke(KeyType.ENTER));
                }
                guiInputBatchDepth--;
                return;
            }
            flushBufferedPlainInput(replaceVisibleSuggestions);
            flushBufferedBackspaces(replaceVisibleSuggestions);
            if (plainInputCharsThisBatch > 0) {
                plainInputCharsThisBatch = 0;
                foldUnbracketedPasteFloodIntoChip(batchText());
            }
        }
        guiInputBatchDepth--;
    }

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
        if (bufferedPlainInput.isEmpty()) bufferedInputStart = draftSnapshot();
        bufferedPlainInput.append(character);
    }

    boolean bufferPlainText(String text) {
        if (text == null || text.length() < 2 || !plainInputStateAllowsBatch()) return false;
        boolean hasBufferedPrefix = !bufferedPlainInput.isEmpty()
            || !getText().isEmpty() || state.modeOverride() != null;
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
        if (bufferedPlainInput.isEmpty()) bufferedInputStart = draftSnapshot();
        bufferedPlainInput.append(text);
        plainInputCharsThisBatch += text.length();
        return true;
    }

    private void flushBufferedPlainInput(boolean publishImmediately) {
        if (bufferedPlainInput.isEmpty()) return;
        String insertion = bufferedPlainInput.toString();
        bufferedPlainInput.setLength(0);
        int caret = host.caretCol();
        String before = state.text();
        String merged = before.substring(0, caret) + insertion + before.substring(caret);
        setTextPreservingViewport(merged);
        TextBoxOffsetAdapter.setOffset(this, caret + insertion.length());
        if (historyController.hasHistoryCursor()) historyController.onUserEdit();
        KillRing.INSTANCE.resetAccumulation();
        KillRing.INSTANCE.resetYankState();
        if (bufferedInputStart != null) {
            state.draftUndo().recordDebounced(bufferedInputStart);
            bufferedInputStart = null;
        }
        if (publishImmediately) host.deliverQueryChangeImmediately();
        else host.fireQueryChange();
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
        int inserted = state.text().length() - batchStartLength;
        if (inserted <= 0) return "";
        int caret = host.caretCol();
        if (caret != batchStartCaret + inserted) return "";
        return state.text().substring(batchStartCaret, caret);
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
        if (!PromptPasteTextPolicy.shouldFoldIntoChip(stripped, numLines, host.terminalRows())) {
            return false;
        }
        int pasteId = pastedContent.nextId();
        pastedContent.put(PastedContent.text(pasteId, stripped));
        int caret = host.caretCol();
        int start = caret - batchText.length();
        host.setText(
            state.text().substring(0, start) + state.text().substring(caret));
        TextBoxOffsetAdapter.setOffset(this, start);
        chips.insertAtCursor(PastedRefParser.formatPastedTextRef(pasteId, numLines));
        host.pastedContentsChanged();
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
                || state.isMessageActionsActive()
                || footer.capturesPlainInput()
                || historyController.isSearching()
                || customKeybindingsEnabled()
                || vim.isEnabled()
                || escOnce
                || !pastedContent.isEmpty()
                || state.modeOverride() != null
                || state.text().indexOf('\n') >= 0) {
            return false;
        }
        int caret = bufferedBackspaces == 0 ? host.caretCol() : bufferedBackspaceCaret;
        int end = caret - bufferedBackspaces;
        int start = end - count;
        if (start < 0) return false;
        for (int index = start; index < end; index++) {
            if (Character.isSurrogate(state.text().charAt(index))) return false;
        }
        return true;
    }

    private void bufferPlainBackspace() {
        bufferPlainBackspaces(1);
    }

    private void bufferPlainBackspaces(int count) {
        if (bufferedBackspaces == 0) {
            bufferedBackspaceCaret = host.caretCol();
            bufferedBackspaceStart = draftSnapshot();
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
        String before = state.text();
        setText(before.substring(0, start) + before.substring(caret));
        TextBoxOffsetAdapter.setOffset(this, start);
        if (historyController.hasHistoryCursor()) historyController.onUserEdit();
        KillRing.INSTANCE.resetAccumulation();
        KillRing.INSTANCE.resetYankState();
        if (bufferedBackspaceStart != null) {
            state.draftUndo().recordDebounced(bufferedBackspaceStart);
            bufferedBackspaceStart = null;
        }
        if (publishImmediately) host.deliverQueryChangeImmediately();
        else host.fireQueryChange();
    }

    /**
     * Message-actions overlay is the first modal key surface after terminal
     * focus/mouse events. It consumes every key while active.
     */
    private Result tryHandleMessageActionsKeyStroke(KeyStroke key) {
        if (!state.isMessageActionsActive()) return null;
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
                host.cyclePermissionMode();
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
        if (state.modeOverride() == Mode.BASH) return null;
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
                || state.isLoading() || state.modeOverride() != null || footer.taskNavigation().isViewing()
                || !state.text().isEmpty() || !pastedContent.isEmpty()
                || !state.leftArrowOpensAgents() || actions == null) {
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
        if (state.modeOverride() != Mode.BASH || host.caretCol() != 0) return null;
        KeyType keyType = key.getKeyType();
        boolean ctrlU = key.isCtrlDown() && !key.isAltDown()
            && key.getCharacter() != null
            && Character.toLowerCase(key.getCharacter()) == 'u';
        if (keyType == KeyType.BACKSPACE || keyType == KeyType.DELETE || ctrlU) {
            host.resetMode();
            host.fireQueryChange();
            return Result.HANDLED;
        }
        if (keyType == KeyType.ESCAPE) {
            host.resetMode();
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

    /** Submits the current prompt as if a plain Enter arrived (deferred paste submit). */
    Result submit() {
        return tryHandleSubmitKeyStroke(new KeyStroke(KeyType.ENTER));
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
        String submitText = state.prependModePrefix(text.trim());
        if (footer.taskNavigation().isViewing()) {
            footer.taskNavigation().injectViewed(submitText);
            state.suppressUndoRecording();
            state.draftUndo().clear();
            setText("");
            pastedContent.clear();
            host.pastedContentsChanged();
            host.resetMode();
            return Result.HANDLED;
        }
        // Image-only input is intentionally submit-able.
        if ((!StringUtils.isBlank(submitText) || !pastedContent.isEmpty()) && actions != null) {
            actions.submit(submitText);
            state.suppressUndoRecording();
            state.draftUndo().clear();
            setText("");
            pastedContent.clear();
            host.pastedContentsChanged();
            host.resetMode();
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
        if (state.isLoading()) {
            if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
            host.cancelTemporaryHint();
            escOnce = false;
            escEmptyFirst = false;
            if (actions != null) actions.cancel();
            return Result.HANDLED;
        }

        // Original priority: recover queued human prompts before either
        // non-empty double-clear or empty double-Esc message selection.
        if (host.popEditableQueuedCommands()) return Result.HANDLED;

        String text = getText();
        if (!StringUtils.isBlank(text)) {
            escEmptyFirst = false;
            if (!escOnce) {
                escOnce = true;
                if (escTimer != null) escTimer.cancel(false);
                escTimer = scheduler.schedule(() -> {
                    escOnce = false;
                    escEmptyFirst = false;
                    escTimer = null;
                }, DOUBLE_PRESS_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                host.showTemporaryHint("Esc again to clear", LanternaTheme.welcomeDim(), HINT_TIMEOUT_MS);
            } else {
                if (escTimer != null) { escTimer.cancel(false); escTimer = null; }
                host.cancelTemporaryHint();
                escOnce = false;

                historyController.addEntry(text, System.getProperty("user.dir"));
                setText("");
                suggestions.hide();
                host.resetMode();
                host.resetHistory();
                host.refreshHint();
                host.fireQueryChange();
            }
            return Result.HANDLED;
        }

        if (!escOnce || !escEmptyFirst) {
            if (!state.hasMessages()) return Result.HANDLED;
            escOnce = true;
            escEmptyFirst = true;
            if (escTimer != null) escTimer.cancel(false);
            escTimer = scheduler.schedule(() -> {
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
                || !getText().isEmpty()
                || state.modeOverride() != null) {
            return null;
        }
        state.setModeOverride(Mode.BASH);
        host.updateMode();
        host.fireQueryChange();
        return Result.HANDLED;
    }

    /** Final TextBox fallback, including the panel's mode/query notifications. */
    private Result handleDefaultTextBoxKeyStroke(KeyStroke key) {
        Result result = super.handleKeyStroke(key);
        // Ordinary plain-character input is already guaranteed NORMAL by
        // canUsePlainCharacterFastPath(). Avoid re-reading the complete
        // prompt merely to prove the mode did not change on every byte of
        // a PTY burst. All fallback keys retain the full mode transition.
        if (!canUsePlainCharacterFastPath(key)) host.updateMode();
        host.fireQueryChange();
        return result;
    }

    // ── Readline helpers ────────────────────────────────────────────
    // Pure cursor/word/kill-yank editing lives in ReadlineEngine (see
    // the `engine` field). The methods below stay in the panel because
    // they depend on panel state (transcript / EOF / history / overlay
    // actions) — they delegate to the engine only for editing.

    /** Ctrl+E: end-of-line (default) or transcript show-all when in transcript mode. */
    private Result rl_ctrlE() {
        if (state.isTranscriptMode() && actions != null) {
            actions.transcriptShowAll();
            return Result.HANDLED;
        }
        return engine.end();
    }
    Result rl_historyUp() {
        int caret = host.caretCol();
        int moved = host.textLayout().moveVertically(caret, -1);
        if (moved != caret) {
            TextBoxOffsetAdapter.setOffset(this, moved);
            host.fireQueryChange();
            return Result.HANDLED;
        }
        if (host.popEditableQueuedCommands()) return Result.HANDLED;
        return historyController.up();
    }
    Result rl_historyDown() {
        int caret = host.caretCol();
        int moved = host.textLayout().moveVertically(caret, 1);
        if (moved != caret) {
            TextBoxOffsetAdapter.setOffset(this, moved);
            host.fireQueryChange();
            return Result.HANDLED;
        }
        return historyDownOrEnterFooter();
    }

    private Result historyDownOrEnterFooter() {
        if (historyController.atBottom()) {
            // The ≡ projects button is the leftmost footer control, so the
            // first ↓ from the prompt selects it; the next ↓/→ resumes the
            // released chain via selectFirstFooterStopAfterProjectsButton().
            footer.enterFromPrompt();
            return Result.HANDLED;
        }
        return historyController.down();
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
            state.suppressUndoRecording();
            StashedPrompt restored = stashedPrompt;
            stashedPrompt = null;
            setText(restored.text());
            moveCaretTo(Math.min(restored.cursorOffset(), restored.text().length()));
            restorePastedContents(restored.pastedContents());
            host.updateMode();
            host.fireQueryChange();
            invalidate();
        } else if (!input.trim().isEmpty()) {
            state.suppressUndoRecording();
            stashedPrompt = new StashedPrompt(input, host.caretCol(),
                pastedContent.snapshot());
            setText("");
            moveCaretTo(0);
            restorePastedContents(Map.of());
            host.updateMode();
            host.fireQueryChange();
            invalidate();
            if (actions != null) actions.stash();
        }
        return Result.HANDLED;
    }

    /**
     * Ctrl+_ — restore the previous prompt draft.
     */
    private void rl_undo() {
        DraftUndoBuffer.Snapshot previous = state.draftUndo().undo();
        if (previous == null) return;
        state.suppressUndoRecording();
        state.setModeOverride(previous.modeOverride());
        setText(previous.text());
        TextBoxOffsetAdapter.setOffset(this, previous.cursorOffset());
        restorePastedContents(previous.pastedContents());
        host.updateMode();
        host.fireQueryChange();
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
            host.fireQueryChange();
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
            case "autocomplete:dismiss" -> { suggestions.hide(); yield true; }
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
                handleKeyStroke(new KeyStroke(KeyType.ENTER));
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
            case "chat:cycleMode"        -> { host.cyclePermissionMode(); return true; }
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
            host.showTemporaryHint("No background agents running",
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
        host.showTemporaryHint("Press " + shortcut + " again to stop background agents",
            LanternaTheme.welcomeDim(), KILL_AGENTS_CONFIRM_WINDOW_MS);
    }
}
